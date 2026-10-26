package com.easymall.websocket.netty;

import com.easymall.entity.dto.MessageSendDTO;
import com.easymall.security.CurrentUser;
import com.easymall.security.UserAuthenticationService;
import com.easymall.websocket.ChannelContextUtils;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketAuthenticationTest {
    private UserAuthenticationService authentication;
    private ChannelContextUtils contexts;
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        authentication = mock(UserAuthenticationService.class);
        contexts = new ChannelContextUtils();
        ReflectionTestUtils.setField(contexts, "userAuthenticationService", authentication);
    }

    @AfterEach
    void tearDown() {
        contexts.closeConnections();
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/ws", "/ws?token=", "/ws?token=one&token=two", "/ws-other?token=one",
            "/ws?token=unknown", "/ws/?token=unknown", "/ws/?token=", "/ws/?token=one&token=two",
            "/ws/other?token=one", "/ws//?token=one"})
    void malformedOrInvalidHandshakeIsForbidden(String uri) {
        HandlerTokenValidation validation = new HandlerTokenValidation();
        ReflectionTestUtils.setField(validation, "channelContextUtils", contexts);
        channel = new EmbeddedChannel(validation);

        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri));

        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        try {
            assertEquals(HttpResponseStatus.FORBIDDEN, response.status());
        } finally {
            response.release();
        }
        assertFalse(channel.isActive());
        assertNull(channel.readInbound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/ws?token=synthetic-token", "/ws/?token=synthetic-token"})
    void verifiedHandshakeContinuesToWebSocketProtocolHandler(String uri) {
        CurrentUser user = new CurrentUser("WS-OWNER", "synthetic", null, CurrentUser.Role.USER);
        when(authentication.resolve("synthetic-token")).thenReturn(user);
        HandlerTokenValidation validation = new HandlerTokenValidation();
        ReflectionTestUtils.setField(validation, "channelContextUtils", contexts);
        channel = new EmbeddedChannel(validation);
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);

        assertTrue(channel.writeInbound(request));

        FullHttpRequest forwarded = channel.readInbound();
        assertSame(request, forwarded);
        forwarded.release();
        assertTrue(channel.isActive());
        assertNull(channel.readOutbound());
        verify(authentication).resolve("synthetic-token");
    }

    @Test
    void heartbeatCannotKeepARevokedSessionAlive() {
        CurrentUser user = new CurrentUser("WS-OWNER", "synthetic", null, CurrentUser.Role.USER);
        when(authentication.resolve("synthetic-token")).thenReturn(user);
        HandlerWebSocket handler = new HandlerWebSocket();
        ReflectionTestUtils.setField(handler, "channelContextUtils", contexts);
        channel = new EmbeddedChannel(handler);
        assertTrue(contexts.addAuthenticatedContext("synthetic-token", channel));
        when(authentication.resolve("synthetic-token")).thenReturn(null);

        channel.writeInbound(new TextWebSocketFrame("ping"));

        assertFalse(channel.isActive());
        assertNull(channel.readOutbound());
    }

    @Test
    void validHeartbeatPreservesExistingNoResponseProtocol() {
        CurrentUser user = new CurrentUser("WS-OWNER", "synthetic", null, CurrentUser.Role.USER);
        when(authentication.resolve("synthetic-token")).thenReturn(user);
        HandlerWebSocket handler = new HandlerWebSocket();
        ReflectionTestUtils.setField(handler, "channelContextUtils", contexts);
        channel = new EmbeddedChannel(handler);
        assertTrue(contexts.addAuthenticatedContext("synthetic-token", channel));

        channel.writeInbound(new TextWebSocketFrame("ping"));

        assertTrue(channel.isActive());
        assertNull(channel.readOutbound());
        verify(authentication, times(2)).resolve("synthetic-token");
    }

    @Test
    void privatePushWaitsUntilTheHttpConnectionHasCompletedItsUpgrade() {
        CurrentUser user = new CurrentUser("WS-OWNER", "synthetic", null, CurrentUser.Role.USER);
        when(authentication.resolve("synthetic-token")).thenReturn(user);
        HandlerWebSocket handler = new HandlerWebSocket();
        ReflectionTestUtils.setField(handler, "channelContextUtils", contexts);
        channel = new EmbeddedChannel(handler);
        assertTrue(contexts.addAuthenticatedContext("synthetic-token", channel));
        MessageSendDTO<?> message = new MessageSendDTO<>();
        message.setUserId("WS-OWNER");
        message.setAssistantMessage("synthetic private reply");

        contexts.sendMessage(message);
        assertNull(channel.readOutbound(), "A pending HTTP upgrade cannot encode private WebSocket frames");

        channel.pipeline().fireUserEventTriggered(new WebSocketServerProtocolHandler.HandshakeComplete(
                "/ws", new DefaultHttpHeaders(), null));
        contexts.sendMessage(message);

        TextWebSocketFrame frame = channel.readOutbound();
        assertNotNull(frame);
        frame.release();
    }
}
