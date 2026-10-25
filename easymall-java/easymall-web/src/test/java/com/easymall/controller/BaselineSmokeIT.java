package com.easymall.controller;

import com.easymall.EasyMallWebApplication;
import com.easymall.commonent.EsSearchComponent;
import com.easymall.commonent.RedisComponent;
import com.easymall.entity.dto.PayInfoDTO;
import com.easymall.entity.enums.PayChannelEnum;
import com.easymall.entity.vo.ProductInfoDTO;
import com.easymall.service.PayChannel;
import com.easymall.utils.StringTools;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Explicit integration smoke test against the schema-only baseline environment.
 * Uses real isolated MySQL/Redis/ES, a loopback model fixture and mocked payment.
 * It creates synthetic orders/messages and deliberately does not reset data.
 */
@EnabledIfSystemProperty(named = "baseline.integration", matches = "true")
@Import(BaselineSmokeIT.LocalToolConfiguration.class)
@SpringBootTest(classes = EasyMallWebApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BaselineSmokeIT {

    private static final String USER_ID = "BASE000001";
    private static final String PRODUCT_ID = "BASELINEPROD001";
    private static final String PROPERTY_VALUE_ID = "BASEVALUE001";
    private static final String MODEL_URL = "http://127.0.0.1:18080";

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RedisComponent redisComponent;

    @Autowired
    private EsSearchComponent esSearchComponent;

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    @LocalServerPort
    private int port;

    @MockitoBean(name = "payChannel4Alipay")
    private PayChannel payment;

    @DynamicPropertySource
    static void refuseNonBaselineInfrastructure(DynamicPropertyRegistry registry) {
        require("true".equals(System.getProperty("baseline.integration")),
                "The explicit baseline integration flag is required");
        require(System.getProperty("dev") == null, "The fixed dev identity must be disabled");
        String location = System.getProperty("spring.config.location", "");
        require(location.startsWith("file:")
                        && location.replace('\\', '/').endsWith("/config/local/web.yml"),
                "An explicit local baseline configuration file is required");
        require("baseline".equals(System.getProperty("spring.profiles.active")),
                "Only the baseline profile is permitted");

        // Parse the effective configuration before any application bean is created.
        StandardEnvironment environment = new StandardEnvironment();
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        String jdbcUrl = environment.getProperty("spring.datasource.url", "");
        require(jdbcUrl.matches("jdbc:mysql://127\\.0\\.0\\.1:13306/easymall_test(?:\\?[^#]*)?"),
                "MySQL must use the isolated loopback baseline database");
        require(!environment.containsProperty("spring.datasource.hikari.jdbc-url"),
                "A Hikari URL must not override the isolated database");
        requireProperty(environment, "spring.data.redis.host", "127.0.0.1");
        requireProperty(environment, "spring.data.redis.port", "16379");
        require(!environment.containsProperty("spring.data.redis.url"),
                "An alternate Redis URL must not override the isolated endpoint");
        for (String key : List.of("spring.data.redis.cluster.nodes",
                "spring.data.redis.sentinel.master", "spring.data.redis.sentinel.nodes")) {
            require(!environment.containsProperty(key) && !environment.containsProperty(key + "[0]"),
                    "Alternate Redis topology must not be configured: " + key);
        }
        requireProperty(environment, "spring.elasticsearch.uris", "http://127.0.0.1:19200");
        requireProperty(environment, "spring.ai.model.chat", "openai");
        requireProperty(environment, "spring.ai.model.embedding", "openai");
        requireProperty(environment, "spring.ai.openai.base-url", MODEL_URL);
        requireProperty(environment, "spring.ai.openai.api-key", "baseline-placeholder");
        requireProperty(environment, "spring.ai.openai.chat.options.model", "baseline-chat");
        requireProperty(environment, "spring.ai.openai.embedding.options.model", "baseline-embedding");
        requireProperty(environment, "spring.ai.openai.chat.completions-path", "/v1/chat/completions");
        requireProperty(environment, "spring.ai.openai.embedding.embeddings-path", "/v1/embeddings");
        require(!environment.containsProperty("spring.ai.openai.chat.base-url")
                        && !environment.containsProperty("spring.ai.openai.embedding.base-url"),
                "Model-specific URLs must not override the local model fixture");
        requireProperty(environment, "project.auto-checkpay", "false");
        require(!environment.containsProperty("spring.ai.dashscope.api-key")
                        && !environment.containsProperty("spring.ai.dashscope.base-url"),
                "Custom external model credentials must not be configured");
        require(environment.getPropertySources().stream()
                        .noneMatch(source -> source.getName().contains("application-dev")),
                "Development ConfigData must not be loaded");

        // MCP transport is outside this smoke test; the provider below is a mock.
        registry.add("spring.ai.mcp.client.enabled", () -> false);
    }

    @BeforeEach
    void configureExternalReplacements() {
        when(payment.getPayUrl(any(PayChannelEnum.class), anyString(), anyString(), any(BigDecimal.class)))
                .thenAnswer(invocation -> new PayInfoDTO("baseline-mock-payment",
                        invocation.getArgument(1), invocation.getArgument(3)));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LocalToolConfiguration {

        @Bean
        ToolCallbackProvider toolCallbackProvider() {
            // Tool calling inspects this bean during context initialization.
            return () -> new ToolCallback[0];
        }
    }

    @Test
    void browseLoginSearchOrderAndAiCompleteAgainstIsolatedServices() {
        JsonNode modelBefore = modelHealth();
        assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo("easymall_test");
        assertThat(jdbc.queryForObject("select count(*) from user_info where user_id=? and email=?",
                Integer.class, USER_ID, "baseline@example.invalid")).isEqualTo(1);

        JsonNode categories = get("/product/loadCategory", null);
        assertThat(fieldValues(categories, "categoryId")).contains("B0001");
        JsonNode products = get("/product/loadProduct?pageNo=1&categoryId=B0002", null);
        assertThat(fieldValues(products.path("list"), "productId")).contains(PRODUCT_ID);
        JsonNode detail = get("/product/getProduct?productId=" + PRODUCT_ID, null);
        assertThat(detail.path("productInfo").path("productId").asText()).isEqualTo(PRODUCT_ID);

        String checkCodeKey = redisComponent.saveCheckCode("7");
        assertThat(redisComponent.getCheckCode(checkCodeKey)).isEqualTo("7");
        MultiValueMap<String, String> loginForm = new LinkedMultiValueMap<>();
        loginForm.add("email", "baseline@example.invalid");
        loginForm.add("password", StringTools.encodeByMD5("Baseline123"));
        loginForm.add("checkCodeKey", checkCodeKey);
        loginForm.add("checkCode", "7");
        JsonNode login = form("/account/login", loginForm, null);
        assertThat(login.path("userId").asText()).isEqualTo(USER_ID);
        String token = login.path("token").asText();
        assertThat(token).isNotBlank();
        assertThat(redisComponent.getCheckCode(checkCodeKey)).isNull();

        esSearchComponent.saveProduct(PRODUCT_ID);
        elasticsearchOperations.indexOps(ProductInfoDTO.class).refresh();
        ProductInfoDTO indexedProduct = elasticsearchOperations.get(PRODUCT_ID, ProductInfoDTO.class);
        assertThat(indexedProduct).as("The synthetic ES document must be indexed").isNotNull();
        assertThat(indexedProduct.getProductId()).isEqualTo(PRODUCT_ID);
        // Current contains/wildcard search is case-sensitive against analyzed terms.
        // The isolated ES check found *Baseline*: 0 hits, *baseline*: 1 hit.
        JsonNode search = get("/product/search?keyWords=baseline&sortType=asc&sortField=price&pageNo=1", null);
        assertThat(fieldValues(search.path("list"), "productId")).contains(PRODUCT_ID);

        String skuHash = StringTools.encodeByMD5(PROPERTY_VALUE_ID);
        Integer stockBefore = stock(skuHash);
        assertThat(stockBefore).as("Synthetic fixture stock must remain available").isPositive();
        BigDecimal price = jdbc.queryForObject(
                "select price from product_sku where product_id=? and property_value_id_hash=?",
                BigDecimal.class, PRODUCT_ID, skuHash);
        Map<String, Object> orderRequest = Map.of(
                "payMethod", "alipay_pc",
                "addressId", "BASEADDR000001",
                "orderFrom", 0,
                "orderList", List.of(Map.of("productId", PRODUCT_ID,
                        "propertyValueIds", PROPERTY_VALUE_ID, "buyCount", 1,
                        "remark", "Synthetic phase 0 smoke test; do not ship")));
        JsonNode paymentResult = json("/order/postOrder", orderRequest, token);
        String payOrderId = paymentResult.path("payOrderId").asText();
        assertThat(payOrderId).isNotBlank();
        assertThat(paymentResult.path("payInfo").asText()).isEqualTo("baseline-mock-payment");
        assertThat(paymentResult.path("amount").decimalValue()).isEqualByComparingTo(price);
        assertThat(stock(skuHash)).isEqualTo(stockBefore - 1);
        verify(payment).getPayUrl(eq(PayChannelEnum.ALIPAY_PC), eq(payOrderId), anyString(), eq(price));

        String orderId = jdbc.queryForObject(
                "select order_id from order_info where pay_order_id=? and user_id=?",
                String.class, payOrderId, USER_ID);
        assertThat(jdbc.queryForObject("select amount from order_info where order_id=?",
                BigDecimal.class, orderId)).isEqualByComparingTo(price);
        assertThat(jdbc.queryForObject("select order_status from order_info where order_id=?",
                Integer.class, orderId)).isZero();
        assertThat(jdbc.queryForObject("select buy_count from order_item where order_id=?",
                Integer.class, orderId)).isEqualTo(1);
        JsonNode ownOrder = get("/order/getOrderInfo?payOrderId=" + payOrderId, token);
        assertThat(ownOrder.path("orderId").asText()).isEqualTo(orderId);
        assertThat(ownOrder.path("userId").asText()).isEqualTo(USER_ID);
        JsonNode ownOrders = get("/order/loadMyOrder?pageNo=1", token);
        assertThat(fieldValues(ownOrders.path("list"), "orderId")).contains(orderId);

        MultiValueMap<String, String> messageForm = new LinkedMultiValueMap<>();
        messageForm.add("message", "阶段0合成链路测试，请回复固定的本地测试提示。");
        JsonNode message = form("/agent/sendMessage", messageForm, token);
        int messageId = message.path("messageId").asInt();
        assertThat(messageId).isPositive();
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject(
                    "select status from agent_message where message_id=? and user_id=?",
                    Integer.class, messageId, USER_ID)).isEqualTo(2);
            assertThat(jdbc.queryForObject(
                    "select assistant_message from agent_message where message_id=? and user_id=?",
                    String.class, messageId, USER_ID))
                    .contains("【阶段0合成测试回复】", "本地固定替身");
        });
        JsonNode history = get("/agent/loadHistoryMessage?pageNo=1", token);
        assertThat(fieldValues(history.path("list"), "messageId"))
                .contains(Integer.toString(messageId));
        JsonNode modelAfter = modelHealth();
        for (String counter : List.of("intentResponses", "streamResponses", "embeddingRequests")) {
            assertThat(modelAfter.path("counters").path(counter).asLong())
                    .as("The local model fixture must receive %s", counter)
                    .isGreaterThan(modelBefore.path("counters").path(counter).asLong());
        }

        // Use the business API to restore this run's stock while retaining evidence.
        MultiValueMap<String, String> cancelForm = new LinkedMultiValueMap<>();
        cancelForm.add("orderId", orderId);
        form("/order/cancelOrder", cancelForm, token);
        assertThat(stock(skuHash)).isEqualTo(stockBefore);
        // Existing service writes CLOSED(5), even for the user cancellation route.
        // This records the current baseline; cancellation semantics remain follow-up work.
        assertThat(jdbc.queryForObject("select order_status from order_info where order_id=?",
                Integer.class, orderId)).isEqualTo(5);
        verify(payment).closeOrder(payOrderId);
    }

    private Integer stock(String skuHash) {
        return jdbc.queryForObject("select stock from product_sku where product_id=? and property_value_id_hash=?",
                Integer.class, PRODUCT_ID, skuHash);
    }

    private JsonNode modelHealth() {
        ResponseEntity<JsonNode> response = http.getForEntity(MODEL_URL + "/health", JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = Objects.requireNonNull(response.getBody());
        assertThat(body.path("synthetic").asBoolean()).isTrue();
        return body;
    }

    private JsonNode get(String path, String token) {
        return success(path, http.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(headers(token, null)), JsonNode.class));
    }

    private JsonNode form(String path, MultiValueMap<String, String> body, String token) {
        return success(path, http.postForEntity(url(path),
                new HttpEntity<>(body, headers(token, MediaType.APPLICATION_FORM_URLENCODED)), JsonNode.class));
    }

    private JsonNode json(String path, Object body, String token) {
        return success(path, http.postForEntity(url(path),
                new HttpEntity<>(body, headers(token, MediaType.APPLICATION_JSON)), JsonNode.class));
    }

    private JsonNode success(String operation, ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode().is2xxSuccessful()).as("%s HTTP status", operation).isTrue();
        JsonNode envelope = Objects.requireNonNull(response.getBody());
        // Do not print entire responses: login data includes a synthetic token.
        assertThat(envelope.path("code").asInt()).as("%s business code", operation).isEqualTo(200);
        return envelope.path("data");
    }

    private HttpHeaders headers(String token, MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set("token", token);
        }
        if (contentType != null) {
            headers.setContentType(contentType);
        }
        return headers;
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + "/api" + path;
    }

    private static List<String> fieldValues(JsonNode array, String field) {
        assertThat(array.isArray()).as("Expected a JSON array").isTrue();
        return StreamSupport.stream(array.spliterator(), false).map(row -> row.path(field).asText()).toList();
    }

    private static void requireProperty(StandardEnvironment environment, String key, String expected) {
        require(expected.equals(environment.getProperty(key)),
                "Unexpected baseline configuration for " + key);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
