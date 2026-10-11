package com.easymall.websocket.netty;

import com.easymall.commonent.RedisComponent;
import com.easymall.entity.dto.TokenUserInfoDTO;
import com.easymall.websocket.ChannelContextUtils;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.CharsetUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 处理Token验证的处理器
 */
@ChannelHandler.Sharable
@Component
@Slf4j
public class HandlerTokenValidation extends SimpleChannelInboundHandler<FullHttpRequest> {
    @Resource
    private RedisComponent redisComponent;
    @Resource
    private ChannelContextUtils channelContextUtils;

    /**
     * 处理接收到的HTTP请求
     * @param ctx 通道处理器上下文
     * @param request 完整的HTTP请求
     * @throws Exception 异常
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        String uri = request.getUri();
        QueryStringDecoder queryStringDecoder = new QueryStringDecoder(uri);
        List<String> tokens = queryStringDecoder.parameters().get("token");
        if (tokens == null) {
            sendErrorResponse(ctx);
            return;
        }
        String token = tokens.get(0);
        TokenUserInfoDTO tokenUserInfoDTO = redisComponent.getTokenInfo(token);
        if (tokenUserInfoDTO == null) {
            log.error("token： {}无效", token);
            sendErrorResponse(ctx);
            return;
        }
        log.info("token校验成功");
        ctx.fireChannelRead(request.retain());

        channelContextUtils.addContext(tokenUserInfoDTO.getUserId(), ctx.channel());

    }

    private void sendErrorResponse(ChannelHandlerContext ctx){
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN, Unpooled.copiedBuffer("token无效", CharsetUtil.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
