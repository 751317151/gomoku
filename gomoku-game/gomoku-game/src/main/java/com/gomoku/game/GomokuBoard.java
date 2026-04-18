package com.gomoku.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 五子棋棋盘逻辑
 */
public class GomokuBoard {

    public static final int SIZE = 15;
    public static final int EMPTY = 0;
    public static final int BLACK = 1;
    public static final int WHITE = 2;

    private final int[][] board;
    private int currentTurn; // 当前轮到谁
    private int moveCount;
    private int moveSeq; // 全局递增序列号（用于消息幂等）
    private final List<int[]> moveHistory = new ArrayList<>();

    public GomokuBoard() {
        this.board = new int[SIZE][SIZE];
        this.currentTurn = BLACK; // 黑棋先手
        this.moveCount = 0;
        this.moveSeq = 0;
    }

    /**
     * 尝试落子
     * @return true 如果落子成功
     */
    public boolean placeStone(int row, int col, int stone) {
        if (!isValidMove(row, col)) return false;
        if (stone != currentTurn) return false;

        board[row][col] = stone;
        moveCount++;
        moveSeq++;
        moveHistory.add(new int[]{row, col, stone});
        currentTurn = (currentTurn == BLACK) ? WHITE : BLACK;
        return true;
    }

    /**
     * 检查是否有效位置
     */
    public boolean isValidMove(int row, int col) {
        return row >= 0 && row < SIZE
                && col >= 0 && col < SIZE
                && board[row][col] == EMPTY;
    }

    /**
     * 检查指定位置是否获胜
     */
    public boolean checkWin(int row, int col) {
        return getWinLine(row, col) != null;
    }

    /**
     * 获取获胜连线坐标（五连的 5 个点），未获胜返回 null
     */
    public int[][] getWinLine(int row, int col) {
        int stone = board[row][col];
        if (stone == EMPTY) return null;

        int[][] directions = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};

        for (int[] dir : directions) {
            List<int[]> line = new ArrayList<>();
            line.add(new int[]{row, col});

            // 正方向
            int r = row + dir[0], c = col + dir[1];
            while (r >= 0 && r < SIZE && c >= 0 && c < SIZE && board[r][c] == stone) {
                line.add(new int[]{r, c});
                r += dir[0];
                c += dir[1];
            }
            // 反方向
            r = row - dir[0];
            c = col - dir[1];
            while (r >= 0 && r < SIZE && c >= 0 && c < SIZE && board[r][c] == stone) {
                line.add(new int[]{r, c});
                r -= dir[0];
                c -= dir[1];
            }

            if (line.size() >= 5) {
                return line.toArray(new int[0][]);
            }
        }
        return null;
    }

    /**
     * 计算某方向连续同色棋子数
     */
    private int countDirection(int row, int col, int dr, int dc, int stone) {
        int count = 0;
        int r = row + dr;
        int c = col + dc;

        while (r >= 0 && r < SIZE && c >= 0 && c < SIZE && board[r][c] == stone) {
            count++;
            r += dr;
            c += dc;
        }
        return count;
    }

    /**
     * 检查是否平局（棋盘已满）
     */
    public boolean isDraw() {
        return moveCount >= SIZE * SIZE;
    }

    /**
     * 重置棋盘
     */
    public void reset() {
        for (int i = 0; i < SIZE; i++) {
            for (int j = 0; j < SIZE; j++) {
                board[i][j] = EMPTY;
            }
        }
        currentTurn = BLACK;
        moveCount = 0;
        moveSeq = 0;
        moveHistory.clear();
    }

    /**
     * 获取棋局记录（不可变）
     */
    public List<int[]> getMoveHistory() {
        return Collections.unmodifiableList(moveHistory);
    }

    /**
     * 将棋盘序列化为二维数组（用于同步）
     */
    public int[][] getBoardSnapshot() {
        int[][] snapshot = new int[SIZE][SIZE];
        for (int i = 0; i < SIZE; i++) {
            System.arraycopy(board[i], 0, snapshot[i], 0, SIZE);
        }
        return snapshot;
    }

    /**
  * 直接访问内部数组（仅限同包内的 AI 引擎使用，外部应使用 getBoardSnapshot()）
  */
 int[][] getBoard() { return board; }
    public int getCurrentTurn() { return currentTurn; }
    public int getMoveCount() { return moveCount; }
    public int getMoveSeq() { return moveSeq; }
    public int getCell(int row, int col) { return board[row][col]; }
}
