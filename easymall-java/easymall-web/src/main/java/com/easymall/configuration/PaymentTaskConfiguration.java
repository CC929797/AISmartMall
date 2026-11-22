package com.easymall.configuration;

import com.easymall.task.BusinessTaskProperties;
import com.easymall.task.BusinessTaskService;
import com.easymall.task.BusinessTaskWorker;
import com.easymall.hander.PaidOrderHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.List;

/** Web 进程只装配付款成功任务的处理器，同时隔离支付查单与关单定时任务的线程。 */
@Configuration(proxyBeanMethods = false)
public class PaymentTaskConfiguration {
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler paymentScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("payment-reconcile-");
        return scheduler;
    }

    @Bean
    public BusinessTaskWorker paidOrderWorker(BusinessTaskService tasks, BusinessTaskProperties properties,
                                             PaidOrderHandler handler) {
        // 11A 按已注册类型领取任务，旧 ORDER_CREATED、ORDER_CANCELLED 继续由后续步骤接入。
        return new BusinessTaskWorker(tasks, properties, List.of(handler));
    }
}
