package com.gomoku.game;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 四子棋规则测试：重力落点 / 四连判定 / 平局 / AI
 */
class ConnectFourBoardTest {

    private ConnectFourBoard board;

    @BeforeEach
    void setUp() {
        board = new ConnectFourBoard();
    }

    @Test
    void gravityDrop() {
        assertEquals(GameType.CONNECT4, board.type());
        assertEquals(6, board.getRows());
        assertEquals(7, board.getCols());
        assertEquals(1, board.getCurrentTurn(), "红先行");
        // 必须落在重力落点（(5,3) 为第 3 列最低空位）
        assertTrue(board.placeStone(5, 3, 1));
        // 第二子落在同列 (4,3)
        assertTrue(board.placeStone(4, 3, 2));
        assertFalse(board.placeStone(5, 3, 1), "已占位非法");
        assertFalse(board.placeStone(2, 3, 1), "悬空位置非法（落点在 (3,3)）");
        assertTrue(board.placeStone(3, 3, 1), "落在当前重力落点应合法");
        assertEquals(1, board.getBoardSnapshot()[5][3]);
    }

    @Test
    void horizontalWin() {
        // 红：col 0,1,2,3 各落底行；黄穿插
        assertTrue(board.placeStone(5, 0, 1));
        assertTrue(board.placeStone(5, 4, 2));
        assertTrue(board.placeStone(5, 1, 1));
        assertTrue(board.placeStone(5, 5, 2));
        assertTrue(board.placeStone(5, 2, 1));
        assertTrue(board.placeStone(4, 4, 2));
        assertTrue(board.placeStone(5, 3, 1));
        assertTrue(board.isOver());
        assertEquals(1, board.getWinner());
        assertNotNull(board.getWinLine(), "应有四连坐标");
        assertEquals(4, board.getWinLine().length);
        assertNotNull(board.getScoreSummary());
    }

    @Test
    void verticalWin() {
        for (int i = 0; i < 4; i++) {
            assertTrue(board.placeStone(5 - i, 2, 1));
            if (i < 3) {
                assertTrue(board.placeStone(5 - i, 6, 2));
            }
        }
        assertTrue(board.isOver());
        assertEquals(1, board.getWinner());
    }

    @Test
    void diagonalWin() {
        // 红斜线 ↗：(5,0) (4,1) (3,2) 已就位；黄子垫高第 3 列使重力落点为 (2,3)
        board.setCell(5, 0, 1);
        board.setCell(4, 1, 1);
        board.setCell(3, 2, 1);
        board.setCell(5, 3, 2);
        board.setCell(4, 3, 2);
        board.setCell(3, 3, 2);
        board.setTurn(1);
        assertTrue(board.placeStone(2, 3, 1), "落在重力落点 (2,3)");
        assertTrue(board.isOver(), "斜四连应获胜");
        assertEquals(1, board.getWinner());
        assertEquals(4, board.getWinLine().length);
    }

    @Test
    void turnEnforced() {
        assertFalse(board.placeStone(5, 0, 2), "黄后手不能先下");
        assertTrue(board.placeStone(5, 0, 1));
        assertFalse(board.placeStone(4, 1, 1), "连续落子非法");
    }

    @Test
    void aiReturnsLegalDrop() {
        int[] mv = ConnectFourAI.INSTANCE.getBestMove(board, 1, 2);
        assertNotNull(mv);
        assertEquals(2, mv.length);
        assertEquals(5, mv[0], "空盘第一子应落底行");
        assertTrue(mv[1] >= 0 && mv[1] < 7);
    }

    @Test
    void aiTakesImmediateWin() {
        // 红 (5,0),(5,1),(5,2)，黄 (5,4),(5,5),(4,5) — 红 (5,3) 即胜
        int[][] seq = {
                {5, 0, 1}, {5, 4, 2}, {5, 1, 1}, {5, 5, 2},
                {5, 2, 1}, {4, 5, 2}
        };
        for (int[] m : seq) {
            assertTrue(board.placeStone(m[0], m[1], m[2]));
        }
        int[] mv = ConnectFourAI.INSTANCE.getBestMove(board, 1, 4);
        assertNotNull(mv);
        assertEquals(3, mv[1], "AI 应选择 (5,3) 直接四连获胜");
        assertEquals(5, mv[0]);
    }

    @Test
    void aiBlocksOpponentWin() {
        // 黄 (5,0),(5,1),(5,2)，红 (5,4),(4,4)? — 黄在 (5,3) 即胜，红必须堵
        int[][] seq = {
                {5, 4, 1}, {5, 0, 2}, {4, 4, 1}, {5, 1, 2}, {5, 6, 1}, {5, 2, 2}
        };
        for (int[] m : seq) {
            assertTrue(board.placeStone(m[0], m[1], m[2]));
        }
        int[] mv = ConnectFourAI.INSTANCE.getBestMove(board, 1, 4);
        assertNotNull(mv);
        assertEquals(3, mv[1], "AI 应堵住黄的 (5,3) 制胜点");
    }

    @Test
    void engineMetadata() {
        assertEquals(30, board.getStepTimeSeconds());
        assertEquals(300, board.getTotalTimeSeconds());
        assertTrue(board.placeStone(5, 0, 1));
        assertTrue(board.isOwnPieceAt(5, 0, 1), "己方棋子判定");
        assertFalse(board.isOwnPieceAt(5, 0, 2));
        assertFalse(board.isOwnPieceAt(4, 0, 1), "空点判定");
    }
}
