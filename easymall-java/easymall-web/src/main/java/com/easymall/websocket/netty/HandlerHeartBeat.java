package com.easymall.websocket.netty;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HandlerHeartBeat extends ChannelDuplexHandler {

    /**
     * 处理心跳检测
     * @param ctx 通道上下文
     * @param evt 事件
     * @throws Exception 异常
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent e = (IdleStateEvent) evt;
            if (e.state() == IdleState.READER_IDLE) {
                log.debug("WebSocket 读端空闲，正在关闭连接");
                ctx.close();
            }
        } else {
            ctx.fireUserEventTriggered(evt);
        }
    }
}
