package com.gomoku.game;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 21 点 AI：基于基础策略表做简化决策。
 *
 * - 12 点以下必叫牌；
 * - 17 点以上停牌；
 * - 13–16 点看庄家明牌：庄家 7 以上更要牌，6 以下少冒险；
 * - 简单难度偶尔随机，困难难度严格遵守阈值。
 */
public final class BlackjackAI implements GameAI {
    static final BlackjackAI INSTANCE = new BlackjackAI();
    private BlackjackAI() {}

    @Override
    public boolean shouldEndTurn(GameEngine engine, int side, int difficulty) {
        if (!(engine instanceof BlackjackEngine)) return false;
        BlackjackEngine game = (BlackjackEngine) engine;
        return decideStand(game, side, difficulty);
    }

    @Override
    public MoveCommand decide(GameEngine engine, int side, int difficulty) {
        if (!(engine instanceof BlackjackEngine)) return null;
        BlackjackEngine game = (BlackjackEngine) engine;
        if (!game.isAwaitingAction(side)) return null;
        if (decideStand(game, side, difficulty)) return MoveCommand.action(BlackjackEngine.ACTION_STAND);
        return MoveCommand.action(BlackjackEngine.ACTION_HIT);
    }

    private boolean decideStand(BlackjackEngine game, int side, int difficulty) {
        int total = game.getTotal(side);
        if (total >= 17) return true;
        if (total <= 11) return false;
        // 只读庄家明牌，AI 不能偷看暗牌
        int visible = game.getDealerVisibleTotal();
        if (difficulty <= 2 && ThreadLocalRandom.current().nextDouble() < 0.25) {
            return ThreadLocalRandom.current().nextBoolean();
        }
        if (difficulty >= 6) return total >= 16 && visible <= 6;
        return total >= 15;
    }
}
