package com.gomoku.game;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 取石子博弈测试
 *
 * 除了常规规则，重点验证 Bouton 定理（异或和决定胜负）——
 * 这是 AI「完美解」的正确性基础，值得单独钉死。
 */
class NimBattleEngineTest {

    private NimBattleEngine engine;

    @BeforeEach
    void setUp() {
        engine = new NimBattleEngine();
    }

    @Test
    void initialState() {
        assertEquals(GameType.NIM, engine.type());
        assertArrayEquals(new int[]{5, 7, 9}, engine.getPiles());
        assertEquals(21, engine.totalStones());
        assertEquals(1, engine.getCurrentTurn(), "先手红方");
        assertFalse(engine.isOver());
        assertEquals(30, engine.getStepTimeSeconds());
        assertEquals(300, engine.getTotalTimeSeconds());
        // 5 ^ 7 ^ 9 = 11 ≠ 0 → 先手必胜态
        assertEquals(5 ^ 7 ^ 9, engine.nimSum());
        assertNotEquals(0, engine.nimSum());
    }

    @Test
    void takeStonesUpdatesPiles() {
        assertTrue(engine.applyMove(MoveCommand.at(0, 3), 1), "从第 1 堆拿 3 颗");
        assertArrayEquals(new int[]{2, 7, 9}, engine.getPiles());
        assertEquals(18, engine.totalStones());
        assertEquals(2, engine.getCurrentTurn(), "换手");
        assertEquals(1, engine.getMoveCount());
    }

    @Test
    void rejectIllegalMoves() {
        // 拿超过拥有数量
        assertFalse(engine.applyMove(MoveCommand.at(0, 6), 1), "超过该堆数量应拒绝");
        // 拿 0 颗
        assertFalse(engine.applyMove(MoveCommand.at(0, 0), 1), "拿 0 颗无意义");
        // 负数
        assertFalse(engine.applyMove(MoveCommand.at(0, -1), 1));
        // 堆号越界
        assertFalse(engine.applyMove(MoveCommand.at(9, 1), 1));
        // 轮次错误
        assertFalse(engine.applyMove(MoveCommand.at(0, 1), 2), "非当前回合方");
        // 行动序号指令（本玩法不接受）
        assertFalse(engine.applyMove(MoveCommand.action(0), 1), "不接受 action 指令");
        assertEquals(0, engine.getMoveCount(), "被拒绝的指令不计入手数");
    }

    @Test
    void takingLastStoneWins() {
        // 直接把三堆清空：每堆拿光，最后一步的人赢
        assertTrue(engine.applyMove(MoveCommand.at(0, 5), 1));
        assertTrue(engine.applyMove(MoveCommand.at(1, 7), 2));
        assertEquals(9, engine.totalStones());
        assertTrue(engine.applyMove(MoveCommand.at(2, 9), 1), "拿走最后一颗");

        assertTrue(engine.isOver());
        assertEquals(1, engine.getWinner(), "拿最后一颗的玩家获胜");
        assertNotNull(engine.getScoreSummary());
        assertEquals(0, engine.totalStones());
    }

    @Test
    void passNotAllowed() {
        assertFalse(engine.pass(1), "Nim 不支持停一手");
    }

    @Test
    void xorStrategyIsWinning() {
        // 对任意非零异或和的局面，最优走法都能把异或和归零
        int[][] positions = {{5, 7, 9}, {1, 2, 3}, {4, 5}, {1}, {3, 5, 7}, {2, 2, 1}};
        for (int[] piles : positions) {
            int xor = 0;
            for (int p : piles) xor ^= p;
            int[] mv = NimAI.optimalMove(piles);
            if (xor == 0) {
                assertNull(mv, "异或和为 0 时应无「归零」走法：" + java.util.Arrays.toString(piles));
                continue;
            }
            assertNotNull(mv, "异或和非 0 时必须存在最优走法：" + java.util.Arrays.toString(piles));
            int[] after = piles.clone();
            after[mv[0]] -= mv[1];
            int newXor = 0;
            for (int p : after) newXor ^= p;
            assertEquals(0, newXor, "最优走法应把异或和归零：" + java.util.Arrays.toString(piles));
        }
    }

    @Test
    void aiTakesWinningMoveOnHard() {
        // 困难难度必走最优：异或和非 0 时落子后异或和应为 0
        NimBattleEngine nim = new NimBattleEngine();
        MoveCommand cmd = NimAI.INSTANCE.decide(nim, 1, 6);
        assertNotNull(cmd);
        int pile = cmd.getRow(), take = cmd.getCol();
        assertTrue(pile >= 0 && pile < 3);
        assertTrue(take >= 1 && take <= nim.getPiles()[pile]);
        assertTrue(nim.applyMove(cmd, 1));

        assertEquals(0, nim.nimSum(), "困难 AI 应把异或和归零（完美解）");
    }

    @Test
    void aiVersusAiTerminates() {
        NimBattleEngine nim = new NimBattleEngine();
        for (int i = 0; i < 100 && !nim.isOver(); i++) {
            int side = nim.getCurrentTurn();
            MoveCommand cmd = NimAI.INSTANCE.decide(nim, side, 6);
            if (cmd == null) break;
            assertTrue(nim.applyMove(cmd, side), "AI 走法必须合法");
        }
        assertTrue(nim.isOver(), "两 AI 对局应收敛");
        assertNotNull(nim.getWinner());
    }

    @Test
    void viewStateIsPublic() {
        var v1 = (java.util.Map<?, ?>) engine.getViewState(1);
        var v2 = (java.util.Map<?, ?>) engine.getViewState(2);
        // 石子数量对双方公开，视角不影响内容
        assertArrayEquals((int[]) v1.get("piles"), (int[]) v2.get("piles"));
        assertEquals(21, ((Number) v1.get("total")).intValue());
        assertEquals("NIM", v1.get("kind"));
    }

    @Test
    void historyEncodesPileAndCount() {
        assertTrue(engine.applyMove(MoveCommand.at(1, 2), 1));
        int[] h = engine.getMoveHistory().get(0);
        assertEquals(3, h.length);
        assertEquals(1, h[NimBattleEngine.HIST_SIDE]);
        assertEquals(1, h[NimBattleEngine.HIST_PILE]);
        assertEquals(2, h[NimBattleEngine.HIST_REMOVED]);
    }

    @Test
    void resetRestoresInitial() {
        assertTrue(engine.applyMove(MoveCommand.at(0, 5), 1));
        engine.reset();
        assertArrayEquals(new int[]{5, 7, 9}, engine.getPiles());
        assertEquals(0, engine.getMoveCount());
        assertEquals(1, engine.getCurrentTurn());
        assertFalse(engine.isOver());
    }
}
