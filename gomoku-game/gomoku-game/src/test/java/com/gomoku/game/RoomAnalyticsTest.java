package com.gomoku.game;

import com.gomoku.model.Player;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对局边界到产品漏斗的接线测试。
 *
 * 重点不是统计服务自身算法，而是服务端真实调用顺序：
 * - 第二人补位会立刻开局，必须仍记成自己的入口（CODE），不能回落成 PARTY；
 * - 人机局原班人马再开也要产生 REMATCH；
 * - 再来一局只记开局，不重复计入匹配等待样本。
 */
class RoomAnalyticsTest {

    @TempDir Path dir;

    private RoomManager manager;
    private RecordingAnalytics analytics;
    private GameRecordService records;
    private final List<EmbeddedChannel> channels = new ArrayList<>();

    @BeforeEach
    void setUp() {
        records = new GameRecordService(dir.resolve("games"));
        records.init();
        GuestIdentityService guests = new GuestIdentityService(dir.resolve("guests.json"));
        guests.init();
        AccountService accounts = new AccountService(dir.resolve("accounts.json"), guests);
        accounts.init();
        FriendService friends = new FriendService(dir.resolve("friends.json"), accounts);
        friends.init();
        EventService events = new EventService(dir.resolve("events.json"));
        events.init();
        analytics = new RecordingAnalytics(dir.resolve("analytics"));
        manager = new RoomManager(new SimpleMeterRegistry(),
                new EloService(dir.resolve("elo.properties"), dir.resolve("legacy.json")), records,
                new SeasonService(records, dir.resolve("seasons.json")), friends, new PartyService(),
                events, analytics);
    }

    @AfterEach
    void tearDown() {
        manager.notifyAllShutdown();
        for (EmbeddedChannel channel : channels) channel.finishAndReleaseAll();
        records.flushForTest();
    }

