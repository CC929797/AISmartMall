package com.easymall.task;

import com.easymall.exception.BusinessException;
import com.easymall.task.persistence.BusinessTaskMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 【后续业务基础设施】任务的事务投递、领取和状态管理入口，供业务服务与独立Worker复用。
 * <p>业务代码调用enqueue登记待办；Worker调用领取、续租和结果方法管理执行过程。
 * 本类不执行订单关闭或商品同步等业务处理器。
 * 投递加入调用方事务，租约和状态操作使用独立短事务，避免外部调用占用领取锁。
 */
public class BusinessTaskService {
    private final BusinessTaskMapper mapper;
    private final BusinessTaskProperties properties;
    /** 必须已有业务事务，不能在业务提交之后再单独补写任务。 */
    private final TransactionTemplate mandatory;
    /** 领取和状态操作独立提交，事务结束后才允许Worker执行处理器。 */
    private final TransactionTemplate independent;

    public BusinessTaskService(BusinessTaskMapper mapper, PlatformTransactionManager transactions, BusinessTaskProperties properties) {
        properties.validate();
        this.mapper = mapper;
        this.properties = properties;
        // MANDATORY：没有事务就拒绝；业务记录与任务记录共同提交或回滚。
        mandatory = new TransactionTemplate(transactions);
        mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
        // REQUIRES_NEW：短暂挂起可能存在的调用方事务，领取结果及时提交。
        independent = new TransactionTemplate(transactions);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        independent.setTimeout(10);
    }

