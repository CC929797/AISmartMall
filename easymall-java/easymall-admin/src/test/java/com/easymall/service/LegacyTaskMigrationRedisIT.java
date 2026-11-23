package com.easymall.service;

import com.easymall.entity.constants.Constants;
import com.easymall.entity.dto.RagDataDTO;
import com.easymall.redis.RedisConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** 仅用于测试。在独立 Redis 的 DB 14 验证失败保留与逐条确认，转存事务本身由 MySQL 集成测试验证。 */
@EnabledIfSystemProperty(named = "legacy.redis.integration", matches = "true")
class LegacyTaskMigrationRedisIT {
    @Test
    void retainsPendingEntriesOnFailureAndAcknowledgesOnlyTransferredEntries() {
        var address = new RedisStandaloneConfiguration("127.0.0.1", 16379);
        address.setDatabase(14);
        var factory = new LettuceConnectionFactory(address);
        factory.afterPropertiesSet();
        var config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:16379").setDatabase(14);
        var client = Redisson.create(config);
        try {
            var redis = new RedisConfig<String>().redisTemplate(factory);
            redis.afterPropertiesSet();
            var rag = client.<RagDataDTO>getBlockingDeque(Constants.REDIS_QUEUE_RAG_DATA, JsonJacksonCodec.INSTANCE);
            var zset = redis.opsForZSet();
            String logisticsKey = Constants.REDIS_KEY_ORDER_LOGISTICS_QUEUE;
            String confirmationKey = Constants.REDIS_KEY_ORDER_DELAY_QUEUE_CONFIRM;
            assertThat(rag.isEmpty()).isTrue();
            assertThat(zset.size(logisticsKey)).isZero();
            assertThat(zset.size(confirmationKey)).isZero();
            rag.add(new RagDataDTO("42", "faq"));
            long future = System.currentTimeMillis() + 60_000;
            zset.add(logisticsKey, "it11b-logistics", future);
            zset.add(confirmationKey, "it11b-confirm", future);

            var transfer = mock(LegacyTaskTransfer.class);
            var migration = new LegacyTaskMigration();
            ReflectionTestUtils.setField(migration, "redissonClient", client);
            ReflectionTestUtils.setField(migration, "redisTemplate", redis);
            ReflectionTestUtils.setField(migration, "transfer", transfer);
            doAnswer(call -> {
                assertThat(rag.peekFirst().getDataId()).isEqualTo("42");
                throw new IllegalStateException("synthetic failed transaction");
            }).doNothing().when(transfer).transferRag("faq", "42");

            assertThatThrownBy(() -> migration.migrate(25)).isInstanceOf(IllegalStateException.class);
            assertThat(rag.size()).isEqualTo(1);
            assertThat(zset.size(logisticsKey)).isEqualTo(1);
            assertThat(zset.size(confirmationKey)).isEqualTo(1);

            doAnswer(call -> {
                assertThat(zset.score(logisticsKey, "it11b-logistics")).isEqualTo((double) future);
                assertThat(call.getArgument(2, Duration.class)).isPositive();
                return null;
            }).when(transfer).transferShipment(org.mockito.ArgumentMatchers.eq("it11b-logistics"),
                    org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.any(Duration.class));

            assertThat(migration.migrate(25)).isEqualTo(Map.of("rag", 1, "logistics", 1, "confirm", 1));
            assertThat(migration.migrate(25)).isEqualTo(Map.of("rag", 0, "logistics", 0, "confirm", 0));
        } finally {
            client.shutdown();
            factory.destroy();
        }
    }
}

