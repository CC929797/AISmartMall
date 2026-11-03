package com.easymall.security;

import com.easymall.exception.BusinessException;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 账号失败次数和来源请求次数分别计量，所有实例共享 Redis 原子计数。
 */
@Component
public class LoginAbuseProtection {
    private static final DefaultRedisScript<Long> ATTEMPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return count
            """, Long.class);
    private final StringRedisTemplate redis;
    private final int failures;
    private final int loginRequests;
    private final int captchaRequests;
    private final int failureWindow;

    public LoginAbuseProtection(StringRedisTemplate redis,
            @Value("${authentication.max-failures:5}") int failures,
            @Value("${authentication.login-requests-per-minute:30}") int loginRequests,
            @Value("${authentication.captcha-requests-per-minute:20}") int captchaRequests,
            @Value("${authentication.failure-window-seconds:300}") int failureWindow) {
        if (failures < 1 || loginRequests < 1 || captchaRequests < 1 || failureWindow < 1) {
            throw new IllegalArgumentException("登录限速参数必须大于零");
        }
        this.redis = redis;
        this.failures = failures;
        this.loginRequests = loginRequests;
        this.captchaRequests = captchaRequests;
        this.failureWindow = failureWindow;
    }

    /**
     * 登录限速
     * @param realm 认证域
     * @param account 账号
     * @param ip IP地址
     */
    public void checkLogin(String realm, String account, String ip) {
        //查看登录是否达到限制
        checkLoginSource(realm, ip);
        //检查账号是否已累计失败过多
        checkAccountFailures(realm, account);
    }

    /**
     * 登录来源限速
     * @param realm 认证域
     * @param ip IP地址
     */
    public void checkLoginSource(String realm, String ip) {
        reserve(realm + ":login:" + digest(ip), 60, loginRequests);
    }

    /**
     * 账号失败次数限速
     * @param realm 认证域
     * @param account 账号
     */
    public void checkAccountFailures(String realm, String account) {
        String count = redis.opsForValue().get(failureKey(realm, account));
        if (count != null && Long.parseLong(count) >= failures) {
            throw new BusinessException("登录失败次数过多，请稍后重试");
        }
    }

    /**
     * 登录失败
     * @param realm 认证域
     * @param account 账号
     */
    public void failed(String realm, String account) {
        Long count = redis.execute(ATTEMPT, List.of(failureKey(realm, account)), Integer.toString(failureWindow));
        if (count == null) {
            throw new BusinessException("登录校验暂不可用，请稍后重试");
        }
    }

    /**
     * 登录成功
     * @param realm 认证域
     * @param account 账号
     */
    public void succeeded(String realm, String account) {
        redis.delete(failureKey(realm, account));
    }

    /**
     * 验证码申请限速
     * @param realm
     * @param ip
     */
    public void checkCaptchaIssue(String realm, String ip) {
        reserve(realm + ":captcha:" + digest(ip), 60, captchaRequests);
    }

    /**
     * 注册申请限速
     * @param ip
     */
    public void checkRegistration(String ip) {
        reserve("web:register:" + digest(ip), 600, 10);
    }
    /**
     * 密码修改申请限速
     * @param userId
     */

    public void checkPasswordChange(String userId) {
        reserve("web:change-password:" + digest(userId), 600, 10);
    }

    /**
     * 限速
     * @param key 限速器名称
     * @param seconds 限速窗口秒数
     * @param limit 限速阈值
     */
    private void reserve(String key, int seconds, int limit) {

        Long count = redis.execute(ATTEMPT, List.of("easymall:auth:rate:" + key), Integer.toString(seconds));
        if (count == null || count > limit) {
            throw new BusinessException("操作过于频繁，请稍后重试");
        }
    }

    /**
     * 获取账号失败次数的 Redis 键名
     * @param realm 认证域
     * @param account 账号
     * @return Redis 键名
     */
    private String failureKey(String realm, String account) {
        return "easymall:auth:failure:" + realm + ":" + digest(account.toLowerCase(Locale.ROOT));
    }

    /**
     * 获取字符串的 SHA-256 散列值
     * @param value 待散列的字符串
     * @return SHA-256 散列值
     */
    private String digest(String value) {
        return DigestUtils.sha256Hex(value);
    }
}
