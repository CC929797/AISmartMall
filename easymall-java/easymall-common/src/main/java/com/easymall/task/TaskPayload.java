package com.easymall.task;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeSet;

/**
 * 【后续业务内部工具】校验并规范化任务JSON，为同一事件的载荷一致性检查生成摘要。
 * 仅在任务包内部使用，不是测试辅助类，也不负责执行JSON描述的业务。
 */
final class TaskPayload {
    // 拒绝重复字段、限制嵌套；使用高精度数字，避免不同载荷因浮点舍入变成相同内容。
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(65536).build()).build())
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);

    /** 对象字段递归排序，数组顺序保留，使仅字段顺序不同的JSON得到一致表示。 */
    static String canonical(String input) {
        if (input == null || input.getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw new IllegalArgumentException("Task payload must be a JSON object of at most 64 KiB");
        }
        try {
            JsonNode node = JSON.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(input);
            if (node == null || !node.isObject()) throw new IllegalArgumentException();
            return JSON.writeValueAsString(sorted(node));
        } catch (Exception ignored) {
            // 使用固定提示，避免解析器的异常正文带出原始任务内容。
            throw new IllegalArgumentException("Task payload must be a valid JSON object");
        }
    }

    /** 为规范化后的JSON生成SHA-256摘要；这是内容校验，不是加密或密码存储。 */
    static String hash(String payload) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    /** 递归整理对象键名；数组中元素的前后次序本身有意义，不能排序。 */
    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            TreeSet<String> names = new TreeSet<>();
            node.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> result.set(name, sorted(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JSON.createArrayNode();
            node.forEach(value -> result.add(sorted(value)));
            return result;
        }
        return node;
    }

    private TaskPayload() { }
}
