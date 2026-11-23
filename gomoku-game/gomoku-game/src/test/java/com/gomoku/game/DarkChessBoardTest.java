package com.gomoku.game;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 暗棋规则单测：随机开局、翻子、走子、吃子等级、炮架、胜负与隐藏信息。 */
class DarkChessBoardTest {

    private DarkChessBoard board;

    @BeforeEach
    void setUp() {
        board = new DarkChessBoard(20261122L);
    }

    @Test
    void initialBoardHasThirtyTwoHiddenPiecesAndRedStarts() {
        assertEquals(GameType.DARK_CHESS, board.type());
        assertEquals(4, board.getRows());
        assertEquals(8, board.getCols());
        assertEquals(1, board.getCurrentTurn(), "红方先手");
        assertEquals(32, board.legalFlips(1).size(), "开局 32 枚全部可翻");
        assertTrue(board.legalMoves(1).isEmpty(), "开局没有明棋可走");

        int[][] visible = board.getVisibleBoardSnapshot();
        for (int r = 0; r < DarkChessBoard.ROWS; r++) {
            for (int c = 0; c < DarkChessBoard.COLS; c++) {
                assertEquals(DarkChessBoard.HIDDEN, visible[r][c],
                        "客户端只能看到背面，不能泄露 (" + r + "," + c + ") 的真实棋子");
            }
        }
    }

    @Test
    void flipRevealsPieceAndPassesTurn() {
        assertTrue(board.placeStone(0, 0, 1));
        assertNotEquals(DarkChessBoard.HIDDEN, board.getVisibleBoardSnapshot()[0][0], "翻开后身份公开");
        assertEquals(2, board.getCurrentTurn());
        assertEquals(1, board.getMoveCount());
        assertEquals(1, board.getMoveSeq());
        assertFalse(board.placeStone(0, 1, 1), "同一方不能连续行动");
        assertEquals(1, board.getMoveCount(), "被拒行动不应推进手数");
    }

    @Test
    void movingRevealedPieceRequiresOwnTurnAndLegalTarget() {
        board.clearForTest();
        board.putForTest(1, 1, 1, DarkChessBoard.ROOK);
        assertTrue(board.placeStone(1, 1, 1, 4, 1), "车可以直线移动");
        assertEquals(14, board.getBoardSnapshot()[1][4]);
        assertEquals(0, board.getBoardSnapshot()[1][1], "起点清空");
        assertEquals(2, board.getCurrentTurn());

        board.forceTurnForTest(2);
        assertFalse(board.placeStone(1, 4, 2, 4, 2), "不能移动对方棋子");
        assertEquals(0, board.getBoardSnapshot()[2][4]);
    }

    @Test
    void rankCaptureRulesAndSoldierBeatsKing() {
        assertTrue(DarkChessBoard.canCapture(DarkChessBoard.KING, DarkChessBoard.GUARD));
        assertTrue(DarkChessBoard.canCapture(DarkChessBoard.SOLDIER, DarkChessBoard.KING),
                "兵可以吃帅");
        assertFalse(DarkChessBoard.canCapture(DarkChessBoard.KING, DarkChessBoard.SOLDIER),
                "帅不能吃兵");
        assertTrue(DarkChessBoard.canCapture(DarkChessBoard.GUARD, DarkChessBoard.ROOK),
                "仕大于车，可以吃");
        assertFalse(DarkChessBoard.canCapture(DarkChessBoard.ROOK, DarkChessBoard.GUARD),
                "车不能吃仕");

        board.clearForTest();
        board.putForTest(1, 0, 1, DarkChessBoard.SOLDIER);
        board.putForTest(0, 0, 2, DarkChessBoard.KING);
        assertTrue(board.isLegalMove(1, 0, 0, 0, 1), "兵向上吃将");
    }

