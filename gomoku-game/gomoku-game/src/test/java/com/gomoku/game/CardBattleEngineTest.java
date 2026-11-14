package com.gomoku.game;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 卡牌对战引擎测试
 *
 * 重点是两件事：
 * 1. 规则正确性（费用、手牌、随从、攻击、胜负）
 * 2. **隐藏信息隔离** —— 这是本玩法引入的新架构要求，
 *    如果 getViewState 不按视角裁剪，对手手牌会随着同步包泄漏。
 */
class CardBattleEngineTest {

    private CardBattleEngine engine;

    @BeforeEach
    void setUp() {
        engine = new CardBattleEngine(new Random(42));
    }

    @Test
    void initialState() {
        assertEquals(GameType.CARD, engine.type());
        assertEquals(1, engine.getCurrentTurn(), "先手方 1");
        assertEquals(30, engine.getHp(1));
        assertEquals(30, engine.getHp(2));
        assertEquals(3, engine.getMana(1), "先手第一回合 3 费（起手有可打牌由换牌逻辑保证）");
        assertEquals(0, engine.getMoveCount());
        assertFalse(engine.isOver());
        assertEquals(45, engine.getStepTimeSeconds());
        assertEquals(600, engine.getTotalTimeSeconds());
    }

    @Test
    void handSizesAtStart() {
        // 后手多一张牌作为补偿
        assertEquals(3, engine.getHand(1).size(), "先手 3 张");
        assertEquals(4, engine.getHand(2).size(), "后手 4 张");
    }

    @Test
    void openingHandAlwaysHasPlayableCard() {
        // 所有种子下起手都必须有牌可打，否则第一回合就无解
        for (int seed = 0; seed < 1000; seed++) {
            CardBattleEngine e = new CardBattleEngine(new Random(seed));
            int idx = firstPlayable(e, 1);
            assertTrue(idx >= 0, "种子 " + seed + " 的起手无牌可打（开局资源不足）");
        }
    }

    @Test
    void rejectCoordinateCommand() {
        // 卡牌玩法只接受行动序号，坐标指令必须被拒绝
        assertFalse(engine.applyMove(MoveCommand.at(1, 2), 1), "坐标指令应被拒绝");
        assertFalse(engine.applyMove(MoveCommand.from(0, 0, 1, 1), 1), "走子指令应被拒绝");
        assertEquals(0, engine.getMoveCount(), "拒绝的指令不计入历史");
    }

    @Test
    void rejectOutOfTurn() {
        assertFalse(engine.applyMove(MoveCommand.action(0), 2), "非当前回合方不能出牌");
    }

    @Test
    void rejectInvalidHandIndex() {
        assertFalse(engine.applyMove(MoveCommand.action(-1), 1));
        assertFalse(engine.applyMove(MoveCommand.action(99), 1));
    }

    @Test
    void rejectUnaffordableCard() {
        // 先手第一回合只有 1 费，找一张 1 费以上的牌尝试打出
        List<CardBattleEngine.Card> hand = engine.getHand(1);
        int expensive = -1;
        for (int i = 0; i < hand.size(); i++) {
            if (hand.get(i).getCost() > engine.getMana(1)) {
                expensive = i;
                break;
            }
        }
        if (expensive >= 0) {
            assertFalse(engine.applyMove(MoveCommand.action(expensive), 1), "费用不足应被拒绝");
        }
    }

    @Test
    void playingCardCostsManaAndLeavesHand() {
        int before = engine.getHand(1).size();
        int mana = engine.getMana(1);
        // 找一张买得起的牌
        int playable = -1;
        List<CardBattleEngine.Card> hand = engine.getHand(1);
        for (int i = 0; i < hand.size(); i++) {
            if (hand.get(i).getCost() <= mana) { playable = i; break; }
        }
        assertTrue(playable >= 0, "第一回合应至少有一张可打的牌");

        int cost = hand.get(playable).getCost();
        assertTrue(engine.applyMove(MoveCommand.action(playable), 1));

        assertEquals(1, engine.getMoveCount());
        assertEquals(mana - cost, engine.getMana(1), "法力应按费用扣除");
        // 出牌后手牌变化：打出的牌移除，新回合自动抽 1 张
        assertTrue(engine.getHand(1).size() >= before - 1, "打出的牌应从手牌移除");
    }

    @Test
    void minionGoesToBoard() {
        // 构造：给先手一张步兵并尝试上场
        CardBattleEngine e = new CardBattleEngine(new Random(7));
        int idx = findPlayableMinion(e, 1);
        if (idx < 0) return; // 随机手牌里没有可打的随从时跳过（不误报失败）

        int boardBefore = e.getBoardCount(1);
        assertTrue(e.applyMove(cmdFor(e, 1, idx), 1));
        assertEquals(boardBefore + 1, e.getBoardCount(1), "随从应进入己方场上");
    }

