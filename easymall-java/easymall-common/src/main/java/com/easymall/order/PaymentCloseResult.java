package com.easymall.order;

/** 支付渠道对一次关单请求的结果，由订单服务结合查单结果决定是否归还库存。 */
public enum PaymentCloseResult {
    /** 渠道确认已关闭本次请求对应的交易，可以进入商城的关单事务。 */
    CLOSED,
    /** 渠道明确查无此单，仍需结合表单截止时间和后续查单结果决定是否收尾。 */
    NOT_FOUND,
    /** 超时、系统错误或应答单号异常等情况，尚不能确认这笔交易的状态。 */
    UNKNOWN
}
