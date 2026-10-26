package com.easymall.security;

import com.easymall.commonent.RedisComponent;
import com.easymall.entity.constants.Constants;
import com.easymall.entity.dto.TokenUserInfoDTO;
import com.easymall.entity.po.UserInfo;
import com.easymall.redis.RedisUtils;
import com.easymall.service.UserInfoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 使用内存替身验证会话生命周期，不连接 Redis，也不启动 Spring 上下文。 */
class RedisSessionRevocationTest {
    private static final String USER = "SYNTHETIC02";
    private static final String TOKEN = "synthetic-session-token";
    private static final String NEW_TOKEN = "synthetic-new-session-token";
    private static final String TOPIC = "easymall.auth.session-revoked";

    private final Map<String, Object> store = new HashMap<>();
    private final RTopic topic = mock(RTopic.class);
    private RedisComponent component;
    private boolean failWrites;

    @BeforeEach
    void setUp() {
        RedisUtils redis = mock(RedisUtils.class, invocation -> {
            Object[] args = invocation.getRawArguments();
            return switch (invocation.getMethod().getName()) {
                case "get" -> store.get(args[0]);
                case "delete" -> {
                    for (String key : (String[]) args[0]) store.remove(key);
                    yield null;
                }
                case "setex", "set" -> {
                    if (failWrites) yield false;
                    store.put((String) args[0], args[1]);
                    yield true;
                }
                case "compareAndDelete" -> {
                    boolean matches = args[1].equals(store.get(args[0]));
                    if (matches) store.remove(args[0]);
                    yield matches;
                }
                case "replaceSessionIfCurrent" -> {
                    boolean matches = store.containsKey(args[0]) && args[3].equals(store.get(args[1]));
                    if (matches) store.put((String) args[0], args[2]);
                    yield matches;
                }
                default -> org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            };
        });
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getTopic(eq(TOPIC), any())).thenReturn(topic);
        component = new RedisComponent();
        ReflectionTestUtils.setField(component, "redisUtils", redis);
        ReflectionTestUtils.setField(component, "redissonClient", redisson);
    }

    @Test
    void profileUpdateCannotResurrectARevokedSession() {
        component.updateTokenInfo(session(TOKEN));

        assertThat(store.containsKey(sessionKey(TOKEN))).isFalse();
        assertThat(store.containsKey(userKey())).isFalse();
    }

    @Test
    void profileUpdateCannotReplaceTheNewerSession() {
        store.put(sessionKey(TOKEN), session(TOKEN));
        store.put(sessionKey(NEW_TOKEN), session(NEW_TOKEN));
        store.put(userKey(), NEW_TOKEN);

        component.updateTokenInfo(session(TOKEN));

        assertThat(NEW_TOKEN.equals(store.get(userKey()))).isTrue();
    }

    @Test
    void staleTokenLogoutPreservesTheNewerReverseMapping() {
        store.put(sessionKey(TOKEN), session(TOKEN));
        store.put(sessionKey(NEW_TOKEN), session(NEW_TOKEN));
        store.put(userKey(), NEW_TOKEN);

        component.cleanToken(TOKEN);

        assertThat(NEW_TOKEN.equals(store.get(userKey()))).isTrue();
        assertThat(store.containsKey(sessionKey(NEW_TOKEN))).isTrue();
        assertThat(store.containsKey(sessionKey(TOKEN))).isFalse();
    }

    @Test
    void logoutNotifiesWebNodesToCloseOnlyTheRevokedSession() throws Exception {
        seedSession();

        component.cleanToken(TOKEN);

        JsonNode event = lastEvent();
        assertThat(event.path("userId").asText()).isEqualTo(USER);
        assertThat(TOKEN.equals(event.path("token").asText())).isTrue();
        assertThat(store.containsKey(sessionKey(TOKEN))).isFalse();
        assertThat(store.containsKey(userKey())).isFalse();
    }

    @Test
    void rotationNotifiesWebNodesAboutThePreviousSession() throws Exception {
        seedSession();

        component.saveTokenInfo(session(TOKEN));

        JsonNode event = lastEvent();
        assertThat(event.path("userId").asText()).isEqualTo(USER);
        assertThat(TOKEN.equals(event.path("token").asText())).isTrue();
        assertThat(store.containsKey(sessionKey(TOKEN))).isFalse();
    }

    @Test
    void forceLogoutNotifiesEvenWhenReverseMappingAlreadyExpired() throws Exception {
        component.forceLogout(USER);

        JsonNode event = lastEvent();
        assertThat(event.path("userId").asText()).isEqualTo(USER);
        assertThat(event.path("token").isNull() || event.path("token").isMissingNode()).isTrue();
    }

    @Test
    void validSessionProfileUpdatePreservesCurrentSession() {
        seedSession();
        TokenUserInfoDTO changed = session(TOKEN);
        changed.setNickName("Synthetic updated profile");

        component.updateTokenInfo(changed);

        assertThat(((TokenUserInfoDTO) store.get(sessionKey(TOKEN))).getNickName())
                .isEqualTo("Synthetic updated profile");
        assertThat(TOKEN.equals(store.get(userKey()))).isTrue();
    }

    @Test
    void missingTokenLogoutIsIdempotent() {
        component.cleanToken(null);
        component.cleanToken("");

        assertThat(store).isEmpty();
        verifyNoInteractions(topic);
    }

    @Test
    void failedSessionStorageCannotReportSuccessfulLogin() {
        failWrites = true;

        assertThatThrownBy(() -> component.saveTokenInfo(session(TOKEN)))
                .isInstanceOf(com.easymall.exception.BusinessException.class);

        assertThat(store).isEmpty();
    }

    @Test
    void lostRevocationNotificationDoesNotRestoreTheDeletedSession() {
        seedSession();
        doThrow(new IllegalStateException("synthetic-publish-failure")).when(topic).publish(anyString());

        component.cleanToken(TOKEN);

        assertThat(store.containsKey(sessionKey(TOKEN))).isFalse();
        assertThat(store.containsKey(userKey())).isFalse();
    }

    @Test
    void revocationEventDiagnosticsCannotExposeTheToken() {
        String diagnostic = new SessionRevokedEvent(USER, TOKEN).toString();

        assertThat(diagnostic.contains(TOKEN)).isFalse();
        assertThat(diagnostic).contains("token=<redacted>");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void authenticatedSnapshotCannotRefreshAfterLogoutOrBan(boolean forceLogout) {
        seedSession();
        UserInfo enabledUser = new UserInfo();
        enabledUser.setUserId(USER);
        enabledUser.setStatus(1);
        enabledUser.setNickName("Synthetic account");
        UserInfoService users = mock(UserInfoService.class);
        when(users.getUserInfoByUserId(USER)).thenReturn(enabledUser);
        UserAuthenticationService authentication = new UserAuthenticationService();
        ReflectionTestUtils.setField(authentication, "redisComponent", component);
        ReflectionTestUtils.setField(authentication, "userInfoService", users);
        CurrentUser current = authentication.resolve(TOKEN);
        assertThat(current).isNotNull();
        TokenUserInfoDTO snapshot = session(TOKEN);
        snapshot.setNickName(current.nickName());

        // 固定执行顺序：请求先通过认证并保留身份快照，然后撤销会话，模拟并发退出或封禁。
        if (forceLogout) component.forceLogout(USER); else component.cleanToken(TOKEN);

        assertThat(component.refreshTokenInfo(snapshot)).isFalse();
        assertThat(store).isEmpty();
        assertThat(authentication.resolve(TOKEN)).isNull();
    }

    @Test
    void refreshingAnActiveSessionKeepsTheTokenAndRenewsOnlyThatSession() {
        seedSession();

        assertThat(component.refreshTokenInfo(session(TOKEN))).isTrue();

        assertThat(store).hasSize(2);
        assertThat(TOKEN.equals(store.get(userKey()))).isTrue();
        verifyNoInteractions(topic);
    }

    private void seedSession() {
        store.put(sessionKey(TOKEN), session(TOKEN));
        store.put(userKey(), TOKEN);
    }

    private static TokenUserInfoDTO session(String token) {
        TokenUserInfoDTO session = new TokenUserInfoDTO();
        session.setUserId(USER);
        session.setNickName("Synthetic session fixture");
        session.setToken(token);
        return session;
    }

    private JsonNode lastEvent() throws Exception {
        ArgumentCaptor<String> event = ArgumentCaptor.forClass(String.class);
        verify(topic).publish(event.capture());
        return new ObjectMapper().readTree(event.getValue());
    }

    private static String sessionKey(String token) {
        return Constants.REDIS_KEY_TOKEN_WEB + token;
    }

    private static String userKey() {
        return Constants.REDIS_KEY_TOKEN_USERID_WEB + USER;
    }
}
