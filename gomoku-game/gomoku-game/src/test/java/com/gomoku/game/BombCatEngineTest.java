package com.gomoku.game;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 炸弹猫规则、隐藏信息、回合压力与多人淘汰测试。 */
class BombCatEngineTest {

    @Test
    void dealsHiddenHandsAndPublicCounts() {
        BombCatEngine engine = new BombCatEngine();
        engine.configurePlayers(4);
        engine.reset();
        assertEquals(GameType.BOMB_CAT, engine.type());
        assertEquals(4, engine.getPlayerCount());
        assertEquals(5, engine.getHand(1).size());
        assertEquals(13, engine.getDeckCount(), "4 人局：3 枚炸弹 + 2 枚拆弹器 + 8 张行动牌");
        Map<?, ?> view = (Map<?, ?>) engine.getViewState(1);
        assertEquals("BOMB_CAT", view.get("kind"));
        List<?> players = (List<?>) view.get("players");
        assertTrue(((Map<?, ?>) players.get(0)).containsKey("hand"));
        assertFalse(((Map<?, ?>) players.get(1)).containsKey("hand"));
        assertFalse(view.containsKey("deckOrder"));
    }

    @Test
    void peekIsPrivateAndDefuseKeepsPlayerInTurn() {
        BombCatEngine engine = new BombCatEngine();
        engine.configurePlayers(3);
        engine.reset();
        engine.setHandForTest(1, List.of(
                card(BombCatEngine.CardType.SEE_FUTURE),
                card(BombCatEngine.CardType.DEFUSE),
                card(BombCatEngine.CardType.SKIP)));
        // setDeckForTest 从底到顶写入；抽牌取栈顶。
        engine.setDeckForTest(List.of(
                card(BombCatEngine.CardType.FAVOR),
                card(BombCatEngine.CardType.ATTACK),
                card(BombCatEngine.CardType.SKIP),
                card(BombCatEngine.CardType.BOMB)));
        engine.forceTurnForTest(1);
        assertTrue(engine.applyMove(MoveCommand.action(0), 1));
        Map<?, ?> mine = (Map<?, ?>) engine.getViewState(1);
        assertEquals(3, ((List<?>) mine.get("peekCards")).size());
        Map<?, ?> other = (Map<?, ?>) engine.getViewState(2);
        assertEquals(0, ((List<?>) other.get("peekCards")).size());

        assertTrue(engine.applyMove(MoveCommand.action(BombCatEngine.DRAW_ACTION), 1));
        assertEquals(engine.getPendingReinsertSide(), 1);
        assertEquals(1, engine.getCurrentTurn());
        assertTrue(engine.applyMove(MoveCommand.action(BombCatEngine.REINSERT_BOTTOM), 1));
        assertEquals(2, engine.getCurrentTurn());
        assertFalse(engine.isOver());
    }

    @Test
    void attackGrantsOneExtraTurnAfterFirstDraw() {
        BombCatEngine engine = new BombCatEngine();
        engine.configurePlayers(3);
        engine.reset();
        engine.setHandForTest(1, List.of(card(BombCatEngine.CardType.ATTACK)));
        engine.setHandForTest(2, List.of(card(BombCatEngine.CardType.SKIP)));
        engine.setDeckForTest(List.of(
                card(BombCatEngine.CardType.SKIP),
                card(BombCatEngine.CardType.SEE_FUTURE)));
        engine.forceTurnForTest(1);
        assertTrue(engine.applyMove(MoveCommand.action(0), 1));
        assertEquals(2, engine.getCurrentTurn(), "攻击后受害者立即行动");
        assertTrue(engine.applyMove(MoveCommand.action(BombCatEngine.DRAW_ACTION), 2));
        assertEquals(2, engine.getCurrentTurn(), "攻击后第一次抽牌结束，仍继续额外回合");
        assertTrue(engine.applyMove(MoveCommand.action(BombCatEngine.DRAW_ACTION), 2));
        assertEquals(3, engine.getCurrentTurn(), "额外回合抽牌结束后交给下一家");
    }

    @Test
    void skipBypassesNextActivePlayer() {
        BombCatEngine engine = new BombCatEngine();
        engine.configurePlayers(4);
        engine.reset();
        engine.setHandForTest(1, List.of(card(BombCatEngine.CardType.SKIP)));
        engine.forceTurnForTest(1);
        assertTrue(engine.applyMove(MoveCommand.action(0), 1));
        assertEquals(3, engine.getCurrentTurn());
    }

    @Test
    void favorStealsRandomCardWithoutPublishingIdentity() {
        BombCatEngine engine = new BombCatEngine();
        engine.configurePlayers(2);
        engine.reset();
        engine.setHandForTest(1, List.of(card(BombCatEngine.CardType.FAVOR)));
        engine.setHandForTest(2, List.of(card(BombCatEngine.CardType.SKIP), card(BombCatEngine.CardType.ATTACK)));
        engine.forceTurnForTest(1);
        assertTrue(engine.applyMove(MoveCommand.withTarget(0, 2, 2), 1));
        assertEquals(1, engine.getHand(1).size());
        assertEquals(1, engine.getHand(2).size());
        assertTrue(engine.getCurrentTurn() == 1);
        Map<?, ?> view = (Map<?, ?>) engine.getViewState(2);
        String text = String.valueOf(view.get("log"));
        assertFalse(text.contains("偷走") && text.contains("攻击"), "日志不能公开被偷走的牌");
        assertEquals(BombCatEngine.MAX_PLAYS_PER_TURN - 1, engine.playsLeft(1),
                "偷牌成功后也必须消耗一次行动机会");
    }

