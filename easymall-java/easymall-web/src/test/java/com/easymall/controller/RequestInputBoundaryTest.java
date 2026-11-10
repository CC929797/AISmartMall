package com.easymall.controller;

import com.easymall.entity.dto.PostOrderDTO;
import com.easymall.entity.dto.TokenUserInfoDTO;
import com.easymall.entity.query.BaseParam;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.service.OrderInfoService;
import com.easymall.validation.RequestInputAdvice;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 输入边界测试使用真实 MVC 参数解析和校验器，业务服务仅作为可验证的替身。 */
class RequestInputBoundaryTest {
    private MockMvc mvc;
    private OrderInfoService orders;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void start() {
        orders = mock(OrderInfoService.class);
        OrderController orderController = new OrderController() {
            @Override protected TokenUserInfoDTO getTokenUserInfo() {
                TokenUserInfoDTO user = new TokenUserInfoDTO();
                user.setUserId("INPUT00001");
                return user;
            }
        };
        ReflectionTestUtils.setField(orderController, "orderInfoService", orders);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new BoundaryProbe(), orderController)
                .setValidator(validator)
                .setControllerAdvice(new RequestInputAdvice(), new AGlobalExceptionHandlerController()).build();
    }

    @AfterEach
    void stop() { validator.close(); }

    @ParameterizedTest
    @ValueSource(strings = {"orderBy", "OrderBy", "query.orderBy", "simplePage.start",
            "simplePage.pageSize", "simplePage.end", "_orderBy", "!simplePage.start", "query[orderBy]"})
    void internalQueryFieldsCannotBeBoundFromRequests(String field) throws Exception {
        mvc.perform(post("/input/list").param(field, "999999"))
                .andExpect(jsonPath("$.code").value(600));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "100001", "2147483648"})
    void scalarPageNumbersAreRejectedBeforeTheHandlerRuns(String value) throws Exception {
        mvc.perform(post("/input/scalar").param("pageNo", value))
                .andExpect(jsonPath("$.code").value(600));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "101"})
    void scalarPageSizesCannotCreateAnUnboundedQuery(String value) throws Exception {
        mvc.perform(post("/input/scalar").param("pageSize", value))
                .andExpect(jsonPath("$.code").value(600));
    }

    @Test
    void springArrayStyleAliasesCannotBypassScalarPagingChecks() throws Exception {
        mvc.perform(post("/input/scalar").param("pageNo[]", "0"))
                .andExpect(jsonPath("$.code").value(600));
        mvc.perform(post("/input/scalar").param("pageSize[]", "101"))
                .andExpect(jsonPath("$.code").value(600));
        mvc.perform(post("/input/scalar").param("pageNo[]", "1").param("pageSize[]", "20"))
                .andExpect(jsonPath("$.code").value(200));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "[null]",
            "[{\"productId\":\"P1\",\"propertyValueIds\":\"S1\",\"buyCount\":0}]",
            "[{\"productId\":\"P1\",\"propertyValueIds\":\"S1\",\"buyCount\":100}]"})
    void jsonOrderValidatesTheListAndEveryNestedItem(String items) throws Exception {
        mvc.perform(post("/order/postOrder").contentType("application/json")
                        .content("{\"requestId\":\"HTTP-ORDER-REQUEST-01\",\"payMethod\":\"alipay_pc\",\"addressId\":\"A1\",\"orderFrom\":0,\"orderList\":" + items + "}"))
                .andExpect(jsonPath("$.code").value(600));
        verifyNoInteractions(orders);
    }

    @Test
    void formBindingAlsoValidatesNestedOrderItems() throws Exception {
        mvc.perform(post("/input/form").param("requestId", "HTTP-ORDER-REQUEST-01").param("payMethod", "alipay_pc").param("addressId", "A1")
                        .param("orderFrom", "0").param("orderList[0].productId", "P1")
                        .param("orderList[0].propertyValueIds", "S1").param("orderList[0].buyCount", "-1"))
                .andExpect(jsonPath("$.code").value(600));
    }

    @Test
    void malformedJsonHasAStableBusinessError() throws Exception {
        mvc.perform(post("/order/postOrder").contentType("application/json").content("{broken"))
                .andExpect(jsonPath("$.code").value(600));
        verifyNoInteractions(orders);
    }

    @Test
    void oversizedFormIndexCannotGrowAnUnboundedCollection() throws Exception {
        mvc.perform(post("/input/form").param("orderList[100000].productId", "P1"))
                .andExpect(jsonPath("$.code").value(600));
    }

    @ParameterizedTest
    @ValueSource(strings = {"attachments", "attachments[0]", "_attachments", "!attachments", "files"})
    void textOnlyAiDoesNotAcceptAttachmentParameters(String field) throws Exception {
        mvc.perform(post("/agent/sendMessage").param("message", "hello").param(field, "synthetic.png"))
                .andExpect(jsonPath("$.code").value(600));
    }

    @Test
    void textOnlyAiDoesNotAcceptMultipartFiles() throws Exception {
        mvc.perform(multipart("/agent/sendMessage")
                        .file(new MockMultipartFile("upload", "synthetic.png", "image/png", new byte[]{1}))
                        .param("message", "hello"))
                .andExpect(jsonPath("$.code").value(600));
    }

    @Test
    void methodValidationErrorsDoNotCauseAClassCastException() throws Exception {
        mvc.perform(post("/input/constraint")).andExpect(jsonPath("$.code").value(600));
    }

    @Test
    void validPagingAndNestedFormRemainUsable() throws Exception {
        mvc.perform(post("/input/list").param("pageNo", "1").param("pageSize", "20"))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(post("/input/form").param("requestId", "HTTP-ORDER-REQUEST-01").param("payMethod", "alipay_pc").param("addressId", "A1")
                        .param("orderFrom", "0").param("orderList[0].productId", "P1")
                        .param("orderList[0].propertyValueIds", "S1").param("orderList[0].buyCount", "1"))
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void validRequestIdReachesOrderServiceAndProcessingIsReturnedWithoutPaymentHtml() throws Exception {
        com.easymall.entity.dto.PayInfoDTO processing = new com.easymall.entity.dto.PayInfoDTO();
        processing.setRequestStatus("PROCESSING"); processing.setRequestId("HTTP-ORDER-REQUEST-01");
        when(orders.postOrder(eq("INPUT00001"), any())).thenReturn(processing);
        mvc.perform(post("/order/postOrder").contentType("application/json").content(
                "{\"requestId\":\"HTTP-ORDER-REQUEST-01\",\"payMethod\":\"alipay_pc\",\"addressId\":\"A1\",\"orderFrom\":0,\"orderList\":[{\"productId\":\"P1\",\"propertyValueIds\":\"S1\",\"buyCount\":1}]}"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.requestStatus").value("PROCESSING"));
        verify(orders).postOrder(eq("INPUT00001"), argThat(request -> "HTTP-ORDER-REQUEST-01".equals(request.getRequestId())));
    }

    @Test
    void requestStatusQueryAlwaysUsesAuthenticatedUser() throws Exception {
        when(orders.getOrderRequest("INPUT00001", "HTTP-ORDER-REQUEST-01")).thenReturn(new com.easymall.entity.dto.PayInfoDTO());
        mvc.perform(post("/order/getOrderRequest").param("requestId", "HTTP-ORDER-REQUEST-01"))
                .andExpect(jsonPath("$.code").value(200));
        verify(orders).getOrderRequest("INPUT00001", "HTTP-ORDER-REQUEST-01");
    }

    @RestController
    static class BoundaryProbe extends ABaseController {
        @RequestMapping("/input/list")
        public ResponseVO list(BaseParam query) { return getSuccessResponseVO(null); }
        @RequestMapping("/input/scalar")
        public ResponseVO scalar(Integer pageNo, Integer pageSize) { return getSuccessResponseVO(null); }
        @RequestMapping("/input/form")
        public ResponseVO form(@Valid PostOrderDTO order) { return getSuccessResponseVO(null); }
        @RequestMapping("/agent/sendMessage")
        public ResponseVO message(String message) { return getSuccessResponseVO(null); }
        @RequestMapping("/input/constraint")
        public ResponseVO constraint() { throw new ConstraintViolationException(Set.of()); }
    }
}
