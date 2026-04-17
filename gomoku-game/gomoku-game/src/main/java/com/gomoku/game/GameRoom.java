package com.gomoku.game;

import com.gomoku.model.GameMessage;
import com.gomoku.model.Player;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 游戏房间 - 管理两名玩家的对战
 */
public class GameRoom {

    private static final Logger logger = Logger.getLogger(GameRoom.class.getName());
    private static final Gson gson = new Gson();

    public enum State {
        WAITING,   // 等待玩家
        PLAYING,   // 对战中
        FINISHED   // 游戏结束
    }

    private final String roomId;
    private final List<Player> players;
    private final List<Player> spectators;
    private final GomokuBoard board;
    private State state;
    private Player currentPlayer;
    private Player winner;
    private Player aiPlayer;
    private int roundCount;

    public GameRoom(String roomId) {
        this.roomId = roomId;
        this.players = new ArrayList<>();
        this.spectators = new ArrayList<>();
        this.board = new GomokuBoard();
        this.state = State.WAITING;
        this.roundCount = 0;
    }

    /**
     * 玩家加入房间
     */
    public boolean addPlayer(Player player) {
        if (players.size() >= 2) return false;
        players.add(player);
        logger.info("玩家 " + player.getName() + " 加入房间 " + roomId);

        if (players.size() == 2) {
            startGame();
        } else {
            // 通知等待
            GameMessage msg = new GameMessage(GameMessage.Type.WAITING);
            msg.setRoomId(roomId);
            msg.setPlayerId(player.getId());
            msg.setMessage("等待对手加入...");
            player.sendMessage(gson.toJson(msg));
        }
        broadcastRoomInfo();
        return true;
    }

    /**
     * 添加电脑
     */
    public void addAI() {
        if (players.size() >= 2) return;
        String aiId = "AI-" + UUID.randomUUID().toString().substring(0, 4);
        aiPlayer = new Player(aiId, "电脑(简单)", null);
        addPlayer(aiPlayer);
    }

    /**
     * 观战者加入
     */
    public void addSpectator(Player player) {
        spectators.add(player);
        logger.info("玩家 " + player.getName() + " 观战房间 " + roomId);
        
        GameMessage msg = new GameMessage(GameMessage.Type.GAME_SYNC);
        msg.setRoomId(roomId);
        msg.setMessage("你已加入观战");
        msg.setData(buildSyncData());
        player.sendMessage(gson.toJson(msg));
        
        broadcastRoomInfo();
    }

    /**
     * 开始游戏
     */
    private void startGame() {
        state = State.PLAYING;
        roundCount++;
        board.reset();

        // 奇数局黑棋先手，交替
        if (roundCount % 2 == 1) {
            players.get(0).setStone(GomokuBoard.BLACK);
            players.get(1).setStone(GomokuBoard.WHITE);
        } else {
            players.get(0).setStone(GomokuBoard.WHITE);
            players.get(1).setStone(GomokuBoard.BLACK);
        }
        currentPlayer = getBlackPlayer();

        // 通知双方游戏开始
        for (Player p : players) {
            GameMessage msg = new GameMessage(GameMessage.Type.GAME_START);
            msg.setRoomId(roomId);
            msg.setPlayerId(p.getId());
            msg.setStone(p.getStone());
            msg.setMessage(getOpponent(p).getName());
            msg.setData(buildRoomData());
            p.sendMessage(gson.toJson(msg));
        }
        
        // 通知观战者
        GameMessage syncMsg = new GameMessage(GameMessage.Type.GAME_SYNC);
        syncMsg.setRoomId(roomId);
        syncMsg.setMessage("游戏开始");
        syncMsg.setData(buildSyncData());
        broadcastSpectators(gson.toJson(syncMsg));
        
        broadcastRoomInfo();
        logger.info("房间 " + roomId + " 游戏开始，第 " + roundCount + " 局");

        if (currentPlayer == aiPlayer) {
            triggerAIMove();
        }
    }

    /**
     * 处理落子
     */
    public boolean handleMove(Player player, int row, int col) {
        if (state != State.PLAYING) return false;
        if (player != currentPlayer) return false;
        if (!board.placeStone(row, col, player.getStone())) return false;

        // 广播落子
        GameMessage moveMsg = new GameMessage(GameMessage.Type.GAME_MOVE);
        moveMsg.setRoomId(roomId);
        moveMsg.setPlayerId(player.getId());
        moveMsg.setPlayerName(player.getName());
        moveMsg.setRow(row);
        moveMsg.setCol(col);
        moveMsg.setStone(player.getStone());
        moveMsg.setData(String.valueOf(board.getCurrentTurn()));
        broadcastMessage(gson.toJson(moveMsg));

        // 检查胜负
        if (board.checkWin(row, col)) {
            endGame(player, "五子连珠！");
        } else if (board.isDraw()) {
            endGame(null, "平局！棋盘已满");
        } else {
            currentPlayer = getOpponent(player);
            if (currentPlayer == aiPlayer) {
                triggerAIMove();
            }
        }
        return true;
    }

