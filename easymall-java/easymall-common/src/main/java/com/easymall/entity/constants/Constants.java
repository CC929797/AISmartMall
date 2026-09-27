package com.easymall.entity.constants;

public class Constants {
    public static final String REGEX_PASSWORD = "^(?=.*\\d)(?=.*[a-zA-Z])[\\da-zA-Z~!@#$%^&*_]{8,18}$";

    public static final String ZERO_STR = "0";
    public static final Integer LENGTH_5 = 5;
    public static final Integer LENGTH_10 = 10;
    public static final Integer LENGTH_15 = 15;
    public static final Integer LENGTH_30 = 30;

    private static final String REDIS_KEY_PREFIX = "easymall:";

    public static final String REDIS_KEY_CHECK_CODE = REDIS_KEY_PREFIX + "checkCode:";

    public static final String REDIS_KEY_TOKEN_ADMIN = REDIS_KEY_PREFIX + "token:admin:";

    public static final String REDIS_KEY_CATEGORY_LIST = REDIS_KEY_PREFIX + "category:list:";

    public static final String REDIS_KEY_TOKEN_WEB = REDIS_KEY_PREFIX + "token:web:";

    public static final String REDIS_KEY_TOKEN_USERID_WEB = REDIS_KEY_PREFIX + "token:web:userId:";

    //支付订单延时队列
    public static final String REDIS_KEY_ORDER_DELAY_QUEUE = REDIS_KEY_PREFIX + "order:delay:queue:";
    //自动发货队列
    public static final String REDIS_KEY_ORDER_DELAY_QUEUE_DELIVERY = REDIS_KEY_PREFIX + "order:delay:queue:delivery:";

    public static final Long REDIS_KEY_EXPIRES_ONE_MIN = 60L;

    public static final Long REDIS_KEY_EXPIRES_DAY = REDIS_KEY_EXPIRES_ONE_MIN * 60 * 24;

    public static final String TOKEN_ADMIN = "adminToken";

    public static final String TOKEN_WEB = "token";

    public static final String FILE_FOLDER_FILE = "file/";

    public static final String IMAGE_THUMBNAIL_SUFFIX = "_thumbnail";

    public static final String CART_PAY_NAME = "购物车支付-%d件商品";
}
