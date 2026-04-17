package com.gomoku.model;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

/**
 * 玩家模型
 */
public class Player {
    private final String id;
    private String name;
    private final Channel connection;
    private int stone; // 1=黑棋, 2=白棋
    private int wins;
    private int losses;

    public Player(String id, String name, Channel connection) {
        this.id = id;
        this.name = name;
        this.connection = connection;
        this.wins = 0;
        this.losses = 0;
    }

    public void sendMessage(String json) {
        if (connection != null && connection.isActive()) {
            connection.writeAndFlush(new TextWebSocketFrame(json));
        }
    }

    public boolean isConnected() {
        return connection != null && connection.isActive();
    }

    // Getters and Setters
    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Channel getConnection() { return connection; }
    public int getStone() { return stone; }
    public void setStone(int stone) { this.stone = stone; }
    public int getWins() { return wins; }
    public void addWin() { this.wins++; }
    public int getLosses() { return losses; }
    public void addLoss() { this.losses++; }

    @Override
    public String toString() {
        return String.format("Player{id='%s', name='%s', stone=%d}", id, name, stone);
    }
}
