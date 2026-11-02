package com.easymall.validation.sort;

import com.easymall.commonent.EsSearchComponent;
import com.easymall.entity.vo.ProductInfoDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 搜索白名单必须保留当前页面的综合、销量、价格排序与默认分页契约。 */
class ProductSearchContractTest {
    private EsSearchComponent search;
    private ElasticsearchOperations elasticsearch;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        search = new EsSearchComponent();
        elasticsearch = mock(ElasticsearchOperations.class);
        ReflectionTestUtils.setField(search, "elasticsearchOperations", elasticsearch);
        SearchHits<ProductInfoDTO> hits = mock(SearchHits.class);
        when(hits.getSearchHits()).thenReturn(List.of());
        when(elasticsearch.search(any(CriteriaQuery.class), eq(ProductInfoDTO.class))).thenReturn(hits);
    }

    @ParameterizedTest
    @CsvSource({",,_score,DESC", "asc,price,minPrice,ASC", "desc,price,minPrice,DESC",
            "desc,sale,totalSale,DESC", "desc,composite,_score,DESC"})
    void acceptedSortEnumsBecomeFixedEsFields(String direction, String field, String expectedField,
                                               Sort.Direction expectedDirection) {
        var result = search.searchProduct("toy", null, null, direction, field, null);
        ArgumentCaptor<CriteriaQuery> query = ArgumentCaptor.forClass(CriteriaQuery.class);
        verify(elasticsearch).search(query.capture(), eq(ProductInfoDTO.class));
        Sort.Order order = query.getValue().getPageable().getSort().getOrderFor(expectedField);
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(expectedDirection);
        assertThat(query.getValue().getPageable().getPageNumber()).isZero();
        assertThat(query.getValue().getPageable().getPageSize()).isEqualTo(15);
        assertThat(result.getPageNo()).isEqualTo(1);
    }
}
