package com.gomoku.game;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class DiceRacePressTest {
    private static class Rolls extends Random {
        private final int[] rolls;
        private int index;
        Rolls(int... rolls) { this.rolls = rolls; }
        @Override public int nextInt(int bound) {
            assertEquals(6, bound);
            assertTrue(index < rolls.length, "unexpected roll");
            return rolls[index++] - 1;
        }
    }
    private DiceRaceEngine game(int players, int... rolls) {
        DiceRaceEngine game = new DiceRaceEngine(new Rolls(rolls));
        game.configurePlayers(players); game.reset(); return game;
    }
    private void act(DiceRaceEngine game, int side, int action) {
        assertTrue(game.applyMove(MoveCommand.action(action), side));
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> view(DiceRaceEngine game, int side) { return (Map<String, Object>) game.getViewState(side); }
    @SuppressWarnings("unchecked")
    private Map<String, Object> player(DiceRaceEngine game, int viewer, int side) {
        return ((List<Map<String, Object>>) view(game, viewer).get("players")).get(side - 1);
    }

    @Test void firstChoiceAndPushDecisionHaveIndependentHiddenBarriers() {
        DiceRaceEngine game = game(3, 4, 5, 3, 4, 6, 1);
        act(game, 1, 1);
        assertFalse(player(game, 2, 1).containsKey("choice"));
        assertFalse(player(game, 0, 1).containsKey("dice"));
        assertEquals(1, player(game, 1, 1).get("choice"));
        assertEquals(0, game.getActionRound());
        act(game, 2, 1); act(game, 3, 0);
        assertTrue(game.isPressing()); assertEquals(1, game.getActionRound());
        assertFalse(game.isAwaitingAction(3));
        assertEquals(9, game.getPendingScore(1));
        assertEquals(7, game.getPendingScore(2));
        act(game, 1, 5);
        assertEquals(5, player(game, 1, 1).get("decision"));
        assertFalse(player(game, 2, 1).containsKey("decision"));
        assertFalse(player(game, 0, 1).containsKey("decision"));
        assertEquals(true, player(game, 0, 1).get("shieldAvailable"));
        assertEquals(0, game.getScore(1));
        assertTrue(game.getLastMoveEffects().isEmpty());
        act(game, 2, 3);
        assertEquals(9, game.getScore(1)); assertEquals(7, game.getScore(2)); assertEquals(6, game.getScore(3));
        assertEquals(false, player(game, 0, 1).get("shieldAvailable"));
        assertEquals(2, game.getActionRound()); assertEquals(2, game.getRound());
        assertEquals(1, player(game, 0, 1).get("lastPush"));
    }

    @Test void bankingKeepsPotAndDoesNotConsumeShieldOrRollAgain() {
        DiceRaceEngine game = game(2, 6, 5, 2);
        act(game, 1, 1); act(game, 2, 0); act(game, 1, 3);
        assertEquals(11, game.getScore(1));
        assertEquals(true, player(game, 1, 1).get("shieldAvailable"));
        assertEquals(0, player(game, 1, 1).get("lastPush"));
        assertEquals(11, game.getMoveHistory().get(2)[2]);
    }

    @Test void unprotectedOneBustsOnlyCurrentRoundAndDoesNotUseShield() {
        DiceRaceEngine game = game(2, 6, 4, 5, 4, 2, 1);
        act(game, 1, 0); act(game, 2, 0);
        act(game, 1, 1); act(game, 2, 0); act(game, 1, 4);
        assertEquals(6, game.getScore(1));
        assertEquals(0, player(game, 0, 1).get("lastRoundScore"));
        assertEquals(true, player(game, 0, 1).get("shieldAvailable"));
        assertEquals(0, player(game, 0, 1).get("streak"));
    }

    @Test void successfulPushAddsDieAndShieldIsSpentEvenWithoutBust() {
        DiceRaceEngine game = game(2, 5, 5, 2, 6, 4, 4, 2);
        act(game, 1, 1); act(game, 2, 0); act(game, 1, 5);
        assertEquals(16, game.getScore(1));
        act(game, 1, 1); act(game, 2, 0);
        assertEquals(Arrays.asList(3, 4), game.legalActions(1));
        int seq = game.getMoveSeq();
        assertFalse(game.applyMove(MoveCommand.action(5), 1));
        assertEquals(seq, game.getMoveSeq());
        act(game, 1, 3); assertEquals(24, game.getScore(1));
    }

    @Test void initialRiskBustCannotBeShieldedAndDoesNotRequireDecision() {
        DiceRaceEngine game = game(2, 1, 6, 5);
        act(game, 1, 1); act(game, 2, 0);
        assertFalse(game.isPressing()); assertEquals(2, game.getRound());
        assertEquals(0, game.getScore(1)); assertEquals(5, game.getScore(2));
        assertEquals(true, player(game, 0, 1).get("shieldAvailable"));
        assertArrayEquals(new int[]{1, 6}, (int[]) player(game, 0, 1).get("lastDice"));
    }

    @Test void chaseUsesLeaderboardBeforeAllFirstRolls() {
        DiceRaceEngine game = game(2, 6, 2, 3, 4);
        act(game, 1, 0); act(game, 2, 0);
        act(game, 2, 2); act(game, 1, 2);
        assertEquals(8, game.getScore(1)); assertEquals(9, game.getScore(2));
    }

    @Test void invalidAndDuplicateCommandsDoNotMutateOrRevealAnything() {
        DiceRaceEngine game = game(2, 5, 5, 4, 4);
        assertFalse(game.applyMove(null, 1));
        assertFalse(game.applyMove(MoveCommand.at(0, 0), 1));
        assertFalse(game.applyMove(MoveCommand.action(3), 1));
        assertFalse(game.applyMove(MoveCommand.action(0), 0));
        act(game, 1, 1);
        assertFalse(game.applyMove(MoveCommand.action(0), 1));
        act(game, 2, 1);
        assertFalse(game.applyMove(MoveCommand.action(1), 1));
        act(game, 1, 3);
        assertFalse(game.applyMove(MoveCommand.action(5), 1));
        assertEquals(3, game.getMoveCount()); assertEquals(0, game.getScore(1));
        act(game, 2, 3);
        Object before = view(game, 0).get("revealed");
        assertFalse(game.applyMove(MoveCommand.action(99), 1));
        assertEquals(before, view(game, 0).get("revealed"));
    }

    @Test void forfeitingLastPendingRiskPlayerAdvancesRemainingPlayers() {
        DiceRaceEngine game = game(3, 4, 4, 3, 2);
        act(game, 1, 1); act(game, 2, 0); act(game, 3, 0);
        assertTrue(game.forfeit(1));
        assertFalse(game.isOver()); assertFalse(game.isPressing());
        assertEquals(2, game.getRound()); assertEquals(3, game.getScore(2));
        assertEquals(2, game.getScore(3)); assertFalse(game.isAwaitingAction(1));
        assertEquals(2, game.getCurrentTurn());
        assertTrue(game.forfeit(2)); assertEquals(3, game.getWinner());
        assertEquals(List.of(3), game.getWinningSides());
    }

    @Test void forfeitingDuringChoiceNeverRollsForDepartedSeat() {
        DiceRaceEngine game = game(3, 5, 6);
        act(game, 2, 0); act(game, 3, 0);
        assertEquals(1, game.getRound());
        assertTrue(game.forfeit(1));
        assertEquals(5, game.getScore(2)); assertEquals(6, game.getScore(3));
        assertEquals(2, game.getRound()); assertEquals(2, game.getCurrentTurn());
    }

    @Test void snapshotsCannotMutateStateAndResetRestoresShield() {
        DiceRaceEngine game = game(2, 5, 5, 3, 1);
        act(game, 1, 1); act(game, 2, 0);
        ((int[]) player(game, 0, 1).get("dice"))[0] = 1;
        assertEquals(10, game.getPendingScore(1));
        act(game, 1, 5);
        game.getMoveHistory().get(2)[2] = 999; game.getLastMoveEffects().get(0)[2] = 999;
        ((int[]) player(game, 0, 1).get("lastDice"))[0] = 1;
        assertEquals(10, game.getMoveHistory().get(2)[2]);
        assertEquals(10, game.getScore(1));
        game.reset();
        assertEquals(0, game.getMoveCount()); assertEquals(0, game.getActionRound());
        assertEquals(0, game.getScore(1)); assertEquals(true, player(game, 1, 1).get("shieldAvailable"));
        assertFalse(player(game, 1, 1).containsKey("lastChoice"));
    }

    @Test void eightPlayerAiGamesTerminateAndRecordEveryAcceptedAction() {
        for (int difficulty : new int[]{2, 4, 6}) {
            DiceRaceEngine game = new DiceRaceEngine(new Random(20261125));
            game.configurePlayers(8); game.reset();
            while (!game.isOver()) {
                int side = game.getCurrentTurn();
                MoveCommand command = DiceRaceAI.INSTANCE.decide(game, side, difficulty);
                assertNotNull(command); assertTrue(game.legalActions(side).contains(command.getAction()));
                assertTrue(game.applyMove(command, side));
                assertTrue(game.getMoveCount() <= 96);
            }
            assertEquals(6, view(game, 0).get("lastRound"));
            assertFalse(game.getWinningSides().isEmpty());
            assertEquals(game.getMoveSeq(), game.getMoveHistory().size());
            assertTrue(game.getMoveHistory().stream().allMatch(row->row.length == 8));
            assertNull(DiceRaceAI.INSTANCE.decide(game, 1, difficulty));
        }
    }
}
