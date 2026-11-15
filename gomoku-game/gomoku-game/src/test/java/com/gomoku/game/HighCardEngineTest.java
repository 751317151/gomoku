package com.gomoku.game;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HighCardEngineTest {
    @Test
    void finalRoundAwardsDoublePoints() {
        HighCardEngine engine = new HighCardEngine();
        engine.configurePlayers(2);
        engine.reset();
        for (int round = 1; round <= 5; round++) {
            int first = 11 - round;
            int second = round;
            assertTrue(engine.applyMove(MoveCommand.action(first), 1));
            assertTrue(engine.applyMove(MoveCommand.action(second), 2));
        }
        assertTrue(engine.isOver());
        assertEquals(List.of(1), engine.getWinningSides());
        Map<String, Object> view = (Map<String, Object>) engine.getViewState(1);
        List<Map<String, Object>> players = (List<Map<String, Object>>) view.get("players");
        assertEquals(6, players.get(0).get("score"));
    }

    @Test
    void viewExposesCurrentRoundValue() {
        HighCardEngine engine = new HighCardEngine();
        engine.configurePlayers(2);
        engine.reset();
        Map<String, Object> view = (Map<String, Object>) engine.getViewState(1);
        assertEquals(1, view.get("roundPoints"));
        for (int i = 0; i < 4; i++) {
            assertTrue(engine.applyMove(MoveCommand.action(10 - i), 1));
            assertTrue(engine.applyMove(MoveCommand.action(1 + i), 2));
        }
        view = (Map<String, Object>) engine.getViewState(1);
        assertEquals(2, view.get("roundPoints"));
    }

    @Test
    void aceDoublesOneCardAndCanOnlyBeUsedOnce() {
        HighCardEngine engine = new HighCardEngine();
        engine.configurePlayers(2); engine.reset();
        assertTrue(engine.applyMove(MoveCommand.withTarget(HighCardEngine.ACE_ACTION, 1, 3), 1));
        assertTrue(engine.applyMove(MoveCommand.action(10), 2));
        Map<String, Object> view = (Map<String, Object>) engine.getViewState(1);
        List<Map<String, Object>> players = (List<Map<String, Object>>) view.get("players");
        assertEquals(false, players.get(0).get("aceAvailable"));
        assertTrue(engine.applyMove(MoveCommand.action(2), 1));
        assertTrue(engine.applyMove(MoveCommand.action(1), 2));
        assertFalse(engine.applyMove(MoveCommand.withTarget(HighCardEngine.ACE_ACTION, 1, 2), 1));
    }

    @Test
    void invalidAceTargetDoesNotConsumeAce() {
        HighCardEngine engine = new HighCardEngine();
        engine.configurePlayers(2); engine.reset();
        assertFalse(engine.applyMove(MoveCommand.withTarget(HighCardEngine.ACE_ACTION, 1, 99), 1));
        Map<String, Object> view = (Map<String, Object>) engine.getViewState(1);
        List<Map<String, Object>> players = (List<Map<String, Object>>) view.get("players");
        assertEquals(true, players.get(0).get("aceAvailable"));
    }
}
