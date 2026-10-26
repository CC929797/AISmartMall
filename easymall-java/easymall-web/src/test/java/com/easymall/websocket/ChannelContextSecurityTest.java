package com.easymall.websocket;

import com.easymall.entity.dto.MessageSendDTO;
import com.easymall.security.CurrentUser;
import com.easymall.security.UserAuthenticationService;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChannelContextSecurityTest {
    private static final CurrentUser USER_A = new CurrentUser("WS-A", "synthetic-a", null, CurrentUser.Role.USER);
    private static final CurrentUser USER_B = new CurrentUser("WS-B", "synthetic-b", null, CurrentUser.Role.USER);

    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private ChannelContextUtils contexts;
    private UserAuthenticationService authentication;

    @BeforeEach
    void setUp() {
        contexts = new ChannelContextUtils();
        authentication = mock(UserAuthenticationService.class);
        ReflectionTestUtils.setField(contexts, "userAuthenticationService", authentication);
        when(authentication.resolve("synthetic-a-token")).thenReturn(USER_A);
        when(authentication.resolve("synthetic-a-new-token")).thenReturn(USER_A);
        when(authentication.resolve("synthetic-b-token")).thenReturn(USER_B);
    }

    @AfterEach
    void tearDown() {
        contexts.closeConnections();
        channels.forEach(EmbeddedChannel::finishAndReleaseAll);
    }

    @Test
    void authenticatedPushGoesOnlyToTheVerifiedOwner() {
        EmbeddedChannel a = channel();
        EmbeddedChannel b = channel();
        assertTrue(bind("synthetic-a-token", a));
        assertTrue(bind("synthetic-b-token", b));

        contexts.sendMessage(message("WS-A"));

        assertPrivateFrame(a);
        assertNull(b.readOutbound());
    }

    @Test
    void userTabsCoexistAndClosingOneDoesNotDetachTheOther() {
        EmbeddedChannel oldTab = channel();
        EmbeddedChannel newTab = channel();
        assertTrue(bind("synthetic-a-token", oldTab));
        assertTrue(bind("synthetic-a-token", newTab));

        oldTab.close();
        contexts.sendMessage(message("WS-A"));

        assertPrivateFrame(newTab);
        assertNull(oldTab.readOutbound());
    }

    @Test
    void expiringSessionCannotReceiveAnotherPrivateFrame() {
        EmbeddedChannel a = channel();
        assertTrue(bind("synthetic-a-token", a));
        when(authentication.resolve("synthetic-a-token")).thenReturn(null);

        contexts.sendMessage(message("WS-A"));

        assertFalse(a.isActive());
        assertNull(a.readOutbound());
    }

    @Test
    void dependencyFailureClosesSocketInsteadOfDeliveringPrivateData() {
        EmbeddedChannel a = channel();
        assertTrue(bind("synthetic-a-token", a));
        when(authentication.resolve("synthetic-a-token")).thenThrow(new IllegalStateException("synthetic dependency failure"));

        contexts.sendMessage(message("WS-A"));

        assertFalse(a.isActive());
        assertNull(a.readOutbound());
    }

    @Test
    void changedOwnerCannotReuseTheBoundSession() {
        EmbeddedChannel a = channel();
        assertTrue(bind("synthetic-a-token", a));
        when(authentication.resolve("synthetic-a-token")).thenReturn(USER_B);

        contexts.sendMessage(message("WS-A"));

        assertFalse(a.isActive());
        assertNull(a.readOutbound());
    }

    @Test
    void scheduledValidationExpiresIdleSessionWithoutClientTraffic() {
        EmbeddedChannel a = channel();
        assertTrue(bind("synthetic-a-token", a));
        when(authentication.resolve("synthetic-a-token")).thenReturn(null);

        contexts.validateSessions();

        assertFalse(a.isActive());
        clearInvocations(authentication);
        contexts.validateSessions();
        verifyNoInteractions(authentication);
    }

    @Test
    void revokingOldTokenDoesNotDisconnectNewSessionOrAnotherUser() {
        EmbeddedChannel oldSession = channel();
        EmbeddedChannel newSession = channel();
        EmbeddedChannel b = channel();
        assertTrue(bind("synthetic-a-token", oldSession));
        assertTrue(bind("synthetic-a-new-token", newSession));
        assertTrue(bind("synthetic-b-token", b));

        contexts.revokeSessions("WS-A", "synthetic-a-token");

        assertFalse(oldSession.isActive());
        assertTrue(newSession.isActive());
        assertTrue(b.isActive());
        contexts.sendMessage(message("WS-A"));
        assertPrivateFrame(newSession);
    }

    @Test
    void banRevocationDisconnectsAllUserSessionsAndLeavesOtherUsers() {
        EmbeddedChannel a1 = channel();
        EmbeddedChannel a2 = channel();
        EmbeddedChannel b = channel();
        assertTrue(bind("synthetic-a-token", a1));
        assertTrue(bind("synthetic-a-new-token", a2));
        assertTrue(bind("synthetic-b-token", b));

        contexts.revokeSessions("WS-A", null);

        assertFalse(a1.isActive());
        assertFalse(a2.isActive());
        assertTrue(b.isActive());
    }

    @Test
    void deniedOrAdminSessionCannotBeBoundForUserPush() {
        EmbeddedChannel missing = channel();
        EmbeddedChannel admin = channel();
        when(authentication.resolve("synthetic-admin-token"))
                .thenReturn(new CurrentUser("admin", "admin", null, CurrentUser.Role.ADMIN));

        assertFalse(bind("synthetic-missing-token", missing));
        assertFalse(bind("synthetic-admin-token", admin));
        assertFalse(contexts.isAuthenticated(missing));
        assertFalse(contexts.isAuthenticated(admin));
    }

    @Test
    void contextInstancesDoNotSharePrivateChannels() {
        EmbeddedChannel a = channel();
        assertTrue(bind("synthetic-a-token", a));

        new ChannelContextUtils().sendMessage(message("WS-A"));

        assertNull(a.readOutbound());
    }

    private boolean bind(String token, EmbeddedChannel channel) {
        boolean accepted = contexts.addAuthenticatedContext(token, channel);
        if (accepted) {
            contexts.completeHandshake(channel);
        }
        return accepted;
    }

    private EmbeddedChannel channel(ChannelHandler... handlers) {
        EmbeddedChannel channel = new EmbeddedChannel(handlers);
        channels.add(channel);
        return channel;
    }

    private static MessageSendDTO<?> message(String userId) {
        MessageSendDTO<?> message = new MessageSendDTO<>();
        message.setUserId(userId);
        message.setAssistantMessage("synthetic private reply");
        return message;
    }

    private static void assertPrivateFrame(EmbeddedChannel channel) {
        TextWebSocketFrame frame = channel.readOutbound();
        assertNotNull(frame);
        try {
            assertTrue(frame.text().contains("synthetic private reply"));
        } finally {
            frame.release();
        }
    }
}