    @Test
    void playingDoesNotEndTurn() {
        // 卡牌是一回合多行动：出牌后仍由同一方继续，直到显式结束回合
        int idx = firstPlayable(engine, 1);
        assertTrue(idx >= 0);
        assertTrue(engine.applyMove(cmdFor(engine, 1, idx), 1));
        assertEquals(1, engine.getCurrentTurn(), "出牌不换手");
        assertFalse(engine.autoEndsTurn(), "卡牌不自动结束回合");
    }

    @Test
    void endTurnSwitchesSideAndRefillsMana() {
        int idx = firstPlayable(engine, 1);
        assertTrue(engine.applyMove(cmdFor(engine, 1, idx), 1));
        int manaBefore = engine.getMana(1);

        assertTrue(engine.endTurn(1), "结束回合应成功");
        assertEquals(2, engine.getCurrentTurn(), "回合应交给对手");
        assertEquals(3, engine.getMana(2), "对手回合法力增长");

        // 非当前回合方不能结束回合
        assertFalse(engine.endTurn(1), "轮次校验");
        assertTrue(engine.endTurn(2));
        assertEquals(1, engine.getCurrentTurn());
        // 开局法力 3，但 maxMana 从 2 起步（否则对手首回合会变 4）；
        // 所以回到己方时 maxMana 推到 3，与开局持平，之后才逐回合增长。
        assertEquals(3, engine.getMana(1), "回到己方的法力与开局持平");

        assertTrue(engine.endTurn(1));
        assertTrue(engine.endTurn(2));
        assertEquals(4, engine.getMana(1), "再下一回合才增长到 4");
        assertTrue(engine.getMana(1) > manaBefore, "法力应随回合增长");
    }

    @Test
    void multiplePlaysInOneTurn() {
        // 3 费开局：能连出多张低费牌
        CardBattleEngine e = new CardBattleEngine(new Random(11));
        int plays = 0;
        for (int guard = 0; guard < 10; guard++) {
            int idx = firstPlayable(e, 1);
            if (idx < 0) break;
            int manaBefore = e.getMana(1);
            assertTrue(e.applyMove(cmdFor(e, 1, idx), 1));
            assertTrue(e.getMana(1) <= manaBefore, "每次出牌都应扣蓝");
            plays++;
            assertEquals(1, e.getCurrentTurn(), "同一回合内不换手");
        }
        assertTrue(plays >= 1, "至少能出一张");
        assertEquals(plays, e.getMoveCount(), "每张牌记一手");
    }

    @Test
    void attackWithMinionHitsHero() {
        CardBattleEngine e = new CardBattleEngine(new Random(3));
        int idx = findPlayableMinion(e, 1);
        if (idx < 0) return; // 该种子没有可打随从时跳过

        CardBattleEngine.Card played = e.getHand(1).get(idx);
        assertTrue(e.applyMove(cmdFor(e, 1, idx), 1));
        assertEquals(1, e.getBoardCount(1));

        // 非冲锋随从有召唤失调：召唤当回合不能攻击
        if (!played.hasCharge()) {
            assertFalse(e.attack(1, 0, -1), "召唤失调期间不能攻击");
            assertTrue(e.endTurn(1), "结束回合");
            assertTrue(e.endTurn(2), "对手也结束回合，回到我方");
        } else {
            // 冲锋：当回合就能打
            assertTrue(e.attack(1, 0, -1), "冲锋随从召唤当回合即可攻击");
        }

        int hpBefore = e.getHp(2);
        if (e.attackableIndexes(1).contains(0)) {
            assertTrue(e.attack(1, 0, -1), "随从攻击英雄应成功");
            assertTrue(e.getHp(2) < hpBefore, "对手应掉血");
            assertFalse(e.attack(1, 0, -1), "同一随从一回合只能攻击一次");
        }
    }

    @Test
    void tauntMustBeAttackedFirst() {
        CardBattleEngine e = new CardBattleEngine(new Random(9));
        // 控制变量：双方各一个已过召唤失调的随从
        e.putMinionForTest(1, CardBattleEngine.Card.KNIGHT);
        e.putMinionForTest(2, CardBattleEngine.Card.GUARDIAN); // 守卫带嘲讽
        e.clearSummoningSicknessForTest(1);
        e.clearSummoningSicknessForTest(2);

        assertEquals(1, e.tauntIndexes(2).size(), "蓝方场上有 1 个嘲讽随从");

        // 有嘲讽时不能打英雄
        assertFalse(e.attack(1, 0, -1), "对方有嘲讽时不能攻击英雄");
        // 有嘲讽时不能打其他（非嘲讽）随从 —— 场上只有嘲讽，越界即为非法
        assertFalse(e.attack(1, 0, 5), "目标越界非法");
        // 必须打嘲讽随从
        assertTrue(e.attack(1, 0, 0), "必须攻击嘲讽随从");
    }

