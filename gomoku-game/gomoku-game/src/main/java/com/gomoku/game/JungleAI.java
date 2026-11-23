package com.gomoku.game;

import java.util.List;
import java.util.Random;

/**
 * 斗兽棋 AI：一步启发式 + 关键局面检查。
 *
 * 三档难度：
 * - 简单：在前若干候选中随机；
 * - 中等：优先进攻/吃子、靠近对方兽穴，避开陷阱；
 * - 困难：额外评估被反吃风险与跳河路线，优先走到安全且能威胁兽穴的位置。
 */
public final class JungleAI implements GameAI {

    public static final JungleAI INSTANCE = new JungleAI();

    private final Random random = new Random();

    private JungleAI() {}

    @Override
    public int[] getBestMove(BoardGameEngine engine, int side, int difficulty) {
        if (!(engine instanceof JungleBoard)) return null;
        JungleBoard board = (JungleBoard) engine;
        List<int[]> moves = board.legalMoves(side);
        if (moves.isEmpty()) return null;
        if (difficulty <= 2) {
            int[] pick = moves.get(random.nextInt(moves.size()));
            return new int[]{pick[0], pick[1], pick[2], pick[3]};
        }

        int[][] snapshot = board.getBoardSnapshot();
        int[] best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int[] move : moves) {
            double score = score(board, snapshot, side, move, difficulty);
            if (difficulty < 6) score += random.nextDouble() * 6;
            if (score > bestScore) {
                bestScore = score;
                best = move;
            }
        }
        return best == null ? null : new int[]{best[0], best[1], best[2], best[3]};
    }

    private double score(JungleBoard board, int[][] b, int side, int[] move, int difficulty) {
        int fromRow = move[0], fromCol = move[1], toRow = move[2], toCol = move[3];
        int piece = b[fromRow][fromCol];
        int type = piece % 10;
        int target = b[toRow][toCol];
        double score = 0;

        // 进入对方兽穴直接获胜
        int[] den = JungleBoard.denOf(side);
        if (toRow == den[0] && toCol == den[1]) return 100000;

        // 吃子收益按对方棋子强度计算；吃象（鼠）与吃高价值棋子权重更高
        if (target != 0) {
            int defend = target % 10;
            score += 20 + defend * 12;
            if (type == JungleBoard.MOUSE && defend == JungleBoard.ELEPHANT) score += 30;
        }

        // 向对方兽穴推进；越接近越好，但不要为了终点忽略安全
        int distBefore = Math.abs(fromRow - den[0]) + Math.abs(fromCol - den[1]);
        int distAfter = Math.abs(toRow - den[0]) + Math.abs(toCol - den[1]);
        score += (distBefore - distAfter) * 4;

        // 跳河节省路程
        if (move[5] > 0) score += 8;

        // 陷阱：自己站上对方陷阱可能被低战力棋子白吃，非必要不进
        if (JungleBoard.isTrap(toRow, toCol) && isTrapForSide(toRow, toCol, side)) score -= 18;

        // 困住对方：走到能威胁对方兽穴的位置加分
        if (difficulty >= 6) {
            score += safety(board, b, side, toRow, toCol, type) * 2;
            // 鼠主动下水通常是为了防守/吃象，保留一点活性
            if (type == JungleBoard.MOUSE && JungleBoard.isWater(toRow, toCol)) score += 4;
        }
        return score;
    }

    private static boolean isTrapForSide(int r, int c, int side) {
        if (!JungleBoard.isTrap(r, c)) return false;
        return (r <= 1) == (side == 2);
    }

    /**
     * 粗略安全评估：目标格周围是否有能吃掉自己的敌方棋子。
     * 只用于困难档，避免 AI 把高价值棋子送到对方口边。
     */
    private int safety(JungleBoard board, int[][] b, int side, int row, int col, int type) {
        int threat = 0;
        for (int[] d : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
            int r = row + d[0], c = col + d[1];
            if (!JungleBoard.inBoard(r, c) || b[r][c] == 0) continue;
            int other = b[r][c];
            if (other / 10 == side) continue;
            int otherType = other % 10;
            boolean canEat = otherType >= type
                    || (otherType == JungleBoard.MOUSE && type == JungleBoard.ELEPHANT);
            if (JungleBoard.isWater(row, col) != JungleBoard.isWater(r, c)) canEat = false;
            if (canEat) threat += 10;
        }
        return -threat;
    }
}
