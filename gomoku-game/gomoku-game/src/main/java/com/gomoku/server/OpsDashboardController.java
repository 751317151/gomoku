package com.gomoku.server;

import com.gomoku.game.OpsMetricsService;
import com.gomoku.game.ProductAnalyticsService;
import com.gomoku.game.RoomManager;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运营看板接口（需 X-Admin-Token）。
 *
 * 只读：从对局记录派生指标，再叠加实时在线/房间数，
 * 让运营在一个页面里同时看到「此刻」和「趋势」。
 */
@RestController
@RequestMapping("/api/admin/ops")
public class OpsDashboardController {

    private final OpsMetricsService metrics;
    private final ProductAnalyticsService analytics;
    private final RoomManager rooms;
    private final AdminGuard adminGuard;

    public OpsDashboardController(OpsMetricsService metrics, ProductAnalyticsService analytics,
                                  RoomManager rooms, AdminGuard adminGuard) {
        this.metrics = metrics;
        this.analytics = analytics;
        this.rooms = rooms;
        this.adminGuard = adminGuard;
    }

    /** 总览：实时状态 + 窗口趋势 + 留存 + 玩法与时段分布 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> overview(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(required = false, defaultValue = "14") int days) {
        ResponseEntity<Map<String, Object>> denied = adminGuard.check(token);
        if (denied != null) return denied;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("realtime", rooms.getStats());
        body.put("metrics", metrics.overview(days));
        body.put("funnel", analytics.overview(days));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
