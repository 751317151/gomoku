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

    /** 读出 EmbeddedChannel 中指定类型的最新一条消息，便于断言真实推送内容。 */
    private com.gomoku.model.GameMessage lastMessage(Player player, com.gomoku.model.GameMessage.Type type) {
        EmbeddedChannel channel = (EmbeddedChannel) player.getConnection();
        // Player.sendMessage 会排到 channel 的 EventLoop，先执行队列才能读到真实出站帧。
        channel.runPendingTasks();
        com.gomoku.model.GameMessage found = null;
        TextWebSocketFrame frame;
        while ((frame = channel.readOutbound()) != null) {
            try {
                com.gomoku.model.GameMessage msg = new Gson().fromJson(frame.text(),
                        com.gomoku.model.GameMessage.class);
                if (msg.getType() == type) found = msg;
            } catch (RuntimeException ignored) { }
            frame.release();
        }
        return found;
    }

    private com.gomoku.model.GameMessage waitMessage(Player player,
            com.gomoku.model.GameMessage.Type type, long timeoutMs) {
        return waitMessage(player, type, null, timeoutMs);
    }

    private com.gomoku.model.GameMessage waitMessage(Player player,
            com.gomoku.model.GameMessage.Type type, String messagePart, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        do {
            com.gomoku.model.GameMessage found;
            while ((found = lastMessage(player, type)) != null) {
                if (messagePart == null || (found.getMessage() != null
                        && found.getMessage().contains(messagePart))) {
                    return found;
                }
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        } while (System.currentTimeMillis() < deadline);
        return null;
    }

    @Test void rematchBroadcastsProgressAndCancelsCleanly() {
        GameRoom room = new GameRoom("ROOM-REMATCH", GameType.GOMOKU, true, 2);
        Player first = player(1);
        Player second = player(2);
        try {
            assertTrue(room.addPlayer(first));
            assertTrue(room.addPlayer(second));
            room.surrender(second);
            assertEquals(GameRoom.State.FINISHED, room.getState());

            room.requestRestart(first);
            assertEquals(1, room.getRestartVoteCount());
            assertTrue(room.getRematchDeadline() > System.currentTimeMillis());
            com.gomoku.model.GameMessage progress = waitMessage(first,
                    com.gomoku.model.GameMessage.Type.RESTART_REQUEST, 1000);
            assertNotNull(progress, "应广播再来一局状态");
            assertTrue(progress.getData().contains(second.getId()), progress.getData());
            assertTrue(progress.getData().contains("\"pending\":true"), progress.getData());

            room.cancelRestart(first);
            assertEquals(0, room.getRestartVoteCount());
            assertEquals(0L, room.getRematchDeadline());
            assertEquals(GameRoom.State.FINISHED, room.getState());
            com.gomoku.model.GameMessage cancelled = waitMessage(second,
                    com.gomoku.model.GameMessage.Type.RESTART_REQUEST, 1000);
            assertNotNull(cancelled, "撤回准备也应通知其他座位");
            assertTrue(cancelled.getData().contains("\"pending\":false"), cancelled.getData());
        } finally { clear(room); }
    }

    @Test void rematchTimeoutClearsWaitWithoutStartingGame() throws Exception {
        GameRoom room = new GameRoom("ROOM-REMATCH-TIMEOUT", GameType.GOMOKU, true, 2);
        Player first = player(1);
        Player second = player(2);
        try {
            assertTrue(room.addPlayer(first));
            assertTrue(room.addPlayer(second));
            room.surrender(second);
            room.requestRestart(first);

            java.lang.reflect.Field expires = GameRoom.class.getDeclaredField("rematchExpiresAt");
            expires.setAccessible(true);
            expires.setLong(room, System.currentTimeMillis() + 80);
            java.lang.reflect.Method schedule = GameRoom.class.getDeclaredMethod("scheduleRematchTimeout");
            schedule.setAccessible(true);
            schedule.invoke(room);

            com.gomoku.model.GameMessage timedOut = waitMessage(second,
                    com.gomoku.model.GameMessage.Type.RESTART_REQUEST, "超时", 2000);
            assertNotNull(timedOut, "超时应向其他座位广播取消等待");
            assertTrue(timedOut.getData().contains("\"pending\":false"), timedOut.getData());
            assertEquals(0, room.getRestartVoteCount());
            assertEquals(0L, room.getRematchDeadline());
            assertEquals(GameRoom.State.FINISHED, room.getState(),
                    "等待超时只能回到结算页，不能自动开局");
        } finally { clear(room); }
    }

    @Test void rematchVotesResetWhenGameRestarts() {
        GameRoom room = new GameRoom("ROOM-REMATCH-RESET", GameType.HIGH_CARD, true, 2);
        Player first = player(1);
        Player second = player(2);
        try {
            assertTrue(room.addPlayer(first));
            assertTrue(room.addPlayer(second));
            room.surrender(second);
            room.requestRestart(first);
            room.requestRestart(second);
            assertEquals(GameRoom.State.PLAYING, room.getState());
            assertEquals(0, room.getRestartVoteCount());
            assertEquals(0L, room.getRematchDeadline());
        } finally { clear(room); }
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
            Player third = room.getPlayers().get(2);
            room.removePlayer(first); assertEquals(GameRoom.State.PLAYING, room.getState());
            assertFalse(room.handleCommand(first, MoveCommand.action(10), -1));
            room.surrender(first); assertEquals(GameRoom.State.PLAYING, room.getState());
            room.surrender(second);
            // 终局后仍有两名真人要留在结算页：不能把房间切回 WAITING，
            // 否则第三人会立刻收到等待消息、看不到本局结果，也可能被新玩家补位。
            assertEquals(GameRoom.State.FINISHED, room.getState());
            assertEquals(2, room.getPlayers().size());
            assertFalse(room.getPlayers().contains(first));
            assertNotNull(room.buildSyncDataForReconnect(third).getGameOver(),
                    "留下的玩家仍应能重连恢复本局结算");
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
