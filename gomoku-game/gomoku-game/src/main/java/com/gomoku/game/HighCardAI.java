package com.gomoku.game;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** 只读本方视角，不读取对手手牌或选择。 */
public class HighCardAI implements GameAI {
    static final HighCardAI INSTANCE = new HighCardAI();
    @Override @SuppressWarnings("unchecked")
    public int chooseAction(GameEngine engine, int side, int difficulty) {
        Map<String, Object> view = (Map<String, Object>) engine.getViewState(side);
        for (Map<String, Object> player : (List<Map<String, Object>>) view.get("players")) {
            if (((Number) player.get("side")).intValue() != side || Boolean.TRUE.equals(player.get("answered"))
                    || !Boolean.TRUE.equals(player.get("active"))) continue;
            List<Integer> hand = (List<Integer>) player.get("hand");
            if (hand == null || hand.isEmpty()) return -1;
            return difficulty >= 6 ? Collections.max(hand) : hand.get(ThreadLocalRandom.current().nextInt(hand.size()));
        }
        return -1;
    }
}
