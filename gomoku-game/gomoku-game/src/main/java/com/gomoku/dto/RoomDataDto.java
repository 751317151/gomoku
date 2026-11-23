package com.gomoku.dto;

import java.util.List;

/**
 * 房间数据 DTO（用于 GAME_START/ROOM_INFO 等消息的 data 字段）
 */
public class RoomDataDto {
    private List<PlayerInfoDto> players;
    private int spectatorCount;
    /** 观战者昵称列表（公开信息，让房间内玩家知道谁在看） */
    private List<String> spectators;
    private int currentTurn;
    private String actionVersion;
    public String getActionVersion() { return actionVersion; }
    public void setActionVersion(String value) { actionVersion = value; }
    private String gameType;
    private int minPlayers;
    private int maxPlayers;
    private int targetPlayers;
    /**
     * 是否为房号邀请/好友约战建的私有房。
     *
     * 客户端据此决定结算页是否提供「匹配下一局」：私有房重开应留在原桌，
     * 公开匹配房才适合直接回到匹配队列。判定必须由服务端下发，
     * 否则重连或服务端安排进房后，前端本地标记会失真。
     */
    private boolean privateRoom;
    /** 当前房间的真人/电脑席位数：客户端无需靠昵称或旧地图推断结算入口。 */
    private int aiSeats;
    private int humanSeats;
    public boolean isPrivateRoom() { return privateRoom; }
    public void setPrivateRoom(boolean value) { privateRoom = value; }
    public int getAiSeats() { return aiSeats; }
    public void setAiSeats(int value) { aiSeats = value; }
    public int getHumanSeats() { return humanSeats; }
    public void setHumanSeats(int value) { humanSeats = value; }
    public int getMinPlayers() { return minPlayers; }
    public void setMinPlayers(int value) { minPlayers = value; }
    public int getMaxPlayers() { return maxPlayers; }
    public void setMaxPlayers(int value) { maxPlayers = value; }
    public int getTargetPlayers() { return targetPlayers; }
    public void setTargetPlayers(int value) { targetPlayers = value; }
    /**
     * 非棋盘玩法的状态快照（RPG 的血量/技能栏等）。
     *
     * 挂在房间数据上而不是单独发一条消息，是为了让 GAME_START 就能画出完整面板：
     * 否则玩家要等到对手走出第一手才看到血条，开局那几秒界面是空的。
     */
    private Object view;
    /** 结算后的再来一局准备状态；对局中可能为 null */
    private RematchStateDto rematch;

    public RoomDataDto() {}

    public String getGameType() { return gameType; }
    public void setGameType(String gameType) { this.gameType = gameType; }

    public List<PlayerInfoDto> getPlayers() { return players; }
    public void setPlayers(List<PlayerInfoDto> players) { this.players = players; }

    public int getSpectatorCount() { return spectatorCount; }
    public void setSpectatorCount(int spectatorCount) { this.spectatorCount = spectatorCount; }

    public List<String> getSpectators() { return spectators; }
    public void setSpectators(List<String> spectators) { this.spectators = spectators; }

    public int getCurrentTurn() { return currentTurn; }
    public void setCurrentTurn(int currentTurn) { this.currentTurn = currentTurn; }

    public Object getView() { return view; }
    public void setView(Object view) { this.view = view; }

    public RematchStateDto getRematch() { return rematch; }
    public void setRematch(RematchStateDto rematch) { this.rematch = rematch; }
}
