package com.easymall.files;

import com.easymall.exception.BusinessException;
import com.easymall.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
class ImageUploadQuotaTest {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ImageUploadQuota quota = new ImageUploadQuota(redis, new ImagePolicy());
    CurrentUser user = new CurrentUser("fixture-sensitive-account", null, null, CurrentUser.Role.USER);
    @Test void limitsAreReservedAtomicallyWithoutPuttingRawIdentityInKeys() {
        List<String> keys = new ArrayList<>();
        when(redis.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            keys.addAll(call.getArgument(1));
            assertTrue(((DefaultRedisScript<?>) call.getArgument(0)).getScriptAsString().contains("INCRBY"));
            return 1L;
        });
        quota.checkAttempt(user);
        quota.reserveBytes(user, 512);
        quota.checkAttempt(new CurrentUser(user.userId(), null, null, CurrentUser.Role.ADMIN));
        assertEquals(3, keys.size());
        assertNotEquals(keys.get(0), keys.get(2));
        assertTrue(keys.stream().noneMatch(key -> key.contains(user.userId())));
        verify(redis, times(2)).execute(any(DefaultRedisScript.class), anyList(), eq("1"), eq("20"), eq("60"));
        verify(redis).execute(any(DefaultRedisScript.class), anyList(), eq("512"), eq("104857600"), eq("172800"));
    }
    @Test void exhaustedRateAndBytesReject() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString())).thenReturn(0L);
        assertThrows(BusinessException.class, () -> quota.checkAttempt(user));
        assertThrows(BusinessException.class, () -> quota.reserveBytes(user, 1));
    }
    @Test void nullScriptResultIsNotAccepted() {
        assertThrows(BusinessException.class, () -> quota.checkAttempt(user));
    }
    @Test void redisFailureNeverFallsBackToUnlimitedUploads() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("fixture-unavailable"));
        assertThrows(IllegalStateException.class, () -> quota.checkAttempt(user));
    }
}
