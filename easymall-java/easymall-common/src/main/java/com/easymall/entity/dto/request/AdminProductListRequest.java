package com.easymall.entity.dto.request;

import com.easymall.entity.query.ProductInfoQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/** 商品列表保留现有筛选参数，并显式接收前端已提交的每页数量。 */
@Getter @Setter
public class AdminProductListRequest extends PageListRequest {
    @Size(max = 200)
    private String productNameFuzzy;
    @Size(max = 15)
    private String categoryIdOrPCategoryId;
    @Min(0) @Max(1)
    private Integer commendType;
    @Min(-1) @Max(1)
    private Integer status;

    public ProductInfoQuery toQuery() {
        ProductInfoQuery query = prepare(new ProductInfoQuery(), "ProductInfoMapper", "p.create_time desc",
                Map.of("createTime", "p.create_time", "minPrice", "p.min_price", "totalSale", "p.total_sale"));
        query.setProductNameFuzzy(productNameFuzzy);
        query.setCategoryIdOrPCategoryId(categoryIdOrPCategoryId);
        query.setCommendType(commendType);
        query.setStatus(status);
        return query;
    }
}
