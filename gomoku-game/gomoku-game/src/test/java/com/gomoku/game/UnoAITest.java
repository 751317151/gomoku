package com.gomoku.game;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class UnoAITest {
    @Test void finalWildStillChoosesColourAndFinalSevenNeedsNoExchange() {
        for (int value : List.of(7, 13, 14)) for (int level : List.of(2, 4, 6)) {
            UnoBattleEngine engine = privateHands();
            engine.setTopForTest(0, 3);
            engine.setHandForTest(1, List.of(card(value >= 13 ? -1 : 0, value)));
            MoveCommand choice = UnoAI.INSTANCE.decide(engine, 1, level);
            assertTrue(engine.applyMove(choice, 1));
            assertTrue(engine.isOver());
            assertEquals(1, engine.getWinner());
        }
    }

    @Test void sevenTargetsSmallestActiveHandWithoutReadingOpponentsCards() {
        for (int level : List.of(2, 4, 6)) {
            UnoBattleEngine engine = privateHands();
            engine.setTopForTest(0, 3);
            engine.setHandForTest(1, List.of(card(0, 7), card(1, 5), card(2, 6), card(3, 9)));
            engine.setHandForTest(3, List.of(card(2, 8)));
            engine.setHandForTest(4, List.of(card(1, 1), card(2, 2), card(3, 3)));
            assertTrue(engine.forfeit(2));
            Object before = engine.getViewState(1);
            MoveCommand choice = UnoAI.INSTANCE.decide(engine, 1, level);
            assertEquals(before, engine.getViewState(1));
            assertEquals(0, choice.getAction());
            assertEquals(2, choice.getTargetSide());
            assertEquals(3, choice.getTargetIndex());
            assertTrue(engine.applyMove(choice, 1));
            assertEquals(1, engine.getHandCount(1));
            assertEquals(3, engine.getHandCount(3));
            assertEquals(1, engine.getUnoPendingSide());
        }
    }

    @Test void drawnSevenUsesActiveSmallestTarget() {
        UnoBattleEngine engine = privateHands();
        engine.setTopForTest(0, 3);
        engine.setHandForTest(1, List.of(card(1, 5), card(2, 6)));
        engine.setHandForTest(3, List.of(card(2, 8)));
        engine.setHandForTest(4, List.of(card(1, 1), card(2, 2), card(3, 3)));
        assertTrue(engine.forfeit(2));
        engine.setDeckForTest(List.of(card(0, 7)), List.of());
        assertTrue(engine.applyMove(MoveCommand.action(UnoBattleEngine.DRAW_ACTION), 1));
        MoveCommand choice = UnoAI.INSTANCE.decide(engine, 1, 6);
        assertEquals(2, choice.getAction());
        assertEquals(3, choice.getTargetIndex());
        assertTrue(engine.applyMove(choice, 1));
        assertEquals(1, engine.getUnoPendingSide());
    }

    @Test void strongerAiAvoidsBadExchangeAndCanKeepDrawnSeven() {
        UnoBattleEngine engine = privateHands();
        engine.setTopForTest(0, 3);
        engine.setHandForTest(1, List.of(card(0, 7), card(0, 8), card(2, 6)));
        assertEquals(1, UnoAI.INSTANCE.decide(engine, 1, 6).getAction());
        engine.setHandForTest(1, List.of(card(1, 5), card(2, 6)));
        engine.setDeckForTest(List.of(card(0, 7)), List.of());
        assertTrue(engine.applyMove(MoveCommand.action(UnoBattleEngine.DRAW_ACTION), 1));
        MoveCommand choice = UnoAI.INSTANCE.decide(engine, 1, 6);
        assertEquals(UnoBattleEngine.END_DRAW_ACTION, choice.getAction());
        assertTrue(engine.applyMove(choice, 1));
        assertEquals(3, engine.getHandCount(1));
        assertEquals(2, engine.getCurrentTurn());
    }

    @Test void strongerAiBlocksNextSeatAndConservesWild() {
        UnoBattleEngine engine = privateHands();
        engine.setTopForTest(0, 3);
        engine.setHandForTest(1, List.of(card(-1, 13), card(0, 9), card(0, 10)));
        engine.setHandForTest(2, List.of(card(2, 8)));
        MoveCommand choice = UnoAI.INSTANCE.decide(engine, 1, 6);
        assertEquals(2, choice.getAction());
        assertTrue(engine.applyMove(choice, 1));
        assertEquals(3, engine.getCurrentTurn());
    }

    @Test void neverChoosesIllegalFourAndDoesNotActForOtherSeats() {
        UnoBattleEngine engine = privateHands();
        engine.setTopForTest(0, 3);
        engine.setHandForTest(1, List.of(card(-1, 14), card(0, 4), card(2, 6)));
        for (int level : List.of(2, 4, 6)) assertEquals(1, UnoAI.INSTANCE.decide(engine, 1, level).getAction());
        assertNull(UnoAI.INSTANCE.decide(engine, 2, 6));
        assertTrue(engine.forfeit(2));
        assertNull(UnoAI.INSTANCE.decide(engine, 2, 6));
    }

    @Test void allLevelsFinishLegalGamesAtTwoFourAndEightSeats() {
        for (int level : List.of(2, 4, 6)) for (int seats : List.of(2, 4, 8)) {
            UnoBattleEngine engine = new UnoBattleEngine();
            engine.configurePlayers(seats); engine.reset();
            int steps = 0;
            while (!engine.isOver() && steps++ < 310) {
                int side = engine.getCurrentTurn();
                MoveCommand choice = UnoAI.INSTANCE.decide(engine, side, level);
                assertNotNull(choice);
                assertTrue(engine.applyMove(choice, side), "非法 AI 行动：" + choice);
            }
            assertTrue(engine.isOver());
            assertEquals(engine.getMoveCount(), engine.getMoveSeq());
        }
    }

    private static UnoBattleEngine privateHands() {
        UnoBattleEngine engine = new UnoBattleEngine() {
            @Override public List<Card> getHand(int side) {
                if (side != 1) throw new AssertionError("AI 不得读取对手的隐藏手牌");
                return super.getHand(side);
            }
        };
        engine.configurePlayers(4); engine.reset();
        return engine;
    }
    private static UnoBattleEngine.Card card(int color, int value) { return new UnoBattleEngine.Card(color, value); }
}
