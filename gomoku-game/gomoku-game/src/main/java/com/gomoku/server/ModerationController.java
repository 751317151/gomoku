package com.gomoku.server;

import com.gomoku.game.GuestIdentityService;
import com.gomoku.game.ModerationService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 运营治理接口：玩家提交举报 + 管理员查看/处置。
 *
 * 鉴权取舍：
 * - 玩家侧只用本人身份凭证（与其它 /api 一致），不需要额外登录。
 * - 管理侧用 .env 的 ADMIN_TOKEN（请求头 X-Admin-Token）。未配置该变量时
 *   管理接口整体关闭（返回 503），避免裸奔的默认口令；配置后需与令牌一致。
 *   这样自托管部署者无需再引入一套后台账号体系，也能安全操作。
 */
@RestController
public class ModerationController {

    private final ModerationService moderation;
    private final GuestIdentityService guests;
    private final AdminGuard adminGuard;

    public ModerationController(ModerationService moderation, GuestIdentityService guests,
                                AdminGuard adminGuard) {
        this.moderation = moderation;
        this.guests = guests;
        this.adminGuard = adminGuard;
    }

    /** 我当前的处置状态：被封禁/禁言时前端可提前提示 */
    @GetMapping("/api/moderation/me")
    public ResponseEntity<Map<String, Object>> myStatus(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String identityId = guests.resolve(token(authorization));
        if (identityId == null) return ResponseEntity.status(401).build();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("banned", moderation.isBanned(identityId));
        body.put("muted", moderation.isMuted(identityId));
        body.put("remainingSeconds", moderation.remainingSeconds(identityId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /** 我的举报记录（最新的在前），便于玩家确认已提交 */
    @GetMapping("/api/moderation/reports/mine")
    public ResponseEntity<Map<String, Object>> myReports(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false, defaultValue = "20") int limit) {
        String identityId = guests.resolve(token(authorization));
        if (identityId == null) return ResponseEntity.status(401).build();
        List<ModerationService.Report> all = moderation.reports(false, 0);
        List<Map<String, Object>> mine = new ArrayList<>();
        for (ModerationService.Report report : all) {
            if (!identityId.equals(report.reporterId)) continue;
            mine.add(reportView(report, false));
            if (limit > 0 && mine.size() >= limit) break;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reports", mine);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    // ============ 管理端（需 X-Admin-Token） ============

    /** 举报队列 + 处置表 */
    @GetMapping("/api/admin/moderation")
    public ResponseEntity<Map<String, Object>> overview(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(required = false, defaultValue = "false") boolean onlyPending,
            @RequestParam(required = false, defaultValue = "100") int limit) {
        ResponseEntity<Map<String, Object>> denied = adminGuard.check(token);
        if (denied != null) return denied;
        List<Map<String, Object>> reports = new ArrayList<>();
        for (ModerationService.Report report : moderation.reports(onlyPending, Math.min(limit, 200))) {
            reports.add(reportView(report, true));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reports", reports);
        body.put("sanctions", moderation.sanctionList());
        body.put("durations", new ArrayList<>(ModerationService.DURATIONS.keySet()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /** 处置某个举报背后的玩家：先按举报定位被举报人，再禁言/封禁 */
    @PostMapping("/api/admin/moderation/reports/{reportId}/sanction")
    public ResponseEntity<Map<String, Object>> sanctionReport(
            @PathVariable String reportId,
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody Map<String, String> body) {
        ResponseEntity<Map<String, Object>> denied = adminGuard.check(token);
        if (denied != null) return denied;
        ModerationService.Report target = null;
        for (ModerationService.Report report : moderation.reports(false, 0)) {
            if (report.id.equals(reportId)) { target = report; break; }
        }
        if (target == null) return error(404, "举报记录不存在");
        try {
            ModerationService.Sanction sanction = moderation.sanction(
                    target.targetId, body.get("action"), body.get("duration"),
                    body.get("reason"), "admin");
            moderation.markHandled(reportId, body.get("action") + "/" + body.get("duration"));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(sanctionView(target.targetId, sanction));
        } catch (IllegalArgumentException e) {
            return error(400, e.getMessage());
        }
    }

    /** 忽略举报（证据不足 / 误报） */
    @PostMapping("/api/admin/moderation/reports/{reportId}/dismiss")
    public ResponseEntity<Map<String, Object>> dismiss(
            @PathVariable String reportId,
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody(required = false) Map<String, String> body) {
        ResponseEntity<Map<String, Object>> denied = adminGuard.check(token);
        if (denied != null) return denied;
        String note = body == null ? null : body.get("note");
        if (!moderation.markHandled(reportId, note == null ? "dismissed" : note)) {
            return error(404, "举报记录不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "已忽略该举报");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    /** 直接按身份处置（管理员已知对方身份时，不必先有举报） */
    @PostMapping("/api/admin/moderation/sanctions")
    public ResponseEntity<Map<String, Object>> sanctionIdentity(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody Map<String, String> body) {
        ResponseEntity<Map<String, Object>> denied = adminGuard.check(token);
        if (denied != null) return denied;
        try {
            ModerationService.Sanction sanction = moderation.sanction(
                    body.get("identityId"), body.get("action"), body.get("duration"),
                    body.get("reason"), "admin");
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(sanctionView(body.get("identityId"), sanction));
        } catch (IllegalArgumentException e) {
            return error(400, e.getMessage());
        }
    }

    /** 撤销处置 */
    @DeleteMapping("/api/admin/moderation/sanctions/{identityId}")
    public ResponseEntity<Map<String, Object>> lift(
            @PathVariable String identityId,
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        ResponseEntity<Map<String, Object>> denied = adminGuard.check(token);
        if (denied != null) return denied;
        if (!moderation.lift(identityId)) return error(404, "该身份没有处置记录");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "处置已撤销");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    // ============ 内部 ============

    /** 举报视图：对玩家隐藏对方身份（只给自己的举报看对方昵称） */
    private static Map<String, Object> reportView(ModerationService.Report report, boolean admin) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", report.id);
        row.put("reporterName", report.reporterName);
        row.put("targetName", report.targetName);
        row.put("reason", report.reason);
        row.put("detail", report.detail);
        row.put("createdAt", report.createdAt);
        row.put("handled", report.handled);
        row.put("handledNote", report.handledNote);
        if (admin) {
            row.put("reporterId", report.reporterId);
            row.put("targetId", report.targetId);
            row.put("roomId", report.roomId);
            row.put("gameType", report.gameType);
        }
        return row;
    }

    private static Map<String, Object> sanctionView(String identityId, ModerationService.Sanction sanction) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("identityId", identityId);
        row.put("action", sanction.action);
        row.put("since", sanction.since);
        row.put("expiresAt", sanction.expiresAt);
        row.put("reason", sanction.reason);
        return row;
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }

    private static String token(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }
}
