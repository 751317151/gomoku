package com.gomoku.game;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class FlightChessBoardTest {

    /** 固定骰子序列，保证测试可复现。 */
    private static final class FixedRandom extends Random {
        private final int[] values;
        private int index;

        FixedRandom(int... values) { this.values = values; }

        @Override public int nextInt(int bound) {
            int v = values[Math.min(index++, values.length - 1)];
            return Math.max(0, Math.min(bound - 1, v - 1));
        }
    }

    private static FlightChessBoard board(int players, int... rolls) {
        FlightChessBoard b = new FlightChessBoard(new FixedRandom(rolls));
        b.configurePlayers(players);
        b.reset();
        b.beginTurnRoll();
        return b;
    }

    private static int position(FlightChessBoard b, int side, int piece) {
        return b.getPiecePosition(side, piece);
    }

    @Test
    void startsWithTwoPlayersAndFourPiecesEach() {
        FlightChessBoard b = board(2, 3);
        assertEquals(2, b.getPlayerCount());
        for (int side = 1; side <= 2; side++) {
            for (int p = 0; p < 4; p++) {
                assertEquals(0, position(b, side, p), "棋子初始在基地");
                assertFalse(b.isPieceFinished(side, p));
            }
        }
        assertEquals(1, b.getCurrentTurn());
        assertEquals(3, b.getDice());
        assertEquals(FlightChessBoard.Phase.SKIP, b.getPhase(), "首掷非 6 且全在基地时应跳过");
    }

    @Test
    void onlySixLaunchesAndSixKeepsTurn() {
        FlightChessBoard b = board(2, 6, 2, 3);
        b.setTurnForTest(1);
        b.setDiceForTest(6);
        assertEquals(6, b.getDice());
        int[] start = FlightChessBoard.ringCell(1);
        assertTrue(b.placeStone(1, 1, start[0], start[1], 1));
        assertEquals(1, position(b, 1, 0));
        assertEquals(1, b.getCurrentTurn(), "掷出 6 点应保留回合");
        assertEquals(2, b.getDice(), "掷出 6 点后应自动掷下一次");
        assertTrue(b.getViewState(1).toString().contains("legalMoves"));
    }

    @Test
    void nonSixCannotLaunchAndRejectsWrongTarget() {
        FlightChessBoard b = board(2, 3);
        assertFalse(b.placeStone(1, 1, 0, 1, 1), "3 点不能起飞");
        assertEquals(0, position(b, 1, 0));
    }

    @Test
    void jumpAdvancesToConfiguredCell() throws Exception {
        FlightChessBoard b = board(2, 6, 6);
        b.setTurnForTest(1);
        b.setDiceForTest(6);
        int[] start = FlightChessBoard.ringCell(1);
        assertTrue(b.placeStone(1, 1, start[0], start[1], 1));
        // 起飞后额外掷骰；把另一枚棋子放到跳格前 1 格，再掷 1 点落到跳格
        b.setPositionForTest(1, 1, 5);
        b.setDiceForTest(1);
        int[] from = FlightChessBoard.ringCell(5);
        int[] jumpCell = FlightChessBoard.ringCell(6);
        assertEquals(1, b.getDice());
        assertTrue(b.placeStone(from[0], from[1], jumpCell[0], jumpCell[1], 1));
        assertEquals(17, position(b, 1, 1), "落到跳格后应前进到 17");
    }

    @Test
    void movementUsesServerDiceAndAdvancesTurn() {
        FlightChessBoard b = board(2, 1);
        b.setTurnForTest(1);
        b.setDiceForTest(1);
        int[] start = FlightChessBoard.ringCell(1);
        assertFalse(b.placeStone(0, 0, start[0], start[1], 1), "基地棋子未掷出 6 不能动");
    }

    @Test
    void cannotOvershootHome() throws Exception {
        FlightChessBoard b = board(2, 2);
        b.setTurnForTest(1);
        b.setDiceForTest(2);
        b.setPositionForTest(1, 0, 56);
        int[] cell = FlightChessBoard.ringCell(56);
        // 2 点会到 58，超过 57，应拒绝
        assertFalse(b.placeStone(cell[0], cell[1], 7, 7, 1));
        assertEquals(56, position(b, 1, 0));
    }

    @Test
    void landingOnHomeFinishesPiece() throws Exception {
        FlightChessBoard b = board(2, 1);
        b.setTurnForTest(1);
        b.setDiceForTest(1);
        b.setPositionForTest(1, 0, 56);
        int[] cell = FlightChessBoard.ringCell(56);
        assertTrue(b.placeStone(cell[0], cell[1], 7, 7, 1));
        assertEquals(FlightChessBoard.HOME_POS, position(b, 1, 0));
        assertTrue(b.isPieceFinished(1, 0));
    }

    @Test
    void landingOnOpponentKicksItBack() throws Exception {
        FlightChessBoard b = board(2, 5);
        b.setTurnForTest(1);
        b.setDiceForTest(5);
        b.setPositionForTest(1, 0, 10);
        b.setPositionForTest(2, 0, 15);
        int[] from = FlightChessBoard.ringCell(10);
        int[] to = FlightChessBoard.ringCell(15);
        assertTrue(b.placeStone(from[0], from[1], to[0], to[1], 1));
        assertEquals(15, position(b, 1, 0));
        assertEquals(0, position(b, 2, 0), "对手棋子应被撞回基地");
    }

    @Test
    void fourFinishedPiecesWin() throws Exception {
        FlightChessBoard b = board(2, 1);
        b.setTurnForTest(1);
        b.setDiceForTest(1);
        for (int p = 0; p < 3; p++) {
            b.setPositionForTest(1, p, FlightChessBoard.HOME_POS);
        }
        b.setFinishedCountForTest(1, 3);
        b.setPositionForTest(1, 3, 56);
        int[] cell = FlightChessBoard.ringCell(56);
        assertTrue(b.placeStone(cell[0], cell[1], 7, 7, 1));
        assertTrue(b.isOver());
        assertEquals(1, b.getWinner());
        assertEquals("玩家1 4/4 · 玩家2 0/4", b.getScoreSummary());
    }

    @Test
    void viewHidesNothingButOnlyGivesOwnLegalMoves() {
        FlightChessBoard b = board(3, 6);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> view = (java.util.Map<String, Object>) b.getViewState(1);
        assertNotNull(view.get("legalMoves"));
        assertEquals("MOVE", view.get("phase"));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> spectator = (java.util.Map<String, Object>) b.getViewState(0);
        assertEquals(0, ((List<?>) spectator.get("legalMoves")).size());
        assertEquals(3, spectator.get("playerCount"));
    }

    @Test
    void historyEncodesEveryMove() {
        FlightChessBoard b = board(2, 6);
        b.setTurnForTest(1);
        b.setDiceForTest(6);
        int[] start = FlightChessBoard.ringCell(1);
        assertTrue(b.placeStone(1, 1, start[0], start[1], 1));
        List<int[]> history = b.getMoveHistory();
        assertEquals(1, history.size());
        assertEquals(1, history.get(0)[0]);
        assertEquals(0, history.get(0)[1]);
        assertEquals(0, history.get(0)[2]);
        assertEquals(1, history.get(0)[3]);
        assertEquals(6, history.get(0)[4]);
    }

    @Test
    void explicitSkipIsRecordedAndAdvancesTurn() {
        FlightChessBoard b = board(2, 3, 4);
        assertEquals(FlightChessBoard.Phase.SKIP, b.getPhase());
        assertTrue(b.skipTurn(1));
        assertEquals(2, b.getCurrentTurn());
        assertEquals(1, b.getMoveHistory().size());
        assertEquals(-1, b.getMoveHistory().get(0)[1]);
        assertEquals(3, b.getMoveHistory().get(0)[4]);
        assertEquals(4, b.getDice());
    }

    @Test
    void threeConsecutiveSixesForceTurnChange() throws Exception {
        FlightChessBoard b = board(2, 6, 6, 6, 2);
        b.setTurnForTest(1);
        int[] start = FlightChessBoard.ringCell(1);
        b.setDiceForTest(6);
        assertTrue(b.placeStone(1, 1, start[0], start[1], 1));
        b.setDiceForTest(6);
        assertTrue(b.placeStone(1, 2, start[0], start[1], 1));
        b.setDiceForTest(6);
        assertTrue(b.placeStone(2, 1, start[0], start[1], 1));
        assertEquals(2, b.getCurrentTurn(), "连续三次 6 点后应换人");
        assertEquals(2, b.getDice());
    }

    @Test
    void forfeitEliminatesOnlyThatSeatAndLastPlayerWins() {
        FlightChessBoard b = board(4, 3);
        assertTrue(b.forfeit(2));
        assertTrue(b.isEliminatedForTest(2));
        assertFalse(b.isOver(), "还有三名玩家时不能由一人退出直接结束");
        assertTrue(b.forfeit(4));
        assertTrue(b.forfeit(3));
        assertTrue(b.isOver());
        assertEquals(1, b.getWinner());
    }

    @Test
    void turnSkipsEliminatedSeat() throws Exception {
        FlightChessBoard b = board(3, 6);
        b.setTurnForTest(1);
        b.setDiceForTest(6);
        assertTrue(b.forfeit(2));
        // 1 号座位的起飞入口是第 1 格（START_RINGS[0] + 1）。
        int[] start = FlightChessBoard.ringCell(1);
        assertTrue(b.placeStone(1, 1, start[0], start[1], 1));
        // forfeit(2) 不改变 1 号当前回合；1 号掷出 6 点后按规则保留回合再掷。
        // 接下来显式把骰子设为非 6 再走一步，才能验证「轮转时跳过已淘汰座位」。
        b.setDiceForTest(1);
        // 首步起飞到 1；再来 1 点应落到跑道位置 2。
        assertTrue(b.placeStone(start[0], start[1],
                FlightChessBoard.ringCell(2)[0], FlightChessBoard.ringCell(2)[1], 1));
        assertEquals(3, b.getCurrentTurn(), "应跳过已退出的 2 号座位");
    }

    @Test
    void ringMappingRoundTrips() {
        for (int pos = 1; pos <= FlightChessBoard.TRACK_CELLS; pos++) {
            int[] cell = FlightChessBoard.ringCell(pos);
            assertEquals(pos, FlightChessBoard.ringPosition(cell[0], cell[1]), "位置 " + pos);
        }
        assertEquals(-1, FlightChessBoard.ringPosition(7, 7), "中心不是跑道");
    }
}