    private void triggerAIMove() {
        if (state != State.PLAYING || aiPlayer == null) return;
        if (currentPlayer != aiPlayer) return;

        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                if (state != State.PLAYING) return;
                int[] move = GomokuAI.getBestMove(board, aiPlayer.getStone());
                if (move != null) {
                    handleMove(aiPlayer, move[0], move[1]);
                }
            }
        }, 600); // 延迟600ms假装思考
    }

    /**
     * 结束游戏
     */
    private void endGame(Player winnerPlayer, String reason) {
        state = State.FINISHED;
        this.winner = winnerPlayer;

        if (winnerPlayer != null) {
            winnerPlayer.addWin();
            if (getOpponent(winnerPlayer) != null) {
                getOpponent(winnerPlayer).addLoss();
            }
        }

        GameMessage msg = new GameMessage(GameMessage.Type.GAME_OVER);
        msg.setRoomId(roomId);
        msg.setWinner(winnerPlayer != null ? winnerPlayer.getId() : "draw");
        msg.setMessage(reason);
        msg.setData(buildScoreData());
        broadcastMessage(gson.toJson(msg));
        broadcastRoomInfo();

        logger.info("房间 " + roomId + " 游戏结束，获胜者: " +
                (winnerPlayer != null ? winnerPlayer.getName() : "平局"));
    }

    /**
     * 重新开始
     */
    public void requestRestart(Player player) {
        if (state == State.FINISHED && players.contains(player)) {
            startGame();
        }
    }

    /**
     * 处理聊天
     */
    public void handleChat(Player player, String message) {
        GameMessage msg = new GameMessage(GameMessage.Type.GAME_CHAT);
        msg.setRoomId(roomId);
        msg.setPlayerId(player.getId());
        msg.setPlayerName(player.getName());
        msg.setMessage(message);
        broadcastMessage(gson.toJson(msg));
    }

    /**
     * 玩家断开连接
     */
    public void handleDisconnect(Player player) {
        if (spectators.contains(player)) {
            spectators.remove(player);
            broadcastRoomInfo();
            return;
        }

        if (state == State.PLAYING) {
            Player remaining = getOpponent(player);
            if (remaining != null && remaining.isConnected()) {
                endGame(remaining, "玩家断开连接");
            }
        }
        players.remove(player);

        if (players.size() == 1 && players.get(0) == aiPlayer) {
            players.remove(aiPlayer);
            aiPlayer = null;
        }

        state = State.WAITING;
        board.reset(); // 清空棋盘，避免下一个观战者或新玩家看到残局

        if (!players.isEmpty()) {
            GameMessage waitMsg = new GameMessage(GameMessage.Type.WAITING);
            waitMsg.setRoomId(roomId);
            waitMsg.setMessage("玩家已离开，等待新玩家加入...");
            broadcastMessage(gson.toJson(waitMsg));
        }

        broadcastRoomInfo();
        logger.info("玩家 " + player.getName() + " 离开房间 " + roomId);
    }

    /**
     * 广播消息给所有玩家和观战者
     */
    public void broadcastMessage(String json) {
        for (Player p : players) {
            if (p != aiPlayer && p.isConnected()) {
                p.sendMessage(json);
            }
        }
        broadcastSpectators(json);
    }
    
    /**
     * 只广播给观战者
     */
    public void broadcastSpectators(String json) {
        for (Player p : spectators) {
            if (p.isConnected()) {
                p.sendMessage(json);
            }
        }
    }

    /**
     * 获取对手
     */
    public Player getOpponent(Player player) {
        for (Player p : players) {
            if (!p.getId().equals(player.getId())) return p;
        }
        return null;
    }

    private Player getBlackPlayer() {
        for (Player p : players) {
            if (p.getStone() == GomokuBoard.BLACK) return p;
        }
        return players.isEmpty() ? null : players.get(0);
    }

    private String buildRoomData() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"players\":[");
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            sb.append("{\"id\":\"").append(p.getId()).append("\",")
              .append("\"name\":\"").append(p.getName()).append("\",")
              .append("\"stone\":").append(p.getStone()).append(",")
              .append("\"wins\":").append(p.getWins()).append("}");
            if (i < players.size() - 1) sb.append(",");
        }
        sb.append("],\"spectatorCount\":").append(spectators.size());
        sb.append(",\"currentTurn\":").append(board.getCurrentTurn()).append("}");
        return sb.toString();
    }
    
    private String buildSyncData() {
        // 构建用于同步游戏状态的 JSON
        StringBuilder sb = new StringBuilder();
        sb.append("{\"state\":\"").append(state.name()).append("\",");
        sb.append("\"roomData\":").append(buildRoomData()).append(",");
        sb.append("\"board\":[");
        int[][] b = board.getBoard();
        for (int i = 0; i < b.length; i++) {
            sb.append("[");
            for (int j = 0; j < b[i].length; j++) {
                sb.append(b[i][j]);
                if (j < b[i].length - 1) sb.append(",");
            }
            sb.append("]");
            if (i < b.length - 1) sb.append(",");
        }
        sb.append("]}");
        return sb.toString();
    }

    private String buildScoreData() {
        StringBuilder sb = new StringBuilder("{\"scores\":[");
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            sb.append("{\"id\":\"").append(p.getId()).append("\",")
              .append("\"name\":\"").append(p.getName()).append("\",")
              .append("\"wins\":").append(p.getWins()).append(",")
              .append("\"losses\":").append(p.getLosses()).append("}");
            if (i < players.size() - 1) sb.append(",");
        }
        sb.append("]}");
        return sb.toString();
    }
    
    public void broadcastRoomInfo() {
        GameMessage msg = new GameMessage(GameMessage.Type.ROOM_INFO);
        msg.setRoomId(roomId);
        msg.setData(buildRoomData());
        broadcastMessage(gson.toJson(msg));
    }

    // Getters
    public String getRoomId() { return roomId; }
    public State getState() { return state; }
    public List<Player> getPlayers() { return players; }
    public List<Player> getSpectators() { return spectators; }
    public int getPlayerCount() { return players.size(); }
    public int getSpectatorCount() { return spectators.size(); }
    public boolean isFull() { return players.size() >= 2; }
    public GomokuBoard getBoard() { return board; }
}
