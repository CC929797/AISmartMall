package com.easymall.service;

import com.easymall.entity.po.ProductCart;
import com.easymall.entity.po.ProductSku;
import com.easymall.entity.query.ProductCartQuery;
import com.easymall.exception.BusinessException;
import com.easymall.mappers.ProductCartMapper;
import com.easymall.mappers.ProductSkuMapper;
import com.easymall.service.Impl.ProductCartServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 购物车输入和累加数量必须先校验，拒绝请求不能提前写入更新时间或数量。 */
@SuppressWarnings("unchecked")
class CartInputBoundaryTest {
    private ProductCartServiceImpl service;
    private ProductCartMapper carts;
    private ProductSkuMapper skus;

    @BeforeEach
    void setUp() {
        carts = mock(ProductCartMapper.class);
        skus = mock(ProductSkuMapper.class);
        service = new ProductCartServiceImpl();
        ReflectionTestUtils.setField(service, "productCartMapper", carts);
        ReflectionTestUtils.setField(service, "productSkuMapper", skus);
    }

    @ParameterizedTest
    @MethodSource("invalidCarts")
    void malformedCartsNeverReachMappers(ProductCart request) {
        assertThatThrownBy(() -> service.add2Cart(request)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(carts, skus);
    }

    @Test
    void accumulationCannotExceedTheLimitOrWriteBeforeItsCheck() {
        when(skus.selectByProductIdAndPropertyValueIdHash(any(), any())).thenReturn(new ProductSku());
        ProductCart existing = validCart();
        existing.setCartId("CART1");
        existing.setBuyCount(99);
        when(carts.selectByProductIdAndPropertyValueIdHashAndUserId(any(), any(), any())).thenReturn(existing);

        assertThatThrownBy(() -> service.add2Cart(validCart())).isInstanceOf(BusinessException.class);

        verify(carts, never()).updateByParam(any(), any());
        verify(carts, never()).insert(any());
        verify(carts, never()).updateCartBuyCount(any(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 99})
    void validFirstAdditionPreservesOwnerAndQuantity(int quantity) {
        when(skus.selectByProductIdAndPropertyValueIdHash(any(), any())).thenReturn(new ProductSku());
        when(carts.insert(any())).thenReturn(1);
        ProductCart request = validCart();
        request.setBuyCount(quantity);

        service.add2Cart(request);

        verify(carts).insert(request);
        assertThat(request.getUserId()).isEqualTo("CARTUSER1");
        assertThat(request.getBuyCount()).isEqualTo(quantity);
        assertThat(request.getPropertyValueIdHash()).hasSize(32);
    }

    @Test
    void concurrentQuantityChangeMustRejectInsteadOfAddingPastTheReadSnapshot() {
        when(skus.selectByProductIdAndPropertyValueIdHash(any(), any())).thenReturn(new ProductSku());
        ProductCart existing = validCart();
        existing.setCartId("CART1");
        existing.setBuyCount(98);
        when(carts.selectByProductIdAndPropertyValueIdHashAndUserId(any(), any(), any())).thenReturn(existing);
        when(carts.updateByParam(any(), any())).thenAnswer(invocation -> {
            ProductCartQuery query = invocation.getArgument(1);
            // 模拟数据库数量已被另一请求改变，带旧数量条件的更新不再命中。
            return query.getCartId() == null ? 1 : 0;
        });

        assertThatThrownBy(() -> service.add2Cart(validCart())).isInstanceOf(BusinessException.class);
        verify(carts, never()).updateCartBuyCount(any(), any());
    }

    @Test
    void legalAccumulationReachesNinetyNineWithTheOwnerAndOldQuantityCondition() {
        when(skus.selectByProductIdAndPropertyValueIdHash(any(), any())).thenReturn(new ProductSku());
        ProductCart existing = validCart();
        existing.setCartId("CART1");
        existing.setBuyCount(98);
        when(carts.selectByProductIdAndPropertyValueIdHashAndUserId(any(), any(), any())).thenReturn(existing);
        AtomicBoolean quantityUpdated = new AtomicBoolean();
        when(carts.updateByParam(any(), any())).thenAnswer(invocation -> {
            ProductCart update = invocation.getArgument(0);
            ProductCartQuery query = invocation.getArgument(1);
            if (query.getCartId() != null) {
                assertThat(query.getCartId()).isEqualTo("CART1");
                assertThat(query.getUserId()).isEqualTo("CARTUSER1");
                assertThat(query.getBuyCount()).isEqualTo(98);
                assertThat(update.getBuyCount()).isEqualTo(99);
                quantityUpdated.set(true);
            }
            return 1;
        });

        service.add2Cart(validCart());

        assertThat(quantityUpdated).isTrue();
        verify(carts, never()).updateCartBuyCount(any(), any());
    }

    static Stream<ProductCart> invalidCarts() {
        List<ProductCart> requests = new ArrayList<>();
        requests.add(null);
        for (Integer quantity : new Integer[]{null, 0, -1, 100, Integer.MAX_VALUE}) {
            requests.add(changed(cart -> cart.setBuyCount(quantity)));
        }
        requests.add(changed(cart -> cart.setUserId(" ")));
        requests.add(changed(cart -> cart.setUserId("U".repeat(16))));
        requests.add(changed(cart -> cart.setProductId(" ")));
        requests.add(changed(cart -> cart.setProductId("P".repeat(16))));
        requests.add(changed(cart -> cart.setPropertyValueIds(" ")));
        requests.add(changed(cart -> cart.setPropertyValueIds("S".repeat(501))));
        return requests.stream();
    }

    private static ProductCart changed(Consumer<ProductCart> change) {
        ProductCart cart = validCart();
        change.accept(cart);
        return cart;
    }

    static ProductCart validCart() {
        ProductCart cart = new ProductCart();
        cart.setUserId("CARTUSER1");
        cart.setProductId("PRODUCT1");
        cart.setPropertyValueIds("SKU1");
        cart.setBuyCount(1);
        return cart;
    }
}
