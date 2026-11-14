package com.gomoku.server;

import com.gomoku.game.*;
import com.gomoku.model.Player;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
public class CoachController {
    private final RoomManager rooms;
    private final CoachService coach;
    private final ConcurrentHashMap<String, Long> requests = new ConcurrentHashMap<>();
    public CoachController(RoomManager rooms, CoachService coach) { this.rooms = rooms; this.coach = coach; }
    @GetMapping("/api/coach") public Map<String, Boolean> status() { return Map.of("available", coach.available()); }
    @PostMapping("/api/coach")
    public ResponseEntity<Map<String, String>> explain(@RequestBody Map<String, String> body) {
        if (!coach.available()) return result(503, "AI 导师尚未配置");
        Player player = rooms.getPlayerBySession(body.get("sessionId"));
        GameRoom room = player == null ? null : rooms.getRoomByPlayer(player);
        if (room == null || !room.hasPlayer(player)) return result(401, "请先加入房间");
        long now = System.currentTimeMillis();
        requests.entrySet().removeIf(e -> now - e.getValue() > 60000);
        Long previous = requests.putIfAbsent(player.getId(), now);
        if (previous != null && (now - previous < 15000 || !requests.replace(player.getId(), previous, now))) return result(429, "请稍候再请求讲解");
        try { return result(200, coach.explain(room.buildSyncDataForReconnect(player), room.getGameType().getDisplayName())); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return result(503, "讲解已中断，请重试"); }
        catch (Exception e) { return result(502, "模型服务暂不可用，请稍后重试"); }
    }
    private static ResponseEntity<Map<String, String>> result(int status, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("message", message));
    }
}
