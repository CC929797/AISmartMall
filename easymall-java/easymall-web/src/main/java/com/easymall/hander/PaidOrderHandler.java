package com.easymall.hander;

import com.easymall.entity.config.AppConfig;
import com.easymall.entity.enums.LogisticsStatusEnum;
import com.easymall.entity.enums.OrderStatusEnum;
import com.easymall.entity.po.OrderInfo;
import com.easymall.entity.po.OrderLogisticsInfo;
import com.easymall.entity.query.OrderInfoQuery;
import com.easymall.refund.RefundPendingException;
import com.easymall.service.OrderInfoService;
import com.easymall.service.OrderLogisticsInfoService;
import com.easymall.task.BusinessTaskHandler;
import com.easymall.task.TaskDeferredException;
import com.easymall.task.TaskExecution;
import com.easymall.utils.StringTools;
import org.springframework.stereotype.Component;

/** 消费与支付状态同事务入库的事件，演示环境使用已有发货入口，其他环境由管理端履约。 */
@Component
public class PaidOrderHandler implements BusinessTaskHandler {
    private final AppConfig config;
    private final OrderInfoService orders;
    private final OrderLogisticsInfoService logistics;

    public PaidOrderHandler(AppConfig config, OrderInfoService orders, OrderLogisticsInfoService logistics) {
        this.config = config;
        this.orders = orders;
        this.logistics = logistics;
    }

    @Override
    public String taskType() {
        return "ORDER_PAID";
    }

    @Override
    public void handle(TaskExecution task) {
        // 正常环境中已付款订单会出现在管理端待发货列表，消费此事件不会生成模拟运单。
        if (!config.isSimulatedLogistics()) {
            return;
        }
        OrderInfoQuery query = new OrderInfoQuery();
        query.setPayOrderId(task.bizKey());
        for (OrderInfo order : orders.findListByParam(query)) {
            if (!OrderStatusEnum.PAID.getStatus().equals(order.getOrderStatus())
                    && !OrderStatusEnum.PARTIALLY_REFUNDED.getStatus().equals(order.getOrderStatus())) {
                continue;
            }
            OrderLogisticsInfo shipment = logistics.getOrderLogisticsInfoByOrderId(order.getOrderId());
            if (shipment == null) {
                throw new IllegalStateException("待发货订单缺少物流记录");
            }
            // 重试时跳过已完成的明细所在主单，真实写入仍由发货入口的主单行锁保护。
            if (!LogisticsStatusEnum.PENDING_SHIPMENT.getStatus().equals(shipment.getLogisticsStatus())) {
                continue;
            }
            task.requireActiveLease();
            OrderLogisticsInfo update = new OrderLogisticsInfo();
            update.setOrderId(order.getOrderId());
            update.setLogisticsCompany("模拟物流");
            update.setLogisticsNo("SIM" + StringTools.getRandomNumber(10));
            try {
                logistics.delivery(update);
            } catch (RefundPendingException pending) {
                // 步骤09的退款确认是发货前置条件，保持待处理，等待期间不耗尽失败重试次数。
                throw new TaskDeferredException();
            }
        }
    }
}
