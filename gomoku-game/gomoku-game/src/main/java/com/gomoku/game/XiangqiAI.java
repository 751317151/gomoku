package com.gomoku.game;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 中国象棋 AI — 材料评估 + Negamax（Alpha-Beta 剪枝）+ 吃子优先排序
 *
 * 难度映射：2=简单(1层) 4=中等(2层) 6=困难(3层)
 * 返回走法 {fromRow, fromCol, toRow, toCol}
 */
public class XiangqiAI implements GameAI {

    static final XiangqiAI INSTANCE = new XiangqiAI();

    private static final int ROWS = XiangqiBoard.ROWS;
    private static final int COLS = XiangqiBoard.COLS;

    /** 深层候选数上限 */
    private static final int MAX_CANDIDATES_DEEP = 14;

    private static final int MATE_SCORE = 100000;

    private XiangqiAI() {}

    @Override
    public int[] getBestMove(BoardGameEngine engine, int side, int difficulty) {
        if (!(engine instanceof XiangqiBoard)) return null;
        int[][] board = engine.getBoardSnapshot();

        List<int[]> moves = allLegalMoves(board, side);
        if (moves.isEmpty()) return null;
        if (moves.size() == 1) return moves.get(0);

        int depth = searchDepth(difficulty);
        int opponent = side == 1 ? 2 : 1;

        // 根节点：吃子优先排序
        orderMoves(board, moves);
        int bestScore = Integer.MIN_VALUE;
        int[] best = moves.get(0);

        for (int[] mv : moves) {
            int captured = apply(board, mv);
            int score = -negamax(board, opponent, depth - 1, Integer.MIN_VALUE, Integer.MAX_VALUE);
            undo(board, mv, captured);

            // 简单难度增加随机性
            int noise = difficulty <= 2 ? ThreadLocalRandom.current().nextInt(60) : 0;
            if (score + noise > bestScore) {
                bestScore = score;
                best = mv;
            }
        }
        return best;
    }

    private int searchDepth(int difficulty) {
        switch (difficulty) {
            case 2: return 1;
            case 6: return 3;
            default: return 2;
        }
    }

    /**
     * Negamax：从 side 视角返回分数
     */
    private int negamax(int[][] board, int side, int depth, int alpha, int beta) {
        List<int[]> moves = allLegalMoves(board, side);

        // 无子可动：绝杀/困毙 → 负
        if (moves.isEmpty()) return -MATE_SCORE + (10 - depth);

        if (depth <= 0) {
            return evaluate(board, side);
        }

        int opponent = side == 1 ? 2 : 1;
        orderMoves(board, moves);
        if (moves.size() > MAX_CANDIDATES_DEEP) {
            moves = moves.subList(0, MAX_CANDIDATES_DEEP);
        }

        int best = Integer.MIN_VALUE;
        for (int[] mv : moves) {
            int captured = apply(board, mv);
            int score = -negamax(board, opponent, depth - 1, -beta, -alpha);
            undo(board, mv, captured);
            if (score > best) best = score;
            if (best > alpha) alpha = best;
            if (alpha >= beta) break;
        }
        return best;
    }

    /** 所有合法走法 {fromR, fromC, toR, toC} */
    private List<int[]> allLegalMoves(int[][] board, int side) {
        List<int[]> moves = new ArrayList<>();
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int v = board[r][c];
                if (v == 0 || v / 10 != side) continue;
                for (int[] t : XiangqiBoard.pseudoMoves(board, r, c)) {
                    int captured = apply(board, r, c, t[0], t[1], v);
                    boolean bad = XiangqiBoard.inCheck(board, side);
                    undo(board, r, c, t[0], t[1], v, captured);
                    if (!bad) {
                        moves.add(new int[]{r, c, t[0], t[1]});
                    }
                }
            }
        }
        return moves;
    }

    /** 吃子优先排序（被吃子价值降序） */
    private void orderMoves(int[][] board, List<int[]> moves) {
        List<long[]> scored = new ArrayList<>();
        for (int[] mv : moves) {
            int victim = board[mv[2]][mv[3]];
            long s = victim == 0 ? 0 : pieceValue(victim) * 100L;
            scored.add(new long[]{mv[0], mv[1], mv[2], mv[3], s});
        }
        scored.sort((a, b) -> Long.compare(b[4], a[4]));
        for (int i = 0; i < moves.size(); i++) {
            long[] s = scored.get(i);
            moves.set(i, new int[]{(int) s[0], (int) s[1], (int) s[2], (int) s[3]});
        }
    }

    /** side 视角材料评估 */
    private int evaluate(int[][] board, int side) {
        int score = 0;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int v = board[r][c];
                if (v == 0) continue;
                int value = pieceValue(v);
                // 过河兵价值提升
                if (v % 10 == XiangqiBoard.PAWN) {
                    int pSide = v / 10;
                    boolean crossed = pSide == 1 ? r <= 4 : r >= 5;
                    if (crossed) value += 100;
                }
                score += (v / 10 == side) ? value : -value;
            }
        }
        return score;
    }

    private static int pieceValue(int piece) {
        switch (piece % 10) {
            case XiangqiBoard.ROOK:    return 900;
            case XiangqiBoard.CANNON:  return 450;
            case XiangqiBoard.HORSE:   return 400;
            case XiangqiBoard.BISHOP:  return 200;
            case XiangqiBoard.ADVISOR: return 200;
            case XiangqiBoard.PAWN:    return 100;
            case XiangqiBoard.KING:    return 10000;
            default: return 0;
        }
    }

    private static int apply(int[][] board, int[] mv) {
        return apply(board, mv[0], mv[1], mv[2], mv[3], board[mv[0]][mv[1]]);
    }

    private static int apply(int[][] board, int fromR, int fromC, int toR, int toC, int piece) {
        int captured = board[toR][toC];
        board[toR][toC] = piece;
        board[fromR][fromC] = 0;
        return captured;
    }

    private static void undo(int[][] board, int[] mv, int captured) {
        undo(board, mv[0], mv[1], mv[2], mv[3], board[mv[2]][mv[3]], captured);
    }

    private static void undo(int[][] board, int fromR, int fromC, int toR, int toC, int piece, int captured) {
        board[fromR][fromC] = piece;
        board[toR][toC] = captured;
    }
}
