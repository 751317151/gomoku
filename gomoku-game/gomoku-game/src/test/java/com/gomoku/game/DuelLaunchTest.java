package com.gomoku.game;

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

/** 好友约战与组队共享入座校验：任何业务拒绝都不能留下房间或移动现有玩家。 */
class DuelLaunchTest {
    private RoomManager rooms;
    private GameRecordService records;
    private final List<EmbeddedChannel> channels = new ArrayList<>();

    @BeforeEach
    void setUp(@TempDir Path dir) {
        records = new GameRecordService(dir.resolve("games"));
        records.init();
        rooms = new RoomManager(new SimpleMeterRegistry(),
                new EloService(dir.resolve("elo.properties"), dir.resolve("legacy.json")), records,
                new SeasonService(records, dir.resolve("seasons.json")), null,
                new PartyService(), new EventService());
    }

    @AfterEach
    void closeChannels() {
        for (EmbeddedChannel channel : channels) {
            rooms.leaveRoom(channel);
            rooms.handleDisconnect(channel);
            channel.finishAndReleaseAll();
        }
        records.shutdown();
    }

    private EmbeddedChannel online(String identity) {
        EmbeddedChannel channel = new EmbeddedChannel();
        channels.add(channel);
        assertTrue(rooms.bindIdentity(channel, identity, identity));
        return channel;
    }

    private void assertNoRoom() {
        assertEquals(0, ((Number) rooms.getStats().get("totalRooms")).intValue());
    }

    @Test
    void invitationValidationDoesNotCreateRoomOrOccupySeats() {
        EmbeddedChannel a = online("a");
        EmbeddedChannel b = online("b");
        rooms.validateDuelPlayers("a", "b", id -> false);
        assertNoRoom();
        assertNull(rooms.getRoomByPlayer(rooms.getPlayer(a)));
        assertNull(rooms.getRoomByPlayer(rooms.getPlayer(b)));
    }

    @Test
    void acceptanceStartsBothPlayersInPrivateRoomWithReconnectSessions() {
        EmbeddedChannel a = online("a");
        EmbeddedChannel b = online("b");
        GameRoom room = rooms.startDuelGame("a", "b", GameType.GOMOKU, id -> false);
        assertEquals(GameRoom.State.PLAYING, room.getState());
        assertTrue(room.isPrivateRoom());
        assertEquals(2, room.getTargetPlayers());
        assertEquals(2, room.getPlayerCount());
        assertSame(room, rooms.getRoomByPlayer(rooms.getPlayer(a)));
        assertSame(room, rooms.getRoomByPlayer(rooms.getPlayer(b)));
        assertNotEquals(rooms.getPlayer(a).getStone(), rooms.getPlayer(b).getStone());
        assertNotNull(rooms.getPlayer(a).getSessionId());
        assertNotNull(rooms.getPlayer(b).getSessionId());
        assertTrue(rooms.getRoomsList().isEmpty());
    }

    @Test
    void multiplayerGameUsesTwoSeatsInsteadOfDefaultFour() {
        online("a"); online("b");
        GameRoom room = rooms.startDuelGame("a", "b", GameType.UNO, id -> false);
        assertEquals(2, room.getTargetPlayers());
        assertEquals(GameRoom.State.PLAYING, room.getState());
    }

    @Test
    void offlineOrBannedPlayerPreventsRoomCreation() {
        online("a");
        assertThrows(DuelService.DuelException.class,
                () -> rooms.startDuelGame("a", "b", GameType.GOMOKU, id -> false));
        online("b");
        DuelService.DuelException banned = assertThrows(DuelService.DuelException.class,
                () -> rooms.startDuelGame("a", "b", GameType.GOMOKU, "b"::equals));
        assertEquals("UNAVAILABLE", banned.getCode());
        assertNoRoom();
    }

    @Test
    void multipleTabsCannotDuplicateOneIdentityIntoTwoSeats() {
        online("a"); online("b"); online("b");
        DuelService.DuelException error = assertThrows(DuelService.DuelException.class,
                () -> rooms.startDuelGame("a", "b", GameType.GOMOKU, id -> false));
        assertTrue(error.getMessage().contains("多个"));
        assertNoRoom();
    }

    @Test
    void occupiedPlayerStaysInOriginalRoomAndOtherPlayerStaysInLobby() {
        EmbeddedChannel a = online("a");
        EmbeddedChannel b = online("b");
        rooms.validateDuelPlayers("a", "b", id -> false);
        GameRoom original = rooms.joinGame(b, "b", null, GameType.GOMOKU, null, null, true);
        assertThrows(DuelService.DuelException.class,
                () -> rooms.startDuelGame("a", "b", GameType.UNO, id -> false));
        assertSame(original, rooms.getRoomByPlayer(rooms.getPlayer(b)));
        assertNull(rooms.getRoomByPlayer(rooms.getPlayer(a)));
        assertEquals(1, ((Number) rooms.getStats().get("totalRooms")).intValue());
    }

    @Test
    void staleDisconnectedSeatIsPreservedOnInviteAndReleasedOnlyOnAcceptance() {
        EmbeddedChannel old = online("a");
        GameRoom oldRoom = rooms.joinGame(old, "a", null, GameType.GOMOKU, null, null, true);
        rooms.handleDisconnect(old);
        old.close();
        EmbeddedChannel a = online("a"); online("b");
        rooms.validateDuelPlayers("a", "b", id -> false);
        assertEquals(1, oldRoom.getPlayerCount(), "发邀请不应清理重连保留席位");
        GameRoom current = rooms.startDuelGame("a", "b", GameType.GOMOKU, id -> false);
        assertEquals(0, oldRoom.getPlayerCount());
        assertSame(current, rooms.getRoomByPlayer(rooms.getPlayer(a)));
        assertEquals(1, ((Number) rooms.getStats().get("totalRooms")).intValue());
    }

    @Test
    void playerWhoLeftRoomKeepsIdentityWhenStartingDuel() {
        EmbeddedChannel a = online("a"); online("b");
        rooms.joinGame(a, "a", null, GameType.GOMOKU, null, null, true);
        rooms.leaveRoom(a);
        GameRoom room = rooms.startDuelGame("a", "b", GameType.CARD, id -> false);
        assertEquals("a", rooms.getPlayer(a).getIdentityId());
        assertEquals("BALANCED", rooms.getPlayer(a).getDeck());
        assertSame(room, rooms.getRoomByPlayer(rooms.getPlayer(a)));
    }

    @Test
    void invalidParticipantsAndMissingTypeLeaveNoState() {
        online("a"); online("b");
        assertThrows(DuelService.DuelException.class,
                () -> rooms.startDuelGame("a", "a", GameType.GOMOKU, id -> false));
        assertThrows(DuelService.DuelException.class,
                () -> rooms.startDuelGame(null, "b", GameType.GOMOKU, id -> false));
        assertThrows(DuelService.DuelException.class,
                () -> rooms.startDuelGame("a", "b", null, id -> false));
        assertNoRoom();
    }
}
