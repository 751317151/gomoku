package com.gomoku.game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class UltimateTicTacToeBoard implements BoardGameEngine {

    static final int[][] LINES = {
            {0, 1, 2}, {3, 4, 5}, {6, 7, 8},
            {0, 3, 6}, {1, 4, 7}, {2, 5, 8}, {0, 4, 8}, {2, 4, 6}
    };

    private final int[][] board = new int[9][9];
    private final int[][] subBoards = new int[3][3];
    private final List<int[]> history = new ArrayList<>();
    private int currentTurn = 1;
    private int nextBoard = -1;
    private int moveCount;
    private boolean over;
    private Integer winner;

    @Override public GameType type() { return GameType.ULTIMATE_TTT; }
    @Override public int getRows() { return 9; }
    @Override public int getCols() { return 9; }
    @Override public int getCurrentTurn() { return currentTurn; }
    @Override public int getMoveCount() { return moveCount; }
    @Override public int getMoveSeq() { return moveCount; }
    @Override public int getStepTimeSeconds() { return 45; }
    @Override public int getTotalTimeSeconds() { return 600; }
    @Override public boolean isOver() { return over; }
    @Override public Integer getWinner() { return winner; }

    @Override
    public void reset() {
        for (int[] row : board) Arrays.fill(row, 0);
        for (int[] row : subBoards) Arrays.fill(row, 0);
        history.clear();
        currentTurn = 1;
        nextBoard = -1;
        moveCount = 0;
        over = false;
        winner = null;
    }

    @Override
    public boolean placeStone(int row, int col, int side) {
        if (side != currentTurn || !isLegal(row, col)) return false;
        board[row][col] = side;
        history.add(new int[]{row, col, side});
        moveCount++;

        int subRow = row / 3;
        int subCol = col / 3;
        int localWinner = lineWinner(board, subRow * 3, subCol * 3);
        if (localWinner != 0) subBoards[subRow][subCol] = localWinner;
        else if (isFull(subRow, subCol)) subBoards[subRow][subCol] = 3;

        int globalWinner = lineWinner(subBoards, 0, 0);
        if (globalWinner != 0) {
            over = true;
            winner = globalWinner;
        } else {
            over = true;
            for (int[] sub : subBoards) {
                for (int value : sub) if (value == 0) over = false;
            }
        }

        int target = (row % 3) * 3 + col % 3;
        nextBoard = over || subBoards[target / 3][target % 3] != 0 ? -1 : target;
        currentTurn = 3 - side;
        return true;
    }

    public boolean isLegal(int row, int col) {
        if (over || row < 0 || row >= 9 || col < 0 || col >= 9) return false;
        int subRow = row / 3;
        int subCol = col / 3;
        return board[row][col] == 0 && subBoards[subRow][subCol] == 0
                && (nextBoard < 0 || nextBoard == subRow * 3 + subCol);
    }

    public List<int[]> legalMoves() {
        List<int[]> moves = new ArrayList<>();
        for (int row = 0; row < 9; row++) {
            for (int col = 0; col < 9; col++) {
                if (isLegal(row, col)) moves.add(new int[]{row, col});
            }
        }
        return moves;
    }

    private boolean isFull(int subRow, int subCol) {
        for (int row = subRow * 3; row < subRow * 3 + 3; row++) {
            for (int col = subCol * 3; col < subCol * 3 + 3; col++) {
                if (board[row][col] == 0) return false;
            }
        }
        return true;
    }

    static int lineWinner(int[][] cells, int startRow, int startCol) {
        for (int[] line : LINES) {
            int value = cells[startRow + line[0] / 3][startCol + line[0] % 3];
            if (value < 1 || value > 2) continue;
            if (cells[startRow + line[1] / 3][startCol + line[1] % 3] == value
                    && cells[startRow + line[2] / 3][startCol + line[2] % 3] == value) return value;
        }
        return 0;
    }

    /** 搜索副本只保留当前位置，不复制真实对局的历史记录。 */
    UltimateTicTacToeBoard copyForSearch() {
        UltimateTicTacToeBoard copy = new UltimateTicTacToeBoard();
        for (int row = 0; row < 9; row++) System.arraycopy(board[row], 0, copy.board[row], 0, 9);
        for (int row = 0; row < 3; row++) System.arraycopy(subBoards[row], 0, copy.subBoards[row], 0, 3);
        copy.currentTurn = currentTurn;
        copy.nextBoard = nextBoard;
        copy.moveCount = moveCount;
        copy.over = over;
        copy.winner = winner;
        return copy;
    }

    private static int[][] snapshot(int[][] source) {
        int[][] copy = new int[source.length][];
        for (int row = 0; row < source.length; row++) copy[row] = source[row].clone();
        return copy;
    }

    @Override public int[][] getBoardSnapshot() { return snapshot(board); }
    public int[][] getSubBoardSnapshot() { return snapshot(subBoards); }

    @Override
    public List<int[]> getMoveHistory() {
        List<int[]> copy = new ArrayList<>();
        for (int[] move : history) copy.add(move.clone());
        return Collections.unmodifiableList(copy);
    }

    @Override
    public List<int[]> getLastMoveEffects() {
        return history.isEmpty() ? Collections.emptyList()
                : Collections.singletonList(history.get(history.size() - 1).clone());
    }

    @Override
    public boolean isOwnPieceAt(int row, int col, int side) {
        return side >= 1 && side <= 2 && row >= 0 && row < 9 && col >= 0 && col < 9
                && board[row][col] == side;
    }

    @Override
    public int[][] getWinLine() {
        if (winner == null) return null;
        for (int[] line : LINES) {
            if (subBoards[line[0] / 3][line[0] % 3] == winner
                    && subBoards[line[1] / 3][line[1] % 3] == winner
                    && subBoards[line[2] / 3][line[2] % 3] == winner) {
                int[][] centers = new int[3][2];
                for (int index = 0; index < 3; index++) {
                    centers[index][0] = line[index] / 3 * 3 + 1;
                    centers[index][1] = line[index] % 3 * 3 + 1;
                }
                return centers;
            }
        }
        return null;
    }

    @Override
    public String getScoreSummary() {
        if (!over) return null;
        return winner == null ? "九个小盘均已封闭，无大盘连线，本局平局"
                : (winner == 1 ? "叉方" : "圈方") + "赢得三个连线小盘";
    }

    @Override
    public Object getViewState(int viewerSide) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("kind", type().name());
        view.put("turn", currentTurn);
        view.put("over", over);
        view.put("subBoards", getSubBoardSnapshot());
        view.put("nextBoard", nextBoard);
        view.put("legalMoves", legalMoves());
        int[] won = new int[2];
        for (int[] row : subBoards) {
            for (int value : row) if (value == 1 || value == 2) won[value - 1]++;
        }
        view.put("wonBoards", won);
        return view;
    }
}
