package com.gomoku.dto;

import java.util.List;

/**
 * 房间列表项 DTO
 */
public class RoomListItemDto {
    private String roomId;
    private String state;
    private int playerCount;
    private int spectatorCount;
    private List<String> players;
    private String gameType;
    private int minPlayers;
    private int maxPlayers;
    private int targetPlayers;
    public int getMinPlayers() { return minPlayers; }
    public void setMinPlayers(int value) { minPlayers = value; }
    public int getMaxPlayers() { return maxPlayers; }
    public void setMaxPlayers(int value) { maxPlayers = value; }
    public int getTargetPlayers() { return targetPlayers; }
    public void setTargetPlayers(int value) { targetPlayers = value; }

    public RoomListItemDto() {}

    public RoomListItemDto(String roomId, String state, int playerCount, int spectatorCount, List<String> players, String gameType) {
        this.roomId = roomId;
        this.state = state;
        this.playerCount = playerCount;
        this.spectatorCount = spectatorCount;
        this.players = players;
        this.gameType = gameType;
    }

    public String getGameType() { return gameType; }
    public void setGameType(String gameType) { this.gameType = gameType; }

    // Getters and Setters
    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public int getPlayerCount() { return playerCount; }
    public void setPlayerCount(int playerCount) { this.playerCount = playerCount; }

    public int getSpectatorCount() { return spectatorCount; }
    public void setSpectatorCount(int spectatorCount) { this.spectatorCount = spectatorCount; }

    public List<String> getPlayers() { return players; }
    public void setPlayers(List<String> players) { this.players = players; }
}
