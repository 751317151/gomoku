package com.gomoku.server;

import com.gomoku.game.AccountService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 账号 REST 接口：注册、登录、当前账号、改密。
 *
 * 全部返回 no-store，避免凭证被浏览器或代理缓存。业务错误由 AccountException
 * 映射成带 code 的 JSON，HTTP 语义沿用 400/401/409。
 */
@RestController
@RequestMapping("/api/account")
public class AccountController {
    private final AccountService accounts;
    private final com.gomoku.game.RoomManager rooms;

    public AccountController(AccountService accounts, com.gomoku.game.RoomManager rooms) {
        this.accounts = accounts;
        this.rooms = rooms;
    }

    /** 注册；请求头带 Authorization: Bearer <游客token> 时可无损升级游客战绩 */
    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        try {
            return ok(accounts.register(body.get("username"), body.get("password"),
                    body.get("nickname"), token(authorization)));
        } catch (AccountService.AccountException e) {
            return error(statusOf(e), e);
        }
    }

    /** 登录，返回指向同一身份的新 token */
    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody Map<String, String> body) {
        try {
            return ok(accounts.login(body.get("username"), body.get("password")));
        } catch (AccountService.AccountException e) {
            return error(statusOf(e), e);
        }
    }

    /** 当前凭证对应的账号信息；未登录账号时返回 401，前端据此退回游客态 */
    @GetMapping("/me")
    public ResponseEntity<Map<String, String>> me(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Map<String, String> account = accounts.current(token(authorization));
        if (account == null) return error(HttpStatus.UNAUTHORIZED.value(), "ACCOUNT_REQUIRED", "尚未登录账号");
        return ok(account);
    }

    /** 修改密码：返回当前设备的新凭证，其他设备旧凭证立即失效。 */
    @PostMapping("/password")
    public ResponseEntity<Map<String, String>> changePassword(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        try {
            return ok(accounts.changePassword(
                    token(authorization), body.get("oldPassword"), body.get("newPassword")));
        } catch (AccountService.AccountException e) {
            return error(statusOf(e), e);
        }
    }

    /** 恢复码重置密码：不用旧密码，用恢复码重置，同时撤销全部旧会话。 */
    @PostMapping("/recover-password")
    public ResponseEntity<Map<String, String>> recoverPassword(
            @RequestBody Map<String, String> body) {
        try {
            return ok(accounts.recoverPassword(
                    body.get("username"), body.get("recoveryCode"), body.get("newPassword")));
        } catch (AccountService.AccountException e) {
            return error(statusOf(e), e);
        }
    }

    /** 已登录用户手动轮换恢复码，旧码立即失效，需要验证当前密码。 */
    @PostMapping("/rotate-recovery-code")
    public ResponseEntity<Map<String, String>> rotateRecoveryCode(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        try {
            return ok(accounts.rotateRecoveryCode(
                    token(authorization), body.get("password")));
        } catch (AccountService.AccountException e) {
            return error(statusOf(e), e);
        }
    }

    /** 退出其他设备：撤销同身份的其他会话，当前凭证继续有效。 */
    @PostMapping("/logout-others")
    public ResponseEntity<Map<String, String>> logoutOthers(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        try {
            String current = token(authorization);
            Map<String, String> account = accounts.current(current);
            int revoked = accounts.logoutOtherSessions(current);
            // 只断开使用旧凭证的连接，当前设备凭当前 token 保持在线。
            if (account != null) rooms.disconnectOtherSessions(
                    account.get("id"), current, "账号已在其他设备退出");
            Map<String, String> body = new LinkedHashMap<>();
            body.put("revoked", String.valueOf(revoked));
            body.put("message", revoked > 0 ? "已退出 " + revoked + " 个其他会话" : "没有其他在线会话");
            return ok(body);
        } catch (AccountService.AccountException e) {
            return error(statusOf(e), e);
        }
    }

    /** 撤销当前账号凭证；成功后客户端必须删除本地凭证。 */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String current = token(authorization);
        Map<String, String> account = accounts.current(current);
        accounts.logout(current);
        if (account != null) rooms.disconnectIdentity(account.get("id"), "账号已退出登录");
        return ok(Map.of("message", "已退出账号"));
    }

    private static int statusOf(AccountService.AccountException e) {
        switch (e.getCode()) {
            case "CONFLICT": return HttpStatus.CONFLICT.value();
            case "UNAUTHORIZED": return HttpStatus.UNAUTHORIZED.value();
            case "INVALID": return HttpStatus.BAD_REQUEST.value();
            case "TOO_MANY_ATTEMPTS": return HttpStatus.TOO_MANY_REQUESTS.value();
            default: return HttpStatus.BAD_REQUEST.value();
        }
    }

    private static ResponseEntity<Map<String, String>> ok(Map<String, String> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<Map<String, String>> error(int status, AccountService.AccountException e) {
        return error(status, e.getCode(), e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> error(int status, String code, String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }

    private static String token(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }
}
