package com.gomoku.game;

import java.util.ArrayList;
import java.util.List;

/**
 * 四子棋 AI — Negamax（Alpha-Beta 剪枝）+ 滑窗评估 + 中心优先
 *
 * 难度映射：2=简单(2层) 4=中等(4层) 6=困难(6层)
 * 返回走法 {row, col}（row 为该列重力落点）
 */
public class ConnectFourAI implements GameAI {

    static final ConnectFourAI INSTANCE = new ConnectFourAI();

    private static final int ROWS = ConnectFourBoard.ROWS;
    private static final int COLS = ConnectFourBoard.COLS;

    /** 中心优先的列顺序，提升剪枝效率 */
    private static final int[] COLUMN_ORDER = {3, 2, 4, 1, 5, 0, 6};

    private static final int WIN_SCORE = 1000000;

    private ConnectFourAI() {}

    @Override
    public int[] getBestMove(BoardGameEngine engine, int side, int difficulty) {
        int[][] board = engine.getBoardSnapshot();
        int depth = searchDepth(difficulty);
        int opponent = side == 1 ? 2 : 1;

        int bestScore = Integer.MIN_VALUE;
        int bestCol = -1;

        for (int col : COLUMN_ORDER) {
            int row = dropRow(board, col);
            if (row < 0) continue;

            board[row][col] = side;
            int score;
            if (isWinAt(board, row, col, side)) {
                score = WIN_SCORE - (6 - depth); // 越快赢分越高
            } else {
                score = -negamax(board, opponent, depth - 1, Integer.MIN_VALUE, Integer.MAX_VALUE);
            }
            board[row][col] = 0;

            if (score > bestScore) {
                bestScore = score;
                bestCol = col;
            }
        }
        if (bestCol < 0) return null;
        return new int[]{dropRow(board, bestCol), bestCol};
    }

    private int searchDepth(int difficulty) {
        switch (difficulty) {
            case 2: return 2;
            case 6: return 6;
            default: return 4;
        }
    }

    private int negamax(int[][] board, int side, int depth, int alpha, int beta) {
        if (depth <= 0) {
            return evaluate(board, side);
        }

        int opponent = side == 1 ? 2 : 1;
        int best = Integer.MIN_VALUE;
        for (int col : COLUMN_ORDER) {
            int row = dropRow(board, col);
            if (row < 0) continue;

            board[row][col] = side;
            int score;
            if (isWinAt(board, row, col, side)) {
                score = WIN_SCORE - depth;
            } else {
                score = -negamax(board, opponent, depth - 1, -beta, -alpha);
            }
            board[row][col] = 0;

            if (score > best) best = score;
            if (best > alpha) alpha = best;
            if (alpha >= beta) break;
        }
        return best == Integer.MIN_VALUE ? 0 : best; // 无处可下 = 平局
    }

    /**
     * 滑窗评估：所有 4 连窗口按子数打分（己方 +，对方 -），中心列加权
     */
    private int evaluate(int[][] board, int side) {
        int opponent = side == 1 ? 2 : 1;
        int score = 0;

        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (board[r][c] == side && c == 3) score += 6;
            }
        }

        score += scoreAllWindows(board, side, opponent);
        return score;
    }

    private int scoreAllWindows(int[][] board, int mine, int theirs) {
        int score = 0;
        int[][] dirs = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};

        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                for (int[] d : dirs) {
                    int pr = r - d[0], pc = c - d[1];
                    if (pr >= 0 && pr < ROWS && pc >= 0 && pc < COLS) continue; // 只从窗口起点算
                    // 统计窗口 [r,c] 沿 d 方向 4 格
                    int mineCount = 0, theirCount = 0;
                    boolean valid = true;
                    for (int i = 0; i < 4; i++) {
                        int rr = r + d[0] * i, cc = c + d[1] * i;
                        if (rr < 0 || rr >= ROWS || cc < 0 || cc >= COLS) { valid = false; break; }
                        int v = board[rr][cc];
                        if (v == mine) mineCount++;
                        else if (v == theirs) theirCount++;
                    }
                    if (!valid) continue;
                    score += windowScore(mineCount, theirCount);
                }
            }
        }
        return score;
    }

    private int windowScore(int mine, int theirs) {
        if (mine > 0 && theirs > 0) return 0; // 死窗
        if (mine == 4) return 100000;
        if (theirs == 4) return -100000;
        if (mine == 3) return 120;
        if (theirs == 3) return -150; // 对方三连威胁更优先防守
        if (mine == 2) return 15;
        if (theirs == 2) return -18;
        if (mine == 1) return 2;
        if (theirs == 1) return -2;
        return 0;
    }

    private static int dropRow(int[][] board, int col) {
        for (int r = ROWS - 1; r >= 0; r--) {
            if (board[r][col] == 0) return r;
        }
        return -1;
    }

    private static boolean isWinAt(int[][] board, int row, int col, int side) {
        int[][] dirs = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};
        for (int[] d : dirs) {
            int count = 1;
            int r = row + d[0], c = col + d[1];
            while (r >= 0 && r < ROWS && c >= 0 && c < COLS && board[r][c] == side) { count++; r += d[0]; c += d[1]; }
            r = row - d[0]; c = col - d[1];
            while (r >= 0 && r < ROWS && c >= 0 && c < COLS && board[r][c] == side) { count++; r -= d[0]; c -= d[1]; }
            if (count >= 4) return true;
        }
        return false;
    }
}
