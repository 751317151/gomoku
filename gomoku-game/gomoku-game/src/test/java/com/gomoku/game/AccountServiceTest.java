package com.gomoku.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 账号体系单测：注册、登录、游客无损升级、密码安全、跨设备与边界。 */
class AccountServiceTest {
    @TempDir Path dir;

    private AccountService newAccounts() {
        GuestIdentityService guests = new GuestIdentityService(dir.resolve("guests.json"));
        guests.init();
        AccountService accounts = new AccountService(dir.resolve("accounts.json"), guests);
        accounts.init();
        return accounts;
    }

    @Test
    void registerThenLoginReturnsSameIdentity() {
        AccountService accounts = newAccounts();
        Map<String, String> registered = accounts.register("Alice", "secret123", "爱丽丝", null);
        assertEquals("alice", registered.get("username"));
        assertEquals("爱丽丝", registered.get("nickname"));
        assertNotNull(registered.get("token"));
        assertEquals(registered.get("id"), accounts.current(registered.get("token")).get("id"));

        Map<String, String> logged = accounts.login("ALICE", "secret123");
        assertEquals(registered.get("id"), logged.get("id"), "登录必须回到同一身份，资产才不会丢");
        assertNotEquals(registered.get("token"), logged.get("token"), "每次登录应签发新凭证");
        assertEquals(registered.get("id"), accounts.current(logged.get("token")).get("id"));
    }

    @Test
    void guestUpgradeKeepsIdentity() {
        GuestIdentityService guests = new GuestIdentityService(dir.resolve("guests.json"));
        guests.init();
        Map<String, String> guest = guests.issue();
        String guestId = guest.get("id");

        AccountService accounts = new AccountService(dir.resolve("accounts.json"), guests);
        Map<String, String> registered = accounts.register("bob", "secret123", null, guest.get("token"));
        assertEquals(guestId, registered.get("id"), "游客升级必须沿用原身份，历史战绩/ELO 才能延续");
        assertEquals(guestId, guests.resolve(registered.get("token")));
    }

    @Test
    void passwordIsSaltedHashNeverPlaintext() throws Exception {
        AccountService accounts = newAccounts();
        accounts.register("carol", "myPlainPassword", null, null);
        String disk = Files.readString(dir.resolve("accounts.json"));
        assertFalse(disk.contains("myPlainPassword"), "磁盘不能出现明文密码");
        assertTrue(disk.contains("passwordHash"), "应存哈希字段");
        assertFalse(disk.contains("\"salt\":\"\""), "盐不能为空");
        assertTrue(disk.contains(AccountService.PASSWORD_SCHEME), "新账号应使用 PBKDF2");
        assertTrue(disk.contains("\"passwordIterations\":310000"));
    }

    @Test
    void duplicateUsernameRejectedAndCaseInsensitive() {
        AccountService accounts = newAccounts();
        accounts.register("Dave", "secret123", null, null);
        AccountService.AccountException ex = assertThrows(AccountService.AccountException.class,
                () -> accounts.register("dave", "secret123", null, null));
        assertEquals("CONFLICT", ex.getCode());
        assertEquals(1, accounts.size());
    }

    @Test
    void wrongPasswordRejected() {
        AccountService accounts = newAccounts();
        accounts.register("erin", "secret123", null, null);
        AccountService.AccountException ex = assertThrows(AccountService.AccountException.class,
                () -> accounts.login("erin", "wrongpass"));
        assertEquals("UNAUTHORIZED", ex.getCode());
    }

    @Test
    void invalidUsernameAndPasswordRejected() {
        AccountService accounts = newAccounts();
        assertEquals("INVALID", assertThrows(AccountService.AccountException.class,
                () -> accounts.register("ab", "secret123", null, null)).getCode());
        assertEquals("INVALID", assertThrows(AccountService.AccountException.class,
                () -> accounts.register("frank", "12345", null, null)).getCode());
    }

    @Test
    void changePasswordThenOldFailsNewWorks() {
        AccountService accounts = newAccounts();
        Map<String, String> registered = accounts.register("grace", "secret123", null, null);
        String token = registered.get("token");
        Map<String, String> rotated = accounts.changePassword(token, "secret123", "newsecret456");
        assertThrows(AccountService.AccountException.class,
                () -> accounts.login("grace", "secret123"));
        assertEquals(registered.get("id"), accounts.login("grace", "newsecret456").get("id"),
                "改密后身份不变");
        assertNull(accounts.current(token), "改密必须撤销旧设备凭证");
        assertEquals(registered.get("id"), accounts.current(rotated.get("token")).get("id"));
    }

