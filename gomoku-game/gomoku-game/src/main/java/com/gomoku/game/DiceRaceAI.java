package com.gomoku.game;

import java.util.concurrent.ThreadLocalRandom;

/** 骰子竞速 AI：根据当前排名在稳健、冒险、追击之间做轻量决策。 */
public final class DiceRaceAI implements GameAI {
    static final DiceRaceAI INSTANCE = new DiceRaceAI();
    private DiceRaceAI() {}

    @Override
    public MoveCommand decide(GameEngine engine, int side, int difficulty) {
        if (!(engine instanceof DiceRaceEngine)) return null;
        DiceRaceEngine race = (DiceRaceEngine) engine;
        if (!race.isAwaitingAction(side)) return null;
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
