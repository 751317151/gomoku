package com.gomoku.game;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class UltimateTicTacToeBoardTest {

    @Test
    void openingAndMetadata() {
        UltimateTicTacToeBoard board = new UltimateTicTacToeBoard();
        assertEquals(GameType.ULTIMATE_TTT, board.type());
        assertEquals(9, board.getRows());
        assertEquals(9, board.getCols());
        assertEquals(1, board.getCurrentTurn());
        assertEquals(45, board.getStepTimeSeconds());
        assertEquals(600, board.getTotalTimeSeconds());
        assertEquals(81, board.legalMoves().size());
        assertEquals(-1, view(board).get("nextBoard"));
        assertNull(board.getWinLine());
        assertNull(board.getScoreSummary());
    }

    @Test
    void relativeCellRoutesOpponentAndRejectedMovesLeaveStateUnchanged() {
        UltimateTicTacToeBoard board = new UltimateTicTacToeBoard();
        assertTrue(board.applyMove(MoveCommand.at(4, 4), 1));
        assertEquals(4, view(board).get("nextBoard"));
        assertEquals(8, board.legalMoves().size());
        assertFalse(board.placeStone(0, 0, 2));
        assertFalse(board.placeStone(3, 3, 1));
        assertFalse(board.placeStone(4, 4, 2));
        assertFalse(board.placeStone(-1, 3, 2));
        assertFalse(board.placeStone(3, 9, 2));
        assertFalse(board.applyMove(MoveCommand.action(0), 2));
        assertFalse(board.applyMove(MoveCommand.from(1, 1, 3, 3), 2));
        assertFalse(board.pass(2));
        assertEquals(1, board.getMoveSeq());
        assertEquals(1, board.getMoveHistory().size());
        assertTrue(board.placeStone(3, 3, 2));
        assertEquals(0, view(board).get("nextBoard"));
        assertTrue(board.placeStone(0, 1, 1));
        assertEquals(1, view(board).get("nextBoard"));
        assertArrayEquals(new int[]{0, 1, 1}, board.getLastMoveEffects().get(0));
    }

    @Test
    void localWinClosesEvenItsEmptyCells() throws Exception {
        UltimateTicTacToeBoard board = new UltimateTicTacToeBoard();
        int[][] cells = cells(board);
        cells[0][0] = 1;
        cells[0][1] = 1;
        set(board, "nextBoard", 0);
        assertTrue(board.placeStone(0, 2, 1));
        assertEquals(1, board.getSubBoardSnapshot()[0][0]);
        assertEquals(2, view(board).get("nextBoard"));
        assertFalse(board.isOver());
        assertFalse(board.isLegal(1, 0));
        assertArrayEquals(new int[]{1, 0}, (int[]) view(board).get("wonBoards"));
    }

    @Test
    void targetingWonOrDrawnBoardGivesFreeChoice() throws Exception {
        for (int closed : new int[]{1, 2, 3}) {
            UltimateTicTacToeBoard board = new UltimateTicTacToeBoard();
            close(board, 0, closed);
            set(board, "nextBoard", 4);
            assertTrue(board.placeStone(3, 3, 1));
            assertEquals(-1, view(board).get("nextBoard"));
            assertEquals(71, board.legalMoves().size());
            assertTrue(board.isLegal(8, 8));
            assertFalse(board.isLegal(1, 1));
        }
    }

    @Test
    void closingTheDestinationThisMoveAlsoGivesFreeChoice() throws Exception {
        UltimateTicTacToeBoard board = new UltimateTicTacToeBoard();
        cells(board)[0][1] = 1;
        cells(board)[0][2] = 1;
        set(board, "nextBoard", 0);
        assertTrue(board.placeStone(0, 0, 1));
        assertEquals(-1, view(board).get("nextBoard"));
        assertEquals(72, board.legalMoves().size());
    }

    @Test
    void fullLocalBoardIsDrawnAndNotOwned() throws Exception {
        UltimateTicTacToeBoard board = new UltimateTicTacToeBoard();
        int[][] pattern = {{1, 2, 1}, {1, 2, 2}, {2, 1, 0}};
        for (int row = 0; row < 3; row++) System.arraycopy(pattern[row], 0, cells(board)[row], 0, 3);
        set(board, "nextBoard", 0);
        assertTrue(board.placeStone(2, 2, 1));
        assertEquals(3, board.getSubBoardSnapshot()[0][0]);
        assertEquals(8, view(board).get("nextBoard"));
        assertArrayEquals(new int[]{0, 0}, (int[]) view(board).get("wonBoards"));
        assertFalse(board.isOver());
    }

    @Test
    void allEightMacroLinesWinForEitherSideAndFreezeFurtherPlay() throws Exception {
        for (int side = 1; side <= 2; side++) {
            for (int[] line : UltimateTicTacToeBoard.LINES) {
                UltimateTicTacToeBoard board = new UltimateTicTacToeBoard();
                close(board, line[0], side);
                close(board, line[1], side);
                int last = line[2];
                int row = last / 3 * 3;
                int col = last % 3 * 3;
                cells(board)[row][col] = side;
                cells(board)[row][col + 1] = side;
                set(board, "currentTurn", side);
                set(board, "nextBoard", last);
                assertTrue(board.placeStone(row, col + 2, side));
                assertTrue(board.isOver());
                assertEquals(side, board.getWinner());
                assertEquals(3, board.getWinLine().length);
                assertTrue(board.getScoreSummary().contains("三个连线小盘"));
                assertEquals(-1, view(board).get("nextBoard"));
                assertTrue(board.legalMoves().isEmpty());
                assertFalse(board.placeStone(8, 8, 3 - side));
                assertEquals(1, board.getMoveCount());
            }
        }
    }

    @Test
    void allClosedWithoutMacroLineIsDrawEvenWithUnequalCapturedCounts() throws Exception {
        UltimateTicTacToeBoard board = new UltimateTicTacToeBoard();
        int[] states = {1, 2, 1, 1, 2, 3, 2, 1};
        for (int index = 0; index < states.length; index++) close(board, index, states[index]);
        int[][] pattern = {{1, 2, 1}, {1, 2, 2}, {2, 1, 0}};
        for (int row = 0; row < 3; row++) System.arraycopy(pattern[row], 0, cells(board)[row + 6], 6, 3);
        set(board, "nextBoard", 8);
        assertTrue(board.placeStone(8, 8, 1));
        assertTrue(board.isOver());
        assertNull(board.getWinner());
        assertNull(board.getWinLine());
        assertTrue(board.getScoreSummary().contains("平局"));
    }

    @Test
    void snapshotsAndHistoryCannotMutateEngineAndResetClearsEverything() throws Exception {
        UltimateTicTacToeBoard board = new UltimateTicTacToeBoard();
        board.placeStone(4, 4, 1);
        board.getBoardSnapshot()[4][4] = 2;
        board.getMoveHistory().get(0)[0] = 8;
        board.getLastMoveEffects().get(0)[2] = 2;
        ((int[][]) view(board).get("subBoards"))[1][1] = 3;
        assertEquals(1, board.getBoardSnapshot()[4][4]);
        assertArrayEquals(new int[]{4, 4, 1}, board.getMoveHistory().get(0));
        assertEquals(0, board.getSubBoardSnapshot()[1][1]);
        assertEquals(view(board).keySet(), ((Map<?, ?>) board.getViewState(0)).keySet());
        assertTrue(board.isOwnPieceAt(4, 4, 1));
        assertFalse(board.isOwnPieceAt(0, 0, 0));
        close(board, 0, 2);
        board.reset();
        assertEquals(81, board.legalMoves().size());
        assertEquals(0, board.getMoveSeq());
        assertTrue(board.getMoveHistory().isEmpty());
        assertTrue(board.getLastMoveEffects().isEmpty());
        assertEquals(-1, view(board).get("nextBoard"));
        assertFalse(board.isOver());
        assertNull(board.getWinner());
    }

    @Test
    void hundredSeededGamesTerminateWithLegalHistoryAndCanBeReplayed() {
        Random random = new Random(20261125L);
        for (int sample = 0; sample < 100; sample++) {
            UltimateTicTacToeBoard board = new UltimateTicTacToeBoard();
            while (!board.isOver()) {
                List<int[]> moves = board.legalMoves();
                assertFalse(moves.isEmpty());
                int[] move = moves.get(random.nextInt(moves.size()));
                assertTrue(board.placeStone(move[0], move[1], board.getCurrentTurn()));
                assertTrue(board.getMoveCount() <= 81);
            }
            UltimateTicTacToeBoard replay = new UltimateTicTacToeBoard();
            for (int[] move : board.getMoveHistory()) assertTrue(replay.placeStone(move[0], move[1], move[2]));
            assertTrue(Arrays.deepEquals(board.getBoardSnapshot(), replay.getBoardSnapshot()));
            assertTrue(Arrays.deepEquals(board.getSubBoardSnapshot(), replay.getSubBoardSnapshot()));
            assertEquals(board.getWinner(), replay.getWinner());
            assertTrue(replay.isOver());
            assertEquals(board.getMoveCount(), board.getMoveHistory().size());
        }
    }

    @Test
    void mediumAndHardAiTakeImmediateMacroWinWithoutMutatingLiveState() throws Exception {
        for (int difficulty : new int[]{4, 6}) {
            UltimateTicTacToeBoard board = new UltimateTicTacToeBoard();
            close(board, 0, 1);
            close(board, 1, 1);
            cells(board)[0][6] = 1;
            cells(board)[0][7] = 1;
            set(board, "nextBoard", 2);
            int[][] before = board.getBoardSnapshot();
            int[] move = UltimateTicTacToeAI.INSTANCE.getBestMove(board, 1, difficulty);
            assertArrayEquals(new int[]{0, 8}, move);
            assertTrue(Arrays.deepEquals(before, board.getBoardSnapshot()));
            assertEquals(0, board.getMoveSeq());
            assertFalse(board.isOver());
        }
    }

    @Test
    void aiAvoidsSendingOpponentToAnImmediateMacroWin() throws Exception {
        UltimateTicTacToeBoard board = new UltimateTicTacToeBoard();
        close(board, 0, 2);
        close(board, 1, 2);
        cells(board)[0][6] = 2;
        cells(board)[0][7] = 2;
        set(board, "nextBoard", 2);
        int[] move = UltimateTicTacToeAI.INSTANCE.getBestMove(board, 1, 4);
        assertNotNull(move);
        assertTrue(board.placeStone(move[0], move[1], 1));
        for (int[] reply : board.legalMoves()) {
            UltimateTicTacToeBoard response = board.copyForSearch();
            assertTrue(response.placeStone(reply[0], reply[1], 2));
            assertNotEquals(2, response.getWinner());
        }
    }

    @Test
    void allAiDifficultiesStayLegalAcrossFreeAndForcedTurns() {
        UltimateTicTacToeBoard board = new UltimateTicTacToeBoard();
        Random random = new Random(42);
        for (int index = 0; index < 45 && !board.isOver(); index++) {
            if (index % 5 == 0) {
                for (int difficulty : new int[]{2, 4, 6}) {
                    int[] move = UltimateTicTacToeAI.INSTANCE.getBestMove(board, board.getCurrentTurn(), difficulty);
                    assertNotNull(move);
                    assertTrue(board.isLegal(move[0], move[1]));
                }
                assertNull(UltimateTicTacToeAI.INSTANCE.getBestMove(board, 3 - board.getCurrentTurn(), 6));
            }
            List<int[]> legal = board.legalMoves();
            int[] move = legal.get(random.nextInt(legal.size()));
            assertTrue(board.placeStone(move[0], move[1], board.getCurrentTurn()));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> view(UltimateTicTacToeBoard board) {
        return (Map<String, Object>) board.getViewState(1);
    }

    private static int[][] cells(UltimateTicTacToeBoard board) throws Exception {
        return (int[][]) field("board").get(board);
    }

    private static void close(UltimateTicTacToeBoard board, int region, int side) throws Exception {
        int subRow = region / 3, subCol = region % 3;
        ((int[][]) field("subBoards").get(board))[subRow][subCol] = side;
        int[][] pattern = side == 3 ? new int[][]{{1, 2, 1}, {1, 2, 2}, {2, 1, 1}}
                : new int[][]{{side, side, side}, {0, 0, 0}, {0, 0, 0}};
        for (int row = 0; row < 3; row++) System.arraycopy(pattern[row], 0, cells(board)[subRow * 3 + row], subCol * 3, 3);
    }

    private static void set(UltimateTicTacToeBoard board, String name, int value) throws Exception {
        field(name).setInt(board, value);
    }

    private static Field field(String name) throws Exception {
        Field field = UltimateTicTacToeBoard.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
