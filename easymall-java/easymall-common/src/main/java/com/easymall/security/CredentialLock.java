package com.easymall.security;

import com.easymall.exception.BusinessException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 多个 Web 实例共用同一用户锁，串行化密码变更与会话签发。
 */
@Component
public class CredentialLock {
    private final RedissonClient redisson;

    public CredentialLock(RedissonClient redisson) {
        this.redisson = redisson;
    }

    /**
     * 串行化用户变更密码、会话签发操作。
     * @param userId 用户ID
     * @param action 用户操作
     * @return 操作结果
     * @param <T> 操作返回值类型
     */
    public <T> T withUser(String userId, Supplier<T> action) {
        RLock lock = redisson.getLock("easymall:auth:credential:" + userId);
        boolean acquired = false;
        try {
            // 不设置固定租约，使用 Redisson 看门狗续期，避免慢请求提前失锁。
            acquired = lock.tryLock(5, TimeUnit.SECONDS);
            if (!acquired) {
                throw new BusinessException("账号正在处理登录或密码变更，请稍后重试");
            }
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("账号操作已中断，请重试");
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
