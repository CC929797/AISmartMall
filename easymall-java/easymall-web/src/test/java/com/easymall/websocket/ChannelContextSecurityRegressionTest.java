package com.easymall.websocket;

import com.easymall.entity.dto.MessageSendDTO;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChannelContextSecurityRegressionTest {

    @Test
    void unverifiedUserIdBindingMustNeverEnablePrivatePush() {
        ChannelContextUtils contexts = new ChannelContextUtils();
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            contexts.addContext("SYNTHETIC-WS-UNVERIFIED", channel);
            MessageSendDTO<?> message = new MessageSendDTO<>();
            message.setUserId("SYNTHETIC-WS-UNVERIFIED");
            message.setAssistantMessage("synthetic private reply");

            contexts.sendMessage(message);

            assertNull(channel.readOutbound(), "A user ID alone must not authorize a private push");
            assertFalse(channel.isActive(), "An unauthenticated connection must be closed");
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
