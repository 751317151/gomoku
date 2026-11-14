package com.gomoku.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 围棋（9 路小棋盘）规则引擎（GameEngine 实现）
 *
 * 实现规则：
 * - 气：同色连通块相邻空点数；无气整块被提
 * - 禁自杀：落子后己方连通块无气且未提子 → 非法
 * - 简单劫：禁止复现上一手之前的局面（提一子回提）
 * - 停一手：显式 PASS，双方连续停一手 → 终局
 * - 计分：数子法（存活子 + 单色包围空点），白贴 7.5 目，无和棋
 */
public class GoBoard implements BoardGameEngine {

    public static final int SIZE = 9;
    public static final int EMPTY = 0;
    public static final int BLACK = 1;
    public static final int WHITE = 2;

    /** 白方贴目（9 路数子法常用 7.5，保证无和棋） */
    public static final double KOMI = 7.5;

    private static final int STEP_TIME_SECONDS = 60;
    private static final int TOTAL_TIME_SECONDS = 600;

    private static final int[][] NEIGHBORS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    private final int[][] board;
    private int currentTurn;
    private int moveCount;
    private int moveSeq;
    private final List<int[]> moveHistory = new ArrayList<>();
    private List<int[]> lastEffects = new ArrayList<>();

    private boolean gameOver;
    private Integer winner;
    private String scoreSummary;

    /** 上一手落子前的局面 key（简单劫判定） */
    private String previousPositionKey;
    private int consecutivePasses;

    public GoBoard() {
        this.board = new int[SIZE][SIZE];
        reset();
    }

