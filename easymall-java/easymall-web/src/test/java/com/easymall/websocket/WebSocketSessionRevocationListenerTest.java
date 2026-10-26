package com.easymall.websocket;

import com.easymall.security.SessionRevokedEvent;
import com.easymall.utils.JsonUtils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.redisson.client.codec.StringCodec;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebSocketSessionRevocationListenerTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void crossProcessRevocationsUseStringCodecAndRemoveOnlyTheirOwnListener() {
        RedissonClient redisson = mock(RedissonClient.class);
        RTopic topic = mock(RTopic.class);
        ChannelContextUtils contexts = mock(ChannelContextUtils.class);
        when(redisson.getTopic(SessionRevokedEvent.TOPIC, StringCodec.INSTANCE)).thenReturn(topic);
        when(topic.addListener(eq(String.class), any(MessageListener.class))).thenReturn(17);
        WebSocketSessionRevocationListener listener = new WebSocketSessionRevocationListener();
        ReflectionTestUtils.setField(listener, "redissonClient", redisson);
        ReflectionTestUtils.setField(listener, "channelContextUtils", contexts);

        listener.listen();

        ArgumentCaptor<MessageListener> callback = ArgumentCaptor.forClass(MessageListener.class);
        verify(topic).addListener(eq(String.class), callback.capture());
        assertDoesNotThrow(() -> callback.getValue().onMessage(SessionRevokedEvent.TOPIC, "@synthetic-invalid-event"));
        verifyNoInteractions(contexts);
        callback.getValue().onMessage(SessionRevokedEvent.TOPIC,
                JsonUtils.convertObj2Json(new SessionRevokedEvent("WS-A", "synthetic-old-token")));
        callback.getValue().onMessage(SessionRevokedEvent.TOPIC,
                JsonUtils.convertObj2Json(new SessionRevokedEvent("WS-B", null)));
        verify(contexts).revokeSessions("WS-A", "synthetic-old-token");
        verify(contexts).revokeSessions("WS-B", null);

        listener.stop();

        verify(topic).removeListener(17);
        verify(redisson, never()).shutdown();
    }
}
