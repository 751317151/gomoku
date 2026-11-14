package com.gomoku.game;

import java.util.ArrayList;
import java.util.List;

/**
 * 黑白棋 AI — 位置权重表 + 行动力 + 浅层 Minimax（Alpha-Beta 剪枝）
 *
 * 难度映射：2=简单(搜索1层) 4=中等(3层) 6=困难(5层)
 */
public class ReversiAI implements GameAI {

    static final ReversiAI INSTANCE = new ReversiAI();

    private static final int SIZE = ReversiBoard.SIZE;
    private static final int EMPTY = ReversiBoard.EMPTY;
    private static final int BLACK = ReversiBoard.BLACK;
    private static final int WHITE = ReversiBoard.WHITE;

    private static final int[][] DIRECTIONS = {
            {-1, -1}, {-1, 0}, {-1, 1},
            {0, -1},           {0, 1},
            {1, -1},  {1, 0},  {1, 1}
    };

    /** 经典位置权重表：角 > 边 > 腹，角旁为负 */
    private static final int[][] WEIGHTS = {
            {120, -20,  20,   5,   5,  20, -20, 120},
            {-20, -40,  -5,  -5,  -5,  -5, -40, -20},
            { 20,  -5,  15,   3,   3,  15,  -5,  20},
            {  5,  -5,   3,   3,   3,   3,  -5,   5},
            {  5,  -5,   3,   3,   3,   3,  -5,   5},
            { 20,  -5,  15,   3,   3,  15,  -5,  20},
            {-20, -40,  -5,  -5,  -5,  -5, -40, -20},
            {120, -20,  20,   5,   5,  20, -20, 120}
    };

    /** 深层搜索候选数上限 */
    private static final int MAX_CANDIDATES_DEEP = 10;

    @Override
    public int[] getBestMove(BoardGameEngine engine, int side, int difficulty) {
        if (!(engine instanceof ReversiBoard)) return null;
        ReversiBoard reversi = (ReversiBoard) engine;
        int[][] board = reversi.getBoardSnapshot();

        List<int[]> moves = legalMoves(board, side);
        if (moves.isEmpty()) return null;
        if (moves.size() == 1) return new int[]{moves.get(0)[0], moves.get(0)[1]};

        int depth = searchDepth(difficulty);
        int opponent = (side == BLACK) ? WHITE : BLACK;

        // 根节点：逐候选搜索
        int bestScore = Integer.MIN_VALUE;
        int bestRow = moves.get(0)[0];
        int bestCol = moves.get(0)[1];

        for (int[] mv : moves) {
            int[][] next = copy(board);
            applyMove(next, mv[0], mv[1], side);
            int score = minimax(next, opponent, side, depth - 1,
                    Integer.MIN_VALUE, Integer.MAX_VALUE);
            if (score > bestScore) {
                bestScore = score;
                bestRow = mv[0];
                bestCol = mv[1];
            }
        }
        return new int[]{bestRow, bestCol};
    }

    private int searchDepth(int difficulty) {
        switch (difficulty) {
            case 2: return 1;
            case 6: return 5;
            default: return 3;
        }
    }

    /**
     * Negamax 风格的 minimax：从 toMove 视角评估（对方视角取负）
     */
    private int minimax(int[][] board, int toMove, int aiSide, int depth, int alpha, int beta) {
        List<int[]> moves = legalMoves(board, toMove);

        // 双方均无合法手 → 终局，按子数定胜负
        if (moves.isEmpty()) {
            int opponent = (toMove == BLACK) ? WHITE : BLACK;
            if (legalMoves(board, opponent).isEmpty()) {
                return terminalScore(board, aiSide);
            }
            // 自动停一手：跳过当前方
            int score = minimax(board, opponent, aiSide, depth, alpha, beta);
            return toMove == aiSide ? score : -score;
        }

        if (depth <= 0) {
            int score = evaluate(board, toMove);
            return toMove == aiSide ? score : -score;
        }

        // 深层限制候选数，优先高权重点
        if (depth < 3 && moves.size() > MAX_CANDIDATES_DEEP) {
            moves.sort((a, b) -> b[2] - a[2]);
            moves = moves.subList(0, MAX_CANDIDATES_DEEP);
        }

        int opponent = (toMove == BLACK) ? WHITE : BLACK;
        int best = Integer.MIN_VALUE;
        for (int[] mv : moves) {
            int[][] next = copy(board);
            applyMove(next, mv[0], mv[1], toMove);
            int score = -minimax(next, opponent, aiSide, depth - 1, -beta, -alpha);
            if (score > best) best = score;
            if (best > alpha) alpha = best;
            if (alpha >= beta) break;
        }
        return best;
    }

