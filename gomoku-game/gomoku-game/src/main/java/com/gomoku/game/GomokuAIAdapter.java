package com.gomoku.game;

/**
 * 五子棋 AI 适配器 — 复用现有 GomokuAI 静态引擎
 */
final class GomokuAIAdapter implements GameAI {

    static final GomokuAIAdapter INSTANCE = new GomokuAIAdapter();

    private GomokuAIAdapter() {}

    @Override
    public int[] getBestMove(BoardGameEngine engine, int side, int difficulty) {
        if (!(engine instanceof GomokuBoard)) return null;
        return GomokuAI.getBestMove((GomokuBoard) engine, side, difficulty);
    }
}
