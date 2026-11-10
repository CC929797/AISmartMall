package com.easymall.service;

import com.easymall.commonent.RedisComponent;
import com.easymall.commonent.SpringContext;
import com.easymall.entity.config.AppConfig;
import com.easymall.entity.dto.PayInfoDTO;
import com.easymall.entity.dto.PostOrderDTO;
import com.easymall.entity.dto.PostOrderItemDTO;
import com.easymall.exception.BusinessException;
import com.easymall.mappers.*;
import com.easymall.entity.po.*;
import com.easymall.entity.enums.PayChannelEnum;
import com.easymall.service.Impl.OrderInfoServiceImpl;
import com.easymall.order.OrderRequest;
import com.easymall.order.OrderRequestService;
import com.easymall.task.BusinessTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证直接调用订单服务也无法绕过输入边界，非法请求在访问数据库前被拒绝。 */
class OrderInputBoundaryTest {
    private OrderInfoServiceImpl service;
    private final List<Object> dependencies = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new OrderInfoServiceImpl();
        dependency("orderInfoMapper", OrderInfoMapper.class);
        dependency("userAddressMapper", UserAddressMapper.class);
        dependency("productInfoMapper", ProductInfoMapper.class);
        dependency("productPropertyValueMapper", ProductPropertyValueMapper.class);
        dependency("productSkuMapper", ProductSkuMapper.class);
        dependency("orderItemMapper", OrderItemMapper.class);
        dependency("productCartMapper", ProductCartMapper.class);
        dependency("orderLogisticsInfoMapper", OrderLogisticsInfoMapper.class);
        dependency("redisComponent", RedisComponent.class);
        dependency("appConfig", AppConfig.class);
        dependency("orderRequestService", OrderRequestService.class);
        dependency("businessTaskService", BusinessTaskService.class);
    }

    @ParameterizedTest
    @MethodSource("invalidOrders")
    void invalidOrdersNeverReachInfrastructure(PostOrderDTO request) {
        assertThatThrownBy(() -> service.postOrder("ORDERUSER1", request)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(dependencies.toArray());
    }

    @Test
    void missingTrustedUserNeverReachesInfrastructure() {
        assertThatThrownBy(() -> service.postOrder(" ", validOrder())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(dependencies.toArray());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 99})
    @SuppressWarnings("unchecked")
    void legalQuantityUsesDatabasePriceAndProducesOneStockDeduction(int quantity) {
        UserAddress address = new UserAddress();
        address.setUserId("ORDERUSER1");
        address.setAddressee("合成收货人");
        address.setAddress("合成订单地址");
        address.setPhone("13000000000");
        when(dep("userAddressMapper", UserAddressMapper.class).selectByAddressId("ADDRESS1")).thenReturn(address);
        ProductInfo product = new ProductInfo();
        product.setProductId("PRODUCT1");
        product.setProductName("合成订单商品");
        product.setStatus(1);
        product.setCover("synthetic-cover.png");
        when(dep("productInfoMapper", ProductInfoMapper.class).selectList(any())).thenReturn(List.of(product));
        ProductPropertyValue property = new ProductPropertyValue();
        property.setProductId("PRODUCT1");
        property.setPropertyValueId("SKU1");
        property.setPropertyName("颜色");
        property.setPropertyValue("蓝色");
        when(dep("productPropertyValueMapper", ProductPropertyValueMapper.class).selectList(any())).thenReturn(List.of(property));
        ProductSku sku = new ProductSku();
        sku.setProductId("PRODUCT1");
        sku.setPropertyValueIds("SKU1");
        sku.setPropertyValueIdHash(com.easymall.utils.StringTools.encodeByMD5("SKU1"));
        sku.setPrice(new BigDecimal("12.34"));
        sku.setStock(100);
        when(dep("productSkuMapper", ProductSkuMapper.class).selectList(any())).thenReturn(List.of(sku));
        when(dep("productSkuMapper", ProductSkuMapper.class).updateStockBatch(any())).thenAnswer(invocation -> {
            List<OrderItem> changes = invocation.getArgument(0);
            assertThat(changes).hasSize(1);
            assertThat(changes.get(0).getBuyCount()).isEqualTo(-quantity);
            return 1;
        });
        when(dep("appConfig", AppConfig.class).getOrderExpireMinute()).thenReturn(10);
        when(dep("orderRequestService", OrderRequestService.class).place(eq("ORDERUSER1"), any(), any())).thenAnswer(invocation -> {
            java.util.function.Function<String, BigDecimal> create = invocation.getArgument(2);
            BigDecimal amount = create.apply("FIXED-PAYMENT-ID");
            OrderRequest row = new OrderRequest(); row.setRequestId(requestId()); row.setPayOrderId("FIXED-PAYMENT-ID");
            row.setStatus("CREATED"); row.setAmount(amount); return row;
        });
        when(dep("orderInfoMapper", OrderInfoMapper.class).selectList(any())).thenAnswer(invocation -> {
            OrderInfo order = new OrderInfo(); order.setOrderId("ORDER1"); order.setPayOrderId("FIXED-PAYMENT-ID");
            order.setOrderStatus(0); order.setOrderTime(new java.util.Date()); order.setPayScene("alipay_pc");
            order.setAmount(new BigDecimal("12.34").multiply(BigDecimal.valueOf(quantity))); return List.of(order);
        });
        PayChannel payment = mock(PayChannel.class);
        when(payment.getPayUrl(any(), anyString(), anyString(), any())).thenAnswer(invocation ->
                new PayInfoDTO("synthetic-payment", invocation.getArgument(1), invocation.getArgument(3)));
        PostOrderDTO request = validOrder();
        request.getOrderList().get(0).setBuyCount(quantity);

        try (MockedStatic<SpringContext> spring = mockStatic(SpringContext.class)) {
            spring.when(() -> SpringContext.getBean("payChannel4Alipay")).thenReturn(payment);
            PayInfoDTO result = service.postOrder("ORDERUSER1", request);
            BigDecimal amount = new BigDecimal("12.34").multiply(BigDecimal.valueOf(quantity));
            assertThat(result.getAmount()).isEqualByComparingTo(amount);
            verify(payment).getPayUrl(eq(PayChannelEnum.ALIPAY_PC), anyString(), anyString(), eq(amount));
            verify(dep("productSkuMapper", ProductSkuMapper.class)).updateStockBatch(any());
        }
    }

    static Stream<PostOrderDTO> invalidOrders() {
        List<PostOrderDTO> requests = new ArrayList<>();
        requests.add(null);
        requests.add(changed(order -> order.setOrderList(null)));
        requests.add(changed(order -> order.setOrderList(List.of())));
        requests.add(changed(order -> order.setOrderList(java.util.Arrays.asList((PostOrderItemDTO) null))));
        for (Integer quantity : new Integer[]{null, 0, -1, 100, Integer.MAX_VALUE}) {
            requests.add(changed(order -> order.getOrderList().get(0).setBuyCount(quantity)));
        }
        requests.add(changed(order -> order.getOrderList().get(0).setProductId(" ")));
        requests.add(changed(order -> order.getOrderList().get(0).setProductId("P".repeat(16))));
        requests.add(changed(order -> order.getOrderList().get(0).setPropertyValueIds(" ")));
        requests.add(changed(order -> order.getOrderList().get(0).setPropertyValueIds("S".repeat(501))));
        requests.add(changed(order -> order.getOrderList().get(0).setRemark("备".repeat(301))));
        requests.add(changed(order -> order.setAddressId(" ")));
        requests.add(changed(order -> order.setAddressId("A".repeat(16))));
        requests.add(changed(order -> order.setPayMethod(" ")));
        requests.add(changed(order -> order.setOrderFrom(null)));
        requests.add(changed(order -> order.setOrderFrom(2)));
        requests.add(changed(order -> order.setOrderList(List.of(item("P1", "S1", 99), item("P1", "S1", 1)))));
        requests.add(changed(order -> order.setRequestId(null)));
        requests.add(changed(order -> order.setRequestId("bad key")));
        requests.add(changed(order -> order.setOrderList(IntStream.range(0, 51)
                .mapToObj(index -> item("P" + index, "S1", 1)).toList())));
        return requests.stream();
    }

    private <T> void dependency(String field, Class<T> type) {
        T replacement = mock(type);
        dependencies.add(replacement);
        ReflectionTestUtils.setField(service, field, replacement);
    }

    private <T> T dep(String field, Class<T> type) {
        return type.cast(ReflectionTestUtils.getField(service, field));
    }

    private static PostOrderDTO changed(Consumer<PostOrderDTO> change) {
        PostOrderDTO request = validOrder();
        change.accept(request);
        return request;
    }

    static PostOrderDTO validOrder() {
        PostOrderDTO request = new PostOrderDTO();
        request.setRequestId(requestId());
        request.setPayMethod("alipay_pc");
        request.setOrderFrom(0);
        request.setAddressId("ADDRESS1");
        request.setOrderList(List.of(item("PRODUCT1", "SKU1", 1)));
        return request;
    }

    static PostOrderItemDTO item(String productId, String propertyValueIds, int quantity) {
        PostOrderItemDTO item = new PostOrderItemDTO();
        item.setProductId(productId);
        item.setPropertyValueIds(propertyValueIds);
        item.setBuyCount(quantity);
        return item;
    }

    private static String requestId() { return "UNIT-ORDER-REQUEST-0001"; }
}
