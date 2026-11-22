package com.easymall.task;

/** 处理器遇到尚未完成的业务前置条件，释放租约并稍后重试；本轮没有执行失败。 */
public final class TaskDeferredException extends RuntimeException {
    public TaskDeferredException() {
        super("任务等待业务前置条件");
    }
}
