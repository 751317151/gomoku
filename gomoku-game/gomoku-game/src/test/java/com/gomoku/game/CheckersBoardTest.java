package com.gomoku.game;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CheckersBoardTest {

    private static int man(int side) { return side * 10 + CheckersBoard.MAN; }
    private static int king(int side) { return side * 10 + CheckersBoard.KING; }

    /** 全空盘面 + 红方先手，便于构造残局。 */
    private static CheckersBoard emptyBoard() {
        CheckersBoard board = new CheckersBoard();
        board.resetTurnForTest();
        return board;
    }

    private static void set(CheckersBoard board, int r, int c, int value) {
        try {
            java.lang.reflect.Field f = CheckersBoard.class.getDeclaredField("board");
            f.setAccessible(true);
            int[][] b = (int[][]) f.get(board);
            b[r][c] = value;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static int[][] snapshot(CheckersBoard board) { return board.getBoardSnapshot(); }

    @Test
    void initialBoardHasTwelvePiecesEachOnDarkSquares() {
        CheckersBoard board = new CheckersBoard();
        int[][] b = snapshot(board);
        int red = 0, black = 0;
        for (int r = 0; r < CheckersBoard.ROWS; r++) {
            for (int c = 0; c < CheckersBoard.COLS; c++) {
                if (b[r][c] == 0) continue;
                assertEquals(1, (r + c) % 2, "棋子必须落在深色格");
                if (b[r][c] / 10 == 1) red++; else black++;
            }
        }
        assertEquals(12, red);
        assertEquals(12, black);
        assertEquals(1, board.getCurrentTurn());
    }

    @Test
    void manMovesForwardDiagonallyOnly() {
        CheckersBoard board = emptyBoard();
        set(board, 5, 2, man(1));
        assertTrue(board.placeStone(5, 2, 4, 1, 1));
        assertEquals(man(1), snapshot(board)[4][1]);
        assertEquals(0, snapshot(board)[5][2]);
    }

    @Test
    void captureIsMandatoryWhenAvailable() {
        CheckersBoard board = emptyBoard();
        set(board, 5, 2, man(1));
        set(board, 4, 3, man(2));
        set(board, 3, 1, man(1));
        // 有吃子时，普通前进必须被拒绝
        assertFalse(board.isLegalMove(3, 1, 2, 0, 1));
        assertTrue(board.isLegalMove(5, 2, 3, 4, 1));
        assertTrue(board.placeStone(5, 2, 3, 4, 1));
        assertEquals(0, snapshot(board)[4][3], "被跳过的棋子应被吃掉");
        assertEquals(man(1), snapshot(board)[3][4]);
    }

    @Test
    void chainedCaptureKeepsSameSideUntilFinished() {
        CheckersBoard board = emptyBoard();
        set(board, 5, 2, man(1));
        set(board, 4, 3, man(2));
        set(board, 2, 5, man(2));
        assertTrue(board.placeStone(5, 2, 3, 4, 1));
        assertEquals(1, board.getCurrentTurn(), "连吃期间仍由同一方行动");
        List<int[]> chain = board.getLegalMovesFor(1);
        assertEquals(1, chain.size());
        assertEquals(1, chain.get(0)[4], "连吃只能继续吃子");
        assertTrue(board.placeStone(3, 4, 1, 6, 1));
        assertEquals(2, board.getCurrentTurn(), "连吃结束后才换手");
        assertEquals(0, snapshot(board)[2][5]);
    }

    @Test
    void reachingLastRowPromotesToKing() {
        CheckersBoard board = emptyBoard();
        set(board, 1, 2, man(1));
        set(board, 6, 5, man(2));
        assertTrue(board.placeStone(1, 2, 0, 1, 1));
        assertEquals(king(1), snapshot(board)[0][1]);
        // 升王后轮到黑方，黑兵仍有前进走法
        assertEquals(2, board.getCurrentTurn());
        assertTrue(board.getLegalMovesFor(2).size() > 0);
    }

    @Test
    void sideWithNoPiecesOrMovesLoses() {
        CheckersBoard board = emptyBoard();
        set(board, 5, 2, man(1));
        assertTrue(board.placeStone(5, 2, 4, 1, 1));
        assertTrue(board.isOver());
        assertEquals(1, board.getWinner(), "黑方无子，红方获胜");
        assertNotNull(board.getScoreSummary());
    }

    @Test
    void quietTurnsEndInDrawBeforeUnlimitedKingShuffle() {
        CheckersBoard board = emptyBoard();
        set(board, 5, 2, king(1));
        set(board, 2, 5, king(2));
        board.setQuietTurnsForTest(CheckersBoard.DRAW_AFTER_QUIET_TURNS - 1);
        assertTrue(board.placeStone(5, 2, 4, 1, 1));
        assertTrue(board.isOver());
        assertNull(board.getWinner());
        assertEquals(java.util.Arrays.asList(1, 2), board.getDrawingSides());
        assertTrue(board.getScoreSummary().contains("和棋"));
    }

    @Test
    void replayHistoryEncodesFromAndTo() {
        CheckersBoard board = emptyBoard();
        set(board, 5, 2, man(1));
        assertTrue(board.placeStone(5, 2, 4, 1, 1));
        int[] move = board.getMoveHistory().get(0);
        assertEquals(4, move[0]);
        assertEquals(1, move[1]);
        assertEquals(1, move[2]);
        assertEquals(5, move[3]);
        assertEquals(2, move[4]);
        // 效果包含清空起点与落子终点
        assertEquals(2, board.getLastMoveEffects().size());
        assertEquals(0, board.getLastMoveEffects().get(0)[2]);
    }

    @Test
    void roomStartsAndPersistsMovesForTwoPlayers() {
        GameRoom room = new GameRoom("ROOM-CHECKERS", GameType.CHECKERS, true, 2);
        try {
            assertTrue(room.addPlayer(new com.gomoku.model.Player("chk-1", "红方", new io.netty.channel.embedded.EmbeddedChannel())));
            assertTrue(room.addPlayer(new com.gomoku.model.Player("chk-2", "黑方", new io.netty.channel.embedded.EmbeddedChannel())));
            assertEquals(GameRoom.State.PLAYING, room.getState());
            com.gomoku.model.Player first = room.getPlayers().get(0);
            assertTrue(room.handleCommand(first, MoveCommand.from(5, 0, 4, 1), -1));
            assertEquals(1, room.getEngine().getMoveCount());
            assertNotNull(((BoardGameEngine) room.getEngine()).getBoardSnapshot());
        } finally {
            room.notifyShutdown();
            for (com.gomoku.model.Player p : room.getPlayers()) {
                if (p.getConnection() instanceof io.netty.channel.embedded.EmbeddedChannel) {
                    ((io.netty.channel.embedded.EmbeddedChannel) p.getConnection()).finishAndReleaseAll();
                }
            }
        }
    }

    @Test
    void aiReturnsLegalMoveFromAndTo() {
        CheckersBoard board = new CheckersBoard();
        int[] move = CheckersAI.INSTANCE.getBestMove(board, 1, 6);
        assertNotNull(move);
        assertEquals(4, move.length);
        assertTrue(board.isLegalMove(move[0], move[1], move[2], move[3], 1));
    }
}
