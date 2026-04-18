package com.gomoku.dto;

/**
 * 房间内玩家信息 DTO
 */
public class PlayerInfoDto {
    private String id;
    private String name;
    private int stone;
    private int wins;

    public PlayerInfoDto() {}

    public PlayerInfoDto(String id, String name, int stone, int wins) {
        this.id = id;
        this.name = name;
        this.stone = stone;
        this.wins = wins;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getStone() { return stone; }
    public void setStone(int stone) { this.stone = stone; }

    public int getWins() { return wins; }
    public void setWins(int wins) { this.wins = wins; }
}
