package com.gomoku.server;

import com.gomoku.game.DailyQuestService;
import com.gomoku.game.GuestIdentityService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 每日任务 REST 接口。进度按持久身份归属，领取必须携带有效凭证。
 */
@RestController
@RequestMapping("/api/quests")
public class DailyQuestController {
    private final DailyQuestService quests;
    private final GuestIdentityService guests;

    public DailyQuestController(DailyQuestService quests, GuestIdentityService guests) {
        this.quests = quests;
        this.guests = guests;
    }

    /** 今日任务列表：进度、可领取状态与累计任务经验 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> status(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String id = guests.resolve(token(authorization));
        if (id == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ok(quests.status(id));
    }

    /** 领取已完成任务的经验；重复领取 409，未完成 400，未知任务 404 */
    @PostMapping("/{questId}/claim")
    public ResponseEntity<Map<String, Object>> claim(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String questId) {
        String id = guests.resolve(token(authorization));
        if (id == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        Map<String, Object> result = quests.claim(id, questId);
        if (Boolean.TRUE.equals(result.get("ok"))) return ok(result);
        String reason = String.valueOf(result.get("reason"));
        HttpStatus status = "UNKNOWN_QUEST".equals(reason) ? HttpStatus.NOT_FOUND
                : "ALREADY_CLAIMED".equals(reason) ? HttpStatus.CONFLICT
                : HttpStatus.BAD_REQUEST;
        String message = "UNKNOWN_QUEST".equals(reason) ? "任务不存在"
                : "ALREADY_CLAIMED".equals(reason) ? "今日已领取该任务"
                : "任务尚未完成";
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("message", message));
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static String token(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }
}
