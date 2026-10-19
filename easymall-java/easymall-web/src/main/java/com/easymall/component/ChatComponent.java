package com.easymall.component;

import com.easymall.entity.dto.MessageSendDTO;
import com.easymall.entity.enums.ExecutorServiceSignletionEnum;
import com.easymall.entity.enums.MessageOutPutTypeEnum;
import com.easymall.entity.enums.UserIntentEnum;
import com.easymall.entity.po.AgentMessage;
import com.easymall.service.AgentMessageService;
import com.easymall.websocket.ChannelContextUtils;
import com.easymall.websocket.message.MessageHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息处理
 */
@RestController
@Slf4j
public class ChatComponent {
    @Resource
    private AgentMessageService agentMessageService;
    @Resource
    private MessageHandler messageHandler;
    @Resource
    private ChatClient chatClient;

    /**
     * 发送消息
     * @param userId
     * @param message
     * @return
     */
    public AgentMessage sendMessage(String userId,String message) {
        AgentMessage agentMessage = agentMessageService.saveMessage(userId, message);

        ExecutorServiceSignletionEnum.INSTANCE.getExecutorService().execute(() -> {
            assistantAnswer(agentMessage);
        });

        return agentMessage;
    }

    /**
     * AI回复
     * @param agentMessage 消息
     */
    public void assistantAnswer(AgentMessage agentMessage) {
        MessageSendDTO messageSendDTO = new MessageSendDTO();
        messageSendDTO.setUserId(agentMessage.getUserId());
        messageSendDTO.setOutPutType(MessageOutPutTypeEnum.DONE.getType());
        //获取到大模型的回复
        String content = chatClient.prompt().user(agentMessage.getUserMessage()).call().content();
        log.info("返回内容：{}", content);
        messageSendDTO.setAssistantMessage(content);
        messageSendDTO.setMessageId(agentMessage.getMessageId());
        messageSendDTO.setBizType(UserIntentEnum.CHAT.getKey());
        messageSendDTO.setUserMessage(agentMessage.getUserMessage());
        messageHandler.sendMessage(messageSendDTO);
    }
}
