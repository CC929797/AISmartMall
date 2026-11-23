package com.easymall.mappers;

import com.easymall.sync.SearchSyncState;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 对应 SearchSyncMapper.xml，负责持久化同步版本和校验 Worker 的执行资格。 */
public interface SearchSyncMapper {
    /** 首次写入版本 1，已有状态则原子递增版本。 */
    int request(@Param("type") String type, @Param("key") String key);
    SearchSyncState find(@Param("type") String type, @Param("key") String key);
    List<SearchSyncState> findBatch(@Param("type") String type, @Param("keys") List<String> keys);
    /** 当前版本未发布且对象空闲时，绑定本次有效租约。 */
    int acquire(@Param("type") String type, @Param("key") String key, @Param("version") long version,
                @Param("taskId") String taskId, @Param("owner") String owner);
    /** 仅当前版本的有效执行者能把索引标记为可读。 */
    int publish(@Param("type") String type, @Param("key") String key, @Param("version") long version,
                @Param("taskId") String taskId, @Param("owner") String owner);
    /** 按任务与令牌清理本次占用，避免释放其他执行者的资格。 */
    int release(@Param("type") String type, @Param("key") String key,
                @Param("taskId") String taskId, @Param("owner") String owner);
    List<String> productsAfter(@Param("cursor") String cursor, @Param("limit") int limit);
    List<Integer> questionsAfter(@Param("cursor") int cursor, @Param("limit") int limit);
    /** 批量确认向量命中的 FAQ 仍存在于业务库。 */
    List<String> existingQuestions(@Param("keys") List<String> keys);
}
