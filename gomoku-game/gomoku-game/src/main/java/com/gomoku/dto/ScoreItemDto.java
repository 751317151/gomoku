package com.gomoku.dto;

/**
 * 单个玩家分数 DTO
 */
public class ScoreItemDto {
    private String id;
    private String name;
    private int wins;
    private int losses;

    public ScoreItemDto() {}

    public ScoreItemDto(String id, String name, int wins, int losses) {
        this.id = id;
        this.name = name;
        this.wins = wins;
        this.losses = losses;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getWins() { return wins; }
    public void setWins(int wins) { this.wins = wins; }

    public int getLosses() { return losses; }
    public void setLosses(int losses) { this.losses = losses; }
}
