package com.easymall.websocket;

import com.easymall.security.SessionRevokedEvent;
import com.easymall.utils.JsonUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

/** 监听用户端和管理端进程发出的退出登录、Token 轮换和封禁事件。 */
@Component
@Slf4j
public class WebSocketSessionRevocationListener {

    @Resource
    private RedissonClient redissonClient;
    @Resource
    private ChannelContextUtils channelContextUtils;

    private RTopic topic;
    private int listenerId;

    /**
     * 监听用户端和管理端进程发出的退出登录、Token 轮换和封禁事件。
     */
    @PostConstruct
    public void listen() {
        topic = redissonClient.getTopic(SessionRevokedEvent.TOPIC, StringCodec.INSTANCE);
        listenerId = topic.addListener(String.class, (channel, json) -> {
            try {
                // 解析 JSON 字符串为 SessionRevokedEvent 对象
                SessionRevokedEvent event = JsonUtils.convertJson2Obj(json, SessionRevokedEvent.class);
                if (event != null) {
                    // 撤销用户会话
                    channelContextUtils.revokeSessions(event.userId(), event.token());
                }
            } catch (RuntimeException e) {
                log.warn("WebSocket 撤销/断开事件被拒绝处理: {}", e.getClass().getSimpleName());
            }
        });
    }

    @PreDestroy
    public void stop() {
        if (topic != null && !redissonClient.isShutdown()) {
            topic.removeListener(listenerId);
        }
    }
}
