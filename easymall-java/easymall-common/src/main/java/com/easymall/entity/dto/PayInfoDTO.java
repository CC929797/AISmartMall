package com.easymall.entity.dto;

import java.math.BigDecimal;

public class PayInfoDTO {
    private String payInfo;
    private String payOrderId;
    private BigDecimal amount;
    /** PROCESSING/CREATED/RETRY；CREATED表示订单创建成功，不表示支付成功。 */
    private String requestStatus;
    private String requestId;
    /** READY/UNAVAILABLE/NOT_PAYABLE；链接不可用时仍返回已创建的支付业务号。 */
    private String paymentStatus;
    /** 原支付组中的一个订单号，供用户继续支付原订单。 */
    private String orderId;

    public String getOrderId() {
        return orderId;
    }
    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getRequestStatus() {
        return requestStatus;
    }
    public void setRequestStatus(String requestStatus) {
        this.requestStatus = requestStatus;
    }
    public String getRequestId() {
        return requestId;
    }
    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
    public String getPaymentStatus() {
        return paymentStatus;
    }
    public void setPaymentStatus(String paymentStatus) {
        this.paymentStatus = paymentStatus;
    }

    public PayInfoDTO() {
    }

    public PayInfoDTO(String payInfo, String payOrderId, BigDecimal amount) {
        this.payInfo = payInfo;
        this.payOrderId = payOrderId;
        this.amount = amount;
    }

    public String getPayInfo() {
        return payInfo;
    }

    public void setPayInfo(String payInfo) {
        this.payInfo = payInfo;
    }

    public String getPayOrderId() {
        return payOrderId;
    }

    public void setPayOrderId(String payOrderId) {
        this.payOrderId = payOrderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}