package com.gomoku.game;

import java.util.List;
import java.util.Random;

/**
 * 暗棋 AI：只读取公开盘面，不偷看背面棋子身份。
 *
 * 三档难度：
 * - 简单：在「翻子」与「走子」里随机，偶尔吃子；
 * - 中等：优先吃高价值明棋，其次翻子，避免走进明显可被反吃的格；
 * - 困难：额外计算炮架、马腿与对方明棋威胁，优先用低价值棋子换高价值目标。
 */
public final class DarkChessAI implements GameAI {

    public static final DarkChessAI INSTANCE = new DarkChessAI();

    private final Random random = new Random();

    private DarkChessAI() {}

    @Override
    public int[] getBestMove(BoardGameEngine engine, int side, int difficulty) {
        if (!(engine instanceof DarkChessBoard)) return null;
        DarkChessBoard board = (DarkChessBoard) engine;
        List<int[]> flips = board.legalFlips(side);
        List<int[]> moves = board.legalMoves(side);
        if (flips.isEmpty() && moves.isEmpty()) return null;

        if (difficulty <= 2) {
            if (!moves.isEmpty() && (!flips.isEmpty() ? random.nextDouble() < 0.35 : true)) {
                int[] pick = moves.get(random.nextInt(moves.size()));
                return new int[]{pick[0], pick[1], pick[2], pick[3]};
            }
            if (!flips.isEmpty()) {
                int[] pick = flips.get(random.nextInt(flips.size()));
                return new int[]{pick[0], pick[1]};
            }
            int[] pick = moves.get(random.nextInt(moves.size()));
            return new int[]{pick[0], pick[1], pick[2], pick[3]};
        }

        int[][] visible = board.getVisibleBoardSnapshot();
        int[] bestFlip = null;
        double bestFlipScore = Double.NEGATIVE_INFINITY;
        for (int[] flip : flips) {
            double score = 6 + random.nextDouble() * 4;
            // 角落翻子后机动性差，中心区域更容易接应
            if (flip[0] == 0 || flip[0] == DarkChessBoard.ROWS - 1) score -= 1.5;
            if (flip[1] == 0 || flip[1] == DarkChessBoard.COLS - 1) score -= 1.5;
            if (difficulty >= 6) {
                score += 2 - Math.abs(flip[0] - 1.5) - Math.abs(flip[1] - 3.5) * 0.3;
            }
            if (score > bestFlipScore) {
                bestFlipScore = score;
                bestFlip = flip;
            }
        }

        int[] bestMove = null;
        double bestMoveScore = Double.NEGATIVE_INFINITY;
        for (int[] move : moves) {
            double score = scoreMove(board, visible, side, move, difficulty);
            if (difficulty < 6) score += random.nextDouble() * 3;
            if (score > bestMoveScore) {
                bestMoveScore = score;
                bestMove = move;
            }
        }

        if (bestMove != null && (bestFlip == null || bestMoveScore >= bestFlipScore)) {
            return new int[]{bestMove[0], bestMove[1], bestMove[2], bestMove[3]};
        }
        return bestFlip == null ? null : new int[]{bestFlip[0], bestFlip[1]};
    }

    private double scoreMove(DarkChessBoard board, int[][] visible, int side,
                             int[] move, int difficulty) {
        int fromRow = move[0], fromCol = move[1], toRow = move[2], toCol = move[3];
        int piece = visible[fromRow][fromCol];
        int type = piece % 10;
        int target = visible[toRow][toCol];
        double score = 0;

        if (target != 0) {
            int targetType = target % 10;
            score += 18 + DarkChessBoard.rank(targetType) * 7;
            if (targetType == DarkChessBoard.KING) score += 40;
            // 用低价值棋子换高价值目标更划算
            score += (type - targetType) * 2;
        } else {
            score += 1;
        }

        // 推进到更靠近对方半场的位置，给后续吃子留空间
        int forward = side == 1 ? fromRow - toRow : toRow - fromRow;
        score += forward * 1.5;

        if (difficulty >= 6) {
            score += safety(visible, side, toRow, toCol, type) * 2.5;
            // 车、炮在开阔线上价值更高，避免自己堵死
            if (type == DarkChessBoard.ROOK || type == DarkChessBoard.CANNON) {
                score += openLines(visible, toRow, toCol) * 0.8;
            }
        }
        return score;
    }

