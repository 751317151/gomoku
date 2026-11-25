package com.gomoku.game;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class UnoBattleEngineTest {
    @Test void drawingWithoutNeedIsRejectedWithoutChangingDecision() {
        UnoBattleEngine engine = fourPlayers();
        engine.setTopForTest(0, 3);
        engine.setHandForTest(1, List.of(card(0, 4), card(2, 5)));
        Object before = engine.getViewState(1);
        assertFalse(engine.canDraw(1));
        assertFalse(engine.applyMove(MoveCommand.action(UnoBattleEngine.DRAW_ACTION), 1));
        assertEquals(before, engine.getViewState(1));
        assertEquals(0, engine.getMoveSeq());
    }

    @Test
    void sevenCannotExchangeWithDepartedSeatAndRejectionIsAtomic() {
        UnoBattleEngine engine = fourPlayers();
        engine.setTopForTest(0, 3);
        engine.setHandForTest(1, List.of(card(0, 7), card(2, 5), card(3, 8)));
        assertTrue(engine.forfeit(2));
        Object before = engine.getViewState(1);
        List<UnoBattleEngine.Card> hand = engine.getHand(1);
        assertFalse(engine.applyMove(MoveCommand.withTarget(0, 2, 2), 1));
        assertEquals(before, engine.getViewState(1));
        assertEquals(hand, engine.getHand(1));
        assertTrue(engine.getHand(2).isEmpty());
        assertEquals(0, engine.getMoveSeq());
        assertTrue(engine.getMoveHistory().isEmpty());
        assertFalse(engine.isOver());
        assertNull(engine.getWinner());
        assertTrue(engine.applyMove(MoveCommand.withTarget(0, 2, 3), 1));
    }

    @Test
    void departedSeatCannotCatchUno() {
        UnoBattleEngine engine = fourPlayers();
        engine.setTopForTest(0, 3);
        engine.setHandForTest(1, List.of(card(0, 4), card(2, 5)));
        assertTrue(engine.forfeit(2));
        assertTrue(engine.applyMove(MoveCommand.action(0), 1));
        Object before = engine.getViewState(1);
        assertFalse(engine.applyMove(MoveCommand.action(UnoBattleEngine.CATCH_UNO_ACTION), 2));
        assertEquals(before, engine.getViewState(1));
        assertEquals(1, engine.getMoveSeq());
        assertTrue(engine.applyMove(MoveCommand.action(UnoBattleEngine.CATCH_UNO_ACTION), 3));
        assertEquals(3, engine.getCurrentTurn());
    }

    @Test
    void playableFlagsRespectWildFourUnoWindowAndTurn() {
        UnoBattleEngine engine = fourPlayers();
        engine.setTopForTest(0, 3);
        engine.setHandForTest(1, List.of(card(-1, 14), card(0, 4)));
        assertFalse(engine.canPlay(1, 0), "有同色手牌时 +4 不能高亮或交给电脑");
        assertTrue(engine.canPlay(1, 1));
        assertFalse(engine.canPlay(2, 0));
        assertEquals(Boolean.FALSE, handView(engine, 1).get(0).get("playable"));
        assertTrue(engine.applyMove(MoveCommand.action(1), 1));
        assertFalse(engine.canPlay(1, 0), "喊 UNO 窗口不能继续出牌");
        assertEquals(Boolean.FALSE, handView(engine, 1).get(0).get("playable"));
        assertTrue(engine.applyMove(MoveCommand.action(UnoBattleEngine.CALL_UNO_ACTION), 1));
    }

    @Test
    void drawnDecisionOnlyEnablesNewCardAndEndIsRecorded() {
        UnoBattleEngine engine = fourPlayers();
        engine.setTopForTest(0, 3);
        engine.setHandForTest(1, List.of(card(1, 5), card(2, 6)));
        engine.setDeckForTest(List.of(card(0, 8)), List.of());
        assertTrue(engine.applyMove(MoveCommand.action(UnoBattleEngine.DRAW_ACTION), 1));
        assertEquals(1, engine.getDrawnPendingSide());
        assertFalse(engine.canPlay(1, 0));
        assertTrue(engine.canPlay(1, 2));
        assertFalse(engine.applyMove(MoveCommand.action(0), 1));
        assertTrue(engine.applyMove(MoveCommand.action(UnoBattleEngine.END_DRAW_ACTION), 1));
        assertEquals(2, engine.getCurrentTurn());
        assertEquals(2, engine.getMoveSeq());
        assertEquals(2, engine.getMoveHistory().size());
        assertArrayEquals(new int[]{1, UnoBattleEngine.END_DRAW_ACTION, 0}, engine.getMoveHistory().get(1));
        assertEquals(0, engine.getDrawnPendingSide());
        assertFalse(engine.applyMove(MoveCommand.action(UnoBattleEngine.END_DRAW_ACTION), 1));
    }

    @Test
    void stackingIsOptionalAndFourRestrictionStillAppliesDuringPenalty() {
        UnoBattleEngine engine = fourPlayers();
        engine.setTopForTest(0, 3);
        engine.setHandForTest(1, List.of(card(0, 12), card(2, 6), card(1, 4)));
        assertTrue(engine.applyMove(MoveCommand.action(0), 1));
        engine.setHandForTest(2, List.of(card(0, 9), card(-1, 14), card(1, 12)));
        assertFalse(engine.canPlay(2, 1));
        assertTrue(engine.canPlay(2, 2));
        engine.setDeckForTest(List.of(card(2, 3), card(3, 4)), List.of());
        assertTrue(engine.applyMove(MoveCommand.action(UnoBattleEngine.DRAW_ACTION), 2));
        assertEquals(5, engine.getHand(2).size());
        assertEquals(0, engine.getDrawnPendingSide());
        assertEquals(0, engine.getPendingDraw());
        assertEquals(3, engine.getCurrentTurn());
    }

    @Test
    void reverseSkipsOpponentWhenOnlyTwoActiveSeatsRemain() {
        UnoBattleEngine engine = fourPlayers();
        assertTrue(engine.forfeit(2));
        assertTrue(engine.forfeit(4));
        engine.setTopForTest(0, 3);
        engine.setHandForTest(1, List.of(card(0, 11), card(1, 4), card(2, 5)));
        assertTrue(engine.applyMove(MoveCommand.action(0), 1));
        assertEquals(-1, engine.getDirection());
        assertEquals(1, engine.getCurrentTurn());
    }

    @Test
    void lastZeroOrSevenWinsBeforeRedistributingHands() {
        for (int value : List.of(0, 7)) {
            UnoBattleEngine engine = fourPlayers();
            engine.setTopForTest(0, 3);
            engine.setHandForTest(1, List.of(card(0, value)));
            List<UnoBattleEngine.Card> other = engine.getHand(2);
            MoveCommand command = MoveCommand.action(0);
            assertTrue(engine.applyMove(command, 1));
            assertTrue(engine.isOver(), "最后一张 0/7 应直接清空获胜");
            assertEquals(1, engine.getWinner());
            assertTrue(engine.getHand(1).isEmpty());
            assertEquals(other, engine.getHand(2));
        }
    }

    @Test
    void sevenExchangesActiveHandsAndOpensUnoWindow() {
        UnoBattleEngine engine = fourPlayers();
        assertTrue(engine.forfeit(2));
        engine.setTopForTest(0, 3);
        UnoBattleEngine.Card retained = card(0, 0), fromThree = card(1, 6), fromFour = card(3, 8);
        engine.setHandForTest(1, List.of(card(0, 7), retained));
        engine.setHandForTest(3, List.of(fromThree));
        engine.setHandForTest(4, List.of(fromFour));
        assertTrue(engine.applyMove(MoveCommand.withTarget(0, 2, 3), 1));
        assertEquals(List.of(fromThree), engine.getHand(1));
        assertEquals(List.of(retained), engine.getHand(3));
        assertEquals(1, engine.getUnoPendingSide());
        assertTrue(engine.applyMove(MoveCommand.action(UnoBattleEngine.CALL_UNO_ACTION), 1));
        assertTrue(engine.applyMove(MoveCommand.action(0), 3));
        assertTrue(engine.isOver());
        assertEquals(3, engine.getWinner());
        assertEquals(List.of(fromFour), engine.getHand(4));
    }

    @Test
    void zeroRotatesNonFinalHandsAmongActiveSeats() {
        UnoBattleEngine engine = fourPlayers();
        assertTrue(engine.forfeit(2));
        engine.setTopForTest(0, 3);
        List<UnoBattleEngine.Card> retained = List.of(card(2, 8), card(3, 5));
        UnoBattleEngine.Card fromThree = card(1, 6), fromFour = card(3, 8);
        engine.setHandForTest(1, List.of(card(0, 0), retained.get(0), retained.get(1)));
        engine.setHandForTest(3, List.of(fromThree));
        engine.setHandForTest(4, List.of(fromFour));
        assertTrue(engine.applyMove(MoveCommand.action(0), 1));
        assertEquals(List.of(fromFour), engine.getHand(1));
        assertEquals(retained, engine.getHand(3));
        assertEquals(List.of(fromThree), engine.getHand(4));
        assertTrue(engine.getHand(2).isEmpty());
        assertEquals(1, engine.getUnoPendingSide());
    }

    private static UnoBattleEngine fourPlayers() {
        UnoBattleEngine engine = new UnoBattleEngine();
        engine.configurePlayers(4);
        engine.reset();
        return engine;
    }

    private static UnoBattleEngine.Card card(int color, int value) { return new UnoBattleEngine.Card(color, value); }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> handView(UnoBattleEngine engine, int side) {
        Map<String, Object> view = (Map<String, Object>) engine.getViewState(side);
        List<Map<String, Object>> players = (List<Map<String, Object>>) view.get("players");
        return (List<Map<String, Object>>) players.get(side - 1).get("hand");
    }

    @Test
    void forfeitRemovesSeatAndContinuesUntilLastPlayer() {
        UnoBattleEngine engine = new UnoBattleEngine();
        engine.configurePlayers(4);
        engine.reset();
        assertTrue(engine.forfeit(2));
        assertFalse(engine.isOver(), "4 人局一人退出后应继续");
        assertTrue(engine.getHand(2).isEmpty(), "退出者手牌清空");
        assertTrue(engine.forfeit(3));
        assertFalse(engine.isOver());
        assertTrue(engine.forfeit(4));
        assertTrue(engine.isOver(), "只剩一人时终局");
        assertEquals(1, engine.getWinner());
        assertEquals(List.of(1), engine.getWinningSides());
    }

    @Test
    void dealsHiddenHandsAndAcceptsAction() {
        UnoBattleEngine engine = new UnoBattleEngine();
        engine.configurePlayers(4); engine.reset();
        assertEquals(GameType.UNO, engine.type()); assertEquals(4, engine.getCount());
        assertEquals(7, engine.getHand(1).size());
        engine.setTopForTest(0, 3);
        engine.setHandForTest(1, java.util.List.of(new UnoBattleEngine.Card(0, 4), new UnoBattleEngine.Card(2, 5)));
        int action = 0;
        MoveCommand command = MoveCommand.action(action);
        assertTrue(engine.applyMove(command, 1));
        if (engine.getDrawnPendingSide() == 1) {
            assertTrue(engine.applyMove(MoveCommand.action(UnoBattleEngine.END_DRAW_ACTION), 1));
        }
        if (engine.getUnoPendingSide() == 1) {
            assertTrue(engine.applyMove(MoveCommand.action(UnoBattleEngine.CALL_UNO_ACTION), 1));
        }
        assertTrue(engine.getCurrentTurn() >= 2);
        Map<?, ?> view = (Map<?, ?>) engine.getViewState(1);
        assertEquals("UNO", view.get("kind"));
        assertFalse(((Map<?, ?>) ((java.util.List<?>) view.get("players")).get(1)).containsKey("hand"));
    }

    @Test
    void unoMustBeCalledBeforeTurnAdvances() {
        UnoBattleEngine engine = new UnoBattleEngine(); engine.reset();
        engine.setTopForTest(0, 3);
        engine.setHandForTest(1, java.util.List.of(new UnoBattleEngine.Card(0, 4), new UnoBattleEngine.Card(2, 5)));
        assertTrue(engine.applyMove(MoveCommand.action(0), 1));
        assertEquals(1, engine.getUnoPendingSide());
        assertFalse(engine.applyMove(MoveCommand.action(UnoBattleEngine.DRAW_ACTION), 1));
        assertTrue(engine.applyMove(MoveCommand.action(UnoBattleEngine.CALL_UNO_ACTION), 1));
        assertTrue(engine.getCurrentTurn() >= 2);
    }

    @Test
    void specialCardsChangeTurnAndStackDrawPenalty() {
        UnoBattleEngine engine = new UnoBattleEngine(); engine.reset();
        engine.setTopForTest(0, 3);
        engine.setHandForTest(1, java.util.List.of(new UnoBattleEngine.Card(0, 12), new UnoBattleEngine.Card(2, 5)));
        engine.setHandForTest(2, java.util.List.of(new UnoBattleEngine.Card(1, 4)));
        assertTrue(engine.applyMove(MoveCommand.action(0), 1));
        assertEquals(2, engine.getPendingDraw()); assertEquals(1, engine.getCurrentTurn());
        assertTrue(engine.applyMove(MoveCommand.action(UnoBattleEngine.CALL_UNO_ACTION), 1));
        assertTrue(engine.getCurrentTurn() >= 2);
        assertTrue(engine.applyMove(MoveCommand.action(UnoBattleEngine.DRAW_ACTION), 2));
        assertEquals(1, engine.getCurrentTurn()); assertEquals(0, engine.getPendingDraw()); assertEquals(3, engine.getHand(2).size());
    }

    @Test
    void wildDrawFourRequiresNoColorMatchAndStacks() {
        UnoBattleEngine engine = new UnoBattleEngine(); engine.reset();
        engine.setTopForTest(0, 4);
        engine.setHandForTest(1, java.util.List.of(new UnoBattleEngine.Card(0, 6), new UnoBattleEngine.Card(-1, 14), new UnoBattleEngine.Card(2, 5)));
        assertFalse(engine.applyMove(MoveCommand.withTarget(1, 1, 2), 1));
        engine.setHandForTest(1, java.util.List.of(new UnoBattleEngine.Card(-1, 14), new UnoBattleEngine.Card(2, 5)));
        assertTrue(engine.applyMove(MoveCommand.withTarget(0, 1, 2), 1));
        assertTrue(engine.applyMove(MoveCommand.action(UnoBattleEngine.CALL_UNO_ACTION), 1));
        engine.setHandForTest(2, java.util.List.of(new UnoBattleEngine.Card(-1, 14), new UnoBattleEngine.Card(1, 2)));
        assertTrue(engine.applyMove(MoveCommand.withTarget(0, 1, 1), 2));
        assertEquals(8, engine.getPendingDraw());
    }

    @Test
    void supportsEightPlayersAndValidatesSevenTargetBeforeMutating() {
        UnoBattleEngine engine = new UnoBattleEngine();
        engine.configurePlayers(8); engine.reset();
        assertEquals(8, engine.getCount());
        engine.setTopForTest(0, 4);
        engine.setHandForTest(1, java.util.List.of(new UnoBattleEngine.Card(0, 7), new UnoBattleEngine.Card(2, 5)));
        engine.setHandForTest(2, java.util.List.of(new UnoBattleEngine.Card(1, 4), new UnoBattleEngine.Card(3, 6)));
        assertFalse(engine.applyMove(MoveCommand.action(0), 1));
        assertEquals(2, engine.getHand(1).size());
        assertTrue(engine.applyMove(MoveCommand.withTarget(0, 2, 2), 1));
        assertEquals(2, engine.getHand(1).size());
        assertEquals(2, engine.getCurrentTurn());
    }

    @Test
    void anotherPlayerCanCatchMissingUnoAndPenalizeTwoCards() {
        UnoBattleEngine engine = new UnoBattleEngine();
        engine.configurePlayers(3); engine.reset();
        engine.setTopForTest(0, 3);
        engine.setHandForTest(1, java.util.List.of(new UnoBattleEngine.Card(0, 4), new UnoBattleEngine.Card(2, 5)));
        engine.setHandForTest(2, java.util.List.of(new UnoBattleEngine.Card(1, 6)));
        assertTrue(engine.applyMove(MoveCommand.action(0), 1));
        assertEquals(1, engine.getUnoPendingSide());
        assertTrue(engine.applyMove(MoveCommand.action(UnoBattleEngine.CATCH_UNO_ACTION), 2));
        assertEquals(3, engine.getHand(1).size());
        assertEquals(0, engine.getUnoPendingSide());
        assertEquals(2, engine.getCurrentTurn());
    }

    @Test
    void winnerReceivesRemainingHandPoints() {
        UnoBattleEngine engine = new UnoBattleEngine();
        engine.reset();
        engine.setTopForTest(0, 3);
        engine.setHandForTest(1, java.util.List.of(new UnoBattleEngine.Card(0, 4)));
        engine.setHandForTest(2, java.util.List.of(new UnoBattleEngine.Card(1, 9), new UnoBattleEngine.Card(-1, 13)));
        assertTrue(engine.applyMove(MoveCommand.action(0), 1));
        assertTrue(engine.isOver());
        assertTrue(engine.getScoreSummary().contains("59 分"));
        Map<?, ?> view = (Map<?, ?>) engine.getViewState(1);
        java.util.List<?> players = (java.util.List<?>) view.get("players");
        assertEquals(59, ((Map<?, ?>) players.get(0)).get("score"));
    }
}







