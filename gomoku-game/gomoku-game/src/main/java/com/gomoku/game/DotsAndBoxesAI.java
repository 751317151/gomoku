package com.gomoku.game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** 简单随机，中等优先围格并避免送第三边，困难以 MaxN 处理多人和连续行动。 */
public final class DotsAndBoxesAI implements GameAI {
    static final DotsAndBoxesAI INSTANCE = new DotsAndBoxesAI();
    private static final int NODE_LIMIT = 18_000;
    private DotsAndBoxesAI() {}
    @Override public int[] getBestMove(BoardGameEngine engine, int side, int difficulty) {
        if (!(engine instanceof DotsAndBoxesBoard) || engine.isOver() || engine.getCurrentTurn() != side) return null;
        DotsAndBoxesBoard position = ((DotsAndBoxesBoard) engine).copyForSearch();
        List<int[]> moves = ordered(position);
        if (moves.isEmpty()) return null;
        if (difficulty <= 2) return moves.get(ThreadLocalRandom.current().nextInt(moves.size())).clone();
        int[] best = moves.get(0);
        if (difficulty < 6) return best.clone();
        Search search = new Search();
        for (int depth = 1; depth <= Math.min(8,moves.size()); depth++) {
            int bestValue = Integer.MIN_VALUE; int[] candidate = best;
            for (int[] move : moves) {
                DotsAndBoxesBoard child = position.copyForSearch(); child.placeStone(move[0],move[1],side);
                int value = search.maxN(child,depth-1)[side];
                if (search.exhausted) break;
                if (value > bestValue) { bestValue = value; candidate = move; }
            }
            // 达到预算只保留已经完整比较全部候选的深度。
            if (search.exhausted) break;
            best = candidate;
        }
        return best.clone();
    }
    private static List<int[]> ordered(DotsAndBoxesBoard position) {
        List<int[]> ranked = new ArrayList<>();
        for (int[] move : position.legalMoves()) ranked.add(new int[]{move[0],move[1],
                position.adjacentWithSides(move[0],move[1],3)*100 - position.adjacentWithSides(move[0],move[1],2)*20});
        ranked.sort(Comparator.comparingInt((int[] move)->move[2]).reversed());
        return ranked;
    }
    private static int[] evaluate(DotsAndBoxesBoard position) {
        int[] utility = new int[DotsAndBoxesBoard.MAX_PLAYERS+1];
        int capture = 0;
        if (!position.isOver()) for (int[] move : position.legalMoves())
            capture = Math.max(capture,position.adjacentWithSides(move[0],move[1],3));
        for (int side = 1; side <= position.getPlayerCount(); side++) {
            if (!position.isActive(side)) { utility[side] = -100_000; continue; }
            int otherBest = 0;
            for (int other = 1; other <= position.getPlayerCount(); other++)
                if (other != side && position.isActive(other)) otherBest = Math.max(otherBest,position.getScore(other));
            utility[side] = position.getScore(side)*1000 - otherBest*900
                    + (position.getCurrentTurn()==side ? capture*120 : -capture*100);
        }
        return utility;
    }
    private static final class Search {
        int nodes; boolean exhausted;
        int[] maxN(DotsAndBoxesBoard position, int depth) {
            if (++nodes > NODE_LIMIT) { exhausted = true; return new int[DotsAndBoxesBoard.MAX_PLAYERS+1]; }
            if (position.isOver() || depth == 0) return evaluate(position);
            int actor = position.getCurrentTurn(); int[] best = null;
            for (int[] move : ordered(position)) {
                DotsAndBoxesBoard child = position.copyForSearch(); child.placeStone(move[0],move[1],actor);
                int[] value = maxN(child,depth-1);
                if (exhausted) return value;
                if (best == null || value[actor] > best[actor]) best = value;
            }
            return best == null ? evaluate(position) : best;
        }
    }
}
