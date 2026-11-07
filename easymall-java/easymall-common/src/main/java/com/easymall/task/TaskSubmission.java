package com.easymall.task;

import java.time.Duration;

/**
 * 【后续业务基础设施】业务代码提交任务时使用的请求对象，交给BusinessTaskService.enqueue。
 * <p>相同任务类型、业务编号和版本表示同一事件；重投不改变首次写入的计划时间。
 *
 * @param taskType 任务类型，必须与对应处理器声明的类型一致
 * @param bizKey 业务对象编号，例如订单号
 * @param bizVersion 业务事件版本，非负；由业务代码提供
 * @param payload 处理器需要的JSON对象字符串，入库前会校验并规范化
 * @param delay 首次执行的等待时间，接受0至365天的整秒值
 */
public record TaskSubmission(String taskType, String bizKey, long bizVersion, String payload, Duration delay) {
    /** 日志中只显示类型和版本，避免直接输出业务编号或任务内容。 */
    @Override public String toString() {
        return "TaskSubmission[type=" + taskType + ",version=" + bizVersion + "]";
    }
}
