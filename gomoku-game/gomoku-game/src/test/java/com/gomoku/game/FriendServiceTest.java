package com.gomoku.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 好友系统单测：好友码、按用户名/好友码申请、同意/拒绝/撤回/删除、上限与持久化。 */
class FriendServiceTest {
    @TempDir Path dir;

    /** 与 FriendService 共用的账号服务（按用户名加好友必须查同一个内存实例） */
    private AccountService accounts;

    private FriendService newFriends() {
        GuestIdentityService guests = new GuestIdentityService(dir.resolve("guests.json"));
        guests.init();
        accounts = new AccountService(dir.resolve("accounts.json"), guests);
        accounts.init();
        FriendService friends = new FriendService(dir.resolve("friends.json"), accounts);
        friends.init();
        return friends;
    }

    /** 注册一个账号身份，返回 identityId（用户名加好友用） */
    private String registerAccount(String username) {
        return accounts.register(username, "secret123", null, null).get("id");
    }

    @Test
    void friendCodeIsStableAndWellFormed() {
        FriendService friends = newFriends();
        String id = registerAccount("alice");
        String code = friends.friendCodeOf(id);
        assertEquals(8, code.length(), "好友码应为 8 位");
        assertTrue(code.matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}"), "好友码只含去混淆字符集");
        assertEquals(code, friends.friendCodeOf(id), "同一身份的好友码必须稳定");
        assertTrue(code.matches(".*[IO01].*") == false, "好友码不应包含易混淆字符 I/O/0/1");
    }

    @Test
    void codePersistsAndSurvivesReload() throws Exception {
        FriendService friends = newFriends();
        String id = "guest-test-1";
        String code = friends.friendCodeOf(id);
        assertTrue(Files.exists(dir.resolve("friends.json")), "好友码应落盘");

        FriendService reloaded = newFriends();
        assertEquals(code, reloaded.friendCodeOf(id), "重启后同一身份拿回同一好友码");
        assertEquals(id, reloaded.identityOfCode(code), "重启后好友码仍能反查身份");
    }

    @Test
    void resolveTargetByCodeIgnoreCaseAndSpaces() {
        FriendService friends = newFriends();
        String id = "guest-test-2";
        String code = friends.friendCodeOf(id);
        assertEquals(id, friends.resolveTarget(code), "好友码原样解析");
        assertEquals(id, friends.resolveTarget(code.toLowerCase()), "好友码大小写不敏感");
        assertEquals(id, friends.resolveTarget(" " + code + " "), "好友码容忍首尾空白");
        // 找一个确定未被分配的合法码：两个候选不可能同时等于已分配的 code
        String unassigned = code.equals("A2345678") ? "B2345678" : "A2345678";
        assertNull(friends.resolveTarget(unassigned), "未分配的好友码解析为空");
    }

    @Test
    void resolveTargetByUsername() {
        FriendService friends = newFriends();
        String id = registerAccount("bob");
        assertEquals(id, friends.resolveTarget("bob"), "账号可用用户名解析");
        assertEquals(id, friends.resolveTarget("BOB"), "用户名大小写不敏感");
        assertNull(friends.resolveTarget("nobodyhere"), "不存在的用户名解析为空");
    }

    @Test
    void requestAcceptCreatesMutualFriendship() {
        FriendService friends = newFriends();
        String a = registerAccount("carol");
        String b = "guest-friend-b";
        friends.friendCodeOf(b);

        friends.request(a, b);
        assertEquals(List.of(b), friends.outgoingOf(a), "申请进入发起方 outgoing");
        assertEquals(List.of(a), friends.incomingOf(b), "申请进入接收方 incoming");

        friends.accept(b, a);
        assertEquals(List.of(b), friends.friendsOf(a), "同意后发起方有好友");
        assertEquals(List.of(a), friends.friendsOf(b), "同意后接收方有好友");
        assertTrue(friends.incomingOf(b).isEmpty(), "同意后申请清单清空");
        assertTrue(friends.outgoingOf(a).isEmpty(), "同意后申请清单清空");
    }

    @Test
    void cannotRequestSelfOrUnknownIdentity() {
        FriendService friends = newFriends();
        String a = "guest-self";
        friends.friendCodeOf(a);
        FriendService.FriendException self = assertThrows(FriendService.FriendException.class,
                () -> friends.request(a, a));
        assertEquals("INVALID", self.getCode(), "不能添加自己");

        FriendService.FriendException unknown = assertThrows(FriendService.FriendException.class,
                () -> friends.request(a, "guest-never-seen"));
        assertEquals("NOT_FOUND", unknown.getCode(), "不能向凭空身份发申请");
    }

    @Test
    void duplicateAndReverseRequestsAreRejected() {
        FriendService friends = newFriends();
        String a = "guest-dup-a";
        String b = "guest-dup-b";
        friends.friendCodeOf(a);
        friends.friendCodeOf(b);

        friends.request(a, b);
        FriendService.FriendException dup = assertThrows(FriendService.FriendException.class,
                () -> friends.request(a, b));
        assertEquals("CONFLICT", dup.getCode(), "重复申请被拒");

        FriendService.FriendException reverse = assertThrows(FriendService.FriendException.class,
                () -> friends.request(b, a));
        assertEquals("CONFLICT", reverse.getCode(), "对方已向我申请时应直接同意而不是重复申请");
    }

