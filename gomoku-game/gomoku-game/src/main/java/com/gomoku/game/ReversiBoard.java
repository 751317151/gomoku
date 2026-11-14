package com.gomoku.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 黑白棋（Reversi/Othello）棋盘逻辑（GameEngine 实现）
 *
 * 规则：8×8，初始中央四子；落子必须至少翻转一枚对方棋子；
 * 轮到某方无合法落子时自动停一手（对方继续）；双方均无合法落子或棋盘满则终局，子多者胜。
 */
public class ReversiBoard implements BoardGameEngine {

    public static final int SIZE = 8;
    public static final int EMPTY = 0;
    public static final int BLACK = 1;
    public static final int WHITE = 2;

    private static final int STEP_TIME_SECONDS = 60;
    private static final int TOTAL_TIME_SECONDS = 300;

    private static final int[][] DIRECTIONS = {
            {-1, -1}, {-1, 0}, {-1, 1},
            {0, -1},           {0, 1},
            {1, -1},  {1, 0},  {1, 1}
    };

    private final int[][] board;
    private int currentTurn;
    private int moveCount;
    private int moveSeq;
    private final List<int[]> moveHistory = new ArrayList<>();
    private List<int[]> lastEffects = new ArrayList<>();

    private boolean gameOver;
    private Integer winner;

    public ReversiBoard() {
        this.board = new int[SIZE][SIZE];
        reset();
    }

    @Override
    public GameType type() { return GameType.REVERSI; }

    @Override
    public int getRows() { return SIZE; }

    @Override
    public int getCols() { return SIZE; }

    @Override
    public void reset() {
        for (int i = 0; i < SIZE; i++) {
            for (int j = 0; j < SIZE; j++) {
                board[i][j] = EMPTY;
            }
        }
        // 初始中央四子（标准开局）
        board[3][3] = WHITE;
        board[3][4] = BLACK;
        board[4][3] = BLACK;
        board[4][4] = WHITE;
        currentTurn = BLACK; // 黑先
        moveCount = 4;
        moveSeq = 0;
        moveHistory.clear();
        lastEffects = new ArrayList<>();
        gameOver = false;
        winner = null;
    }

    /**
     * 落子：必须为空格且至少翻转一枚对方棋子
     */
    @Override
    public boolean placeStone(int row, int col, int side) {
        if (gameOver) return false;
        if (row < 0 || row >= SIZE || col < 0 || col >= SIZE) return false;
        if (side != currentTurn) return false;
        if (board[row][col] != EMPTY) return false;

        List<int[]> flips = flipsFor(row, col, side);
        if (flips.isEmpty()) return false;

        board[row][col] = side;
        for (int[] f : flips) {
            board[f[0]][f[1]] = side;
        }
        moveCount++;
        moveSeq++;
        moveHistory.add(new int[]{row, col, side});

        // 构建效果列表：所有被翻转的棋子（含新值）
        List<int[]> effects = new ArrayList<>();
        for (int[] f : flips) {
            effects.add(new int[]{f[0], f[1], side});
        }

        // 换手 + 自动停一手判定
        int opponent = (side == BLACK) ? WHITE : BLACK;
        if (hasLegalMoves(opponent)) {
            currentTurn = opponent;
        } else if (hasLegalMoves(side)) {
            // 对手无合法落子，自动停一手，本方继续
            currentTurn = side;
            effects.add(new int[]{EFFECT_AUTO_PASS_ROW, opponent, 0});
        } else {
            // 双方均无合法落子 → 终局
            currentTurn = opponent;
            finish();
        }
        lastEffects = effects;
        return true;
    }

    /**
     * 计算在 (row, col) 落 side 子会翻转的对方棋子（不修改盘面）
     */
    public List<int[]> flipsFor(int row, int col, int side) {
        List<int[]> flips = new ArrayList<>();
        if (row < 0 || row >= SIZE || col < 0 || col >= SIZE) return flips;
        if (board[row][col] != EMPTY) return flips;

        int opponent = (side == BLACK) ? WHITE : BLACK;
        for (int[] d : DIRECTIONS) {
            List<int[]> line = new ArrayList<>();
            int r = row + d[0], c = col + d[1];
            // 先走一段连续对方棋子
            while (r >= 0 && r < SIZE && c >= 0 && c < SIZE && board[r][c] == opponent) {
                line.add(new int[]{r, c});
                r += d[0];
                c += d[1];
            }
            // 终点必须是己方棋子才有效
            if (!line.isEmpty() && r >= 0 && r < SIZE && c >= 0 && c < SIZE && board[r][c] == side) {
                flips.addAll(line);
            }
        }
        return flips;
    }

    /**
     * 某方是否有合法落子
     */
    public boolean hasLegalMoves(int side) {
        return !getLegalMoves(side).isEmpty();
    }

    /**
     * 某方的所有合法落子点
     */
    public List<int[]> getLegalMoves(int side) {
        List<int[]> moves = new ArrayList<>();
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (board[r][c] == EMPTY && !flipsFor(r, c, side).isEmpty()) {
                    moves.add(new int[]{r, c});
                }
            }
        }
        return moves;
    }

    /** 双方棋子数 [黑, 白] */
    public int[] countDiscs() {
        int black = 0, white = 0;
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (board[r][c] == BLACK) black++;
                else if (board[r][c] == WHITE) white++;
            }
        }
        return new int[]{black, white};
    }

    // ============ 测试钩子（包级可见，仅单元测试使用） ============

    void setCell(int row, int col, int side) {
        board[row][col] = side;
    }

    void setTurn(int side) {
        this.currentTurn = side;
    }

    private void finish() {
        gameOver = true;
        int[] counts = countDiscs();
        if (counts[0] > counts[1]) winner = BLACK;
        else if (counts[1] > counts[0]) winner = WHITE;
        else winner = null; // 平局
    }

    @Override
    public int getCurrentTurn() { return currentTurn; }

    @Override
    public int[][] getBoardSnapshot() {
        int[][] snapshot = new int[SIZE][SIZE];
        for (int i = 0; i < SIZE; i++) {
            System.arraycopy(board[i], 0, snapshot[i], 0, SIZE);
        }
        return snapshot;
    }

    @Override
    public boolean isOver() { return gameOver; }

    @Override
    public Integer getWinner() { return winner; }

    @Override
    public String getScoreSummary() {
        int[] counts = countDiscs();
        return "黑 " + counts[0] + " 子 : 白 " + counts[1] + " 子";
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
