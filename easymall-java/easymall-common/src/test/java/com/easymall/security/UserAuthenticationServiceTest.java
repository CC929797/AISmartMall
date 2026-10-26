package com.easymall.security;

import com.easymall.commonent.RedisComponent;
import com.easymall.entity.dto.TokenUserInfoDTO;
import com.easymall.entity.po.UserInfo;
import com.easymall.service.UserInfoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class UserAuthenticationServiceTest {
    private static final String USER = "AUTHUSER02";
    private static final String TOKEN = "synthetic-resolver-session";

    private final RedisComponent redis = mock(RedisComponent.class);
    private final UserInfoService users = mock(UserInfoService.class);
    private final UserAuthenticationService authentication = new UserAuthenticationService();
    private TokenUserInfoDTO session;
    private UserInfo user;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authentication, "redisComponent", redis);
        ReflectionTestUtils.setField(authentication, "userInfoService", users);
        session = new TokenUserInfoDTO();
        session.setUserId(USER);
        session.setNickName("Old cached profile");
        session.setToken(TOKEN);
        user = new UserInfo();
        user.setUserId(USER);
        user.setStatus(1);
        user.setNickName("Current synthetic profile");
        user.setAvatar("synthetic-avatar.png");
        when(redis.getTokenInfo(TOKEN)).thenReturn(session);
        when(redis.getCurrentToken(USER)).thenReturn(TOKEN);
        when(users.getUserInfoByUserId(USER)).thenReturn(user);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "null"})
    void missingTokenNeverCreatesAnIdentity(String token) {
        assertThat(authentication.resolve(token)).isNull();
        verifyNoInteractions(redis, users);
    }

    @Test
    void unknownOrExpiredSessionIsRejectedBeforeReadingUsers() {
        when(redis.getTokenInfo(TOKEN)).thenReturn(null);

        assertThat(authentication.resolve(TOKEN)).isNull();
        verifyNoInteractions(users);
    }

    @Test
    void activeCurrentSessionProducesAnImmutableSafeIdentity() {
        CurrentUser current = authentication.resolve(TOKEN);

        assertThat(current).isEqualTo(new CurrentUser(USER, "Current synthetic profile", "synthetic-avatar.png", CurrentUser.Role.USER));
        assertThat(CurrentUser.class.isRecord()).isTrue();
        assertThat(CurrentUser.class.getRecordComponents()).extracting(component -> component.getName())
                .containsExactly("userId", "nickName", "avatar", "role");
    }

    @Test
    void tokenMustMatchTheSessionItWasLoadedFrom() {
        session.setToken("different-synthetic-session");

        assertThat(authentication.resolve(TOKEN)).isNull();
        verifyNoInteractions(users);
    }

    @Test
    void sessionMustHaveAUserIdentity() {
        session.setUserId(null);

        assertThat(authentication.resolve(TOKEN)).isNull();
        verifyNoInteractions(users);
    }

    @Test
    void olderSessionLosesAuthorityAfterConcurrentRotation() {
        when(redis.getCurrentToken(USER)).thenReturn("newer-synthetic-session");

        assertThat(authentication.resolve(TOKEN)).isNull();
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 99})
    void disabledOrUnknownStatusRevokesExistingSession(int status) {
        user.setStatus(status);

        assertThat(authentication.resolve(TOKEN)).isNull();
        verify(redis).cleanToken(TOKEN);
    }

    @Test
    void missingStatusCannotGrantAccess() {
        user.setStatus(null);

        assertThat(authentication.resolve(TOKEN)).isNull();
        verify(redis).cleanToken(TOKEN);
    }

    @Test
    void deletedAccountRevokesExistingSession() {
        when(users.getUserInfoByUserId(USER)).thenReturn(null);

        assertThat(authentication.resolve(TOKEN)).isNull();
        verify(redis).cleanToken(TOKEN);
    }

    @Test
    void mismatchedUserRecordCannotChangeTheRequestIdentity() {
        user.setUserId("OTHERUSER02");

        assertThat(authentication.resolve(TOKEN)).isNull();
    }

    @Test
    void redisOrDatabaseFailureNeverFallsBackToADemoIdentity() {
        when(users.getUserInfoByUserId(USER)).thenThrow(new IllegalStateException("synthetic-storage-failure"));

        assertThatThrownBy(() -> authentication.resolve(TOKEN)).isInstanceOf(IllegalStateException.class);
    }
}
