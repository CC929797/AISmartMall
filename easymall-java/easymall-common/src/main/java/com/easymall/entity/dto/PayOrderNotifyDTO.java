package com.easymall.entity.dto;

import java.math.BigDecimal;

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
    private BigDecimal amount;
    /** 渠道原始交易状态，例如 TRADE_SUCCESS。 */
    private String tradeStatus;
    /** 验签后核对的应用及收款方标识。主动查单成功时由适配器填充应用标识。 */
    private String appId;
    private String sellerId;

    public String getTradeStatus() {
        return tradeStatus;
    }
    public void setTradeStatus(String tradeStatus) {
        this.tradeStatus = tradeStatus;
    }
    public String getAppId() {
        return appId;
    }
    public void setAppId(String appId) {
        this.appId = appId;
    }
    public String getSellerId() {
        return sellerId; 
    }
    public void setSellerId(String sellerId) {
        this.sellerId = sellerId;
    }

    public java.math.BigDecimal getAmount() {
        return amount;
    }
    public void setAmount(java.math.BigDecimal amount) {
        this.amount = amount;
    }

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