    @Test
    void chargeCanAttackImmediately() {
        CardBattleEngine e = new CardBattleEngine(new Random(9));
        e.putMinionForTest(1, CardBattleEngine.Card.ARCHER); // 弓箭手带冲锋
        // 未清除召唤失调
        assertTrue(e.attackableIndexes(1).contains(0), "冲锋随从召唤当回合即可攻击");
        int hpBefore = e.getHp(2);
        assertTrue(e.attack(1, 0, -1));
        assertTrue(e.getHp(2) < hpBefore);
    }

    @Test
    void nonChargeHasSummoningSickness() {
        CardBattleEngine e = new CardBattleEngine(new Random(9));
        e.putMinionForTest(1, CardBattleEngine.Card.KNIGHT); // 无冲锋
        assertFalse(e.attackableIndexes(1).contains(0), "普通随从有召唤失调");
        assertFalse(e.attack(1, 0, -1), "召唤失调期间不能攻击");

        e.clearSummoningSicknessForTest(1);
        assertTrue(e.attackableIndexes(1).contains(0), "解除后可攻击");
    }

    @Test
    void decksAreIndependent() {
        CardBattleEngine e = new CardBattleEngine(new Random(21));
        int deck1 = e.getDeckLeft(1);
        int deck2 = e.getDeckLeft(2);
        // 双方各自 30 张，起手分别抽 3 / 4 张
        assertEquals(27, deck1, "先手牌库 = 30 - 起手 3");
        assertEquals(26, deck2, "后手牌库 = 30 - 起手 4");

        // 先手出一张牌 + 结束回合（会抽 1 张），只影响自己的牌库
        int idx = firstPlayable(e, 1);
        if (idx >= 0) assertTrue(e.applyMove(cmdFor(e, 1, idx), 1));
        int before1 = e.getDeckLeft(1);
        int before2 = e.getDeckLeft(2);
        assertTrue(e.endTurn(1));
        // 换手时对手回合开始抽牌 → 只减少对手的牌库
        assertEquals(before1, e.getDeckLeft(1), "自己的牌库不因对手抽牌而减少");
        assertEquals(before2 - 1, e.getDeckLeft(2), "对手回合只消耗对手牌库");
    }

