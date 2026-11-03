package com.easymall.controller;

import com.easymall.EasyMallWebApplication;
import com.easymall.commonent.EsSearchComponent;
import com.easymall.commonent.RedisComponent;
import com.easymall.entity.config.AppConfig;
import com.easymall.entity.constants.Constants;
import com.easymall.entity.dto.PayInfoDTO;
import com.easymall.entity.dto.TokenUserInfoDTO;
import com.easymall.entity.dto.MessageSendDTO;
import com.easymall.websocket.message.MessageHandler;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.redis.core.RedisTemplate;
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
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 显式启用的基线集成烟测，使用仅由表结构和合成数据建立的独立环境。
 * 连接真实的隔离 MySQL、Redis、ES，模型使用本机替身，支付使用 Mock。
 * 测试产生的合成订单和消息保留作为证据，不重置数据库。
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
    private static final String ADMIN_URL = "http://127.0.0.1:6061/api";
    private static final String SECURITY_USER_ID = "SECUSER002";

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RedisComponent redisComponent;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private AppConfig appConfig;

    @Autowired
    private MessageHandler messageHandler;

    @Autowired
    private EsSearchComponent esSearchComponent;

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping requestMappings;

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

        // 在创建应用 Bean 前解析实际生效的配置，先确认所有基础设施均指向隔离环境。
        StandardEnvironment environment = new StandardEnvironment();
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        String jdbcUrl = environment.getProperty("spring.datasource.url", "");
        require(jdbcUrl.matches("jdbc:mysql://127\\.0\\.0\\.1:13306/easymall_test(?:\\?[^#]*)?"),
                "MySQL must use the isolated loopback baseline database");
        require(!environment.containsProperty("spring.datasource.hikari.jdbc-url"),
                "A Hikari URL must not override the isolated database");
        requireProperty(environment, "spring.data.redis.host", "127.0.0.1");
        requireProperty(environment, "spring.data.redis.port", "16379");
        requireProperty(environment, "ws.port", "6051");
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

        // 本烟测不连接实际 MCP 服务，下方使用不提供工具的测试替身。
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
            // 工具调用组件会在初始化上下文时读取该 Bean。
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
        // 当前包含匹配和通配符搜索对分词结果区分大小写。
        // 隔离 ES 实测：*Baseline* 命中 0 条，*baseline* 命中 1 条。
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

        // 通过业务接口恢复本轮占用的库存，同时保留订单记录作为证据。
        MultiValueMap<String, String> cancelForm = new LinkedMultiValueMap<>();
        cancelForm.add("orderId", orderId);
        form("/order/cancelOrder", cancelForm, token);
        assertThat(stock(skuHash)).isEqualTo(stockBefore);
        // 现有服务即使经用户取消接口执行，也会写入 CLOSED(5)。
        // 此处仅记录当前基线行为，取消状态语义留待后续步骤修复。
        assertThat(jdbc.queryForObject("select order_status from order_info where order_id=?",
                Integer.class, orderId)).isEqualTo(5);
        verify(payment).closeOrder(payOrderId);
    }

    @Test
    void userProfileBoundaryAndClosedCrudProtectBothSyntheticUsers() {
        String otherUserId = "SECUSER002";
        assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo("easymall_test");
        prepareSyntheticSecurityUser(otherUserId);
        Map<String, Object> otherUserBefore = snapshotUser(otherUserId);
        Map<String, Object> loginUserBefore = snapshotUser(USER_ID);
        String injectedPassword = "synthetic-profile-password-injection";
        String injectedApiKey = "synthetic-profile-key-injection";
        List<String> secrets = Stream.of(loginUserBefore.get("password"), loginUserBefore.get("api_key"),
                        otherUserBefore.get("password"), otherUserBefore.get("api_key"),
                        injectedPassword, injectedApiKey)
                .filter(Objects::nonNull).map(Object::toString).filter(value -> !value.isBlank()).toList();

        // 若旧路由被意外恢复，必须在发送删除或更新请求之前让测试失败。
        assertThat(requestMappings.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream())
                .noneMatch(path -> path.equals("/userInfo") || path.startsWith("/userInfo/")))
                .as("the generated user CRUD must not be registered").isTrue();

        String checkCodeKey = redisComponent.saveCheckCode("9");
        MultiValueMap<String, String> loginForm = new LinkedMultiValueMap<>();
        loginForm.add("email", "baseline@example.invalid");
        loginForm.add("password", StringTools.encodeByMD5("Baseline123"));
        loginForm.add("checkCodeKey", checkCodeKey);
        loginForm.add("checkCode", "9");
        JsonNode login = form("/account/login", loginForm, null);
        assertCredentialSafe(login, secrets);
        assertThat(login.path("userId").asText()).isEqualTo(USER_ID);
        String token = login.path("token").asText();
        assertThat(token).isNotBlank();
        assertThat(redisComponent.getCheckCode(checkCodeKey) == null)
                .as("login consumes the synthetic captcha").isTrue();

        JsonNode automaticLogin = get("/account/autoLogin", token);
        assertCredentialSafe(automaticLogin, secrets);
        assertThat(automaticLogin.path("userId").asText()).isEqualTo(USER_ID);
        // 刷新现有 Token，防止旧身份快照在会话撤销后创建新的登录态。
        assertThat(token.equals(automaticLogin.path("token").asText()))
                .as("automatic login keeps the authenticated token").isTrue();
        token = automaticLogin.path("token").asText();
        assertThat(token).isNotBlank();
        JsonNode profile = get("/account/getUserInfo?userId=" + otherUserId, token);
        assertCredentialSafe(profile, secrets);
        assertThat(profile.path("userId").asText()).isEqualTo(USER_ID);

        Map<String, Object> ownUserBeforeEdit = snapshotUser(USER_ID);
        int newSex = jdbc.queryForObject("select sex from user_info where user_id=?",
                Integer.class, USER_ID) == 1 ? 0 : 1;
        String newAvatar = "security/synthetic-a.png";
        String newNickName = "Security fixture A";
        MultiValueMap<String, String> edit = new LinkedMultiValueMap<>();
        edit.add("avatar", newAvatar);
        edit.add("nickName", newNickName);
        edit.add("sex", Integer.toString(newSex));
        edit.add("userId", otherUserId);
        edit.add("email", "injected-profile@example.invalid");
        edit.add("status", "0");
        edit.add("password", injectedPassword);
        edit.add("apiKey", injectedApiKey);
        JsonNode updated = form("/account/updateUserInfo", edit, token);
        assertCredentialSafe(updated, secrets);
        assertThat(updated.path("userId").asText()).isEqualTo(USER_ID);

        Map<String, Object> ownUserAfterEdit = snapshotUser(USER_ID);
        assertThat(newAvatar.equals(ownUserAfterEdit.get("avatar")))
                .as("only the authenticated user's avatar is edited").isTrue();
        assertThat(newNickName.equals(ownUserAfterEdit.get("nick_name")))
                .as("only the authenticated user's nickname is edited").isTrue();
        assertThat(jdbc.queryForObject("select sex from user_info where user_id=?", Integer.class, USER_ID))
                .isEqualTo(newSex);
        assertThat(nonEditableUserFields(ownUserBeforeEdit).equals(nonEditableUserFields(ownUserAfterEdit)))
                .as("all user columns outside avatar, nickname and sex remain unchanged").isTrue();
        assertThat(otherUserBefore.equals(snapshotUser(otherUserId)))
                .as("the other synthetic user's entire row is unchanged").isTrue();

        JsonNode automaticLoginAfterEdit = get("/account/autoLogin", token);
        assertCredentialSafe(automaticLoginAfterEdit, secrets);
        assertThat(automaticLoginAfterEdit.path("userId").asText()).isEqualTo(USER_ID);
        assertThat(newNickName.equals(automaticLoginAfterEdit.path("nickName").asText()))
                .as("automatic login reads the updated nickname from Redis").isTrue();
        assertThat(newAvatar.equals(automaticLoginAfterEdit.path("avatar").asText()))
                .as("automatic login reads the updated avatar from Redis").isTrue();
        assertThat(token.equals(automaticLoginAfterEdit.path("token").asText()))
                .as("profile refresh does not rotate the authenticated token").isTrue();
        token = automaticLoginAfterEdit.path("token").asText();
        JsonNode refreshedProfile = get("/account/getUserInfo", token);
        assertCredentialSafe(refreshedProfile, secrets);
        assertThat(newNickName.equals(refreshedProfile.path("nickName").asText()))
                .as("the edited profile remains readable").isTrue();

        List<String> legacyOperations = List.of("loadDataList", "add", "addBatch", "addOrUpdateBatch",
                "getUserInfoByUserId", "updateUserInfoByUserId", "deleteUserInfoByUserId",
                "getUserInfoByEmail", "updateUserInfoByEmail", "deleteUserInfoByEmail",
                "getUserInfoByNickName", "updateUserInfoByNickName", "deleteUserInfoByNickName");
        MultiValueMap<String, String> legacyAttack = new LinkedMultiValueMap<>();
        legacyAttack.add("userId", otherUserId);
        legacyAttack.add("email", "security-b@example.invalid");
        legacyAttack.add("nickName", "Security fixture B");
        legacyAttack.add("password", injectedPassword);
        legacyAttack.add("apiKey", injectedApiKey);
        legacyAttack.add("status", "0");
        for (String operation : legacyOperations) {
            String path = "/userInfo/" + operation;
            assertLegacyNotFound(path, http.exchange(url(path) + "?userId=" + otherUserId,
                    HttpMethod.GET, new HttpEntity<>(headers(null, null)), JsonNode.class), secrets);
            assertLegacyNotFound(path, http.postForEntity(url(path),
                    new HttpEntity<>(legacyAttack, headers(null, MediaType.APPLICATION_FORM_URLENCODED)),
                    JsonNode.class), secrets);
        }
        assertThat(otherUserBefore.equals(snapshotUser(otherUserId)))
                .as("anonymous requests to all legacy routes leave the other user untouched").isTrue();
    }

    @Test
    void realHttpKeepsPublicBrowsingAndProtectsPrivateHandlersByDefault() {
        assertThat(fieldValues(get("/product/loadCategory", null), "categoryId")).contains("B0001");
        assertThat(get("/order/comment/loadComment?productId=" + PRODUCT_ID + "&pageNo=1", null)
                .path("list").isArray()).isTrue();
        assertThat(get("/account/autoLogin", null).isNull()).isTrue();
        ResponseEntity<String> image = http.getForEntity(
                url("/file/getResource?sourceName=security/synthetic-missing-public.png"), String.class);
        assertThat(image.getStatusCode().value()).isEqualTo(404);
        for (String path : List.of("/productCart/loadProductCart",
                "/order/loadMyOrder", "/userAddress/loadDataList", "/agent/loadHistoryMessage",
                "/account/getUserInfo", "/account/updatePassword")) {
            assertHttpCode(path, null, 901);
            assertHttpCode(path, "synthetic-invalid-token", 901);
        }
    }

    @Test
    void realRedisScriptsPreserveNewLoginAndNeverReviveRevokedOrExpiredSnapshots() {
        prepareSyntheticSecurityUser(SECURITY_USER_ID);
        try {
            String oldToken = loginSecurityUser();
            TokenUserInfoDTO oldSnapshot = redisComponent.getTokenInfo(oldToken);
            assertThat(oldSnapshot != null).as("the initial synthetic session exists").isTrue();
            String currentToken = loginSecurityUser();
            TokenUserInfoDTO currentSnapshot = redisComponent.getTokenInfo(currentToken);
            assertThat(currentSnapshot != null).isTrue();
            assertThat(!oldToken.equals(currentToken)).as("a new login replaces the old session").isTrue();

            // 仅重建合成旧会话的键，使用实际 JSON 序列化结果验证比较删除的原子条件。
            redisTemplate.opsForValue().set(Constants.REDIS_KEY_TOKEN_WEB + oldToken,
                    oldSnapshot, Duration.ofMinutes(1));
            redisComponent.cleanToken(oldToken);
            assertThat(currentToken.equals(redisComponent.getCurrentToken(SECURITY_USER_ID)))
                    .as("logging out an old snapshot cannot remove the current token index").isTrue();
            get("/account/getUserInfo", currentToken);

            currentSnapshot.setNickName("Synthetic Redis refresh");
            redisComponent.updateTokenInfo(currentSnapshot);
            assertThat("Synthetic Redis refresh".equals(redisComponent.getTokenInfo(currentToken).getNickName()))
                    .as("a live session can still be refreshed using the actual serializer").isTrue();
            form("/account/logout", new LinkedMultiValueMap<>(), currentToken);
            assertHttpCode("/account/getUserInfo", currentToken, 901);
            redisComponent.updateTokenInfo(currentSnapshot);
            assertThat(redisComponent.getTokenInfo(currentToken) == null)
                    .as("profile updates cannot recreate a logged-out session").isTrue();
            assertThat(redisComponent.getCurrentToken(SECURITY_USER_ID) == null).isTrue();

            String expiringToken = loginSecurityUser();
            TokenUserInfoDTO expiringSnapshot = redisComponent.getTokenInfo(expiringToken);
            expireSyntheticToken(expiringToken);
            await().atMost(Duration.ofSeconds(4)).pollInterval(Duration.ofMillis(50))
                    .until(() -> redisComponent.getTokenInfo(expiringToken) == null);
            assertHttpCode("/account/getUserInfo", expiringToken, 901);
            redisComponent.updateTokenInfo(expiringSnapshot);
            assertThat(redisComponent.getTokenInfo(expiringToken) == null)
                    .as("profile updates cannot recreate an expired session").isTrue();
        } finally {
            redisComponent.forceLogout(SECURITY_USER_ID);
        }
    }

    @Test
    void realWebSocketsCloseOnLogoutAndExpireEvenWithoutClientTraffic() {
        prepareSyntheticSecurityUser(SECURITY_USER_ID);
        try {
            String logoutToken = loginSecurityUser();
            try (SocketProbe socket = openSocket(logoutToken)) {
                MessageSendDTO probe = new MessageSendDTO();
                probe.setUserId(SECURITY_USER_ID);
                probe.setMessageId(-2);
                probe.setAssistantMessage("synthetic-authenticated-websocket-probe");
                messageHandler.sendMessage(probe);
                socket.awaitSyntheticMessage();
                form("/account/logout", new LinkedMultiValueMap<>(), logoutToken);
                socket.awaitClosed(Duration.ofSeconds(5), "logout revocation");
                assertHttpCode("/account/getUserInfo", logoutToken, 901);
            }
            assertSocketRejected(logoutToken);

            String expiringToken = loginSecurityUser();
            try (SocketProbe socket = openSocket(expiringToken)) {
                expireSyntheticToken(expiringToken);
                // 不发送心跳或私有消息，验证周期扫描能兜底关闭自然过期的连接。
                socket.awaitClosed(Duration.ofSeconds(20), "idle token expiry");
                assertHttpCode("/account/getUserInfo", expiringToken, 901);
            }
            assertSocketRejected(expiringToken);
        } finally {
            redisComponent.forceLogout(SECURITY_USER_ID);
        }
    }

    @Test
    void adminProcessBanImmediatelyClosesWebSocketAndSeparatesUserAndAdminTokens() {
        prepareSyntheticSecurityUser(SECURITY_USER_ID);
        String adminToken = loginSyntheticAdmin();
        try {
            changeSyntheticUserStatus(adminToken, 1);
            String userToken = loginSecurityUser();
            assertAdminCode("/user/loadUser", null, 901);
            assertAdminCode("/user/loadUser", userToken, 901);
            assertHttpCode("/account/getUserInfo", adminToken, 901);
            try (SocketProbe socket = openSocket(userToken)) {
                // 不发送客户端心跳；管理端与用户端运行在不同 JVM 中，仅通过 Redis 共享撤销通知。
                changeSyntheticUserStatus(adminToken, 0);
                socket.awaitClosed(Duration.ofSeconds(5), "cross-process administrator ban");
                assertHttpCode("/account/getUserInfo", userToken, 901);
                assertSocketRejected(userToken);
            }
        } finally {
            try {
                changeSyntheticUserStatus(adminToken, 1);
            } finally {
                redisComponent.forceLogout(SECURITY_USER_ID);
                ResponseEntity<String> logout = http.postForEntity(ADMIN_URL + "/account/logout",
                        new HttpEntity<>(new LinkedMultiValueMap<>(), adminHeaders(adminToken)), String.class);
                assertThat(logout.getStatusCode().is2xxSuccessful()).as("synthetic admin cleanup logout").isTrue();
            }
        }
    }

    @Test
    void bothRealUsersCanReadTheirOwnResourcesButCannotReadOrChangeEachOthersResources() {
        prepareSyntheticSecurityUser(SECURITY_USER_ID);
        assertThat(jdbc.queryForObject("select count(*) from user_info where user_id=? and email=?",
                Integer.class, USER_ID, "baseline@example.invalid")).isEqualTo(1);
        prepareOwnershipFixture(USER_ID, "AUTH02ORDERA001", "AUTH02PAYA001", "AUTH02ADDRA001", 0);
        prepareOwnershipFixture(SECURITY_USER_ID, "AUTH02ORDERB001", "AUTH02PAYB001", "AUTH02ADDRB001", 1);
        String tokenA = loginBaselineUser();
        String tokenB = loginSecurityUser();
        try {
            List<Map<String, Object>> orderRows = ownershipRows("order_info", "order_id",
                    "AUTH02ORDERA001", "AUTH02ORDERB001");
            List<Map<String, Object>> commentRows = ownershipRows("order_comment", "order_id",
                    "AUTH02ORDERA001", "AUTH02ORDERB001");
            List<Map<String, Object>> addressRows = jdbc.queryForList(
                    "select * from user_address where user_id in (?,?) order by address_id", USER_ID, SECURITY_USER_ID);

            verifyOwnerAndRejectOther(tokenA, tokenB, USER_ID, "AUTH02ORDERA001", "AUTH02PAYA001", "AUTH02ADDRA001");
            verifyOwnerAndRejectOther(tokenB, tokenA, SECURITY_USER_ID, "AUTH02ORDERB001", "AUTH02PAYB001", "AUTH02ADDRB001");

            assertThat(orderRows.equals(ownershipRows("order_info", "order_id", "AUTH02ORDERA001", "AUTH02ORDERB001")))
                    .as("cross-user HTTP requests leave both synthetic orders unchanged").isTrue();
            assertThat(commentRows.equals(ownershipRows("order_comment", "order_id", "AUTH02ORDERA001", "AUTH02ORDERB001")))
                    .as("cross-user HTTP requests leave both synthetic comments unchanged").isTrue();
            assertThat(addressRows.equals(jdbc.queryForList(
                    "select * from user_address where user_id in (?,?) order by address_id", USER_ID, SECURITY_USER_ID)))
                    .as("cross-user address edits cannot change either user's address or default flags").isTrue();
        } finally {
            redisComponent.cleanToken(tokenA);
            redisComponent.forceLogout(SECURITY_USER_ID);
        }
    }

    private void verifyOwnerAndRejectOther(String ownerToken, String otherToken, String userId,
                                          String orderId, String payOrderId, String addressId) {
        assertThat(get("/order/getOrderInfo?payOrderId=" + payOrderId, ownerToken).path("userId").asText())
                .isEqualTo(userId);
        assertThat(get("/order/comment/getComment?orderId=" + orderId, ownerToken).path("userId").asText())
                .isEqualTo(userId);
        assertThat(fieldValues(get("/userAddress/loadDataList", ownerToken), "addressId")).contains(addressId);

        assertHttpCode("/order/getOrderInfo?payOrderId=" + payOrderId, otherToken, 600);
        assertHttpCode("/order/comment/getComment?orderId=" + orderId, otherToken, 600);
        MultiValueMap<String, String> order = new LinkedMultiValueMap<>();
        order.add("orderId", orderId);
        assertFormCode("/order/cancelOrder", order, otherToken, 600);
        MultiValueMap<String, String> comment = new LinkedMultiValueMap<>();
        comment.add("orderId", orderId);
        comment.add("commentContent", "Synthetic unauthorized initial review");
        comment.add("star", "5");
        assertFormCode("/order/comment/postComment", comment, otherToken, 600);
        comment.add("reCommentContent", "Synthetic unauthorized follow-up review");
        assertFormCode("/order/comment/postReComment", comment, otherToken, 600);

        MultiValueMap<String, String> address = new LinkedMultiValueMap<>();
        address.add("addressId", addressId);
        address.add("userId", userId);
        address.add("address", "Synthetic cross-user address injection");
        address.add("addressee", "Synthetic injection");
        address.add("phone", "13000000000");
        address.add("defaultType", "1");
        assertFormCode("/userAddress/updateAddress", address, otherToken, 600);
        assertFormCode("/userAddress/updateDefault", address, otherToken, 600);
    }

    private void prepareOwnershipFixture(String userId, String orderId, String payOrderId,
                                         String addressId, int defaultType) {
        assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo("easymall_test");
        if (jdbc.queryForObject("select count(*) from order_info where order_id=?", Integer.class, orderId) == 0) {
            // 已完成状态的合成记录仅用于验证权限，不操作库存或支付渠道。
            jdbc.update("""
                    insert into order_info (order_id,amount,user_id,order_time,order_status,pay_order_id,comment_status)
                    values (?,3999,?,current_timestamp,3,?,1)
                    """, orderId, userId, payOrderId);
        }
        Map<String, Object> existingOrder = jdbc.queryForMap("select * from order_info where order_id=?", orderId);
        assertThat(userId.equals(existingOrder.get("user_id")) && payOrderId.equals(existingOrder.get("pay_order_id")))
                .as("only a known synthetic ownership order may be reused").isTrue();
        if (jdbc.queryForObject("select count(*) from order_comment where order_id=?", Integer.class, orderId) == 0) {
            jdbc.update("""
                    insert into order_comment (order_id,product_id,comment_content,comment_time,star,user_id,status)
                    values (?,?,?,current_timestamp,5,?,0)
                    """, orderId, PRODUCT_ID, "Synthetic step 02 ownership fixture", userId);
        }
        Map<String, Object> existingComment = jdbc.queryForMap("select * from order_comment where order_id=?", orderId);
        assertThat(userId.equals(existingComment.get("user_id"))
                && PRODUCT_ID.equals(existingComment.get("product_id"))
                && "Synthetic step 02 ownership fixture".equals(existingComment.get("comment_content")))
                .as("only a known synthetic ownership comment may be reused").isTrue();
        if (jdbc.queryForObject("select count(*) from user_address where address_id=?", Integer.class, addressId) == 0) {
            jdbc.update("""
                    insert into user_address (address_id,user_id,address,addressee,phone,default_type)
                    values (?,?,?,?,?,?)
                    """, addressId, userId, "Synthetic authorization fixture " + userId,
                    "Synthetic recipient", "13000000000", defaultType);
        }
        Map<String, Object> existingAddress = jdbc.queryForMap("select * from user_address where address_id=?", addressId);
        assertThat(userId.equals(existingAddress.get("user_id"))
                && ("Synthetic authorization fixture " + userId).equals(existingAddress.get("address")))
                .as("only a known synthetic ownership address may be reused").isTrue();
    }

    private List<Map<String, Object>> ownershipRows(String table, String idColumn, String first, String second) {
        // 此处 SQL 表名和列名均为测试内部的固定值，不接收请求输入。
        return jdbc.queryForList("select * from " + table + " where " + idColumn + " in (?,?) order by " + idColumn,
                first, second);
    }

    private String loginBaselineUser() {
        String checkCodeKey = redisComponent.saveCheckCode("11");
        MultiValueMap<String, String> login = new LinkedMultiValueMap<>();
        login.add("email", "baseline@example.invalid");
        login.add("password", StringTools.encodeByMD5("Baseline123"));
        login.add("checkCodeKey", checkCodeKey);
        login.add("checkCode", "11");
        JsonNode result = form("/account/login", login, null);
        assertThat(result.path("userId").asText()).isEqualTo(USER_ID);
        assertThat(!result.path("token").asText().isBlank()).isTrue();
        return result.path("token").asText();
    }

    private void assertFormCode(String path, MultiValueMap<String, String> body, String token, int expected) {
        ResponseEntity<JsonNode> response = http.postForEntity(url(path),
                new HttpEntity<>(body, headers(token, MediaType.APPLICATION_FORM_URLENCODED)), JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(Objects.requireNonNull(response.getBody()).path("code").asInt())
                .as("%s rejects a cross-user operation", path).isEqualTo(expected);
    }

    private String loginSecurityUser() {
        String checkCodeKey = redisComponent.saveCheckCode("11");
        MultiValueMap<String, String> login = new LinkedMultiValueMap<>();
        login.add("email", "security-b@example.invalid");
        login.add("password", StringTools.encodeByMD5("SyntheticB123"));
        login.add("checkCodeKey", checkCodeKey);
        login.add("checkCode", "11");
        JsonNode result = form("/account/login", login, null);
        assertThat(result.path("userId").asText()).isEqualTo(SECURITY_USER_ID);
        assertThat(!result.path("token").asText().isBlank()).as("synthetic user login issues a token").isTrue();
        return result.path("token").asText();
    }

    private String loginSyntheticAdmin() {
        String checkCodeKey = redisComponent.saveCheckCode("11");
        MultiValueMap<String, String> login = new LinkedMultiValueMap<>();
        login.add("account", "baseline-admin");
        login.add("password", StringTools.encodeByMD5("BaselineAdmin123"));
        login.add("checkCodeKey", checkCodeKey);
        login.add("checkCode", "11");
        JsonNode result = success("synthetic administrator login", http.postForEntity(ADMIN_URL + "/account/login",
                new HttpEntity<>(login, adminHeaders(null)), JsonNode.class));
        assertThat(result.isTextual() && !result.asText().isBlank()).as("synthetic admin token is present").isTrue();
        return result.asText();
    }

    private void changeSyntheticUserStatus(String adminToken, int status) {
        assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo("easymall_test");
        prepareSyntheticSecurityUser(SECURITY_USER_ID);
        MultiValueMap<String, String> change = new LinkedMultiValueMap<>();
        change.add("userId", SECURITY_USER_ID);
        change.add("status", Integer.toString(status));
        success("synthetic user administrator status change", http.postForEntity(ADMIN_URL + "/user/changeStatus",
                new HttpEntity<>(change, adminHeaders(adminToken)), JsonNode.class));
        assertThat(jdbc.queryForObject("select status from user_info where user_id=?", Integer.class, SECURITY_USER_ID))
                .as("admin and web use the same isolated synthetic user").isEqualTo(status);
    }

    private static HttpHeaders adminHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (token != null) {
            headers.set(Constants.TOKEN_ADMIN, token);
        }
        return headers;
    }

    private void assertHttpCode(String path, String token, int expected) {
        ResponseEntity<JsonNode> response = http.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(headers(token, null)), JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as("%s preserves the response envelope", path).isTrue();
        assertThat(Objects.requireNonNull(response.getBody()).path("code").asInt())
                .as("%s business code", path).isEqualTo(expected);
    }

    private void assertAdminCode(String path, String token, int expected) {
        ResponseEntity<JsonNode> response = http.exchange(ADMIN_URL + path, HttpMethod.GET,
                new HttpEntity<>(adminHeaders(token)), JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(Objects.requireNonNull(response.getBody()).path("code").asInt())
                .as("admin %s business code", path).isEqualTo(expected);
    }

    private void expireSyntheticToken(String token) {
        TokenUserInfoDTO session = redisComponent.getTokenInfo(token);
        assertThat(session != null && SECURITY_USER_ID.equals(session.getUserId()))
                .as("only the dedicated synthetic user's live token may be expired").isTrue();
        assertThat(redisTemplate.expire(Constants.REDIS_KEY_TOKEN_WEB + token, Duration.ofSeconds(1))).isTrue();
    }

    private SocketProbe openSocket(String token) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        ProbeListener listener = new ProbeListener();
        try {
            WebSocket socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3))
                    .buildAsync(socketUri(token), listener).get(5, TimeUnit.SECONDS);
            assertThat(!listener.closed.isDone()).as("the verified synthetic socket starts open").isTrue();
            return new SocketProbe(client, socket, listener);
        } catch (Exception e) {
            client.shutdownNow();
            // 异常消息或原因可能包含连接地址，不能把其中的合成 Token 写入日志。
            throw new AssertionError("Synthetic WebSocket handshake failed: " + e.getClass().getSimpleName());
        }
    }

    private void assertSocketRejected(String token) {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            try {
                WebSocket socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3))
                        .buildAsync(socketUri(token), new ProbeListener()).get(5, TimeUnit.SECONDS);
                socket.abort();
                throw new AssertionError("An invalid synthetic session must not upgrade to WebSocket");
            } catch (ExecutionException e) {
                assertThat(e.getCause() instanceof WebSocketHandshakeException)
                        .as("invalid sessions are rejected during the HTTP handshake").isTrue();
                assertThat(((WebSocketHandshakeException) e.getCause()).getResponse().statusCode()).isEqualTo(403);
            } catch (Exception e) {
                throw new AssertionError("Synthetic rejected-handshake probe failed: " + e.getClass().getSimpleName());
            }
        }
    }

    private URI socketUri(String token) {
        assertThat(appConfig.getWsPort()).as("only the isolated baseline WebSocket endpoint is allowed").isEqualTo(6051);
        // 与现有前端环境配置保持一致，覆盖实际使用的带尾斜杠地址。
        return URI.create("ws://127.0.0.1:6051/ws/?token=" + token);
    }

    private static final class ProbeListener implements WebSocket.Listener {
        private final CompletableFuture<Boolean> closed = new CompletableFuture<>();
        private final CompletableFuture<String> text = new CompletableFuture<>();
        private final StringBuilder fragments = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) {
                text.complete(fragments.toString());
                fragments.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(true);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            // 服务端撤销会话时可能直接关闭 TCP，不发送关闭帧；不记录可能带连接地址的异常详情。
            closed.complete(true);
        }
    }

    private record SocketProbe(HttpClient client, WebSocket socket, ProbeListener listener) implements AutoCloseable {
        void awaitSyntheticMessage() {
            try {
                String payload = listener.text.get(5, TimeUnit.SECONDS);
                assertThat(payload.contains("synthetic-authenticated-websocket-probe"))
                        .as("authenticated private data traverses the Redis topic and real WebSocket encoder").isTrue();
            } catch (Exception e) {
                throw new AssertionError("Synthetic WebSocket data probe failed: " + e.getClass().getSimpleName());
            }
        }

        void awaitClosed(Duration timeout, String operation) {
            try {
                assertThat(listener.closed.get(timeout.toMillis(), TimeUnit.MILLISECONDS)).as(operation).isTrue();
            } catch (Exception e) {
                throw new AssertionError("Synthetic WebSocket did not close for " + operation + ": "
                        + e.getClass().getSimpleName());
            }
        }

        @Override
        public void close() {
            socket.abort();
            client.shutdownNow();
        }
    }

    @Test
    void invalidInputsCannotWriteBusinessRowsOrInvokeExternalWork() {
        assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo("easymall_test");
        String token = loginBaselineUser();
        Map<String, Integer> rowsBefore = inputBoundaryRowCounts();
        Integer stockBefore = stock(StringTools.encodeByMD5(PROPERTY_VALUE_ID));
        JsonNode modelBefore = modelHealth();
        try {
            Map<String, Object> item = Map.of("productId", PRODUCT_ID, "propertyValueIds", PROPERTY_VALUE_ID, "buyCount", 1);
            List<Object> invalidLists = new java.util.ArrayList<>();
            invalidLists.add(null);
            invalidLists.add(List.of());
            invalidLists.add(java.util.Collections.singletonList(null));
            for (int quantity : List.of(-1, 0, 100)) {
                invalidLists.add(List.of(Map.of("productId", PRODUCT_ID, "propertyValueIds", PROPERTY_VALUE_ID, "buyCount", quantity)));
            }
            invalidLists.add(List.of(item, item));
            invalidLists.add(java.util.Collections.nCopies(51, item));
            for (Object items : invalidLists) {
                Map<String, Object> order = new HashMap<>();
                order.put("payMethod", "alipay_pc");
                order.put("addressId", "BASEADDR000001");
                order.put("orderFrom", 0);
                order.put("orderList", items);
                assertInputJsonRejected("/order/postOrder", order, token);
            }
            for (String quantity : List.of("0", "-1", "100")) {
                MultiValueMap<String, String> cart = new LinkedMultiValueMap<>();
                cart.add("productId", PRODUCT_ID);
                cart.add("propertyValueIds", PROPERTY_VALUE_ID);
                cart.add("buyCount", quantity);
                assertFormCode("/productCart/add2Cart", cart, token, 600);
            }
            for (String message : List.of(" ", "界".repeat(1001))) {
                MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                form.add("message", message);
                assertFormCode("/agent/sendMessage", form, token, 600);
            }
            for (String star : List.of("0", "6")) {
                MultiValueMap<String, String> comment = new LinkedMultiValueMap<>();
                comment.add("orderId", "AUTH02ORDERA001");
                comment.add("commentContent", "Synthetic invalid rating");
                comment.add("star", star);
                assertFormCode("/order/comment/postComment", comment, token, 600);
            }
            assertHttpCode("/product/loadProduct?pageNo=0", null, 600);
            assertHttpCode("/product/search?keyWords=baseline&sortField=unknown", null, 600);
            MultiValueMap<String, String> internal = new LinkedMultiValueMap<>();
            internal.add("orderBy", "order_time desc; select 1");
            assertFormCode("/order/loadMyOrder", internal, token, 600);
            assertThat(inputBoundaryRowCounts()).as("非法请求不得产生订单、购物车、评论或AI消息记录").isEqualTo(rowsBefore);
            assertThat(stock(StringTools.encodeByMD5(PROPERTY_VALUE_ID))).isEqualTo(stockBefore);
            JsonNode modelAfter = modelHealth();
            for (String counter : List.of("chatRequests", "embeddingRequests")) {
                assertThat(modelAfter.path("counters").path(counter).asLong())
                        .as("非法AI输入不能触发本地模型替身").isEqualTo(modelBefore.path("counters").path(counter).asLong());
            }
            org.mockito.Mockito.verifyNoInteractions(payment);
        } finally {
            redisComponent.cleanToken(token);
        }
    }

    @Test
    void realAdminListUsesWhitelistedSortAndBoundedPaging() {
        String token = loginSyntheticAdmin();
        try {
            JsonNode list = success("bounded synthetic admin list", http.exchange(
                    ADMIN_URL + "/user/loadUser?pageNo=1&pageSize=1&sortField=joinTime&sortDirection=desc",
                    HttpMethod.GET, new HttpEntity<>(adminHeaders(token)), JsonNode.class));
            assertThat(list.path("pageSize").asInt()).isEqualTo(1);
            assertThat(list.path("list").size()).isLessThanOrEqualTo(1);
            assertAdminCode("/user/loadUser?pageSize=101", token, 600);
            assertAdminCode("/user/loadUser?sortField=password&sortDirection=asc", token, 600);
            assertAdminCode("/user/loadUser?sortField=joinTime&sortDirection=invalid", token, 600);
            assertAdminCode("/user/loadUser?orderBy=password", token, 600);
            assertAdminCode("/user/loadUser?simplePage.pageSize=10000", token, 600);
        } finally {
            http.postForEntity(ADMIN_URL + "/account/logout",
                    new HttpEntity<>(new LinkedMultiValueMap<>(), adminHeaders(token)), String.class);
        }
    }

    private Map<String, Integer> inputBoundaryRowCounts() {
        Map<String, Integer> counts = new HashMap<>();
        // 表名均为本测试的固定常量，不接收请求提供的 SQL 标识符。
        for (String table : List.of("order_info", "order_item", "order_logistics_info", "product_cart", "agent_message", "order_comment")) {
            counts.put(table, jdbc.queryForObject("select count(*) from " + table, Integer.class));
        }
        return counts;
    }

    @Autowired private com.easymall.files.ImageUploadQuota imageQuota;

    @Test
    void realUploadsRequireIdentityReencodeAndKeepPublicImagesReadable() throws Exception {
        String token = loginBaselineUser();
        String admin = loginSyntheticAdmin();
        try {
            var image = new java.awt.image.BufferedImage(320, 160, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var bytes = new java.io.ByteArrayOutputStream();
            assertThat(javax.imageio.ImageIO.write(image, "png", bytes)).isTrue();
            assertThat(uploadFixture(url("/file/uploadImage"), null, "fixture.png", bytes.toByteArray()).path("code").asInt()).isEqualTo(901);
            assertThat(uploadFixture(ADMIN_URL + "/file/uploadImage", null, "fixture.png", bytes.toByteArray()).path("code").asInt()).isEqualTo(901);
            for (boolean backend : List.of(false, true)) {
                String base = backend ? ADMIN_URL : "http://127.0.0.1:" + port + "/api";
                String credential = backend ? admin : token;
                JsonNode result = uploadFixture(base + "/file/uploadImage", credential, "fixture.png", bytes.toByteArray());
                assertThat(result.path("code").asInt()).isEqualTo(200);
                String source = result.path("data").asText();
                assertThat(source).matches("[0-9]{6}/[a-f0-9]{32}\\.png");
                ResponseEntity<byte[]> original = http.getForEntity(base + "/file/getResource?sourceName=" + source, byte[].class);
                assertThat(original.getStatusCode().value()).isEqualTo(200);
                assertThat(original.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
                assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(original.getBody())).getWidth()).isEqualTo(320);
                ResponseEntity<byte[]> thumb = http.getForEntity(base + "/file/getResource?sourceName=" + source.replace(".png", "_thumbnail.png"), byte[].class);
                assertThat(thumb.getStatusCode().value()).isEqualTo(200);
                assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(thumb.getBody())).getWidth()).isEqualTo(200);
                assertThat(uploadFixture(base + "/file/uploadImage", credential, "fake.png", "<svg onload='alert(1)'/>".getBytes()).path("code").asInt()).isEqualTo(600);
                assertThat(uploadFixture(base + "/file/uploadImage", credential, "fake.svg", bytes.toByteArray()).path("code").asInt()).isEqualTo(600);
                assertThat(uploadFixture(base + "/file/uploadImage", credential, "huge.png", new byte[5 * 1024 * 1024 + 1]).path("code").asInt()).isEqualTo(600);
                for (String path : List.of("../secret.png", "C:/private.png", "month/../../secret.png")) {
                    ResponseEntity<JsonNode> rejected = http.getForEntity(base + "/file/getResource?sourceName=" + path, JsonNode.class);
                    assertThat(rejected.getBody().path("code").asInt()).isEqualTo(600);
                }
            }
        } finally {
            redisComponent.cleanToken(token);
            http.postForEntity(ADMIN_URL + "/account/logout", new HttpEntity<>(new LinkedMultiValueMap<>(), adminHeaders(admin)), String.class);
        }
    }

    private JsonNode uploadFixture(String endpoint, String credential, String name, byte[] bytes) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new org.springframework.core.io.ByteArrayResource(bytes) {
            @Override public String getFilename() { return name; }
        });
        form.add("createThumbnail", "true");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (credential != null) {
            headers.set(endpoint.startsWith(ADMIN_URL) ? Constants.TOKEN_ADMIN : Constants.TOKEN_WEB, credential);
        }
        ResponseEntity<JsonNode> response = http.postForEntity(endpoint, new HttpEntity<>(form, headers), JsonNode.class);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    @Test
    void realRedisUploadLimitsAreAtomicAndStopAtExactDailyBudget() throws Exception {
        var identity = new com.easymall.security.CurrentUser("UPLOAD" + java.util.UUID.randomUUID(), null, null, com.easymall.security.CurrentUser.Role.USER);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Callable<Boolean>> calls = java.util.stream.IntStream.range(0, 40).mapToObj(index -> (java.util.concurrent.Callable<Boolean>) () -> {
                try { imageQuota.checkAttempt(identity); return true; }
                catch (com.easymall.exception.BusinessException expected) { return false; }
            }).toList();
            int accepted = 0;
            for (var result : workers.invokeAll(calls)) {
                if (result.get()) {
                    accepted++;
                }
            }
            assertThat(accepted).isEqualTo(20);
        } finally { workers.shutdownNow(); }
        imageQuota.reserveBytes(identity, 100L * 1024 * 1024 - 1);
        org.junit.jupiter.api.Assertions.assertThrows(com.easymall.exception.BusinessException.class, () -> imageQuota.reserveBytes(identity, 2));
        imageQuota.reserveBytes(identity, 1);
        org.junit.jupiter.api.Assertions.assertThrows(com.easymall.exception.BusinessException.class, () -> imageQuota.reserveBytes(identity, 1));
    }

    private void assertInputJsonRejected(String path, Object body, String token) {
        ResponseEntity<JsonNode> response = http.postForEntity(url(path),
                new HttpEntity<>(body, headers(token, MediaType.APPLICATION_JSON)), JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(Objects.requireNonNull(response.getBody()).path("code").asInt()).isEqualTo(600);
    }

    private void prepareSyntheticSecurityUser(String userId) {
        Integer count = jdbc.queryForObject("select count(*) from user_info where user_id=?",
                Integer.class, userId);
        if (count == 0) {
            jdbc.update("""
                    insert into user_info (user_id, nick_name, avatar, email, password, sex,
                                           join_time, status, api_key)
                    values (?, ?, ?, ?, ?, ?, current_timestamp, ?, ?)
                    """, userId, "Security fixture B", "security/synthetic-b.png",
                    "security-b@example.invalid", StringTools.encodeByMD5("SyntheticB123"),
                    2, 1, "synthetic-security-b-key");
        }
        assertThat("security-b@example.invalid".equals(jdbc.queryForObject(
                "select email from user_info where user_id=?", String.class, userId)))
                .as("only the expected synthetic secondary user may be reused").isTrue();
    }

    private Map<String, Object> snapshotUser(String userId) {
        return jdbc.queryForMap("select * from user_info where user_id=?", userId);
    }

    private static Map<String, Object> nonEditableUserFields(Map<String, Object> row) {
        Map<String, Object> fields = new HashMap<>(row);
        fields.remove("avatar");
        fields.remove("nick_name");
        fields.remove("sex");
        return fields;
    }

    private static void assertCredentialSafe(JsonNode payload, List<String> secrets) {
        assertThat(payload.findValues("password").isEmpty())
                .as("password is absent from the HTTP payload").isTrue();
        assertThat(payload.findValues("apiKey").isEmpty() && payload.findValues("api_key").isEmpty())
                .as("API key is absent from the HTTP payload").isTrue();
        String json = payload.toString();
        for (String secret : secrets) {
            assertThat(json.contains(secret)).as("credential values cannot escape through HTTP").isFalse();
        }
    }

    private static void assertLegacyNotFound(String path, ResponseEntity<JsonNode> response,
                                           List<String> secrets) {
        assertThat(response.getStatusCode().is2xxSuccessful()).as("%s keeps the existing error envelope", path)
                .isTrue();
        JsonNode body = Objects.requireNonNull(response.getBody());
        assertThat(body.path("code").asInt()).as("%s is closed to anonymous requests", path).isEqualTo(404);
        assertCredentialSafe(body, secrets);
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
        // 登录响应包含合成 Token，因此断言失败时也不打印完整响应。
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
