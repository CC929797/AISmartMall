package com.easymall.controller;

import com.easymall.commonent.RedisComponent;
import com.easymall.entity.query.*;
import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 按现有后台页面的表单协议，验证六类列表都使用明确 DTO 和服务器排序映射。 */
class AdminListHttpContractTest {
    private UserInfoService users;
    private OrderInfoService orders;
    private OrderCommentService comments;
    private RagQuestionService rag;
    private ProductInfoService products;
    private ProductSkuService stock;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        users = mock(UserInfoService.class);
        orders = mock(OrderInfoService.class);
        comments = mock(OrderCommentService.class);
        rag = mock(RagQuestionService.class);
        products = mock(ProductInfoService.class);
        stock = mock(ProductSkuService.class);
        UserController userController = new UserController();
        ReflectionTestUtils.setField(userController, "userInfoService", users);
        ReflectionTestUtils.setField(userController, "redisComponent", mock(RedisComponent.class));
        OrderController orderController = new OrderController();
        ReflectionTestUtils.setField(orderController, "orderInfoService", orders);
        ReflectionTestUtils.setField(orderController, "orderCommentService", comments);
        RagController ragController = new RagController();
        ReflectionTestUtils.setField(ragController, "ragQuestionService", rag);
        ProductInfoController productController = new ProductInfoController();
        ReflectionTestUtils.setField(productController, "productInfoService", products);
        HomeController homeController = new HomeController();
        ReflectionTestUtils.setField(homeController, "productSkuService", stock);
        when(users.findListByPage(any())).thenReturn(page());
        when(orders.findListByPage(any())).thenReturn(page());
        when(comments.findListByPage(any())).thenReturn(page());
        when(rag.findListByPage(any())).thenReturn(page());
        when(products.findListByPageListVO(any())).thenReturn(page());
        when(stock.findListByPage4ListVO(any())).thenReturn(page());
        mvc = MockMvcBuilders.standaloneSetup(userController, orderController, ragController,
                        productController, homeController)
                .setControllerAdvice(new AGlobalExceptionHandlerController()).build();
    }

    @ParameterizedTest
    @CsvSource({"/user/loadUser,joinTime,u.join_time asc", "/order/loadOrder,orderTime,o.order_time asc",
            "/order/loadComment,commentTime,o.comment_time asc", "/rag/loadRagQuestion,questionId,r.question_id asc",
            "/productInfo/loadProduct,createTime,p.create_time asc", "/home/loadLessStockProduct,stock,p.stock asc"})
    void validFormsKeepPagingFilteringAndMapBusinessSortNames(String path, String field, String expectedSort)
            throws Exception {
        mvc.perform(post(path).param("pageNo", "2").param("pageSize", "30")
                        .param("sortField", field).param("sortDirection", "asc")
                        .param("nickNameFuzzy", "合成昵称").param("productNameFuzzy", "合成商品")
                        .param("questionFuzzy", "合成问题").param("status", "1").param("orderStatus", "1")
                        .param("queryItems", "false").param("queryUser", "false")
                        .param("queryProduct", "false").param("queryUserInfo", "false"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.pageSize").value(30));
        BaseParam query = capturedQuery(path);
        assertThat(query.getPageNo()).isEqualTo(2);
        assertThat(query.getPageSize()).isEqualTo(30);
        assertThat(query.getOrderBy()).isEqualTo(expectedSort);
        if (query instanceof UserInfoQuery user) { assertThat(user.getNickNameFuzzy()).isEqualTo("合成昵称"); }
        if (query instanceof OrderInfoQuery order) {
            assertThat(order.getProductNameFuzzy()).isEqualTo("合成商品");
            assertThat(order.getOrderStatus()).isEqualTo(1);
            assertThat(order.getQueryItems()).isTrue();
            assertThat(order.getQueryUser()).isTrue();
        }
        if (query instanceof OrderCommentQuery comment) {
            assertThat(comment.getNickNameFuzzy()).isEqualTo("合成昵称");
            assertThat(comment.getProductNameFuzzy()).isEqualTo("合成商品");
            assertThat(comment.getQueryProduct()).isTrue();
            assertThat(comment.getQueryUserInfo()).isTrue();
        }
        if (query instanceof RagQuestionQuery question) { assertThat(question.getQuestionFuzzy()).isEqualTo("合成问题"); }
        if (query instanceof ProductInfoQuery product) { assertThat(product.getProductNameFuzzy()).isEqualTo("合成商品"); }
        if (query instanceof ProductSkuQuery sku) { assertThat(sku.getLessStock()).isEqualTo(10); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/user/loadUser", "/order/loadOrder", "/order/loadComment", "/rag/loadRagQuestion",
            "/productInfo/loadProduct", "/home/loadLessStockProduct"})
    void unknownSortCannotReachAnyListService(String path) throws Exception {
        mvc.perform(post(path).param("sortField", "raw_sql_expression").param("sortDirection", "asc"))
                .andExpect(jsonPath("$.code").value(600));
        verifyNoInteractions(users, orders, comments, rag, products, stock);
    }

    private BaseParam capturedQuery(String path) {
        if (path.equals("/user/loadUser")) {
            ArgumentCaptor<UserInfoQuery> argument = ArgumentCaptor.forClass(UserInfoQuery.class);
            verify(users).findListByPage(argument.capture()); return argument.getValue();
        }
        if (path.equals("/order/loadOrder")) {
            ArgumentCaptor<OrderInfoQuery> argument = ArgumentCaptor.forClass(OrderInfoQuery.class);
            verify(orders).findListByPage(argument.capture()); return argument.getValue();
        }
        if (path.equals("/order/loadComment")) {
            ArgumentCaptor<OrderCommentQuery> argument = ArgumentCaptor.forClass(OrderCommentQuery.class);
            verify(comments).findListByPage(argument.capture()); return argument.getValue();
        }
        if (path.equals("/rag/loadRagQuestion")) {
            ArgumentCaptor<RagQuestionQuery> argument = ArgumentCaptor.forClass(RagQuestionQuery.class);
            verify(rag).findListByPage(argument.capture()); return argument.getValue();
        }
        if (path.equals("/productInfo/loadProduct")) {
            ArgumentCaptor<ProductInfoQuery> argument = ArgumentCaptor.forClass(ProductInfoQuery.class);
            verify(products).findListByPageListVO(argument.capture()); return argument.getValue();
        }
        ArgumentCaptor<ProductSkuQuery> argument = ArgumentCaptor.forClass(ProductSkuQuery.class);
        verify(stock).findListByPage4ListVO(argument.capture()); return argument.getValue();
    }

    private static <T> PaginationResultVO<T> page() { return new PaginationResultVO<>(0, 30, 2, 0, List.of()); }
}
