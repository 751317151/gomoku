package com.gomoku.game;

/**
 * 一次行动的指令载体 — 屏蔽不同玩法的差异
 *
 * 之所以要有这一层：棋类的行动是「坐标」，RPG 的行动是「技能序号」，
 * 卡牌是「手牌 index + 目标」。它们共用一个入参才能走同一条房间流程。
 *
 * 字段使用约定（按游戏类型各取所需，未用到的字段保持默认值）：
 * - 落子类（五子棋/黑白棋/围棋/四子棋）：row + col
 * - 棋子移动类（象棋）：fromRow + fromCol + row + col
 * - 非棋盘类（RPG/卡牌）：action = 行动序号
 */
public class MoveCommand {

    /** 目标行（落子/走子终点） */
    private final int row;
    /** 目标列 */
    private final int col;
    /** 起点行（仅棋子移动类），-1 表示无起点 */
    private final int fromRow;
    /** 起点列，-1 表示无起点 */
    private final int fromCol;
    /**
     * 行动序号（仅非棋盘类使用）。
     * 由引擎把它翻译成「第几个技能」，前端技能栏按下标发送。
     */
    private final int action;
    /**
     * 目标所属方（仅「需要选目标」的行动使用）：0 = 无目标，1 = 己方，2 = 对方。
     * 卡牌里「火球术」打对方（英雄或随从）、「鼓舞」强化己方随从。
     */
    private final int targetSide;
    /** 目标下标：-1 = 英雄，>=0 = 该方场上第几个随从 */
    private final int targetIndex;

    private MoveCommand(int row, int col, int fromRow, int fromCol, int action) {
        this(row, col, fromRow, fromCol, action, 0, -1);
    }

    private MoveCommand(int row, int col, int fromRow, int fromCol,
                        int action, int targetSide, int targetIndex) {
        this.row = row;
        this.col = col;
        this.fromRow = fromRow;
        this.fromCol = fromCol;
        this.action = action;
        this.targetSide = targetSide;
        this.targetIndex = targetIndex;
    }

    /** 落子/走到某格（无起点） */
    public static MoveCommand at(int row, int col) {
        return new MoveCommand(row, col, -1, -1, -1);
    }

    /** 从起点移动到终点（象棋） */
    public static MoveCommand from(int fromRow, int fromCol, int toRow, int toCol) {
        return new MoveCommand(toRow, toCol, fromRow, fromCol, -1);
    }

    /** 按行动序号出招（RPG/卡牌等非棋盘类，不需要选目标） */
    public static MoveCommand action(int actionIndex) {
        return new MoveCommand(-1, -1, -1, -1, actionIndex);
    }

    /**
     * 带目标的出招（卡牌：火球指定敌方英雄/随从，鼓舞指定己方随从）。
     *
     * @param targetSide  1 = 己方，2 = 对方
     * @param targetIndex -1 = 英雄，>=0 = 场上随从下标
     */
    public static MoveCommand withTarget(int actionIndex, int targetSide, int targetIndex) {
        return new MoveCommand(-1, -1, -1, -1, actionIndex, targetSide, targetIndex);
    }

    /** 是否携带目标 */
    public boolean hasTarget() {
        return targetSide == 1 || targetSide == 2;
    }

    public int getTargetSide() { return targetSide; }

    public int getTargetIndex() { return targetIndex; }

    /**
     * 攻击指令（卡牌）：用己方第 minionIndex 个随从攻击目标。
     *
     * 编码上用 action = ATTACK_MARKER(-2)、row = 随从下标、col = 目标下标
     * （-1 表示对方英雄）。这样不需要扩展协议字段，房间层据此直接分派。
     */
    public static MoveCommand attack(int minionIndex, int targetIndex) {
        return new MoveCommand(minionIndex, targetIndex, -1, -1, ATTACK_MARKER);
    }

    /** 是否为攻击指令 */
    public boolean hasAttack() {
        return action == ATTACK_MARKER;
    }

    /** 攻击者下标（己方随从） */
    public int getAttackMinion() {
        return row;
    }

    /** 攻击目标下标（-1 = 对方英雄） */
    public int getAttackTarget() {
        return col;
    }

    /** 攻击标记（与 GameServerHandler 的 ATTACK_MARKER 一致） */
    public static final int ATTACK_MARKER = -2;

    /**
     * 兼容旧调用：带起点的落子；起点为 -1 时退化为普通落子。
     * 保留是为了让既有代码与历史消息不需要改动。
     */
    public static MoveCommand of(int fromRow, int fromCol, int row, int col) {
        if (fromRow < 0 || fromCol < 0) {
            return at(row, col);
        }
        return from(fromRow, fromCol, row, col);
    }

    /** 是否携带合法起点 */
    public boolean hasOrigin() {
        return fromRow >= 0 && fromCol >= 0;
    }

    /** 是否按行动序号出招 */
    public boolean hasAction() {
        return action >= 0;
    }

    public int getRow() { return row; }
    public int getCol() { return col; }
    public int getFromRow() { return fromRow; }
    public int getFromCol() { return fromCol; }
    public int getAction() { return action; }

    @Override
    public String toString() {
        if (hasAction()) {
            return hasTarget()
                    ? "MoveCommand{action=" + action + ", target=" + targetSide + ":" + targetIndex + "}"
                    : "MoveCommand{action=" + action + "}";
        }
        if (hasOrigin()) return "MoveCommand{" + fromRow + "," + fromCol + " -> " + row + "," + col + "}";
        return "MoveCommand{" + row + "," + col + "}";
    }
}