package com.gomoku.game;

import java.util.concurrent.ThreadLocalRandom;

/** 只按公开图录、收藏组合、金币和上一轮出价估值，不读取本轮暗投。 */
public final class GemAuctionAI implements GameAI {
    static final GemAuctionAI INSTANCE = new GemAuctionAI();
    private GemAuctionAI() {}
    @Override public MoveCommand decide(GameEngine engine, int side, int difficulty) {
        if (!(engine instanceof GemAuctionEngine)) return null;
        GemAuctionEngine auction = (GemAuctionEngine) engine;
        if (!auction.isAwaitingAction(side)) return null;
        int max = Math.min(GemAuctionEngine.MAX_BID, auction.getCoins(side));
        if (difficulty <= 2) return MoveCommand.action(ThreadLocalRandom.current().nextInt(max + 1));
        int remaining = GemAuctionEngine.ROUNDS - auction.getActionRound();
        int bonus = auction.getBonusGain(side), value = auction.getCurrentValue() + bonus;
        if (max == 0 || (max <= 4 && remaining > 1 && bonus == 0)) return MoveCommand.action(0);
        int budget = (auction.getCoins(side) + remaining - 1) / remaining;
        int target = remaining == 1 ? max : budget + (value >= 12 ? 3 : value >= 8 ? 1 : -2);
        if (difficulty >= 6 && bonus > 0) {
            int seen = 0;
            for (int other = 1; other <= GemAuctionEngine.MAX_PLAYERS; other++) {
                if (other != side && auction.isActive(other)) seen = Math.max(seen, auction.getLastBid(other));
            }
            target = Math.max(target, Math.min(budget + 3, seen + 1));
        }
        target += ThreadLocalRandom.current().nextInt(3) - 1;
        return MoveCommand.action(Math.max(1, Math.min(max, target)));
    }
}
