package com.gomoku.game;

import com.gomoku.dto.*;
import com.gomoku.model.GameMessage;
import com.gomoku.model.Player;
import com.google.gson.Gson;
import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 游戏房间 - 管理两名玩家的对战
 */
public class GameRoom {

    private static final Logger logger = LoggerFactory.getLogger(GameRoom.class);
    private static final Gson gson = new Gson();
    private static final int AI_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors());
    private static final ScheduledExecutorService aiExecutor = createAIExecutor();

    // ============ 计时器常量 ============
    private static final int STEP_TIME_SECONDS = 30;   // 每步限时 30 秒
    private static final int TOTAL_TIME_SECONDS = 300;  // 总时间 5 分钟

    private static ScheduledExecutorService createAIExecutor() {
        ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(
                AI_POOL_SIZE,
                r -> {
                    Thread t = new Thread(r, "AI-Move-Thread");
                    t.setDaemon(true);
                    return t;
                }
        );
        pool.setRemoveOnCancelPolicy(true);
        return pool;
    }

    private static final int AI_DELAY_MS = 600;

    public enum State {
        WAITING, PLAYING, FINISHED
    }

    private final String roomId;
    private final CopyOnWriteArrayList<Player> players;
    private final CopyOnWriteArrayList<Player> spectators;
    private final GomokuBoard board;
    private final ChannelGroup channelGroup;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private volatile State state;
    private volatile Player currentPlayer;
    private volatile Player winner;
    private Player aiPlayer;
    private int aiDifficulty = 4; // AI 搜索深度（简单2/中等4/困难6）
    private int roundCount;
    private volatile long lastActivityTime;
    private volatile String restartRequestedBy;

    // ============ 计时器状态 ============
    private volatile int[] totalTimeLeft = new int[2]; // [0]=黑方剩余秒, [1]=白方剩余秒
    private volatile int stepTimeLeft;                  // 当前步时剩余秒
    private ScheduledFuture<?> timerFuture;

    // ELO 服务引用（由 RoomManager 注入）
    private EloService eloService;

    public GameRoom(String roomId) {
        this.roomId = roomId;
        this.channelGroup = new DefaultChannelGroup("room-" + roomId, GlobalEventExecutor.INSTANCE);
        this.players = new CopyOnWriteArrayList<>();
        this.spectators = new CopyOnWriteArrayList<>();
        this.board = new GomokuBoard();
        this.state = State.WAITING;
        this.roundCount = 0;
        this.lastActivityTime = System.currentTimeMillis();
    }

    public void setEloService(EloService eloService) {
        this.eloService = eloService;
    }

    private void touchActivity() {
        this.lastActivityTime = System.currentTimeMillis();
    }

    public long getLastActivityTime() {
        return lastActivityTime;
    }

    // ============ 状态语义方法 ============

    public boolean canJoin() { return players.size() < 2; }
    public boolean canMove() { return state == State.PLAYING; }
    public boolean canRestart() { return state == State.FINISHED; }
    public boolean canSurrender() { return state == State.PLAYING; }

    // ============ 玩家管理 ============

    public boolean addPlayer(Player player) {
        lock.writeLock().lock();
        try {
            if (players.size() >= 2) return false;
            player.setStone(0);
            // 初始化 ELO
            if (eloService != null) {
                player.setElo(eloService.getRating(player.getName()));
            }
            players.add(player);
            if (player.isConnected()) {
                channelGroup.add(player.getConnection());
            }
            touchActivity();
            logger.info("[AUDIT] player_join roomId={} playerId={} playerName={}", roomId, player.getId(), player.getName());

            if (players.size() == 2) {
                startGame();
            } else {
                GameMessage msg = new GameMessage(GameMessage.Type.WAITING);
                msg.setRoomId(roomId);
                msg.setPlayerId(player.getId());
                msg.setMessage("等待对手加入...");
                player.sendMessage(gson.toJson(msg));
            }
            broadcastRoomInfo();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void addAI(int difficulty) {
        lock.writeLock().lock();
        try {
            if (players.size() >= 2) return;
            this.aiDifficulty = Math.max(2, Math.min(6, difficulty));
            String aiId = "AI-" + UUID.randomUUID().toString().substring(0, 4);
            aiPlayer = new Player(aiId, "电脑", null);
            addPlayer(aiPlayer);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void addAI() {
        addAI(4); // 默认中等
    }

    public void addSpectator(Player player) {
        spectators.add(player);
        if (player.isConnected()) {
            channelGroup.add(player.getConnection());
        }
        touchActivity();
        logger.info("[AUDIT] spectator_join roomId={} playerId={}", roomId, player.getId());

        GameMessage msg = new GameMessage(GameMessage.Type.GAME_SYNC);
        msg.setRoomId(roomId);
        msg.setMessage("你已加入观战");
        msg.setData(gson.toJson(buildSyncData()));
        player.sendMessage(gson.toJson(msg));
        broadcastRoomInfo();
    }

    // ============ 游戏流程 ============

    private void startGame() {
        state = State.PLAYING;
        roundCount++;
        board.reset();
        restartRequestedBy = null;
        touchActivity();

        if (roundCount % 2 == 1) {
            players.get(0).setStone(GomokuBoard.BLACK);
            players.get(1).setStone(GomokuBoard.WHITE);
        } else {
            players.get(0).setStone(GomokuBoard.WHITE);
            players.get(1).setStone(GomokuBoard.BLACK);
        }
        currentPlayer = getBlackPlayer();

        // 初始化计时器
        totalTimeLeft = new int[]{TOTAL_TIME_SECONDS, TOTAL_TIME_SECONDS};
        stepTimeLeft = STEP_TIME_SECONDS;

        for (Player p : players) {
            GameMessage msg = new GameMessage(GameMessage.Type.GAME_START);
            msg.setRoomId(roomId);
            msg.setPlayerId(p.getId());
            msg.setStone(p.getStone());
            msg.setMessage(getOpponent(p).getName());
            msg.setData(gson.toJson(buildRoomData()));
            p.sendMessage(gson.toJson(msg));
        }

        GameMessage syncMsg = new GameMessage(GameMessage.Type.GAME_SYNC);
        syncMsg.setRoomId(roomId);
        syncMsg.setMessage("游戏开始");
        syncMsg.setData(gson.toJson(buildSyncData()));
        broadcastSpectators(gson.toJson(syncMsg));

        broadcastRoomInfo();
        logger.info("[AUDIT] game_start roomId={} round={}", roomId, roundCount);

        // 启动计时器（AI 回合也启动但不超时判负）
        startTurnTimer();

        if (currentPlayer == aiPlayer) {
            triggerAIMove();
        }
    }

    public boolean handleMove(Player player, int row, int col, int expectedSeq) {
        lock.writeLock().lock();
        try {
            if (!canMove()) return false;
            if (!player.getId().equals(currentPlayer.getId())) return false;
            if (player.getStone() != currentPlayer.getStone()) return false;

            if (expectedSeq >= 0 && expectedSeq != board.getMoveSeq() + 1) {
                logger.warn("[AUDIT] move_seq_mismatch roomId={} playerId={} expected={} actual={}",
                        roomId, player.getId(), expectedSeq, board.getMoveSeq() + 1);
                return false;
            }

            if (!board.placeStone(row, col, player.getStone())) return false;
            touchActivity();

            // 更新计时器：扣减己方总时间
            int stoneIdx = player.getStone() - 1; // 0=黑, 1=白
            int elapsed = STEP_TIME_SECONDS - stepTimeLeft;
            totalTimeLeft[stoneIdx] = Math.max(0, totalTimeLeft[stoneIdx] - elapsed);

            // 广播落子
            GameMessage moveMsg = new GameMessage(GameMessage.Type.GAME_MOVE);
            moveMsg.setRoomId(roomId);
            moveMsg.setPlayerId(player.getId());
            moveMsg.setPlayerName(player.getName());
            moveMsg.setRow(row);
            moveMsg.setCol(col);
            moveMsg.setStone(player.getStone());
            moveMsg.setMoveSeq(board.getMoveSeq());
            moveMsg.setData(String.valueOf(board.getCurrentTurn()));
            broadcastMessage(gson.toJson(moveMsg));

            logger.info("[AUDIT] move roomId={} playerId={} row={} col={} stone={} seq={}",
                    roomId, player.getId(), row, col, player.getStone(), board.getMoveSeq());

            if (board.checkWin(row, col)) {
                endGame(player, "五子连珠！", board.getWinLine(row, col));
            } else if (board.isDraw()) {
                endGame(null, "平局！棋盘已满", null);
            } else {
                currentPlayer = findPlayerById(getOpponent(player).getId());
                // 重置步时，启动新回合计时
                stepTimeLeft = STEP_TIME_SECONDS;
                startTurnTimer();
                if (currentPlayer == aiPlayer) {
                    triggerAIMove();
                }
            }
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean handleMove(Player player, int row, int col) {
        return handleMove(player, row, col, -1);
    }

    private void triggerAIMove() {
        if (state != State.PLAYING || aiPlayer == null || currentPlayer != aiPlayer) return;
        aiExecutor.schedule(() -> {
            if (state != State.PLAYING) return;
            int[] move = GomokuAI.getBestMove(board, aiPlayer.getStone(), aiDifficulty);
            if (move != null) {
                handleMove(aiPlayer, move[0], move[1]);
            }
        }, AI_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    // ============ 计时器 ============

    private void startTurnTimer() {
        cancelTurnTimer();
        timerFuture = aiExecutor.scheduleAtFixedRate(() -> {
            lock.writeLock().lock();
            try {
                if (state != State.PLAYING) {
                    cancelTurnTimer();
                    return;
                }
                stepTimeLeft--;
                // 扣减当前玩家总时间
                if (currentPlayer != null) {
                    int idx = currentPlayer.getStone() - 1;
                    if (idx >= 0 && idx < 2) {
                        totalTimeLeft[idx] = Math.max(0, totalTimeLeft[idx] - 1);
                    }
                }

                // 广播计时同步
                broadcastTimerSync();

                // 超时判负（AI 不判负）
                if (stepTimeLeft <= 0 || (currentPlayer != null && currentPlayer.getStone() > 0
                        && totalTimeLeft[currentPlayer.getStone() - 1] <= 0)) {
                    if (currentPlayer != aiPlayer) {
                        Player timeoutPlayer = currentPlayer;
                        String reason = totalTimeLeft[timeoutPlayer.getStone() - 1] <= 0
                                ? timeoutPlayer.getName() + " 总时间耗尽" : timeoutPlayer.getName() + " 步时超时";
                        endGame(getOpponent(timeoutPlayer), reason, null);
                    }
                    cancelTurnTimer();
                }
            } finally {
                lock.writeLock().unlock();
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void cancelTurnTimer() {
        if (timerFuture != null) {
            timerFuture.cancel(false);
            timerFuture = null;
        }
    }

    private void broadcastTimerSync() {
        int blackTime = totalTimeLeft[0];
        int whiteTime = totalTimeLeft[1];
        GameMessage msg = new GameMessage(GameMessage.Type.TIMER_SYNC);
        msg.setRoomId(roomId);
        msg.setTimeLeft(stepTimeLeft);
        msg.setTotalTimeLeft(blackTime); // 兼容：用 totalTimeLeft 传黑方时间
        // 用 data 传递双端时间
        java.util.Map<String, Integer> timerData = new java.util.LinkedHashMap<>();
        timerData.put("stepTime", stepTimeLeft);
        timerData.put("blackTotal", blackTime);
        timerData.put("whiteTotal", whiteTime);
        msg.setData(gson.toJson(timerData));
        broadcastMessage(gson.toJson(msg));
    }

    // ============ 结束游戏 ============

    private void endGame(Player winnerPlayer, String reason, int[][] winLine) {
        cancelTurnTimer();
        state = State.FINISHED;
        this.winner = winnerPlayer;
        touchActivity();

        // 更新胜负
        if (winnerPlayer != null) {
            winnerPlayer.addWin();
            Player opponent = getOpponent(winnerPlayer);
            if (opponent != null) opponent.addLoss();
        }

        // ELO 更新（跳过 AI）
        int eloChange = 0;
        if (eloService != null && winnerPlayer != null && winnerPlayer != aiPlayer) {
            Player loser = getOpponent(winnerPlayer);
            if (loser != null && loser != aiPlayer) {
                int[] result = eloService.updateRating(winnerPlayer, loser);
                eloChange = result[2];
            }
        } else if (eloService != null && winnerPlayer == null) {
            // 平局
            if (players.size() == 2 && players.get(0) != aiPlayer && players.get(1) != aiPlayer) {
                eloService.updateDraw(players.get(0), players.get(1));
            }
        }

        GameMessage msg = new GameMessage(GameMessage.Type.GAME_OVER);
        msg.setRoomId(roomId);
        msg.setWinner(winnerPlayer != null ? winnerPlayer.getId() : "draw");
        msg.setMessage(reason);

        // 构建附加数据：分数 + ELO + 获胜连线 + 对局记录
        Map<String, Object> dataMap = new LinkedHashMap<>();
        ScoreDataDto scoreData = buildScoreData();
        dataMap.put("scores", scoreData.getScores());
        dataMap.put("eloChange", eloChange);
        if (winLine != null) {
            dataMap.put("winLine", Arrays.asList(winLine));
        }
        // 对局记录（用于回放）
        dataMap.put("moveHistory", board.getMoveHistory());
        msg.setData(gson.toJson(dataMap));

        broadcastMessage(gson.toJson(msg));
        broadcastRoomInfo();

        logger.info("[AUDIT] game_over roomId={} winner={} reason=\"{}\" eloChange={}", roomId,
                winnerPlayer != null ? winnerPlayer.getId() : "draw", reason, eloChange);
    }

    public void surrender(Player player) {
        lock.writeLock().lock();
        try {
            if (!canSurrender()) return;
            Player found = findPlayerById(player.getId());
            if (found == null) return;
            Player opponent = findPlayerById(getOpponent(found).getId());
            if (opponent != null) endGame(opponent, player.getName() + " 认输", null);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void requestRestart(Player player) {
        lock.writeLock().lock();
        try {
            if (!canRestart()) return;
            Player found = findPlayerById(player.getId());
            if (found == null) return;

            Player opponent = findPlayerById(getOpponent(found).getId());
            if (opponent == aiPlayer) { startGame(); return; }

            if (restartRequestedBy == null) {
                restartRequestedBy = found.getId();
                if (opponent != null) {
                    GameMessage msg = new GameMessage(GameMessage.Type.RESTART_REQUEST);
                    msg.setRoomId(roomId);
                    msg.setPlayerName(found.getName());
                    msg.setMessage(found.getName() + " 请求再来一局");
                    opponent.sendMessage(gson.toJson(msg));
                }
            } else if (!restartRequestedBy.equals(found.getId())) {
                startGame();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void handleChat(Player player, String message) {
        if (!player.canSendChat()) return;
        GameMessage msg = new GameMessage(GameMessage.Type.GAME_CHAT);
        msg.setRoomId(roomId);
        msg.setPlayerId(player.getId());
        msg.setPlayerName(player.getName());
        msg.setMessage(message);
        broadcastMessage(gson.toJson(msg));
    }

    public void handleDisconnect(Player player) {
        lock.writeLock().lock();
        try {
            if (player.getConnection() != null) {
                channelGroup.remove(player.getConnection());
            }

            if (spectators.contains(player)) {
                spectators.remove(player);
                broadcastRoomInfo();
                return;
            }

            if (state == State.PLAYING) {
                Player remaining = findPlayerById(getOpponent(player).getId());
                if (remaining != null && remaining.isConnected()) {
                    endGame(remaining, "玩家断开连接", null);
                }
            }
            players.remove(player);

            if (players.size() == 1 && players.get(0) == aiPlayer) {
                players.remove(aiPlayer);
                aiPlayer = null;
            }

            state = State.WAITING;
            board.reset();
            cancelTurnTimer();
            restartRequestedBy = null;
            touchActivity();

            if (!players.isEmpty()) {
                GameMessage waitMsg = new GameMessage(GameMessage.Type.WAITING);
                waitMsg.setRoomId(roomId);
                waitMsg.setMessage("玩家已离开，等待新玩家加入...");
                broadcastMessage(gson.toJson(waitMsg));
            }
            broadcastRoomInfo();
            logger.info("[AUDIT] player_leave roomId={} playerId={}", roomId, player.getId());
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ============ 广播 ============

    public void broadcastMessage(String json) {
        TextWebSocketFrame frame = new TextWebSocketFrame(json);
        channelGroup.writeAndFlush(frame.retain());
        frame.release();
    }

    public void broadcastSpectators(String json) {
        for (Player p : spectators) {
            if (p.isConnected()) p.sendMessage(json);
        }
    }

    // ============ 辅助方法 ============

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

    // ============ DTO 构建 ============

    private RoomDataDto buildRoomData() {
        RoomDataDto data = new RoomDataDto();
        List<PlayerInfoDto> playerDtos = new ArrayList<>();
        for (Player p : players) {
            playerDtos.add(new PlayerInfoDto(p.getId(), p.getName(), p.getStone(), p.getWins(), p.getElo()));
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
            ScoreItemDto item = new ScoreItemDto(p.getId(), p.getName(), p.getWins(), p.getLosses(), p.getElo());
            items.add(item);
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

    public GameSyncDto buildSyncDataForReconnect(Player player) {
        lock.readLock().lock();
        try {
            GameSyncDto data = new GameSyncDto();
            data.setState(state.name());
            data.setRoomData(buildRoomData());
            data.setBoard(board.getBoardSnapshot());
            if (player != null && player.getStone() != 0) {
                data.setMoveCount(board.getMoveCount());
            }
            return data;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void replacePlayer(Player oldPlayer, Player newPlayer) {
        lock.writeLock().lock();
        try {
            int idx = players.indexOf(oldPlayer);
            if (idx >= 0) players.set(idx, newPlayer);
            if (currentPlayer == oldPlayer) currentPlayer = newPlayer;
            if (winner == oldPlayer) winner = newPlayer;
            if (aiPlayer == oldPlayer) aiPlayer = newPlayer;
            if (oldPlayer.getConnection() != null) channelGroup.remove(oldPlayer.getConnection());
            if (newPlayer.isConnected()) channelGroup.add(newPlayer.getConnection());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void notifyShutdown() {
        cancelTurnTimer();
        GameMessage msg = new GameMessage(GameMessage.Type.ERROR);
        msg.setMessage("服务器正在关闭，请稍后重连");
        broadcastMessage(gson.toJson(msg));
    }

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
    public int getAiDifficulty() { return aiDifficulty; }
}
