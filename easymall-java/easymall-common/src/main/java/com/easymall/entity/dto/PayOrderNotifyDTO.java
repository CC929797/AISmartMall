package com.easymall.entity.dto;

public class PayOrderNotifyDTO {
    /**
     * 支付订单ID
     */
    private String payOrderId;
    /**
     * 支付订单号 通道订单号
     */
    private String channelOrderId;
    /** 支付渠道确认的金额，用于与本地整组订单金额核对；不能取客户端金额。 */
    private java.math.BigDecimal amount;

    public java.math.BigDecimal getAmount() { return amount; }
    public void setAmount(java.math.BigDecimal amount) { this.amount = amount; }

    public PayOrderNotifyDTO() {
    }

    public PayOrderNotifyDTO(String payOrderId, String channelOrderId) {
        this.payOrderId = payOrderId;
        this.channelOrderId = channelOrderId;
    }

    public String getPayOrderId() {
        return payOrderId;
    }

    public void setPayOrderId(String payOrderId) {
        this.payOrderId = payOrderId;
    }

    public String getChannelOrderId() {
        return channelOrderId;
    }

    public void setChannelOrderId(String channelOrderId) {
        this.channelOrderId = channelOrderId;
    }
}
