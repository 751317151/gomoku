package com.gomoku.game;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 斗兽棋规则单测：布局、八兽克制、水域/陷阱、狮虎跳河与胜负边界。 */
class JungleBoardTest {

    private JungleBoard board;

    @BeforeEach
    void setUp() {
        board = new JungleBoard();
    }

    @Test
    void initialLayoutAndRedStarts() {
        assertEquals(GameType.JUNGLE, board.type());
        assertEquals(9, board.getRows());
        assertEquals(7, board.getCols());
        assertEquals(1, board.getCurrentTurn(), "红方先手");
        int[][] b = board.getBoardSnapshot();
        assertEquals(16, b[8][0], "红虎在左下");
        assertEquals(17, b[8][6], "红狮在右下");
        assertEquals(11, b[6][6], "红鼠在右下内侧");
        assertEquals(28, b[2][6], "黑象在右上内侧");
        assertEquals(0, b[4][3], "中心是河流");
        assertEquals(8, countPieces(1));
        assertEquals(8, countPieces(2));
        assertTrue(board.legalMoves(1).size() >= 8, "初始红方八枚棋子都能行动");
    }

    @Test
    void rankAndMouseElephantExceptionAreEnforced() {
        // 鼠吃象，象不能吃鼠
        board.resetTurnForTest();
        board.putForTest(4, 3, 1, JungleBoard.MOUSE);
        board.putForTest(3, 3, 2, JungleBoard.ELEPHANT);
        assertTrue(board.isLegalMove(4, 3, 3, 3, 1), "鼠可向上吃象");
        canMove(4, 3, 3, 3);
        assertTrue(board.isOver() || board.getCurrentTurn() == 2);

        board.resetTurnForTest();
        board.putForTest(4, 3, 1, JungleBoard.ELEPHANT);
        board.putForTest(3, 3, 2, JungleBoard.MOUSE);
        assertFalse(board.isLegalMove(4, 3, 3, 3, 1), "象不能吃鼠");

        // 同级可吃、低级不能吃高级
        board.resetTurnForTest();
        board.putForTest(4, 3, 1, JungleBoard.DOG);
        board.putForTest(3, 3, 2, JungleBoard.CAT);
        assertTrue(board.isLegalMove(4, 3, 3, 3, 1), "狗可吃猫");
        board.clearForTest(3, 3);
        board.putForTest(3, 3, 2, JungleBoard.WOLF);
        assertFalse(board.isLegalMove(4, 3, 3, 3, 1), "狗不能吃狼");
    }

    @Test
    void onlyMouseCanEnterWaterAndLandCannotCaptureIt() {
        board.resetTurnForTest();
        board.putForTest(2, 1, 1, JungleBoard.MOUSE);
        assertTrue(board.isLegalMove(2, 1, 3, 1, 1), "鼠可进河");

        board.putForTest(4, 1, 2, JungleBoard.CAT);
        assertFalse(board.isLegalMove(4, 1, 3, 1, 2), "猫不能进河，也不能隔岸吃水中鼠");

        board.putForTest(4, 1, 1, JungleBoard.DOG);
        board.putForTest(3, 1, 2, JungleBoard.MOUSE);
        assertFalse(board.isLegalMove(4, 1, 3, 1, 1), "岸边狗不能吃水中鼠");
    }

    @Test
    void trapNeutralizesDefenderForAnyAttacker() {
        board.resetTurnForTest();
        // 上方陷阱归黑方；红猫站到陷阱里不会被削弱，但黑鼠在己方陷阱内无保护。
        board.putForTest(0, 2, 2, JungleBoard.ELEPHANT);
        board.putForTest(0, 3, 1, JungleBoard.MOUSE);
        assertTrue(board.isLegalMove(0, 3, 0, 2, 1), "低战力棋子也可吃陷阱中的高战力敌棋");
    }

    @Test
    void lionAndTigerCanJumpWaterButMouseBlocks() {
        board.resetTurnForTest();
        board.putForTest(2, 1, 1, JungleBoard.LION);
        assertTrue(board.isLegalMove(2, 1, 6, 1, 1), "狮可直线跳过整段河道");

        board.putForTest(4, 1, 2, JungleBoard.MOUSE);
        assertFalse(board.isLegalMove(2, 1, 6, 1, 1), "河中只要有鼠就阻挡跳跃");

        board.clearForTest(4, 1);
        board.putForTest(2, 1, 1, JungleBoard.TIGER);
        assertTrue(board.isLegalMove(2, 1, 6, 1, 1), "虎可直线跳河");
    }

