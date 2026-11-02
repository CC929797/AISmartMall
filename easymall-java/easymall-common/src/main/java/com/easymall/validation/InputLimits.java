package com.easymall.validation;

/** 请求与业务层共用的规模限制，避免不同入口采用不同边界。 */
public final class InputLimits {
    public static final int MAX_PAGE_NO = 100_000;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_ORDER_ITEMS = 50;
    public static final int MAX_BUY_COUNT = 99;
    public static final int MAX_MESSAGE_LENGTH = 1000;
    public static final int MAX_COMMENT_LENGTH = 300;
    public static final int MAX_COMMENT_IMAGES = 5;

    private InputLimits() { }
}
