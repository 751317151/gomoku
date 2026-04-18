package com.gomoku.dto;

import java.util.List;

/**
 * 房间数据 DTO（用于 GAME_START/ROOM_INFO 等消息的 data 字段）
 */
public class RoomDataDto {
    private List<PlayerInfoDto> players;
    private int spectatorCount;
    private int currentTurn;

    public RoomDataDto() {}

    public List<PlayerInfoDto> getPlayers() { return players; }
    public void setPlayers(List<PlayerInfoDto> players) { this.players = players; }

    public int getSpectatorCount() { return spectatorCount; }
    public void setSpectatorCount(int spectatorCount) { this.spectatorCount = spectatorCount; }

    public int getCurrentTurn() { return currentTurn; }
    public void setCurrentTurn(int currentTurn) { this.currentTurn = currentTurn; }
}
