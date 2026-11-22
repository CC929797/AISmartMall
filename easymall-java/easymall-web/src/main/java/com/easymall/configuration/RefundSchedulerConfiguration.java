package com.easymall.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 渠道查询可能等待网络响应，退款补偿使用独立调度线程。 */
@Configuration(proxyBeanMethods = false)
public class RefundSchedulerConfiguration {
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler refundScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("refund-reconcile-");
        return scheduler;
    }
}
