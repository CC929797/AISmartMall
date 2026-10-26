package com.easymall.websocket.netty;

import com.easymall.entity.dto.MessageSendDTO;
import com.easymall.security.CurrentUser;
import com.easymall.security.UserAuthenticationService;
import com.easymall.websocket.ChannelContextUtils;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 使用真实本机网络和协议编解码器，验证前端 WebSocket 地址兼容性与撤销后的推送边界。 */
class WebSocketEndpointCompatibilityTest {
    private static final String TOKEN = "synthetic-compatibility-token";
    private static final String USER_ID = "WS-COMPAT";
    private static final String REPLY = "synthetic-compatibility-reply";

    private final Queue<Channel> connections = new ConcurrentLinkedQueue<>();
    private final Queue<WebSocket> clients = new ConcurrentLinkedQueue<>();
    private final CompletableFuture<Boolean> handshakeCompleted = new CompletableFuture<>();
    private EventLoopGroup acceptors;
    private EventLoopGroup workers;
    private Channel server;
    private HttpClient client;
    private int port;
    private UserAuthenticationService authentication;
    private ChannelContextUtils contexts;

    @BeforeEach
    void startLoopbackTransport() throws InterruptedException {
        authentication = mock(UserAuthenticationService.class);
        when(authentication.resolve(TOKEN)).thenReturn(
                new CurrentUser(USER_ID, "合成传输测试用户", null, CurrentUser.Role.USER));
        contexts = new ChannelContextUtils();
        ReflectionTestUtils.setField(contexts, "userAuthenticationService", authentication);
        HandlerTokenValidation validation = new HandlerTokenValidation();
        ReflectionTestUtils.setField(validation, "channelContextUtils", contexts);
        HandlerWebSocket handler = new HandlerWebSocket();
        ReflectionTestUtils.setField(handler, "channelContextUtils", contexts);

        // 只监听本机随机端口，不连接数据库、Redis、大模型或支付渠道。
        acceptors = new NioEventLoopGroup(1);
        workers = new NioEventLoopGroup(1);
        server = new ServerBootstrap().group(acceptors, workers)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        connections.add(channel);
                        channel.pipeline().addLast(new HttpServerCodec());
                        channel.pipeline().addLast(new HttpObjectAggregator(65536));
                        channel.pipeline().addLast(validation);
                        // 与实际服务保持相同配置，端点范围由前置认证处理器精确限制。
                        channel.pipeline().addLast(new WebSocketServerProtocolHandler(
                                "/ws", null, true, 65536, true, true, 10000L));
                        channel.pipeline().addLast(handler);
                        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
                                // 真实业务处理器标记升级完成后，测试线程才允许发送私有帧。
                                if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                                    handshakeCompleted.complete(true);
                                }
                                ctx.fireUserEventTriggered(event);
                            }
                        });
                    }
                }).bind("127.0.0.1", 0).sync().channel();
        port = ((InetSocketAddress) server.localAddress()).getPort();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterEach
    void stopLoopbackTransport() throws InterruptedException {
        boolean clientStopped = true;
        try {
            for (WebSocket socket : clients) {
                socket.abort();
            }
            if (client != null) {
                client.shutdownNow();
                clientStopped = client.awaitTermination(Duration.ofSeconds(5));
            }
        } finally {
            // 即使客户端退出超时或断言失败，也必须回收服务端连接和事件循环。
            if (contexts != null) {
                contexts.closeConnections();
            }
            for (Channel channel : connections) {
                channel.close().syncUninterruptibly();
            }
            if (server != null) {
                server.close().syncUninterruptibly();
            }
            if (workers != null) {
                workers.shutdownGracefully(0, 3, TimeUnit.SECONDS).syncUninterruptibly();
            }
            if (acceptors != null) {
                acceptors.shutdownGracefully(0, 3, TimeUnit.SECONDS).syncUninterruptibly();
            }
        }
        assertTrue(clientStopped, "HTTP 客户端必须释放传输线程");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/ws", "/ws/"})
    void configuredEndpointsUpgradeDeliverPrivateReplyAndCloseAfterRevocation(String path) {
        SocketListener listener = new SocketListener();
        try {
            WebSocket socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3))
                    .buildAsync(uri(path + "?token=" + TOKEN), listener).get(5, TimeUnit.SECONDS);
            clients.add(socket);
            assertTrue(handshakeCompleted.get(5, TimeUnit.SECONDS), "服务端必须完成真实协议升级");

            contexts.sendMessage(reply(REPLY));
            String delivered = listener.messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(delivered, "认证连接必须实际收到私有回复");
            assertTrue(delivered.contains(REPLY), "收到的帧必须包含合成回复");

            when(authentication.resolve(TOKEN)).thenReturn(null);
            contexts.sendMessage(reply("synthetic-revoked-reply"));
            assertTrue(listener.closed.get(5, TimeUnit.SECONDS), "撤销会话后必须关闭真实连接");
            assertTrue(listener.messages.isEmpty(), "撤销后不能继续投递私有回复");
        } catch (Exception e) {
            // 连接异常可能带有完整地址，不输出异常原因或包含 Token 的 URI。
            fail("真实 WebSocket 兼容性验证失败，异常类型：" + e.getClass().getSimpleName());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/ws/?token=unknown", "/ws/other?token=synthetic-compatibility-token"})
    void invalidSessionAndUnlistedPathAreRejectedBeforeUpgrade(String path) {
        try {
            WebSocket socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3))
                    .buildAsync(uri(path), new SocketListener()).get(5, TimeUnit.SECONDS);
            clients.add(socket);
            fail("无效会话或未列入白名单的路径不能升级为 WebSocket");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof WebSocketHandshakeException,
                    "必须在 HTTP 握手阶段拒绝请求");
            assertEquals(403, ((WebSocketHandshakeException) e.getCause()).getResponse().statusCode());
        } catch (Exception e) {
            fail("拒绝握手验证失败，异常类型：" + e.getClass().getSimpleName());
        }
    }

    private URI uri(String path) {
        return URI.create("ws://127.0.0.1:" + port + path);
    }

    private static MessageSendDTO<String> reply(String text) {
        MessageSendDTO<String> reply = new MessageSendDTO<>();
        reply.setUserId(USER_ID);
        reply.setAssistantMessage(text);
        return reply;
    }

    private static final class SocketListener implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final CompletableFuture<Boolean> closed = new CompletableFuture<>();
        private final StringBuilder fragments = new StringBuilder();

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) {
                messages.add(fragments.toString());
                fragments.setLength(0);
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            closed.complete(true);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            // 服务端可能直接断开 TCP；只记录关闭状态，不保留可能包含 URI 的异常。
            closed.complete(true);
        }
    }
}
