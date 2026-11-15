package com.gomoku.game;

import java.util.concurrent.ThreadLocalRandom;

/** 宝石拍卖 AI：根据金币和当前分数决定保守/抢高价值宝石。 */
public final class GemAuctionAI implements GameAI {
    static final GemAuctionAI INSTANCE = new GemAuctionAI();
    private GemAuctionAI() {}
    @Override public MoveCommand decide(GameEngine engine, int side, int difficulty) {
        if (!(engine instanceof GemAuctionEngine)) return null;
        GemAuctionEngine auction = (GemAuctionEngine) engine;
        if (!auction.isAwaitingAction(side)) return null;
        int max = Math.min(GemAuctionEngine.MAX_BID, auction.getCoins(side));
        int value = GemAuctionEngine.GEM_VALUES[Math.min(auction.getActionRound(), GemAuctionEngine.ROUNDS - 1)];
        double chance = ThreadLocalRandom.current().nextDouble();
        int bid = value >= 9 && difficulty >= 5 ? Math.min(max, 7 + ThreadLocalRandom.current().nextInt(4)) : Math.min(max, 2 + ThreadLocalRandom.current().nextInt(5));
        if (difficulty <= 2 || chance < 0.2) bid = Math.min(max, ThreadLocalRandom.current().nextInt(max + 1));
        return MoveCommand.action(bid);
    }
}
