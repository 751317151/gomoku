package com.gomoku.game;

import com.gomoku.model.Player;
import com.google.gson.Gson;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MultiplayerRoomTest {
    @TempDir Path dir;
    private Player player(int index) { return new Player("p" + index, "玩家" + index, new EmbeddedChannel()); }
    private void clear(GameRoom room) {
        room.notifyShutdown();
        for (Player p : room.getPlayers()) ((EmbeddedChannel) p.getConnection()).finishAndReleaseAll();
    }
    @Test void capacitiesAndRealGamesFromTwoToEight() {
        for (int count = 2; count <= 8; count++) {
            GameRoom room = new GameRoom("ROOM-TEST" + count, GameType.HIGH_CARD, true, count);
            try {
                for (int index = 1; index <= count; index++) {
                    assertTrue(room.addPlayer(player(index)));
                    assertEquals(index == count ? GameRoom.State.PLAYING : GameRoom.State.WAITING, room.getState());
                }
                assertTrue(room.isFull()); assertFalse(room.addPlayer(player(99)));
                assertEquals(count, room.getPlayers().stream().map(Player::getStone).distinct().count());
                // 提交顺序刻意逆序，证明同时暗选无需等待前一座位。
                for (int round = 1; round <= 5; round++) for (int side = count; side >= 1; side--)
                    assertTrue(room.handleCommand(room.getPlayers().get(side - 1), MoveCommand.action(round), -1));
                assertEquals(GameRoom.State.FINISHED, room.getState());
                assertEquals(count, room.getEngine().getWinningSides().size());
                room.requestRestart(room.getPlayers().get(0));
                if (count > 2) { room.requestRestart(room.getPlayers().get(0)); assertEquals(GameRoom.State.FINISHED, room.getState()); }
                for (int i = 1; i < count; i++) room.requestRestart(room.getPlayers().get(i));
                assertEquals(GameRoom.State.PLAYING, room.getState());
                assertEquals(2, room.getPlayers().get(0).getStone());
            } finally { clear(room); }
        }
        assertThrows(IllegalArgumentException.class, () -> new GameRoom("bad", GameType.GOMOKU, true, 3));
        assertThrows(IllegalArgumentException.class, () -> new GameRoom("bad", GameType.HIGH_CARD, true, 9));
    }
    @Test void privateChoicesAndHandsNeverReachOpponentOrSpectator() {
        HighCardEngine engine = new HighCardEngine(); engine.configurePlayers(8); engine.reset();
        assertTrue(engine.applyMove(MoveCommand.action(9), 8));
        assertFalse(engine.applyMove(MoveCommand.action(8), 8));
        Gson gson = new Gson();
        String observer = gson.toJson(engine.getViewState(0));
        assertFalse(observer.contains("\"choice\"")); assertFalse(observer.contains("\"hand\""));
        Map<?, ?> owner = (Map<?, ?>) engine.getViewState(8);
        List<?> players = (List<?>) owner.get("players");
        assertEquals(9, ((Map<?, ?>) players.get(7)).get("choice"));
        assertFalse(((Map<?, ?>) players.get(0)).containsKey("hand"));
        for (int i = 1; i < 8; i++) assertTrue(engine.applyMove(MoveCommand.action(i), i));
        assertEquals(2, ((Map<?, ?>) engine.getViewState(0)).get("round"));
        assertTrue(gson.toJson(engine.getViewState(0)).contains("lastChoice"));
    }
    @Test void leaveEliminatesOnlyThatSeatAndArchivesAllParticipants() {
        GameRecordService records = new GameRecordService(dir.resolve("games")); records.init();
        GameRoom room = new GameRoom("ROOM-LEAVE", GameType.HIGH_CARD, true, 3); room.setRecordService(records);
        try {
            for (int i = 1; i <= 3; i++) room.addPlayer(player(i));
            Player first = room.getPlayers().get(0), second = room.getPlayers().get(1);
            room.removePlayer(first); assertEquals(GameRoom.State.PLAYING, room.getState());
            assertFalse(room.handleCommand(first, MoveCommand.action(10), -1));
            room.surrender(first); assertEquals(GameRoom.State.PLAYING, room.getState());
            room.surrender(second); assertEquals(GameRoom.State.WAITING, room.getState());
            assertEquals(2, room.getPlayers().size());
            assertFalse(room.getPlayers().contains(first));
            EmbeddedChannel departedChannel = (EmbeddedChannel) first.getConnection();
            departedChannel.runPendingTasks();
            TextWebSocketFrame frame;
            while ((frame = departedChannel.readOutbound()) != null) {
                assertFalse(frame.text().contains("\"type\":\"GAME_MOVE\""));
                assertFalse(frame.text().contains("\"type\":\"GAME_OVER\""));
                frame.release();
            }
            departedChannel.finishAndReleaseAll();
            records.flushForTest();
            GameRecord record = records.getRecentGames(1).get(0);
            assertEquals(3, record.getParticipants().size()); assertFalse(record.isRanked());
            assertEquals("WIN", record.getParticipants().get(2).getResult());
        } finally { clear(room); }
    }
    @Test void reconnectPreservesSeatAndOnlyReturnsItsHand() {
        GameRoom room = new GameRoom("ROOM-REJOIN", GameType.HIGH_CARD, true, 8);
        try {
            for (int i = 1; i <= 8; i++) room.addPlayer(player(i));
            Player old = room.getPlayers().get(7);
            room.handleDisconnect(old);
            Player replacement = new Player(old.getId(), old.getName(), new EmbeddedChannel()); replacement.setStone(old.getStone());
            room.replacePlayer(old, replacement);
            assertEquals(8, room.getPlayers().size()); assertEquals(8, replacement.getStone());
            assertTrue(room.handleCommand(replacement, MoveCommand.action(10), -1));
            String sync = new Gson().toJson(room.buildSyncDataForReconnect(replacement));
            assertTrue(sync.contains("\"choice\":10"));
        } finally { clear(room); }
    }
}
