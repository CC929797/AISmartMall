package com.easymall.entity.vo;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;


import java.math.BigDecimal;

@Document(indexName = "easymall-index")
public class ProductInfoDTO {

    /**
     * 商品ID
     */
    @Field(type = FieldType.Keyword)
    @Id
    private String productId;

    /**
     * 商品名称
     */
    @Field(type = FieldType.Text,
            analyzer = "ik_max_word",
            searchAnalyzer = "ik_smart",
            fielddata = true)
    private String productName;

    /**
     * 封面
     */
    @Field(type = FieldType.Keyword, index = false)
    private String cover;


    /**
     * 最低价格
     */
    @Field(type = FieldType.Double)
    private BigDecimal minPrice;

    /**
     * 最高价格
     */
    @Field(type = FieldType.Double)
    private BigDecimal maxPrice;

    /**
     * 销量
     */
    @Field(type = FieldType.Integer)
    private Integer totalSale;

    /** 用来核对 MySQL 的同步版本。旧索引还没有版本的文档读到 null。 */
    @JsonIgnore
    @ReadOnlyProperty
    @Field(type = FieldType.Long)
    private Long syncVersion;

    public Long getSyncVersion() {
        return syncVersion;
    }

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

    public String getCover() {
        return cover;
    }

    public void setCover(String cover) {
        this.cover = cover;
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
}
