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
}