    /** 终局评估：子数差放大，胜定给出大分 */
    private int terminalScore(int[][] board, int aiSide) {
        int mine = 0, theirs = 0;
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (board[r][c] == aiSide) mine++;
                else if (board[r][c] != EMPTY) theirs++;
            }
        }
        int diff = mine - theirs;
        if (diff > 0) return 10000 + diff * 100;
        if (diff < 0) return -10000 + diff * 100;
        return 0;
    }

    /** 中局评估：位置权重 + 行动力 */
    private int evaluate(int[][] board, int side) {
        int opponent = (side == BLACK) ? WHITE : BLACK;
        int positional = 0;
        int myDiscs = 0, theirDiscs = 0;
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (board[r][c] == side) {
                    positional += WEIGHTS[r][c];
                    myDiscs++;
                } else if (board[r][c] == opponent) {
                    positional -= WEIGHTS[r][c];
                    theirDiscs++;
                }
            }
        }
        int mobility = legalMoves(board, side).size() - legalMoves(board, opponent).size();

        // 接近终局时子数权重提升
        int empty = SIZE * SIZE - myDiscs - theirDiscs;
        int discWeight = empty < 10 ? 20 : 1;
        return positional + mobility * 10 + (myDiscs - theirDiscs) * discWeight;
    }

    /** 合法落子列表，元素 {row, col, flipCount} */
    static List<int[]> legalMoves(int[][] board, int side) {
        List<int[]> moves = new ArrayList<>();
        int opponent = (side == BLACK) ? WHITE : BLACK;
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (board[r][c] != EMPTY) continue;
                int flips = countFlips(board, r, c, side, opponent);
                if (flips > 0) moves.add(new int[]{r, c, flips});
            }
        }
        return moves;
    }

    /** 在副本上落子并翻转 */
    static void applyMove(int[][] board, int row, int col, int side) {
        int opponent = (side == BLACK) ? WHITE : BLACK;
        board[row][col] = side;
        for (int[] d : DIRECTIONS) {
            List<int[]> line = new ArrayList<>();
            int r = row + d[0], c = col + d[1];
            while (r >= 0 && r < SIZE && c >= 0 && c < SIZE && board[r][c] == opponent) {
                line.add(new int[]{r, c});
                r += d[0];
                c += d[1];
            }
            if (!line.isEmpty() && r >= 0 && r < SIZE && c >= 0 && c < SIZE && board[r][c] == side) {
                for (int[] p : line) board[p[0]][p[1]] = side;
            }
        }
    }

    private static int countFlips(int[][] board, int row, int col, int side, int opponent) {
        int total = 0;
        for (int[] d : DIRECTIONS) {
            int count = 0;
            int r = row + d[0], c = col + d[1];
            while (r >= 0 && r < SIZE && c >= 0 && c < SIZE && board[r][c] == opponent) {
                count++;
                r += d[0];
                c += d[1];
            }
            if (count > 0 && r >= 0 && r < SIZE && c >= 0 && c < SIZE && board[r][c] == side) {
                total += count;
            }
        }
        return total;
    }

    private static int[][] copy(int[][] src) {
        int[][] dst = new int[SIZE][];
        for (int i = 0; i < SIZE; i++) {
            dst[i] = src[i].clone();
        }
        return dst;
    }
}