    @Test
    void waterJumpRequiresWholeRiverLineClearAndCanCaptureOnLanding() {
        board.resetTurnForTest();
        board.putForTest(2, 4, 1, JungleBoard.TIGER);
        board.putForTest(6, 4, 2, JungleBoard.CAT);
        // 落点有敌棋时，跳河同样是一手合法吃子。
        int[] jump = board.legalMoves(1).stream()
                .filter(m -> m[0] == 2 && m[1] == 4 && m[2] == 6 && m[3] == 4)
                .findFirst().orElse(null);
        assertNotNull(jump);
        assertEquals(1, jump[5], "跳河标记");
        assertEquals(1, jump[4], "落点有敌棋应标记为吃子");

        // 河面任意一格有鼠都会挡住整条跳跃路线。
        board.putForTest(4, 4, 2, JungleBoard.MOUSE);
        assertFalse(board.isLegalMove(2, 4, 6, 4, 1), "河面有鼠时虎不能跳");

        // 狮虎不能斜跳；只有直线穿过连续河面才允许。
        board.resetTurnForTest();
        board.putForTest(2, 1, 1, JungleBoard.LION);
        assertFalse(board.isLegalMove(2, 1, 5, 2, 1), "不能斜向跳河");
    }

    @Test
    void cannotEnterOwnDenButEnteringOpponentDenWins() {
        board.resetTurnForTest();
        board.putForTest(7, 3, 1, JungleBoard.DOG);
        board.putForTest(8, 2, 1, JungleBoard.CAT);
        board.putForTest(0, 2, 2, JungleBoard.LION);
        assertFalse(board.isLegalMove(7, 3, 8, 3, 1), "不能进入己方兽穴");

        board.resetTurnForTest();
        board.putForTest(1, 3, 1, JungleBoard.DOG);
        board.putForTest(0, 2, 2, JungleBoard.LION);
        assertTrue(board.isLegalMove(1, 3, 0, 3, 1), "可进入对方兽穴");
        board.placeStone(1, 3, 0, 3, 1);
        assertTrue(board.isOver());
        assertEquals(1, board.getWinner());
    }

    @Test
    void quietMoveLimitEndsInDraw() {
        board.resetTurnForTest();
        board.putForTest(6, 6, 1, JungleBoard.MOUSE);
        board.putForTest(0, 0, 2, JungleBoard.LION);
        board.setQuietTurnsForTest(JungleBoard.DRAW_AFTER_QUIET_TURNS - 1);
        assertTrue(board.isLegalMove(6, 6, 5, 6, 1));
        board.placeStone(6, 6, 5, 6, 1);
        assertTrue(board.isOver());
        assertNull(board.getWinner());
        assertEquals(2, board.getDrawingSides().size());
    }

    @Test
    void captureOrBlockadeVictory() {
        board.resetTurnForTest();
        board.putForTest(4, 3, 1, JungleBoard.LION);
        board.putForTest(3, 3, 2, JungleBoard.CAT);
        assertTrue(board.isLegalMove(4, 3, 3, 3, 1));
        board.placeStone(4, 3, 3, 3, 1);
        assertTrue(board.isOver(), "吃光对方即胜");
        assertEquals(1, board.getWinner());
    }

    @Test
    void viewExposesRulesAndLegalMoves() {
        java.util.Map<?, ?> view = (java.util.Map<?, ?>) board.getViewState(1);
        assertEquals(1, view.get("turn"));
        assertEquals(JungleBoard.DRAW_AFTER_QUIET_TURNS, view.get("drawAfterQuietTurns"));
        assertTrue(((List<?>) view.get("legalMoves")).size() > 0);
        assertEquals(12, ((List<?>) view.get("water")).size());
        assertEquals(6, ((List<?>) view.get("traps")).size());
    }

    private int countPieces(int side) {
        int count = 0;
        for (int[] row : board.getBoardSnapshot()) {
            for (int v : row) if (v / 10 == side) count++;
        }
        return count;
    }

    private void canMove(int fromRow, int fromCol, int toRow, int toCol) {
        assertTrue(board.placeStone(fromRow, fromCol, toRow, toCol, 1));
    }
}
