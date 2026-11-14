package com.gomoku.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.AttributeKey;
import io.netty.util.NetUtil;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

/**
 * 在 WebSocket 握手阶段确定真实客户端 IP。
 *
 * <p>HTTPS 部署时所有连接都来自 Caddy 的容器地址；若继续使用
 * {@code remoteAddress}，IP 连接数限制会把所有用户当成同一个客户端。
 * 只有显式开启受信代理模式时才读取 {@code X-Forwarded-For}，避免直连
 * 9001 端口的客户端通过伪造请求头绕过限流。</p>
 */
final class ClientAddressHandler extends ChannelInboundHandlerAdapter {

    static final AttributeKey<String> CLIENT_IP = AttributeKey.valueOf("gomoku.clientIp");
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final boolean trustForwardedFor;

    ClientAddressHandler(boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest) {
            String ip = resolveClientIp((HttpRequest) msg, ctx.channel().remoteAddress(), trustForwardedFor);
            if (ip != null) {
                ctx.channel().attr(CLIENT_IP).set(ip);
            }
        }
        ctx.fireChannelRead(msg);
    }

    static String resolveClientIp(HttpRequest request, SocketAddress remoteAddress, boolean trustForwardedFor) {
        if (trustForwardedFor) {
            String forwardedIp = lastValidForwardedFor(request.headers().getAll(X_FORWARDED_FOR));
            if (forwardedIp != null) {
                return forwardedIp;
            }
        }
        return directIp(remoteAddress);
    }

    /**
     * 取最右侧的合法地址。受信代理会把直连客户端追加到链尾，因此右侧比
     * 客户端可伪造的左侧值更可信；同时支持直接连接时不带代理链的常见格式。
     */
    static String lastValidForwardedFor(List<String> headerValues) {
        for (int valueIndex = headerValues.size() - 1; valueIndex >= 0; valueIndex--) {
            String value = headerValues.get(valueIndex);
            if (value == null || value.trim().isEmpty()) continue;
            String[] parts = value.split(",");
            for (int partIndex = parts.length - 1; partIndex >= 0; partIndex--) {
                String candidate = normalize(parts[partIndex]);
                if (NetUtil.isValidIpV4Address(candidate) || NetUtil.isValidIpV6Address(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static String directIp(SocketAddress remoteAddress) {
        if (!(remoteAddress instanceof InetSocketAddress)) {
            return null;
        }
        InetAddress address = ((InetSocketAddress) remoteAddress).getAddress();
        return address == null ? null : address.getHostAddress();
    }

    private static String normalize(String value) {
        String candidate = value.trim();
        if (candidate.length() >= 2 && candidate.startsWith("\"") && candidate.endsWith("\"")) {
            candidate = candidate.substring(1, candidate.length() - 1).trim();
        }
        if (candidate.length() >= 2 && candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        return candidate;
    }
}