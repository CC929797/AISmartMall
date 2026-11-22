package com.easymall.task.persistence;

import com.easymall.task.BusinessTask;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/**
 * 【后续业务持久化接口】对应资源目录下的BusinessTaskMapper.xml，负责business_task表读写。
 * <p>事务边界由BusinessTaskService管理；不能把“查到任务”直接当成“拥有执行资格”。
 * 租约续期和结果写入的SQL都会核对RUNNING状态、当前租约标识和未过期时间。
 */
public interface BusinessTaskMapper {
    /** 插入新事件；唯一键重复时不覆盖原记录，由服务层再核对载荷摘要。 */
    int insertEvent(@Param("task") BusinessTask task, @Param("delaySeconds") long delaySeconds);
    /** 按事件唯一键加行锁读取，用于并发投递后的载荷一致性检查。 */
    BusinessTask findEventForUpdate(@Param("type") String type, @Param("key") String key, @Param("version") long version);
    /** 读取任务快照，不代表该调用者已经领取任务。 */
    BusinessTask findById(@Param("id") String id);
    /** 锁定到期候选任务并跳过其他事务已锁行，必须在领取短事务内调用。 */
    List<BusinessTask> lockDue(@Param("types") List<String> types, @Param("limit") int limit);
    /** 条件领取：增加执行次数，写入新租约标识与期限；返回实际更新行数。 */
    int claim(@Param("id") String id, @Param("owner") String owner, @Param("leaseSeconds") int leaseSeconds);
    /** 将已到期且次数耗尽的任务转为DEAD，包括最后一次执行崩溃的情况。 */
    int exhaust(@Param("id") String id);
    /** 仅为当前未过期租约续期；旧owner或过期租约更新不到行。 */
    int renew(@Param("id") String id, @Param("owner") String owner, @Param("leaseSeconds") int leaseSeconds);
    /** 返回当前有效租约匹配的记录数，服务层据此判断执行资格。 */
    int owns(@Param("id") String id, @Param("owner") String owner);
    /** 当前有效租约持有者标记SUCCESS并清除租约。 */
    int succeed(@Param("id") String id, @Param("owner") String owner);
    /** 等待业务前置条件时延后执行，只接受当前有效租约。 */
    int defer(@Param("id") String id, @Param("owner") String owner);
    /** 当前有效租约持有者登记失败，根据次数转为RETRY或DEAD，并保存固定错误码。 */
    int fail(@Param("id") String id, @Param("owner") String owner, @Param("delayMillis") long delayMillis, @Param("errorCode") String errorCode);
    /** 只允许DEAD恢复READY并重置本轮次数，普通执行中或成功任务不能借此重跑。 */
    int retryDead(@Param("id") String id);
    /** 按有限批次读取DEAD任务供内部排查，不在此层提供管理员权限校验。 */
    List<BusinessTask> listDead(@Param("limit") int limit);
}
