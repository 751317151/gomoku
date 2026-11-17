package com.gomoku.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
        accounts.changePassword(token, "secret123", "newsecret456");
        assertThrows(AccountService.AccountException.class,
                () -> accounts.login("grace", "secret123"));
        assertEquals(registered.get("id"), accounts.login("grace", "newsecret456").get("id"),
                "改密后身份不变");
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
}
