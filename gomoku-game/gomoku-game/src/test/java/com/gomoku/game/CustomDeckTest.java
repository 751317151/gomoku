package com.gomoku.game;

import org.junit.jupiter.api.Test;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 自定义套牌校验测试
 *
 * 客户端可以做 UI 约束，但服务端必须独立校验 —— 这些规则都是防作弊/防呆底线。
 */
class CustomDeckTest {

    private static Map<CardBattleEngine.Card, Integer> deck(Object... pairs) {
        Map<CardBattleEngine.Card, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((CardBattleEngine.Card) pairs[i], (Integer) pairs[i + 1]);
        }
        return m;
    }

    @Test
    void validDeckPasses() {
        Map<CardBattleEngine.Card, Integer> d = deck(
                CardBattleEngine.Card.FOOTMAN, 6,
                CardBattleEngine.Card.ARCHER, 6,
                CardBattleEngine.Card.KNIGHT, 6,
                CardBattleEngine.Card.GUARDIAN, 6,
                CardBattleEngine.Card.FIREBALL, 6);
        assertEquals(30, d.values().stream().mapToInt(Integer::intValue).sum());
        assertNull(CardBattleEngine.validateCustomDeck(d), "30 张合法配比应通过");
    }

    @Test
    void rejectsWrongTotal() {
        // 10 张（单卡 6 张以内，总数不足）
        Map<CardBattleEngine.Card, Integer> d = deck(
                CardBattleEngine.Card.FOOTMAN, 6,
                CardBattleEngine.Card.ARCHER, 4);
        String err = CardBattleEngine.validateCustomDeck(d);
        assertNotNull(err, "不足 30 张应被拒绝");
        assertTrue(err.contains("30"), "错误信息应说明需要 30 张：" + err);
    }

    @Test
    void rejectsOverCopies() {
        Map<CardBattleEngine.Card, Integer> d = deck(
                CardBattleEngine.Card.FOOTMAN, 7,
                CardBattleEngine.Card.ARCHER, 6,
                CardBattleEngine.Card.KNIGHT, 6,
                CardBattleEngine.Card.GUARDIAN, 6,
                CardBattleEngine.Card.FIREBALL, 5);
        String err = CardBattleEngine.validateCustomDeck(d);
        assertNotNull(err, "单卡超过 6 张应被拒绝");
        assertTrue(err.contains("最多"), err);
    }

    @Test
    void rejectsTokens() {
        Map<CardBattleEngine.Card, Integer> d = deck(
                CardBattleEngine.Card.WOLF_CUB, 6,
                CardBattleEngine.Card.FOOTMAN, 6,
                CardBattleEngine.Card.ARCHER, 6,
                CardBattleEngine.Card.KNIGHT, 6,
                CardBattleEngine.Card.GUARDIAN, 6);
        String err = CardBattleEngine.validateCustomDeck(d);
        assertNotNull(err, "衍生物不能进自定义套牌");
        assertTrue(err.contains("衍生物"), err);
    }

    @Test
    void rejectsEmptyAndNull() {
        assertNotNull(CardBattleEngine.validateCustomDeck(null));
        assertNotNull(CardBattleEngine.validateCustomDeck(new LinkedHashMap<>()));
    }

    @Test
    void rejectsNegativeCount() {
        Map<CardBattleEngine.Card, Integer> d = deck(
                CardBattleEngine.Card.FOOTMAN, -6,
                CardBattleEngine.Card.ARCHER, 30);
        String err = CardBattleEngine.validateCustomDeck(d);
        assertNotNull(err, "负数张数应被拒绝");
    }

    @Test
    void setCustomDeckSwitchesModeAndBuildsDeck() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        Map<CardBattleEngine.Card, Integer> d = deck(
                CardBattleEngine.Card.FOOTMAN, 6,
                CardBattleEngine.Card.ARCHER, 6,
                CardBattleEngine.Card.KNIGHT, 6,
                CardBattleEngine.Card.GUARDIAN, 6,
                CardBattleEngine.Card.FIREBALL, 6);
        assertNull(e.setCustomDeck(1, d), "合法配比应设置成功");
        assertEquals(CardBattleEngine.Deck.CUSTOM, e.getDeck(1), "模式应切到 CUSTOM");

        e.reset();
        // 牌库 = 30 - 起手 3
        assertEquals(27, e.getDeckLeft(1), "自定义套牌应按配比建 30 张");
        // 起手只可能来自这 5 种卡
        Set<CardBattleEngine.Card> allowed = d.keySet();
        for (CardBattleEngine.Card c : e.getHand(1)) {
            assertTrue(allowed.contains(c), "起手出现了未选入的卡：" + c.getLabel());
        }
    }

    @Test
    void setCustomDeckRejectsInvalidAndKeepsPrevious() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        e.setDeck(1, CardBattleEngine.Deck.AGGRO);
        Map<CardBattleEngine.Card, Integer> bad = deck(CardBattleEngine.Card.FOOTMAN, 1);
        assertNotNull(e.setCustomDeck(1, bad), "非法配比应返回错误");
        assertEquals(CardBattleEngine.Deck.AGGRO, e.getDeck(1), "非法配比不应改变原有选择");
    }

    @Test
    void customDeckIsPerSide() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        Map<CardBattleEngine.Card, Integer> a = deck(
                CardBattleEngine.Card.FOOTMAN, 6, CardBattleEngine.Card.ARCHER, 6,
                CardBattleEngine.Card.KNIGHT, 6, CardBattleEngine.Card.GUARDIAN, 6,
                CardBattleEngine.Card.FIREBALL, 6);
        Map<CardBattleEngine.Card, Integer> b = deck(
                CardBattleEngine.Card.DRAGON, 6, CardBattleEngine.Card.BONE_DRAGON, 6,
                CardBattleEngine.Card.CLEAVE, 6, CardBattleEngine.Card.HEAL, 6,
                CardBattleEngine.Card.SHIELD_GUARD, 6);
        assertNull(e.setCustomDeck(1, a));
        assertNull(e.setCustomDeck(2, b));
        assertEquals(CardBattleEngine.Deck.CUSTOM, e.getDeck(1));
        assertEquals(CardBattleEngine.Deck.CUSTOM, e.getDeck(2));

        e.reset();
        // 双方都按各自配比建库（各 30 张 → 27 / 26 起手后）
        assertEquals(27, e.getDeckLeft(1));
        assertEquals(26, e.getDeckLeft(2));
        Set<CardBattleEngine.Card> set1 = a.keySet();
        Set<CardBattleEngine.Card> set2 = b.keySet();
        for (CardBattleEngine.Card c : e.getHand(1)) {
            assertTrue(set1.contains(c), "红方起手出现了蓝方的牌：" + c.getLabel());
        }
        for (CardBattleEngine.Card c : e.getHand(2)) {
            assertTrue(set2.contains(c), "蓝方起手出现了红方的牌：" + c.getLabel());
        }
    }
}
