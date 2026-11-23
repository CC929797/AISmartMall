package com.easymall.service;

import com.easymall.entity.enums.LogisticsStatusEnum;
import com.easymall.entity.enums.OrderStatusEnum;
import com.easymall.entity.po.OrderInfo;
import com.easymall.entity.po.OrderLogisticsInfo;
import com.easymall.entity.po.OrderLogisticsInfoRecord;
import com.easymall.entity.query.OrderInfoQuery;
import com.easymall.entity.query.OrderLogisticsInfoQuery;
import com.easymall.entity.query.OrderLogisticsInfoRecordQuery;
import com.easymall.mappers.OrderInfoMapper;
import com.easymall.mappers.OrderLogisticsInfoMapper;
import com.easymall.mappers.OrderLogisticsInfoRecordMapper;
import com.easymall.sync.SearchSyncService;
import com.easymall.task.FulfillmentTasks;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * 负责迁移单条旧任务的 MySQL 事务。单独放在 Spring 服务里，让迁移入口通过事务代理调用。
 * 代理提交成功后方法才返回，外层随后移除对应的 Redis 记录。
 */
@Service
public class LegacyTaskTransfer {
    private final SearchSyncService search;
    private final OrderInfoMapper<OrderInfo, OrderInfoQuery> orders;
    private final OrderLogisticsInfoMapper<OrderLogisticsInfo, OrderLogisticsInfoQuery> shipping;
    private final OrderLogisticsInfoRecordMapper<OrderLogisticsInfoRecord, OrderLogisticsInfoRecordQuery> records;
    private final FulfillmentTasks fulfillment;

    public LegacyTaskTransfer(SearchSyncService search, OrderInfoMapper<OrderInfo, OrderInfoQuery> orders,
                              OrderLogisticsInfoMapper<OrderLogisticsInfo, OrderLogisticsInfoQuery> shipping,
                              OrderLogisticsInfoRecordMapper<OrderLogisticsInfoRecord, OrderLogisticsInfoRecordQuery> records,
                              FulfillmentTasks fulfillment) {
        this.search = search;
        this.orders = orders;
        this.shipping = shipping;
        this.records = records;
        this.fulfillment = fulfillment;
    }

    /** 旧知识库消息只提供对象编号，按当前业务数据重新登记同步版本。 */
    @Transactional(rollbackFor = Exception.class)
    public void transferRag(String type, String id) {
        if ("product".equals(type)) {
            search.productChanged(id);
        } else if ("faq".equals(type)) {
            search.questionChanged(Integer.parseInt(id));
        } else {
            throw new IllegalArgumentException("旧队列包含无法识别的知识库数据类型");
        }
    }

    /**
     * 锁定并检查当前订单。订单或物流记录已经不存在时，可直接确认这条旧消息。
     * 需要继续履约时，沿用旧队列剩余的等待时间。
     */
    @Transactional(rollbackFor = Exception.class)
    public void transferShipment(String orderId, boolean autoConfirm, Duration delay) {
        OrderInfo order = orders.selectOrderForUpdate(orderId);
        if (order == null) {
            return;
        }
        OrderLogisticsInfo shipment = shipping.selectByOrderId(orderId);
        if (shipment == null) {
            return;
        }
        boolean inTransit = LogisticsStatusEnum.IN_TRANSIT.getStatus().equals(shipment.getLogisticsStatus());
        if (autoConfirm) {
            // 允许待收货和仍有未退款商品的部分退款订单继续办理自动收货。
            if ((inTransit || LogisticsStatusEnum.DELIVERED.getStatus().equals(shipment.getLogisticsStatus()))
                    && (OrderStatusEnum.SHIPPED.getStatus().equals(order.getOrderStatus())
                    || OrderStatusEnum.PARTIALLY_REFUNDED.getStatus().equals(order.getOrderStatus()))) {
                fulfillment.autoConfirm(orderId, delay);
            }
        } else if (inTransit) {
            // 已经追加的轨迹保留。以当前记录数为版本登记下一跳，同一进度重复迁移时可去重。
            var query = new OrderLogisticsInfoRecordQuery();
            query.setOrderId(orderId);
            fulfillment.logisticsStep(orderId, records.selectCount(query), delay);
        }
    }
}
