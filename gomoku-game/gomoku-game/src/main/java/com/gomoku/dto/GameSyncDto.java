package com.gomoku.dto;

/**
 * 游戏状态同步 DTO（观战/重连用）
 *
 * board 与 view 二选一：
 * - 棋盘类填 board（int[][]），view 为 null
 * - 非棋盘类（回合制 RPG 等）填 view，board 为 null
 * 前端按 gameType 找到对应渲染模块后，只读它认识的那个字段。
 */
public class GameSyncDto {
    private String state;
    private RoomDataDto roomData;
    private int[][] board;
    private Object view;
    private int moveCount;
    private int moveSeq;
    private java.util.Map<String, Object> timers;
    private com.gomoku.model.GameMessage gameOver;
    private String gameType;

    public GameSyncDto() {}

    public String getGameType() { return gameType; }
    public void setGameType(String gameType) { this.gameType = gameType; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public RoomDataDto getRoomData() { return roomData; }
    public void setRoomData(RoomDataDto roomData) { this.roomData = roomData; }

    public int[][] getBoard() { return board; }
    public void setBoard(int[][] board) { this.board = board; }

    public Object getView() { return view; }
    public void setView(Object view) { this.view = view; }

    public int getMoveCount() { return moveCount; }
    public void setMoveCount(int moveCount) { this.moveCount = moveCount; }
    public int getMoveSeq() { return moveSeq; }
    public void setMoveSeq(int value) { moveSeq = value; }
    public java.util.Map<String, Object> getTimers() { return timers; }
    public void setTimers(java.util.Map<String, Object> value) { timers = value; }
    public com.gomoku.model.GameMessage getGameOver() { return gameOver; }
    public void setGameOver(com.gomoku.model.GameMessage value) { gameOver = value; }
}
