package com.easymall.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.easymall.redis.RedisUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证存储失败时不会通过缓存键、缓存值或异常内容把 Token 写入日志。 */
class RedisSessionLoggingTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedWritesDoNotLogSessionKeysValuesOrExceptionContents(boolean expiring) {
        String syntheticSecret = "synthetic-session-credential-do-not-log";
        RedisTemplate<String, Object> template = mock(RedisTemplate.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        RuntimeException failure = new IllegalStateException("synthetic-error-" + syntheticSecret);
        doThrow(failure).when(values).set(anyString(), any());
        doThrow(failure).when(values).set(anyString(), any(), anyLong(), any(TimeUnit.class));
        RedisUtils<Object> redis = new RedisUtils<>();
        ReflectionTestUtils.setField(redis, "redisTemplate", template);
        Logger logger = (Logger) LoggerFactory.getLogger(RedisUtils.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        boolean additive = logger.isAdditive();
        logger.setAdditive(false);
        logger.addAppender(events);
        try {
            boolean saved = expiring ? redis.setex("session:" + syntheticSecret, syntheticSecret, 60)
                    : redis.set("session:" + syntheticSecret, syntheticSecret);

            assertThat(saved).isFalse();
            assertThat(events.list).hasSize(1);
            assertThat(events.list.get(0).getFormattedMessage().contains(syntheticSecret)).isFalse();
            assertThat(events.list.get(0).getFormattedMessage()).contains("operation=", "exceptionType=IllegalStateException");
            assertThat(events.list.get(0).getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(events);
            logger.setAdditive(additive);
            events.stop();
        }
    }
}
