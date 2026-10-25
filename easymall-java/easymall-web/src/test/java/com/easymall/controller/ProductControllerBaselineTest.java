package com.easymall.controller;

import com.easymall.commonent.EsSearchComponent;
import com.easymall.entity.enums.CommendTypeEnum;
import com.easymall.entity.enums.ProductStatusEnum;
import com.easymall.entity.po.ProductInfo;
import com.easymall.entity.po.SysCategory;
import com.easymall.entity.query.ProductInfoQuery;
import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.entity.vo.ProductInfoDTO;
import com.easymall.exception.BusinessException;
import com.easymall.service.ProductInfoService;
import com.easymall.service.SysCategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP binding and response baseline only. All service calls are mocked;
 * this test does not start application infrastructure or verify authentication.
 */
@ExtendWith(MockitoExtension.class)
class ProductControllerBaselineTest {

    @Mock
    private SysCategoryService sysCategoryService;

    @Mock
    private ProductInfoService productInfoService;

    @Mock
    private EsSearchComponent esSearchComponent;

    @InjectMocks
    private ProductController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AGlobalExceptionHandlerController())
                .build();
    }

    @Test
    void categoryResponsePreservesTheCategoryTree() throws Exception {
        SysCategory root = category("fixture-electronics", "Electronics");
        root.setChildren(List.of(category("fixture-laptops", "Laptops")));
        when(sysCategoryService.getAllCategoryList()).thenReturn(List.of(root));

        mockMvc.perform(get("/product/loadCategory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].categoryId").value("fixture-electronics"))
                .andExpect(jsonPath("$.data[0].children[0].categoryName").value("Laptops"));

        verify(sysCategoryService).getAllCategoryList();
        verifyNoInteractions(productInfoService, esSearchComponent);
    }

    @Test
    void categoryProductsForwardThePageAndKeepTheOnSaleFilter() throws Exception {
        ProductInfo product = new ProductInfo();
        product.setProductId("fixture-product-001");
        product.setProductName("Fixture laptop");
        when(productInfoService.findListByPage(any(ProductInfoQuery.class)))
                .thenReturn(new PaginationResultVO<>(31, 15, 2, 3, List.of(product)));

        mockMvc.perform(get("/product/loadProduct")
                        .param("pageNo", "2")
                        .param("categoryId", "fixture-laptops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.pageNo").value(2))
                .andExpect(jsonPath("$.data.totalCount").value(31))
                .andExpect(jsonPath("$.data.list[0].productId").value("fixture-product-001"));

        ArgumentCaptor<ProductInfoQuery> query = ArgumentCaptor.forClass(ProductInfoQuery.class);
        verify(productInfoService).findListByPage(query.capture());
        assertThat(query.getValue().getPageNo()).isEqualTo(2);
        assertThat(query.getValue().getCategoryId()).isEqualTo("fixture-laptops");
        assertThat(query.getValue().getStatus()).isEqualTo(ProductStatusEnum.ON_SALE.getStatus());
        assertThat(query.getValue().getCommendType()).isNull();
        verifyNoInteractions(sysCategoryService, esSearchComponent);
    }

    @Test
    void homeProductsUseTheNonRecommendedList() throws Exception {
        when(productInfoService.findListByPage(any(ProductInfoQuery.class)))
                .thenReturn(new PaginationResultVO<>(0, 15, 1, 0, List.of()));

        mockMvc.perform(get("/product/loadProduct").param("pageNo", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list").isEmpty());

        ArgumentCaptor<ProductInfoQuery> query = ArgumentCaptor.forClass(ProductInfoQuery.class);
        verify(productInfoService).findListByPage(query.capture());
        assertThat(query.getValue().getStatus()).isEqualTo(ProductStatusEnum.ON_SALE.getStatus());
        assertThat(query.getValue().getCommendType()).isEqualTo(CommendTypeEnum.NOT_COMMEND.getType());
        verifyNoInteractions(sysCategoryService, esSearchComponent);
    }

    @Test
    void searchBindsDecimalPricesAndForwardsSearchOptions() throws Exception {
        ProductInfoDTO product = new ProductInfoDTO();
        product.setProductId("fixture-search-001");
        product.setMinPrice(new BigDecimal("3999.50"));
        when(esSearchComponent.searchProduct("laptop", new BigDecimal("3000.10"),
                new BigDecimal("5000.90"), "asc", "price", 2))
                .thenReturn(new PaginationResultVO<>(16, 15, 2, 2, List.of(product)));

        mockMvc.perform(get("/product/search")
                        .param("keyWords", "laptop")
                        .param("priceFrom", "3000.10")
                        .param("priceTo", "5000.90")
                        .param("sortType", "asc")
                        .param("sortField", "price")
                        .param("pageNo", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.pageNo").value(2))
                .andExpect(jsonPath("$.data.list[0].productId").value("fixture-search-001"))
                .andExpect(jsonPath("$.data.list[0].minPrice").value(3999.50));

        verify(esSearchComponent).searchProduct("laptop", new BigDecimal("3000.10"),
                new BigDecimal("5000.90"), "asc", "price", 2);
        verifyNoInteractions(sysCategoryService, productInfoService);
    }

    @Test
    void missingProductKeepsTheExistingBusinessErrorEnvelope() throws Exception {
        when(productInfoService.getProductInfo("fixture-missing"))
                .thenThrow(new BusinessException("Fixture product unavailable"));

        mockMvc.perform(get("/product/getProduct").param("productId", "fixture-missing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value(600))
                .andExpect(jsonPath("$.info").value("Fixture product unavailable"));

        verify(productInfoService).getProductInfo("fixture-missing");
        verifyNoInteractions(sysCategoryService, esSearchComponent);
    }

    private static SysCategory category(String id, String name) {
        SysCategory category = new SysCategory();
        category.setCategoryId(id);
        category.setCategoryName(name);
        return category;
    }
}