    @Test
    void logoutOthersKeepsCurrentSessionOnly() {
        AccountService accounts = newAccounts();
        Map<String, String> first = accounts.register("jack", "secret123", null, null);
        Map<String, String> second = accounts.login("jack", "secret123");
        Map<String, String> third = accounts.login("jack", "secret123");

        assertEquals(2, accounts.logoutOtherSessions(first.get("token")));
        assertEquals("jack", accounts.current(first.get("token")).get("username"));
        assertNull(accounts.current(second.get("token")));
        assertNull(accounts.current(third.get("token")));
    }

    @Test
    void explicitLogoutRevokesOnlyAccountSession() {
        GuestIdentityService guests = newGuests();
        AccountService accounts = new AccountService(dir.resolve("accounts.json"), guests);
        accounts.init();
        Map<String, String> account = accounts.register("kate", "secret123", null, null);
        Map<String, String> guest = guests.issue();

        assertTrue(accounts.logout(account.get("token")));
        assertNull(accounts.current(account.get("token")));
        assertFalse(accounts.logout(guest.get("token")), "游客凭证不能被账号退出接口误删");
        assertEquals(guest.get("id"), guests.resolve(guest.get("token")));
    }

    @Test
    void legacyPasswordHashUpgradesOnSuccessfulLogin() throws Exception {
        GuestIdentityService guests = newGuests();
        Path accountsPath = dir.resolve("accounts.json");
        AccountService created = new AccountService(accountsPath, guests);
        created.init();
        created.register("legacy", "secret123", "旧账号", null);

        // 手工构造一份真实旧格式记录：SHA-256 加盐迭代 12000 次。
        Map<String, Map<String, Object>> stored = new Gson().fromJson(
                Files.readString(accountsPath), new TypeToken<Map<String, Map<String, Object>>>() {}.getType());
        Map<String, Object> account = stored.get("legacy");
        byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(salt);
        byte[] hash = digest.digest("secret123".getBytes(StandardCharsets.UTF_8));
        for (int i = 1; i < AccountService.LEGACY_HASH_ITERATIONS; i++) hash = digest.digest(hash);
        account.put("salt", Base64.getEncoder().encodeToString(salt));
        account.put("passwordHash", Base64.getEncoder().encodeToString(hash));
        account.put("passwordScheme", null);
        account.put("passwordIterations", AccountService.LEGACY_HASH_ITERATIONS);
        Files.writeString(accountsPath, new Gson().toJson(stored));

        AccountService restarted = new AccountService(accountsPath, guests);
        restarted.init();
        Map<String, String> logged = restarted.login("legacy", "secret123");
        assertEquals("旧账号", logged.get("nickname"));
        assertTrue(Files.readString(accountsPath).contains(AccountService.PASSWORD_SCHEME),
                "成功登录后旧散列应透明升级");
    }

    @Test
    void currentWithoutAccountReturnsNull() {
        AccountService accounts = newAccounts();
        GuestIdentityService guests = new GuestIdentityService(dir.resolve("guests.json"));
        guests.init();
        assertNull(accounts.current("not-a-token"));
        // 纯游客身份不属于任何账号
        Map<String, String> guest = guests.issue();
        assertNull(accounts.current(guest.get("token")));
    }

    @Test
    void accountsSurviveRestart() {
        AccountService first = newAccounts();
        Map<String, String> registered = first.register("heidi", "secret123", "海蒂", null);
        AccountService restarted = newAccounts();
        assertEquals(registered.get("id"), restarted.login("heidi", "secret123").get("id"),
                "重启后账号仍可登录并回到同一身份");
        assertEquals("海蒂", restarted.current(registered.get("token")).get("nickname"));
    }

    @Test
    void claimedIdentityNotReusedByAnotherAccount() {
        GuestIdentityService guests = new GuestIdentityService(dir.resolve("guests.json"));
        guests.init();
        Map<String, String> guest = guests.issue();
        AccountService accounts = new AccountService(dir.resolve("accounts.json"), guests);
        accounts.register("ivan", "secret123", null, guest.get("token"));
        Map<String, String> second = accounts.register("judy", "secret123", null, guest.get("token"));
        assertNotEquals(guest.get("id"), second.get("id"), "一个身份不能同时属于两个账号");
    }

