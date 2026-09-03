package com.easymall.entity.vo;

import com.easymall.valid.updateGroup;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.util.Date;

public class ProductListVO {
    /**
     * 商品ID
     */
    @NotEmpty(groups = {updateGroup.class})
    private String productId;

    /**
     * 商品名称
     */
    @NotEmpty
    private String productName;

    /**
     * 商品描述
     */
    @NotEmpty
    private String productDesc;

    /**
     * 封面
     */
    @NotEmpty
    private String cover;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    /**
     * 分类ID
     */
    @NotEmpty
    private String categoryId;

    /**
     * 分类父ID
     */
    @NotEmpty
    private String pCategoryId;

    /**
     * -1:已删除 0:下架  1:上架
     */
    private Integer status;

    /**
     * 最低价格
     */
    private BigDecimal minPrice;

    /**
     * 最高价格
     */
    private BigDecimal maxPrice;

    /**
     * 销量
     */
    private Integer totalSale;

    /**
     * 0:未推荐 1:已经推荐
     */
    private Integer commendType;

    /**
     * 分类名称
     */
    private String categoryName;

    /**
     * Sku库存
     */
    private Integer skuCount;

    /**
     * 总库存
     */
    private Integer totalStock;

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getProductDesc() {
        return productDesc;
    }

    public void setProductDesc(String productDesc) {
        this.productDesc = productDesc;
    }

    public String getCover() {
        return cover;
    }

    public void setCover(String cover) {
        this.cover = cover;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
    }

    public String getpCategoryId() {
        return pCategoryId;
    }

    public void setpCategoryId(String pCategoryId) {
        this.pCategoryId = pCategoryId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public BigDecimal getMinPrice() {
        return minPrice;
    }

    public void setMinPrice(BigDecimal minPrice) {
        this.minPrice = minPrice;
    }

    public BigDecimal getMaxPrice() {
        return maxPrice;
    }

    public void setMaxPrice(BigDecimal maxPrice) {
        this.maxPrice = maxPrice;
    }

    public Integer getTotalSale() {
        return totalSale;
    }

    public void setTotalSale(Integer totalSale) {
        this.totalSale = totalSale;
    }

    public Integer getCommendType() {
        return commendType;
    }

    public void setCommendType(Integer commendType) {
        this.commendType = commendType;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryNames) {
        this.categoryName = categoryNames;
    }

    public Integer getSkuCount() {
        return skuCount;
    }

    public void setSkuCount(Integer skuCount) {
        this.skuCount = skuCount;
    }

    public Integer getTotalStock() {
        return totalStock;
    }

    public void setTotalStock(Integer totalStock) {
        this.totalStock = totalStock;
    }
}
