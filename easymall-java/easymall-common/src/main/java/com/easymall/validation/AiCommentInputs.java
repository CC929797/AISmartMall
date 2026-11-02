package com.easymall.validation;

import com.easymall.entity.enums.ResponseCodeEnum;
import com.easymall.exception.BusinessException;

/** AI 与评价入口共用的业务输入规则，Controller 和 Service 使用相同边界。 */
public final class AiCommentInputs {
    private static final int MAX_USER_ID_LENGTH = 10;
    private static final int MAX_ORDER_ID_LENGTH = 32;
    private static final int MAX_IMAGE_PATHS_LENGTH = 300;

    private AiCommentInputs() { }

    public static void requireMessage(String userId, String message) {
        InputValidator.requireText(userId, MAX_USER_ID_LENGTH);
        InputValidator.requireText(message, InputLimits.MAX_MESSAGE_LENGTH);
    }

    public static void requireMessageId(String userId, Integer messageId) {
        InputValidator.requireText(userId, MAX_USER_ID_LENGTH);
        InputValidator.requireRange(messageId, 1, Integer.MAX_VALUE);
    }

    public static void requireComment(String userId, String orderId, String content, String images, Integer star) {
        requireReComment(userId, orderId, content, images);
        InputValidator.requireRange(star, 1, 5);
    }

    public static void requireReComment(String userId, String orderId, String content, String images) {
        InputValidator.requireText(userId, MAX_USER_ID_LENGTH);
        InputValidator.requireText(orderId, MAX_ORDER_ID_LENGTH);
        InputValidator.requireText(content, InputLimits.MAX_COMMENT_LENGTH);
        requireImages(images);
    }

    /** 保留前端逗号分隔图片路径的协议；未选择图片时允许 null 或空字符串。 */
    private static void requireImages(String images) {
        if (images == null || images.isEmpty()) { return; }
        if (images.length() > MAX_IMAGE_PATHS_LENGTH) { reject(); }
        String[] paths = images.split(",", -1);
        if (paths.length > InputLimits.MAX_COMMENT_IMAGES) { reject(); }
        for (String path : paths) {
            if (path.isBlank()) { reject(); }
        }
    }

    private static void reject() { throw new BusinessException(ResponseCodeEnum.CODE_600); }
}
