package com.gomoku.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * WebSocket 帧路由器：根据帧类型分发给对应的解码器
 *
 * - TextWebSocketFrame → 直接透传给 JSON 处理器（GameServerHandler）
 * - BinaryWebSocketFrame → 解码为 GameMessage 后透传
 *
 * 这个 Handler 替代了直接将 GameServerHandler 绑定到 TextWebSocketFrame 的做法，
 * 使同一个 Pipeline 能同时处理 JSON 和二进制两种协议。
 */
public class WebSocketFrameRouter extends MessageToMessageDecoder<WebSocketFrame> {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketFrameRouter.class);
    private final BinaryMessageDecoder binaryDecoder = new BinaryMessageDecoder();

    @Override
    protected void decode(ChannelHandlerContext ctx, WebSocketFrame frame, List<Object> out) throws Exception {
        if (frame instanceof TextWebSocketFrame) {
            // JSON 协议：直接透传 TextWebSocketFrame
            out.add(frame.retain());
        } else if (frame instanceof BinaryWebSocketFrame) {
            // 二进制协议：解码为 GameMessage
            ByteBuf content = frame.content();
            binaryDecoder.decode(ctx, content, out);
        }
        // 其他帧类型（Ping/Pong/Close）由 Netty 内部处理
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("WebSocketFrameRouter error: {}", cause.getMessage(), cause);
        ctx.close();
    }
}
