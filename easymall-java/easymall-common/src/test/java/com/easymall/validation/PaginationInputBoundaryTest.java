package com.easymall.validation;

import com.easymall.entity.query.BaseParam;
import com.easymall.entity.query.SimplePage;
import com.easymall.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaginationInputBoundaryTest {
    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 100001, Integer.MAX_VALUE})
    void queryRejectsInvalidPageNumbers(int pageNo) {
        assertThatThrownBy(() -> new BaseParam().setPageNo(pageNo)).isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101, Integer.MAX_VALUE})
    void queryRejectsInvalidPageSizes(int pageSize) {
        assertThatThrownBy(() -> new BaseParam().setPageSize(pageSize)).isInstanceOf(BusinessException.class);
    }

    @Test
    void directPagingCannotCreateAnUnboundedLimit() {
        assertThatThrownBy(() -> new SimplePage(1, 500, 101)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new SimplePage(0, 101)).isInstanceOf(BusinessException.class);
    }

    @Test
    void omittedAndValidQueryPagingRemainCompatible() {
        BaseParam query = new BaseParam();
        query.setPageNo(null);
        query.setPageSize(null);
        query.setPageNo(1);
        query.setPageSize(100);
        assertThat(query.getPageNo()).isEqualTo(1);
        assertThat(query.getPageSize()).isEqualTo(100);
        assertThat(new SimplePage(1, 500, 100).getStart()).isZero();
        assertThat(new SimplePage(0, 11).getEnd()).isEqualTo(11);
    }
}
