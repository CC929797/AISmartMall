package com.easymall.sync;

/**
 * 单个业务对象在某个同步目标下的进度。一个商品会分别记录关键词索引和向量索引的进度。
 *
 * @param bizKey 商品 ID 或 FAQ ID
 * @param requestedVersion 最新需要同步的版本，业务每次登记时递增
 * @param publishedVersion 最后一次完整写入并发布的版本，等于 0 表示尚未发布过
 */
public record SearchSyncState(String bizKey, long requestedVersion, long publishedVersion) {
}
