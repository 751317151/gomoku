package com.gomoku.game;

import com.gomoku.model.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 21 点回归：发牌、要牌/停牌流程、爆牌、平局判定与战绩落库。
 *
 * 这里重点钉住「平局不能被记成负」——黑杰克对庄家点数相同的座位应记为 DRAW，
 * 否则战绩和任务进度都会虚高失真。
 */
class BlackjackEngineTest {

    @Test
    void dealGivesEverySeatTwoCardsAndDealerTwo() {
        BlackjackEngine engine = new BlackjackEngine(new Random(11));
        engine.configurePlayers(4);
        engine.reset();

        Map<String, Object> view = asView(engine.getViewState(1));
        List<Map<String, Object>> players = asList(view.get("players"));
        assertEquals(4, players.size());
        for (Map<String, Object> p : players) {
            assertEquals(2, asList(p.get("cards")).size());
        }
        List<Map<String, Object>> dealer = asList(view.get("dealer"));
        assertEquals(2, dealer.size());
        assertEquals(Boolean.TRUE, dealer.get(1).get("hidden"), "未终局时庄家第二张牌必须隐藏");
    }

    @Test
    void hittingCanBustAndStandsAdvanceTheTurn() {
        BlackjackEngine engine = new BlackjackEngine(new Random(5));
        engine.configurePlayers(2);
        engine.reset();

        int first = engine.getCurrentTurn();
        assertTrue(engine.applyMove(MoveCommand.action(BlackjackEngine.ACTION_HIT), first));
        assertTrue(engine.getMoveCount() >= 1);
        // 要牌后可能正好 21 点而自动停牌；只有仍在行动时才需要手动停牌
        if (engine.isAwaitingAction(first)) {
            assertTrue(engine.endTurn(first), "停牌应结束该座位回合");
        }
        assertEquals(2, engine.getCurrentTurn(), "应轮到第二个座位");

        assertTrue(engine.endTurn(2));
        assertTrue(engine.isOver());
        assertTrue(engine.getDealerTotal() >= BlackjackEngine.DEALER_STAND);
    }

    @Test
    void winnersAndPushesNeverOverlap() {
        for (int seed = 0; seed < 200; seed++) {
            BlackjackEngine engine = new BlackjackEngine(new Random(seed));
            engine.configurePlayers(4);
            engine.reset();
            int guard = 0;
            while (!engine.isOver() && guard++ < 200) {
                int turn = engine.getCurrentTurn();
                if (turn < 1) break;
                if (engine.getTotal(turn) < 17) engine.applyMove(MoveCommand.action(BlackjackEngine.ACTION_HIT), turn);
                else engine.endTurn(turn);
            }
            assertTrue(engine.isOver(), "seed " + seed + " 应在有限步内结束");
            List<Integer> wins = engine.getWinningSides();
            List<Integer> pushes = engine.getDrawingSides();
            for (int side : pushes) assertFalse(wins.contains(side), "平局座位不应同时算胜");
            for (int side = 1; side <= 4; side++) {
                if (engine.isBusted(side)) {
                    assertFalse(wins.contains(side), "爆牌不应算胜");
                    assertFalse(pushes.contains(side), "爆牌不应算平");
                }
            }
        }
    }

    @Test
    void aceCountsAsElevenOrOne() {
        assertEquals(21, BlackjackEngine.total(List.of(card(0, 1), card(1, 13))));
        assertEquals(21, BlackjackEngine.total(List.of(card(0, 1), card(1, 1), card(2, 9))));
        assertEquals(21, BlackjackEngine.total(List.of(card(0, 1), card(1, 13), card(2, 13))));
        assertEquals(19, BlackjackEngine.total(List.of(card(0, 1), card(1, 9), card(2, 9))));
        assertEquals(0, BlackjackEngine.total(List.of()));
    }

    @Test
    void roomFinishesBlackjackWithoutRankedElo() {
        GameRoom room = new GameRoom("ROOM-BJ", GameType.BLACKJACK, true, 2);
        room.setEloService(new EloService(
                java.nio.file.Paths.get("target", "bj-elo-" + System.nanoTime() + ".properties"), null));
        GameRecordService records = new GameRecordService(
                java.nio.file.Paths.get("target", "bj-records-" + System.nanoTime()));
        room.setRecordService(records);

        Player a = new Player("bj-a", "玩家甲", null);
        Player b = new Player("bj-b", "玩家乙", null);
        int eloBefore = a.getElo();
        room.addPlayer(a);
        room.addPlayer(b);

        int guard = 0;
        while (room.getState() == GameRoom.State.PLAYING && guard++ < 200) {
            int turn = room.getEngine().getCurrentTurn();
            Player actor = turn == a.getStone() ? a : turn == b.getStone() ? b : null;
            if (actor == null) break;
            assertTrue(room.handleEndTurn(actor));
        }

        assertEquals(GameRoom.State.FINISHED, room.getState());
        records.flushForTest();
        GameRecord record = records.getRecentGames(1).get(0);
        assertEquals("BLACKJACK", record.getGameType());
        assertFalse(record.isRanked(), "21 点属于运气玩法，不进入双人 ELO");
        assertEquals(eloBefore, a.getElo(), "ELO 不应因休闲局改变");
        room.notifyShutdown();
    }

    private static int card(int suit, int rank) { return suit * 13 + (rank - 1); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asView(Object view) { return (Map<String, Object>) view; }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object value) { return (List<Map<String, Object>>) value; }
}
