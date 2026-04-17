package com.gomoku.model;

/**
 * WebSocket 消息模型
 */
public class GameMessage {

    public enum Type {
        // 客户端 -> 服务端
        GET_ROOMS,  // 获取房间列表
        JOIN,       // 加入游戏
        SPECTATE,   // 观战
        ADD_AI,     // 添加电脑
        MOVE,       // 落子
        CHAT,       // 聊天
        RESTART,    // 请求重新开始
        LEAVE,      // 离开房间/大厅

        // 服务端 -> 客户端
        ROOM_LIST,  // 房间列表数据
        ROOM_INFO,  // 房间信息
        GAME_START, // 游戏开始
        GAME_SYNC,  // 游戏状态同步(观战用)
        GAME_MOVE,  // 落子广播
        GAME_OVER,  // 游戏结束
        GAME_CHAT,  // 聊天广播
        WAITING,    // 等待对手
        ERROR       // 错误信息
    }

    private Type type;
    private String roomId;
    private String playerId;
    private String playerName;
    private int row;
    private int col;
    private int stone;       // 1=黑, 2=白
    private String winner;
    private String message;
    private String data;     // JSON 附加数据

    public GameMessage() {}

    public GameMessage(Type type) {
        this.type = type;
    }

    // Getters and Setters
    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }

    public String getPlayerId() { return playerId; }
    public void setPlayerId(String playerId) { this.playerId = playerId; }

    public String getPlayerName() { return playerName; }
    public void setPlayerName(String playerName) { this.playerName = playerName; }

    public int getRow() { return row; }
    public void setRow(int row) { this.row = row; }

    public int getCol() { return col; }
    public void setCol(int col) { this.col = col; }

    public int getStone() { return stone; }
    public void setStone(int stone) { this.stone = stone; }

    public String getWinner() { return winner; }
    public void setWinner(String winner) { this.winner = winner; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getData() { return data; }
    public void setData(String data) { this.data = data; }
}
