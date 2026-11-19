package com.gomoku.server;

import com.gomoku.game.EventService;
import com.gomoku.game.GameType;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 限时活动接口。
 *
 * 玩家侧 {@code GET /api/events} 无需登录：大厅需要展示当前加成；
 * 管理侧沿用 X-Admin-Token，与公告/治理后台同一套入口。
 */
@RestController
public class EventController {

    private final EventService events;
    private final AdminGuard adminGuard;

    public EventController(EventService events, AdminGuard adminGuard) {
        this.events = events;
        this.adminGuard = adminGuard;
    }

    /** 大厅可见活动（生效且未过期） */
    @GetMapping("/api/events")
    public ResponseEntity<Map<String, Object>> live() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (EventService.Event item : events.listLive()) {
            rows.add(view(item, false));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("events", rows);
        body.put("serverTime", System.currentTimeMillis());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    // ============ 管理端（需 X-Admin-Token） ============

    @GetMapping("/api/admin/events")
    public ResponseEntity<Map<String, Object>> list(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        ResponseEntity<Map<String, Object>> denied = adminGuard.check(token);
        if (denied != null) return denied;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (EventService.Event item : events.listAll()) {
            rows.add(view(item, true));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("events", rows);
        body.put("durations", new ArrayList<>(EventService.DURATION_HOURS.keySet()));
        body.put("percents", new ArrayList<>(EventService.PERCENT_OPTIONS));
        body.put("gameTypes", gameTypeOptions());
        body.put("serverTime", System.currentTimeMillis());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @PostMapping("/api/admin/events")
    public ResponseEntity<Map<String, Object>> create(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        ResponseEntity<Map<String, Object>> denied = adminGuard.check(token);
        if (denied != null) return denied;
        Integer percent = intValue(body.get("xpPercent"));
        if (percent == null) return error(400, "缺少经验加成百分比");
        try {
            EventService.Event created = events.create(
                    string(body.get("title")), string(body.get("gameType")), percent, string(body.get("duration")));
            return ok("event", view(created, true));
        } catch (IllegalArgumentException e) {
            return error(400, e.getMessage());
        }
    }

    /** 暂停 / 恢复 */
    @PostMapping("/api/admin/events/{id}")
    public ResponseEntity<Map<String, Object>> toggle(
            @PathVariable String id,
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        ResponseEntity<Map<String, Object>> denied = adminGuard.check(token);
        if (denied != null) return denied;
        Object value = body.get("active");
        if (!(value instanceof Boolean)) return error(400, "缺少 active 字段");
        EventService.Event updated = events.setActive(id, (Boolean) value);
        if (updated == null) return error(404, "活动不存在");
        return ok("event", view(updated, true));
    }

    @DeleteMapping("/api/admin/events/{id}")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable String id,
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        ResponseEntity<Map<String, Object>> denied = adminGuard.check(token);
        if (denied != null) return denied;
        if (!events.delete(id)) return error(404, "活动不存在");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", true);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    // ============ 内部 ============

    private static Map<String, Object> view(EventService.Event item, boolean admin) {
        long ts = System.currentTimeMillis();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", item.id);
        row.put("title", item.title);
        row.put("gameType", item.gameType);
        row.put("gameLabel", EventService.gameLabel(item.gameType));
        row.put("xpPercent", item.xpPercent);
        row.put("startsAt", item.startsAt);
        row.put("endsAt", item.endsAt);
        if (admin) {
            boolean ended = ts >= item.endsAt;
            row.put("active", item.active);
            row.put("ended", ended);
            row.put("live", item.active && !ended && ts >= item.startsAt);
        }
        return row;
    }

    private static List<Map<String, Object>> gameTypeOptions() {
        List<Map<String, Object>> options = new ArrayList<>();
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("value", EventService.ALL_GAMES);
        all.put("label", "全部玩法");
        options.add(all);
        for (GameType type : GameType.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("value", type.name());
            item.put("label", type.getDisplayName());
            options.add(item);
        }
        return options;
    }

    private static ResponseEntity<Map<String, Object>> ok(String key, Object value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(key, value);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer intValue(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value == null) return null;
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