    @Test
    void declineRemovesRequestAndAllowsReRequest() {
        FriendService friends = newFriends();
        String a = "guest-dec-a";
        String b = "guest-dec-b";
        friends.friendCodeOf(a);
        friends.friendCodeOf(b);

        friends.request(a, b);
        friends.decline(b, a);
        assertTrue(friends.incomingOf(b).isEmpty(), "拒绝后申请消失");
        assertTrue(friends.outgoingOf(a).isEmpty(), "拒绝后对方 outgoing 同步清空");

        friends.request(a, b);
        assertEquals(List.of(b), friends.outgoingOf(a), "拒绝后可以重新申请");
    }

    @Test
    void cancelWithdrawsOutgoingRequest() {
        FriendService friends = newFriends();
        String a = "guest-can-a";
        String b = "guest-can-b";
        friends.friendCodeOf(a);
        friends.friendCodeOf(b);

        friends.request(a, b);
        friends.cancel(a, b);
        assertTrue(friends.outgoingOf(a).isEmpty(), "撤回后 outgoing 清空");
        assertTrue(friends.incomingOf(b).isEmpty(), "撤回后对方 incoming 同步清空");
    }

    @Test
    void removeDeletesBothSides() {
        FriendService friends = newFriends();
        String a = "guest-rm-a";
        String b = "guest-rm-b";
        friends.friendCodeOf(a);
        friends.friendCodeOf(b);
        friends.request(a, b);
        friends.accept(b, a);

        friends.remove(a, b);
        assertTrue(friends.friendsOf(a).isEmpty(), "删除后发起方无好友");
        assertTrue(friends.friendsOf(b).isEmpty(), "删除后另一方也无好友");
    }

    @Test
    void friendshipSurvivesReload() throws Exception {
        FriendService friends = newFriends();
        String a = "guest-pers-a";
        String b = "guest-pers-b";
        friends.friendCodeOf(a);
        friends.friendCodeOf(b);
        friends.request(a, b);
        friends.accept(b, a);

        FriendService reloaded = newFriends();
        assertEquals(List.of(b), reloaded.friendsOf(a), "重启后好友关系保留");
        assertEquals(List.of(a), reloaded.friendsOf(b), "重启后好友关系保留");
    }

    @Test
    void outgoingPendingLimitEnforced() {
        FriendService friends = newFriends();
        String a = "guest-limit-a";
        friends.friendCodeOf(a);
        for (int i = 0; i < FriendService.MAX_PENDING; i++) {
            String other = "guest-limit-t" + i;
            friends.friendCodeOf(other);
            friends.request(a, other);
        }
        String extra = "guest-limit-extra";
        friends.friendCodeOf(extra);
        FriendService.FriendException ex = assertThrows(FriendService.FriendException.class,
                () -> friends.request(a, extra));
        assertEquals("LIMIT", ex.getCode(), "待处理申请达到上限后拒绝新申请");
    }

    @Test
    void friendCapRejectsRequestWhenTargetFull() {
        FriendService friends = newFriends();
        String a = "guest-cap-a";
        String b = "guest-cap-b";
        friends.friendCodeOf(a);
        friends.friendCodeOf(b);
        // a 用 50 个陪练身份把好友列表填满
        for (int i = 0; i < FriendService.MAX_FRIENDS; i++) {
            String filler = "guest-cap-f" + i;
            friends.friendCodeOf(filler);
            friends.request(a, filler);
            friends.accept(filler, a);
        }
        FriendService.FriendException ex = assertThrows(FriendService.FriendException.class,
                () -> friends.request(b, a));
        assertEquals("LIMIT", ex.getCode(), "目标好友满员后直接拒绝新申请");
    }

    @Test
    void diskNeverStoresNicknamesOrTokens() throws Exception {
        FriendService friends = newFriends();
        String id = registerAccount("carol");
        friends.friendCodeOf(id);
        String disk = Files.readString(dir.resolve("friends.json"));
        assertFalse(disk.contains("carol"), "好友文件只存身份与关系，不存用户名");
        assertTrue(disk.contains("codes") && disk.contains("graph"), "应包含好友码目录与好友图");
    }

    @Test
    void areFriendsFollowsGraphLifecycle() {
        FriendService friends = newFriends();
        String a = "guest-are-a";
        String b = "guest-are-b";
        friends.friendCodeOf(a);
        friends.friendCodeOf(b);
        assertFalse(friends.areFriends(a, b), "陌生人不是好友");
        assertFalse(friends.areFriends(a, a), "自己不算自己的好友");
        friends.request(a, b);
        assertFalse(friends.areFriends(a, b), "仅申请未同意还不是好友");
        friends.accept(b, a);
        assertTrue(friends.areFriends(a, b), "同意后是好友");
        assertTrue(friends.areFriends(b, a), "好友关系对称");
        friends.remove(a, b);
        assertFalse(friends.areFriends(a, b), "删除后不再是好友");
    }
}
