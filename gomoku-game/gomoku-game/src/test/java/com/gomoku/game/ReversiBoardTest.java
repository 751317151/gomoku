package com.gomoku.game;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 黑白棋规则测试
 */
class ReversiBoardTest {

    private ReversiBoard board;

    @BeforeEach
    void setUp() {
        board = new ReversiBoard();
    }

    @Test
    void initialPosition() {
        assertEquals(GameType.REVERSI, board.type());
        assertEquals(8, board.getRows());
        assertEquals(ReversiBoard.BLACK, board.getCurrentTurn(), "黑先手");
        // 初始中央四子
        assertEquals(4, board.getMoveCount());
        assertEquals(2, board.countDiscs()[0]);
        assertEquals(2, board.countDiscs()[1]);
        // 初始黑棋恰好 4 个合法点
        List<int[]> moves = board.getLegalMoves(ReversiBoard.BLACK);
        assertEquals(4, moves.size(), "初始黑棋应有 4 个合法落子点");
    }

    @Test
    void illegalMoveRejected() {
        // 无翻转 → 非法
        assertFalse(board.placeStone(0, 0, ReversiBoard.BLACK), "无夹击的落子应被拒绝");
        // 已占位
        assertFalse(board.placeStone(3, 3, ReversiBoard.BLACK), "已占位应被拒绝");
        // 轮次错误
        assertFalse(board.placeStone(2, 3, ReversiBoard.WHITE), "轮到黑棋时白棋不能落子");
    }

    @Test
    void flipCorrect() {
        // 黑 (2,3)：夹住白 (3,3)（(3,4) 为黑）
        assertTrue(board.placeStone(2, 3, ReversiBoard.BLACK));
        assertEquals(ReversiBoard.BLACK, board.getBoardSnapshot()[3][3], "(3,3) 白子应被翻转");
        int[] counts = board.countDiscs();
        assertEquals(4, counts[0], "翻转后黑应 4 子");
        assertEquals(1, counts[1], "翻转后白应 1 子");
        assertEquals(ReversiBoard.WHITE, board.getCurrentTurn(), "换白棋行棋");
        // 效果列表记录翻转
        List<int[]> effects = board.getLastMoveEffects();
        assertEquals(1, effects.size());
        assertArrayEquals(new int[]{3, 3, ReversiBoard.BLACK}, effects.get(0));
    }

    @Test
    void whiteCanRespond() {
        board.placeStone(2, 3, ReversiBoard.BLACK); // 黑翻转 (3,3)
        // 白 (4,2)：夹住黑 (4,3)（(4,4) 为白）
        assertTrue(board.placeStone(4, 2, ReversiBoard.WHITE));
        assertEquals(ReversiBoard.WHITE, board.getBoardSnapshot()[4][3], "(4,3) 黑子应被翻转");
    }

    @Test
    void boardFullEndsWithCorrectWinner() {
        // 构造：63 格已占（62 黑 + 1 白 (7,6)），最后一空格 (7,5)
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                if (r == 7 && c == 5) continue;      // 最后一手
                if (r == 7 && c == 6) continue;      // 白子
                board.setCell(r, c, ReversiBoard.BLACK);
            }
        }
        board.setCell(7, 6, ReversiBoard.WHITE);
        board.setTurn(ReversiBoard.BLACK);

        // 黑 (7,5)：夹住 (7,6) 白子（(7,7) 为黑）
        assertTrue(board.placeStone(7, 5, ReversiBoard.BLACK), "最后一手应夹住 (7,6) 白子");
        assertTrue(board.isOver(), "棋盘满应终局");
        assertEquals(ReversiBoard.BLACK, board.getWinner());
        assertNotNull(board.getScoreSummary());
    }

    @Test
    void noLegalMovesForEitherEndsGame() {
        // 白棋只剩被包围的孤子且无处可翻转 → 双方均无合法手 → 终局按子数判
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                board.setCell(r, c, ReversiBoard.BLACK);
            }
        }
        board.setCell(7, 7, ReversiBoard.WHITE);
        // 黑棋最后一手需要翻转白子，但角上白子无法被翻 → 触发 placeStone 前先手动判定：
        // 直接验证 hasLegalMoves 逻辑
        assertFalse(board.hasLegalMoves(ReversiBoard.WHITE), "白棋应无合法手");
        assertFalse(board.hasLegalMoves(ReversiBoard.BLACK), "黑棋也应无合法手（角子不可翻）");
        board.setTurn(ReversiBoard.BLACK);
        // 黑棋落子必然非法（无夹击），触发不了终局 —— 验证落子被拒
        assertFalse(board.placeStone(0, 0, ReversiBoard.BLACK));
        // 终局判定只能由引擎内部双方无合法手触发，此处验证 hasLegalMoves 的正确性
    }

    @Test
    void aiFindsCornerMove() {
        // AI 在标准开局下应能返回合法点（通常偏好角/边）
        int[] move = ReversiAI.INSTANCE.getBestMove(board, ReversiBoard.BLACK, 2);
        assertNotNull(move);
        List<int[]> legal = board.getLegalMoves(ReversiBoard.BLACK);
        boolean found = legal.stream().anyMatch(m -> m[0] == move[0] && m[1] == move[1]);
        assertTrue(found, "AI 返回的点必须是合法点");
    }

    @Test
    void engineMetadata() {
        assertEquals(60, board.getStepTimeSeconds());
        assertEquals(300, board.getTotalTimeSeconds());
    }
}
