package com.gomoku.server;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClientAddressHandlerTest {

    @Test
    void trustedProxyUsesRightmostValidForwardedAddress() {
        FullHttpRequest request = requestWithForwardedFor("198.51.100.9, 203.0.113.7, unknown");

        assertEquals("203.0.113.7",
                ClientAddressHandler.resolveClientIp(request, null, true));
        request.release();
    }

    @Test
    void forwardedForIsIgnoredForDirectConnections() {
        FullHttpRequest request = requestWithForwardedFor("203.0.113.7");
        InetSocketAddress remote = new InetSocketAddress("192.0.2.44", 54321);

        assertEquals("192.0.2.44",
                ClientAddressHandler.resolveClientIp(request, remote, false));
        request.release();
    }

    @Test
    void invalidForwardedChainFallsBackToDirectPeer() {
        FullHttpRequest request = requestWithForwardedFor("spoofed, not-an-ip");
        InetSocketAddress remote = new InetSocketAddress("192.0.2.55", 54321);

        assertEquals("192.0.2.55",
                ClientAddressHandler.resolveClientIp(request, remote, true));
        request.release();
    }

    @Test
    void supportsMultipleHeadersIpv6AndQuotedValues() {
        assertEquals("2001:db8::10", ClientAddressHandler.lastValidForwardedFor(Arrays.asList(
                "unknown", "\"198.51.100.8\"", "[2001:db8::10]")));
        assertNull(ClientAddressHandler.lastValidForwardedFor(Arrays.asList("unknown", "")));
    }

    @Test
    void capturesIpBeforeHandshakeCompletes() {
        EmbeddedChannel channel = new EmbeddedChannel(new ClientAddressHandler(true));
        FullHttpRequest request = requestWithForwardedFor("203.0.113.90");

        channel.writeInbound(request);

        assertEquals("203.0.113.90", channel.attr(ClientAddressHandler.CLIENT_IP).get());
        FullHttpRequest forwarded = channel.readInbound();
        forwarded.release();
        channel.finishAndReleaseAll();
    }

    private static FullHttpRequest requestWithForwardedFor(String value) {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/ws");
        request.headers().set("X-Forwarded-For", value);
        return request;
    }
}