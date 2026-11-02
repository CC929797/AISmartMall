package com.easymall.entity.dto;

import com.easymall.validation.InputLimits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class PostOrderItemDTO {

    @NotBlank
    @Size(max = 15)
    private String productId;

    @NotBlank
    @Size(max = 500)
    private String propertyValueIds;

    @NotNull
    @Min(1)
    @Max(InputLimits.MAX_BUY_COUNT)
    private Integer buyCount;

    @Size(max = 300)
    private String remark;

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getPropertyValueIds() {
        return propertyValueIds;
    }

    public void setPropertyValueIds(String propertyValueIds) {
        this.propertyValueIds = propertyValueIds;
    }

    public Integer getBuyCount() {
        return buyCount;
    }

    public void setBuyCount(Integer buyCount) {
        this.buyCount = buyCount;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
