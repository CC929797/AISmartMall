package com.easymall.files;

import com.easymall.exception.BusinessException;
import com.easymall.security.CurrentUser;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/** Redis 原子计量，多实例共用身份限额；Redis 故障时不放行上传。 */

/**
 * 限制每个登录身份的图片上传频率和每日上传总量，避免反复上传占用服务器资源
 */
@Component
public class ImageUploadQuota {
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            local used = tonumber(redis.call('GET', KEYS[1]) or '0')
            local amount = tonumber(ARGV[1])
            if used + amount > tonumber(ARGV[2]) then return 0 end
            local total = redis.call('INCRBY', KEYS[1], amount)
            if total == amount then redis.call('EXPIRE', KEYS[1], ARGV[3]) end
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final ImagePolicy policy;
    public ImageUploadQuota(StringRedisTemplate redis, ImagePolicy policy) { this.redis = redis; this.policy = policy; }
    public void checkAttempt(CurrentUser user) {
        reserve("minute:" + identity(user), 1, policy.getRequestsPerMinute(), 60, "图片上传过于频繁，请稍后再试");
    }
    public void reserveBytes(CurrentUser user, long bytes) {
        reserve("day:" + LocalDate.now(ZoneOffset.UTC) + ":" + identity(user), bytes,
                policy.getDailyBytes(), 172800, "今日图片上传额度已用完");
    }
    private String identity(CurrentUser user) { return DigestUtils.sha256Hex(user.role().name() + ":" + user.userId()); }
    private void reserve(String key, long amount, long limit, int expiry, String message) {
        Long accepted = redis.execute(RESERVE, List.of("easymall:upload:" + key),
                Long.toString(amount), Long.toString(limit), Integer.toString(expiry));
        if (!Long.valueOf(1).equals(accepted)) {
            throw new BusinessException(message);
        }
    }
}
