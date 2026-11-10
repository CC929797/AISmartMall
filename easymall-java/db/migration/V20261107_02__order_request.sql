-- 步骤08：在商城业务库执行一次。要求MySQL 8.0.16+ / InnoDB；应用不自动迁移。
-- 先执行11A的business_task迁移。本脚本只新增表，不修改已有订单/库存字段，不删除数据。
CREATE TABLE order_request (
    user_id VARCHAR(15) COLLATE utf8mb4_bin NOT NULL COMMENT '可信登录用户，幂等键的用户范围',
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '前端一次购买意图的稳定编号',
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '合并排序后的请求SHA256，不保存收货信息或备注原文',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'PROCESSING/CREATED/RETRY',
    pay_order_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '首次预留后固定复用的支付业务号',
    amount DECIMAL(12,2) NULL COMMENT '订单事务提交的支付组总金额',
    lease_owner CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL COMMENT '处理进程崩溃后允许接管的UTC时间',
    last_error VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '固定错误码，不保存异常正文',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, request_id),
    UNIQUE KEY uk_order_request_pay (pay_order_id),
    KEY idx_order_request_recovery (status, lease_until),
    CONSTRAINT chk_order_request_status CHECK (status IN ('PROCESSING','CREATED','RETRY')),
    CONSTRAINT chk_order_request_lease CHECK (
        (status='PROCESSING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (status<>'PROCESSING' AND lease_owner IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT chk_order_request_amount CHECK (status<>'CREATED' OR (amount IS NOT NULL AND amount>0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='步骤08下单请求幂等记录';
