package com.gomoku.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理接口共享鉴权：X-Admin-Token 与 .env 的 ADMIN_TOKEN 恒定时间比对。
 *
 * 抽成组件是因为治理接口与运营看板都要用同一套规则：
 * 未配置令牌时管理功能整体关闭（503），避免自托管部署默认裸奔。
 */
@Component
public class AdminGuard {

    private final String adminToken;

    public AdminGuard(@Value("${admin.token:}") String adminToken) {
        this.adminToken = adminToken == null ? "" : adminToken.trim();
    }

    /** 鉴权通过返回 null；否则返回可直接下发给客户端的错误响应 */
    public ResponseEntity<Map<String, Object>> check(String provided) {
        if (adminToken.isEmpty()) return error(503, "管理接口未启用（未配置 ADMIN_TOKEN）");
        if (provided == null || !constantTimeEquals(provided.trim(), adminToken)) {
            return error(403, "管理令牌无效");
        }
        return null;
    }

    /** 管理功能是否已启用（前端据此决定是否显示入口） */
    public boolean enabled() {
        return !adminToken.isEmpty();
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
