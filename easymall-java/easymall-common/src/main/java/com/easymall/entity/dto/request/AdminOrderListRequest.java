package com.easymall.entity.dto.request;

import com.easymall.entity.query.OrderInfoQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/** 后台订单列表不接收内部查询开关、SQL 排序或任意用户条件。 */
@Getter @Setter
public class AdminOrderListRequest extends PageListRequest {
    @Size(max = 200)
    private String productNameFuzzy;
    @Min(-1) @Max(7)
    private Integer orderStatus;

    public OrderInfoQuery toQuery() {
        OrderInfoQuery query = prepare(new OrderInfoQuery(), "OrderInfoMapper", "o.order_time desc",
                Map.of("orderTime", "o.order_time", "amount", "o.amount", "orderStatus", "o.order_status"));
        query.setProductNameFuzzy(productNameFuzzy);
        query.setOrderStatus(orderStatus);
        query.setQueryItems(true);
        query.setQueryUser(true);
        return query;
    }
}
