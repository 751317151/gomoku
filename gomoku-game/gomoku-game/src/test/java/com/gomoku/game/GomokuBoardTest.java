package com.gomoku.game;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 五子棋棋盘规则测试
 */
class GomokuBoardTest {

    private GomokuBoard board;

    @BeforeEach
    void setUp() {
        board = new GomokuBoard();
    }

    /** 交替落子辅助：黑棋沿 (row, colStart+i) 连线，白棋在 (0, i) 闲着 */
    private void playHorizontal(int row, int colStart, int count) {
        for (int i = 0; i < count; i++) {
            assertTrue(board.placeStone(row, colStart + i, GomokuBoard.BLACK), "黑棋落子应成功");
            if (i < count - 1) {
                assertTrue(board.placeStone(0, 10 + i, GomokuBoard.WHITE), "白棋落子应成功");
            }
        }
    }

    @Test
    void blackFirstTurnEnforced() {
        // 黑先手：白棋不能先下
        assertFalse(board.placeStone(7, 7, GomokuBoard.WHITE), "轮次校验：白棋不能先落子");
        assertTrue(board.placeStone(7, 7, GomokuBoard.BLACK));
        assertFalse(board.placeStone(7, 8, GomokuBoard.BLACK), "轮次校验：连续落子应被拒绝");
    }

    @Test
    void occupiedCellRejected() {
        assertTrue(board.placeStone(7, 7, GomokuBoard.BLACK));
        assertFalse(board.placeStone(7, 7, GomokuBoard.WHITE), "已占位不能落子");
    }

    @Test
    void fiveInRowWins() {
        playHorizontal(7, 4, 5);
        assertTrue(board.isOver(), "五连后应终局");
        assertEquals(GomokuBoard.BLACK, board.getWinner());
        assertNotNull(board.getWinLine(), "应给出获胜连线");
        assertEquals(5, board.getWinLine().length);
    }

    @Test
    void gameOverBlocksFurtherMoves() {
        playHorizontal(7, 4, 5);
        assertTrue(board.isOver());
        // 终局后任何落子都应被拒绝
        assertFalse(board.placeStone(0, 0, GomokuBoard.WHITE), "终局后不能继续落子");
    }

    @Test
    void fourInRowNotOver() {
        playHorizontal(7, 4, 4);
        assertFalse(board.isOver(), "四连不应终局");
        assertNull(board.getWinner());
    }

    @Test
    void blockedFourNotWin() {
        // 黑四连被白棋堵住一端也不应获胜（只有四连本身）
        playHorizontal(7, 4, 4);
        assertFalse(board.isOver());
    }

    @Test
    void verticalWin() {
        for (int i = 0; i < 5; i++) {
            assertTrue(board.placeStone(3 + i, 7, GomokuBoard.BLACK));
            if (i < 4) {
                assertTrue(board.placeStone(0, 10 + i, GomokuBoard.WHITE));
            }
        }
        assertTrue(board.isOver());
        assertEquals(GomokuBoard.BLACK, board.getWinner());
    }

    @Test
    void diagonalWin() {
        for (int i = 0; i < 5; i++) {
            assertTrue(board.placeStone(3 + i, 3 + i, GomokuBoard.BLACK));
            if (i < 4) {
                assertTrue(board.placeStone(0, 10 + i, GomokuBoard.WHITE));
            }
        }
        assertTrue(board.isOver());
        assertEquals(GomokuBoard.BLACK, board.getWinner());
    }

    @Test
    void winLineCoversFiveStones() {
        playHorizontal(7, 4, 5);
        int[][] line = board.getWinLine();
        for (int i = 0; i < 5; i++) {
            final int r = 7, c = 4 + i;
            assertTrue(java.util.Arrays.stream(line).anyMatch(p -> p[0] == r && p[1] == c),
                    "获胜连线应包含 (" + r + "," + c + ")");
        }
    }

    @Test
    void engineMetadata() {
        assertEquals(GameType.GOMOKU, board.type());
        assertEquals(15, board.getRows());
        assertEquals(15, board.getCols());
        assertEquals(30, board.getStepTimeSeconds());
        assertEquals(300, board.getTotalTimeSeconds());
        assertTrue(board.getLastMoveEffects().isEmpty(), "五子棋无翻子/提子效果");
    }

    @Test
    void resetClearsState() {
        playHorizontal(7, 4, 5);
        assertTrue(board.isOver());
        board.reset();
        assertFalse(board.isOver());
        assertNull(board.getWinner());
        assertEquals(0, board.getMoveCount());
        assertEquals(0, board.getMoveHistory().size());
        assertTrue(board.isValidMove(7, 4), "重置后应可重新落子");
    }
}
