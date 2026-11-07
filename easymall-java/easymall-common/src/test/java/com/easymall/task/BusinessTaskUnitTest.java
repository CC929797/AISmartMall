package com.easymall.task;

import com.easymall.task.persistence.BusinessTaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 【仅用于测试验证，不参与正式业务运行】任务基础规则的单元测试。
 * 使用Mockito替身，不连接真实MySQL；验证事务前置、JSON校验/精度、退避、配置与日志边界。
 * 此类位于src/test/java，只在执行测试时使用，不是后续业务需要调用的服务。
 */
class BusinessTaskUnitTest {
    private final BusinessTaskMapper mapper = mock(BusinessTaskMapper.class);
    private final BusinessTaskProperties properties = new BusinessTaskProperties();
    private final BusinessTaskService service = new BusinessTaskService(mapper,
            new DataSourceTransactionManager(mock(DataSource.class)), properties);

    /** 无业务事务时应在访问数据库前拒绝投递，避免业务与任务分别提交。 */
    @Test void enqueueWithoutBusinessTransactionIsRejectedBeforeMapper() {
        assertThatThrownBy(() -> service.enqueue(new TaskSubmission("TEST", "key", 0, "{}", Duration.ZERO)))
                .isInstanceOf(IllegalTransactionStateException.class);
        verifyNoInteractions(mapper);
    }

    /** 对象字段顺序不影响去重，数组顺序和高精度数字含义必须保留。 */
    @Test void payloadCanonicalizationSortsObjectsButPreservesArrayOrder() {
        assertThat(TaskPayload.canonical("{\"z\":[2,1],\"a\":{\"b\":1,\"a\":2}}"))
                .isEqualTo("{\"a\":{\"a\":2,\"b\":1},\"z\":[2,1]}");
        assertThat(TaskPayload.hash(TaskPayload.canonical("{\"b\":2,\"a\":1}")))
                .isEqualTo(TaskPayload.hash(TaskPayload.canonical("{\"a\":1,\"b\":2}")));
        assertThat(TaskPayload.canonical("{\"amount\":0.123456789012345678901,\"id\":123456789012345678901}"))
                .isEqualTo("{\"amount\":0.123456789012345678901,\"id\":123456789012345678901}");
        assertThat(TaskPayload.hash(TaskPayload.canonical("{\"amount\":0.123456789012345678901}")))
                .isNotEqualTo(TaskPayload.hash(TaskPayload.canonical("{\"amount\":0.123456789012345678902}")));
    }

    /** 非法、重复字段、过深或过大JSON均拒绝，错误提示不能带出原文。 */
    @Test void invalidOrOversizedPayloadsDoNotLeakTheirContents() {
        for (String payload : new String[]{null, "[]", "null", "{", "{\"a\":1,\"a\":2}", "{}{}",
                "{\"secret\":\"" + "S".repeat(65536) + "\"}", "{\"a\":".repeat(34) + "0" + "}".repeat(34)}) {
            assertThatThrownBy(() -> TaskPayload.canonical(payload)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("secret");
        }
    }

    /** 非法事件标识和不支持的延迟在访问基础设施之前被拒绝。 */
    @Test void identityAndDelayAreBoundedBeforeInfrastructure() {
        for (TaskSubmission request : List.of(
                new TaskSubmission("bad type", "key", 0, "{}", Duration.ZERO),
                new TaskSubmission("TEST", " key ", 0, "{}", Duration.ZERO),
                new TaskSubmission("TEST", "x".repeat(129), 0, "{}", Duration.ZERO),
                new TaskSubmission("TEST", "key", -1, "{}", Duration.ZERO),
                new TaskSubmission("TEST", "key", 0, "{}", Duration.ofMillis(1)),
                new TaskSubmission("TEST", "key", 0, "{}", Duration.ofDays(366)))) {
            assertThatThrownBy(() -> service.enqueue(request)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(mapper);
    }

    /** 失败后的等待时间逐次增加，但不能超过配置上限。 */
    @Test void retriesUseCappedExponentialBackoff() {
        properties.setInitialBackoffMillis(1000); properties.setMaxBackoffMillis(3000);
        assertThat(service.retryDelayMillis(1)).isEqualTo(1000);
        assertThat(service.retryDelayMillis(2)).isEqualTo(2000);
        assertThat(service.retryDelayMillis(3)).isEqualTo(3000);
        assertThatThrownBy(() -> service.retryDelayMillis(0)).isInstanceOf(IllegalArgumentException.class);
    }

    /** 没有支持类型时不领取；批次、租约和错误码必须符合限制。 */
    @Test void emptyRegistryDoesNotClaimAndInvalidBoundsAreRejected() {
        assertThat(service.claimBatch(Set.of(), 1, 30)).isEmpty();
        assertThatThrownBy(() -> service.claimBatch(Set.of("TEST"), 65, 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.claimBatch(Set.of("TEST"), 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.fail(new BusinessTask(), "password=secret")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper);
    }

    /** 不合理的续租时间和执行次数在启动时失败，包含极端数值情况。 */
    @Test void invalidConfigurationFailsFastIncludingOverflowValues() {
        properties.getWorker().setHeartbeatMillis(Long.MAX_VALUE);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
        properties.getWorker().setHeartbeatMillis(10000);
        properties.setMaxAttempts(4);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
    }

    /** 一个类型不能注册两个处理器，也不能在没有处理器时启动Worker。 */
    @Test void duplicateAndMissingHandlersAreRejected() {
        BusinessTaskHandler handler = mock(BusinessTaskHandler.class);
        when(handler.taskType()).thenReturn("TEST");
        assertThatThrownBy(() -> new BusinessTaskWorker(service, properties, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusinessTaskWorker(service, properties, List.of(handler, handler))).isInstanceOf(IllegalArgumentException.class);
    }

    /** 已失去执行资格时抛出异常，诊断字符串也不能输出业务主键或载荷。 */
    @Test void lostLeaseAndDiagnosticStringsNeverExposePayload() {
        TaskExecution task = new TaskExecution("id", "TEST", "private-key", 1, "secret", 1, () -> false);
        assertThatThrownBy(task::requireActiveLease).isInstanceOf(TaskExecution.LeaseLostException.class);
        assertThat(task.toString()).doesNotContain("secret", "private-key");
        assertThat(new TaskSubmission("TEST", "private-key", 1, "secret", Duration.ZERO).toString()).doesNotContain("secret", "private-key");
    }
}
