package com.gomoku.game;

import org.junit.jupiter.api.Test;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 套牌预组测试
 *
 * 三套预组必须各自正好 30 张、风格真的不同、且都能保证起手有牌可打。
 */
class DeckPresetTest {

    @Test
    void everyPresetHas30Cards() {
        for (CardBattleEngine.Deck d : CardBattleEngine.Deck.values()) {
            Map<CardBattleEngine.Card, Integer> counts = CardBattleEngine.deckCounts(d);
            int total = counts.values().stream().mapToInt(Integer::intValue).sum();
            assertEquals(30, total, "套牌 " + d + " 的张数应为 30");
            // 衍生物不能出现在配比表里
            for (CardBattleEngine.Card c : counts.keySet()) {
                assertFalse(c.isToken(), "套牌 " + d + " 不该包含衍生物 " + c.getLabel());
            }
        }
    }

    @Test
    void presetsAreActuallyDifferent() {
        Map<CardBattleEngine.Card, Integer> a = CardBattleEngine.deckCounts(CardBattleEngine.Deck.AGGRO);
        Map<CardBattleEngine.Card, Integer> c = CardBattleEngine.deckCounts(CardBattleEngine.Deck.CONTROL);
        Map<CardBattleEngine.Card, Integer> b = CardBattleEngine.deckCounts(CardBattleEngine.Deck.BALANCED);

        assertNotEquals(a, c, "快攻与控制不应是同一套牌");
        assertNotEquals(a, b);
        assertNotEquals(c, b);

        // 快攻的平均费用应低于控制
        assertTrue(avgCost(a) < avgCost(c),
                "快攻平均费用(" + avgCost(a) + ") 应低于控制(" + avgCost(c) + ")");
    }

    private double avgCost(Map<CardBattleEngine.Card, Integer> counts) {
        int total = 0, cost = 0;
        for (Map.Entry<CardBattleEngine.Card, Integer> e : counts.entrySet()) {
            total += e.getValue();
            cost += e.getKey().getCost() * e.getValue();
        }
        return total == 0 ? 0 : (double) cost / total;
    }

    @Test
    void eachPresetGuaranteesPlayableOpening() {
        // 每种套牌都要能保证起手有可打牌（靠 drawOpeningHand 的换牌逻辑）
        for (CardBattleEngine.Deck d : CardBattleEngine.Deck.values()) {
            for (int seed = 0; seed < 150; seed++) {
                CardBattleEngine e = new CardBattleEngine(new Random(seed));
                e.setDeck(1, d);
                e.setDeck(2, d);
                e.reset(); // reset 后才会按新套牌建库
                List<CardBattleEngine.Card> hand = e.getHand(1);
                int mana = e.getMana(1);
                boolean any = hand.stream().anyMatch(c -> c.getCost() <= mana);
                assertTrue(any, "套牌 " + d + " 种子 " + seed + " 起手无牌可打");
            }
        }
    }

    @Test
    void deckSelectionAffectsOpeningPool() {
        // 快攻套牌里低费随从应该明显更多：统计 100 次起手的低费牌比例
        double aggroLow = lowCostRatio(CardBattleEngine.Deck.AGGRO);
        double controlLow = lowCostRatio(CardBattleEngine.Deck.CONTROL);
        assertTrue(aggroLow > controlLow,
                "快攻低费牌比例(" + aggroLow + ") 应高于控制(" + controlLow + ")");
    }

    private double lowCostRatio(CardBattleEngine.Deck d) {
        int low = 0, total = 0;
        for (int seed = 0; seed < 100; seed++) {
            CardBattleEngine e = new CardBattleEngine(new Random(seed));
            e.setDeck(1, d);
            e.setDeck(2, d);
            e.reset();
            for (CardBattleEngine.Card c : e.getHand(1)) {
                total++;
                if (c.getCost() <= 2) low++;
            }
        }
        return total == 0 ? 0 : (double) low / total;
    }

    @Test
    void deckNameParsingIsForgiving() {
        assertEquals(CardBattleEngine.Deck.BALANCED, CardBattleEngine.Deck.fromName(null));
        assertEquals(CardBattleEngine.Deck.BALANCED, CardBattleEngine.Deck.fromName(""));
        assertEquals(CardBattleEngine.Deck.BALANCED, CardBattleEngine.Deck.fromName("不存在的套牌"));
        assertEquals(CardBattleEngine.Deck.AGGRO, CardBattleEngine.Deck.fromName("aggro"));
        assertEquals(CardBattleEngine.Deck.CONTROL, CardBattleEngine.Deck.fromName("CONTROL"));
    }

    @Test
    void deckExposedInView() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        e.setDeck(1, CardBattleEngine.Deck.AGGRO);
        e.reset();
        @SuppressWarnings("unchecked")
        Map<String, Object> view = (Map<String, Object>) e.getViewState(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> players = (List<Map<String, Object>>) view.get("players");
        assertEquals("AGGRO", players.get(0).get("deck"), "视图应暴露我方套牌");
        assertEquals("快攻", players.get(0).get("deckLabel"));
        assertEquals("BALANCED", players.get(1).get("deck"), "对手套牌也是公开信息");
    }
}
