package com.easymall.task;

/**
 * 【后续业务基础设施】任务生命周期状态；这些名称直接保存在数据库中。
 * <p>正常路径：READY → RUNNING → SUCCESS；失败可进入RETRY，次数耗尽进入DEAD。
 * RUNNING租约到期后允许重新领取；DEAD保留记录，等待检查或人工重试。
 */
public enum TaskStatus {
    /** 已入库，等待首次执行；仍需满足nextRunAt已到期。 */
    READY,
    /** 已被领取，当前执行资格由leaseOwner和leaseUntil共同确定。 */
    RUNNING,
    /** 执行失败，等待退避时间结束后再次领取。 */
    RETRY,
    /** 已成功完成，普通轮询不会再次领取。 */
    SUCCESS,
    /** 本轮执行次数已耗尽，停止自动尝试；不代表记录被删除。 */
    DEAD
}
