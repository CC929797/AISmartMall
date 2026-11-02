package com.easymall.controller;

import com.easymall.entity.dto.TokenUserInfoDTO;
import com.easymall.entity.po.ProductCart;
import com.easymall.service.ProductCartService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 保留前端表单协议，并验证购物车 DTO 的约束与服务端字段边界。 */
class CartRequestBoundaryTest {
    private ProductCartService service;
    private MockMvc mvc;

    @BeforeEach
    void startMvc() {
        service = mock(ProductCartService.class);
        ProductCartController controller = new ProductCartController() {
            @Override
            protected TokenUserInfoDTO getTokenUserInfo() {
                TokenUserInfoDTO user = new TokenUserInfoDTO();
                user.setUserId("CARTOWNER1");
                return user;
            }
        };
        ReflectionTestUtils.setField(controller, "productCartService", service);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AGlobalExceptionHandlerController()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "100", "2147483647", "invalid"})
    void invalidQuantityIsRejectedBeforeService(String quantity) throws Exception {
        mvc.perform(post("/productCart/add2Cart").param("productId", "PRODUCT1")
                        .param("propertyValueIds", "SKU1").param("buyCount", quantity))
                .andExpect(jsonPath("$.code").value(600));
        verifyNoInteractions(service);
    }

    @Test
    void blankProductIsRejectedBeforeService() throws Exception {
        mvc.perform(post("/productCart/add2Cart").param("productId", " ")
                        .param("propertyValueIds", "SKU1").param("buyCount", "1"))
                .andExpect(jsonPath("$.code").value(600));
        verifyNoInteractions(service);
    }

    @Test
    void legalFormCannotSelectAnotherUserOrBindPersistenceFields() throws Exception {
        mvc.perform(post("/productCart/add2Cart").param("productId", "PRODUCT1")
                        .param("propertyValueIds", "SKU1").param("buyCount", "99")
                        .param("userId", "OTHERUSER1").param("cartId", "OTHER-CART")
                        .param("propertyValueIdHash", "injected-hash").param("createTime", "2026-01-01"))
                .andExpect(jsonPath("$.code").value(200));
        ArgumentCaptor<ProductCart> saved = ArgumentCaptor.forClass(ProductCart.class);
        verify(service).add2Cart(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo("CARTOWNER1");
        assertThat(saved.getValue().getBuyCount()).isEqualTo(99);
        assertThat(saved.getValue().getCartId()).isNull();
        assertThat(saved.getValue().getPropertyValueIdHash()).isNull();
        assertThat(saved.getValue().getCreateTime()).isNull();
    }
}
