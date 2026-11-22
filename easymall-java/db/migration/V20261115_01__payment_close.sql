ALTER TABLE order_info
    MODIFY COLUMN order_status TINYINT(1) DEFAULT NULL
        COMMENT '-1已删除 0待付款 1已付款待发货 2已发货 3已完成 4已取消 5已关闭 6已退款 7部分退款 8取消确认中 9超时关单确认中',
    ADD COLUMN payment_expires_at DATETIME(3) NULL
        COMMENT '新订单的固定支付截止时间，与支付宝付款表单一致，历史订单为空',
    ADD COLUMN payment_link_issued TINYINT(1) NULL
        COMMENT '0尚未向客户端签发付款链接 1可能已签发 NULL历史订单签发情况未知',
    ADD COLUMN payment_close_check_at DATETIME(3) NULL
        COMMENT '关单结果下一次允许查证的时间，结束关单时清空',
    ADD INDEX idx_order_payment_expiry (order_status, payment_expires_at, order_time),
    ADD INDEX idx_order_payment_close_check (order_status, payment_close_check_at);
