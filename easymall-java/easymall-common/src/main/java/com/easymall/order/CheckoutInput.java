package com.easymall.order;

import com.easymall.entity.dto.PostOrderDTO;
import com.easymall.entity.dto.PostOrderItemDTO;
import com.easymall.entity.enums.OrderFromTypeEnum;
import com.easymall.entity.enums.PayChannelEnum;
import com.easymall.exception.BusinessException;
import com.easymall.validation.InputLimits;
import com.easymall.validation.InputValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 【正式业务】下单请求的规范化入口：先校验，再合并相同商品/SKU并排序，最后计算稳定摘要。
 * 同SKU备注必须一致，合并后数量仍受99件上限约束；不采用客户端价格。
 * 不修改调用方DTO，避免重试时原请求被扣库存代码改写。
 */

/**
 * 规范化下单请求。
 * @param order 规范化后的下单请求
 * @param digest 购买内容的摘要(根据地址、支付方式、购买来源、商品/SKU、数量、备注转成JSON并计算SHA-256得到64位十六进制摘要字符串)
 * digest作用：判断同一个 requestId，重试时购买内容有没有被改动。
 */
public record CheckoutInput(PostOrderDTO order, String digest) {
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 规范化下单请求：校验参数、合并相同商品/SKU并排序，计算摘要。
     * @param input
     * @return
     */
    public static CheckoutInput normalize(PostOrderDTO input) {
        InputValidator.validateBean(input);
        if (PayChannelEnum.getByPayScene(input.getPayMethod()) == null || OrderFromTypeEnum.getByType(input.getOrderFrom()) == null) {
            throw new BusinessException("购买来源或支付方式无效");
        }
        TreeMap<String, PostOrderItemDTO> merged = new TreeMap<>();
        for (PostOrderItemDTO item : input.getOrderList()) {
            if (!item.getProductId().equals(item.getProductId().strip()) || !item.getPropertyValueIds().equals(item.getPropertyValueIds().strip())) {
                throw new BusinessException("商品或SKU标识无效");
            }
            // JSON数组编码避免直接拼接标识产生边界碰撞；排序确保不同明细顺序得到同一摘要。
            String key = json(List.of(item.getProductId(), item.getPropertyValueIds()));
            String remark = item.getRemark() == null ? "" : item.getRemark();
            PostOrderItemDTO existing = merged.get(key);
            if (existing == null) {
                existing = new PostOrderItemDTO();
                existing.setProductId(item.getProductId()); existing.setPropertyValueIds(item.getPropertyValueIds());
                existing.setBuyCount(item.getBuyCount()); existing.setRemark(remark); merged.put(key, existing);
            } else {
                if (!existing.getRemark().equals(remark)) throw new BusinessException("相同SKU的备注不一致，请合并后提交");
                int quantity = Math.addExact(existing.getBuyCount(), item.getBuyCount());
                if (quantity > InputLimits.MAX_BUY_COUNT) throw new BusinessException("相同SKU合计不能超过99件");
                existing.setBuyCount(quantity);
            }
        }
        PostOrderDTO normalized = new PostOrderDTO();
        normalized.setRequestId(input.getRequestId()); normalized.setAddressId(input.getAddressId());
        normalized.setOrderFrom(input.getOrderFrom()); normalized.setPayMethod(input.getPayMethod());
        normalized.setOrderList(List.copyOf(merged.values()));
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("addressId", normalized.getAddressId()); canonical.put("orderFrom", normalized.getOrderFrom());
        canonical.put("payMethod", normalized.getPayMethod());
        canonical.put("items", normalized.getOrderList().stream().map(item -> List.of(
                item.getProductId(), item.getPropertyValueIds(), item.getBuyCount(), item.getRemark())).toList());
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json(canonical).getBytes(StandardCharsets.UTF_8)));
            return new CheckoutInput(normalized, hash);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception failure) { throw new BusinessException("下单参数无效"); }
    }
    /** 不在诊断字符串中暴露地址、备注等购买信息。 */
    @Override public String toString() { return "CheckoutInput[normalized]"; }
}