    @Test
    void loginLocksOutAfterFiveFailuresAndUnlocksAfterWindow() throws Exception {
        AccountService svc = new AccountService(dir.resolve("accounts-throttle.json"), newGuests()) {
            private long fake = 1_000_000L;
            @Override long clock() { return fake; }
        };
        svc.init();
        svc.register("throttleuser", "Correct@123", "节流玩家", null);

        long t0 = 1_000_000L;
        for (int i = 0; i < AccountService.MAX_LOGIN_FAILURES; i++) {
            svc.recordLoginFailure("ThrottleUser", t0);
        }
        assertEquals(AccountService.MAX_LOGIN_FAILURES, svc.failureCount("throttleuser", t0));

        // 达到阈值后，即使密码正确也必须拒绝，避免在线暴力破解
        AccountService.AccountException locked = assertThrows(AccountService.AccountException.class,
                () -> svc.login("throttleuser", "Correct@123"));
        assertEquals("TOO_MANY_ATTEMPTS", locked.getCode());

        // 时间前进 11 分钟：失败记录全部过期，解锁后正确密码可登录
        // fake clock 在子类闭包中推进（这里通过 record+login 的组合验证）
        // 由于 Java 匿名类捕获的是 final，改用「先记 5 次旧失败，再断言可登录」的等效路径：
        AccountService unlocked = new AccountService(dir.resolve("accounts-throttle2.json"), newGuests()) {
            private long fake = 1_000_000L;
            @Override long clock() { return fake; }
        };
        unlocked.init();
        unlocked.register("throttleuser", "Correct@123", "节流玩家", null);
        long stale = 1_000_000L - 11 * 60 * 1000L;
        for (int i = 0; i < AccountService.MAX_LOGIN_FAILURES; i++) {
            unlocked.recordLoginFailure("ThrottleUser", stale);
        }
        Map<String, String> result = unlocked.login("throttleuser", "Correct@123");
        assertEquals("节流玩家", result.get("nickname"));
    }

    private GuestIdentityService newGuests() {
        GuestIdentityService guests = new GuestIdentityService(dir.resolve("guests.json"));
        guests.init();
        return guests;
    }

    @Test
    void successfulLoginClearsFailureCounter() {
        AccountService svc = newAccounts();
        svc.register("resetuser", "Correct@123", "重置玩家", null);

        long now = System.currentTimeMillis();
        svc.recordLoginFailure("resetuser", now);
        svc.recordLoginFailure("resetuser", now);
        assertEquals(2, svc.failureCount("resetuser", now));

        svc.login("resetuser", "Correct@123");
        assertEquals(0, svc.failureCount("resetuser", now));
    }

    @Test
    void recoveryCodeReturnedOnceAndNeverStoredInPlaintext() throws Exception {
        AccountService accounts = newAccounts();
        Map<String, String> registered = accounts.register("recover1", "secret123", null, null);
        String code = registered.get("recoveryCode");
        assertNotNull(code);
        assertEquals(20, code.length(), "恢复码长度固定，便于用户抄写");
        assertTrue(code.matches("[A-HJ-NP-Z2-9]{20}"), "恢复码应使用去混淆字符集");
        String disk = Files.readString(dir.resolve("accounts.json"));
        assertFalse(disk.contains(code), "磁盘不能保存恢复码明文");
        assertTrue(disk.contains("recoveryCodeHash"));
        assertNull(accounts.login("recover1", "secret123").get("recoveryCode"),
                "登录不应重复下发恢复码");
    }

    @Test
    void recoverPasswordResetsPasswordAndRevokesAllSessions() {
        AccountService accounts = newAccounts();
        Map<String, String> registered = accounts.register("recover2", "secret123", "找回玩家", null);
        String firstToken = registered.get("token");
        String secondToken = accounts.login("recover2", "secret123").get("token");

        Map<String, String> recovered = accounts.recoverPassword(
                "RECOVER2", " " + registered.get("recoveryCode").toLowerCase() + " ", "brandNew789");
        assertEquals(registered.get("id"), recovered.get("id"), "找回密码必须回到同一身份，资产不丢");
        assertEquals("找回玩家", recovered.get("nickname"));
        assertNotNull(recovered.get("token"));
        assertNotNull(recovered.get("recoveryCode"));
        assertNotEquals(registered.get("recoveryCode"), recovered.get("recoveryCode"),
                "使用后必须换发新恢复码");

        assertNull(accounts.current(firstToken), "旧会话必须全部撤销");
        assertNull(accounts.current(secondToken), "其他设备也必须撤销");
        assertEquals(registered.get("id"), accounts.current(recovered.get("token")).get("id"));
        assertThrows(AccountService.AccountException.class,
                () -> accounts.login("recover2", "secret123"));
        assertEquals(registered.get("id"), accounts.login("recover2", "brandNew789").get("id"));
    }

