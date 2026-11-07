package com.easymall.task;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 【后续业务基础设施】绑定business-task配置，统一管理执行次数、退避和Worker资源上限。
 * 默认值供正式框架使用；测试中会单独缩短等待时间，不应把测试时间参数直接用于业务环境。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "business-task")
public class BusinessTaskProperties {
    /** 每轮最多执行几次，包含首次执行，允许1至3次。 */
    private int maxAttempts = 3;
    /** 首次失败后的退避时间，单位毫秒。 */
    private long initialBackoffMillis = 1000;
    /** 指数退避的时间上限，单位毫秒。 */
    private long maxBackoffMillis = 60000;
    /** 独立Worker的线程、领取、续租与停机配置。 */
    private Worker worker = new Worker();

    /** 【后续业务配置】只控制独立入口；商城API不会因enabled=true自动创建Worker。 */
    @Getter @Setter
    public static class Worker {
        /** 独立入口是否创建Worker；入口可覆盖此默认值。 */
        private boolean enabled = false;
        /** 处理器最大并发数，也限制同时领取但尚未完成的任务数。 */
        private int threads = 2;
        /** 单轮最多领取多少条，实际还受空闲执行许可数量限制。 */
        private int batchSize = 8;
        /** 两轮轮询之间的等待时间，单位毫秒。 */
        private long pollIntervalMillis = 1000;
        /** 每次领取或续租后，执行资格有效多久，单位秒。 */
        private int leaseSeconds = 30;
        /** 执行中续租的间隔，单位毫秒，必须小于租约长度的一半。 */
        private long heartbeatMillis = 10000;
        /** 停机时允许在途处理器自然完成的等待时间，单位毫秒。 */
        private long shutdownGraceMillis = 5000;
    }

    /** 启动时校验边界，避免无限并发、无限重试或续租晚于租约到期等错误配置。 */
    public void validate() {
        if (maxAttempts < 1 || maxAttempts > 3 || initialBackoffMillis < 1
                || maxBackoffMillis < initialBackoffMillis || maxBackoffMillis > 86400000
                || worker == null || worker.threads < 1 || worker.threads > 16
                || worker.batchSize < 1 || worker.batchSize > 64
                || worker.pollIntervalMillis < 50 || worker.pollIntervalMillis > 60000
                || worker.leaseSeconds < 3 || worker.leaseSeconds > 600
                || worker.heartbeatMillis < 100 || worker.heartbeatMillis >= worker.leaseSeconds * 1000L / 2
                || worker.shutdownGraceMillis < 0 || worker.shutdownGraceMillis > 30000) {
            throw new IllegalArgumentException("Invalid business-task configuration");
        }
    }
}
