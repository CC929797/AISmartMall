CREATE TABLE refund_request (
    refund_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '商城退款单号，作为主键',
    user_id VARCHAR(15) COLLATE utf8mb4_general_ci NOT NULL COMMENT '申请退款的用户ID，用于校验退款单归属',
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '商城退款请求号，与用户ID共同保证请求幂等，重试沿用原编号',
    order_id VARCHAR(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT '退款明细所属的商城主订单ID',
    order_item_id VARCHAR(40) COLLATE utf8mb4_general_ci NOT NULL COMMENT '本次退款对应的订单明细ID',
    pay_order_id VARCHAR(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT '原支付单号，对应支付宝商户订单号 out_trade_no',
    pay_scene VARCHAR(20) COLLATE utf8mb4_general_ci NOT NULL COMMENT '原订单的支付场景，用于选择对应的支付适配器',
    channel_request_no VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '发给支付渠道的退款请求号，对应支付宝 out_request_no，查询与重试始终复用',
    amount DECIMAL(10,2) NOT NULL COMMENT '申请退款金额，单位元，取整条订单明细的实付金额',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'PROCESSING 处理中，UNKNOWN 结果待确认，SUCCESS 成功，FAILED 明确失败',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '处理版本号，领取补偿任务或更新退款结果时递增，防止旧结果覆盖新状态',
    last_error VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '最近一次记录的错误类型，退款成功时清空',
    next_check_at DATETIME(6) NULL COMMENT '下一次允许后台查证的最早时间，UTC，退款成功或明确失败后为空',
    created_at DATETIME(6) NOT NULL COMMENT '退款单创建时间，UTC',
    updated_at DATETIME(6) NOT NULL COMMENT '退款单最近更新时间，UTC',
    occupied_item_id VARCHAR(40) COLLATE utf8mb4_general_ci
        GENERATED ALWAYS AS (CASE WHEN status <> 'FAILED' THEN order_item_id ELSE NULL END) STORED
        COMMENT '数据库生成的明细占用键，处理中、待确认和成功时占用，明确失败后释放',
    PRIMARY KEY (refund_id),
    UNIQUE KEY uk_refund_request (user_id, request_id),
    UNIQUE KEY uk_refund_active_item (occupied_item_id),
    UNIQUE KEY uk_refund_channel (channel_request_no),
    KEY idx_refund_due (next_check_at, refund_id),
    KEY idx_refund_item (order_item_id, created_at),
    CONSTRAINT chk_refund_amount CHECK (amount > 0),
    CONSTRAINT chk_refund_state CHECK (
        (status IN ('PROCESSING','UNKNOWN') AND next_check_at IS NOT NULL)
        OR (status IN ('SUCCESS','FAILED') AND next_check_at IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='步骤09退款请求与渠道结果';