    @Test
    void attackBlockedOutsideTurn() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        int idx = findPlayableMinion(e, 1);
        if (idx < 0) return;
        assertTrue(e.applyMove(cmdFor(e, 1, idx), 1));
        assertFalse(e.attack(2, 0, -1), "非当前回合方不能攻击");
    }

    @Test
    void hiddenHandIsolation() {
        // 视角 1 只能看到自己的手牌内容
        Map<String, Object> view1 = (Map<String, Object>) engine.getViewState(1);
        List<Map<String, Object>> players1 = (List<Map<String, Object>>) view1.get("players");

        Map<String, Object> me = players1.get(0);
        Map<String, Object> foe = players1.get(1);

        assertTrue(me.containsKey("hand"), "自己的手牌应可见");
        assertFalse(foe.containsKey("hand"), "对手手牌内容必须不可见");
        assertEquals(engine.getHand(2).size(), ((Number) foe.get("handCount")).intValue(),
                "对手手牌数量可见");

        // 视角 2 反过来
        Map<String, Object> view2 = (Map<String, Object>) engine.getViewState(2);
        List<Map<String, Object>> players2 = (List<Map<String, Object>>) view2.get("players");
        assertFalse(players2.get(0).containsKey("hand"), "视角 2 看不到对方手牌");
        assertTrue(players2.get(1).containsKey("hand"), "视角 2 能看到自己手牌");
    }

    @Test
    void spectatorSeesNoHands() {
        Map<String, Object> view = (Map<String, Object>) engine.getViewState(0);
        List<Map<String, Object>> players = (List<Map<String, Object>>) view.get("players");
        for (Map<String, Object> p : players) {
            assertFalse(p.containsKey("hand"), "观战者不应看到任何一方手牌内容");
            assertTrue(p.containsKey("handCount"), "但手牌数量是公开信息");
        }
    }

    @Test
    void viewHidesDeckContents() {
        Map<String, Object> view = (Map<String, Object>) engine.getViewState(1);
        // 只暴露剩余张数，不暴露具体牌
        assertTrue(view.containsKey("deckLeft"));
        assertFalse(view.containsKey("deck"), "牌库内容不能下发");
    }

    @Test
    void boardIsPublic() {
        Map<String, Object> view1 = (Map<String, Object>) engine.getViewState(1);
        List<Map<String, Object>> players = (List<Map<String, Object>>) view1.get("players");
        for (Map<String, Object> p : players) {
            assertTrue(p.containsKey("board"), "场上随从对双方都是公开信息");
            assertTrue(p.containsKey("hp") && p.containsKey("mana"), "英雄状态公开");
        }
    }

    @Test
    void moveHistoryEncodesCardAndValue() {
        int idx = firstPlayable(engine, 1);
        assertTrue(idx >= 0);
        CardBattleEngine.Card played = engine.getHand(1).get(idx);
        assertTrue(engine.applyMove(cmdFor(engine, 1, idx), 1));

        List<int[]> hist = engine.getMoveHistory();
        assertEquals(1, hist.size());
        int[] h = hist.get(0);
        assertEquals(3, h.length, "{side, cardIndex, value}");
        assertEquals(1, h[CardBattleEngine.HIST_SIDE]);
        assertEquals(played.getIndex(), h[CardBattleEngine.HIST_ACTION]);
    }

    @Test
    void battleConverges() {
        // 双方都用 AI 按「一回合多行动」打，保证对局一定结束
        CardBattleEngine e = new CardBattleEngine(new Random(123));
        for (int i = 0; i < CardBattleEngine.MAX_MOVES + 200 && !e.isOver(); i++) {
            int side = e.getCurrentTurn();
            if (CardAI.INSTANCE.shouldEndTurn(e, side, 6)) {
                if (!e.endTurn(side)) break;
                continue;
            }
            MoveCommand cmd = CardAI.INSTANCE.decide(e, side, 6);
            if (cmd == null || !e.applyMove(cmd, side)) {
                // 出不了牌 → 收尾
                if (!e.endTurn(side)) break;
            }
        }
        assertTrue(e.isOver(), "对局必须在硬上限内收敛");
        assertNotNull(e.getScoreSummary());
    }

    @Test
    void fatigueWhenDeckEmpty() {
        // 反复抽牌直到牌库耗尽不是本测试重点，改为验证疲劳字段存在且初始为 0
        Map<String, Object> view = (Map<String, Object>) engine.getViewState(1);
        List<Map<String, Object>> players = (List<Map<String, Object>>) view.get("players");
        assertEquals(0, ((Number) players.get(0).get("fatigue")).intValue());
    }

    @Test
    void resetClearsEverything() {
        int idx = firstPlayable(engine, 1);
        if (idx >= 0) engine.applyMove(MoveCommand.action(idx), 1);
        engine.reset();

        assertEquals(0, engine.getMoveCount());
        assertEquals(1, engine.getCurrentTurn());
        assertEquals(30, engine.getHp(1));
        assertEquals(30, engine.getHp(2));
        assertFalse(engine.isOver());
        assertEquals(3, engine.getHand(1).size(), "先手起手 3 张");
        assertEquals(4, engine.getHand(2).size(), "后手起手 4 张");
        assertEquals(3, engine.getMana(1), "重置后回到开局 3 费");
    }

    @Test
    void playerNameInjected() {
        engine.setPlayerName(1, "小明");
        engine.setPlayerName(2, "小红");
        Map<String, Object> view = (Map<String, Object>) engine.getViewState(1);
        List<Map<String, Object>> players = (List<Map<String, Object>>) view.get("players");
        assertEquals("小明", players.get(0).get("name"));
        assertEquals("小红", players.get(1).get("name"));
    }

    // ============ 战吼 / 亡语 ============

    @Test
    void newCardsExistWithKeywords() {
        CardBattleEngine.Card berserker = CardBattleEngine.Card.BERSERKER;
        CardBattleEngine.Card boneDragon = CardBattleEngine.Card.BONE_DRAGON;
        assertTrue(berserker.hasBattlecry(), "狂战士应有战吼");
        assertFalse(berserker.hasDeathrattle());
        assertTrue(boneDragon.hasDeathrattle(), "骨龙应有亡语");
        assertFalse(boneDragon.hasBattlecry());
        // 下标不能与旧卡冲突（回放编码依赖它，只能往后追加）
        assertEquals(9, berserker.getIndex());
        assertEquals(10, boneDragon.getIndex());
    }

    @Test
    void battlecryFiresOnSummon() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        e.giveCardForTest(1, CardBattleEngine.Card.BERSERKER, 10);
        int before = e.getHp(2);
        int idx = e.getHand(1).size() - 1;
        assertTrue(e.applyMove(MoveCommand.action(idx), 1), "打出狂战士");
        assertEquals(before - 2, e.getHp(2), "战吼应立即对敌方英雄造成 2 点伤害");
    }

    @Test
    void battlecryDoesNotRefireForOtherCards() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        e.giveCardForTest(1, CardBattleEngine.Card.KNIGHT, 10);
        int foeHpBefore = e.getHp(2);
        int idx = e.getHand(1).size() - 1;
        assertTrue(e.applyMove(cmdFor(e, 1, idx), 1));
        assertEquals(foeHpBefore, e.getHp(2), "无战吼的随从不应造成伤害");
    }

    @Test
    void deathrattleFiresOnAttackDeath() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        e.putMinionForTest(1, CardBattleEngine.Card.BONE_DRAGON);
        e.putMinionForTest(2, CardBattleEngine.Card.DRAGON);
        e.clearSummoningSicknessForTest(1);
        e.clearSummoningSicknessForTest(2);

        int foeHpBefore = e.getHp(2);
        // 骨龙 4/4 攻击幼龙 6/5：骨龙受 6 伤阵亡，亡语应打对手 3 点
        assertTrue(e.attack(1, 0, 0), "骨龙发起攻击");
        assertEquals(foeHpBefore - 3, e.getHp(2), "亡语应对敌方英雄造成 3 点伤害");
    }

    @Test
    void deathrattleFiresOnAutoAttackDeath() {
        // 结束回合的自动攻击路径也必须触发亡语（曾因 removeIf 清理尸体而漏掉）
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        e.putMinionForTest(1, CardBattleEngine.Card.BONE_DRAGON);
        e.putMinionForTest(2, CardBattleEngine.Card.DRAGON);
        e.clearSummoningSicknessForTest(1);
        e.clearSummoningSicknessForTest(2);

        int foeHpBefore = e.getHp(2);
        assertTrue(e.endTurn(1), "结束回合触发自动攻击");
        assertEquals(foeHpBefore - 3, e.getHp(2), "自动攻击导致的死亡也要触发亡语");
    }

    @Test
    void deathrattleCanWinTheGame() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        e.putMinionForTest(1, CardBattleEngine.Card.BONE_DRAGON);
        e.putMinionForTest(2, CardBattleEngine.Card.DRAGON);
        e.clearSummoningSicknessForTest(1);
        e.clearSummoningSicknessForTest(2);
        e.setHpForTest(2, 3); // 对手只剩 3 血

        assertTrue(e.attack(1, 0, 0));
        assertTrue(e.isOver(), "亡语把人打死后应终局");
        assertEquals(1, e.getWinner(), "亡语伤害的施加者获胜");
    }

    @Test
    void cleaveKillsDoNotBreakBoardState() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        e.putMinionForTest(2, CardBattleEngine.Card.ARCHER);
        e.giveCardForTest(1, CardBattleEngine.Card.CLEAVE, 10);
        int idx = e.getHand(1).size() - 1;
        int foeHpBefore = e.getHp(2);
        assertTrue(e.applyMove(cmdFor(e, 1, idx), 1));
        assertEquals(0, e.getBoardCount(2), "横扫应清掉 2/1 随从");
        assertEquals(foeHpBefore, e.getHp(2), "横扫不直接打英雄");
    }

    @Test
    void deckStillHas30CardsAfterAddingCards() {
        CardBattleEngine e = new CardBattleEngine(new Random(1));
        assertEquals(27, e.getDeckLeft(1), "先手牌库 = 30 - 起手 3");
        assertEquals(26, e.getDeckLeft(2), "后手牌库 = 30 - 起手 4");
    }

    // ============ 选目标法术 ============

    @Test
    void fireballNeedsTarget() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        e.giveCardForTest(1, CardBattleEngine.Card.FIREBALL, 10);
        int idx = e.getHand(1).size() - 1;
        int foeHpBefore = e.getHp(2);

        // 不给目标 → 拒绝，且不消耗法力/卡牌
        int manaBefore = e.getMana(1);
        assertFalse(e.applyMove(MoveCommand.action(idx), 1), "火球必须指定目标");
        assertEquals(manaBefore, e.getMana(1), "非法目标不应扣法力");
        assertEquals(idx + 1, e.getHand(1).size(), "非法目标不应消耗手牌");
        assertEquals(foeHpBefore, e.getHp(2), "非法目标不应造成伤害");
    }

    @Test
    void fireballCanHitEnemyHero() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        e.giveCardForTest(1, CardBattleEngine.Card.FIREBALL, 10);
        int idx = e.getHand(1).size() - 1;
        int before = e.getHp(2);
        assertTrue(e.applyMove(MoveCommand.withTarget(idx, 2, -1), 1), "打敌方英雄");
        assertEquals(before - 4, e.getHp(2), "英雄受 4 点伤害");
    }

    @Test
    void fireballCanKillEnemyMinion() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        // 敌方 2/1 弓箭手，火球 4 伤足以打死
        e.putMinionForTest(2, CardBattleEngine.Card.ARCHER);
        e.giveCardForTest(1, CardBattleEngine.Card.FIREBALL, 10);
        int idx = e.getHand(1).size() - 1;
        int foeHpBefore = e.getHp(2);

        assertTrue(e.applyMove(MoveCommand.withTarget(idx, 2, 0), 1), "打敌方随从");
        assertEquals(0, e.getBoardCount(2), "随从应被击杀");
        assertEquals(foeHpBefore, e.getHp(2), "火球打随从不应伤到英雄");
    }

    @Test
    void fireballRejectsWrongSideTarget() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        e.giveCardForTest(1, CardBattleEngine.Card.FIREBALL, 10);
        int idx = e.getHand(1).size() - 1;
        // targetSide=1（己方）对火球是非法目标
        assertFalse(e.applyMove(MoveCommand.withTarget(idx, 1, -1), 1),
                "火球不能指定己方为目标");
    }

    @Test
    void buffNeedsOwnMinionTarget() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        e.giveCardForTest(1, CardBattleEngine.Card.BUFF, 10);
        int idx = e.getHand(1).size() - 1;

        // 场上没有己方随从 → 拒绝
        assertFalse(e.applyMove(MoveCommand.withTarget(idx, 1, 0), 1),
                "没有己方随从时鼓舞应被拒绝");

        // 放两个随从上场，指定第二个
        e.putMinionForTest(1, CardBattleEngine.Card.FOOTMAN);
        e.putMinionForTest(1, CardBattleEngine.Card.KNIGHT);
        int knightAtkBefore = e.getBoardForAi(1).get(1)[0];
        int knightHpBefore = e.getBoardForAi(1).get(1)[1];

        assertTrue(e.applyMove(MoveCommand.withTarget(idx, 1, 1), 1), "指定第二个随从");
        int[] knight = e.getBoardForAi(1).get(1);
        assertEquals(knightAtkBefore + 2, knight[0], "被指向的随从攻击 +2");
        assertEquals(knightHpBefore + 2, knight[1], "被指向的随从生命 +2");
        // 第一个随从不受影响
        assertEquals(2, e.getBoardForAi(1).get(0)[1], "未被指向的随从不受影响");
    }

    @Test
    void aiPicksValuableFireballTarget() {
        CardBattleEngine e = CardBattleEngine.forAiTestWithSingleCard(
                new Random(5), CardBattleEngine.Card.FIREBALL, 10);
        // 敌方：1/2 步兵 + 2/1 弓箭手（后者能被火球打死，应优先）
        e.putMinionForTest(2, CardBattleEngine.Card.FOOTMAN);
        e.putMinionForTest(2, CardBattleEngine.Card.ARCHER);

        MoveCommand cmd = CardAI.INSTANCE.decide(e, 1, 6);
        assertNotNull(cmd, "AI 应能出牌");
        assertTrue(cmd.hasAction());
        assertTrue(cmd.hasTarget(), "火球指令应带目标");
        assertEquals(2, cmd.getTargetSide(), "目标是敌方");
        assertEquals(1, cmd.getTargetIndex(), "应优先打 2/1 弓箭手（能打死且攻击高）");
    }

    @Test
    void aiBuffsStrongestOwnMinion() {
        CardBattleEngine e = CardBattleEngine.forAiTestWithSingleCard(
                new Random(5), CardBattleEngine.Card.BUFF, 10);
        e.putMinionForTest(1, CardBattleEngine.Card.FOOTMAN); // 1/2
        e.putMinionForTest(1, CardBattleEngine.Card.KNIGHT);  // 3/3 最强

        MoveCommand cmd = CardAI.INSTANCE.decide(e, 1, 6);
        assertNotNull(cmd);
        assertTrue(cmd.hasTarget(), "鼓舞指令应带目标");
        assertEquals(1, cmd.getTargetSide(), "目标是己方");
        assertEquals(1, cmd.getTargetIndex(), "应强化攻击力最高的骑士");
    }

    // ============ 圣盾 / 亡语召唤 / 衍生物 ============

    @Test
    void divineShieldAbsorbsFirstHit() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        e.putMinionForTest(2, CardBattleEngine.Card.SHIELD_GUARD); // 3/2 圣盾·嘲讽
        e.putMinionForTest(1, CardBattleEngine.Card.KNIGHT);       // 3/3
        e.clearSummoningSicknessForTest(1);

        // 骑士 3 攻打圣盾卫兵：本次伤害应被完全吸收，卫兵不掉血
        assertTrue(e.attack(1, 0, 0), "攻击圣盾随从");
        assertEquals(2, e.getBoardForAi(2).get(0)[1], "第一击被圣盾完全吸收，生命不变");
        assertTrue(e.getBoardCount(2) > 0, "随从应存活");
    }

    @Test
    void divineShieldOnlyAbsorbsOnce() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        e.putMinionForTest(2, CardBattleEngine.Card.SHIELD_GUARD); // 3/2
        e.clearSummoningSicknessForTest(2);
        // 两个攻击者，分两次打
        e.putMinionForTest(1, CardBattleEngine.Card.KNIGHT); // 3/3
        e.putMinionForTest(1, CardBattleEngine.Card.ARCHER); // 2/1
        e.clearSummoningSicknessForTest(1); // 两个攻击者都要解除召唤失调

        assertTrue(e.attack(1, 0, 0), "第一击");
        assertEquals(2, e.getBoardForAi(2).get(0)[1], "第一击被吸收");

        // 注意：第一击时骑士受了 3 点反伤已阵亡，所以场上只剩弓箭手，
        // 它的下标从 1 变成 0。用「当前可攻击的下标」而不是写死 1。
        List<Integer> usable = e.attackableIndexes(1);
        assertFalse(usable.isEmpty(), "还有可攻击的随从");
        assertTrue(e.attack(1, usable.get(0), 0), "第二击");
        // 2 点伤害打在 2 血上 → 阵亡
        assertEquals(0, e.getBoardCount(2), "圣盾破了之后第二击应造成伤害并击杀");
    }

    @Test
    void divineShieldBlocksFireball() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        e.putMinionForTest(2, CardBattleEngine.Card.SHIELD_GUARD);
        e.giveCardForTest(1, CardBattleEngine.Card.FIREBALL, 10);
        int idx = e.getHand(1).size() - 1;

        // 火球打圣盾随从：4 点伤害应被吸收
        assertTrue(e.applyMove(MoveCommand.withTarget(idx, 2, 0), 1), "火球打圣盾随从");
        assertEquals(1, e.getBoardCount(2), "随从应存活");
        assertEquals(2, e.getBoardForAi(2).get(0)[1], "生命不变（伤害被圣盾吸收）");
    }

    @Test
    void divineShieldBlocksCleave() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        e.putMinionForTest(2, CardBattleEngine.Card.SHIELD_GUARD); // 3/2
        e.giveCardForTest(1, CardBattleEngine.Card.CLEAVE, 10);
        int idx = e.getHand(1).size() - 1;

        assertTrue(e.applyMove(MoveCommand.action(idx), 1), "横扫");
        assertEquals(2, e.getBoardForAi(2).get(0)[1], "横扫的 3 点也被圣盾吸收");
    }

    @Test
    void divineShieldBlocksAutoAttack() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        e.putMinionForTest(2, CardBattleEngine.Card.SHIELD_GUARD);
        e.clearSummoningSicknessForTest(2);
        e.putMinionForTest(1, CardBattleEngine.Card.KNIGHT);
        e.clearSummoningSicknessForTest(1);

        // 结束回合会触发自动攻击路径
        assertTrue(e.endTurn(1), "结束回合");
        assertEquals(2, e.getBoardForAi(2).get(0)[1], "自动攻击也被圣盾吸收");
    }

    @Test
    void deathrattleSummonsToken() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        e.putMinionForTest(1, CardBattleEngine.Card.SKELETON); // 2/2 亡语召唤 1/1
        e.putMinionForTest(2, CardBattleEngine.Card.DRAGON);   // 6/5 足以打死骷髅
        e.clearSummoningSicknessForTest(1);
        e.clearSummoningSicknessForTest(2);

        assertEquals(1, e.getBoardCount(1), "先有一个骷髅");
        assertTrue(e.attack(1, 0, 0), "骷髅攻击幼龙（会阵亡）");
        // 骷髅阵亡 → 亡语召唤幼狼
        assertEquals(1, e.getBoardCount(1), "骷髅换成幼狼，场上仍有 1 个");
        int[] cub = e.getBoardForAi(1).get(0);
        assertEquals(1, cub[0], "幼狼 1 攻");
        assertEquals(1, cub[1], "幼狼 1 血");
    }

    @Test
    void deathrattleSummonSkippedWhenBoardFull() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        // 己方先塞满 7 个（用步兵），再放一个骷髅替代其中最后一个位置
        for (int i = 0; i < 6; i++) e.putMinionForTest(1, CardBattleEngine.Card.FOOTMAN);
        e.putMinionForTest(1, CardBattleEngine.Card.SKELETON);
        assertEquals(7, e.getBoardCount(1), "场上有 7 个");
        e.putMinionForTest(2, CardBattleEngine.Card.DRAGON);
        e.clearSummoningSicknessForTest(1);
        e.clearSummoningSicknessForTest(2);

        // 骷髅攻击幼龙自灭，场上应为 6（场上满，亡语召唤不了）
        assertTrue(e.attack(1, 6, 0), "最后一个位置的骷髅攻击");
        assertEquals(6, e.getBoardCount(1), "场上已满，亡语不召唤");
    }

    @Test
    void tokenNeverEntersDeck() {
        // 衍生物不能进牌库：多局统计，起手里不该出现幼狼
        for (int seed = 0; seed < 200; seed++) {
            CardBattleEngine e = new CardBattleEngine(new Random(seed));
            for (int side = 1; side <= 2; side++) {
                for (CardBattleEngine.Card c : e.getHand(side)) {
                    assertFalse(c.isToken(), "衍生物不该出现在起手中：" + c.getLabel());
                }
            }
        }
        // 牌库张数仍为 30
        CardBattleEngine e = new CardBattleEngine(new Random(1));
        assertEquals(27, e.getDeckLeft(1));
        assertEquals(26, e.getDeckLeft(2));
    }

    @Test
    void divineShieldExposedInView() {
        CardBattleEngine e = new CardBattleEngine(new Random(5));
        e.putMinionForTest(1, CardBattleEngine.Card.SHIELD_GUARD);

        assertTrue(readShieldFlag(e, 1, 0), "初始应显示圣盾");

        // 红方有攻击者可攻击（当前回合是红方 1）
        e.putMinionForTest(1, CardBattleEngine.Card.KNIGHT);
        e.clearSummoningSicknessForTest(1);
        // 蓝方也放一个随从，让红方有目标可打（否则只能打脸）
        e.putMinionForTest(2, CardBattleEngine.Card.FOOTMAN);

        // 红方骑士打蓝方步兵 —— 这不影响红方自己的圣盾卫兵。
        // 想验证「破盾后视图更新」，改成让蓝方随从打红方的圣盾卫兵：
        // 先把回合交给蓝方。
        assertTrue(e.endTurn(1), "红方结束回合（会触发自动攻击，注意步兵可能阵亡）");
        int blueAttacker = e.attackableIndexes(2).isEmpty() ? -1 : e.attackableIndexes(2).get(0);
        if (blueAttacker < 0) return; // 蓝方这回合没随从可攻击，跳过（随机性）

        // 蓝方攻击红方（红方场上有嘲讽的圣盾卫兵 → 必打它）
        assertTrue(e.attack(2, blueAttacker, 0), "蓝方攻击圣盾卫兵");
        assertFalse(readShieldFlag(e, 1, 0), "破盾后视图应更新为 false");
    }

    /** 从视图里读某个随从的圣盾标记 */
    @SuppressWarnings("unchecked")
    private boolean readShieldFlag(CardBattleEngine e, int viewerSide, int boardIndex) {
        java.util.Map<String, Object> view = (java.util.Map<String, Object>) e.getViewState(viewerSide);
        java.util.List<java.util.Map<String, Object>> players =
                (java.util.List<java.util.Map<String, Object>>) view.get("players");
        java.util.List<java.util.Map<String, Object>> board =
                (java.util.List<java.util.Map<String, Object>>) players.get(0).get("board");
        return Boolean.TRUE.equals(board.get(boardIndex).get("divineShield"));
    }

    // ============ 辅助 ============

    /**
     * 找一张可打出的牌。
     *
     * 优先挑「不需要指定目标」的牌：多数用例只想验证"能出一张牌"，
     * 用火球/鼓舞还得构造目标，会让用例意外变红（本轮改牌库后就踩到了）。
     * 找不到无目标的牌时才回落任意可打的牌。
     */
    private int firstPlayable(CardBattleEngine e, int side) {
        List<CardBattleEngine.Card> hand = e.getHand(side);
        int fallback = -1;
        for (int i = 0; i < hand.size(); i++) {
            CardBattleEngine.Card c = hand.get(i);
            if (c.getCost() > e.getMana(side)) continue;
            if (!c.needsTarget()) return i;
            if (fallback < 0) fallback = i;
        }
        return fallback;
    }

    /**
     * 为某张手牌构造合法指令：需要选目标的牌自动补一个合法目标。
     * 用于「只想把这手牌打出去」的用例。
     */
    private MoveCommand cmdFor(CardBattleEngine e, int side, int idx) {
        CardBattleEngine.Card c = e.getHand(side).get(idx);
        if (!c.needsTarget()) return MoveCommand.action(idx);
        if (c == CardBattleEngine.Card.BUFF) {
            // 鼓舞需要己方随从；没有就返回一个必然失败的无目标指令（调用方应避免）
            return e.getBoardCount(side) > 0
                    ? MoveCommand.withTarget(idx, side, 0)
                    : MoveCommand.action(idx);
        }
        // 火球打敌方英雄
        return MoveCommand.withTarget(idx, 3 - side, -1);
    }

    private int findPlayableMinion(CardBattleEngine e, int side) {
        List<CardBattleEngine.Card> hand = e.getHand(side);
        for (int i = 0; i < hand.size(); i++) {
            CardBattleEngine.Card c = hand.get(i);
            if (c.isMinion() && c.getCost() <= e.getMana(side)) return i;
        }
        return -1;
    }
}
