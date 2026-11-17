package com.gomoku.game;

import com.gomoku.dto.GameSyncDto;
import com.gomoku.dto.PlayerInfoDto;
import com.gomoku.model.Player;
import com.google.gson.JsonParser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RoomLifecycleTest {
    @TempDir Path dir;
    private RoomManager manager;
    private GameRecordService records;
    private final List<EmbeddedChannel> channels = new ArrayList<>();

    @BeforeEach void setUp() {
        records = new GameRecordService(dir.resolve("games"));
        records.init();
        manager = new RoomManager(new SimpleMeterRegistry(),
                new EloService(dir.resolve("elo.properties"), dir.resolve("legacy.json")), records,
                new SeasonService(records, dir.resolve("seasons.json")));
    }

    @AfterEach void close() {
        manager.notifyAllShutdown();
        for (EmbeddedChannel channel : channels) channel.finishAndReleaseAll();
        // 异步写盘线程可能在 JUnit 删除 @TempDir 之后重建 games/ 目录，
        // 导致 Windows 上删除失败。先等待所有待写任务落盘再交还临时目录。
        records.flushForTest();
    }

    private EmbeddedChannel channel() {
        EmbeddedChannel channel = new EmbeddedChannel(); channels.add(channel); return channel;
    }

    private GameRoom create(EmbeddedChannel channel, int count) {
        return manager.joinGame(channel, "玩家", null, GameType.HIGH_CARD, null, null, true, count);
    }

    @Test void reconnectClearsExpiredTimestampAndReplacesOldConnection() {
        EmbeddedChannel original = channel(); GameRoom room = create(original, 3);
        Player old = manager.getPlayer(original);
        manager.handleDisconnect(original);
        @SuppressWarnings("unchecked") Map<String, Long> disconnected =
                (Map<String, Long>) ReflectionTestUtils.getField(manager, "disconnectedAt");
        disconnected.put(old.getId(), System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(11));
        EmbeddedChannel resumed = channel();
        assertSame(room, manager.reconnect(resumed, old.getSessionId()));
        assertFalse(disconnected.containsKey(old.getId()));
        ReflectionTestUtils.invokeMethod(manager, "cleanupIdleRooms");
        assertNotNull(manager.getPlayerBySession(old.getSessionId()));
        assertSame(room, manager.getRoomByPlayer(manager.getPlayer(resumed)));
        assertFalse(original.isActive());
        assertNull(manager.getPlayer(original));
    }

    @Test void boundConnectionCannotStealAnotherSession() {
        EmbeddedChannel first = channel(), second = channel();
        GameRoom firstRoom = create(first, 3), secondRoom = create(second, 3);
        Player owner = manager.getPlayer(first), bound = manager.getPlayer(second);
        assertNull(manager.reconnect(second, owner.getSessionId()));
        assertSame(bound, manager.getPlayer(second));
        assertSame(secondRoom, manager.getRoomByPlayer(bound));
        assertSame(firstRoom, manager.getRoomByPlayer(owner));
        assertTrue(first.isActive());
    }

    @Test void spectatorRefreshKeepsOnePublicSeatAndCanBecomePlayer() {
        EmbeddedChannel owner = channel(), spectator = channel(); GameRoom room = create(owner, 3);
        String code = room.getRoomId().replace("ROOM-", "").toLowerCase();
        assertSame(room, manager.spectateGame(spectator, "观众", code));
        assertSame(room, manager.spectateGame(spectator, "观众", code));
        assertEquals(1, room.getSpectatorCount());
        Player old = manager.getPlayer(spectator);
        manager.handleDisconnect(spectator);
        EmbeddedChannel replacement = channel();
        assertSame(room, manager.reconnect(replacement, old.getSessionId()));
        Player resumed = manager.getPlayer(replacement);
        assertEquals(0, resumed.getStone());
        assertEquals(1, room.getSpectatorCount());
        assertSame(resumed, room.getSpectators().get(0));
        assertFalse(room.handleCommand(resumed, MoveCommand.action(10), -1));
        assertSame(room, manager.joinGame(replacement, "观众", code, GameType.HIGH_CARD));
        assertEquals(0, room.getSpectatorCount());
        assertEquals(2, room.getPlayerCount());
        assertNull(manager.spectateGame(replacement, "观众", code));
    }

    @Test void switchingRoomsPreservesArchivedSeatAndDoesNotLeakPrivateHand() {
        EmbeddedChannel first = channel(), second = channel(), third = channel(), other = channel();
        GameRoom original = create(first, 3);
        manager.joinGame(second, "二号", original.getRoomId(), GameType.HIGH_CARD);
        manager.joinGame(third, "三号", original.getRoomId(), GameType.HIGH_CARD);
        Player historical = manager.getPlayer(third);
        GameRoom target = create(other, 3);
        assertSame(target, manager.spectateGame(third, "三号", target.getRoomId()));
        Player current = manager.getPlayer(third);
        assertNotSame(historical, current);
        assertEquals(3, historical.getStone()); assertEquals(0, current.getStone());
        assertFalse(original.hasPlayer(current));
        String publicView = new com.google.gson.Gson().toJson(original.buildSyncDataForReconnect(current));
        assertFalse(publicView.contains("\"hand\""));
        original.surrender(manager.getPlayer(first));
        records.flushForTest();
        GameRecord archived = records.getRecentGames(1).get(0);
        assertEquals(3, archived.getParticipants().size());
        assertEquals(3, archived.getParticipants().stream().filter(p -> p.getPlayerId().equals(historical.getId()))
                .findFirst().orElseThrow().getSide());
        assertSame(target, manager.getRoomByPlayer(current));
    }

    @Test void leavingSoloRoomStopsAiGameAndRemovesRoom() {
        EmbeddedChannel human = channel(); GameRoom room = create(human, 8);
        for (int i = 0; i < 7; i++) manager.addAI(human);
        assertEquals(GameRoom.State.PLAYING, room.getState());
        manager.leaveRoom(human);
        assertEquals(0, room.getPlayerCount());
        assertEquals(GameRoom.State.WAITING, room.getState());
        assertEquals(0, ((Number) manager.getStats().get("totalRooms")).intValue());
    }

    @Test void syncRestoresAuthoritativeSequenceTimersAndFinishedResult() {
        EmbeddedChannel first = channel(), second = channel();
        GameRoom room = manager.joinGame(first, "甲", null, GameType.GO9, null, null, true);
        manager.joinGame(second, "乙", room.getRoomId(), GameType.GO9);
        Player a = manager.getPlayer(first), b = manager.getPlayer(second);
        assertTrue(room.handlePass(a));
        GameSyncDto playing = room.buildSyncDataForReconnect(a);
        assertEquals(1, playing.getMoveCount()); assertEquals(1, playing.getMoveSeq());
        assertNotNull(playing.getTimers()); assertNull(playing.getGameOver());
        assertTrue(room.handlePass(b));
        GameSyncDto finished = room.buildSyncDataForReconnect(a);
        assertNotNull(finished.getGameOver());
        assertEquals(2, finished.getMoveCount());
        assertTrue(JsonParser.parseString(finished.getGameOver().getData()).getAsJsonObject().has("winningSides"));
    }

    @Test void roomInfoShowsSeasonTierForPersistentIdentity() {
        // 本月四场胜利 = 12 赛季分 → 白银（阶梯 10 分起）
        for (int i = 0; i < 4; i++) {
            GameRecord win = new GameRecord();
            win.setRoomId("ROOM-TIER" + i);
            win.setGameType("HIGH_CARD");
            win.setEndedAt(System.currentTimeMillis() - i * 1000L);
            GameRecord.Participant winner = new GameRecord.Participant(
                    "p-tier" + i, "段位玩家", 1, "WIN", false, 1200, 1200, 0);
            winner.setIdentityId("tier-guest");
            win.getParticipants().add(winner);
            records.saveAsync(win);
        }
        records.flushForTest();
        GameRecord rec = new GameRecord();
        rec.setRoomId("ROOM-TIER");
        rec.setGameType("HIGH_CARD");
        rec.setEndedAt(System.currentTimeMillis());
        GameRecord.Participant me = new GameRecord.Participant("p-tier", "段位玩家", 1, "WIN", false, 1200, 1200, 0);
        me.setIdentityId("tier-guest");
        rec.getParticipants().add(me);
        records.saveAsync(rec);
        records.flushForTest();

        GameRoom room = create(channel(), 2);
        io.netty.channel.embedded.EmbeddedChannel bound = channel();
        assertTrue(manager.bindIdentity(bound, "段位玩家", "tier-guest"));
        assertSame(room, manager.joinGame(bound, "段位玩家",
                room.getRoomId().replace("ROOM-", ""), GameType.HIGH_CARD));

        Player joined = manager.getPlayer(bound);
        GameSyncDto sync = room.buildSyncDataForReconnect(joined);
        PlayerInfoDto dto = sync.getRoomData().getPlayers().stream()
                .filter(p -> "段位玩家".equals(p.getName())).findFirst().orElseThrow();
        assertEquals("白银", dto.getTier(), "座位信息应携带赛季段位徽章");
    }
}
