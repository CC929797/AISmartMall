package com.easymall.security;

import com.easymall.entity.constants.Constants;
import com.easymall.exception.BusinessException;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** 统一密码编码与旧记录验证；接口始终接收原密码，不猜测客户端摘要协议。 */
@Component
public class PasswordService {
    public static final int MEMORY_KIB = 19 * 1024;
    public static final int ITERATIONS = 2;
    private static final Pattern LEGACY = Pattern.compile("[0-9a-fA-F]{32}");
    private static final Pattern MODERN = Pattern.compile(
            "\\$argon2id\\$v=19\\$m=([0-9]{1,6}),t=([0-9]{1,2}),p=([0-9]{1,2})\\$[A-Za-z0-9+/]{11,43}\\$[A-Za-z0-9+/]{22,86}");
    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, MEMORY_KIB, ITERATIONS);
    // 限制单实例同时进行的高成本计算，避免登录洪峰耗尽内存。
    private final Semaphore calculations = new Semaphore(2);

    /**
     * 更改密码时验证新密码是否符合要求。
     * @param raw
     */
    public void validateNewPassword(String raw) {
        if (raw == null || !raw.matches(Constants.REGEX_PASSWORD)) {
            throw new BusinessException("密码须为8至18位，至少包含一个字母和一个数字，并使用允许的特殊字符");
        }
    }

    /**
     * 验证密码输入是否合法，不进行编码或匹配。
     * @param raw
     * @return
     */
    public boolean validInput(String raw) {
        return raw != null && !raw.isEmpty() && raw.length() <= 256;
    }

    /**
     * 密码编码，使用Argon2id算法。
     * @param raw
     * @return
     */
    public String encode(String raw) {
        if (!validInput(raw)) {
            throw new BusinessException("密码参数不合法");
        }
        return calculate(() -> encoder.encode(raw));
    }


    /**
     * 密码匹配，支持旧的MD5与新的Argon2id两种格式。
     * @param raw
     * @param stored
     * @return
     */
    public boolean matches(String raw, String stored) {
        if (!validInput(raw) || stored == null) {
            return false;
        }
        // 如果此时数据库中存储的是旧的MD5密码，就对输入的密码进行md5处理再进行比较
        if (LEGACY.matcher(stored).matches()) {
            byte[] expected = stored.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
            byte[] actual = DigestUtils.md5Hex(raw.getBytes(StandardCharsets.UTF_8)).getBytes(StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(expected, actual);
        }
        // 验证新的Argon2id密码
        if (!isModernEncoding(stored)) {
            return false;
        }
        return calculate(() -> encoder.matches(raw, stored));
    }

    /**
     * 限定已存参数的资源上界，损坏或未知格式的编码一律拒绝。
     */
    public boolean isModernEncoding(String stored) {
        if (stored == null) {
            return false;
        }
        var matcher = MODERN.matcher(stored);
        if (!matcher.matches()) {
            return false;
        }
        int memory = Integer.parseInt(matcher.group(1));
        int iterations = Integer.parseInt(matcher.group(2));
        int parallelism = Integer.parseInt(matcher.group(3));
        return memory >= 8 && memory <= 65536 && iterations >= 1 && iterations <= 6
                && parallelism >= 1 && parallelism <= 4 && memory >= parallelism * 8;
    }

    /**
     * 判断密码是否需要升级，旧的MD5密码或参数不符合要求的密码均需要升级。
      * @param stored 存储的密码
     * @return 是否需要升级
     */
    public boolean needsUpgrade(String stored) {
        if (stored != null && LEGACY.matcher(stored).matches()) {
            return true;
        }
        return isModernEncoding(stored) && encoder.upgradeEncoding(stored);
    }

    private <T> T calculate(Supplier<T> operation) {
        if (!calculations.tryAcquire()) {
            throw new BusinessException("登录服务繁忙，请稍后重试");
        }
        try {
            return operation.get();
        } finally {
            calculations.release();
        }
    }
}
