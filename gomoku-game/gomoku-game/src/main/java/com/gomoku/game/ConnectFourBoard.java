package com.gomoku.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 四子棋（Connect Four）规则引擎（GameEngine 实现）
 *
 * 7×6 竖向棋盘：棋子受重力下坠落到每列最低空位，横/竖/斜四子连线获胜，棋盘满为平局。
 * 盘面编码：0 空，1/2 = 双方棋子（1=红 先行，2=黄）。
 */
public class ConnectFourBoard implements BoardGameEngine {

    public static final int ROWS = 6;
    public static final int COLS = 7;

    private static final int STEP_TIME_SECONDS = 30;
    private static final int TOTAL_TIME_SECONDS = 300;

    private final int[][] board;
    private int currentTurn;
    private int moveCount;
    private int moveSeq;
    private final List<int[]> moveHistory = new ArrayList<>();
    private List<int[]> lastEffects = new ArrayList<>();

    private boolean gameOver;
    private Integer winner;

    public ConnectFourBoard() {
        this.board = new int[ROWS][COLS];
        reset();
    }

    @Override
    public GameType type() { return GameType.CONNECT4; }

    @Override
    public int getRows() { return ROWS; }

    @Override
    public int getCols() { return COLS; }

    @Override
    public void reset() {
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                board[r][c] = 0;
            }
        }
        currentTurn = 1;
        moveCount = 0;
        moveSeq = 0;
        moveHistory.clear();
        lastEffects = new ArrayList<>();
        gameOver = false;
        winner = null;
    }

    /** 某列当前的下坠落点（最低空行），列满返回 -1 */
    public int dropRow(int col) {
        if (col < 0 || col >= COLS) return -1;
        for (int r = ROWS - 1; r >= 0; r--) {
            if (board[r][col] == 0) return r;
        }
        return -1;
    }

    @Override
    public boolean placeStone(int row, int col, int side) {
        if (gameOver) return false;
        if (side != currentTurn) return false;
        if (col < 0 || col >= COLS) return false;
        int r = dropRow(col);
        if (r < 0 || r != row) return false; // 必须落在重力落点

        board[r][col] = side;
        moveCount++;
        moveSeq++;
        moveHistory.add(new int[]{r, col, side});
        lastEffects = new ArrayList<>();
        lastEffects.add(new int[]{r, col, side});

        currentTurn = side == 1 ? 2 : 1;

        if (checkWinAt(r, col, side)) {
            gameOver = true;
            winner = side;
        } else if (moveCount >= ROWS * COLS) {
            gameOver = true;
            winner = null;
        }
        return true;
    }

    /** 从 (row,col) 出发四方向连线判定 */
    private boolean checkWinAt(int row, int col, int side) {
        int[][] dirs = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};
        for (int[] d : dirs) {
            int count = 1;
            count += countDir(row, col, d[0], d[1], side);
            count += countDir(row, col, -d[0], -d[1], side);
            if (count >= 4) return true;
        }
        return false;
    }

    private int countDir(int row, int col, int dr, int dc, int side) {
        int count = 0;
        int r = row + dr, c = col + dc;
        while (r >= 0 && r < ROWS && c >= 0 && c < COLS && board[r][c] == side) {
            count++;
            r += dr;
            c += dc;
        }
        return count;
    }

    /** 获胜四连坐标（GameEngine.getWinLine 约定扩展，供前端高亮） */
    @Override
    public int[][] getWinLine() {
        if (!gameOver || winner == null) return null;
        int side = winner;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (board[r][c] != side) continue;
                int[][] dirs = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};
                for (int[] d : dirs) {
                    // 只从每条线的起点统计，凑满 4 收集
                    int prevR = r - d[0], prevC = c - d[1];
                    boolean isStart = !(prevR >= 0 && prevR < ROWS && prevC >= 0 && prevC < COLS
                            && board[prevR][prevC] == side);
                    if (!isStart) continue;
                    List<int[]> line = new ArrayList<>();
                    int rr = r, cc = c;
                    while (rr >= 0 && rr < ROWS && cc >= 0 && cc < COLS && board[rr][cc] == side) {
                        line.add(new int[]{rr, cc});
                        rr += d[0];
                        cc += d[1];
                    }
                    if (line.size() >= 4) {
                        return line.toArray(new int[0][]);
                    }
                }
            }
        }
        return null;
    }

    @Override
    public boolean isOwnPieceAt(int row, int col, int side) {
        if (row < 0 || row >= ROWS || col < 0 || col >= COLS) return false;
        return board[row][col] == side;
    }

    // ============ 测试钩子（包级可见，仅单元测试使用） ============

    void setCell(int row, int col, int side) {
        board[row][col] = side;
    }

    void setTurn(int side) {
        this.currentTurn = side;
    }

    @Override
    public int getCurrentTurn() { return currentTurn; }

    @Override
    public int[][] getBoardSnapshot() {
        int[][] snapshot = new int[ROWS][COLS];
        for (int i = 0; i < ROWS; i++) {
            System.arraycopy(board[i], 0, snapshot[i], 0, COLS);
        }
        return snapshot;
    }

    @Override
    public boolean isOver() { return gameOver; }

    @Override
    public Integer getWinner() { return winner; }

    @Override
    public String getScoreSummary() {
        if (!gameOver || winner == null) return null;
        return (winner == 1 ? "红方" : "黄方") + "四子连线";
    }

    @Override
    public List<int[]> getMoveHistory() {
        return Collections.unmodifiableList(moveHistory);
    }

    @Override
    public List<int[]> getLastMoveEffects() {
        return lastEffects;
    }

    @Override
    public int getMoveCount() { return moveCount; }

    @Override
    public int getMoveSeq() { return moveSeq; }

    @Override
    public int getStepTimeSeconds() { return STEP_TIME_SECONDS; }

    @Override
    public int getTotalTimeSeconds() { return TOTAL_TIME_SECONDS; }
}
