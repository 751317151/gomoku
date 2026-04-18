package com.gomoku.game;

import com.gomoku.dto.*;
import com.gomoku.model.GameMessage;
import com.gomoku.model.Player;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 游戏房间 - 管理两名玩家的对战
 */
public class GameRoom {

    private static final Logger logger = LoggerFactory.getLogger(GameRoom.class);
    private static final Gson gson = new Gson();
    private static final ScheduledExecutorService aiExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AI-Move-Thread");
        t.setDaemon(true);
        return t;
    });

    private static final int AI_DELAY_MS = 600;

    public enum State {
        WAITING,   // 等待玩家
        PLAYING,   // 对战中
        FINISHED   // 游戏结束
    }

    private final String roomId;
    private final CopyOnWriteArrayList<Player> players;
    private final CopyOnWriteArrayList<Player> spectators;
    private final GomokuBoard board;
    private volatile State state;
    private volatile Player currentPlayer;
    private volatile Player winner;
    private Player aiPlayer;
    private int roundCount;

    // 重启确认
    private volatile String restartRequestedBy; // playerId who requested restart

    public GameRoom(String roomId) {
        this.roomId = roomId;
        this.players = new CopyOnWriteArrayList<>();
        this.spectators = new CopyOnWriteArrayList<>();
        this.board = new GomokuBoard();
        this.state = State.WAITING;
        this.roundCount = 0;
    }

    /**
     * 玩家加入房间
     */
    public synchronized boolean addPlayer(Player player) {
        if (players.size() >= 2) return false;
        // 清除上一局的 stone 残留，避免 ROOM_INFO 带旧值
        player.setStone(0);
        players.add(player);
        logger.info("玩家 {} 加入房间 {}", player.getName(), roomId);

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
    public synchronized void addAI() {
        if (players.size() >= 2) return;
        String aiId = "AI-" + UUID.randomUUID().toString().substring(0, 4);
        aiPlayer = new Player(aiId, "电脑", null);
        addPlayer(aiPlayer);
    }

    /**
     * 观战者加入
     */
    public void addSpectator(Player player) {
        spectators.add(player);
        logger.info("玩家 {} 观战房间 {}", player.getName(), roomId);

        GameMessage msg = new GameMessage(GameMessage.Type.GAME_SYNC);
        msg.setRoomId(roomId);
        msg.setMessage("你已加入观战");
        msg.setData(gson.toJson(buildSyncData()));
        player.sendMessage(gson.toJson(msg));

        broadcastRoomInfo();
    }

    /**
     * 开始游戏
     */
    private synchronized void startGame() {
        state = State.PLAYING;
        roundCount++;
        board.reset();
        restartRequestedBy = null;

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
            msg.setData(gson.toJson(buildRoomData()));
            p.sendMessage(gson.toJson(msg));
        }

        // 通知观战者
        GameMessage syncMsg = new GameMessage(GameMessage.Type.GAME_SYNC);
        syncMsg.setRoomId(roomId);
        syncMsg.setMessage("游戏开始");
        syncMsg.setData(gson.toJson(buildSyncData()));
        broadcastSpectators(gson.toJson(syncMsg));

        broadcastRoomInfo();
        logger.info("房间 {} 游戏开始，第 {} 局", roomId, roundCount);

        if (currentPlayer == aiPlayer) {
            triggerAIMove();
        }
    }

    /**
     * 处理落子
     */
    public synchronized boolean handleMove(Player player, int row, int col) {
        if (state != State.PLAYING) return false;
        // 用 playerId 比较而非引用比较，避免断线重连后引用不一致
        if (!player.getId().equals(currentPlayer.getId())) return false;
        if (player.getStone() != currentPlayer.getStone()) return false;
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
            currentPlayer = findPlayerById(getOpponent(player).getId());
            if (currentPlayer == aiPlayer) {
                triggerAIMove();
            }
        }
        return true;
    }

    private void triggerAIMove() {
        if (state != State.PLAYING || aiPlayer == null) return;
        if (currentPlayer != aiPlayer) return;

        aiExecutor.schedule(() -> {
            if (state != State.PLAYING) return;
            int[] move = GomokuAI.getBestMove(board, aiPlayer.getStone());
            if (move != null) {
                synchronized (GameRoom.this) {
                    handleMove(aiPlayer, move[0], move[1]);
                }
            }
        }, AI_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 结束游戏
     */
    private synchronized void endGame(Player winnerPlayer, String reason) {
        state = State.FINISHED;
        this.winner = winnerPlayer;

        if (winnerPlayer != null) {
            winnerPlayer.addWin();
            Player opponent = getOpponent(winnerPlayer);
            if (opponent != null) {
                opponent.addLoss();
            }
        }

        GameMessage msg = new GameMessage(GameMessage.Type.GAME_OVER);
        msg.setRoomId(roomId);
        msg.setWinner(winnerPlayer != null ? winnerPlayer.getId() : "draw");
        msg.setMessage(reason);
        msg.setData(gson.toJson(buildScoreData()));
        broadcastMessage(gson.toJson(msg));
        broadcastRoomInfo();

        logger.info("房间 {} 游戏结束，获胜者: {}", roomId,
                winnerPlayer != null ? winnerPlayer.getName() : "平局");
    }

    /**
     * 玩家认输
     */
    public synchronized void surrender(Player player) {
        if (state != State.PLAYING) return;
        Player found = findPlayerById(player.getId());
        if (found == null) return;

        Player opponent = findPlayerById(getOpponent(found).getId());
        if (opponent != null) {
            endGame(opponent, player.getName() + " 认输");
        }
    }

    /**
     * 请求重新开始 - 需要双方确认
     */
    public synchronized void requestRestart(Player player) {
        if (state != State.FINISHED) return;
        // 用 id 查找确保引用在 players 列表中
        Player found = findPlayerById(player.getId());
        if (found == null) return;

        // AI 对手直接同意重新开始
        Player opponent = findPlayerById(getOpponent(found).getId());
        if (opponent == aiPlayer) {
            startGame();
            return;
        }

        if (restartRequestedBy == null) {
            restartRequestedBy = found.getId();
            if (opponent != null) {
                GameMessage msg = new GameMessage(GameMessage.Type.RESTART_REQUEST);
                msg.setRoomId(roomId);
                msg.setPlayerName(found.getName());
                msg.setMessage(found.getName() + " 请求再来一局");
                opponent.sendMessage(gson.toJson(msg));
            }
        } else if (restartRequestedBy.equals(found.getId())) {
            // 同一个人重复请求，忽略
        } else {
            // 对方确认，开始新一局
            startGame();
        }
    }

    /**
     * 处理聊天
     */
    public void handleChat(Player player, String message) {
        if (!player.canSendChat()) return;
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
    public synchronized void handleDisconnect(Player player) {
        if (spectators.contains(player)) {
            spectators.remove(player);
            broadcastRoomInfo();
            return;
        }

        if (state == State.PLAYING) {
            Player remaining = findPlayerById(getOpponent(player).getId());
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
        restartRequestedBy = null;

        if (!players.isEmpty()) {
            GameMessage waitMsg = new GameMessage(GameMessage.Type.WAITING);
            waitMsg.setRoomId(roomId);
            waitMsg.setMessage("玩家已离开，等待新玩家加入...");
            broadcastMessage(gson.toJson(waitMsg));
        }

        broadcastRoomInfo();
        logger.info("玩家 {} 离开房间 {}", player.getName(), roomId);
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

    private Player findPlayerById(String id) {
        for (Player p : players) {
            if (p.getId().equals(id)) return p;
        }
        return null;
    }

    // ============ DTO 构建方法 ============

    private RoomDataDto buildRoomData() {
        RoomDataDto data = new RoomDataDto();
        List<PlayerInfoDto> playerDtos = new ArrayList<>();
        for (Player p : players) {
            playerDtos.add(new PlayerInfoDto(p.getId(), p.getName(), p.getStone(), p.getWins()));
        }
        data.setPlayers(playerDtos);
        data.setSpectatorCount(spectators.size());
        data.setCurrentTurn(board.getCurrentTurn());
        return data;
    }

    private GameSyncDto buildSyncData() {
        GameSyncDto data = new GameSyncDto();
        data.setState(state.name());
        data.setRoomData(buildRoomData());
        data.setBoard(board.getBoardSnapshot());
        return data;
    }

    private ScoreDataDto buildScoreData() {
        ScoreDataDto data = new ScoreDataDto();
        List<ScoreItemDto> items = new ArrayList<>();
        for (Player p : players) {
            items.add(new ScoreItemDto(p.getId(), p.getName(), p.getWins(), p.getLosses()));
        }
        data.setScores(items);
        return data;
    }

    public void broadcastRoomInfo() {
        GameMessage msg = new GameMessage(GameMessage.Type.ROOM_INFO);
        msg.setRoomId(roomId);
        msg.setData(gson.toJson(buildRoomData()));
        broadcastMessage(gson.toJson(msg));
    }

    /**
     * 为重连玩家构建完整的同步数据
     */
    public GameSyncDto buildSyncDataForReconnect(Player player) {
        GameSyncDto data = new GameSyncDto();
        data.setState(state.name());
        data.setRoomData(buildRoomData());
        data.setBoard(board.getBoardSnapshot());
        // 重连时附带额外信息
        if (player != null && player.getStone() != 0) {
            data.setMoveCount(board.getMoveCount());
        }
        return data;
    }

    /**
     * 替换玩家对象（断线重连用）
     */
    public synchronized void replacePlayer(Player oldPlayer, Player newPlayer) {
        int idx = players.indexOf(oldPlayer);
        if (idx >= 0) {
            players.set(idx, newPlayer);
        }
        // 如果是当前回合玩家，也需要更新引用
        if (currentPlayer == oldPlayer) {
            currentPlayer = newPlayer;
        }
        // 如果是赢家，也需要更新
        if (winner == oldPlayer) {
            winner = newPlayer;
        }
        // 如果是 AI 玩家，更新引用
        if (aiPlayer == oldPlayer) {
            aiPlayer = newPlayer;
        }
    }

    /**
     * 优雅关闭 AI 线程池
     */
    public static void shutdownAIExecutor() {
        aiExecutor.shutdown();
        try {
            if (!aiExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                aiExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            aiExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
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
