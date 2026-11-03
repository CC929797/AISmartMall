package com.easymall.security;

import io.netty.util.NetUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 默认使用连接对端；只有显式列出的代理才能提供转发地址，且从右向左验证信任链。
 */
@Component
public class ClientIpResolver {
    private final Set<String> trusted;

    public ClientIpResolver(@Value("${authentication.trusted-proxies:}") String trustedProxies) {
        trusted = Arrays.stream(trustedProxies.split(",")).map(String::trim)
                .filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet());
        if (trusted.stream().anyMatch(value -> !valid(value))) {
            throw new IllegalArgumentException("受信代理必须配置为明确的 IP 地址");
        }
    }

    /**
     * 从连接对端和转发链中解析出客户端地址。
     * @param peer 连接对端地址
     * @param forwardedFor 转发链
     * @return 客户端地址
     */
    public String resolve(String peer, String forwardedFor) {
        if (!valid(peer)) {
            return "unknown";
        }
        if (!trusted.contains(peer) || forwardedFor == null || forwardedFor.length() > 1024) {
            return peer;
        }
        String[] chain = forwardedFor.split(",", -1);
        if (chain.length > 16) {
            return peer;
        }
        String current = peer;
        for (int index = chain.length - 1; index >= 0 && trusted.contains(current); index--) {
            String candidate = chain[index].trim();
            if (!valid(candidate)) {
                return peer;
            }
            current = candidate;
        }
        return current;
    }

    /**
     * 验证地址是否为明确的 IP 地址。
     * @param address 待验证的地址
     * @return 如果地址为明确的 IPv4 或 IPv6 地址，则返回 true
     */
    private boolean valid(String address) {
        return address != null && (NetUtil.isValidIpV4Address(address) || NetUtil.isValidIpV6Address(address));
    }
}
