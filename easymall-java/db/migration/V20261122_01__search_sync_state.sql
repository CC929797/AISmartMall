-- 11B 前置迁移。选择目标商城库后执行一次，和 11A 的 business_task 配套使用。
-- 本表保存同步进度，待执行事件、重试时间与租约有效期保存在 business_task。
CREATE TABLE search_sync_state (
    task_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '同步目标，例如 PRODUCT_SEARCH、PRODUCT_VECTOR、FAQ_VECTOR',
    biz_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '商品 ID 或 FAQ ID，按同步目标区分',
    requested_version BIGINT NOT NULL DEFAULT 1 COMMENT 'MySQL 当前需要同步的版本，在业务事务内单调递增',
    published_version BIGINT NOT NULL DEFAULT 0 COMMENT '已经完整写入并可以对外读取的版本，0 表示尚未同步成功',
    running_task_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '正在同步此对象的可靠任务 ID',
    running_lease_owner CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '正在同步此对象的任务租约令牌，租约到期可接管',
    updated_at DATETIME(6) NOT NULL COMMENT '同步状态最近更新时间，固定使用 UTC',
    PRIMARY KEY (task_type, biz_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='商品搜索和知识库同步版本及同对象执行状态';
