package com.easymall.security;

/** 内部会话撤销事件：token 为 null 时撤销该用户的全部连接；事件内容不得写入日志。 */
public record SessionRevokedEvent(String userId, String token) {
    public static final String TOPIC = "easymall.auth.session-revoked";

    @Override
    public String toString() {
        return "SessionRevokedEvent[userId=" + userId + ", token=<redacted>]";
    }
}