    /** 目标格是否处在对方明棋的直接威胁下；只用于困难档。 */
    private int safety(int[][] visible, int side, int row, int col, int type) {
        int threat = 0;
        for (int r = 0; r < DarkChessBoard.ROWS; r++) {
            for (int c = 0; c < DarkChessBoard.COLS; c++) {
                int other = visible[r][c];
                if (other == 0 || other / 10 == side) continue;
                int otherType = other % 10;
                if (canReach(visible, r, c, row, col, otherType)
                        && DarkChessBoard.canCapture(otherType, type)) {
                    threat += Math.max(1, DarkChessBoard.rank(otherType));
                }
            }
        }
        return -threat;
    }

    /** 粗略判断某类棋子能否从 (r,c) 到达目标格，忽略吃子大小。 */
    private boolean canReach(int[][] b, int r, int c, int tr, int tc, int type) {
        int dr = tr - r, dc = tc - c;
        switch (type) {
            case DarkChessBoard.KING:
                return Math.abs(dr) + Math.abs(dc) == 1;
            case DarkChessBoard.GUARD:
                return Math.abs(dr) == 1 && Math.abs(dc) == 1;
            case DarkChessBoard.ELEPHANT:
                if (Math.abs(dr) != 2 || Math.abs(dc) != 2) return false;
                return b[r + dr / 2][c + dc / 2] == 0;
            case DarkChessBoard.ROOK:
                return clearLine(b, r, c, tr, tc);
            case DarkChessBoard.HORSE:
                if (Math.abs(dr) == 2 && Math.abs(dc) == 1) {
                    return b[r + dr / 2][c] == 0;
                }
                if (Math.abs(dr) == 1 && Math.abs(dc) == 2) {
                    return b[r][c + dc / 2] == 0;
                }
                return false;
            case DarkChessBoard.CANNON:
                // 炮的「无炮架直走」只能落到空格；目标有明棋时必须有恰好一个炮架。
                if (b[tr][tc] == 0) return clearLine(b, r, c, tr, tc);
                return screenCount(b, r, c, tr, tc) == 1;
            case DarkChessBoard.SOLDIER:
                // 这里不知道 side，按「一步正交」保守估算
                return Math.abs(dr) + Math.abs(dc) == 1;
            default:
                return false;
        }
    }

    private boolean clearLine(int[][] b, int r, int c, int tr, int tc) {
        if (r != tr && c != tc) return false;
        int dr = Integer.compare(tr, r), dc = Integer.compare(tc, c);
        int rr = r + dr, cc = c + dc;
        while (rr != tr || cc != tc) {
            if (b[rr][cc] != 0) return false;
            rr += dr;
            cc += dc;
        }
        return true;
    }

    private int screenCount(int[][] b, int r, int c, int tr, int tc) {
        if (r != tr && c != tc) return -1;
        int dr = Integer.compare(tr, r), dc = Integer.compare(tc, c);
        int count = 0;
        int rr = r + dr, cc = c + dc;
        while (rr != tr || cc != tc) {
            if (b[rr][cc] != 0) count++;
            rr += dr;
            cc += dc;
        }
        return count;
    }

    private int openLines(int[][] b, int row, int col) {
        int lines = 0;
        for (int[] d : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
            int r = row + d[0], c = col + d[1];
            while (r >= 0 && r < DarkChessBoard.ROWS && c >= 0 && c < DarkChessBoard.COLS
                    && b[r][c] == 0) {
                lines++;
                r += d[0];
                c += d[1];
            }
        }
        return lines;
    }
}
