package com.gomoku.game;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class UltimateTicTacToeAI implements GameAI {

    static final UltimateTicTacToeAI INSTANCE = new UltimateTicTacToeAI();
    private static final int WIN = 1_000_000;
    private static final int NODE_LIMIT = 24_000;

    private UltimateTicTacToeAI() {}

    @Override
    public int[] getBestMove(BoardGameEngine engine, int side, int difficulty) {
        if (!(engine instanceof UltimateTicTacToeBoard) || engine.isOver()
                || engine.getCurrentTurn() != side) return null;
        UltimateTicTacToeBoard position = ((UltimateTicTacToeBoard) engine).copyForSearch();
        List<int[]> moves = orderedMoves(position);
        if (moves.isEmpty()) return null;
        if (difficulty <= 2) return moves.get(ThreadLocalRandom.current().nextInt(moves.size())).clone();

        int[] best = moves.get(0);
        Search search = new Search();
        int maxDepth = difficulty >= 6 ? 4 : 2;
        for (int depth = 1; depth <= maxDepth; depth++) {
            int bestScore = -WIN * 2;
            int[] candidate = best;
            for (int[] move : moves) {
                UltimateTicTacToeBoard child = position.copyForSearch();
                child.placeStone(move[0], move[1], side);
                int score = -search.negamax(child, depth - 1, -WIN * 2, -bestScore);
                if (search.exhausted) break;
                if (score > bestScore) {
                    bestScore = score;
                    candidate = move;
                }
            }
            if (search.exhausted) break;
            best = candidate;
            if (bestScore >= WIN) break;
        }
        return best.clone();
    }

    private static List<int[]> orderedMoves(UltimateTicTacToeBoard position) {
        List<int[]> moves = position.legalMoves();
        moves.sort(Comparator.comparingInt((int[] move) ->
                cellWeight(move[0] / 3, move[1] / 3) * 3 + cellWeight(move[0] % 3, move[1] % 3)).reversed());
        return moves;
    }

    private static int cellWeight(int row, int col) {
        return row == 1 && col == 1 ? 4 : row != 1 && col != 1 ? 2 : 1;
    }

    private static int evaluate(UltimateTicTacToeBoard position) {
        int side = position.getCurrentTurn();
        int[][] board = position.getBoardSnapshot();
        int[][] subBoards = position.getSubBoardSnapshot();
        int score = lineScore(subBoards, 0, 0, side, 70, 600);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int value = subBoards[row][col];
                if (value == 1 || value == 2) {
                    score += (value == side ? 1 : -1) * 200 * cellWeight(row, col);
                } else if (value == 0) {
                    score += lineScore(board, row * 3, col * 3, side, 2, 16);
                }
            }
        }
        return score;
    }

    private static int lineScore(int[][] board, int startRow, int startCol, int side, int single, int pair) {
        int score = 0;
        for (int[] line : UltimateTicTacToeBoard.LINES) {
            int mine = 0;
            int theirs = 0;
            boolean blocked = false;
            for (int index : line) {
                int value = board[startRow + index / 3][startCol + index % 3];
                if (value == side) mine++;
                else if (value == 3 - side) theirs++;
                else if (value == 3) blocked = true;
            }
            if (blocked || mine > 0 && theirs > 0) continue;
            if (mine == 1) score += single;
            else if (mine == 2) score += pair;
            if (theirs == 1) score -= single;
            else if (theirs == 2) score -= pair;
        }
        return score;
    }

    private static final class Search {
        private int nodes;
        private boolean exhausted;

        private int negamax(UltimateTicTacToeBoard position, int depth, int alpha, int beta) {
            if (position.isOver()) {
                Integer winner = position.getWinner();
                return winner == null ? 0 : (winner == position.getCurrentTurn() ? WIN + depth : -WIN - depth);
            }
            if (++nodes > NODE_LIMIT) {
                exhausted = true;
                return 0;
            }
            if (depth == 0) return evaluate(position);
            int best = -WIN * 2;
            for (int[] move : orderedMoves(position)) {
                UltimateTicTacToeBoard child = position.copyForSearch();
                child.placeStone(move[0], move[1], position.getCurrentTurn());
                int score = -negamax(child, depth - 1, -beta, -alpha);
                if (exhausted) return 0;
                best = Math.max(best, score);
                alpha = Math.max(alpha, score);
                if (alpha >= beta) break;
            }
            return best;
        }
    }
}