    @Test
    void cannonNeedsExactlyOneScreenToCapture() {
        board.clearForTest();
        board.putForTest(1, 0, 1, DarkChessBoard.CANNON);
        board.putForTest(1, 4, 2, DarkChessBoard.ROOK);
        assertFalse(board.isLegalMove(1, 0, 1, 4, 1), "没有炮架不能吃");
        board.putForTest(1, 2, 1, DarkChessBoard.SOLDIER);
        assertTrue(board.isLegalMove(1, 0, 1, 4, 1), "隔一枚炮架可以吃");
        board.putForTest(1, 3, 2, DarkChessBoard.SOLDIER);
        assertFalse(board.isLegalMove(1, 0, 1, 4, 1), "两个炮架不能吃");
        assertTrue(board.isLegalMove(1, 0, 1, 3, 1), "炮可以正常吃无遮挡的相邻敌棋");
    }

    @Test
    void hiddenPieceCanBeCapturedWithoutRevealingIdentityToOpponent() {
        board.clearForTest();
        board.putForTest(1, 0, 1, DarkChessBoard.ROOK);
        board.putHiddenForTest(1, 1, 2, DarkChessBoard.KING);
        board.putForTest(3, 7, 2, DarkChessBoard.SOLDIER);
        assertTrue(board.isLegalMove(1, 0, 1, 1, 1), "背面棋子可以被直接吃");
        assertTrue(board.placeStone(1, 0, 1, 1, 1));
        assertEquals(14, board.getBoardSnapshot()[1][1], "棋盘上留下己方车");
        assertEquals(14, board.getVisibleBoardSnapshot()[1][1]);
        java.util.Map<?, ?> view = (java.util.Map<?, ?>) board.getViewState(1);
        assertNull(view.get("lastCaptured"), "被吃暗子的身份不能通过实时视图泄漏");
        assertEquals(0, board.getMoveHistory().get(0)[7],
                "被吃暗子的真实身份不应写入棋谱");
    }

    @Test
    void capturingLastPieceWinsAndEndsGame() {
        board.clearForTest();
        board.putForTest(1, 0, 1, DarkChessBoard.ROOK);
        board.putForTest(1, 2, 2, DarkChessBoard.SOLDIER);
        board.putForTest(3, 7, 2, DarkChessBoard.SOLDIER);
        assertTrue(board.placeStone(1, 0, 1, 2, 1));
        assertTrue(board.isOver());
        assertEquals(1, board.getWinner());
    }

    @Test
    void noLegalActionLosesForSideToMove() {
        board.clearForTest();
        board.putForTest(2, 0, 1, DarkChessBoard.ROOK);
        board.putForTest(3, 0, 2, DarkChessBoard.SOLDIER);
        board.forceTurnForTest(2);
        // 黑卒只能向下，已到底；又无暗子可翻。红方车虽远，但黑方无事可做。
        assertTrue(board.legalMoves(2).isEmpty());
        assertTrue(board.legalFlips(2).isEmpty());
        // 通过走一步红车触发对方无子可动判定
        board.forceTurnForTest(1);
        assertTrue(board.placeStone(2, 0, 2, 1, 1));
        assertTrue(board.isOver());
        assertEquals(1, board.getWinner());
    }

    @Test
    void quietTurnLimitEndsInDraw() {
        board.clearForTest();
        board.putForTest(0, 0, 1, DarkChessBoard.ROOK);
        board.putForTest(3, 7, 2, DarkChessBoard.ROOK);
        board.setQuietTurnsForTest(DarkChessBoard.DRAW_AFTER_QUIET_TURNS - 1);
        assertTrue(board.placeStone(0, 0, 0, 1, 1));
        assertTrue(board.isOver());
        assertNull(board.getWinner());
        assertEquals(2, board.getDrawingSides().size());
    }

    @Test
    void resetClearsMoveHistoryAndRehidesEverything() {
        board.placeStone(0, 0, 1);
        board.reset();
        assertEquals(0, board.getMoveCount());
        assertEquals(0, board.getMoveSeq());
        assertFalse(board.isOver());
        assertEquals(32, board.legalFlips(1).size());
        assertEquals(DarkChessBoard.HIDDEN, board.getVisibleBoardSnapshot()[0][0]);
    }
}
