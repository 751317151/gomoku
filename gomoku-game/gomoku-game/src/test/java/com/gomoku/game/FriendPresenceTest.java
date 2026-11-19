package com.gomoku.game;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 好友面板的身份在线状态与房间位置查询（RoomManager.getIdentityPresence）。
 *
 * 动机：好友「一键加入」必须拿到对方所在的房间号与玩法；
 * 房号对好友可见是邀请语义 —— 私有房也返回，只有好友能查到。
 */
class FriendPresenceTest {

    private RoomManager manager;

    @BeforeEach
    void setUp() throws Exception {
        Path dir = Files.createTempDirectory("friend-presence");
        EloService elo = new EloService(dir.resolve("elo.properties"), dir.resolve("legacy.json"));
        GameRecordService records = new GameRecordService(dir.resolve("games"));
        records.init();
        GuestIdentityService guests = new GuestIdentityService(dir.resolve("guests.json"));
        guests.init();
        AccountService accounts = new AccountService(dir.resolve("accounts.json"), guests);
        accounts.init();
        FriendService friends = new FriendService(dir.resolve("friends.json"), accounts);
        friends.init();
        manager = new RoomManager(new SimpleMeterRegistry(), elo, records,
                new SeasonService(records, dir.resolve("seasons.json")), friends, new PartyService(),
                new EventService(dir.resolve("events.json")));
    }

    /** 让一个连接以指定持久身份进入大厅 */
    private void bindAndJoin(EmbeddedChannel conn, String name, String identityId, boolean createRoom) {
        assertTrue(manager.bindIdentity(conn, name, identityId));
        GameRoom room = manager.joinGame(conn, name, null, GameType.GOMOKU, null, null, createRoom);
        assertNotNull(room);
    }

    @Test
    void unknownIdentityShowsOfflineOnly() {
        Map<String, Object> presence = manager.getIdentityPresence("guest-nobody");
        assertEquals(Boolean.FALSE, presence.get("online"), "陌生身份只有 offline 字段");
        assertFalse(presence.containsKey("roomId"), "离线身份不该有房间信息");
        assertNull(manager.getIdentityPresence(null).get("name"), "null 身份安全返回");
    }

    @Test
    void waitingRoomIsJoinableByFriend() {
        EmbeddedChannel host = new EmbeddedChannel();
        bindAndJoin(host, "房主甲", "guest-host", true);

        Map<String, Object> presence = manager.getIdentityPresence("guest-host");
        assertEquals(Boolean.TRUE, presence.get("online"), "连着 WS 的身份算在线");
        assertEquals("房主甲", presence.get("name"), "在线身份带实时昵称");
        assertNotNull(presence.get("roomId"), "等待中的房间号对好友可见");
        assertEquals(GameType.GOMOKU.name(), presence.get("gameType"), "好友面板需要玩法类型才能发 JOIN");
        assertEquals(Boolean.TRUE, presence.get("joinable"), "未满员的等待房可以一键加入");
    }

    @Test
    void playingRoomIsNotJoinable() {
        EmbeddedChannel host = new EmbeddedChannel();
        EmbeddedChannel guest = new EmbeddedChannel();
        bindAndJoin(host, "先手", "guest-p1", true);
        // 按房号加入同一间 → 双人局立刻开局
        String roomId = String.valueOf(manager.getIdentityPresence("guest-p1").get("roomId"));
        GameRoom joined = manager.joinGame(guest, "后手", roomId, GameType.GOMOKU, null, null, false);
        assertNotNull(joined);
        assertEquals(GameRoom.State.PLAYING, joined.getState());

        Map<String, Object> presence = manager.getIdentityPresence("guest-p1");
        assertEquals("PLAYING", presence.get("roomState"), "开局后房间状态对好友可见");
        assertEquals(Boolean.FALSE, presence.get("joinable"), "已开局房间不可加入");
    }

    @Test
    void privateRoomStillVisibleToFriendQuery() {
        EmbeddedChannel host = new EmbeddedChannel();
        bindAndJoin(host, "私有房主", "guest-priv", true);

        Map<String, Object> presence = manager.getIdentityPresence("guest-priv");
        assertNotNull(presence.get("roomId"), "私有房对好友也返回房号（邀请语义）");
        assertTrue(manager.getRoomsList().stream()
                        .noneMatch(r -> String.valueOf(presence.get("roomId")).equals(r.getRoomId())),
                "私有房不在公共列表，但好友查询仍可拿到");
    }

    @Test
    void disconnectedIdentityIsOfflineButNamed() {
        EmbeddedChannel host = new EmbeddedChannel();
        bindAndJoin(host, "掉线甲", "guest-drop", true);
        host.close();
        manager.handleDisconnect(host);

        Map<String, Object> presence = manager.getIdentityPresence("guest-drop");
        assertEquals(Boolean.FALSE, presence.get("online"), "断线后不算在线");
        assertEquals("掉线甲", presence.get("name"), "断线宽限期内昵称仍可展示");
    }

    @Test
    void secondTabKeepsIdentityOnline() {
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        bindAndJoin(first, "双开甲", "guest-multi", true);
        first.close();
        manager.handleDisconnect(first);
        // 同一身份换一个连接重新进入（第二个标签页 / 重连）
        assertTrue(manager.bindIdentity(second, "双开甲", "guest-multi"));
        manager.joinGame(second, "双开甲", null, GameType.GOMOKU, null, null, false);

        assertEquals(Boolean.TRUE, manager.getIdentityPresence("guest-multi").get("online"),
                "任一连接在线即算好友在线");
    }

    @Test
    void leavingRoomKeepsHallPresenceOnline() {
        EmbeddedChannel host = new EmbeddedChannel();
        bindAndJoin(host, "回大厅甲", "guest-leave", true);
        // 离开房间回大厅：连接仍然活跃，好友不应看到我离线
        manager.leaveRoom(host);

        Map<String, Object> presence = manager.getIdentityPresence("guest-leave");
        assertEquals(Boolean.TRUE, presence.get("online"),
                "LEAVE 只清房间成员，不清连接级身份——回大厅仍在线");
        assertFalse(presence.containsKey("roomId"), "离开房间后不再有房间位置");
    }

    @Test
    void hallOnlyBindIsOnlineWithoutRoom() {
        // 大厅绑定（HALL_BIND）：只在门口刷大厅、没进过任何房间的人也算在线
        EmbeddedChannel conn = new EmbeddedChannel();
        assertTrue(manager.bindIdentity(conn, "大厅甲", "guest-hall"));

        Map<String, Object> presence = manager.getIdentityPresence("guest-hall");
        assertEquals(Boolean.TRUE, presence.get("online"), "大厅绑定即在线");
        assertEquals("大厅甲", presence.get("name"), "presence 带最近绑定的昵称");
        assertFalse(presence.containsKey("roomId"), "没进房就没有房间位置");

        conn.close();
        manager.handleDisconnect(conn);
        assertEquals(Boolean.FALSE, manager.getIdentityPresence("guest-hall").get("online"),
                "连接断开才算离线");
    }
}
