package com.gomoku.model;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 玩家模型
 */
public class Player {
    private final String id;
    private String name;
    private final Channel connection;
    private final String sessionId;
    private int stone; // 1=黑棋, 2=白棋
    private int wins;
    private int losses;
    private int elo = 1200; // ELO 积分

    private static final long CHAT_INTERVAL_MS = 1000;
    private static final long ACTION_INTERVAL_MS = 300;
    private final AtomicLong lastChatTime = new AtomicLong(0);
    private final AtomicLong lastActionTime = new AtomicLong(0);

    public Player(String id, String name, Channel connection) {
        this(id, name, connection, null);
    }

    public Player(String id, String name, Channel connection, String sessionId) {
        this.id = id;
        this.name = name;
        this.connection = connection;
        this.sessionId = sessionId;
        this.wins = 0;
        this.losses = 0;
    }

    /**
     * 通过 Player 对象发送 JSON 消息
     */
    public void sendMessage(String json) {
        sendMessageTo(connection, json);
    }

    /**
     * 静态发送方法：直接通过 Channel 发送，无需 Player 对象
     * Pipeline 中有 BinaryMessageEncoder 时，TextWebSocketFrame 会被正常发送
     */
    public static void sendMessageTo(Channel conn, String json) {
        if (conn != null && conn.isActive()) {
            conn.writeAndFlush(new TextWebSocketFrame(json));
        }
    }

    public boolean isConnected() {
        return connection != null && connection.isActive();
    }

    public boolean canSendChat() {
        long now = System.currentTimeMillis();
        long last = lastChatTime.get();
        if (now - last < CHAT_INTERVAL_MS) {
            return false;
        }
        return lastChatTime.compareAndSet(last, now);
    }

    public boolean canPerformAction() {
        long now = System.currentTimeMillis();
        long last = lastActionTime.get();
        if (now - last < ACTION_INTERVAL_MS) {
            return false;
        }
        return lastActionTime.compareAndSet(last, now);
    }

    // Getters and Setters
    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Channel getConnection() { return connection; }
    public String getSessionId() { return sessionId; }
    public int getStone() { return stone; }
    public void setStone(int stone) { this.stone = stone; }
    public int getWins() { return wins; }
    public void addWin() { this.wins++; }
    public void setWins(int wins) { this.wins = wins; }
    public int getLosses() { return losses; }
    public void addLoss() { this.losses++; }
    public void setLosses(int losses) { this.losses = losses; }
    public int getElo() { return elo; }
    public void setElo(int elo) { this.elo = elo; }

    @Override
    public String toString() {
        return String.format("Player{id='%s', name='%s', stone=%d}", id, name, stone);
    }
}
