package com.easymall.validation.sort;

import com.easymall.commonent.EsSearchComponent;
import com.easymall.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 搜索参数错误不能被吞成“搜索无结果”，也不能进入 ES 查询。 */
class ProductSearchInputTest {
    private EsSearchComponent search;
    private ElasticsearchOperations elasticsearch;

    @BeforeEach
    void setUp() {
        search = new EsSearchComponent();
        elasticsearch = mock(ElasticsearchOperations.class);
        ReflectionTestUtils.setField(search, "elasticsearchOperations", elasticsearch);
    }

    @ParameterizedTest
    @CsvSource({"asc;select,price,1", "up,price,1", "asc,unknown,1", "asc,price desc,1",
            "asc,price,0", "asc,price,-1", "asc,price,100001"})
    void rejectsInvalidSortAndPagination(String direction, String field, Integer pageNo) {
        assertThatThrownBy(() -> search.searchProduct("toy", null, null, direction, field, pageNo))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(elasticsearch);
    }

    @ParameterizedTest
    @CsvSource({"-1,10", "10,-1", "20,10"})
    void rejectsInvalidPriceRange(BigDecimal from, BigDecimal to) {
        assertThatThrownBy(() -> search.searchProduct("toy", from, to, "asc", "price", 1))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(elasticsearch);
    }
}
