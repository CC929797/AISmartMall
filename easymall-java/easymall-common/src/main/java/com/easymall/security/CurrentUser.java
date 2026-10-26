package com.easymall.security;


/**
 * 服务端为当前请求或任务解析出的不可变身份快照，不保留 Token、密码等凭据。
 **/
public record CurrentUser(String userId, String nickName, String avatar, Role role) {
    public enum Role {
        USER, ADMIN
    }
}
