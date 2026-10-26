package com.easymall.websocket;


import com.easymall.entity.dto.MessageSendDTO;
import com.easymall.security.CurrentUser;
import com.easymall.security.UserAuthenticationService;
import com.easymall.utils.JsonUtils;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.AttributeKey;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Component("channelContextUtils")
public class ChannelContextUtils {
    private static final AttributeKey<WebSocketSession> SESSION = AttributeKey.valueOf("easymall.authenticated-websocket-session");
    private static final AttributeKey<Boolean> HANDSHAKE_COMPLETE = AttributeKey.valueOf("easymall.websocket-handshake-complete");

    // 同一用户可以同时打开多个标签页；关闭旧标签页时不能移除新连接。
    private final ConcurrentMap<String, Set<Channel>> channelsByUser = new ConcurrentHashMap<>();

    @Resource
    private UserAuthenticationService userAuthenticationService;

    /**
     * 发送消息
     * @param messageSendDto 消息dto
     */
    public void sendMessage(MessageSendDTO messageSendDto) {
        if (messageSendDto == null || messageSendDto.getUserId() == null) {
            return;
        }
        Set<Channel> channels = channelsByUser.get(messageSendDto.getUserId());
        if (channels == null) {
            return;
        }
        for (Channel channel : channels) {
            if (isAuthenticated(channel) && Boolean.TRUE.equals(channel.attr(HANDSHAKE_COMPLETE).get())) {
                channel.writeAndFlush(new TextWebSocketFrame(JsonUtils.convertObj2Json(messageSendDto)));
            }
        }
    }

    /**
     * 用户 ID 不能作为身份凭据。保留此入口，仅用于拒绝尚未经过认证的旧调用方式。
     */
    @Deprecated
    public void addContext(String userId, Channel channel) {
        channel.close();
    }

    /** 握手时统一解析身份，并绑定仅供服务端使用的不可变会话快照。 */
    public boolean addAuthenticatedContext(String token, Channel channel) {
        CurrentUser user = resolve(token);
        if (!channel.isActive() || user == null || user.role() != CurrentUser.Role.USER) {
            return false;
        }
        WebSocketSession session = new WebSocketSession(user, token);
        if (channel.attr(SESSION).setIfAbsent(session) != null) {
            channel.close();
            return false;
        }
        channelsByUser.compute(user.userId(), (userId, channels) -> {
            Set<Channel> result = channels == null ? ConcurrentHashMap.newKeySet() : channels;
            result.add(channel);
            return result;
        });
        channel.closeFuture().addListener(future -> removeContext(channel));
        return channel.isActive();
    }

    /** 处理私有消息推送或客户端数据帧前，重新校验当前会话。 */
    public boolean isAuthenticated(Channel channel) {
        WebSocketSession session = channel.attr(SESSION).get();
        if (channel.isActive() && session != null) {
            CurrentUser current = resolve(session.token());
            if (current != null && current.role() == CurrentUser.Role.USER
                    && Objects.equals(current.userId(), session.user().userId())) {
                return true;
            }
        }
        removeContext(channel);
        channel.close();
        return false;
    }

    /** 仅在升级成功且 Netty 已安装 WebSocket 编码器后调用。 */
    public void completeHandshake(Channel channel) {
        if (isAuthenticated(channel)) {
            channel.attr(HANDSHAKE_COMPLETE).set(true);
        }
    }

    /**
     * 移除会话连接
     * @param channel
     */
    public void removeContext(Channel channel) {
        WebSocketSession session = channel.attr(SESSION).getAndSet(null);
        channel.attr(HANDSHAKE_COMPLETE).set(null);
        if (session == null) {
            return;
        }
        channelsByUser.computeIfPresent(session.user().userId(), (userId, channels) -> {
            channels.remove(channel);
            return channels.isEmpty() ? null : channels;
        });
    }

    /** Token 为 null 时撤销该用户的全部会话，例如管理员封禁用户时。 */
    public void revokeSessions(String userId, String token) {
        if (userId == null) {
            return;
        }
        Set<Channel> channels = channelsByUser.get(userId);
        if (channels == null) {
            return;
        }
        for (Channel channel : new ArrayList<>(channels)) {
            WebSocketSession session = channel.attr(SESSION).get();
            if (session != null && (token == null || Objects.equals(token, session.token()))) {
                removeContext(channel);
                channel.close();
            }
        }
    }

    // 发布/订阅用于及时通知撤销，会话存储才是校验依据；定期检查也会关闭过期的空闲连接。
    @Scheduled(fixedDelay = 15_000)
    public void validateSessions() {
        for (Set<Channel> channels : channelsByUser.values()) {
            for (Channel channel : channels) {
                isAuthenticated(channel);
            }
        }
    }

    @PreDestroy
    public void closeConnections() {
        for (String userId : new ArrayList<>(channelsByUser.keySet())) {
            revokeSessions(userId, null);
        }
    }

    private CurrentUser resolve(String token) {
        try {
            return userAuthenticationService.resolve(token);
        } catch (RuntimeException e) {
            // 依赖服务异常时必须关闭连接；异常消息可能包含凭据，因此不记录异常原文。
            log.warn("WebSocket 认证不可用：{}", e.getClass().getSimpleName());
            return null;
        }
    }

    private record WebSocketSession(CurrentUser user, String token) {
        @Override
        public String toString() {
            return "WebSocketSession[userId=" + user.userId() + "]";
        }
    }
}
