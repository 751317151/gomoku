package com.gomoku.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpRequest;

/**
 * 将反向代理暴露的 WebSocket 路径（默认 /ws）改写为 Netty 协议处理器监听的根路径 /。
 *
 * <p>这样本地开发仍可直连 {@code ws://localhost:9001/}，而 HTTPS 反向代理可以把
 * {@code wss://example.com/ws} 转发到同一个 Netty 端口，无需同时注册两个握手处理器。</p>
 */
final class WebSocketPathNormalizer extends ChannelInboundHandlerAdapter {

    private final String webSocketPath;

    WebSocketPathNormalizer(String webSocketPath) {
        this.webSocketPath = normalizeConfiguredPath(webSocketPath);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest) {
            HttpRequest request = (HttpRequest) msg;
            request.setUri(rewrite(request.uri()));
        }
        ctx.fireChannelRead(msg);
    }

    String rewrite(String uri) {
        if (uri == null || uri.isEmpty()) {
            return uri;
        }

        int queryStart = uri.indexOf('?');
        String path = queryStart >= 0 ? uri.substring(0, queryStart) : uri;
        String query = queryStart >= 0 ? uri.substring(queryStart) : "";

        if (path.equals(webSocketPath) || path.equals(webSocketPath + "/")) {
            return "/" + query;
        }
        return uri;
    }

    private static String normalizeConfiguredPath(String path) {
        String normalized = path == null ? "" : path.trim();
        if (normalized.isEmpty()) {
            return "/ws";
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
