package com.gomoku.model;

/**
 * WebSocket 消息模型
 */
public class GameMessage {

    public enum Type {
        // 客户端 -> 服务端
        GET_ROOMS,      // 获取房间列表
        JOIN,           // 加入游戏
        SPECTATE,       // 观战
        ADD_AI,         // 添加电脑
        MOVE,           // 落子
        PASS,           // 停一手（围棋）
        END_TURN,       // 结束回合（一回合多行动的玩法，如卡牌）
        CHAT,           // 聊天
        RESTART,        // 请求重新开始
        LEAVE,          // 离开房间/大厅
        RECONNECT,      // 断线重连
        SURRENDER,      // 认输
        PING,           // 心跳

        // 服务端 -> 客户端
        ROOM_LIST,      // 房间列表数据
        ROOM_INFO,      // 房间信息
        GAME_START,     // 游戏开始
        GAME_SYNC,      // 游戏状态同步(观战用)
        GAME_MOVE,      // 落子广播
        GAME_OVER,      // 游戏结束
        GAME_CHAT,      // 聊天广播
        WAITING,        // 等待对手
        RESTART_REQUEST,// 对手请求重玩
        TIMER_SYNC,     // 计时同步（服务端→客户端，每秒推送）
        PLAYER_STATUS,  // 玩家连接状态（断线/重连通知，不重置棋盘）
        ERROR           // 错误信息
    }

    private Type type;
    private String roomId;
    private String playerId;
    private String playerName;
    private int row;
    private int col;
    private int fromRow = -1; // 起点行（象棋等棋子移动类游戏，-1 = 无）
    private int fromCol = -1; // 起点列
    private int action = -1;  // 行动序号（RPG 技能/道具下标等非棋盘玩法，-1 = 未使用）
    private int targetSide = 0;   // 目标所属方（1=己方 2=对方，0=未指定；卡牌选目标用）
    private int targetIndex = -1; // 目标下标（-1=英雄，>=0=场上随从）
    private int stone;       // 1=黑, 2=白
    private String winner;
    private String message;
    private String data;     // JSON 附加数据
    private String sessionId; // 用于断线重连
    private String gameType; // 游戏类型（GOMOKU/REVERSI/GO9）
    private String deck;     // 卡牌套牌预组（BALANCED/AGGRO/CONTROL/CUSTOM），其他玩法忽略
    /** 自定义套牌配比（JSON：{"FOOTMAN":6,...}），仅 deck=CUSTOM 时有意义 */
    private String deckCards;
    private int moveSeq;     // 落子序列号（防重复）
    private String actionVersion; // 操作所依据的对局/轮次或局面版本；旧客户端可省略
    public String getActionVersion() { return actionVersion; }
    public void setActionVersion(String value) { actionVersion = value; }
    private int timeLeft;    // 当前步时剩余秒数
    private int totalTimeLeft; // 己方总时间剩余秒数
    /**
     * 建房标志（JOIN 时使用）。
     *
     * 默认 false = 自动匹配：优先加入同类型等待房间，没有才新建。
     * true = 强制新建一个私有房间，并把房间号回给客户端用于邀请好友。
     * 好友用 roomId 加入时不需要这个标志。
     */
    private boolean createRoom;
    private String guestToken;
    public String getGuestToken() { return guestToken; }
    public void setGuestToken(String token) { guestToken = token; }
    private int playerCount;
    public int getPlayerCount() { return playerCount; }
    public void setPlayerCount(int playerCount) { this.playerCount = playerCount; }

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

    public int getFromRow() { return fromRow; }
    public void setFromRow(int fromRow) { this.fromRow = fromRow; }

    public int getFromCol() { return fromCol; }
    public void setFromCol(int fromCol) { this.fromCol = fromCol; }

    public int getAction() { return action; }
    public void setAction(int action) { this.action = action; }

    public int getTargetSide() { return targetSide; }
    public void setTargetSide(int targetSide) { this.targetSide = targetSide; }

    public int getTargetIndex() { return targetIndex; }
    public void setTargetIndex(int targetIndex) { this.targetIndex = targetIndex; }

    public int getStone() { return stone; }
    public void setStone(int stone) { this.stone = stone; }

    public String getWinner() { return winner; }
    public void setWinner(String winner) { this.winner = winner; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getData() { return data; }
    public void setData(String data) { this.data = data; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getGameType() { return gameType; }
    public void setGameType(String gameType) { this.gameType = gameType; }

    public String getDeck() { return deck; }
    public void setDeck(String deck) { this.deck = deck; }

    public String getDeckCards() { return deckCards; }
    public void setDeckCards(String deckCards) { this.deckCards = deckCards; }

    public boolean isCreateRoom() { return createRoom; }
    public void setCreateRoom(boolean createRoom) { this.createRoom = createRoom; }

    public int getMoveSeq() { return moveSeq; }
    public void setMoveSeq(int moveSeq) { this.moveSeq = moveSeq; }

    public int getTimeLeft() { return timeLeft; }
    public void setTimeLeft(int timeLeft) { this.timeLeft = timeLeft; }

    public int getTotalTimeLeft() { return totalTimeLeft; }
    public void setTotalTimeLeft(int totalTimeLeft) { this.totalTimeLeft = totalTimeLeft; }
}
