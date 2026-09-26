package com.easymall.task;

import com.easymall.commonent.RedisComponent;
import com.easymall.entity.constants.Constants;
import com.easymall.entity.enums.ExecutorServiceSignletionEnum;
import com.easymall.entity.enums.OrderStatusEnum;
import com.easymall.entity.po.OrderInfo;
import com.easymall.service.OrderInfoService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@Slf4j
public class PayOrderTask {
    @Resource
    private RedisComponent redisComponent;
    @Resource
    private OrderInfoService orderInfoService;

    @PostConstruct
    public void consumeDelayOrder() {
        ExecutorServiceSignletionEnum.INSTANCE.getExecutorService().execute(() -> {
            while (true) {
                try {
                    Set<String> queueOrderList = redisComponent.getTimeOutOrder(Constants.REDIS_KEY_ORDER_DELAY_QUEUE);
                    if(queueOrderList == null || queueOrderList.isEmpty()) {
                        Thread.sleep(5000);
                        continue;
                    }

                    for (String orderId : queueOrderList) {
                        if (redisComponent.removeTimeOutOrder(Constants.REDIS_KEY_ORDER_DELAY_QUEUE,orderId) > 0){
                            OrderInfo orderInfo = orderInfoService.getOrderInfoByOrderId(orderId);
                            if (!OrderStatusEnum.WAIT_PAYMENT.getStatus().equals(orderInfo.getOrderStatus())) {
                                continue;
                            }
                            orderInfoService.cancelOrder(null,orderId,OrderStatusEnum.CLOSED);
                        }
                    }
                }catch (Exception e) {
                    log.error("支付订单任务出错！错误信息：" + e.getMessage());
                    try {
                        Thread.sleep(5000);
                    }catch (Exception ex) {
                        log.error("休眠失败" + ex.getMessage());
                    }
                }
            }
        });
        log.info("定时任务开始启动");
    }
}