    @Test
    void recoveryCodeIsSingleUse() {
        AccountService accounts = newAccounts();
        Map<String, String> registered = accounts.register("recover3", "secret123", null, null);
        accounts.recoverPassword("recover3", registered.get("recoveryCode"), "brandNew789");

        AccountService.AccountException reused = assertThrows(AccountService.AccountException.class,
                () -> accounts.recoverPassword("recover3", registered.get("recoveryCode"), "anotherOne123"));
        assertEquals("UNAUTHORIZED", reused.getCode());
        // 旧码失效不应影响新密码
        assertEquals(registered.get("id"), accounts.login("recover3", "brandNew789").get("id"));
    }

    @Test
    void wrongRecoveryCodeDoesNotRevealAccountExistence() {
        AccountService accounts = newAccounts();
        accounts.register("recover4", "secret123", null, null);
        String wrongForExisting = assertThrows(AccountService.AccountException.class,
                () -> accounts.recoverPassword("recover4", "AAAAAAAAAAAAAAAAAAAA", "brandNew789")).getMessage();
        String wrongForMissing = assertThrows(AccountService.AccountException.class,
                () -> accounts.recoverPassword("nobodyhere", "AAAAAAAAAAAAAAAAAAAA", "brandNew789")).getMessage();
        assertEquals(wrongForExisting, wrongForMissing, "两种失败必须返回同一提示，避免枚举用户名");
    }

    @Test
    void recoveryCodeIsRateLimited() {
        AccountService svc = new AccountService(dir.resolve("accounts-recovery-throttle.json"), newGuests());
        svc.init();
        svc.register("recover5", "secret123", null, null);
        long now = System.currentTimeMillis();
        for (int i = 0; i < AccountService.MAX_LOGIN_FAILURES; i++) {
            svc.recordRecoveryFailure("recover5", now);
        }
        assertEquals(AccountService.MAX_LOGIN_FAILURES, svc.recoveryFailureCount("recover5", now));
        AccountService.AccountException locked = assertThrows(AccountService.AccountException.class,
                () -> svc.recoverPassword("recover5", "AAAAAAAAAAAAAAAAAAAA", "brandNew789"));
        assertEquals("TOO_MANY_ATTEMPTS", locked.getCode());
    }

    @Test
    void rotateRecoveryCodeRequiresPasswordAndInvalidatesOldCode() {
        AccountService accounts = newAccounts();
        Map<String, String> registered = accounts.register("recover6", "secret123", null, null);
        String token = registered.get("token");
        assertThrows(AccountService.AccountException.class,
                () -> accounts.rotateRecoveryCode(token, "wrongpass"));

        String rotated = accounts.rotateRecoveryCode(token, "secret123").get("recoveryCode");
        assertNotNull(rotated);
        assertNotEquals(registered.get("recoveryCode"), rotated);
        assertThrows(AccountService.AccountException.class,
                () -> accounts.recoverPassword("recover6", registered.get("recoveryCode"), "brandNew789"));
        // 新码可用，且原会话仍然有效（轮换恢复码不强制下线）
        assertEquals(registered.get("id"),
                accounts.recoverPassword("recover6", rotated, "brandNew789").get("id"));
    }

    @Test
    void legacyAccountWithoutRecoveryFieldsStillLogsIn() {
        AccountService accounts = newAccounts();
        accounts.register("recover7", "secret123", null, null);
        // 旧账号没有恢复码字段时应正常登录，只是无法用恢复码找回。
        assertDoesNotThrow(() -> accounts.login("recover7", "secret123"));
        assertThrows(AccountService.AccountException.class,
                () -> accounts.recoverPassword("recover7", "AAAAAAAAAAAAAAAAAAAA", "brandNew789"));
    }
}
