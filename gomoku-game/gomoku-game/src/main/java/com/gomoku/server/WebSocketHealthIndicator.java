package com.gomoku.server;

import org.springframework.boot.actuate.health.*;
import org.springframework.stereotype.Component;

@Component("webSocket")
public class WebSocketHealthIndicator implements HealthIndicator {
    private final NettyWebSocketServer server;
    public WebSocketHealthIndicator(NettyWebSocketServer server) { this.server = server; }
    public Health health() { return server.isListening() ? Health.up().build() : Health.down().build(); }
}
