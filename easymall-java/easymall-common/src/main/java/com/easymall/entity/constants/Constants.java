package com.easymall.entity.constants;

public class Constants {
    private static final String REDIS_KEY_PREFIX = "easymall:";

    public static final String REDIS_KEY_CHECK_CODE = REDIS_KEY_PREFIX + "checkCode:";

    public static final String REDIS_KEY_TOKEN_ADMIN = REDIS_KEY_PREFIX + "token:admin:";

    public static final Long REDIS_KEY_EXPIRES_ONE_MIN = 60L;

    public static final Long REDIS_KEY_EXPIRES_ONE_DAY = REDIS_KEY_EXPIRES_ONE_MIN * 60 * 24;

    public static final String TOKEN_ADMIN = "adminToken";
}
