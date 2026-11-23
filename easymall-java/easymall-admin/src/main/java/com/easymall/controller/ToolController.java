package com.easymall.controller;

import com.easymall.entity.enums.DateTimePatternEnum;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.exception.BusinessException;
import com.easymall.service.LegacyTaskMigration;
import com.easymall.service.ProductInfoService;
import com.easymall.service.StatisticsInfoService;
import com.easymall.sync.SearchSyncService;
import com.easymall.task.BusinessTask;
import com.easymall.task.BusinessTaskService;
import com.easymall.task.FulfillmentTasks;
import com.easymall.task.TaskStatus;
import com.easymall.task.persistence.BusinessTaskMapper;
import com.easymall.utils.DateUtil;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/tool")
public class ToolController extends ABaseController {
    // 只展示当前提供处理入口的任务类型，失败重试也使用同一白名单。
    private static final List<String> TASK_TYPES = List.of(SearchSyncService.PRODUCT_SEARCH,
            SearchSyncService.PRODUCT_VECTOR, SearchSyncService.FAQ_VECTOR, "ORDER_PAID",
            FulfillmentTasks.LOGISTICS_STEP, FulfillmentTasks.AUTO_CONFIRM);
    private static final int MAX_BATCH = 50;

    @Resource
    private StatisticsInfoService statisticsInfoService;
    @Resource
    private ProductInfoService productInfoService;
    @Resource
    private SearchSyncService searchSyncService;
    @Resource
    private BusinessTaskMapper taskMapper;
    @Resource
    private BusinessTaskService businessTaskService;
    @Resource
    private LegacyTaskMigration legacyTaskMigration;

    /**
     * 统计7天各项数据(订单金额、退款金额、订单数量、退款数量)
     * @return 统计数据
     */
    @RequestMapping("/statistics")
    public ResponseVO statistics() {
        String beforeDate = DateUtil.getBeforeDay(7, DateTimePatternEnum.YYYY_MM_DD.getPattern());
        List<String> dateList = DateUtil.getDateRange(beforeDate,
                DateUtil.format(new Date(), DateTimePatternEnum.YYYY_MM_DD.getPattern()), DateTimePatternEnum.YYYY_MM_DD.getPattern());
        for (String date : dateList) {
            statisticsInfoService.statisticsData(date);
        }
        return getSuccessResponseVO(null);
    }

    /**
     * 同步商品
     * 管理端单个商品重同步。商品存在性由 MySQL 决定，包括逻辑删除后的记录。
     */
    @PostMapping("/productData")
    public ResponseVO productData(String productId) {
        requireKey(productId);
        if (productInfoService.getProductInfoByProductId(productId) == null) {
            throw new BusinessException("商品不存在");
        }
        searchSyncService.resyncProduct(productId);
        return getSuccessResponseVO(null);
    }

    /**
     * 把问题放入知识库当中
     * 根据 FAQ ID 追加一次向量同步，已删除的 FAQ 也可用此入口补清理。
     * */
    @PostMapping("/ragData")
    public ResponseVO ragData(Integer questionId) {
        if (questionId == null || questionId <= 0) {
            throw new BusinessException("请输入有效的 FAQ ID");
        }
        searchSyncService.resyncQuestion(questionId);
        return getSuccessResponseVO(null);
    }

    /**
     * 把所有商品放入elasticsearch
     * 存量初始化按主键分页，结果返回下一页游标，单次最多登记 50 个对象。
     * */
    @PostMapping("/syncBackfill")
    public ResponseVO syncBackfill(String source, @RequestParam(defaultValue = "") String cursor,
                                   @RequestParam(defaultValue = "25") int limit) {
        checkBatch(limit);
        if ("product".equals(source)) {
            if (!cursor.isEmpty()) {
                requireKey(cursor);
            }
            List<String> keys = searchSyncService.backfillProducts(cursor, limit);
            return getSuccessResponseVO(Map.of("count", keys.size(), "nextCursor", keys.isEmpty() ? cursor : keys.getLast()));
        }
        if ("faq".equals(source)) {
            int after = cursor.isEmpty() ? 0 : parseCursor(cursor);
            List<Integer> keys = searchSyncService.backfillQuestions(after, limit);
            return getSuccessResponseVO(Map.of("count", keys.size(), "nextCursor", keys.isEmpty() ? after : keys.getLast()));
        }
        throw new BusinessException("同步类型无效");
    }

