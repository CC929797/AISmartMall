package com.easymall.task;

import com.easymall.task.persistence.BusinessTaskMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 【后续业务基础设施】注册任务配置、Mapper和BusinessTaskService。
 * <p>商城API进程通过此配置获得投递能力；独立Worker入口也复用这部分装配。
 * 本配置只注册投递。Web 的付款任务配置和独立入口分别声明各自的 Worker 及处理器。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BusinessTaskProperties.class)
@MapperScan(basePackageClasses = BusinessTaskMapper.class)
public class BusinessTaskConfiguration {
    /** 使用当前应用的事务管理器，确保投递能够加入调用方的同一数据库事务。 */
    @Bean
    BusinessTaskService businessTaskService(BusinessTaskMapper mapper, PlatformTransactionManager transactions, BusinessTaskProperties properties) {
        return new BusinessTaskService(mapper, transactions, properties);
    }
}
