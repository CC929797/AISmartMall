package com.easymall.security;

import com.easymall.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CredentialLockTest {
    private final RedissonClient client = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final CredentialLock credentials = new CredentialLock(client);

    @BeforeEach
    void setup() {
        when(client.getLock("easymall:auth:credential:SYNTHETIC")).thenReturn(lock);
    }

    @Test
    void actionIsNotExecutedWithoutLock() throws Exception {
        when(lock.tryLock(5, TimeUnit.SECONDS)).thenReturn(false);
        assertThatThrownBy(() -> credentials.withUser("SYNTHETIC", () -> { throw new AssertionError(); }))
                .isInstanceOf(BusinessException.class);
        verify(lock, never()).unlock();
    }

    @Test
    void actionFailureStillReleasesOwnedLock() throws Exception {
        when(lock.tryLock(5, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        assertThatThrownBy(() -> credentials.withUser("SYNTHETIC", () -> { throw new IllegalStateException(); }))
                .isInstanceOf(IllegalStateException.class);
        verify(lock).unlock();
    }
}
