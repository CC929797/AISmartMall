package com.easymall.sync;

import com.easymall.mappers.SearchSyncMapper;
import com.easymall.task.BusinessTaskService;
import com.easymall.task.TaskDeferredException;
import com.easymall.task.TaskExecution;
import com.easymall.task.TaskSubmission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 步骤 11B 的同步入口。MySQL 保管商品和 FAQ 原始数据，索引保存用于查询的副本。
 * 修改业务时先登记需要同步的版本，Worker 异步写索引，写完再把这个版本标记为可读。
 */
@Service
public class SearchSyncService {
    public static final String PRODUCT_SEARCH = "PRODUCT_SEARCH";
    public static final String PRODUCT_VECTOR = "PRODUCT_VECTOR";
    public static final String FAQ_VECTOR = "FAQ_VECTOR";

    private final SearchSyncMapper mapper;
    private final BusinessTaskService tasks;

    public SearchSyncService(SearchSyncMapper mapper, BusinessTaskService tasks) {
        this.mapper = mapper;
        this.tasks = tasks;
    }

    /**
     * 商品保存、上下架、删除共用此入口，在同一个业务事务里登记关键词与向量两份任务。
     * MANDATORY 要求调用方已经开启事务。固定先操作搜索状态行，再操作向量状态行。
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void productChanged(String id) {
        enqueue(PRODUCT_SEARCH, id);
        enqueue(PRODUCT_VECTOR, id);
    }

    /** 销量变化只刷新用于排序的 ES 文档，不重新计算商品向量。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void salesChanged(String id) {
        enqueue(PRODUCT_SEARCH, id);
    }

    /** FAQ 只进入向量知识库。删除 FAQ 同样递增版本，便于后台清掉之前的答案。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void questionChanged(int id) {
        enqueue(FAQ_VECTOR, Integer.toString(id));
    }

    private void enqueue(String type, String key) {
        // 首次登记得到版本 1，后续每次修改递增。版本状态和任务记录随业务一起提交或回滚。
        mapper.request(type, key);
        // 登记得到版本号
        long version = mapper.find(type, key).requestedVersion();
        // 任务只保存对象编号和版本。执行时再查 MySQL，空 JSON 表示没有额外的业务参数。
        tasks.enqueue(new TaskSubmission(type, key, version, "{}", Duration.ZERO));
    }

    /**
     * 领取该对象的短锁后再执行外部同步。处理器写入版本化数据，成功后才发布该版本。
     * 如果生成向量期间业务变更，即使旧请求已经写到 ES，旧版本也无法通过发布检查。
     */
    public void synchronize(TaskExecution task, Runnable writeProjection) {
        task.requireActiveLease();
        int acquired = mapper.acquire(task.taskType(), task.bizKey(), task.bizVersion(), task.taskId(), task.leaseOwner());
        if (acquired == 0) {
            SearchSyncState state = mapper.find(task.taskType(), task.bizKey());
            // 版本被更新取代或已经发布时，正常返回，Worker 会把本条任务标记为成功。
            if (state == null || state.requestedVersion() != task.bizVersion()
                    || state.publishedVersion() >= task.bizVersion()) {
                return;
            }
            // 当前版本仍需要处理，另一份有效租约暂时占用对象。延后执行，保留原有尝试次数。
            throw new TaskDeferredException();
        }
        try {
            // 这一步调用 ES 或 Embedding。对象的执行资格已登记，数据库行锁在此之前已释放。
            writeProjection.run();
            task.requireActiveLease();
            if (mapper.publish(task.taskType(), task.bizKey(), task.bizVersion(), task.taskId(), task.leaseOwner()) == 0) {
                SearchSyncState state = mapper.find(task.taskType(), task.bizKey());
                // 新业务版本已提交时，此任务可以结束。当前版本仍未发布则需要继续恢复。
                if (state != null && state.requestedVersion() == task.bizVersion()
                        && state.publishedVersion() < task.bizVersion()) {
                    throw new TaskExecution.LeaseLostException();
                }
            }
        } finally {
            // 始终尝试归还执行资格。SQL 同时匹配任务 ID 和令牌，避免清除后来 Worker 的资格。
            mapper.release(task.taskType(), task.bizKey(), task.taskId(), task.leaseOwner());
        }
    }

    /** 批量读取搜索候选项的同步版本，供搜索和 AI 导购过滤过期内容。 */
    public Map<String, SearchSyncState> states(String type, List<String> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        return mapper.findBatch(type, keys).stream().collect(Collectors.toMap(SearchSyncState::bizKey, Function.identity()));
    }

    /** 管理员按商品 ID 重同步时，通过这个事务入口为两个索引登记新版本。 */
    @Transactional(rollbackFor = Exception.class)
    public void resyncProduct(String id) {
        // 登记搜索和向量版本
        productChanged(id);
    }

    /** 重同步以当前 MySQL 数据为准。FAQ 已删除时，Worker 只执行清理。 */
    @Transactional(rollbackFor = Exception.class)
    public void resyncQuestion(int id) {
        questionChanged(id);
    }

    /** 分批重投存量数据，调用方保存游标。这里只登记任务，避免管理请求等待外部服务。 */
    @Transactional(rollbackFor = Exception.class)
    public List<String> backfillProducts(String cursor, int limit) {
        List<String> keys = mapper.productsAfter(cursor, limit);
        for (String key : keys) {
            productChanged(key);
        }
        return keys;
    }

    /** 从上次处理的 FAQ ID 之后继续登记，返回的最后一个 ID 供下一批使用。 */
    @Transactional(rollbackFor = Exception.class)
    public List<Integer> backfillQuestions(int cursor, int limit) {
        List<Integer> keys = mapper.questionsAfter(cursor, limit);
        for (Integer key : keys) {
            questionChanged(key);
        }
        return keys;
    }
}
