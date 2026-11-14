package com.gomoku.server;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class WebSocketPathNormalizerTest {

    @Test
    void rewritesProxyPathAndPreservesQueryString() {
        WebSocketPathNormalizer normalizer = new WebSocketPathNormalizer("/ws");

        assertEquals("/", normalizer.rewrite("/ws"));
        assertEquals("/", normalizer.rewrite("/ws/"));
        assertEquals("/?token=abc", normalizer.rewrite("/ws?token=abc"));
        assertEquals("/?token=abc", normalizer.rewrite("/ws/?token=abc"));
    }

    @Test
    void leavesDirectRootAndUnrelatedRequestsUntouched() {
        WebSocketPathNormalizer normalizer = new WebSocketPathNormalizer("ws/");

        assertEquals("/", normalizer.rewrite("/"));
        assertEquals("/api/health", normalizer.rewrite("/api/health"));
        assertEquals("/ws-extra", normalizer.rewrite("/ws-extra"));
    }

    @Test
    void handlerMutatesHttpRequestBeforeNextHandler() {
        EmbeddedChannel channel = new EmbeddedChannel(new WebSocketPathNormalizer("/ws"));
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/ws?room=ABC123");

        channel.writeInbound(request);

        FullHttpRequest forwarded = channel.readInbound();
        assertSame(request, forwarded);
        assertEquals("/?room=ABC123", forwarded.uri());
        forwarded.release();
        channel.finishAndReleaseAll();
    }
}
