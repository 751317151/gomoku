package com.gomoku.game;

import com.gomoku.game.CardBattleEngine.Card;
import com.gomoku.game.CardBattleEngine.Deck;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 高费卡（终结手段）测试
 *
 * 补强高费区的原因：之前 6 费以上只有幼龙一张，后期双方只能靠小随从互相磨，
 * 对局缺少"终结手段"。
 */
class HighCostCardTest {

    @Test
    void highCostCardsExist() {
        Card titan = Card.WAR_TITAN;
        assertEquals(8, titan.getCost(), "泰坦 8 费");
        assertEquals(9, titan.getAtk());
        assertEquals(9, titan.getHp());
        assertTrue(titan.hasDivineShield(), "泰坦应带圣盾");
        assertTrue(titan.hasTaunt(), "泰坦应带嘲讽");

        Card necro = Card.NECROMANCER;
        assertEquals(6, necro.getCost());
        assertTrue(necro.hasDeathrattle(), "死灵法师应有亡语");

        Card queen = Card.BLOOD_QUEEN;
        assertEquals(7, queen.getCost());
        assertTrue(queen.hasLifesteal(), "血皇后应带吸血");
        assertTrue(queen.hasCombo(), "血皇后应带连击");
    }

    @Test
    void atLeastThreeCardsCostSixOrMore() {
        // 曲线检查：高费区不能只有一张（否则后期无牌可打）
        long high = Arrays.stream(Card.values())
                .filter(c -> !c.isToken() && c.getCost() >= 6)
                .count();
        assertTrue(high >= 3, "6 费以上的卡应至少 3 张，实际 " + high);
    }

    @Test
    void necromancerSummonsSkeletonWarrior() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        e.putMinionForTest(1, Card.NECROMANCER); // 5/5
        e.putMinionForTest(2, Card.DRAGON);      // 6/5 足以打死
        e.clearSummoningSicknessForTest(1);
        e.clearSummoningSicknessForTest(2);

        assertTrue(e.attack(1, 0, 0), "死灵法师攻击幼龙（会阵亡）");
        // 5/5 挨 6 点 → 死；亡语召唤 3/3
        List<int[]> board = e.getBoardForAi(1);
        assertEquals(1, board.size(), "亡语应召唤一个随从");
        assertEquals(3, board.get(0)[0], "骷髅战士 3 攻");
        assertEquals(3, board.get(0)[1], "骷髅战士 3 血");
    }

    @Test
    void skeletonWarriorIsTokenNotInDeck() {
        assertTrue(Card.SKELETON_WARRIOR.isToken(), "骷髅战士应是衍生物");
        // 衍生物不进任何预组
        for (Deck d : Deck.values()) {
            if (d == Deck.CUSTOM) continue;
            assertFalse(CardBattleEngine.deckCounts(d).containsKey(Card.SKELETON_WARRIOR),
                    "衍生物不该出现在预组 " + d);
        }
    }

    @Test
    void titanWithShieldSurvivesFirstHit() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        e.putMinionForTest(2, Card.WAR_TITAN); // 9/9 圣盾嘲讽
        e.putMinionForTest(1, Card.DRAGON);    // 6/5
        e.clearSummoningSicknessForTest(1);
        e.clearSummoningSicknessForTest(2);

        assertTrue(e.attack(1, 0, 0), "幼龙撞泰坦");
        // 泰坦的圣盾吃掉 6 伤 → 仍 9 血
        assertEquals(9, e.getBoardForAi(2).get(0)[1], "圣盾应吸收第一击");
        // 幼龙挨 9 点 → 死
        assertEquals(0, e.getBoardCount(1), "幼龙应阵亡");
    }

    @Test
    void bloodQueenCombosAndSteals() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        // 先出一张牌激活连击
        e.giveCardForTest(1, Card.FOOTMAN, 20);
        int i1 = e.getHand(1).size() - 1;
        assertTrue(e.applyMove(MoveCommand.action(i1), 1), "先出步兵");

        // 血皇后：连击 +2 攻 → 7 攻；吸血
        e.giveCardForTest(1, Card.BLOOD_QUEEN, 20);
        int i2 = e.getHand(1).size() - 1;
        assertTrue(e.applyMove(MoveCommand.action(i2), 1), "出连击血皇后");
        List<int[]> board = e.getBoardForAi(1);
        assertEquals(7, board.get(1)[0], "连击激活后血皇后应 7 攻（5+2）");

        // 打脸验证吸血（刚上场有召唤失调，先清掉）
        e.clearSummoningSicknessForTest(1);
        e.setHpForTest(1, 20);
        assertTrue(e.attack(1, 1, -1), "血皇后的攻击应成功");
        assertEquals(27, e.getHp(1), "7 攻吸血应回 7 血（20 → 27）");
    }

    @Test
    void presetsStillBalancedAfterHighCostAddition() {
        // 加了高费卡后，三套预组仍各 30 张
        for (Deck d : Deck.values()) {
            if (d == Deck.CUSTOM) continue;
            int total = CardBattleEngine.deckCounts(d).values()
                    .stream().mapToInt(Integer::intValue).sum();
            assertEquals(30, total, "预组 " + d + " 应仍为 30 张");
        }
        // 控制套牌应该有泰坦（它是最需要终结手段的那套）
        assertTrue(CardBattleEngine.deckCounts(Deck.CONTROL).containsKey(Card.WAR_TITAN),
                "控制套牌应包含战争泰坦");
    }
}
