package com.easymall.security;

import com.easymall.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** 验证算法、独立盐、旧编码兼容、输入边界及未知格式拒绝。 */
class PasswordServiceTest {
    private final PasswordService passwords = new PasswordService();

    @Test
    void samePasswordProducesDistinctSaltedEncodings() {
        String first = passwords.encode("Password123");
        String second = passwords.encode("Password123");
        assertThat(first).startsWith("$argon2id$v=19$m=19456,t=2,p=1$").isNotEqualTo(second);
        assertThat(first.length()).isLessThanOrEqualTo(255);
        assertThat(passwords.matches("Password123", first)).isTrue();
        assertThat(passwords.matches("Password123", second)).isTrue();
        assertThat(passwords.matches("WrongPassword", first)).isFalse();
        assertThat(passwords.matches(first, first)).isFalse();
        assertThat(passwords.needsUpgrade(first)).isFalse();
    }

    @Test
    void legacyEncodingOnlyVerifiesOriginalInput() {
        String legacy = org.apache.commons.codec.digest.DigestUtils.md5Hex("Password123");
        assertThat(passwords.matches("Password123", legacy)).isTrue();
        assertThat(passwords.matches("Password123", legacy.toUpperCase())).isTrue();
        assertThat(passwords.matches(legacy, legacy)).isFalse();
        assertThat(passwords.needsUpgrade(legacy)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"123", "abcdefgh", "12345678", "Password1234567890123", "含中文Password123", "Pass word123"})
    void weakNewPasswordIsRejected(String raw) {
        assertThatThrownBy(() -> passwords.validateNewPassword(raw)).isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"unknown", "{noop}Password123", "$argon2i$v=19$m=19456,t=2,p=1$x$x",
            "$argon2id$v=19$m=999999,t=2,p=1$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void unsupportedOrDamagedStoredValueIsRejected(String stored) {
        assertThat(passwords.matches("Password123", stored)).isFalse();
        assertThat(passwords.isModernEncoding(stored)).isFalse();
    }

    @Test
    void inputHasHardUpperBoundAndLegacyLoginDoesNotApplyNewStrengthRules() {
        assertThat(passwords.matches("x".repeat(257), "0".repeat(32))).isFalse();
        String legacy = org.apache.commons.codec.digest.DigestUtils.md5Hex("old");
        assertThat(passwords.matches("old", legacy)).isTrue();
    }
}
