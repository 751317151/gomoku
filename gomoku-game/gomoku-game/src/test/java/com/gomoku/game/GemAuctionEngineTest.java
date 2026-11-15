package com.gomoku.game;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class GemAuctionEngineTest {
    @Test
    void fiveRoundsConsumeCoinsAndRevealHiddenBids() {
        GemAuctionEngine engine = new GemAuctionEngine(new Random(3));
        engine.configurePlayers(3);
        engine.reset();
        assertTrue(engine.applyMove(MoveCommand.action(2), 1));
        Map<String,Object> view = (Map<String,Object>) engine.getViewState(2);
        List<Map<String,Object>> players = (List<Map<String,Object>>) view.get("players");
        assertFalse(players.get(0).containsKey("bid"));
        assertTrue(engine.applyMove(MoveCommand.action(5), 2));
        assertTrue(engine.applyMove(MoveCommand.action(4), 3));
        view = (Map<String,Object>) engine.getViewState(2);
        assertEquals(Boolean.TRUE, view.get("revealed"));
        assertEquals(5, ((Number)((List<Map<String,Object>>)view.get("players")).get(1).get("bid")).intValue());
        for (int round = 1; round < GemAuctionEngine.ROUNDS; round++) {
            for (int side = 1; side <= 3; side++) assertTrue(engine.applyMove(MoveCommand.action(1), side));
        }
        assertTrue(engine.isOver());
        assertNotNull(engine.getScoreSummary());
        assertEquals(3 * GemAuctionEngine.ROUNDS, engine.getMoveCount());
    }

    @Test
    void cannotBidMoreThanAvailableCoins() {
        GemAuctionEngine engine = new GemAuctionEngine();
        engine.configurePlayers(2);
        engine.reset();
        assertFalse(engine.applyMove(MoveCommand.action(GemAuctionEngine.STARTING_COINS + 1), 1));
        assertTrue(engine.applyMove(MoveCommand.action(0), 1));
    }
}
