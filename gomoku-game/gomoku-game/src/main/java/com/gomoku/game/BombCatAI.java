package com.gomoku.game;

/** 炸弹猫 AI：只读公开信息与自己的手牌，简单按威胁和剩余回合出牌。 */
public final class BombCatAI implements GameAI {
    static final BombCatAI INSTANCE = new BombCatAI();
    private BombCatAI() {}

    @Override
    public MoveCommand decide(GameEngine engine, int side, int difficulty) {
        if (!(engine instanceof BombCatEngine)) return null;
        BombCatEngine game = (BombCatEngine) engine;
        if (!game.isAwaitingAction(side)) return null;
        if (game.getPendingReinsertSide() == side) {
            // 抽到炸弹后必须先秘密放回，否则房间会停在等待状态。
            return MoveCommand.action(BombCatEngine.REINSERT_MIDDLE);
        }
        // 本回合行动次数用尽后只能抽牌；否则第四次出牌会被引擎拒绝，
        // 而 autoEndsTurn=true 让房间没有可用的兜底结束动作。
        if (game.playsLeft(side) <= 0) {
            return MoveCommand.action(BombCatEngine.DRAW_ACTION);
        }
        java.util.List<BombCatEngine.Card> hand = game.getHand(side);
        boolean hasDefuse = game.hasCard(side, BombCatEngine.CardType.DEFUSE);

        // 有拆弹器时保留预知/攻击用于开局；没拆弹器时优先偷牌自救。
        int favor = find(hand, BombCatEngine.CardType.FAVOR);
        if (!hasDefuse && favor >= 0) {
            int target = weakestOpponent(game, side);
            if (target > 0) return MoveCommand.withTarget(favor, 2, target);
        }
        int seeFuture = find(hand, BombCatEngine.CardType.SEE_FUTURE);
        if (seeFuture >= 0 && (difficulty >= 4 || hasDefuse)) return MoveCommand.action(seeFuture);
        int attack = find(hand, BombCatEngine.CardType.ATTACK);
        if (attack >= 0 && difficulty >= 4) return MoveCommand.action(attack);
        int skip = find(hand, BombCatEngine.CardType.SKIP);
        if (skip >= 0 && difficulty >= 5) return MoveCommand.action(skip);
        if (favor >= 0) {
            int target = weakestOpponent(game, side);
            if (target > 0) return MoveCommand.withTarget(favor, 2, target);
        }
        int card = firstPlayable(hand);
        if (card >= 0) return MoveCommand.action(card);
        return MoveCommand.action(BombCatEngine.DRAW_ACTION);
    }

    /**
     * 每次只返回一张牌；房间在一手结束后会重新调用 AI。
     * 真正的「本回合已经出过哪些牌」由引擎手牌变化决定，不在这里缓存。
     */
    private static int firstPlayable(java.util.List<BombCatEngine.Card> hand) {
        for (int i = 0; i < hand.size(); i++) {
            BombCatEngine.CardType type = hand.get(i).type;
            // 偷牌必须先选定目标，上面两个分支已经处理；避免在无人可偷时
            // 反复返回一张会被引擎拒绝的牌。
            if (type != BombCatEngine.CardType.DEFUSE
                    && type != BombCatEngine.CardType.BOMB
                    && type != BombCatEngine.CardType.FAVOR) return i;
        }
        return -1;
    }

    private static int find(java.util.List<BombCatEngine.Card> hand, BombCatEngine.CardType type) {
        for (int i = 0; i < hand.size(); i++) if (hand.get(i).type == type) return i;
        return -1;
    }

    private static int weakestOpponent(BombCatEngine game, int side) {
        int target = 0, smallest = Integer.MAX_VALUE;
        for (int candidate = 1; candidate <= game.getPlayerCount(); candidate++) {
            if (candidate == side || !game.isActive(candidate)) continue;
            int size = game.getHand(candidate).size();
            if (size < smallest) {
                smallest = size;
                target = candidate;
            }
        }
        return target;
    }
}
