package com.gomoku.game;

import org.junit.jupiter.api.Test;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 吸血 / 连击 关键词测试
 *
 * 这两个关键词都有"边界容易搞错"的地方：
 * - 吸血按**实际造成**的伤害回血（打圣盾时是 0）
 * - 连击只在"本回合已出过别的牌"时激活
 */
class NewKeywordTest {

    @Test
    void lifestealHealsOnMinionAttack() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        e.putMinionForTest(1, CardBattleEngine.Card.VAMPIRE); // 3/4 吸血
        e.putMinionForTest(2, CardBattleEngine.Card.FOOTMAN); // 1/2
        e.clearSummoningSicknessForTest(1);
        e.clearSummoningSicknessForTest(2);
        e.setHpForTest(1, 20); // 先扣血，便于观察回血

        assertTrue(e.attack(1, 0, 0), "吸血鬼攻击步兵");
        // 造成 3 点伤害 → 回 3 血（20 → 23）
        assertEquals(23, e.getHp(1), "吸血应按造成的伤害回血");
    }

    @Test
    void lifestealHealsOnHeroAttack() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        e.putMinionForTest(1, CardBattleEngine.Card.VAMPIRE_LORD); // 6/6 吸血
        e.clearSummoningSicknessForTest(1);
        e.setHpForTest(1, 15);

        assertTrue(e.attack(1, 0, -1), "血族领主打脸");
        assertEquals(21, e.getHp(1), "打脸也能吸血（6 点）");
    }

    @Test
    void lifestealDoesNotHealThroughDivineShield() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        e.putMinionForTest(1, CardBattleEngine.Card.VAMPIRE);      // 3/4 吸血
        e.putMinionForTest(2, CardBattleEngine.Card.SHIELD_GUARD); // 3/2 圣盾嘲讽
        e.clearSummoningSicknessForTest(1);
        e.clearSummoningSicknessForTest(2);
        e.setHpForTest(1, 20);

        assertTrue(e.attack(1, 0, 0), "吸血鬼攻击圣盾卫兵");
        // 伤害被圣盾吸收 → 实际造成 0，不该回血
        assertEquals(20, e.getHp(1), "打圣盾不能吸血（实际伤害为 0）");
    }

    @Test
    void lifestealCappedAtMaxHp() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        e.putMinionForTest(1, CardBattleEngine.Card.VAMPIRE_LORD); // 6/6
        e.clearSummoningSicknessForTest(1);
        // 满血 30，打脸 6 点仍应是 30（不能超过上限）
        assertEquals(30, e.getHp(1));
        assertTrue(e.attack(1, 0, -1));
        assertEquals(30, e.getHp(1), "回血不能超过上限");
    }

    @Test
    void comboActivatesOnlyAfterAnotherCardThisTurn() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        // 本回合第一张：连击不激活
        e.giveCardForTest(1, CardBattleEngine.Card.ASSASSIN, 10);
        int idx = e.getHand(1).size() - 1;
        assertTrue(e.applyMove(MoveCommand.action(idx), 1), "打出刺客（本回合第一张）");
        List<int[]> board = e.getBoardForAi(1);
        assertEquals(3, board.get(0)[0], "首张连击随从没有加成（3 攻）");
    }

    @Test
    void comboActivatesOnSecondCard() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        // 先打一张步兵
        e.giveCardForTest(1, CardBattleEngine.Card.FOOTMAN, 10);
        int idx1 = e.getHand(1).size() - 1;
        assertTrue(e.applyMove(MoveCommand.action(idx1), 1), "先出步兵");

        // 再打刺客：连击应激活（3 → 5 攻）
        e.giveCardForTest(1, CardBattleEngine.Card.ASSASSIN, 10);
        int idx2 = e.getHand(1).size() - 1;
        assertTrue(e.applyMove(MoveCommand.action(idx2), 1), "再出刺客");
        List<int[]> board = e.getBoardForAi(1);
        // 场上：步兵、刺客（顺序按出场）
        assertEquals(5, board.get(1)[0], "连击激活后刺客应 5 攻（3+2）");
    }

    @Test
    void comboDoesNotLeakAcrossTurns() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        // 第 1 回合出步兵
        e.giveCardForTest(1, CardBattleEngine.Card.FOOTMAN, 10);
        int idx1 = e.getHand(1).size() - 1;
        assertTrue(e.applyMove(MoveCommand.action(idx1), 1));
        assertTrue(e.endTurn(1));
        assertTrue(e.endTurn(2)); // 回到红方新回合

        // 新回合第一张：连击不应激活
        e.giveCardForTest(1, CardBattleEngine.Card.SWORDSMAN, 10);
        int idx2 = e.getHand(1).size() - 1;
        assertTrue(e.applyMove(MoveCommand.action(idx2), 1), "新回合出剑客");
        List<int[]> board = e.getBoardForAi(1);
        // 场上：步兵、剑客
        assertEquals(2, board.get(1)[0], "跨回合后连击不该激活（2 攻）");
    }

    @Test
    void newCardsInViewExposeKeywords() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        e.putMinionForTest(1, CardBattleEngine.Card.VAMPIRE);
        e.putMinionForTest(1, CardBattleEngine.Card.SWORDSMAN);
        @SuppressWarnings("unchecked")
        Map<String, Object> view = (Map<String, Object>) e.getViewState(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> players = (List<Map<String, Object>>) view.get("players");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> board = (List<Map<String, Object>>) players.get(0).get("board");
        assertEquals(Boolean.TRUE, board.get(0).get("lifesteal"), "视图应暴露吸血");
        assertEquals(Boolean.TRUE, board.get(1).get("combo"), "视图应暴露连击");
        assertTrue(board.get(1).containsKey("comboActive"), "视图应暴露连击是否已激活");
    }
}
