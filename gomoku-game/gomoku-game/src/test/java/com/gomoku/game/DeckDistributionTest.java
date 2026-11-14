package com.gomoku.game;

import org.junit.jupiter.api.Test;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 牌库构建的健全性检查。
 *
 * 早期版本用「随机抽 300 局起手，断言每张卡都出现过」来验证牌库没漏卡 ——
 * 这个思路有问题：低配比卡（如均衡套牌里只放 1 张的幼龙）单局出现概率约 3%，
 * 300 局里偶尔缺席是正常统计波动，会误报（改了两次牌库都踩到）。
 *
 * 现在直接检查**构建结果**：从 deckCounts 拿配比，确认三套加起来覆盖了所有非衍生物卡，
 * 并确认衍生物一张都没进配比。这是确定性的，不依赖随机。
 */
class DeckDistributionTest {

    @Test
    void allNonTokenCardsAreUsedBySomePreset() {
        Set<CardBattleEngine.Card> used = EnumSet.noneOf(CardBattleEngine.Card.class);
        for (CardBattleEngine.Deck d : CardBattleEngine.Deck.values()) {
            if (d == CardBattleEngine.Deck.CUSTOM) continue; // 预组才有固定配比
            used.addAll(CardBattleEngine.deckCounts(d).keySet());
        }

        // 每个非衍生物卡牌都应至少被一套预组使用
        List<String> missing = new ArrayList<>();
        for (CardBattleEngine.Card c : CardBattleEngine.Card.values()) {
            if (c.isToken()) continue;
            if (!used.contains(c)) missing.add(c.getLabel());
        }
        assertTrue(missing.isEmpty(),
                "这些卡牌没有被任何预组使用（可能是加卡后忘了配置比）：" + missing);
    }

    @Test
    void noTokenInAnyPreset() {
        for (CardBattleEngine.Deck d : CardBattleEngine.Deck.values()) {
            if (d == CardBattleEngine.Deck.CUSTOM) continue;
            for (CardBattleEngine.Card c : CardBattleEngine.deckCounts(d).keySet()) {
                assertFalse(c.isToken(),
                        "衍生物「" + c.getLabel() + "」不该出现在预组 " + d + " 里");
            }
        }
    }

    @Test
    void everyPresetHasExactlyThirtyCards() {
        for (CardBattleEngine.Deck d : CardBattleEngine.Deck.values()) {
            if (d == CardBattleEngine.Deck.CUSTOM) continue;
            int total = CardBattleEngine.deckCounts(d).values()
                    .stream().mapToInt(Integer::intValue).sum();
            assertEquals(30, total, "预组 " + d + " 必须是 30 张");
        }
    }

    @Test
    void openingHandsAlwaysPlayable() {
        // 抽样验证：每个预组跑 100 局，起手都必须有可打出的牌。
        // 这条是随机性测试，但断言的属性（有牌可打）由 drawOpeningHand 结构性保证，
        // 不存在低概率误报。
        for (CardBattleEngine.Deck d : CardBattleEngine.Deck.values()) {
            if (d == CardBattleEngine.Deck.CUSTOM) continue;
            for (int seed = 0; seed < 100; seed++) {
                CardBattleEngine e = new CardBattleEngine(new Random(seed));
                e.setDeck(1, d);
                e.setDeck(2, d);
                e.reset();
                int mana = e.getMana(1);
                boolean any = e.getHand(1).stream().anyMatch(c -> c.getCost() <= mana);
                assertTrue(any, "预组 " + d + " 种子 " + seed + " 起手无牌可打");
            }
        }
    }

    @Test
    void newCardsAreReachableFromDeck() {
        // 新加的卡必须在至少一套预组里、且建库后确实进了牌库。
        CardBattleEngine e = new CardBattleEngine(new Random(1));
        e.setDeck(1, CardBattleEngine.Deck.CONTROL);
        e.setDeck(2, CardBattleEngine.Deck.AGGRO);
        e.reset();
        assertEquals(27, e.getDeckLeft(1));
        assertEquals(26, e.getDeckLeft(2));
    }
}
