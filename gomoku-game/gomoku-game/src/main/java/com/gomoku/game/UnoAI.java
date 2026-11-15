package com.gomoku.game;

import java.util.List;

/** UNO AI：优先特殊牌，万能牌选择手牌中出现最多的颜色。 */
public class UnoAI implements GameAI {
    static final UnoAI INSTANCE = new UnoAI();
    private UnoAI() {}
    public MoveCommand decide(GameEngine engine, int side, int difficulty) {
        if (!(engine instanceof UnoBattleEngine)) return null;
        UnoBattleEngine uno = (UnoBattleEngine) engine;
        if (uno.getUnoPendingSide() == side) return MoveCommand.action(UnoBattleEngine.CALL_UNO_ACTION);
        List<UnoBattleEngine.Card> hand = uno.getHand(side);
        int fallback = -1;
        for (int i = 0; i < hand.size(); i++) {
            if (!uno.canPlay(side, i)) continue;
            UnoBattleEngine.Card c = hand.get(i);
            if (c.value >= 10 && c.value <= 14) return MoveCommand.action(i);
            if (c.color < 0) fallback = i;
            else if (fallback < 0) fallback = i;
        }
        if (fallback < 0) return MoveCommand.action(UnoBattleEngine.DRAW_ACTION);
        UnoBattleEngine.Card chosen = hand.get(fallback);
        if (chosen.color < 0) {
            int[] counts = new int[4];
            for (UnoBattleEngine.Card c : hand) if (c.color >= 0) counts[c.color]++;
            int color = 0; for (int i = 1; i < counts.length; i++) if (counts[i] > counts[color]) color = i;
            return MoveCommand.withTarget(fallback, 1, color);
        }
        return MoveCommand.action(fallback);
    }
}



