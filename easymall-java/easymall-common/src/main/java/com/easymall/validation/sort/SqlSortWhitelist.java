package com.easymall.validation.sort;

import com.easymall.entity.enums.ResponseCodeEnum;
import com.easymall.exception.BusinessException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** SQL 排序只能由固定列名和方向重建；不能将调用方字符串原样拼入 SQL。 */
public final class SqlSortWhitelist {
    private static final Map<String, Set<String>> COLUMNS = Map.ofEntries(
            columns("AgentMessageMapper", "a", "message_id", "send_time"),
            columns("OrderCommentMapper", "o", "order_id", "comment_time", "star"),
            columns("OrderInfoMapper", "o", "order_id", "order_time", "amount", "order_status"),
            columns("OrderItemMapper", "o", "order_item_id", "order_id"),
            columns("OrderLogisticsInfoMapper", "o", "order_id", "logistics_status"),
            columns("OrderLogisticsInfoRecordMapper", "o", "record_id", "record_time"),
            columns("ProductCartMapper", "p", "cart_id", "last_update_time", "create_time"),
            columns("ProductInfoMapper", "p", "product_id", "create_time", "min_price", "total_sale"),
            columns("ProductPropertyValueMapper", "p", "property_id", "property_value_id", "property_sort", "sort"),
            columns("ProductSkuMapper", "p", "product_id", "property_value_id_hash", "stock", "price", "sort"),
            columns("RagQuestionMapper", "r", "question_id", "create_time"),
            columns("StatisticsInfoMapper", "s", "statistics_date", "data_type"),
            Map.entry("SysCategoryMapper", categoryColumns()),
            columns("SysProductPropertyMapper", "s", "property_id", "property_sort"),
            columns("UserAddressMapper", "u", "address_id", "default_type"),
            columns("UserInfoMapper", "u", "user_id", "join_time", "last_login_time", "nick_name", "status")
    );

    private SqlSortWhitelist() { }

    /** Mapper 在产生排序 SQL 之前调用，非法列、方向、表达式和未知 Mapper 均拒绝。 */
    public static String forMapper(String mapper, String orderBy) {
        if (orderBy == null) { return null; }
        Set<String> allowed = COLUMNS.get(mapper);
        if (allowed == null || orderBy.isBlank()) { throw invalid(); }
        String[] segments = orderBy.toLowerCase(Locale.ROOT).split(",", -1);
        if (segments.length > 4) { throw invalid(); }
        List<String> clauses = new ArrayList<>();
        for (String segment : segments) {
            String[] words = segment.trim().split("\\s+");
            if (words.length != 2 || !allowed.contains(words[0])) { throw invalid(); }
            String direction = switch (words[1]) {
                case "asc" -> "asc";
                case "desc" -> "desc";
                default -> throw invalid();
            };
            // 从白名单中取出常量列名，绝不返回原始排序片段。
            String column = allowed.stream().filter(value -> value.equals(words[0])).findFirst().orElseThrow();
            clauses.add(column + " " + direction);
        }
        return String.join(",", clauses);
    }

    /** 页面只提交业务字段名，服务端映射到该列表允许排序的固定数据库列。 */
    public static String requested(String mapper, String field, String direction, String defaultOrder, Map<String, String> fields) {
        boolean noField = field == null || field.isEmpty();
        boolean noDirection = direction == null || direction.isEmpty();
        if (noField) {
            if (!noDirection) { throw invalid(); }
            return forMapper(mapper, defaultOrder);
        }
        String column = fields.get(field);
        if (column == null) { throw invalid(); }
        String safeDirection = noDirection ? "desc" : switch (direction) {
            case "asc" -> "asc";
            case "desc" -> "desc";
            default -> throw invalid();
        };
        return forMapper(mapper, column + " " + safeDirection);
    }

    private static Map.Entry<String, Set<String>> columns(String mapper, String alias, String... names) {
        Set<String> columns = new HashSet<>();
        for (String name : names) {
            columns.add(name);
            columns.add(alias + "." + name);
        }
        return Map.entry(mapper, Set.copyOf(columns));
    }

    private static Set<String> categoryColumns() {
        Set<String> columns = new HashSet<>(columns("SysCategoryMapper", "s", "category_id", "sort").getValue());
        columns.add("sp.property_sort");
        return Set.copyOf(columns);
    }

    private static BusinessException invalid() { return new BusinessException(ResponseCodeEnum.CODE_600); }
}
