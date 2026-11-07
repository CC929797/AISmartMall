package com.easymall.task;

import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * 【后续业务运行入口】在单独JVM中启动任务Worker，供run-task-worker.ps1调用。
 * <p>它属于正式任务框架，只装配任务与JDBC/MyBatis，不启动HTTP服务，
 * 不扫描商城Controller、Redis、ES、AI或旧消费者。后续业务处理器需在此入口显式注册。
 * 本类内的smokeTaskHandler是唯一测试专用部分，必须通过配置单独开启。
 */
public final class BusinessTaskWorkerApplication {
    public static void main(String[] args) {
        new SpringApplicationBuilder(Bootstrap.class).web(WebApplicationType.NONE)
                .properties("spring.config.name=business-task-worker",
                        "logging.config=classpath:logback-business-task.xml",
                        "business-task.worker.enabled=true",
                        "mybatis.configuration.map-underscore-to-camel-case=true")
                .run(args);
    }

    /**
     * 【后续业务启动配置】仅在本入口作为Spring启动源时装配。
     * 不标注@Configuration或@Component，避免普通商城组件扫描误启动Worker。
     */
    @ImportAutoConfiguration({PropertyPlaceholderAutoConfiguration.class, DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class, JdbcTemplateAutoConfiguration.class, MybatisAutoConfiguration.class})
    @Import(BusinessTaskConfiguration.class)
    public static class Bootstrap {
        /** 从显式注册的处理器构建Worker；没有处理器时拒绝启动，避免误以为业务已接通。 */
        @Bean
        @ConditionalOnProperty(name = "business-task.worker.enabled", havingValue = "true")
        BusinessTaskWorker businessTaskWorker(BusinessTaskService service, BusinessTaskProperties properties,
                                              ObjectProvider<BusinessTaskHandler> handlers) {
            return new BusinessTaskWorker(service, properties, handlers.orderedStream().toList());
        }

        /**
         * 【仅用于测试验证，不处理正式业务】提供无副作用的SMOKE_NOOP任务。
         * 用于检查真实JVM能否领取并完成任务，不关闭订单、不改库存、不调用模型或索引。
         * 默认关闭，且只允许隔离13306/easymall_test；类的其他部分仍是后续业务入口。
         */
        @Bean
        @ConditionalOnProperty(name = "business-task.smoke.enabled", havingValue = "true")
        BusinessTaskHandler smokeTaskHandler(DataSourceProperties datasource, Environment environment) {
            String url = datasource.getUrl();
            if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:13306/easymall_test(?:\\?[^#]*)?")
                    || environment.containsProperty("spring.datasource.hikari.jdbc-url")) {
                throw new IllegalStateException("Task smoke handler requires isolated loopback easymall_test");
            }
            return new BusinessTaskHandler() {
                @Override public String taskType() { return "SMOKE_NOOP"; }
                @Override public void handle(TaskExecution task) { task.requireActiveLease(); }
            };
        }
    }

    private BusinessTaskWorkerApplication() { }
}
