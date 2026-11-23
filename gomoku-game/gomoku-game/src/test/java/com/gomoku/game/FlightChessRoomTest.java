package com.gomoku.game;

import com.gomoku.model.GameMessage;
import com.gomoku.model.Player;
import com.google.gson.Gson;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 飞行棋的房间层回归：首骰只掷一次、跳过推进、AI 自动跳过、
 * 4 人终局结算与回放手数/效果对齐。
 */
class FlightChessRoomTest {

    /** 固定骰子：每次返回同一点数，保证房间层断言可复现。 */
    private static final class FixedRandom extends Random {
        private final int value;
        FixedRandom(int value) { this.value = value; }
        @Override public int nextInt(int bound) { return Math.max(0, Math.min(bound - 1, value - 1)); }
    }

    private static final class TestRoom implements AutoCloseable {
        final GameRoom room;
        final List<Player> players = new ArrayList<>();
        final List<EmbeddedChannel> channels = new ArrayList<>();

        TestRoom(String id, int count) {
            room = new GameRoom(id, GameType.FLIGHT_CHESS, true, count);
            ((FlightChessBoard) room.getEngine()).setRandomForTest(new FixedRandom(3));
        }

        Player add(String name) {
            EmbeddedChannel channel = new EmbeddedChannel();
            Player player = new Player("flight-" + name, name, channel);
            assertTrue(room.addPlayer(player));
            players.add(player);
            channels.add(channel);
            return player;
        }

        Player addAi() {
            room.addAI(4);
            return room.getPlayers().stream().filter(Player::isAi).reduce((a, b) -> b).orElseThrow();
        }

        void drain() {
            for (EmbeddedChannel channel : channels) channel.runPendingTasks();
        }

        /** 读取某个玩家收到的指定类型消息；会消费该频道中已排队的帧。 */
        GameMessage last(Player player, GameMessage.Type type) {
            EmbeddedChannel channel = (EmbeddedChannel) player.getConnection();
            channel.runPendingTasks();
            GameMessage found = null;
            TextWebSocketFrame frame;
            while ((frame = channel.readOutbound()) != null) {
                try {
                    GameMessage msg = new Gson().fromJson(frame.text(), GameMessage.class);
                    if (msg.getType() == type) found = msg;
                } catch (RuntimeException ignored) {
                    // 测试里只关心 JSON 协议帧
                } finally {
                    frame.release();
                }
            }
            return found;
        }

        GameMessage waitFor(Player player, GameMessage.Type type, long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                GameMessage msg = last(player, type);
                if (msg != null) return msg;
                try { Thread.sleep(20); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return null;
        }

        @Override public void close() {
            room.notifyShutdown();
            for (EmbeddedChannel channel : channels) channel.finishAndReleaseAll();
        }
    }

    private static Map<?, ?> viewOf(GameMessage msg) {
        String data = msg.getData();
        assertNotNull(data);
        Map<?, ?> root = new Gson().fromJson(data, Map.class);
        Object view = root.get("view");
        assertTrue(view instanceof Map, "GAME_START 应带 view：" + data);
        return (Map<?, ?>) view;
    }

    @Test
    void fourPlayerRoomSeatsEveryoneAndRollsTheFirstDiceExactlyOnce() {
        try (TestRoom test = new TestRoom("ROOM-FLIGHT-SEATS", 4)) {
            for (int i = 1; i <= 4; i++) test.add("飞行" + i);
            assertEquals(GameRoom.State.PLAYING, test.room.getState());
            assertEquals(4, test.room.getPlayers().size());
            assertEquals(4, test.room.getPlayers().stream().map(Player::getStone).distinct().count());

            FlightChessBoard engine = (FlightChessBoard) test.room.getEngine();
            assertEquals(1, engine.getRollCountForTest(), "开局只允许 beginTurnRoll 掷一次骰");
            assertEquals(3, engine.getDice());
            assertEquals(FlightChessBoard.Phase.SKIP, engine.getPhase());

            GameMessage start = test.waitFor(test.players.get(0), GameMessage.Type.GAME_START, 1500);
            assertNotNull(start, "应收到 GAME_START");
            Map<?, ?> view = viewOf(start);
            assertEquals(3, ((Number) view.get("dice")).intValue(), "首骰被重复掷会覆盖本次点数");
            assertEquals("SKIP", view.get("phase"));
            assertEquals(4, ((Number) view.get("playerCount")).intValue());
        }
    }

    @Test
    void explicitPassAdvancesSeatAndKeepsHistoryAligned() {
        try (TestRoom test = new TestRoom("ROOM-FLIGHT-PASS", 2)) {
            Player first = test.add("甲");
            test.add("乙");
            FlightChessBoard engine = (FlightChessBoard) test.room.getEngine();
            assertEquals(1, engine.getCurrentTurn());

            assertTrue(test.room.handlePass(first), "无棋可走时应允许显式跳过");
            assertEquals(2, engine.getCurrentTurn(), "跳过应推进到下一位");
            assertEquals(1, engine.getMoveCount());
            assertEquals(-1, engine.getMoveHistory().get(0)[1], "跳过用 pieceIndex=-1 记录");
            assertEquals(3, engine.getDice(), "跳过后应为下一位重新掷骰");
            assertEquals(FlightChessBoard.Phase.SKIP, engine.getPhase());
        }
    }