    private EmbeddedChannel channel() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channels.add(channel);
        return channel;
    }

    @Test
    void secondPlayerKeepsCodeModeWhenJoiningStartsGameImmediately() {
        EmbeddedChannel host = channel(), guest = channel();
        assertTrue(manager.bindIdentity(host, "房主", "guest-host"));
        assertTrue(manager.bindIdentity(guest, "来客", "guest-guest"));

        GameRoom room = manager.joinGame(host, "房主", null, GameType.GOMOKU,
                null, null, false, 2, "QUICK");
        assertNotNull(room);
        assertSame(room, manager.joinGame(guest, "来客", room.getRoomId(), GameType.GOMOKU,
                null, null, false, 2, "CODE"));
        assertEquals(GameRoom.State.PLAYING, room.getState());

        assertTrue(analytics.modeSelects.contains("guest-guest:CODE"),
                "第二人入座事件必须带自己的入口：" + analytics.modeSelects);
        assertTrue(analytics.starts.stream()
                        .anyMatch(s -> s.identity.equals("guest-guest") && s.mode.equals("CODE") && s.waitMs >= 0),
                "即时开局也要记成 CODE：" + analytics.starts);
    }

    @Test
    void rematchCountsAgainButDoesNotPolluteWaitSamples() {
        EmbeddedChannel host = channel(), guest = channel();
        assertTrue(manager.bindIdentity(host, "甲", "guest-a"));
        assertTrue(manager.bindIdentity(guest, "乙", "guest-b"));
        GameRoom room = manager.joinGame(host, "甲", null, GameType.GOMOKU,
                null, null, false, 2, "QUICK");
        manager.joinGame(guest, "乙", room.getRoomId(), GameType.GOMOKU, null, null, false, 2, "CODE");
        room.surrender(manager.getPlayer(host));

        assertTrue(analytics.finishes.contains("guest-b:CODE"));
        room.requestRestart(manager.getPlayer(host));
        room.requestRestart(manager.getPlayer(guest));
        assertEquals(GameRoom.State.PLAYING, room.getState());

        assertTrue(analytics.rematches.contains("guest-b:CODE"));
        assertTrue(analytics.starts.stream()
                        .anyMatch(s -> s.identity.equals("guest-b") && s.mode.equals("CODE") && s.waitMs < 0),
                "再来一局应记开局但不重复计等待：" + analytics.starts);
        assertEquals(2, analytics.starts.stream()
                .filter(s -> s.identity.equals("guest-b") && s.mode.equals("CODE")).count());
    }

    @Test
    void aiRoomRematchIsRecognizedAsSameRoster() {
        EmbeddedChannel solo = channel();
        assertTrue(manager.bindIdentity(solo, "练习者", "guest-ai"));
        GameRoom room = manager.joinGame(solo, "练习者", null, GameType.GOMOKU,
                null, null, true, 2, "AI");
        manager.addAI(solo, 4);
        assertEquals(GameRoom.State.PLAYING, room.getState());

        room.surrender(manager.getPlayer(solo));
        assertTrue(analytics.finishes.contains("guest-ai:AI"));
        room.requestRestart(manager.getPlayer(solo));

        assertEquals(GameRoom.State.PLAYING, room.getState());
        assertTrue(analytics.rematches.contains("guest-ai:AI"),
                "AI 座位也要参与原班人马判定：" + analytics.rematches);
    }

    @Test
    void delayedFinishFromOldRoomDoesNotLeakIntoNewEntry() {
        EmbeddedChannel host = channel(), guest = channel();
        assertTrue(manager.bindIdentity(host, "甲", "guest-a"));
        assertTrue(manager.bindIdentity(guest, "乙", "guest-b"));
        GameRoom oldRoom = manager.joinGame(host, "甲", null, GameType.GOMOKU,
                null, null, false, 2, "QUICK");
        manager.joinGame(guest, "乙", oldRoom.getRoomId(), GameType.GOMOKU,
                null, null, false, 2, "CODE");
        assertEquals(GameRoom.State.PLAYING, oldRoom.getState());

        // 切房会先让旧局判负终局，再登记新入口；旧房的延迟/重复回调不得落到新入口上。
        GameRoom newRoom = manager.joinGame(host, "甲", null, GameType.GOMOKU,
                null, null, true, 2, "PRIVATE");
        assertNotNull(newRoom);
        assertNotSame(oldRoom, newRoom);

        // 模拟旧房间回调仍持有切换前的玩家快照（真实延迟/并发回调读到的就是旧视图）。
        Player staleHost = new Player(manager.getPlayer(host).getId(), "甲", null);
        staleHost.setIdentityId("guest-a");
        GameRoom staleView = new GameRoom(oldRoom.getRoomId(), GameType.GOMOKU, false, 2) {
            @Override
            public List<Player> getPlayers() {
                return List.of(staleHost);
            }
        };
        manager.onGameFinished(staleView);

        assertEquals(1, count(analytics.finishes, "guest-a:QUICK"),
                "旧房终局只应记一次原入口：" + analytics.finishes);
        assertFalse(analytics.finishes.contains("guest-a:PRIVATE"),
                "旧房终局不得记成新入口：" + analytics.finishes);
    }

    @Test
    void duplicateFinishCallbackOnlyCountsOnce() {
        EmbeddedChannel host = channel(), guest = channel();
        assertTrue(manager.bindIdentity(host, "甲", "guest-a"));
        assertTrue(manager.bindIdentity(guest, "乙", "guest-b"));
        GameRoom room = manager.joinGame(host, "甲", null, GameType.GOMOKU,
                null, null, false, 2, "QUICK");
        manager.joinGame(guest, "乙", room.getRoomId(), GameType.GOMOKU,
                null, null, false, 2, "CODE");

        manager.onGameFinished(room);
        manager.onGameFinished(room);

        assertEquals(1, count(analytics.finishes, "guest-a:QUICK"));
        assertEquals(1, count(analytics.finishes, "guest-b:CODE"));
    }

    @Test
    void rematchResetsFinishGuardAndCountsSecondFinish() {
        EmbeddedChannel host = channel(), guest = channel();
        assertTrue(manager.bindIdentity(host, "甲", "guest-a"));
        assertTrue(manager.bindIdentity(guest, "乙", "guest-b"));
        GameRoom room = manager.joinGame(host, "甲", null, GameType.GOMOKU,
                null, null, false, 2, "QUICK");
        manager.joinGame(guest, "乙", room.getRoomId(), GameType.GOMOKU,
                null, null, false, 2, "CODE");

        room.surrender(manager.getPlayer(host));
        assertEquals(1, count(analytics.finishes, "guest-b:CODE"));

        room.requestRestart(manager.getPlayer(host));
        room.requestRestart(manager.getPlayer(guest));
        assertEquals(GameRoom.State.PLAYING, room.getState());
        assertEquals(1, count(analytics.rematches, "guest-b:CODE"));

        // 第二次真实终局必须能记录，证明新局开始时终局幂等标记已复位。
        room.surrender(manager.getPlayer(host));
        assertEquals(2, count(analytics.finishes, "guest-b:CODE"));

        // 第二局结束后的重复回调仍不能重复计数。
        manager.onGameFinished(room);
        assertEquals(2, count(analytics.finishes, "guest-b:CODE"));
        assertEquals(1, count(analytics.rematches, "guest-b:CODE"));
    }

    private static long count(List<String> values, String expected) {
        return values.stream().filter(expected::equals).count();
    }

    private static final class RecordingAnalytics extends ProductAnalyticsService {
        final List<String> modeSelects = new ArrayList<>();
        final List<String> finishes = new ArrayList<>();
        final List<String> rematches = new ArrayList<>();
        final List<Start> starts = new ArrayList<>();

        RecordingAnalytics(Path dir) {
            super(dir);
        }

        @Override
        public void recordModeSelect(String identityId, String mode) {
            modeSelects.add(identityId + ":" + mode);
        }

        @Override
        public void recordGameStart(String identityId, String mode, long waitMs) {
            starts.add(new Start(identityId, mode, waitMs));
        }

        @Override
        public void recordGameFinish(String identityId, String mode) {
            finishes.add(identityId + ":" + mode);
        }

        @Override
        public void recordRematch(String identityId, String mode) {
            rematches.add(identityId + ":" + mode);
        }
    }

    private static final class Start {
        final String identity;
        final String mode;
        final long waitMs;

        Start(String identity, String mode, long waitMs) {
            this.identity = identity;
            this.mode = mode;
            this.waitMs = waitMs;
        }

        @Override
        public String toString() {
            return identity + ":" + mode + ":" + waitMs;
        }
    }
}