    /**
     * 把旧版留在 Redis、还没处理完的任务转到新版的 MySQL business_task 表，让新版 Worker 接着处理
     * 手动迁移一批旧任务，返回三个队列各自处理的数量，方便判断是否需要继续。
     */
    @PostMapping("/migrateLegacyTasks")
    public ResponseVO migrateLegacyTasks(@RequestParam(defaultValue = "25") int limit) {
        checkBatch(limit);
        return getSuccessResponseVO(legacyTaskMigration.migrate(limit));
    }

    /**
     * 查询任务
     * @param type 任务类型
     * @param bizKey 业务编号
     * @param status 任务状态
     * @param limit 每批最多处理 50 条
     * 从 MySQL 的 business_task 表读取任务记录，可以看某件商品的同步任务现在执行到哪一步了
     * Admin 拦截器校验登录。列表只展示运维字段，任务载荷和租约令牌不进入 HTTP 响应。
     */
    @PostMapping("/syncTasks")
    public ResponseVO syncTasks(String type, String bizKey, String status, @RequestParam(defaultValue = "25") int limit) {
        checkBatch(limit);
        if (type != null && !TASK_TYPES.contains(type)) {
            throw new BusinessException("任务类型无效");
        }
        if (bizKey != null) {
            requireKey(bizKey);
        }
        if (status != null) {
            try {
                TaskStatus.valueOf(status);
            } catch (IllegalArgumentException invalid) {
                throw new BusinessException("任务状态无效");
            }
        }
        return getSuccessResponseVO(taskMapper.listManaged(TASK_TYPES, type, bizKey, status, limit).stream()
                .map(TaskRow::from).toList());
    }

    /**
     * 管理端任务列表里「重试」按钮调用的接口。它把一条已经进入 DEAD（尝试次数耗尽）的任务重新排队，交给 Worker 再执行
     * 只把白名单内的 DEAD 任务重新排队，由 Worker 使用原任务 ID 再执行。
     */
    @PostMapping("/retrySyncTask")
    public ResponseVO retrySyncTask(String taskId) {
        if (taskId == null || !taskId.matches("[a-f0-9]{32}")) {
            throw new BusinessException("任务编号无效");
        }
        BusinessTask task = businessTaskService.findById(taskId);
        if (task == null || !TASK_TYPES.contains(task.getTaskType()) || !businessTaskService.retryDead(taskId)) {
            throw new BusinessException("只能重试当前已失败的业务任务");
        }
        return getSuccessResponseVO(null);
    }

    /**
     * 检查业务编号是否有效
     * @param key 业务编号
     */
    private static void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new BusinessException("业务编号无效");
        }
    }

    /**
     * 检查批量处理数量是否在合理范围
     * @param limit 批量处理数量
     */
    private static void checkBatch(int limit) {
        if (limit < 1 || limit > MAX_BATCH) {
            throw new BusinessException("每批最多处理 50 条");
        }
    }

    /**
     * 解析游标
     * @param cursor 游标
     * @return 游标对应的值
     */
    private static int parseCursor(String cursor) {
        try {
            int value = Integer.parseInt(cursor);
            if (value >= 0) {
                return value;
            }
        } catch (NumberFormatException invalid) {
            // 使用一致的业务提示处理非法或越界的游标。
        }
        throw new BusinessException("FAQ 游标无效");
    }

    /**
     * 管理页面展示的任务摘要。数据库载荷和租约令牌保留在内部。
     */
    public record TaskRow(String taskId, String taskType, String bizKey, long bizVersion, TaskStatus status,
                          int attempts, int maxAttempts, LocalDateTime nextRunAt, String lastError) {
        private static TaskRow from(BusinessTask task) {
            return new TaskRow(task.getTaskId(), task.getTaskType(), task.getBizKey(), task.getBizVersion(),
                    task.getStatus(), task.getAttempts(), task.getMaxAttempts(), task.getNextRunAt(), task.getLastError());
        }
    }
}
