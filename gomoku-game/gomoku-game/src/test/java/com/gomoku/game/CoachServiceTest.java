package com.gomoku.game;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class CoachServiceTest {
    @Test void sendsOnlyVisibleStateToConfiguredEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> payload = new AtomicReference<>();
        server.createContext("/chat", exchange -> {
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("Bearer test-key", exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] reply = "{\"choices\":[{\"message\":{\"content\":\"先保留大牌，观察局势\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length); exchange.getResponseBody().write(reply); exchange.close();
        }); server.start();
        try {
            CoachService service = new CoachService(true, "http://127.0.0.1:" + server.getAddress().getPort() + "/chat", "test-key", "test-model");
            HighCardEngine engine = new HighCardEngine(); engine.applyMove(MoveCommand.action(9), 2);
            assertEquals("先保留大牌，观察局势", service.explain(engine.getViewState(1), "比大小"));
            assertTrue(payload.get().contains("test-model")); assertFalse(payload.get().contains("test-key"));
            assertFalse(payload.get().contains("guestToken"));
            assertEquals(1, payload.get().split("hand", -1).length - 1);
            assertTrue(new CoachService(false, "", "", "").available() == false);
        } finally { server.stop(0); }
    }
}
