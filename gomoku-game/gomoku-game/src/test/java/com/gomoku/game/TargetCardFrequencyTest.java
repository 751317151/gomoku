package com.gomoku.game;

import org.junit.jupiter.api.Test;
import java.util.*;

/** 检查需要选目标的牌在起手中出现的频率，确认浏览器里"总也抽不到"是否异常 */
class TargetCardFrequencyTest {

    @Test
    void targetCardsAppearInOpeningHands() {
        int withTarget = 0;
        int trials = 500;
        Map<String, Integer> byCard = new HashMap<>();
        for (int seed = 0; seed < trials; seed++) {
            CardBattleEngine e = new CardBattleEngine(new Random(seed));
            boolean any = false;
            for (int side = 1; side <= 2; side++) {
                for (CardBattleEngine.Card c : e.getHand(side)) {
                    if (c.needsTarget()) {
                        any = true;
                        byCard.merge(c.getLabel(), 1, Integer::sum);
                    }
                }
            }
            if (any) withTarget++;
        }
        System.out.println("起手含需选目标牌的局数: " + withTarget + "/" + trials
                + " (" + (withTarget * 100 / trials) + "%)  明细: " + byCard);
        // 至少 25% 的局起手里应该有需选目标的牌
        if (withTarget < trials / 4) {
            throw new AssertionError("需选目标的牌出现频率过低: " + withTarget + "/" + trials);
        }
    }
}
