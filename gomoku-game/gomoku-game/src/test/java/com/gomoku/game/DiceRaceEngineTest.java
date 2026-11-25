package com.gomoku.game;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DiceRaceEngineTest {
    @Test
    void roomStartsFourPlayerRaceAndSettlesAllRounds() {
        GameRoom room = new GameRoom("ROOM-DICE", GameType.DICE_RACE, true, 4);
        try {
            for (int side = 1; side <= 4; side++) {
                assertTrue(room.addPlayer(new com.gomoku.model.Player("dice-" + side, "玩家" + side, new io.netty.channel.embedded.EmbeddedChannel())));
            }
            assertEquals(GameRoom.State.PLAYING, room.getState());
            for (int round = 0; round < DiceRaceEngine.ROUNDS; round++) {
                for (com.gomoku.model.Player player : room.getPlayers()) {
                    assertTrue(room.handleCommand(player, MoveCommand.action(round % DiceRaceEngine.CHOICES), -1));
                }
                for (com.gomoku.model.Player player : room.getPlayers()) {
                    if (room.getEngine().isAwaitingAction(player.getStone())
                            && ((DiceRaceEngine) room.getEngine()).isPressing()) {
                        assertTrue(room.handleCommand(player, MoveCommand.action(DiceRaceEngine.ACTION_BANK), -1));
                    }
                }
            }
            assertEquals(GameRoom.State.FINISHED, room.getState());
            assertTrue(room.getEngine().getMoveCount() >= DiceRaceEngine.ROUNDS * 4);
            assertTrue(room.getEngine().getMoveCount() <= DiceRaceEngine.ROUNDS * 8);
        } finally {
            room.notifyShutdown();
            for (com.gomoku.model.Player player : room.getPlayers()) {
                if (player.getConnection() instanceof io.netty.channel.embedded.EmbeddedChannel) {
                    ((io.netty.channel.embedded.EmbeddedChannel) player.getConnection()).finishAndReleaseAll();
                }
            }
        }
    }
    @Test
    void supportsEightPlayersAndHiddenChoices() {
        DiceRaceEngine e = new DiceRaceEngine(new Random(7));
        e.configurePlayers(4);
        e.reset();
        assertTrue(e.isAwaitingAction(1));
        assertTrue(e.applyMove(MoveCommand.action(DiceRaceEngine.CHOICE_SAFE), 1));
        assertEquals(1, e.getMoveCount());
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> view = (java.util.Map<String, Object>) e.getViewState(2);
        java.util.List<java.util.Map<String, Object>> players = (java.util.List<java.util.Map<String, Object>>) view.get("players");
        assertEquals(Boolean.TRUE, players.get(0).get("answered"));
        assertFalse(players.get(0).containsKey("choice"));
        assertTrue(e.applyMove(MoveCommand.action(DiceRaceEngine.CHOICE_CHASE), 2));
        assertTrue(e.applyMove(MoveCommand.action(DiceRaceEngine.CHOICE_RISK), 3));
        assertTrue(e.applyMove(MoveCommand.action(DiceRaceEngine.CHOICE_SAFE), 4));
        assertEquals(1, (int) view.getOrDefault("round", 1));
    }

    @Test
    void sixRoundsProduceWinnerOrDraw() {
        DiceRaceEngine e = new DiceRaceEngine(new Random(1));
        e.configurePlayers(2);
        e.reset();
        for (int round = 0; round < DiceRaceEngine.ROUNDS; round++) {
            assertTrue(e.applyMove(MoveCommand.action(DiceRaceEngine.CHOICE_SAFE), 1));
            assertTrue(e.applyMove(MoveCommand.action(DiceRaceEngine.CHOICE_SAFE), 2));
        }
        assertTrue(e.isOver());
        assertEquals(2 * DiceRaceEngine.ROUNDS, e.getMoveCount());
        assertNotNull(e.getScoreSummary());
    }
}