    @Override
    public GameType type() { return GameType.GO9; }

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
        currentTurn = BLACK;
        moveCount = 0;
        moveSeq = 0;
        moveHistory.clear();
        lastEffects = new ArrayList<>();
        gameOver = false;
        winner = null;
        scoreSummary = null;
        previousPositionKey = keyOf(board);
        consecutivePasses = 0;
    }

    /**
     * 落子（含提子 / 禁自杀 / 简单劫校验）
     */
    @Override
    public boolean placeStone(int row, int col, int side) {
        if (gameOver) return false;
        if (!inBounds(row, col) || board[row][col] != EMPTY) return false;
        if (side != currentTurn) return false;

        List<int[]> captured = new ArrayList<>();
        String keyBefore = keyOf(board);
        String keyAfter = simulateMove(board, row, col, side, captured);

        // 禁自杀：己方所在连通块无气且未提子
        List<int[]> ownGroup = findGroupOn(board, row, col);
        if (captured.isEmpty() && libertiesOfOn(board, ownGroup) == 0) {
            board[row][col] = EMPTY;
            return false;
        }

        // 简单劫：不得复现上一手之前的局面
        if (keyAfter.equals(previousPositionKey)) {
            revert(board, row, col, side, captured);
            return false;
        }

        // 合法落子，提交状态
        previousPositionKey = keyBefore;
        moveCount++;
        moveSeq++;
        moveHistory.add(new int[]{row, col, side});
        consecutivePasses = 0;
        lastEffects = new ArrayList<>();
        for (int[] p : captured) lastEffects.add(new int[]{p[0], p[1], EMPTY});
        currentTurn = opponentOf(side);

        if (moveCount >= SIZE * SIZE) {
            finishByScore();
        }
        return true;
    }

    /**
     * 显式停一手（围棋战略性 pass）
     */
    @Override
    public boolean pass(int side) {
        if (gameOver) return false;
        if (side != currentTurn) return false;

        moveCount++;
        moveSeq++;
        moveHistory.add(new int[]{PASS_MOVE_ROW, PASS_MOVE_ROW, side});
        lastEffects = new ArrayList<>();
        consecutivePasses++;
        currentTurn = opponentOf(side);

        if (consecutivePasses >= 2) {
            finishByScore();
        }
        return true;
    }

    /**
     * 综合合法性检查（落子点 + 自杀 + 劫），供 AI 与 UI 使用。
     * 纯快照模拟，不修改实盘。
     */
    public boolean isLegalMove(int row, int col, int side) {
        if (gameOver || !inBounds(row, col) || board[row][col] != EMPTY) return false;
        if (side != currentTurn) return false;

        int[][] sim = new int[SIZE][];
        for (int i = 0; i < SIZE; i++) sim[i] = board[i].clone();

        List<int[]> captured = new ArrayList<>();
        String keyAfter = simulateMove(sim, row, col, side, captured);

        List<int[]> ownGroup = findGroupOn(sim, row, col);
        if (captured.isEmpty() && libertiesOfOn(sim, ownGroup) == 0) return false;
        return !keyAfter.equals(previousPositionKey);
    }

    /**
     * 在给定棋盘上模拟落子 + 提子（会修改传入数组）
     * @return 落子后的局面 key
     */
    private static String simulateMove(int[][] b, int row, int col, int side, List<int[]> capturedOut) {
        b[row][col] = side;
        int opponent = opponentOf(side);
        boolean[][] handled = new boolean[SIZE][SIZE];
        for (int[] n : NEIGHBORS) {
            int nr = row + n[0], nc = col + n[1];
            if (!inBounds(nr, nc) || b[nr][nc] != opponent || handled[nr][nc]) continue;
            List<int[]> group = findGroupOn(b, nr, nc);
            for (int[] p : group) handled[p[0]][p[1]] = true;
            if (libertiesOfOn(b, group) == 0) {
                for (int[] p : group) {
                    b[p[0]][p[1]] = EMPTY;
                    capturedOut.add(new int[]{p[0], p[1]});
                }
            }
        }
        return keyOf(b);
    }

    /** 撤销未提交的模拟落子 */
    private static void revert(int[][] b, int row, int col, int side, List<int[]> captured) {
        b[row][col] = EMPTY;
        int opponent = opponentOf(side);
        for (int[] p : captured) {
            b[p[0]][p[1]] = opponent;
        }
    }

    /** flood-fill 找连通同色块 */
    private static List<int[]> findGroupOn(int[][] b, int row, int col) {
        int color = b[row][col];
        List<int[]> group = new ArrayList<>();
        boolean[][] visited = new boolean[SIZE][SIZE];
        List<int[]> stack = new ArrayList<>();
        stack.add(new int[]{row, col});
        visited[row][col] = true;
        while (!stack.isEmpty()) {
            int[] p = stack.remove(stack.size() - 1);
            group.add(p);
            for (int[] n : NEIGHBORS) {
                int nr = p[0] + n[0], nc = p[1] + n[1];
                if (inBounds(nr, nc) && !visited[nr][nc] && b[nr][nc] == color) {
                    visited[nr][nc] = true;
                    stack.add(new int[]{nr, nc});
                }
            }
        }
        return group;
    }

    /** 连通块的气数 */
    private static int libertiesOfOn(int[][] b, List<int[]> group) {
        boolean[][] counted = new boolean[SIZE][SIZE];
        int liberties = 0;
        for (int[] p : group) {
            for (int[] n : NEIGHBORS) {
                int nr = p[0] + n[0], nc = p[1] + n[1];
                if (inBounds(nr, nc) && b[nr][nc] == EMPTY && !counted[nr][nc]) {
                    counted[nr][nc] = true;
                    liberties++;
                }
            }
        }
        return liberties;
    }

    private static String keyOf(int[][] b) {
        StringBuilder sb = new StringBuilder(SIZE * SIZE);
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                sb.append(b[r][c]);
            }
        }
        return sb.toString();
    }

    /**
     * 数子法终局计分：黑 = 存活子 + 黑独占空点；白 = 存活子 + 白独占空点 + 贴目
     */
    private void finishByScore() {
        gameOver = true;
        int blackStones = 0, whiteStones = 0;
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (board[r][c] == BLACK) blackStones++;
                else if (board[r][c] == WHITE) whiteStones++;
            }
        }

        // 空点分区：仅被一色包围的算该色地盘
        double blackTerritory = 0, whiteTerritory = 0;
        boolean[][] visited = new boolean[SIZE][SIZE];
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (board[r][c] != EMPTY || visited[r][c]) continue;
                List<int[]> region = new ArrayList<>();
                boolean touchesBlack = false, touchesWhite = false;
                List<int[]> stack = new ArrayList<>();
                stack.add(new int[]{r, c});
                visited[r][c] = true;
                while (!stack.isEmpty()) {
                    int[] p = stack.remove(stack.size() - 1);
                    region.add(p);
                    for (int[] n : NEIGHBORS) {
                        int nr = p[0] + n[0], nc = p[1] + n[1];
                        if (!inBounds(nr, nc)) continue;
                        if (board[nr][nc] == BLACK) touchesBlack = true;
                        else if (board[nr][nc] == WHITE) touchesWhite = true;
                        else if (!visited[nr][nc]) {
                            visited[nr][nc] = true;
                            stack.add(new int[]{nr, nc});
                        }
                    }
                }
                if (touchesBlack && !touchesWhite) blackTerritory += region.size();
                else if (touchesWhite && !touchesBlack) whiteTerritory += region.size();
                // 双色交界 = 公气，双方都不算
            }
        }

        double blackScore = blackStones + blackTerritory;
        double whiteScore = whiteStones + whiteTerritory + KOMI;
        winner = blackScore > whiteScore ? BLACK : WHITE;
        double diff = Math.abs(blackScore - whiteScore);
        scoreSummary = String.format("数子法：黑 %.0f 子 vs 白 %.0f 子(含贴 %.1f 目) → %s胜 %.1f 目",
                blackScore, whiteScore - KOMI, KOMI,
                winner == BLACK ? "黑" : "白", diff);
    }

    private static boolean inBounds(int r, int c) {
        return r >= 0 && r < SIZE && c >= 0 && c < SIZE;
    }

    private static int opponentOf(int side) {
        return side == BLACK ? WHITE : BLACK;
    }

    // ============ GameEngine 通用接口 ============

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
    public String getScoreSummary() { return scoreSummary; }

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
