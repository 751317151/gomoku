package com.gomoku.game;

import java.util.List;
import java.util.Random;

/**
 * 西洋跳棋 AI：优先吃子，其次升王，再按前压与中心控制选简单走法。
 *
 * 只做 1 层启发式评估，保证在 45 秒步时内稳定返回；难度通过随机扰动控制。
 */
public final class CheckersAI implements GameAI {
    static final CheckersAI INSTANCE = new CheckersAI();
    private final Random random = new Random();

    private CheckersAI() {}

    @Override
    public int[] getBestMove(BoardGameEngine engine, int side, int difficulty) {
        if (!(engine instanceof CheckersBoard)) return null;
        CheckersBoard checkers = (CheckersBoard) engine;
        List<int[]> moves = checkers.getLegalMovesFor(side);
        if (moves.isEmpty()) return null;

        int[] best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int[] move : moves) {
            int score = evaluate(checkers, move, side);
            score += random.nextInt(difficulty >= 6 ? 3 : 12);
            if (score > bestScore) {
                bestScore = score;
                best = move;
            }
        }
        return best == null ? null : new int[]{best[0], best[1], best[2], best[3]};
    }

    private int evaluate(CheckersBoard board, int[] move, int side) {
        int score = move[4] > 0 ? 100 : 0;
        int toRow = move[2], toCol = move[3];
        int[][] boardState = board.getBoardSnapshot();
        int piece = boardState[move[0]][move[1]];
        boolean isKing = piece % 10 == CheckersBoard.KING;
        // 兵接近底线加分，升王价值最高；兵不能后退，而王可双向活动。
        if (!isKing) {
            int forward = side == 1 ? -1 : 1;
            if (toRow == (side == 1 ? 0 : CheckersBoard.ROWS - 1)) score += 80;
            score += toRow * forward * 2;
        } else {
            score += 30; // 王比兵更难被限制
        }
        // 中心格更有活动空间
        score += 8 - Math.abs(3 - toCol) - Math.abs(3 - toRow);
        return score;
    }

    /** 连吃时判断是否还能继续吃（房间只需要 AI 返回下一段）。 */
    boolean mustContinue(CheckersBoard board, int side) {
        return !board.getLegalMovesFor(side).isEmpty();
    }
}
