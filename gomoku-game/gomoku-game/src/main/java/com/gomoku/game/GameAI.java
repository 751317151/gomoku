package com.gomoku.game;

/**
 * 游戏 AI 抽象 — 每种玩法提供一个实现
 *
 * 入口统一是 {@link #decide}：不管底下是落子、走子还是放技能，
 * 都返回同一种 {@link MoveCommand}，房间层不需要知道玩法细节。
 *
 * 实现方按自己属于哪一类覆写对应方法：
 * - 棋盘玩法覆写 {@link #getBestMove}（返回坐标数组）
 * - 非棋盘玩法覆写 {@link #chooseAction}（返回行动序号）
 * 两者都保持默认实现时，{@link #decide} 返回 null，房间会把它当作「无可用行动」。
 */
public interface GameAI {

    /**
     * 计算本回合的行动指令。
     *
     * @return null 表示无可用行动（例如围棋无处可下）
     */
    default MoveCommand decide(GameEngine engine, int side, int difficulty) {
        if (engine instanceof BoardGameEngine) {
            int[] move = getBestMove((BoardGameEngine) engine, side, difficulty);
            if (move == null) return null;
            // 象棋等走子类返回 4 元素，落子类返回 2 元素
            return move.length >= 4
                    ? MoveCommand.from(move[0], move[1], move[2], move[3])
                    : MoveCommand.at(move[0], move[1]);
        }
        int action = chooseAction(engine, side, difficulty);
        return action >= 0 ? MoveCommand.action(action) : null;
    }

    /**
     * 棋盘玩法：计算最佳落子/走子
     *
     * @param engine     引擎快照视图（实现方只读取盘面，不修改）
     * @param side       AI 执子方
     * @param difficulty 难度（2=简单, 4=中等, 6=困难）
     * @return {row, col} 或象棋的 {fromRow, fromCol, toRow, toCol}；null 表示无子可下
     */
    default int[] getBestMove(BoardGameEngine engine, int side, int difficulty) {
        return null;
    }

    /**
     * 非棋盘玩法：计算最佳行动序号
     *
     * @return 行动下标；-1 表示无可用行动
     */
    default int chooseAction(GameEngine engine, int side, int difficulty) {
        return -1;
    }

    /**
     * 一回合多行动的玩法（{@link GameEngine#autoEndsTurn()} 为 false）：
     * 除了出牌，AI 还需要决定「是否结束回合」。
     *
     * 默认返回 false（不结束），需要该能力的玩法覆写它。
     * 只返回 true 时房间会调 endTurn，让 AI 的回合真正收尾。
     */
    default boolean shouldEndTurn(GameEngine engine, int side, int difficulty) {
        return false;
    }
}
