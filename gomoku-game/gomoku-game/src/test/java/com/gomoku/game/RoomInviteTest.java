package com.gomoku.game;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 房号邀请（建房 / 按房号加入）
 *
 * 动机：之前大厅的「创建房间」其实只是普通 JOIN，会把我匹配进别人的等待房间；
 * 而显式房号找不到时会静默回落到自动匹配，等于「输错房号被塞进陌生人的房间」。
 * 这里把这两条行为钉死。
 */
class RoomInviteTest {

    private RoomManager manager;

    @BeforeEach
    void setUp() throws Exception {
        Path dir = Files.createTempDirectory("room-invite");
        EloService elo = new EloService(dir.resolve("elo.properties"), dir.resolve("legacy.json"));
        GameRecordService records = new GameRecordService(dir.resolve("games"));
        records.init();
        manager = new RoomManager(new SimpleMeterRegistry(), elo, records);
    }

    @Test
    void normalizeAcceptsAllCommonForms() {
        assertEquals("ROOM-ABC123", RoomManager.normalizeRoomId("abc123"));
        assertEquals("ROOM-ABC123", RoomManager.normalizeRoomId("  ABC123 "));
        assertEquals("ROOM-ABC123", RoomManager.normalizeRoomId("room-abc123"));
        assertNull(RoomManager.normalizeRoomId(null));
        assertNull(RoomManager.normalizeRoomId("   "));
    }

    @Test
    void createRoomNeverMatchesAnExistingWaitingRoom() {
        GameRoom first = manager.joinGame(new EmbeddedChannel(), "建房甲",
                null, GameType.GOMOKU, null, null, true);
        GameRoom second = manager.joinGame(new EmbeddedChannel(), "建房乙",
                null, GameType.GOMOKU, null, null, true);

        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first.getRoomId(), second.getRoomId(),
                "createRoom 必须新建房间，不能匹配进别人的等待房间");
        assertEquals(GameRoom.State.WAITING, first.getState());
        assertEquals(GameRoom.State.WAITING, second.getState());
    }

    @Test
    void autoMatchStillWorksWithoutCreateFlag() {
        GameRoom first = manager.joinGame(new EmbeddedChannel(), "匹配甲",
                null, GameType.GOMOKU, null, null, false);
        GameRoom second = manager.joinGame(new EmbeddedChannel(), "匹配乙",
                null, GameType.GOMOKU, null, null, false);

        assertEquals(first.getRoomId(), second.getRoomId(), "不带 createRoom 时应自动匹配同一房间");
        assertEquals(GameRoom.State.PLAYING, first.getState());
    }

    @Test
    void joinByCodeNormalizesCaseAndStartsGame() {
        GameRoom created = manager.joinGame(new EmbeddedChannel(), "房主",
                null, GameType.GOMOKU, null, null, true);
        String code = created.getRoomId().replace("ROOM-", "").toLowerCase();

        GameRoom joined = manager.joinGame(new EmbeddedChannel(), "好友",
                code, GameType.GOMOKU, null, null, false);

        assertNotNull(joined, "小写且不带前缀的房号也应能加入");
        assertEquals(created.getRoomId(), joined.getRoomId());
        assertEquals(GameRoom.State.PLAYING, created.getState());
    }

    @Test
    void unknownRoomCodeFailsInsteadOfAutoMatching() {
        // 先制造一个等待房间，若实现回落匹配，就会被错误地塞进去
        manager.joinGame(new EmbeddedChannel(), "等待者",
                null, GameType.GOMOKU, null, null, true);

        GameRoom room = manager.joinGame(new EmbeddedChannel(), "输错房号",
                "ROOM-ZZZZZZ", GameType.GOMOKU, null, null, false);

        assertNull(room, "房号不存在必须失败，不能回落到自动匹配");
    }

    @Test
    void cannotJoinRoomThatIsAlreadyPlaying() {
        GameRoom created = manager.joinGame(new EmbeddedChannel(), "先手",
                null, GameType.GOMOKU, null, null, true);
        manager.joinGame(new EmbeddedChannel(), "后手",
                created.getRoomId(), GameType.GOMOKU, null, null, false);
        assertEquals(GameRoom.State.PLAYING, created.getState());

        GameRoom third = manager.joinGame(new EmbeddedChannel(), "迟到者",
                created.getRoomId(), GameType.GOMOKU, null, null, false);

        assertNull(third, "已开局的房间不能再按房号加入");
    }

    @Test
    void privateRoomIsHiddenFromPublicList() {
        // 普通匹配房（非 createRoom）应出现在大厅列表
        manager.joinGame(new EmbeddedChannel(), "公开房主",
                null, GameType.GOMOKU, null, null, false);
        int before = manager.getRoomsList().size();

        // 建房拿到的私有房不应出现在大厅列表
        GameRoom priv = manager.joinGame(new EmbeddedChannel(), "私有房主",
                null, GameType.GOMOKU, null, null, true);
        assertNotNull(priv);
        assertTrue(priv.isPrivateRoom(), "createRoom 建出的应是私有房");
        assertEquals(before, manager.getRoomsList().size(),
                "私有房不应让公共房间列表变长");
        assertTrue(manager.getRoomsList().stream()
                        .noneMatch(r -> r.getRoomId().equals(priv.getRoomId())),
                "列表里不该出现私有房的房号");
    }

    @Test
    void privateRoomIsNotAutoMatched() {
        // 先建一个私有房；随后自动匹配的人不应被塞进去
        GameRoom priv = manager.joinGame(new EmbeddedChannel(), "私有房主",
                null, GameType.GOMOKU, null, null, true);

        GameRoom matched = manager.joinGame(new EmbeddedChannel(), "路人",
                null, GameType.GOMOKU, null, null, false);

        assertNotEquals(priv.getRoomId(), matched.getRoomId(),
                "私有房不能被自动匹配进陌生人");
        assertEquals(GameRoom.State.WAITING, priv.getState(), "私有房应仍在等人");
    }

    @Test
    void cannotJoinWithMismatchedGameType() {
        GameRoom created = manager.joinGame(new EmbeddedChannel(), "五子棋房主",
                null, GameType.GOMOKU, null, null, true);

        GameRoom wrong = manager.joinGame(new EmbeddedChannel(), "围棋玩家",
                created.getRoomId(), GameType.GO9, null, null, false);

        assertNull(wrong, "房号对应玩法不同时应拒绝");
    }
}
