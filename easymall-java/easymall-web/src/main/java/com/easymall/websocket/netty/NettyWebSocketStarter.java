package com.easymall.websocket.netty;

import com.alipay.api.java_websocket.server.WebSocketServer;
import com.easymall.entity.config.AppConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerDomainSocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class NettyWebSocketStarter implements Runnable {
    @Resource
    private AppConfig appConfig;

    //boss线程，用于处理连接
    private EventLoopGroup boosGroup = new NioEventLoopGroup();

    //worker线程，用于处理业务
    private EventLoopGroup workGroup = new NioEventLoopGroup();

    @Resource
    private HandlerTokenValidation handlerTokenValidation;

    @Resource
    private HandlerWebSocket handlerWebSocket;

    @PreDestroy
    private void close(){
        boosGroup.shutdownGracefully();
        workGroup.shutdownGracefully();
    }

    //启动一个异步线程
    @Override
    public void run() {
        try {
            ServerBootstrap serverBootstrap = new ServerBootstrap();
            serverBootstrap.group(boosGroup, workGroup);
            serverBootstrap.channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.DEBUG))
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        public void initChannel(Channel channel) {
                            ChannelPipeline pipeline = channel.pipeline();
                            /**
                             * 对http协议的支持，使用http的编码器，解码器
                             */
                            pipeline.addLast(new HttpServerCodec());

                            pipeline.addLast(new HttpObjectAggregator(65536));
                            pipeline.addLast(new IdleStateHandler(60,0,0, TimeUnit.SECONDS));

                            pipeline.addLast(new HandlerHeartBeat());

                            pipeline.addLast(handlerTokenValidation);

                            pipeline.addLast(new WebSocketServerProtocolHandler("/ws", null, true,65536,true,true,10000L));

                            pipeline.addLast(handlerWebSocket);

                        }
                    });

            Channel channel = serverBootstrap.bind(appConfig.getWsPort()).sync().channel();
            log.info("netty服务启动成功，端口：{}", appConfig.getWsPort());
            channel.closeFuture().sync();
        }catch (Exception e){
            log.error("netty启动异常",e);
        }finally {
            boosGroup.shutdownGracefully();
            workGroup.shutdownGracefully();
        }
    }
}
