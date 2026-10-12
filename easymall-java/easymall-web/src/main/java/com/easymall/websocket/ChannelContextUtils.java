package com.easymall.websocket;


import com.alibaba.fastjson2.JSON;
import com.easymall.entity.dto.MessageSendDTO;
import com.easymall.utils.JsonUtils;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component("channelContextUtils")
public class ChannelContextUtils {
    private static final ConcurrentHashMap<String, Channel> USER_CONTEXT_MAP = new ConcurrentHashMap<>();

    /**
     * 发送消息
     * @param messageSendDto 消息dto
     */
    public void sendMessage(MessageSendDTO messageSendDto) {
        Channel channel = USER_CONTEXT_MAP.get(messageSendDto.getUserId());
        if (channel == null || !channel.isActive()) {
            return;
        }
        channel.writeAndFlush(new TextWebSocketFrame(JsonUtils.convertObj2Json(messageSendDto)));
    }

    /**
     * 添加用户与channel的映射
     * @param userId 用户id
     * @param channel 通道
     */
    public void addContext(String userId, Channel channel) {
        try {
            String channelId = channel.id().toString();
            AttributeKey attributeKey = null;
            if (!AttributeKey.exists(channelId)) {
                attributeKey = AttributeKey.newInstance(channelId);
            }else {
                attributeKey = AttributeKey.valueOf(channelId);
            }
            channel.attr(attributeKey).set(userId);
            USER_CONTEXT_MAP.put(userId, channel);
        }catch (Exception e){

        }
    }
}
