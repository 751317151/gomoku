package com.gomoku.game;

/**
 * 棋盘类引擎 — 在通用对战骨架上补出「盘面」语义
 *
 * 五子棋 / 黑白棋 / 围棋 / 象棋 / 四子棋都实现本接口，房间与 AI 通过它拿盘面。
 * 非棋盘玩法（回合制 RPG 等）只实现 {@link GameEngine}，不需要假装自己有 int[][]。
 *
 * 之所以拆成两层：原先这些方法全挤在 GameEngine 上，导致任何新玩法都得
 * 提供一个自己不用的 getBoardSnapshot()，而 GameRoom 又会无条件去取盘面塞进同步包。
 * 拆开之后，房间问盘面前先判 {@code instanceof BoardGameEngine}，非棋盘玩法自然不会走到那段。
 */
public interface BoardGameEngine extends GameEngine {

    /** 盘面行数 */
    int getRows();

    /** 盘面列数 */
    int getCols();

    /**
     * 落子 / 走子到目标格（无起点）。
     *
     * @return true 该手被接受
     */
    boolean placeStone(int row, int col, int side);

    /**
     * 带起点的走子（象棋等棋子移动类）。
     *
     * 默认实现只在「没有起点」时退化为 {@link #placeStone(int, int, int)}，
     * 带起点则返回 false —— 落子类游戏没有走子概念，不应静默忽略起点后当成落子处理，
     * 那会让一条错误指令变成一手合法落子。
     */
    default boolean placeStone(int fromRow, int fromCol, int toRow, int toCol, int side) {
        if (fromRow < 0 || fromCol < 0) {
            return placeStone(toRow, toCol, side);
        }
        return false;
    }

    /** (row, col) 是否为 side 方的棋子 */
    default boolean isOwnPieceAt(int row, int col, int side) {
        return false;
    }

    /** 盘面快照（防御性拷贝，调用方修改不影响引擎内部状态） */
    int[][] getBoardSnapshot();

    /**
     * 最后一手的连线（五子棋五星连珠 / 四子棋四连），无连线返回 null。
     * 围棋、象棋这类不吃「连线」概念的玩法保持 null。
     */
    default int[][] getWinLine() {
        return null;
    }

    /**
     * 把通用行动指令翻译成落子/走子。
     *
     * 棋类的行动天然就是坐标，所以这里能给出通用实现；
     * 子类若对指令有额外约束（例如围棋的 pass）可自行覆写。
     */
    @Override
    default boolean applyMove(MoveCommand command, int side) {
        if (command == null) return false;
        if (command.hasAction()) {
            // 棋盘玩法收到「行动序号」是无意义指令，直接拒绝而不是猜一个坐标
            return false;
        }
        return placeStone(command.getFromRow(), command.getFromCol(),
                          command.getRow(), command.getCol(), side);
    }
}
