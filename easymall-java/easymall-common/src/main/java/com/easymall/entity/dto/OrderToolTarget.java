package com.easymall.entity.dto;

import java.util.regex.Pattern;

/** 模型提取的资源线索。订单号、明细号和支付单号各自独立。 */
public record OrderToolTarget(String orderId, String orderItemId, String payOrderId) {
    private static final Pattern ORDER_ID = Pattern.compile("[A-Za-z0-9]{1,32}");
    private static final Pattern ORDER_ITEM_ID = Pattern.compile("[A-Za-z0-9]{1,32}_[1-9][0-9]{0,6}");

    /** 允许省略全部线索来查列表，指定其他类型编号时拒绝该查询。 */
    public boolean invalidOrderLookup() {
        return orderItemId != null || payOrderId != null || (orderId != null && !ORDER_ID.matcher(orderId).matches());
    }

    /** 校验退款明细号的格式，以及它与同时提供的主订单号是否对应。 */
    public boolean invalidRefundTarget() {
        if (payOrderId != null) {
            return true;
        }
        if (orderItemId == null) {
            return orderId != null;
        }
        return !ORDER_ITEM_ID.matcher(orderItemId).matches() || (orderId != null && !orderItemId.startsWith(orderId + "_"));
    }
}
