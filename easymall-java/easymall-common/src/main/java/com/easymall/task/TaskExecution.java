package com.easymall.task;

import java.util.function.BooleanSupplier;

/**
 * 【后续业务基础设施】Worker交给处理器的本次执行上下文，与投递请求TaskSubmission区分。
 *
 * @param taskId 任务记录编号
 * @param taskType 任务类型
 * @param bizKey 要处理的业务对象编号
 * @param bizVersion 该业务事件的版本
 * @param payload 已规范化的JSON内容
 * @param attempt 本轮第几次执行，从1开始
 * @param leaseActive 检查当前执行是否仍持有有效租约的回调，由Worker提供
 */
public record TaskExecution(String taskId, String taskType, String bizKey, long bizVersion,
                            String payload, int attempt, BooleanSupplier leaseActive) {
    /**
     * 租约已丢失时中止当前处理路径。处理器可在关键操作前调用。
     * 此检查不能撤销已经发出的外部请求，业务副作用仍需幂等和版本保护。
     */
    public void requireActiveLease() {
        if (!leaseActive.getAsBoolean()) throw new LeaseLostException();
    }
    /** 避免日志包含业务编号、JSON内容或回调对象。 */
    @Override public String toString() {
        return "TaskExecution[id=" + taskId + ",type=" + taskType + ",attempt=" + attempt + "]";
    }

    /** 【运行时异常】表示本次执行资格已失效，属于正式任务框架，不是测试专用异常。 */
    public static final class LeaseLostException extends IllegalStateException {
        public LeaseLostException() { super("Task lease lost"); }
    }
}
