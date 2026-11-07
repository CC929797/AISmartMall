package com.easymall.task;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 【后续业务基础设施】对应business_task表的一条任务记录，供Mapper和任务服务传递数据库快照。
 * <p>它记录“要处理哪个业务事件、何时执行、谁正在执行、已经尝试几次”，不包含具体业务操作。
 * 任务框架用于后续业务；真实订单、商品等处理器在相应步骤接入。
 * 不生成包含payload的toString，也不直接作为公开HTTP响应，避免泄露任务内容。
 */
@Getter
@Setter
public class BusinessTask {
    /** 每条任务的唯一编号，与业务对象的编号不同。 */
    private String taskId;
    /** 任务类型，Worker据此找到对应的BusinessTaskHandler。 */
    private String taskType;
    /** 业务对象编号，例如订单号；与类型、版本一起组成事件唯一键。 */
    private String bizKey;
    /** 业务事件版本；同一对象产生新事件时，由业务代码提供相应版本。 */
    private long bizVersion;
    /** 处理器需要的JSON数据，应只保存必要信息，不保存凭据。 */
    private String payload;
    /** 规范化JSON的摘要，用于发现“同一事件却提交了不同内容”。 */
    private String payloadHash;
    /** 当前状态，含义见TaskStatus。 */
    private TaskStatus status;
    /** 本轮已领取执行的次数，首次领取变为1；崩溃后的重新领取也计次。 */
    private int attempts;
    /** 投递时保存的执行次数上限，避免配置变化影响已有任务。 */
    private int maxAttempts;
    /** 最早允许领取的UTC时间，用于首次延迟和失败退避。 */
    private LocalDateTime nextRunAt;
    /** 本次领取的唯一租约标识；重新领取会更换，不是固定的机器编号。 */
    private String leaseOwner;
    /** 租约到期的UTC时间；到期后可由其他Worker重新领取。 */
    private LocalDateTime leaseUntil;
    /** 固定错误码，便于排查；不保存原始异常正文或payload。 */
    private String lastError;
    /** 创建时间，按UTC解释。 */
    private LocalDateTime createdAt;
    /** 最近一次状态或租约变更时间，按UTC解释。 */
    private LocalDateTime updatedAt;
}
