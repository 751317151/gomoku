package com.gomoku.game;

import java.util.List;

/**
 * 通用对战引擎 — 与具体玩法无关的对战骨架
 *
 * 这一层只管「哪些座位可以行动、谁赢、走了多少步、耗时多少」，不假设盘面长什么样。
 * 因此同一套房间流程可以承载完全不同的玩法：
 * - 棋类：盘面是 int[][]，见 {@link BoardGameEngine}
 * - 回合制 RPG 对战：盘面是双方 HP/MP/状态，见 {@link RpgBattleEngine}
 * - 卡牌 / 问答等：只要实现本接口，房间、计时、ELO、存档、回放全部复用
 *
 * 通用约定：
 * - side 为从 1 起的座位号；双人棋类中 1/2 即先手/后手
 * - 出招由引擎校验（轮次、规则合法性），不接受时返回 false
 * - {@link #getMoveSeq()} 是服务端权威手数，客户端只用它做消息幂等与错位校准
 */
public interface GameEngine {

    /** 自动停一手的效果标记（Reversi 自动 pass） */
    int EFFECT_AUTO_PASS_ROW = -1;

    /** 回放/历史中显式停一手的位置标记（围棋 PASS） */
    int PASS_MOVE_ROW = -2;

    GameType type();

    /** 开局前配置人数；旧的双人引擎拒绝扩容。 */
    default void configurePlayers(int count) {
        if (count != 2) throw new IllegalArgumentException("该引擎仅支持双人");
    }

    /** 多胜者/并列获胜由引擎决定。 */
    default List<Integer> getWinningSides() {
        Integer side = getWinner();
        return side == null ? java.util.Collections.emptyList() : java.util.Collections.singletonList(side);
    }

    /**
     * 平局/和局的座位（例如 21 点玩家点数与庄家相同）。
     *
     * 默认空列表，表示「没有胜者就是整体平局」。卡牌类玩法需要单独标注平局座位，
     * 否则房间只能把所有非胜者都记成负，战绩会失真。
     */
    default List<Integer> getDrawingSides() { return java.util.Collections.emptyList(); }

    /** 多人玩法自行处理淘汰；false 表示房间应终止本局。 */
    default boolean forfeit(int side) { return false; }

    /** 重置到初始局面 */
    void reset();

    /**
     * 执行一次行动。
     *
     * 棋类解释为落子/走子；非棋类（RPG 等）由实现解释 {@link MoveCommand#getAction()}。
     *
     * @return true 行动被接受
     */
    boolean applyMove(MoveCommand command, int side);

    /**
     * 显式停一手（围棋用），默认不支持
     */
    default boolean pass(int side) { return false; }

    /**
     * 一手是否代表「整个回合」。
     *
     * 棋类与 RPG 都是「一次行动 = 一个回合」，落子/出招后立刻换手，返回 true（默认）。
     * 卡牌不是：一回合内可以连续出若干张牌，玩家自己决定何时结束。
     * 返回 false 时，房间在收到一次行动后**不换手**，仍由同一方继续行动，
     * 直到该方显式 {@link #endTurn(int)}（卡牌里是「结束回合」按钮/超时）。
     */
    default boolean autoEndsTurn() { return true; }

    /**
     * 显式结束本方回合。只有 {@link #autoEndsTurn()} 为 false 的玩法需要实现。
     *
     * @return true 表示回合已结束并交付给对手
     */
    default boolean endTurn(int side) { return false; }

    /**
     * 一轮是否允许多个座位同时提交。
     *
     * 默认 false。答题和比大小返回 true：引擎通过 isAwaitingAction 公开待提交座位，
     * 通过 getActionRound 标识整轮完成；房间据此为各座位独立计时与调度 AI。
     *
     * 卡牌虽然也是一回合多行动，但同一时刻只有一方在动，所以保持 false。
     */
    default boolean simultaneousTurn() { return false; }

    /** 当前是否仍需该座位提交。本方法仅公开提交状态，不暴露选择内容。 */
    default boolean isAwaitingAction(int side) { return !isOver() && getCurrentTurn() == side; }

    /** 同时行动的轮次标识；只有整轮完成才变化，用于重置各席位时限。 */
    default int getActionRound() { return 0; }

    /**
     * 注入玩家昵称（房间在开局时调用）。
     *
     * 做成接口默认方法，是为了避免房间层堆 if-else：
     * 之前每加一个非棋盘玩法，都得回 GameRoom 里补一条 instanceof 分支，
     * 漏掉了就会有玩法显示「第 1 方」这种占位文案（Nim 就漏了）。
     * 信息全公开的棋类可以忽略这个调用。
     */
    default void setPlayerName(int side, String name) {}

    /** 当前轮到的座位；同时行动玩法中仅作为一个待提交座位的兼容字段。 */
    int getCurrentTurn();

    /** 是否已终局（胜负已分或和棋） */
    boolean isOver();

    /** 唯一胜者座位，并列、平局或未结束返回 null */
    Integer getWinner();

    /** 终局附加说明（围棋目数 / RPG 剩余血量等），无则返回 null */
    default String getScoreSummary() { return null; }

    /**
     * 对局记录：每手一条。编码由实现决定（棋类为坐标数组），供回放与存档使用。
     */
    List<int[]> getMoveHistory();

    /** 最后一手造成的盘面变化（棋类的提子/翻转；非棋类可为空列表） */
    List<int[]> getLastMoveEffects();

    /** 已走手数 */
    int getMoveCount();

    /** 全局递增落子序列号（消息幂等） */
    int getMoveSeq();

    /** 每步限时（秒） */
    int getStepTimeSeconds();

    /** 每方总时限（秒） */
    int getTotalTimeSeconds();

    /**
     * 面向客户端的状态快照（JSON 可序列化）。
     *
     * 房间把这份数据直接塞进 GAME_SYNC / GAME_MOVE 的 data 字段，
     * 前端按 gameType 找到对应渲染模块自行解释，服务端不关心内容结构。
     *
     * @param viewerSide 观察者所在座位（从 1 起）。信息完全公开的玩法（棋类、RPG）
     *                   可以忽略它；有隐藏信息的玩法（卡牌手牌）必须按视角裁剪，
     *                   否则对手的牌会随同步包一起发出去。
     *                   观战者传 0，表示只能看到公开信息。
     */
    default Object getViewState(int viewerSide) { return null; }

    /**
     * 无视角版本（兼容既有调用）。默认按「公开视角」处理。
     * 有隐藏信息的玩法应让调用方改用带视角的重载。
     */
    default Object getViewState() { return getViewState(0); }
}
