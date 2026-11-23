package com.easymall.configuration;

import com.easymall.entity.config.AppConfig;
import com.easymall.refund.RefundPendingException;
import com.easymall.service.OrderInfoService;
import com.easymall.service.OrderLogisticsInfoService;
import com.easymall.task.BusinessTaskHandler;
import com.easymall.task.BusinessTaskProperties;
import com.easymall.task.BusinessTaskService;
import com.easymall.task.BusinessTaskWorker;
import com.easymall.task.FulfillmentTasks;
import com.easymall.task.TaskDeferredException;
import com.easymall.task.TaskExecution;
import com.easymall.hander.PaidOrderHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.List;
import java.util.function.Consumer;

/** 付款后履约使用同一个有界 Worker，主动查单与关单则保留独立调度器。 */
@Configuration(proxyBeanMethods = false)
public class PaymentTaskConfiguration {
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler paymentScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("payment-reconcile-");
        return scheduler;
    }

    /**
     * 付款后自动发货、模拟物流、自动收货由同一个履约 Worker 按类型分派。
     */
    @Bean
    public BusinessTaskWorker paidOrderWorker(BusinessTaskService tasks, BusinessTaskProperties properties,
                                             PaidOrderHandler handler, AppConfig config,
                                             OrderLogisticsInfoService logistics, OrderInfoService orders) {
        return new BusinessTaskWorker(tasks, properties, List.of(handler,
                new SimulatedHandler(FulfillmentTasks.LOGISTICS_STEP, config,
                        task -> logistics.mockOrderLogistics(task.bizKey(), task.bizVersion())),
                new SimulatedHandler(FulfillmentTasks.AUTO_CONFIRM, config,
                        task -> orders.confirmSimulatedOrder(task.bizKey()))));
    }

    /** 两类模拟任务共用配置检查、执行资格检查和退款等待规则。 */
    private record SimulatedHandler(String taskType, AppConfig config,
                                    Consumer<TaskExecution> action) implements BusinessTaskHandler {
        @Override
        public void handle(TaskExecution task) {
            if (!config.isSimulatedLogistics()) {
                // 关闭模拟期间保留已经登记的任务，重新开启可以继续处理。
                throw new TaskDeferredException();
            }
            task.requireActiveLease();
            try {
                action.accept(task);
            } catch (RefundPendingException pending) {
                // 等退款结果明确后再推进订单，延期不会消耗一次失败重试机会。
                throw new TaskDeferredException();
            }
        }
    }
}
