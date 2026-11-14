package com.gomoku.game;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 中国象棋规则测试：走子约束 / 将军 / 绝杀 / 照面
 */
class XiangqiBoardTest {

    private XiangqiBoard board;

    // 编码：side*10+type；红：帅11 仕12 相13 马14 车15 炮16 兵17；黑：将21 士22 象23 马24 车25 炮26 卒27
    private static final int BLK_K = 21, BLK_R = 25, BLK_H = 24, BLK_P = 27;

    @BeforeEach
    void setUp() {
        board = new XiangqiBoard();
    }

    private boolean move(int fr, int fc, int tr, int tc, int side) {
        return board.placeStone(fr, fc, tr, tc, side);
    }

    @Test
    void initialPositionAndTurn() {
        assertEquals(GameType.XIANGQI, board.type());
        assertEquals(10, board.getRows());
        assertEquals(9, board.getCols());
        assertEquals(1, board.getCurrentTurn(), "红先行");
        assertEquals(11, board.getBoardSnapshot()[9][4], "红帅在 (9,4)");
        assertEquals(21, board.getBoardSnapshot()[0][4], "黑将在 (0,4)");
        assertEquals(16, board.getBoardSnapshot()[7][1], "红炮在 (7,1)");
        assertFalse(board.placeStone(6, 4, 5, 4, 2), "轮次校验：黑先行应非法");
    }

    @Test
    void pawnForwardAndCrossRiver() {
        // 红兵 (6,4) 前进；未过河（r>=5）不能横走；过河（r<=4）后可横走，不能后退
        assertTrue(board.isLegalMove(6, 4, 5, 4, 1), "兵可前进");
        assertFalse(board.isLegalMove(6, 4, 6, 3, 1), "未过河兵不能横走");
        assertTrue(move(6, 4, 5, 4, 1));
        assertTrue(move(2, 1, 2, 0, 2), "黑走闲棋（炮平移）");
        assertFalse(board.isLegalMove(5, 4, 5, 3, 1), "兵在本方半场（r=5）仍未过河");
        assertTrue(move(5, 4, 4, 4, 1));
        assertTrue(move(2, 0, 2, 1, 2), "黑走闲棋");
        assertTrue(board.isLegalMove(4, 4, 4, 3, 1), "过河兵可横走");
        assertFalse(board.isLegalMove(4, 4, 5, 4, 1), "兵不能后退");
    }

    @Test
    void horseLegBlocked() {
        assertTrue(board.isLegalMove(9, 1, 7, 2, 1), "马跳日应合法");
        board.setCell(8, 1, BLK_P); // 塞住马腿
        assertFalse(board.isLegalMove(9, 1, 7, 2, 1), "蹩马腿后不能跳");
    }

    @Test
    void bishopEyeAndRiver() {
        assertTrue(board.isLegalMove(9, 2, 7, 4, 1), "相飞田字应合法");
        board.setCell(8, 3, BLK_P); // 塞象眼
        assertFalse(board.isLegalMove(9, 2, 7, 4, 1), "塞象眼后不能飞");
        board.setCell(8, 3, 0);
        // 相在红界内活动：(7,4)→(5,6) 合法，(5,6)→(3,8) 越河非法
        board.setCell(7, 4, 13);
        assertTrue(board.isLegalMove(7, 4, 5, 6, 1), "相在红界内可飞");
        board.setCell(5, 6, 13);
        board.setCell(7, 4, 0);
        assertFalse(board.isLegalMove(5, 6, 3, 8, 1), "相不能过河");
    }

    @Test
    void cannonNeedsScreenToCapture() {
        // 目标子为炮行进方向第一个子 → 只能当炮架不能直接吃；摆炮架后才能吃
        board.setCell(3, 1, BLK_P);
        assertFalse(board.isLegalMove(7, 1, 3, 1, 1), "无隔子炮不能吃第一个子");
        board.setCell(5, 1, BLK_H); // 炮架
        assertTrue(board.isLegalMove(7, 1, 3, 1, 1), "隔一子炮可以吃");
        assertTrue(board.isLegalMove(7, 1, 7, 0, 1), "炮可平移到空点");
        assertFalse(board.isLegalMove(7, 1, 5, 1, 1), "炮平移不能落在炮架上");
    }

    @Test
    void flyingGeneralForbidden() {
        // 清空同列全部遮挡 → 双将照面，帅动到同列任何点都非法
        board.setCell(3, 4, 0);
        board.setCell(6, 4, 0);
        assertFalse(board.isLegalMove(9, 4, 8, 4, 1), "将帅照面应非法");
    }

