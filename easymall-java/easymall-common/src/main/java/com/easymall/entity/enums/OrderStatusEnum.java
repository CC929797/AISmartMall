package com.easymall.entity.enums;

import java.util.Arrays;
import java.util.Optional;

public enum OrderStatusEnum {
    DELETE(-1, "已删除"),
    WAIT_PAYMENT(0, "待付款"),
    PAID(1, "已付款,待发货"),
    SHIPPED(2, "已发货"),
    COMPLETED(3, "已完成"),
    CANCELLED(4, "交易取消"),
    CLOSED(5, "交易关闭"),
    REFUNDED(6, "已退款,交易关闭"),
    PARTIALLY_REFUNDED(7, "部分退款"),
    CANCEL_PENDING(8, "取消确认中"),
    CLOSE_PENDING(9, "超时关单确认中");

    /** 付款成功仍可从这两种未决状态确认，直到可信的关闭结果完成落库。 */
    public static boolean isClosing(Integer status) {
        return CANCEL_PENDING.getStatus().equals(status) || CLOSE_PENDING.getStatus().equals(status);
    }

    private Integer status;
    private String desc;

    OrderStatusEnum(Integer status, String desc) {
        this.status = status;
        this.desc = desc;
    }

    public Integer getStatus() {
        return status;
    }

    public String getDesc() {
        return desc;
    }


    public static OrderStatusEnum getByStatus(Integer status) {
        Optional<OrderStatusEnum> typeEnum = Arrays.stream(OrderStatusEnum.values()).filter(value -> value.getStatus().equals(status)).findFirst();
        return typeEnum.orElse(null);
    }
}
