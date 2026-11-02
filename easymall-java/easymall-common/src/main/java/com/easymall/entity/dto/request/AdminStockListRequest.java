package com.easymall.entity.dto.request;

import com.easymall.entity.constants.Constants;
import com.easymall.entity.query.ProductSkuQuery;

import java.util.Map;

/** 库存预警阈值由服务端决定，页面只能选择合法分页和排序。 */
public class AdminStockListRequest extends PageListRequest {
    public ProductSkuQuery toQuery() {
        ProductSkuQuery query = prepare(new ProductSkuQuery(), "ProductSkuMapper", null,
                Map.of("stock", "p.stock", "sort", "p.sort"));
        query.setLessStock(Constants.LENGTH_10);
        return query;
    }
}
