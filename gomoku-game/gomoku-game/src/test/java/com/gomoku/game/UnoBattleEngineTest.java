package com.gomoku.game;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class UnoBattleEngineTest {
    @Test
    void dealsHiddenHandsAndAcceptsAction() {
        UnoBattleEngine engine = new UnoBattleEngine();
        engine.configurePlayers(4); engine.reset();
        assertEquals(GameType.UNO, engine.type()); assertEquals(4, engine.getCount());
        assertEquals(7, engine.getHand(1).size());
        int action = UnoBattleEngine.DRAW_ACTION;
        for (int i = 0; i < engine.getHand(1).size(); i++) if (engine.canPlay(1, i)) { action = i; break; }
        UnoBattleEngine.Card chosen = action == UnoBattleEngine.DRAW_ACTION ? null : engine.getHand(1).get(action);
        MoveCommand command = chosen != null && chosen.color < 0 ? MoveCommand.withTarget(action, 1, 0) : MoveCommand.action(action);
        assertTrue(engine.applyMove(command, 1));
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
}



