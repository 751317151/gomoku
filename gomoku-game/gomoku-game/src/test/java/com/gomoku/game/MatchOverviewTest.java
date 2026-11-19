package com.gomoku.game;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 匹配总览与等待房可匹配性。
 *
 * 重点钉住一条曾经断流的规则：「补了一部分电脑的等待房」必须继续参与快速匹配。
 * AI 没有连接，曾被 findWaitingRoom 当作掉线玩家，导致整间房被跳过。
 */
class MatchOverviewTest {

    private RoomManager manager;

    @BeforeEach
    void setUp() throws Exception {
        Path dir = Files.createTempDirectory("match-overview");
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> game(Map<String, Object> overview, String type) {
        for (Map<String, Object> g : (List<Map<String, Object>>) overview.get("games")) {
            if (type.equals(g.get("gameType"))) return g;
        }
        fail("总览缺少玩法：" + type);
        return null;
    }

    @Test
    void overviewListsEveryGameTypeWithZeroCountsWhenIdle() {
        Map<String, Object> overview = manager.getMatchOverview();

        assertEquals(GameType.values().length, ((List<?>) overview.get("games")).size());
        assertEquals(0, overview.get("waitingRooms"));
        assertEquals(0, overview.get("waitingPlayers"));
        assertEquals(0, overview.get("playingRooms"));
        for (GameType type : GameType.values()) {
            Map<String, Object> g = game(overview, type.name());
            assertEquals(type.getDisplayName(), g.get("name"));
            assertEquals(0, g.get("waitingRooms"));
            assertEquals(0, g.get("waitingPlayers"));
            assertEquals(0, g.get("playingRooms"));
        }
    }

    @Test
    void publicWaitingRoomShowsItsHuman() {
        manager.joinGame(new EmbeddedChannel(), "等待甲", null, GameType.GOMOKU, null, null, false);

        Map<String, Object> overview = manager.getMatchOverview();
        Map<String, Object> gomoku = game(overview, "GOMOKU");
        assertEquals(1, gomoku.get("waitingRooms"));
        assertEquals(1, gomoku.get("waitingPlayers"));
        assertEquals(0, gomoku.get("playingRooms"));
        assertEquals(1, overview.get("onlinePlayers"));
    }

    @Test
    void privateRoomIsNotExposedAsMatchable() {
        manager.joinGame(new EmbeddedChannel(), "私人房主", null, GameType.GOMOKU, null, null, true);

        Map<String, Object> overview = manager.getMatchOverview();
        assertEquals(0, game(overview, "GOMOKU").get("waitingRooms"));
        assertEquals(0, overview.get("waitingPlayers"));
    }

    @Test
    void aiSeatsDoNotCountAsWaitingHumans() {
        EmbeddedChannel channel = new EmbeddedChannel();
        manager.joinGame(channel, "带电脑", null, GameType.HIGH_CARD, null, null, false, 4);
        manager.addAI(channel, 4);

        Map<String, Object> g = game(manager.getMatchOverview(), "HIGH_CARD");
        assertEquals(1, g.get("waitingRooms"), "房间还有空位，应继续对外展示");
        assertEquals(1, g.get("waitingPlayers"), "AI 不计入等待人数");
    }

    @Test
    void waitingRoomWithPartialAiStillAcceptsMatchedHumans() {
        EmbeddedChannel channel = new EmbeddedChannel();
        GameRoom room = manager.joinGame(channel, "带电脑", null,
                GameType.HIGH_CARD, null, null, false, 4);
        manager.addAI(channel, 4);

        GameRoom matched = manager.joinGame(new EmbeddedChannel(), "后来的真人",
                null, GameType.HIGH_CARD, null, null, false, 4);

        assertEquals(room.getRoomId(), matched.getRoomId(),
                "补了一部分电脑的公开等待房仍应参与快速匹配");
        assertEquals(3, matched.getPlayers().size());
    }

    @Test
    void roomWithDisconnectedHumanIsNotOfferedForMatching() {
        EmbeddedChannel channel = new EmbeddedChannel();
        manager.joinGame(channel, "掉线的人", null, GameType.GOMOKU, null, null, false);
        channel.close();

        assertEquals(0, game(manager.getMatchOverview(), "GOMOKU").get("waitingRooms"),
                "宽限期内的掉线房不应被计为可加入");
        GameRoom matched = manager.joinGame(new EmbeddedChannel(), "新人",
                null, GameType.GOMOKU, null, null, false);
        assertEquals(1, matched.getPlayers().size(), "新人应被放进一间新房，而不是掉线者的房");
        assertEquals(GameRoom.State.WAITING, matched.getState());
    }

    @Test
    void playingRoomMovesFromWaitingToPlayingCounts() {
        manager.joinGame(new EmbeddedChannel(), "甲", null, GameType.GOMOKU, null, null, false);
        manager.joinGame(new EmbeddedChannel(), "乙", null, GameType.GOMOKU, null, null, false);

        Map<String, Object> overview = manager.getMatchOverview();
        Map<String, Object> gomoku = game(overview, "GOMOKU");
        assertEquals(0, gomoku.get("waitingRooms"));
        assertEquals(1, gomoku.get("playingRooms"));
        assertEquals(0, overview.get("waitingPlayers"));
    }
}
