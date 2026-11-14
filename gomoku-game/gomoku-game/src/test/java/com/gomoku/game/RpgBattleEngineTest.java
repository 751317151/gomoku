package com.gomoku.game;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回合制 RPG 对战引擎测试
 *
 * 全部用固定随机种子，否则伤害浮动会让断言随运行次数漂移。
 */
class RpgBattleEngineTest {

    private static RpgBattleEngine newEngine() {
        return new RpgBattleEngine(new Random(42));
    }

    private static MoveCommand act(RpgBattleEngine.Action action) {
        return MoveCommand.action(action.getIndex());
    }

    @Test
    @SuppressWarnings("unchecked")
    void battleStartsWithFullHpAndPlayerOneFirst() {
        RpgBattleEngine e = newEngine();
        assertEquals(GameType.RPG, e.type());
        assertEquals(1, e.getCurrentTurn());
        assertEquals(RpgBattleEngine.MAX_HP, e.getHp(1));
        assertEquals(RpgBattleEngine.MAX_HP, e.getHp(2));
        assertFalse(e.isOver());
        assertNull(e.getWinner());
        assertEquals(0, e.getMoveCount());

        Map<String, Object> view = (Map<String, Object>) e.getViewState();
        Map<String, Object> first = ((List<Map<String, Object>>) view.get("fighters")).get(0);
        assertEquals(RpgBattleEngine.START_POTIONS, first.get("potions"), "开局应带满药水");
    }

    @Test
    void attackDealsDamageAndSwitchesTurn() {
        RpgBattleEngine e = newEngine();
        assertTrue(e.applyMove(act(RpgBattleEngine.Action.ATTACK), 1));
        assertTrue(e.getHp(2) < RpgBattleEngine.MAX_HP, "攻击应造成伤害");
        assertEquals(2, e.getCurrentTurn(), "出手后应轮到对方");
        assertEquals(1, e.getMoveSeq());
        assertEquals(1, e.getMoveCount());
    }

    @Test
    void outOfTurnMoveIsRejected() {
        RpgBattleEngine e = newEngine();
        // 蓝方先手出手必须被拒：这是房间层不会替我们做的校验
        assertFalse(e.applyMove(act(RpgBattleEngine.Action.ATTACK), 2));
        assertEquals(0, e.getMoveSeq(), "被拒的行动不应推进手数");
        assertEquals(1, e.getCurrentTurn());
    }

    @Test
    void boardCoordinatesAreRejected() {
        RpgBattleEngine e = newEngine();
        // RPG 只认 action 下标，坐标指令属于客户端串线，必须拒绝而不是猜一个技能
        assertFalse(e.applyMove(MoveCommand.at(3, 4), 1));
        assertEquals(0, e.getMoveSeq());
    }

    @Test
    void skillCostsMpAndFailsWithoutMp() {
        RpgBattleEngine e = newEngine();
        // 满蓝时回合开始的回蓝会被上限吃掉，所以只看「扣掉技能蓝耗」的结果
        assertTrue(e.applyMove(act(RpgBattleEngine.Action.FIREBALL), 1));
        assertEquals(RpgBattleEngine.MAX_MP - RpgBattleEngine.Action.FIREBALL.getMpCost(),
                e.getMp(1), "火球术应扣蓝（满蓝时回蓝被上限吃掉）");

        // 把蓝耗光，再连发火球必然被拒
        while (e.getMp(1) >= RpgBattleEngine.Action.FIREBALL.getMpCost()) {
            int turn = e.getCurrentTurn();
            if (turn == 1) {
                e.applyMove(act(RpgBattleEngine.Action.FIREBALL), 1);
            } else {
                e.applyMove(act(RpgBattleEngine.Action.ATTACK), 2);
            }
            if (e.isOver()) return; // 提前打死也算通过，本用例只关心蓝量约束
        }
        // 轮到红方且蓝不够时必须拒绝
        while (e.getCurrentTurn() != 1 && !e.isOver()) {
            e.applyMove(act(RpgBattleEngine.Action.ATTACK), 2);
        }
        if (!e.isOver()) {
            assertFalse(e.canUse(1, RpgBattleEngine.Action.FIREBALL), "蓝不足时火球应不可用");
            int seq = e.getMoveSeq();
            assertFalse(e.applyMove(act(RpgBattleEngine.Action.FIREBALL), 1), "蓝不足时火球应被拒");
            assertEquals(seq, e.getMoveSeq());
        }
    }

    @Test
    void potionIsLimited() {
        RpgBattleEngine e = newEngine();
        for (int i = 0; i < RpgBattleEngine.START_POTIONS; i++) {
            assertTrue(e.canUse(1, RpgBattleEngine.Action.POTION), "第 " + (i + 1) + " 瓶应可用");
            assertTrue(e.applyMove(act(RpgBattleEngine.Action.POTION), 1));
            // 换对方走一手，把回合交还红方
            assertTrue(e.applyMove(act(RpgBattleEngine.Action.ATTACK), 2));
        }
        assertFalse(e.canUse(1, RpgBattleEngine.Action.POTION), "药水用完应不可用");
        assertFalse(e.applyMove(act(RpgBattleEngine.Action.POTION), 1), "药水用完应被拒");
    }

