package com.easymall.validation.sort;

import com.easymall.entity.dto.request.*;
import com.easymall.entity.query.BaseParam;
import com.easymall.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 明确每个列表的可提交字段、固定排序映射以及对内部查询参数的隔离。 */
class ListRequestContractTest {
    @Test
    void userFilterAndSafeSortMapToANewInternalQuery() {
        AdminUserListRequest request = new AdminUserListRequest();
        request.setNickNameFuzzy("合成用户");
        request.setStatus(1);
        request.setPageNo(2);
        request.setPageSize(30);
        request.setSortField("lastLoginTime");
        request.setSortDirection("asc");
        var query = request.toQuery();
        assertThat(query.getNickNameFuzzy()).isEqualTo("合成用户");
        assertThat(query.getStatus()).isEqualTo(1);
        assertThat(query.getPageNo()).isEqualTo(2);
        assertThat(query.getPageSize()).isEqualTo(30);
        assertThat(query.getOrderBy()).isEqualTo("u.last_login_time asc");
        assertThat(query.getSimplePage()).isNull();
        assertThat(query.getPassword()).isNull();
        assertThat(query.getApiKey()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "u.join_time", "joinTime desc", "joinTime;select", "unknown"})
    void userSortAcceptsOnlyTheBusinessFieldEnum(String field) {
        AdminUserListRequest request = new AdminUserListRequest();
        request.setSortField(field);
        assertThatThrownBy(request::toQuery).isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ascending", "asc;select", "ASC", "desc nulls last"})
    void directionMustBeAnExplicitSupportedEnum(String direction) {
        AdminUserListRequest request = new AdminUserListRequest();
        request.setSortField("joinTime");
        request.setSortDirection(direction);
        assertThatThrownBy(request::toQuery).isInstanceOf(BusinessException.class);
    }

    @Test
    void everyAdminListKeepsItsExistingDefaultsAndServerQuerySwitches() {
        assertThat(new AdminUserListRequest().toQuery().getOrderBy()).isEqualTo("u.join_time desc");
        assertThat(new AdminOrderListRequest().toQuery().getOrderBy()).isEqualTo("o.order_time desc");
        assertThat(new AdminCommentListRequest().toQuery().getOrderBy()).isEqualTo("o.comment_time desc");
        assertThat(new AdminRagListRequest().toQuery().getOrderBy()).isEqualTo("r.question_id desc");
        assertThat(new AdminProductListRequest().toQuery().getOrderBy()).isEqualTo("p.create_time desc");
        assertThat(new AdminStockListRequest().toQuery().getLessStock()).isEqualTo(10);
        assertThat(new AdminOrderListRequest().toQuery().getQueryItems()).isTrue();
        assertThat(new AdminOrderListRequest().toQuery().getQueryUser()).isTrue();
        assertThat(new AdminCommentListRequest().toQuery().getQueryProduct()).isTrue();
        assertThat(new AdminCommentListRequest().toQuery().getQueryUserInfo()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"page0", "pageHuge", "size0", "sizeHuge", "directionWithoutField"})
    void listContractsRejectInvalidPageAndIncompleteSort(String scenario) {
        AdminUserListRequest request = new AdminUserListRequest();
        switch (scenario) {
            case "page0" -> request.setPageNo(0);
            case "pageHuge" -> request.setPageNo(100001);
            case "size0" -> request.setPageSize(0);
            case "sizeHuge" -> request.setPageSize(101);
            case "directionWithoutField" -> request.setSortDirection("asc");
        }
        assertThatThrownBy(request::toQuery).isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"u.password asc", "u.join_time desc;select 1", "rand() asc",
            "join_time asc/*comment*/", "o.order_time desc", "join_time", "join_time desc,"})
    void internalSqlDefenseRejectsExpressionsAndColumnsOutsideTheMapper(String order) {
        assertThatThrownBy(() -> SqlSortWhitelist.forMapper("UserInfoMapper", order))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void supportedCaseAndWhitespaceAreRebuiltFromConstants() {
        assertThat(SqlSortWhitelist.forMapper("UserInfoMapper", "  U.JOIN_TIME   DESC  "))
                .isEqualTo("u.join_time desc");
        assertThatThrownBy(() -> SqlSortWhitelist.forMapper("UnknownMapper", "join_time desc"))
                .isInstanceOf(BusinessException.class);
    }
}