    /**
     * 在现有可写业务事务中登记任务，例如业务更新后登记一次后续同步。
     * 同类型、同业务编号、同版本只保留一条；重复提交内容不同时拒绝，防止事件含义被覆盖。
     *
     * @return 首次创建或此前已存在的任务；重复投递不改变原计划时间和状态
     */
    public BusinessTask enqueue(TaskSubmission request) {
        Objects.requireNonNull(request, "Task submission required");
        validateType(request.taskType());
        if (request.bizKey() == null || request.bizKey().isBlank() || !request.bizKey().equals(request.bizKey().strip())
                || request.bizKey().length() > 128 || request.bizVersion() < 0
                || request.delay() == null || request.delay().isNegative() || request.delay().compareTo(Duration.ofDays(365)) > 0
                || request.delay().getNano() != 0) throw new IllegalArgumentException("Invalid task event identity or delay");
        String payload = TaskPayload.canonical(request.payload());
        return mandatory.execute(status -> {
            if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                throw new IllegalStateException("Task enqueue requires a writable business transaction");
            }
            BusinessTask task = new BusinessTask();
            task.setTaskId(id()); task.setTaskType(request.taskType()); task.setBizKey(request.bizKey()); task.setBizVersion(request.bizVersion());
            task.setPayload(payload); task.setPayloadHash(TaskPayload.hash(payload)); task.setMaxAttempts(properties.getMaxAttempts());
            // 数据库唯一键处理并发重复；随后加锁读取最终记录，比较载荷摘要。
            mapper.insertEvent(task, request.delay().toSeconds());
            BusinessTask existing = mapper.findEventForUpdate(request.taskType(), request.bizKey(), request.bizVersion());
            if (existing == null) throw new IllegalStateException("Task event could not be persisted");
            if (!existing.getPayloadHash().equals(task.getPayloadHash())) {
                throw new BusinessException(600, "相同任务事件不能使用不同载荷");
            }
            return existing;
        });
    }

    /**
     * 只领取本Worker已注册处理器的到期任务，也恢复租约已到期的执行中任务。
     * SQL使用SKIP LOCKED跳过其他领取者正在锁定的行；这里只改状态，不调用处理器。
     *
     * @param supportedTypes 当前Worker能够处理的任务类型
     * @param limit 本轮领取上限，应由Worker空闲执行容量决定
     * @param leaseSeconds 本次领取后租约有效秒数
     * @return 已在独立事务中提交的任务快照，可能少于limit条
     */
    public List<BusinessTask> claimBatch(Set<String> supportedTypes, int limit, int leaseSeconds) {
        if (supportedTypes == null || supportedTypes.isEmpty()) return List.of();
        if (supportedTypes.size() > 64 || limit < 1 || limit > 64) throw new IllegalArgumentException("Invalid task claim batch");
        supportedTypes.forEach(BusinessTaskService::validateType);
        validateLease(leaseSeconds);
        return independent.execute(status -> {
            List<BusinessTask> claimed = new ArrayList<>();
            for (BusinessTask task : mapper.lockDue(supportedTypes.stream().sorted().toList(), limit)) {
                if (task.getAttempts() >= task.getMaxAttempts()) {
                    // 最后一次执行也可能崩溃；租约到期后仍要转DEAD，不能永久停在RUNNING。
                    mapper.exhaust(task.getTaskId());
                } else if (mapper.claim(task.getTaskId(), id(), leaseSeconds) == 1) {
                    // 每次领取产生新标识，旧执行者不能再以旧标识写入结果。
                    claimed.add(mapper.findById(task.getTaskId()));
                }
            }
            return claimed;
        });
    }

    /** 续租只接受当前且尚未过期的租约；已经到期的执行资格不能被旧Worker复活。 */
    public boolean renew(BusinessTask task, int leaseSeconds) {
        validateLease(leaseSeconds);
        return independent.execute(status -> mapper.renew(task.getTaskId(), task.getLeaseOwner(), leaseSeconds) == 1);
    }

    /** 查询本次领取是否仍有效；供处理器关键步骤前检查，不代替外部业务幂等。 */
    public boolean ownsLease(BusinessTask task) {
        return independent.execute(status -> mapper.owns(task.getTaskId(), task.getLeaseOwner()) == 1);
    }

    /** 当前有效租约持有者才能标记成功，返回false表示此次条件更新未生效。 */
    public boolean succeed(BusinessTask task) {
        return independent.execute(status -> mapper.succeed(task.getTaskId(), task.getLeaseOwner()) == 1);
    }

    /**
     * 当前有效租约持有者登记失败：未耗尽次数则进入RETRY，否则进入DEAD。
     * errorCode必须是固定错误码，不能直接传入异常正文、请求内容或凭据。
     */
    public boolean fail(BusinessTask task, String errorCode) {
        if (errorCode == null || !errorCode.matches("[A-Z][A-Z0-9_]{0,159}")) throw new IllegalArgumentException("Fixed task error code required");
        long delay = retryDelayMillis(task.getAttempts());
        return independent.execute(status -> mapper.fail(task.getTaskId(), task.getLeaseOwner(), delay, errorCode) == 1);
    }

    /** 按本轮执行次数计算指数退避并封顶，例如基数1秒时依次为1秒、2秒、4秒。 */
    public long retryDelayMillis(int attempt) {
        if (attempt < 1 || attempt > 3) throw new IllegalArgumentException("Invalid task attempt");
        return Math.min(properties.getInitialBackoffMillis() * (1L << (attempt - 1)), properties.getMaxBackoffMillis());
    }

    /** 内部排查时按任务编号读取快照；结果含payload，不应直接作为公开接口响应。 */
    public BusinessTask findById(String taskId) { return mapper.findById(taskId); }

    /**
     * 内部维护方法：仅把DEAD任务恢复为READY，重置本轮执行次数；保留旧错误码直到成功。
     * 11B再通过管理员鉴权接口暴露，不能作为任意用户都能调用的公共重试入口。
     */
    public boolean retryDead(String taskId) {
        return independent.execute(status -> mapper.retryDead(taskId) == 1);
    }

    /** 有限批次查询DEAD任务，供内部排查；管理员查询页面留待后续接入。 */
    public List<BusinessTask> listDead(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid task list size");
        return mapper.listDead(limit);
    }

    static void validateType(String type) {
        if (type == null || !type.matches("[A-Z][A-Z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid task type");
    }
    private static void validateLease(int seconds) {
        if (seconds < 3 || seconds > 600) throw new IllegalArgumentException("Invalid task lease");
    }
    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
