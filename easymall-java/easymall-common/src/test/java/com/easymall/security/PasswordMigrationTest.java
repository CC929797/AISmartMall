package com.easymall.security;

import com.easymall.commonent.RedisComponent;
import com.easymall.entity.po.UserInfo;
import com.easymall.entity.query.UserInfoQuery;
import com.easymall.exception.BusinessException;
import com.easymall.mappers.UserInfoMapper;
import com.easymall.service.Impl.UserInfoServiceImpl;
import com.easymall.utils.StringTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 覆盖旧账号升级、新注册、密码规则和改密撤销会话的真实业务入口。 */
class PasswordMigrationTest {
    private final UserInfoMapper<UserInfo, UserInfoQuery> mapper = mock(UserInfoMapper.class);
    private final RedisComponent redis = mock(RedisComponent.class);
    private final UserInfoServiceImpl service = new UserInfoServiceImpl();
    private final PasswordService passwords = new PasswordService();
    private final CredentialLock lock = mock(CredentialLock.class);
    private static final String PASSWORD = "Password123";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "userInfoMapper", mapper);
        ReflectionTestUtils.setField(service, "redisComponent", redis);
        ReflectionTestUtils.setField(service, "passwordService", passwords);
        ReflectionTestUtils.setField(service, "credentialLock", lock);
        when(mapper.selectByUserId(anyString())).thenAnswer(invocation -> legacyUser());
        when(mapper.replacePasswordIfCurrent(anyString(), anyString(), anyString())).thenReturn(1);
        when(lock.withUser(anyString(), any())).thenAnswer(invocation ->
                ((java.util.function.Supplier<?>) invocation.getArgument(1)).get());
    }

    private UserInfo legacyUser() {
        UserInfo user = new UserInfo();
        user.setUserId("PWDTEST001");
        user.setEmail("password-test@example.invalid");
        user.setStatus(1);
        user.setPassword(StringTools.encodeByMD5(PASSWORD));
        return user;
    }

    @Test
    void legacyAccountAcceptsOriginalPassword() {
        when(mapper.selectByEmail(anyString())).thenReturn(legacyUser());
        assertThatCode(() -> service.login("password-test@example.invalid", PASSWORD, "127.0.0.1"))
                .doesNotThrowAnyException();
        verify(redis).saveTokenInfo(any());
        ArgumentCaptor<String> encoding = ArgumentCaptor.forClass(String.class);
        verify(mapper).replacePasswordIfCurrent(eq("PWDTEST001"), eq(legacyUser().getPassword()), encoding.capture());
        assertThat(encoding.getValue()).startsWith("$argon2id$");
        assertThat(passwords.matches(PASSWORD, encoding.getValue())).isTrue();
    }

    @Test
    void registrationUsesModernSaltedEncoding() {
        service.register("new-password@example.invalid", "密码测试", PASSWORD);
        ArgumentCaptor<UserInfo> user = ArgumentCaptor.forClass(UserInfo.class);
        verify(mapper).insert(user.capture());
        assertThat(user.getValue().getPassword()).startsWith("$argon2id$").doesNotContain(PASSWORD);
    }

    @Test
    void weakNewPasswordIsRejectedAtServiceBoundary() {
        when(mapper.selectByUserId(anyString())).thenReturn(legacyUser());
        assertThatThrownBy(() -> service.updatePassword("PWDTEST001", PASSWORD, "123"))
                .isInstanceOf(BusinessException.class);
        verify(mapper, never()).updateByUserId(any(), anyString());
    }

    @Test
    void successfulPasswordChangeRevokesExistingSession() {
        when(mapper.selectByUserId(anyString())).thenReturn(legacyUser());
        service.updatePassword("PWDTEST001", PASSWORD, "NewPassword456");
        verify(redis).forceLogout("PWDTEST001");
    }

    @Test
    void clientDigestCannotReplaceOriginalPassword() {
        when(mapper.selectByEmail(anyString())).thenReturn(legacyUser());
        assertThatThrownBy(() -> service.login("password-test@example.invalid", legacyUser().getPassword(), "127.0.0.1"))
                .isInstanceOf(BusinessException.class);
        verify(mapper, never()).replacePasswordIfCurrent(anyString(), anyString(), anyString());
        verifyNoInteractions(redis);
    }

    @Test
    void wrongPasswordCannotUpgradeAccount() {
        when(mapper.selectByEmail(anyString())).thenReturn(legacyUser());
        assertThatThrownBy(() -> service.login("password-test@example.invalid", "WrongPassword123", "127.0.0.1"))
                .isInstanceOf(BusinessException.class);
        verify(mapper, never()).replacePasswordIfCurrent(anyString(), anyString(), anyString());
        verifyNoInteractions(redis);
    }

    @Test
    void modernAccountMatchesWithoutAnotherMigration() {
        UserInfo user = legacyUser();
        user.setPassword(passwords.encode(PASSWORD));
        when(mapper.selectByEmail(anyString())).thenReturn(user);
        when(mapper.selectByUserId(anyString())).thenReturn(user);
        service.login(user.getEmail(), PASSWORD, "127.0.0.1");
        verify(mapper, never()).replacePasswordIfCurrent(anyString(), anyString(), anyString());
        verify(redis).saveTokenInfo(any());
    }

    @Test
    void concurrentPasswordChangePreventsStaleLogin() {
        when(mapper.selectByEmail(anyString())).thenReturn(legacyUser());
        UserInfo changed = legacyUser();
        changed.setPassword(passwords.encode("NewPassword456"));
        when(mapper.selectByUserId(anyString())).thenReturn(changed);
        assertThatThrownBy(() -> service.login(changed.getEmail(), PASSWORD, "127.0.0.1"))
                .isInstanceOf(BusinessException.class);
        verify(mapper, never()).replacePasswordIfCurrent(anyString(), anyString(), anyString());
        verifyNoInteractions(redis);
    }

    @Test
    void lostMigrationCompareAndSetCannotIssueToken() {
        when(mapper.selectByEmail(anyString())).thenReturn(legacyUser());
        when(mapper.replacePasswordIfCurrent(anyString(), anyString(), anyString())).thenReturn(0);
        assertThatThrownBy(() -> service.login(legacyUser().getEmail(), PASSWORD, "127.0.0.1"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(redis);
    }

    @Test
    void wrongOldPasswordCannotChangePasswordOrRevokeSession() {
        assertThatThrownBy(() -> service.updatePassword("PWDTEST001", "WrongPassword123", "NewPassword456"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(redis);
        verify(mapper, never()).replacePasswordIfCurrent(anyString(), anyString(), anyString());
    }

    @Test
    void revocationFailureLeavesPasswordUnchanged() {
        doThrow(new IllegalStateException("synthetic Redis failure")).when(redis).forceLogout(anyString());
        assertThatThrownBy(() -> service.updatePassword("PWDTEST001", PASSWORD, "NewPassword456"))
                .isInstanceOf(IllegalStateException.class);
        verify(mapper, never()).replacePasswordIfCurrent(anyString(), anyString(), anyString());
    }

    @Test
    void revocationHappensBeforePasswordIsStored() {
        service.updatePassword("PWDTEST001", PASSWORD, "NewPassword456");
        var order = inOrder(redis, mapper);
        order.verify(redis).forceLogout("PWDTEST001");
        ArgumentCaptor<String> encoded = ArgumentCaptor.forClass(String.class);
        order.verify(mapper).replacePasswordIfCurrent(eq("PWDTEST001"), anyString(), encoded.capture());
        assertThat(passwords.matches("NewPassword456", encoded.getValue())).isTrue();
        assertThat(passwords.matches(PASSWORD, encoded.getValue())).isFalse();
    }
}
