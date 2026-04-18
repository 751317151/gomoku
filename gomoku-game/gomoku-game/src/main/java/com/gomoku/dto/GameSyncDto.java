package com.gomoku.dto;

/**
 * 游戏状态同步 DTO（观战/重连用）
 */
public class GameSyncDto {
    private String state;
    private RoomDataDto roomData;
    private int[][] board;
    private int moveCount;

    public GameSyncDto() {}

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public RoomDataDto getRoomData() { return roomData; }
    public void setRoomData(RoomDataDto roomData) { this.roomData = roomData; }

    public int[][] getBoard() { return board; }
    public void setBoard(int[][] board) { this.board = board; }

    public int getMoveCount() { return moveCount; }
    public void setMoveCount(int moveCount) { this.moveCount = moveCount; }
}
