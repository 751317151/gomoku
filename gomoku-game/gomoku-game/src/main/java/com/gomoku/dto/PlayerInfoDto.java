package com.gomoku.dto;

/**
 * 房间内玩家信息 DTO
 */
public class PlayerInfoDto {
    private String id;
    private String name;
    private int stone;
    private int wins;
    private int elo;
    /** 当前赛季段位（青铜…王者）；0 分或游客可能为 null */
    private String tier;
    /** 队伍标识（PTY-XXXX）；不在队伍时为 null。同一队伍的队员在座位上显示同一徽标 */
    private String partyId;
    /** 电脑席位：结算页据此隐藏「匹配下一局」等真人入口，座位本身仍要可见。 */
    private boolean ai;

    public PlayerInfoDto() {}

    public PlayerInfoDto(String id, String name, int stone, int wins, int elo) {
        this.id = id;
        this.name = name;
        this.stone = stone;
        this.wins = wins;
        this.elo = elo;
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

    public int getElo() { return elo; }
    public void setElo(int elo) { this.elo = elo; }

    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }

    public String getPartyId() { return partyId; }
    public void setPartyId(String partyId) { this.partyId = partyId; }

    public boolean isAi() { return ai; }
    public void setAi(boolean ai) { this.ai = ai; }
}
