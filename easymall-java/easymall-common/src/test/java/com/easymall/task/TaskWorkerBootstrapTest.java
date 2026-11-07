package com.easymall.task;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

/**
 * 【仅用于测试验证，不参与正式业务运行】检查Spring装配边界和启动配置。
 * 使用轻量测试容器及不可用的占位数据库端口，不启动商城HTTP，也不连接真实MySQL。
 * 验证普通商城不会误启动新Worker，独立入口也不会顺带启动Redis、ES或旧任务。
 */
class TaskWorkerBootstrapTest {
    /** 构建测试上下文；端口1只作占位，验证本身不应触发数据库连接。 */
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(BusinessTaskWorkerApplication.Bootstrap.class)
                .withPropertyValues("spring.datasource.url=jdbc:mysql://127.0.0.1:1/easymall_test",
                        "spring.datasource.username=placeholder", "spring.datasource.password=placeholder",
                        "mybatis.configuration.map-underscore-to-camel-case=true", "business-task.worker.enabled=false");
    }

    /** 独立配置只装配任务设施，不把商城其他消费者和客户端带进来。 */
    @Test void standaloneConfigurationDoesNotStartCommerceOrInfrastructureClients() {
        runner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(BusinessTaskService.class).doesNotHaveBean(BusinessTaskWorker.class);
            assertThat(context.getBeanDefinitionNames()).noneMatch(name -> name.toLowerCase().contains("redis")
                    || name.toLowerCase().contains("elasticsearch") || name.equals("payOrderTask") || name.equals("chatComponent"));
        });
    }

    /** API只导入公共配置时，即使开关为true也不会创建Worker。 */
    @Test void merchantApplicationsDoNotStartWorkerEvenWithEnabledFlag() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        DataSourceTransactionManagerAutoConfiguration.class, MybatisAutoConfiguration.class))
                .withUserConfiguration(BusinessTaskConfiguration.class)
                .withPropertyValues("spring.datasource.url=jdbc:mysql://127.0.0.1:1/easymall_test", "business-task.worker.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(BusinessTaskWorker.class));
    }

    /** 测试专用Smoke处理器必须在连接数据库前拒绝非隔离地址。 */
    @Test void smokeHandlerRefusesNonIsolatedDatabaseBeforeConnecting() {
        runner().withPropertyValues("business-task.smoke.enabled=true", "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/easymall")
                .run(context -> assertThat(context).hasFailed());
    }

    /** 避免出现Worker已经启动但没有任何处理能力的假成功。 */
    @Test void enabledWorkerWithNoHandlerFailsFast() {
        runner().withPropertyValues("business-task.worker.enabled=true").run(context -> assertThat(context).hasFailed());
    }
}
