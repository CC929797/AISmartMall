package com.easymall.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.regex.Pattern;

/**
 * MCP 模块仅供受控服务调用，所有请求在进入协议处理前校验凭据。
 */
@Component
public class McpServiceAuthenticationFilter implements WebFilter {
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{32,128}");
    private static final String BEARER_PREFIX = "Bearer ";
    private final byte[] expectedToken;

    public McpServiceAuthenticationFilter(@Value("${MCP_SERVICE_TOKEN:}") String configuredToken) {
        expectedToken = TOKEN_FORMAT.matcher(configuredToken).matches()
                ? configuredToken.getBytes(StandardCharsets.US_ASCII) : null;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        // 本阶段仅开放服务调用，拒绝带浏览器来源的请求。
        if (headers.containsKey(HttpHeaders.ORIGIN)) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }

        List<String> authorization = headers.getOrEmpty(HttpHeaders.AUTHORIZATION);
        if (authorization.size() != 1 || !matchesToken(authorization.getFirst())) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"easymall-mcp\"");
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    /**
     * 校验请求头中的凭据是否匹配。
     */
    private boolean matchesToken(String header) {
        if (expectedToken == null || header.length() > BEARER_PREFIX.length() + 128
                || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return false;
        }
        String supplied = header.substring(BEARER_PREFIX.length());
        return TOKEN_FORMAT.matcher(supplied).matches()
                && MessageDigest.isEqual(expectedToken, supplied.getBytes(StandardCharsets.US_ASCII));
    }
}
