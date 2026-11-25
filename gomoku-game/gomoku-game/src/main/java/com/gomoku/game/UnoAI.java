package com.gomoku.game;

import java.util.List;

/** UNO AI：只读自己的牌与公开手牌数，中高档评估换手收益、下家威胁并保留万能牌。 */
public class UnoAI implements GameAI {
    static final UnoAI INSTANCE = new UnoAI();
    private UnoAI() {}
    public MoveCommand decide(GameEngine engine, int side, int difficulty) {
        if (!(engine instanceof UnoBattleEngine)) return null;
        UnoBattleEngine uno = (UnoBattleEngine) engine;
        if (uno.isOver() || !uno.isActive(side) || uno.getCurrentTurn() != side) return null;
        if (uno.getUnoPendingSide() == side) return MoveCommand.action(UnoBattleEngine.CALL_UNO_ACTION);
        if (uno.getDrawnPendingSide() == side) {
            int index = uno.getDrawnCardIndex();
            List<UnoBattleEngine.Card> drawnHand = uno.getHand(side);
            if (index >= 0 && index < drawnHand.size()) {
                UnoBattleEngine.Card drawn = drawnHand.get(index);
                if (!uno.canPlay(side, index)) return MoveCommand.action(UnoBattleEngine.END_DRAW_ACTION);
                if (difficulty >= 4 && inheritedCount(uno, side, drawn) > drawnHand.size() - 1)
                    return MoveCommand.action(UnoBattleEngine.END_DRAW_ACTION);
                return command(uno, side, index, drawnHand);
            }
            return MoveCommand.action(UnoBattleEngine.END_DRAW_ACTION);
        }
        List<UnoBattleEngine.Card> hand = uno.getHand(side);
        int fallback = -1;
        double best = -Double.MAX_VALUE;
        for (int i = 0; i < hand.size(); i++) {
            if (!uno.canPlay(side, i)) continue;
            UnoBattleEngine.Card c = hand.get(i);
            if (hand.size() == 1) return command(uno, side, i, hand);
            if (difficulty >= 4) {
                double score = score(uno, side, hand, c, difficulty);
                if (score > best) { best = score; fallback = i; }
                continue;
            }
            if (c.value == 7) {
                int target = smallestOpponentHand(uno, side);
                if (target > 0) return MoveCommand.withTarget(i, 2, target);
            }
            if (c.value >= 10 && c.value <= 14) {
                return c.color < 0 ? MoveCommand.withTarget(i, 1, preferredColor(hand)) : MoveCommand.action(i);
            }
            if (c.color < 0) fallback = i;
            else if (fallback < 0) fallback = i;
        }
        if (fallback < 0) return MoveCommand.action(UnoBattleEngine.DRAW_ACTION);
        return command(uno, side, fallback, hand);
    }

    private static MoveCommand command(UnoBattleEngine uno, int side, int index, List<UnoBattleEngine.Card> hand) {
        UnoBattleEngine.Card card = hand.get(index);
        if (card.color < 0) return MoveCommand.withTarget(index, 1, preferredColor(hand));
        if (card.value == 7 && hand.size() > 1) return MoveCommand.withTarget(index, 2, smallestOpponentHand(uno, side));
        return MoveCommand.action(index);
    }

    private static double score(UnoBattleEngine uno, int side, List<UnoBattleEngine.Card> hand, UnoBattleEngine.Card card, int difficulty) {
        if (hand.size() == 1) return 10000;
        int next = neighbour(uno, side, uno.getDirection());
        boolean threatened = uno.getHandCount(next) <= 2;
        if (card.value == 0 || card.value == 7)
            return 25 + 20 * (hand.size() - 1 - inheritedCount(uno, side, card));
        double value = card.value <= 9 ? 12 + card.value * .4 : 20;
        if (card.value == 10) value += threatened ? 40 : 4;
        if (card.value == 11) {
            int previous = neighbour(uno, side, -uno.getDirection());
            value += next == previous ? 20 : (uno.getHandCount(previous) - uno.getHandCount(next)) * 3;
        }
        if (card.value == 12) value += threatened ? 40 : 8;
        if (card.value == 13) value = 4;
        if (card.value == 14) value = 10 + (threatened ? 40 : 0) + (uno.getPendingDraw() > 0 ? 25 : 0);
        if (difficulty >= 6 && card.color >= 0) {
            for (UnoBattleEngine.Card other : hand) if (other.color == card.color) value += 2;
        }
        return value;
    }

    private static int inheritedCount(UnoBattleEngine uno, int side, UnoBattleEngine.Card card) {
        if (card.value == 7) return uno.getHandCount(smallestOpponentHand(uno, side));
        if (card.value == 0) return uno.getHandCount(neighbour(uno, side, -uno.getDirection()));
        return 0;
    }

    private static int neighbour(UnoBattleEngine uno, int side, int direction) {
        int candidate = side;
        for (int i = 0; i < uno.getCount(); i++) {
            candidate = (candidate - 1 + direction + uno.getCount()) % uno.getCount() + 1;
            if (uno.isActive(candidate)) return candidate;
        }
        return side;
    }

    private static int preferredColor(List<UnoBattleEngine.Card> hand) {
        int[] counts = new int[4];
        for (UnoBattleEngine.Card c : hand) if (c.color >= 0) counts[c.color]++;
        int color = 0;
        for (int i = 1; i < counts.length; i++) if (counts[i] > counts[color]) color = i;
        return color;
    }

    private static int smallestOpponentHand(UnoBattleEngine uno, int side) {
        int target = -1, size = Integer.MAX_VALUE;
        for (int candidate = 1; candidate <= uno.getCount(); candidate++) {
            if (candidate == side || !uno.isActive(candidate)) continue;
            int candidateSize = uno.getHandCount(candidate);
            if (candidateSize < size) { size = candidateSize; target = candidate; }
        }
        return target;
    }
}





