package com.easymall.validation.sort;

import com.easymall.entity.query.BaseParam;
import com.easymall.entity.query.OrderInfoQuery;
import com.easymall.entity.query.OrderCommentQuery;
import com.easymall.entity.query.SysCategoryQuery;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 不连接数据库，直接检查各 Mapper 在生成 SQL 前能否拦截外部排序文本。 */
class MapperSortBoundaryTest {
    private static final String[] MAPPERS = {"AgentMessage", "OrderComment", "OrderInfo", "OrderItem",
            "OrderLogisticsInfo", "OrderLogisticsInfoRecord", "ProductCart", "ProductInfo",
            "ProductPropertyValue", "ProductSku", "RagQuestion", "StatisticsInfo", "SysCategory",
            "SysProductProperty", "UserAddress", "UserInfo"};
    private static final Configuration CONFIGURATION = new Configuration();

    @BeforeAll
    static void loadMappings() throws Exception {
        for (String mapper : MAPPERS) {
            String resource = "com/easymall/mappers/" + mapper + "Mapper.xml";
            try (InputStream input = MapperSortBoundaryTest.class.getClassLoader().getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, CONFIGURATION, resource, CONFIGURATION.getSqlFragments()).parse();
            }
        }
    }

    @ParameterizedTest
    @MethodSource("mapperNames")
    void injectedOrderTextIsRejectedBeforeSqlExists(String mapper) throws Exception {
        BaseParam query = query(mapper);
        query.setOrderBy("sort desc; select sleep(1)");
        assertThatThrownBy(() -> sql(mapper, query)).isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @MethodSource("mapperNames")
    void omittedSortingRemainsValid(String mapper) throws Exception {
        assertThat(sql(mapper, query(mapper)).toLowerCase()).doesNotContain("order by");
    }

    @ParameterizedTest
    @ValueSource(strings = {"AgentMessage|message_id desc", "OrderInfo|o.order_time desc",
            "OrderComment|comment_time desc", "OrderComment|o.comment_time desc",
            "OrderLogisticsInfoRecord|record_time desc", "ProductCart|p.last_update_time desc",
            "ProductInfo|create_time desc", "ProductInfo|p.create_time desc",
            "ProductPropertyValue|property_sort asc", "ProductSku|sort asc",
            "RagQuestion|r.question_id desc", "StatisticsInfo|statistics_date asc,data_type asc",
            "SysCategory|sort asc", "SysCategory|s.sort asc", "SysCategory|s.sort asc,sp.property_sort asc",
            "UserAddress|default_type desc", "UserInfo|u.join_time desc"})
    void everyExistingServerSortRemainsSupported(String specification) throws Exception {
        String[] fields = specification.split("\\|", 2);
        BaseParam query = query(fields[0]);
        query.setOrderBy(fields[1]);
        if (query instanceof SysCategoryQuery category && fields[1].contains("sp.property_sort")) {
            category.setQueryProperty(true);
        }
        assertThat(sql(fields[0], query).replaceAll("\\s+", " "))
                .contains("order by " + fields[1]);
    }

    private static Stream<String> mapperNames() { return Stream.of(MAPPERS); }

    @Test
    void orderProductFilterIsAParameterForListAndCount() {
        OrderInfoQuery query = new OrderInfoQuery();
        query.setProductNameFuzzy("synthetic' or 1=1 --");
        for (String statement : new String[]{"selectList", "selectCount"}) {
            BoundSql bound = CONFIGURATION.getMappedStatement("com.easymall.mappers.OrderInfoMapper." + statement)
                    .getBoundSql(Map.of("query", query));
            assertThat(bound.getSql()).contains("filter_item.product_name like concat('%', ?, '%')")
                    .doesNotContain(query.getProductNameFuzzy());
            assertThat(bound.getParameterMappings()).extracting("property").contains("query.productNameFuzzy");
        }
    }

    @Test
    void commentFiltersAreBoundParametersWithoutDependingOnJoinSwitches() {
        OrderCommentQuery query = new OrderCommentQuery();
        query.setNickNameFuzzy("synthetic' or 1=1 --");
        query.setProductNameFuzzy("synthetic-product");
        for (String statement : new String[]{"selectList", "selectCount"}) {
            BoundSql bound = CONFIGURATION.getMappedStatement("com.easymall.mappers.OrderCommentMapper." + statement)
                    .getBoundSql(Map.of("query", query));
            assertThat(bound.getSql()).contains("filter_user.nick_name like concat('%', ?, '%')")
                    .contains("filter_product.product_name like concat('%', ?, '%')")
                    .doesNotContain(query.getNickNameFuzzy()).doesNotContain(query.getProductNameFuzzy());
            assertThat(bound.getParameterMappings()).extracting("property")
                    .contains("query.nickNameFuzzy", "query.productNameFuzzy");
        }
    }

    private static BaseParam query(String mapper) throws Exception {
        return (BaseParam) Class.forName("com.easymall.entity.query." + mapper + "Query")
                .getDeclaredConstructor().newInstance();
    }

    private static String sql(String mapper, BaseParam query) {
        return CONFIGURATION.getMappedStatement("com.easymall.mappers." + mapper + "Mapper.selectList")
                .getBoundSql(Map.of("query", query)).getSql();
    }
}
