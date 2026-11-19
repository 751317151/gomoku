package com.gomoku.server;

import com.gomoku.game.AnnouncementService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 运营公告接口。
 *
 * 玩家侧 {@code GET /api/announcements} 无需登录：大厅打开就要看到活动/维护信息；
 * 管理侧沿用 X-Admin-Token（同治理接口），未配置令牌时整体关闭。
 */
@RestController
public class AnnouncementController {

    private final AnnouncementService announcements;
    private final AdminGuard adminGuard;

    public AnnouncementController(AnnouncementService announcements, AdminGuard adminGuard) {
        this.announcements = announcements;
        this.adminGuard = adminGuard;
    }

    /** 大厅可见公告（生效且未过期） */
    @GetMapping("/api/announcements")
    public ResponseEntity<Map<String, Object>> active() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AnnouncementService.Announcement item : announcements.listActive()) {
            rows.add(view(item, false));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("announcements", rows);
        body.put("serverTime", System.currentTimeMillis());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    // ============ 管理端（需 X-Admin-Token） ============

    /** 全部公告 + 可选级别/有效期，方便后台直接渲染下拉框 */
    @GetMapping("/api/admin/announcements")
    public ResponseEntity<Map<String, Object>> list(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        ResponseEntity<Map<String, Object>> denied = adminGuard.check(token);
        if (denied != null) return denied;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AnnouncementService.Announcement item : announcements.listAll()) {
            rows.add(view(item, true));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("announcements", rows);
        body.put("levels", new ArrayList<>(AnnouncementService.LEVELS));
        body.put("ttls", new ArrayList<>(AnnouncementService.TTL_HOURS.keySet()));
        body.put("serverTime", System.currentTimeMillis());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @PostMapping("/api/admin/announcements")
    public ResponseEntity<Map<String, Object>> create(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        ResponseEntity<Map<String, Object>> denied = adminGuard.check(token);
        if (denied != null) return denied;
        try {
            AnnouncementService.Announcement created = announcements.create(
                    string(body.get("title")), string(body.get("body")),
                    string(body.get("level")), string(body.get("ttl")));
            return ok("announcement", view(created, true));
        } catch (IllegalArgumentException e) {
            return error(400, e.getMessage());
        }
    }

    /** 上线 / 下线 */
    @PostMapping("/api/admin/announcements/{id}")
    public ResponseEntity<Map<String, Object>> toggle(
            @PathVariable String id,
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        ResponseEntity<Map<String, Object>> denied = adminGuard.check(token);
        if (denied != null) return denied;
        Object value = body.get("active");
        if (!(value instanceof Boolean)) return error(400, "缺少 active 字段");
        AnnouncementService.Announcement updated = announcements.setActive(id, (Boolean) value);
        if (updated == null) return error(404, "公告不存在");
        return ok("announcement", view(updated, true));
    }

    @DeleteMapping("/api/admin/announcements/{id}")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable String id,
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        ResponseEntity<Map<String, Object>> denied = adminGuard.check(token);
        if (denied != null) return denied;
        if (!announcements.delete(id)) return error(404, "公告不存在");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", true);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    // ============ 内部 ============

    private static Map<String, Object> view(AnnouncementService.Announcement item, boolean admin) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", item.id);
        row.put("title", item.title);
        row.put("body", item.body);
        row.put("level", item.level);
        row.put("createdAt", item.createdAt);
        row.put("expiresAt", item.expiresAt);
        if (admin) {
            row.put("active", item.active);
            row.put("expired", item.expiresAt > 0 && System.currentTimeMillis() >= item.expiresAt);
        }
        return row;
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
}