    @Test
    void aiSeatAutoSkipsWithoutStallingTheRoom() throws Exception {
        try (TestRoom test = new TestRoom("ROOM-FLIGHT-AI", 2)) {
            Player human = test.add("真人");
            test.addAi();
            FlightChessBoard engine = (FlightChessBoard) test.room.getEngine();
            assertEquals(1, engine.getCurrentTurn());

            assertTrue(test.room.handlePass(human));
            assertEquals(1, engine.getCurrentTurn(), "AI 无棋可走应被房间自动跳过，回到真人");
            assertEquals(2, engine.getMoveCount(), "真人跳过 + AI 自动跳过各记一手");
            assertEquals(GameRoom.State.PLAYING, test.room.getState());

            // 等过 AI 调度延迟，确认没有重复调度或递归跳过。
            Thread.sleep(900);
            assertEquals(2, engine.getMoveCount(), "AI 不应被重复调度");
            assertEquals(1, engine.getCurrentTurn());
            assertEquals(1, test.room.getPlayers().stream().filter(Player::isAi).count(),
                    "房间应保留一个电脑席位");
        }
    }

    @Test
    void fourPlayerFinishProducesScoresWithoutEloAndReplayArraysMatch() {
        try (TestRoom test = new TestRoom("ROOM-FLIGHT-FINISH", 4)) {
            Player first = test.add("冠军");
            test.add("亚军");
            test.add("季军");
            test.add("殿军");
            FlightChessBoard engine = (FlightChessBoard) test.room.getEngine();

            // 四个座位都掷出 3 点且全在基地，依次显式跳过，回到冠军并留下 4 条跳过记录。
            for (int i = 0; i < 4; i++) {
                assertTrue(test.room.handlePass(test.room.getPlayers().get(i)));
            }
            assertEquals(4, engine.getMoveCount());
            assertEquals(1, engine.getCurrentTurn());

            engine.setDiceForTest(1);
            for (int p = 0; p < 3; p++) engine.setPositionForTest(1, p, FlightChessBoard.HOME_POS);
            engine.setFinishedCountForTest(1, 3);
            engine.setPositionForTest(1, 3, 56);

            int[] from = FlightChessBoard.ringCell(56);
            assertTrue(test.room.handleMove(first, from[0], from[1], 7, 7, -1));
            assertEquals(GameRoom.State.FINISHED, test.room.getState());
            assertTrue(engine.isOver());
            assertEquals(1, engine.getWinner());

            GameMessage over = test.waitFor(first, GameMessage.Type.GAME_OVER, 2000);
            assertNotNull(over, "应收到 GAME_OVER");
            Map<?, ?> data = new Gson().fromJson(over.getData(), Map.class);
            List<?> scores = (List<?>) data.get("scores");
            assertEquals(4, scores.size(), "4 人局结算应包含全部座位");
            for (Object score : scores) {
                assertEquals(0, ((Number) ((Map<?, ?>) score).get("eloChange")).intValue(),
                        "4 人飞行棋不进入双人 ELO");
            }
            List<?> history = (List<?>) data.get("moveHistory");
            List<?> effects = (List<?>) data.get("moveEffects");
            assertEquals(5, history.size());
            assertEquals(history.size(), effects.size(), "回放效果必须与手数逐手对齐");
            for (int i = 0; i < 4; i++) {
                assertEquals(0, ((List<?>) effects.get(i)).size(), "跳过手没有盘面变化");
            }
            assertTrue(((List<?>) effects.get(4)).size() > 0, "到达终点应带完整快照效果");
            assertEquals("NORMAL", data.get("finishReason"), "正常终局不应误报认输");
        }
    }

    @Test
    void surrenderKeepsFourPlayerMatchAliveUntilOneSeatRemains() {
        try (TestRoom test = new TestRoom("ROOM-FLIGHT-SURRENDER", 4)) {
            Player first = test.add("甲");
            Player second = test.add("乙");
            Player third = test.add("丙");
            test.add("丁");
            FlightChessBoard engine = (FlightChessBoard) test.room.getEngine();

            test.room.surrender(second);
            assertEquals(GameRoom.State.PLAYING, test.room.getState(), "一人退出后其余三人继续");
            assertFalse(engine.isEliminatedForTest(1));
            assertTrue(engine.isEliminatedForTest(2));
            GameMessage exit = test.waitFor(second, GameMessage.Type.PLAYER_STATUS, 1200);
            assertNotNull(exit, "淘汰座位应收到明确的 PLAYER_STATUS 广播");
            assertTrue(exit.getMessage().contains("乙") && exit.getMessage().contains("退出"),
                    "聊天区应明确显示哪名玩家退出：" + exit.getMessage());

            test.room.surrender(third);
            assertEquals(GameRoom.State.PLAYING, test.room.getState(), "仍有两人时继续对局");
            test.room.surrender(test.room.getPlayers().stream()
                    .filter(p -> p.getStone() == 4).findFirst().orElseThrow());
            assertEquals(GameRoom.State.FINISHED, test.room.getState());
            assertEquals(1, engine.getWinner(), "最后留在场上的 1 号座位获胜");

            GameMessage over = test.waitFor(first, GameMessage.Type.GAME_OVER, 2000);
            assertNotNull(over);
            Map<?, ?> data = new Gson().fromJson(over.getData(), Map.class);
            assertEquals("SURRENDER", data.get("finishReason"));
            assertEquals(4, ((List<?>) data.get("scores")).size());
            assertEquals(0, ((Number) data.get("eloChange")).intValue(), "4 人淘汰局不写双人 ELO");
        }
    }
}
