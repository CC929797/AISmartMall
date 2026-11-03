package com.easymall.redis;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component("redisUtils")
@Slf4j
public class RedisUtils<V> {

    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0",
            Long.class);

    private static final DefaultRedisScript<Long> REPLACE_CURRENT_SESSION = new DefaultRedisScript<>(
            "if redis.call('EXISTS', KEYS[1]) == 1 and redis.call('GET', KEYS[2]) == ARGV[2] then "
                    + "redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3]); "
                    + "redis.call('EXPIRE', KEYS[2], ARGV[3]); return 1 end return 0", Long.class);

    @Resource
    private RedisTemplate<String, V> redisTemplate;

    /**
     * 删除缓存
     *
     * @param key 可以传一个值 或多个
     */
    public void delete(String... key) {
        if (key != null && key.length > 0) {
            if (key.length == 1) {
                redisTemplate.delete(key[0]);
            } else {
                redisTemplate.delete((Collection<String>) CollectionUtils.arrayToList(key));
            }
        }
    }

    public V get(String key) {
        return key == null ? null : redisTemplate.opsForValue().get(key);
    }

    /** 原子读取并消费验证码，同一验证码最多有一个并发请求能取得。 */
    public V getAndDelete(String key) {
        return key == null ? null : redisTemplate.opsForValue().getAndDelete(key);
    }

    /** 仅删除仍等于预期值的映射，避免误删并发登录刚写入的新 Token。 */
    public boolean compareAndDelete(String key, V expectedValue) {
        return Long.valueOf(1).equals(redisTemplate.execute(COMPARE_AND_DELETE, List.of(key), expectedValue));
    }

    /** 原子刷新仍存在的当前会话，禁止重新创建已经撤销的会话。 */
    public boolean replaceSessionIfCurrent(String sessionKey, String userKey, V session, String token, long seconds) {
        return Long.valueOf(1).equals(redisTemplate.execute(REPLACE_CURRENT_SESSION,
                List.of(sessionKey, userKey), session, token, seconds));
    }

    /**
     * 普通缓存放入
     *
     * @param key   键
     * @param value 值
     * @return true成功 false失败
     */
    public boolean set(String key, V value) {
        try {
            redisTemplate.opsForValue().set(key, value);
            return true;
        } catch (Exception e) {
            log.error("Redis write failed: operation=set, exceptionType={}", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 普通缓存放入并设置时间
     *
     * @param key   键
     * @param value 值
     * @param time  时间(秒) time要大于0 如果time小于等于0 将设置无限期
     * @return true成功 false 失败
     */
    public boolean setex(String key, V value, long time) {
        try {
            if (time > 0) {
                redisTemplate.opsForValue().set(key, value, time, TimeUnit.SECONDS);
            } else {
                set(key, value);
            }
            return true;
        } catch (Exception e) {
            log.error("Redis write failed: operation=setex, exceptionType={}", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * set 关操作
     */
    public void zsetAdd(String key, V value, double score) {
        redisTemplate.opsForZSet().add(key, value, score);
    }

    public Set<V> zsetRangeByScore(String key, double min, double max) {
        return redisTemplate.opsForZSet().rangeByScore(key, min, max);
    }

    public Long zsetAddRemove(String key, V v) {
        return redisTemplate.opsForZSet().remove(key, v);
    }

}
