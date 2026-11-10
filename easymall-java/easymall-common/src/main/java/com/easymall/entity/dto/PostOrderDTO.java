package com.easymall.entity.dto;

import jakarta.validation.Valid;
import com.easymall.validation.InputLimits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public class PostOrderDTO {

    /** 一次购买意图的稳定编号；网络重试必须复用，购买内容变化后生成新编号。 */
    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9_-]{16,64}")
    private String requestId;

    @NotBlank
    @Size(max = 20)
    private String payMethod;

    @NotBlank
    @Size(max = 15)
    private String addressId;

    @NotEmpty
    @Size(max = InputLimits.MAX_ORDER_ITEMS)
    @Valid
    private List<@NotNull PostOrderItemDTO> orderList;

    @NotNull
    @Min(0)
    @Max(1)
    private Integer orderFrom;

    public String getRequestId() {
        return requestId;
    }
    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Integer getOrderFrom() {
        return orderFrom;
    }

    public void setOrderFrom(Integer orderFrom) {
        this.orderFrom = orderFrom;
    }

    public String getPayMethod() {
        return payMethod;
    }

    public void setPayMethod(String payMethod) {
        this.payMethod = payMethod;
    }

    public List<PostOrderItemDTO> getOrderList() {
        return orderList;
    }

    public void setOrderList(List<PostOrderItemDTO> orderList) {
        this.orderList = orderList;
    }

    public String getAddressId() {
        return addressId;
    }

    public void setAddressId(String addressId) {
        this.addressId = addressId;
    }
}
