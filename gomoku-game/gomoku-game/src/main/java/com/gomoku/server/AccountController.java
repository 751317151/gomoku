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

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
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

    /** 修改密码 */
    @PostMapping("/password")
    public ResponseEntity<Map<String, String>> changePassword(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        try {
            accounts.changePassword(token(authorization), body.get("oldPassword"), body.get("newPassword"));
            return ok(Map.of("message", "密码已更新"));
        } catch (AccountService.AccountException e) {
            return error(statusOf(e), e);
        }
    }

    private static int statusOf(AccountService.AccountException e) {
        switch (e.getCode()) {
            case "CONFLICT": return HttpStatus.CONFLICT.value();
            case "UNAUTHORIZED": return HttpStatus.UNAUTHORIZED.value();
            case "INVALID": return HttpStatus.BAD_REQUEST.value();
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
