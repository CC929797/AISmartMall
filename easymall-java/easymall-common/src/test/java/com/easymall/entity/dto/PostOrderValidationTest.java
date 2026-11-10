package com.easymall.entity.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 Bean Validation 能进入订单列表和嵌套明细，覆盖 HTTP 入参使用的真实约束。 */
class PostOrderValidationTest {
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void startValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() { factory.close(); }

    @ParameterizedTest
    @MethodSource("invalidNestedOrders")
    void malformedNestedOrderHasViolations(PostOrderDTO request) {
        assertThat(validator.validate(request).isEmpty()).as("非法嵌套订单必须产生约束错误").isFalse();
    }

    @Test
    void aValidOrderAtBothQuantityBoundariesIsAccepted() {
        PostOrderDTO request = validOrder();
        assertThat(validator.validate(request)).isEmpty();
        request.getOrderList().get(0).setBuyCount(99);
        assertThat(validator.validate(request)).isEmpty();
    }

    static Stream<PostOrderDTO> invalidNestedOrders() {
        List<PostOrderDTO> requests = new ArrayList<>();
        requests.add(changed(order -> order.setOrderList(null)));
        requests.add(changed(order -> order.setOrderList(List.of())));
        requests.add(changed(order -> order.setOrderList(java.util.Arrays.asList((PostOrderItemDTO) null))));
        requests.add(changed(order -> order.setOrderList(java.util.Collections.nCopies(51, item()))));
        requests.add(changed(order -> order.getOrderList().get(0).setBuyCount(0)));
        requests.add(changed(order -> order.getOrderList().get(0).setBuyCount(-1)));
        requests.add(changed(order -> order.getOrderList().get(0).setBuyCount(100)));
        requests.add(changed(order -> order.getOrderList().get(0).setProductId(" ")));
        requests.add(changed(order -> order.getOrderList().get(0).setPropertyValueIds(" ")));
        requests.add(changed(order -> order.getOrderList().get(0).setRemark("备".repeat(301))));
        return requests.stream();
    }

    private static PostOrderDTO changed(Consumer<PostOrderDTO> change) {
        PostOrderDTO request = validOrder();
        change.accept(request);
        return request;
    }

    private static PostOrderDTO validOrder() {
        PostOrderDTO order = new PostOrderDTO();
        order.setRequestId("UNIT-ORDER-VALIDATION-01");
        order.setAddressId("ADDRESS1");
        order.setPayMethod("alipay_pc");
        order.setOrderFrom(0);
        order.setOrderList(List.of(item()));
        return order;
    }

    private static PostOrderItemDTO item() {
        PostOrderItemDTO item = new PostOrderItemDTO();
        item.setProductId("PRODUCT1");
        item.setPropertyValueIds("SKU1");
        item.setBuyCount(1);
        return item;
    }
}
