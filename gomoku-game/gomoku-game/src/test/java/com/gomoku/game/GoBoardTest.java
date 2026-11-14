package com.gomoku.game;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 围棋（9路）规则测试：提子 / 禁自杀 / 简单劫 / 停一手 / 数子计分
 */
class GoBoardTest {

    private GoBoard board;

    @BeforeEach
    void setUp() {
        board = new GoBoard();
    }

    /** 白棋停一手（交替行棋辅助） */
    private void whitePass() {
        assertTrue(board.pass(GoBoard.WHITE), "白棋停一手应成功");
    }

    @Test
    void turnEnforced() {
        assertEquals(GameType.GO9, board.type());
        assertEquals(GoBoard.BLACK, board.getCurrentTurn());
        assertFalse(board.placeStone(4, 4, GoBoard.WHITE), "黑先手");
        assertFalse(board.pass(GoBoard.WHITE), "轮到黑棋时白棋不能停一手");
        assertTrue(board.placeStone(4, 4, GoBoard.BLACK));
    }

    @Test
    void captureSingleStone() {
        // 构造：白 (4,4) 仅剩 (4,5) 一口气，黑棋收气提子
        assertTrue(board.placeStone(3, 4, GoBoard.BLACK)); // B
        assertTrue(board.placeStone(4, 4, GoBoard.WHITE)); // W
        assertTrue(board.placeStone(5, 4, GoBoard.BLACK)); // B
        whitePass();                                        // W pass
        assertTrue(board.placeStone(4, 3, GoBoard.BLACK)); // B，白 (4,4) 只剩 1 气
        whitePass();                                        // W pass
        assertTrue(board.placeStone(4, 5, GoBoard.BLACK), "收最后一气应成功");
        assertEquals(GoBoard.EMPTY, board.getBoardSnapshot()[4][4], "白子应被提掉");
        // 效果：提子列表
        assertEquals(1, board.getLastMoveEffects().size());
        assertArrayEquals(new int[]{4, 4, GoBoard.EMPTY}, board.getLastMoveEffects().get(0));
    }

    @Test
    void suicideForbidden() {
        // 构造：(0,0) 点被白棋包围（白 (0,1),(1,0)），黑棋下 (0,0) 为自杀
        assertTrue(board.placeStone(1, 1, GoBoard.BLACK));
        assertTrue(board.placeStone(0, 1, GoBoard.WHITE));
        assertTrue(board.pass(GoBoard.BLACK), "黑停一手");
        assertTrue(board.placeStone(1, 0, GoBoard.WHITE));

        assertFalse(board.placeStone(0, 0, GoBoard.BLACK), "无气且无提子的落子应被禁止");
        assertEquals(GoBoard.EMPTY, board.getBoardSnapshot()[0][0], "禁自杀点应保持为空");

        // 白棋下同一点是合法的（连接己方且留有气）
        assertTrue(board.pass(GoBoard.BLACK), "黑停一手让白棋行棋");
        assertTrue(board.placeStone(0, 0, GoBoard.WHITE), "白棋占 (0,0) 应合法");
    }

