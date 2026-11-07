package com.easymall.entity.enums;

import com.easymall.utils.StringTools;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public enum PromptTypeEnum {

    GLOBAL("global", """
            # 角色与核心目标
            你是智能购物助手，帮助用户查找商品、了解商城规则、查询本人订单，并提供操作指引。
            """, "全局系统提示词"),

    USER_INTENT("userIntentV2", """
            请分析用户消息的购物意图,并提取关键信息。
            
            用户问题: %s
            
            请从以下意图类型中选择最匹配的一个：
            INTENT TYPES:
            - PRODUCT_SEARCH: 搜索商品、想买东西、查看商品信息（如：我想买手机，找一下连衣裙），关键信息返回具体的商品信息。
            - QUERY_ORDER: 查询订单（如：我的订单、查一下订单 20251229161636SGUCPYYI1TXOEPHY）。
            - REFUND: 退款退货申请（如：我想退款，商品有问题要退货）
            - CANCEL_ORDER: 取消已下单但未发货或未完成的订单（如：我不想要了，帮我取消订单，取消我刚买的那个东西）
            - CONFIRM_RECEIPT: 用户确认已收到货物（如：我已经收到货了，点哪里确认收货，那个东西我拿到了）
            - QUERY_LOGISTICS: 物流查询、快递跟踪、包裹状态查询（如：我的快递到哪了？查一下物流，跟踪包裹）
            - PRODUCT_REVIEW: 用户主动对已收货商品给出评价（如："给上个订单好评"、"这东西太差了，我要评价"、"给一个好评吧"、"给一个差评"）
            - CHAT: 一般性问题，比如 问候、打招呼，商品对比，购物细则等
            
            返回JSON格式，包含以下字段：
            - intent: 意图类型（使用上面的类型值）
            - data: 仅用于商品搜索关键词。
            - orderId: 主订单号，如 20251229161636SGUCPYYI1TXOEPHY。
            - orderItemId: 商品明细号，如 20251229161636SGUCPYYI1TXOEPHY_1。
            - payOrderId: 支付单号，仅在用户明确提供支付单号时提取。
            - 仅提取明确提供的编号，省略未提供的字段。不要补造编号或用户身份。
            - 用户只说“我的订单”“看看最近的订单”等、没有提供任何编号时，返回 QUERY_ORDER，
              orderId、orderItemId、payOrderId 均为 null。后端会查询本人最近15天的订单，无需追问订单号。
            示例响应：
            {
              "intent": "PRODUCT_SEARCH",
              "data":"运动鞋"
            }
            """, "用户意图提示词"),
    PRODUCT_SEARCH("product_search", """
            请基于以下商品信息回答用户问题：
            商品数据：
            ```json
              %s
            ```
            用户问题：
            %s
            
            请严格按照以下要求执行：
            1. 只返回JSON数组，不要有任何额外文本
            2. JSON数组中每个对象只包含productId字段
            3. 只使用提供的商品信息中的ID，不要自己编造ID
            4. 如果用户问题不明确或没有相关商品，返回空数组：[]
            5. 严格按照以下格式：
            [
              {"productId": "147099495496439"},
              {"productId": "326818100160840"}
            ]
            """, "商品搜索"),
    QUERY_ORDER("query_order", "", "查询订单"),
    REFUND("refund", "", "退款"),
    CANCEL_ORDER("cancel_order", "", "取消订单"),
    CONFIRM_RECEIPT("confirm_receipt", "", "确认收货"),
    QUERY_LOGISTICS("query_logistics", "", "查询物流信息"),
    PRODUCT_REVIEW("product_review", "", "评价订单"),
    CHAT("chatV2", """
             请基于以下知识库内容回答用户问题，保持友好专业：
             回答要求：
             1. 只基于提供的知识库内容回答，不要编造信息
             2. 回答要专业、友好、简洁
             3. 对于政策类问题，需要明确说明条件和限制
             4. 如果涉及多个方面，请分点说明
             5. 知识库中包含图片信息也一并返回不要过滤
             知识库内容：
             %s
            
             用户问题:%s
            
            """, "一般性问题"),
    ;

    private String key;
    private String prompt;
    private String desc;

    PromptTypeEnum(String key, String prompt, String desc) {
        this.key = key;
        this.prompt = prompt;
        this.desc = desc;
    }

    public String getKey() {
        return key;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getDesc() {
        return desc;
    }

    public static PromptTypeEnum getByCode(String code) {
        Optional<PromptTypeEnum> typeEnum = Arrays.stream(PromptTypeEnum.values()).filter(value -> value.toString().equals(code)).findFirst();
        return typeEnum == null || typeEnum.isEmpty() ? null : typeEnum.get();
    }

    public static PromptTypeEnum getByKey(String key) {
        Optional<PromptTypeEnum> typeEnum = Arrays.stream(PromptTypeEnum.values()).filter(value -> value.getKey().equals(key)).findFirst();
        return typeEnum == null || typeEnum.isEmpty() ? null : typeEnum.get();
    }


    public record Prompt(String key, String prompt, String desc) {
        public static Prompt of(PromptTypeEnum typeEnum) {
            return new Prompt(typeEnum.getKey(), typeEnum.getPrompt(), typeEnum.getDesc());
        }
    }

    public static List<Prompt> getPrompts() {
        return Arrays.stream(PromptTypeEnum.values()).filter(value -> !StringTools.isEmpty(value.getPrompt())).map(Prompt::of).toList();
    }
}