    @Test
    void actionLimitRejectsFourthCardAndMarksRemainingCardsUnplayable() {
        BombCatEngine engine = new BombCatEngine();
        engine.configurePlayers(2);
        engine.reset();
        engine.setHandForTest(1, List.of(
                card(BombCatEngine.CardType.SHUFFLE),
                card(BombCatEngine.CardType.SHUFFLE),
                card(BombCatEngine.CardType.SHUFFLE),
                card(BombCatEngine.CardType.SHUFFLE)));
        // 本例只验证行动上限和抽牌换手，不能随机抽中炸弹直接终局。
        engine.setDeckForTest(List.of(card(BombCatEngine.CardType.SKIP)));
        engine.forceTurnForTest(1);
        for (int i = 0; i < BombCatEngine.MAX_PLAYS_PER_TURN; i++) {
            assertTrue(engine.applyMove(MoveCommand.action(0), 1));
        }
        assertEquals(0, engine.playsLeft(1));
        assertFalse(engine.applyMove(MoveCommand.action(0), 1), "第四张行动牌必须被拒绝");
        Map<?, ?> view = (Map<?, ?>) engine.getViewState(1);
        Map<?, ?> me = (Map<?, ?>) ((List<?>) view.get("players")).get(0);
        List<?> hand = (List<?>) me.get("hand");
        assertTrue(hand.stream().noneMatch(c -> Boolean.TRUE.equals(((Map<?, ?>) c).get("playable"))),
                "行动次数用尽后所有手牌都应显示为不可出");
        assertTrue(engine.applyMove(MoveCommand.action(BombCatEngine.DRAW_ACTION), 1));
        assertEquals(2, engine.getCurrentTurn(), "抽牌后正常换手");
    }

    @Test
    void aiDrawsAfterUsingAllActionPlays() {
        BombCatEngine engine = new BombCatEngine();
        engine.configurePlayers(2);
        engine.reset();
        engine.setHandForTest(1, List.of(
                card(BombCatEngine.CardType.SHUFFLE),
                card(BombCatEngine.CardType.SHUFFLE),
                card(BombCatEngine.CardType.SHUFFLE)));
        engine.setDeckForTest(List.of(card(BombCatEngine.CardType.SKIP)));
        engine.forceTurnForTest(1);
        for (int i = 0; i < BombCatEngine.MAX_PLAYS_PER_TURN; i++) {
            MoveCommand command = BombCatAI.INSTANCE.decide(engine, 1, 6);
            assertNotNull(command);
            assertTrue(engine.applyMove(command, 1), "AI 的每次动作都必须是合法指令");
        }
        MoveCommand exhausted = BombCatAI.INSTANCE.decide(engine, 1, 6);
        assertEquals(BombCatEngine.DRAW_ACTION, exhausted.getAction(),
                "三次行动后 AI 必须抽牌，而不是继续提交第四张牌");
        assertTrue(engine.applyMove(exhausted, 1));
        assertEquals(2, engine.getCurrentTurn());
    }

    @Test
    void moveCapEndsLongGameInDraw() {
        BombCatEngine engine = new BombCatEngine();
        engine.configurePlayers(2);
        engine.reset();
        engine.setHandForTest(1, List.of(card(BombCatEngine.CardType.SHUFFLE)));
        engine.setDeckForTest(List.of(card(BombCatEngine.CardType.SKIP)));
        engine.forceTurnForTest(1);
        engine.forceMoveCountForTest(BombCatEngine.MAX_MOVES - 1);
        assertTrue(engine.applyMove(MoveCommand.action(0), 1));
        assertTrue(engine.isOver());
        assertNull(engine.getWinner());
        assertTrue(engine.getDrawingSides().contains(1));
        assertTrue(engine.getScoreSummary().contains("行动上限"));
    }

    @Test
    void bombWithoutDefuseEliminatesAndLastSurvivorWins() {
        BombCatEngine engine = new BombCatEngine();
        engine.configurePlayers(2);
        engine.reset();
        engine.setHandForTest(1, List.of(card(BombCatEngine.CardType.SKIP)));
        engine.setDeckForTest(List.of(card(BombCatEngine.CardType.BOMB)));
        engine.forceTurnForTest(1);
        assertTrue(engine.applyMove(MoveCommand.action(BombCatEngine.DRAW_ACTION), 1));
        assertFalse(engine.isActive(1));
        assertTrue(engine.isOver());
        assertEquals(2, engine.getWinner());
        assertEquals("WIN", winnerResult(engine, 2));
    }

    @Test
    void depletedDeckEndsInDrawAndForfeitEliminates() {
        BombCatEngine engine = new BombCatEngine();
        engine.configurePlayers(2);
        engine.reset();
        engine.setHandForTest(1, List.of(card(BombCatEngine.CardType.SKIP)));
        engine.setDeckForTest(new ArrayList<>());
        engine.forceTurnForTest(1);
        assertTrue(engine.applyMove(MoveCommand.action(BombCatEngine.DRAW_ACTION), 1));
        assertTrue(engine.isOver());
        assertNull(engine.getWinner());
        assertTrue(engine.getDrawingSides().contains(1));
        assertFalse(engine.forfeit(1), "终局后不能再淘汰");
    }

    private static BombCatEngine.Card card(BombCatEngine.CardType type) {
        return new BombCatEngine.Card(type);
    }

    private static String winnerResult(BombCatEngine engine, int side) {
        return engine.getWinningSides().contains(side) ? "WIN" : "LOSE";
    }
}
