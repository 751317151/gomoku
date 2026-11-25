package com.gomoku.game;

import java.util.concurrent.ThreadLocalRandom;

/** 骰子竞速 AI：只按公开排名、待收分和本人护盾决定是否继续冒险。 */
public final class DiceRaceAI implements GameAI {
    static final DiceRaceAI INSTANCE = new DiceRaceAI();
    private DiceRaceAI() {}

    @Override
    public MoveCommand decide(GameEngine engine, int side, int difficulty) {
        if (!(engine instanceof DiceRaceEngine)) return null;
        DiceRaceEngine race = (DiceRaceEngine) engine;
        if (!race.isAwaitingAction(side)) return null;
        if (race.isPressing()) {
            java.util.List<Integer> actions = race.legalActions(side);
            if (difficulty <= 2) return MoveCommand.action(actions.get(ThreadLocalRandom.current().nextInt(actions.size())));
            int projected = race.getScore(side) + race.getPendingScore(side);
            int otherBest = 0;
            for (int other = 1; other <= 8; other++) if (other != side) {
                otherBest = Math.max(otherBest, race.getScore(other) + race.getPendingScore(other));
            }
            if (race.getRound() == DiceRaceEngine.ROUNDS && projected > otherBest) {
                return MoveCommand.action(DiceRaceEngine.ACTION_BANK);
            }
            if (difficulty >= 6 && actions.contains(DiceRaceEngine.ACTION_SHIELD)
                    && (race.getRound() >= 5 || race.getPendingScore(side) >= 10)) {
                return MoveCommand.action(DiceRaceEngine.ACTION_SHIELD);
            }
            return MoveCommand.action(race.getPendingScore(side) >= 11 && projected > otherBest
                    ? DiceRaceEngine.ACTION_BANK : DiceRaceEngine.ACTION_PUSH);
        }
        int choice;
        double noise = ThreadLocalRandom.current().nextDouble();
        if (difficulty <= 2 && noise < 0.45) {
            choice = ThreadLocalRandom.current().nextInt(DiceRaceEngine.CHOICES);
        } else if (race.getScore(side) < leaderScore(race) && noise < (difficulty >= 6 ? 0.65 : 0.45)) {
            choice = DiceRaceEngine.CHOICE_CHASE;
        } else if (difficulty >= 6 && noise < 0.30) {
            choice = DiceRaceEngine.CHOICE_RISK;
        } else {
            choice = DiceRaceEngine.CHOICE_SAFE;
        }
        return MoveCommand.action(choice);
    }

    private int leaderScore(DiceRaceEngine race) {
        int best = 0;
        for (int side = 1; side <= 8; side++) best = Math.max(best, race.getScore(side));
        return best;
    }
}
