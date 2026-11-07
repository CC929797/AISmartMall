-- 11A: MySQL 8.0.16+ / InnoDB。先在明确选中的隔离库执行；应用不自动迁移。
-- 纯新增表，不修改旧表，不清理旧Redis任务。所有DATETIME列均按UTC读写。
CREATE TABLE business_task (
    task_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    biz_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    biz_version BIGINT NOT NULL,
    payload MEDIUMTEXT COLLATE utf8mb4_bin NOT NULL,
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,
    next_run_at DATETIME(6) NOT NULL,
    lease_owner CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    last_error VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (task_id),
    UNIQUE KEY uk_task_event (task_type, biz_key, biz_version),
    KEY idx_task_due (task_type, status, next_run_at, task_id),
    KEY idx_task_lease (task_type, status, lease_until, task_id),
    CONSTRAINT chk_task_status CHECK (status IN ('READY','RUNNING','RETRY','SUCCESS','DEAD')),
    CONSTRAINT chk_task_attempts CHECK (attempts >= 0 AND max_attempts BETWEEN 1 AND 3),
    CONSTRAINT chk_task_version CHECK (biz_version >= 0),
    CONSTRAINT chk_task_lease CHECK (
        (status = 'RUNNING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'RUNNING' AND lease_owner IS NULL AND lease_until IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='11A可靠任务与事务Outbox';