    @Test
    void mustResolveCheck() {
        // 黑车 (8,4) 直线将军红帅 (9,4)
        board.setCell(8, 4, BLK_R);
        board.setTurn(1);
        // 走不解将的闲棋 → 非法
        assertFalse(board.isLegalMove(9, 3, 8, 3, 1), "被将军时不能走不解将的棋");
        // 吃掉将军的车（(8,4) 无保护）→ 合法
        assertTrue(board.isLegalMove(9, 4, 8, 4, 1), "吃车解将应合法");
    }

    @Test
    void checkmateNoLegalMoves() {
        // 双车绝杀：红车 (1,4) 将军，红车 (1,3)/(1,5) 封两侧，黑仅剩将
        XiangqiBoard b = new XiangqiBoard();
        b.setCell(0, 3, 0); b.setCell(0, 5, 0);       // 黑士
        b.setCell(0, 2, 0); b.setCell(0, 6, 0);       // 黑象
        b.setCell(2, 1, 0); b.setCell(2, 7, 0);       // 黑炮
        b.setCell(2, 2, 0); b.setCell(2, 6, 0);       // 黑象
        b.setCell(3, 0, 0); b.setCell(3, 2, 0); b.setCell(3, 4, 0);
        b.setCell(3, 6, 0); b.setCell(3, 8, 0);       // 黑卒
        b.setCell(0, 1, 0); b.setCell(0, 7, 0);       // 黑马
        b.setCell(1, 4, 15);                           // 红车将军
        b.setCell(1, 3, 15);                           // 封 (1,3)
        b.setCell(1, 5, 15);                           // 封 (1,5)
        b.setTurn(2);

        boolean hasMove = false;
        outer:
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                if (!b.isOwnPieceAt(r, c, 2)) continue;
                for (int tr = 0; tr < 10; tr++) {
                    for (int tc = 0; tc < 9; tc++) {
                        if (b.isLegalMove(r, c, tr, tc, 2)) { hasMove = true; break outer; }
                    }
                }
            }
        }
        assertFalse(hasMove, "黑应被绝杀无合法走法");
    }

    @Test
    void normalMovesRecordHistoryAndEffects() {
        assertTrue(move(6, 4, 5, 4, 1));
        assertTrue(move(2, 1, 2, 0, 2));
        assertFalse(board.isOver());
        assertEquals(2, board.getMoveCount());
        int[] last = board.getMoveHistory().get(board.getMoveHistory().size() - 1);
        assertEquals(5, last.length, "象棋历史为 {toR,toC,side,fromR,fromC}");
        assertEquals(2, last[3], "fromRow");
        assertEquals(1, last[4], "fromCol");
        List<int[]> effects = board.getLastMoveEffects();
        assertEquals(2, effects.size());
        assertArrayEquals(new int[]{2, 1, 0}, effects.get(0), "清空起点");
        assertArrayEquals(new int[]{2, 0, 26}, effects.get(1), "落子终点");
    }

    @Test
    void capturePiece() {
        // 炮 (7,1) 架 (5,1) 吃卒 (3,1)
        board.setCell(5, 1, BLK_H);
        board.setCell(3, 1, BLK_P);
        assertTrue(move(7, 1, 3, 1, 1));
        assertEquals(16, board.getBoardSnapshot()[3][1], "红炮吃掉黑卒");
        assertEquals(0, board.getBoardSnapshot()[7][1], "起点清空");
        assertEquals(BLK_H, board.getBoardSnapshot()[5][1], "炮架黑马仍在原位");
    }

    @Test
    void engineMetadata() {
        assertEquals(60, board.getStepTimeSeconds());
        assertEquals(600, board.getTotalTimeSeconds());
        assertFalse(board.isOwnPieceAt(5, 5, 1), "空点不是己方棋子");
        assertTrue(board.isOwnPieceAt(9, 4, 1), "红帅是红方棋子");
    }

    @Test
    void aiReturnsLegalMove() {
        int[] mv = XiangqiAI.INSTANCE.getBestMove(board, 1, 2);
        assertNotNull(mv);
        assertEquals(4, mv.length, "象棋 AI 返回 {fromR,fromC,toR,toC}");
        assertTrue(board.isLegalMove(mv[0], mv[1], mv[2], mv[3], 1), "AI 走法必须合法");
    }
}
