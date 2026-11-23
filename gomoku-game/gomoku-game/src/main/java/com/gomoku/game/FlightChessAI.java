package com.gomoku.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 飞行棋 AI：从服务端已掷出的骰子点数和合法走法中选择一枚棋子。
 *
 * 三档难度真的不同：
 * - 简单：随机走一枚可走的棋子；
 * - 中等：优先撞人、优先到达终点，再按推进距离选择；
 * - 困难：在同分候选中优先选择不会被对手立刻撞到的安全格，并保留更多前线棋子。
 */
public final class FlightChessAI implements GameAI {

    public static final FlightChessAI INSTANCE = new FlightChessAI();

    private final Random random = new Random();

    private FlightChessAI() {}

    @Override
    public int[] getBestMove(BoardGameEngine engine, int side, int difficulty) {
        if (!(engine instanceof FlightChessBoard)) return null;
        FlightChessBoard board = (FlightChessBoard) engine;
        List<int[]> legal = board.legalMoves(side);
        if (legal.isEmpty()) return null;
        if (difficulty <= 2) {
            int[] pick = legal.get(random.nextInt(legal.size()));
            return new int[]{pick[0], pick[1], pick[2], pick[3]};
        }

        int[] best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int[] move : legal) {
            double score = score(board, side, move, difficulty);
            if (score > bestScore) {
                bestScore = score;
                best = move;
            }
        }
        return best == null ? null : new int[]{best[0], best[1], best[2], best[3]};
    }

    private double score(FlightChessBoard board, int side, int[] move, int difficulty) {
        int target = landingPosition(move);
        double score = 0;
        int piece = move.length > 4 ? move[4] : board.findOwnPieceAt(side, move[0], move[1]);

        if (target == FlightChessBoard.HOME_POS) score += 1000;
        else if (target > 0) score += target;

        if (piece >= 0) {
            int from = board.getPiecePosition(side, piece);
            if (from == 0) score += 60;
            if (target == FlightChessBoard.HOME_POS) score += 200;
            // 跳格收益直接算进落点；撞子则显著加分。
            Integer jumpTo = FlightChessBoard.jumpTargetForTest(target);
            if (jumpTo != null) score += jumpTo - target;
            // 撞回对手的价值高于单纯前进：对手损失全部已走距离并退回基地。
            if (board.hasOpponentAt(target, side)) score += 90;
        }

        if (difficulty >= 6) {
            score += safetyBonus(board, side, target) * 3;
            if (target > FlightChessBoard.TRACK_CELLS - 12) score += 40;
        }
        return score;
    }

    private int landingPosition(int[] move) {
        if (move[2] == 7 && move[3] == 7) return FlightChessBoard.HOME_POS;
        int ring = FlightChessBoard.ringPosition(move[2], move[3]);
        return ring;
    }

    /** 距离对手棋子越远越安全；被对手 1–6 步覆盖则扣分。 */
    private int safetyBonus(FlightChessBoard board, int side, int target) {
        if (target <= 0 || target > FlightChessBoard.TRACK_CELLS) return 0;
        int nearestThreat = 99;
        for (int other = 1; other <= board.getPlayerCount(); other++) {
            if (other == side) continue;
            for (int p = 0; p < FlightChessBoard.PIECES_PER_PLAYER; p++) {
                int pos = board.getPiecePosition(other, p);
                if (pos <= 0) continue;
                int gap = target - pos;
                if (gap > 0 && gap <= 6) nearestThreat = Math.min(nearestThreat, gap);
            }
        }
        if (nearestThreat == 99) return 12;
        if (nearestThreat <= 2) return -20;
        return 4 - nearestThreat;
    }
}