    @Test
    void simpleKoForbidden() {
        // 经典劫形：
        //   B(3,4) W(4,4) B(5,4) W(3,5) B(4,3) W(4,6) B pass W(5,5) → B(4,5) 提 W(4,4)
        //   此时 W 回提 (4,4) 会复现上一局面 → 被简单劫禁止
        assertTrue(board.placeStone(3, 4, GoBoard.BLACK));
        assertTrue(board.placeStone(4, 4, GoBoard.WHITE));
        assertTrue(board.placeStone(5, 4, GoBoard.BLACK));
        assertTrue(board.placeStone(3, 5, GoBoard.WHITE));
        assertTrue(board.placeStone(4, 3, GoBoard.BLACK));
        assertTrue(board.placeStone(4, 6, GoBoard.WHITE));
        assertTrue(board.pass(GoBoard.BLACK));
        assertTrue(board.placeStone(5, 5, GoBoard.WHITE));

        // 黑提劫
        assertTrue(board.placeStone(4, 5, GoBoard.BLACK), "黑 (4,5) 应提掉白 (4,4)");
        assertEquals(GoBoard.EMPTY, board.getBoardSnapshot()[4][4]);

        // 白立即回提 → 劫禁着
        assertFalse(board.placeStone(4, 4, GoBoard.WHITE), "回提复现局面应被简单劫禁止");
        assertEquals(GoBoard.EMPTY, board.getBoardSnapshot()[4][4], "劫争点应保持为空");
        assertEquals(GoBoard.WHITE, board.getCurrentTurn(), "禁着后仍是白棋行棋");

        // 白寻劫（下别处）后劫争点仍未解禁（简单劫只看上一局面）
        assertTrue(board.placeStone(0, 0, GoBoard.WHITE));
        assertTrue(board.placeStone(8, 8, GoBoard.BLACK));
        // 现在白再下 (4,4)：黑 (4,5) 有 1 气 ((4,4) 空)，白下 (4,4) 提掉黑 (4,5)
        assertTrue(board.placeStone(4, 4, GoBoard.WHITE), "隔一手后可以正常提回");
        assertEquals(GoBoard.EMPTY, board.getBoardSnapshot()[4][5]);
    }

    @Test
    void twoPassesEndGameAndScore() {
        assertTrue(board.placeStone(4, 4, GoBoard.BLACK));
        assertTrue(board.placeStone(5, 5, GoBoard.WHITE));
        assertTrue(board.pass(GoBoard.BLACK));
        assertFalse(board.isOver(), "一手停着不应终局");
        assertTrue(board.pass(GoBoard.WHITE), "连续两停应终局");
        assertTrue(board.isOver());
        assertNotNull(board.getScoreSummary(), "应有数子结果说明");
        // 盘面黑 1 子，白 1 子 + 贴 7.5 → 白胜
        assertEquals(GoBoard.WHITE, board.getWinner());
    }

    @Test
    void passResetsOnRealMove() {
        assertTrue(board.pass(GoBoard.BLACK));
        assertTrue(board.placeStone(4, 4, GoBoard.WHITE));
        assertFalse(board.isOver(), "实手后停着一手计数应清零");
        assertTrue(board.pass(GoBoard.BLACK));
        assertFalse(board.isOver());
        assertTrue(board.pass(GoBoard.WHITE));
        assertTrue(board.isOver());
    }

    @Test
    void moveHistoryRecordsPass() {
        assertTrue(board.placeStone(4, 4, GoBoard.BLACK));
        assertTrue(board.pass(GoBoard.WHITE));
        var history = board.getMoveHistory();
        assertEquals(2, history.size());
        int[] pass = history.get(1);
        assertEquals(GameEngine.PASS_MOVE_ROW, pass[0], "停一手应以 PASS_MOVE_ROW 记录");
        assertEquals(GoBoard.WHITE, pass[2]);
    }

    @Test
    void aiReturnsLegalOrPass() {
        // 空棋盘 AI 应返回天元附近
        int[] first = GoAI.INSTANCE.getBestMove(board, GoBoard.BLACK, 4);
        assertNotNull(first);
        assertEquals(4, first[0]);
        assertEquals(4, first[1]);

        assertTrue(board.placeStone(4, 4, GoBoard.BLACK));
        int[] second = GoAI.INSTANCE.getBestMove(board, GoBoard.WHITE, 6);
        assertNotNull(second);
        assertTrue(board.isLegalMove(second[0], second[1], GoBoard.WHITE), "AI 落点必须合法");
    }

    @Test
    void engineMetadata() {
        assertEquals(60, board.getStepTimeSeconds());
        assertEquals(600, board.getTotalTimeSeconds());
    }
}
