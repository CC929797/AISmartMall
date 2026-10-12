package com.easymall.websocket.message;

import com.easymall.entity.dto.MessageSendDTO;
import com.easymall.utils.JsonUtils;
import com.easymall.websocket.ChannelContextUtils;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

@Component("messageHandler4Redis")
@Slf4j
public class MessageHandler4Redis  implements MessageHandler {
    @Resource
    private RedissonClient redissonClient;

    @Resource
    private ChannelContextUtils channelContextUtils;

    private static final String MESSAGE_TOPIC = "message.topic";

    /**
     * 监听消息
     */
    @Override
    public void listenMessage() {
        RTopic rTopic = redissonClient.getTopic(MESSAGE_TOPIC);
        rTopic.addListener(MessageSendDTO.class, (channel, message) -> {
            log.info("收到消息: {}", JsonUtils.convertObj2Json(message));
            channelContextUtils.sendMessage(message);
        });

    }

    /**
     * 发送消息
     * @param messageSendDTO 消息
     */
    @Override
    public void sendMessage(MessageSendDTO messageSendDTO) {
        //TODO: 消息取消，不发送消息
        RTopic rTopic = redissonClient.getTopic(MESSAGE_TOPIC);
        rTopic.publish(messageSendDTO);
    }

    @PreDestroy
    public void aVoid(){
        redissonClient.shutdown();
    }
}