    @Test
    void fireballAppliesBurnAndBurnTicksOnOwnerTurn() {
        RpgBattleEngine e = newEngine();
        assertTrue(e.applyMove(act(RpgBattleEngine.Action.FIREBALL), 1));
        // 蓝方被点燃，其回合开始时先吃灼烧
        int hpBefore = e.getHp(2);
        assertTrue(e.applyMove(act(RpgBattleEngine.Action.ATTACK), 2));
        assertTrue(e.getHp(2) < hpBefore, "灼烧应在自己回合开始掉血");
    }

    @Test
    void defendReducesIncomingDamage() {
        RpgBattleEngine normal = newEngine();
        normal.applyMove(act(RpgBattleEngine.Action.ATTACK), 1); // 红方普攻
        int normalLoss = RpgBattleEngine.MAX_HP - normal.getHp(2);

        RpgBattleEngine defended = newEngine();
        defended.applyMove(act(RpgBattleEngine.Action.DEFEND), 1);
        defended.applyMove(act(RpgBattleEngine.Action.ATTACK), 2); // 蓝方打防御中的红方
        int defendedLoss = RpgBattleEngine.MAX_HP - defended.getHp(1);

        assertTrue(defendedLoss < normalLoss,
                "防御应减少受到的伤害：正常 " + normalLoss + " vs 防御 " + defendedLoss);
    }

    @Test
    void battleEndsWhenSomeoneFallsAndWinnerIsReported() {
        RpgBattleEngine e = newEngine();
        int guard = 0;
        while (!e.isOver() && guard++ < 500) {
            int turn = e.getCurrentTurn();
            MoveCommand cmd = RpgAI.INSTANCE.decide(e, turn, 6);
            assertNotNull(cmd, "AI 必须给出可行动作");
            assertTrue(e.applyMove(cmd, turn), "AI 给出的动作必须合法：turn=" + turn + " cmd=" + cmd);
        }
        assertTrue(e.isOver(), "500 手内必须分出胜负，说明战斗不会无限拖");
        assertNotNull(e.getWinner());
        assertNotNull(e.getScoreSummary());
        assertTrue(e.getHp(e.getWinner()) > 0);
        assertEquals(0, e.getHp(3 - e.getWinner()));
    }

    @Test
    void moveHistoryEncodesSideActionAndValue() {
        RpgBattleEngine e = newEngine();
        e.applyMove(act(RpgBattleEngine.Action.ATTACK), 1);
        List<int[]> history = e.getMoveHistory();
        assertEquals(1, history.size());
        int[] entry = history.get(0);
        assertEquals(3, entry.length, "每手应为 {side, action, value}");
        assertEquals(1, entry[RpgBattleEngine.HIST_SIDE]);
        assertEquals(RpgBattleEngine.Action.ATTACK.getIndex(), entry[RpgBattleEngine.HIST_ACTION]);
        assertTrue(entry[RpgBattleEngine.HIST_VALUE] > 0, "普攻的 value 应是造成的伤害");
    }

    @Test
    void resetClearsBattle() {
        RpgBattleEngine e = newEngine();
        e.applyMove(act(RpgBattleEngine.Action.HEAVY), 1);
        e.reset();
        assertEquals(RpgBattleEngine.MAX_HP, e.getHp(2));
        assertEquals(RpgBattleEngine.MAX_MP, e.getMp(1), "重置后蓝量应回到上限");
        assertEquals(1, e.getCurrentTurn());
        assertEquals(0, e.getMoveCount());
        assertEquals(0, e.getMoveSeq());
        assertFalse(e.isOver());
    }

    @Test
    @SuppressWarnings("unchecked")
    void viewStateCarriesEverythingClientNeeds() {
        RpgBattleEngine e = newEngine();
        e.setFighterName(1, "小明");
        Map<String, Object> view = (Map<String, Object>) e.getViewState();

        assertEquals("RPG", view.get("kind"));
        assertEquals(1, view.get("turn"));

        List<Map<String, Object>> fighters = (List<Map<String, Object>>) view.get("fighters");
        assertEquals(2, fighters.size());
        assertEquals("小明", fighters.get(0).get("name"), "昵称应注入到视图里");
        assertEquals(RpgBattleEngine.MAX_HP, fighters.get(0).get("maxHp"));

        List<Map<String, Object>> actions = (List<Map<String, Object>>) view.get("actions");
        assertEquals(RpgBattleEngine.Action.values().length, actions.size());
        assertEquals(true, actions.get(0).get("available"), "普攻永远可用");

        assertTrue(((List<String>) view.get("log")).size() > 0, "至少要有一条战斗日志");
    }

    @Test
    void viewLogIsCappedSoSyncPacketsStaySmall() {
        RpgBattleEngine e = newEngine();
        int guard = 0;
        while (!e.isOver() && guard++ < 500) {
            int turn = e.getCurrentTurn();
            e.applyMove(RpgAI.INSTANCE.decide(e, turn, 4), turn);
        }
        @SuppressWarnings("unchecked")
        List<String> log = (List<String>) ((Map<String, Object>) e.getViewState()).get("log");
        assertTrue(log.size() <= 12, "日志应截断到最近 12 条，实际 " + log.size());
    }
}
