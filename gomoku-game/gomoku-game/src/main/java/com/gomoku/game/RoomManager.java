package com.gomoku.game;

import com.gomoku.dto.RoomListItemDto;
import com.gomoku.model.GameMessage;
import com.gomoku.model.Player;
import com.google.gson.Gson;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 房间管理器 - 管理所有游戏房间和玩家
 */
@Service
public class RoomManager {

    private static final Logger logger = LoggerFactory.getLogger(RoomManager.class);
    private static final Gson gson = new Gson();
    private static final int MAX_CONNECTIONS_PER_IP = 5;
    private static final long ROOM_IDLE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);

    // 房间ID -> 房间
    private final ConcurrentHashMap<String, GameRoom> rooms = new ConcurrentHashMap<>();
    // WebSocket Channel -> 玩家
    private final ConcurrentHashMap<Channel, Player> playerByConn = new ConcurrentHashMap<>();
    // 玩家ID -> 玩家
    private final ConcurrentHashMap<String, Player> playerById = new ConcurrentHashMap<>();
    // SessionID -> 玩家ID（断线重连用）
    private final ConcurrentHashMap<String, String> sessionToPlayer = new ConcurrentHashMap<>();
    // 玩家ID -> 房间ID
    private final ConcurrentHashMap<String, String> playerRoom = new ConcurrentHashMap<>();
    // IP -> 连接数（IP 限流）
    private final ConcurrentHashMap<String, Integer> ipConnectionCount = new ConcurrentHashMap<>();

    // 僵尸房间清理定时任务
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Room-Cleanup-Thread");
        t.setDaemon(true);
        return t;
    });

    private final EloService eloService;

    public RoomManager(MeterRegistry meterRegistry, EloService eloService) {
        this.eloService = eloService;
        // 每 60 秒扫描一次僵尸房间
        cleanupExecutor.scheduleAtFixedRate(this::cleanupIdleRooms, 60, 60, TimeUnit.SECONDS);

        // 注册 Micrometer 自定义指标
        meterRegistry.gauge("gomoku.players.online", playerByConn, ConcurrentHashMap::size);
        meterRegistry.gauge("gomoku.rooms.total", rooms, ConcurrentHashMap::size);
        meterRegistry.gauge("gomoku.rooms.playing", rooms, m ->
                m.values().stream().filter(r -> r.getState() == GameRoom.State.PLAYING).count());
        meterRegistry.gauge("gomoku.rooms.waiting", rooms, m ->
                m.values().stream().filter(r -> r.getState() == GameRoom.State.WAITING).count());
    }

    /**
     * IP 连接数检查（返回 true 表示允许）
     */
    public boolean checkIpLimit(String ip) {
        int count = ipConnectionCount.merge(ip, 1, Integer::sum);
        if (count > MAX_CONNECTIONS_PER_IP) {
            ipConnectionCount.merge(ip, -1, Integer::sum);
            return false;
        }
        return true;
    }

    /**
     * IP 连接释放（从 handlerRemoved 直接调用）
     */
    public void releaseIp(String ip) {
        if (ip != null) {
            ipConnectionCount.computeIfPresent(ip, (k, v) -> v <= 1 ? null : v - 1);
        }
    }

    /**
     * 清理超时空房间
     */
    private void cleanupIdleRooms() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, GameRoom> entry : rooms.entrySet()) {
            GameRoom room = entry.getValue();
            // 只清理 WAITING 状态且超过 5 分钟无活动的空房间
            if (room.getState() == GameRoom.State.WAITING
                    && room.getPlayerCount() == 0
                    && now - room.getLastActivityTime() > ROOM_IDLE_TIMEOUT_MS) {
                rooms.remove(entry.getKey());
                logger.info("[CLEANUP] 清理僵尸房间 {}", entry.getKey());
            }
        }
    }

    public String getRoomsListJson() {
        List<RoomListItemDto> list = new ArrayList<>();
        for (GameRoom room : rooms.values()) {
            List<String> playerNames = room.getPlayers().stream()
                    .map(Player::getName)
                    .collect(Collectors.toList());
            list.add(new RoomListItemDto(
                    room.getRoomId(),
                    room.getState().name(),
                    room.getPlayerCount(),
                    room.getSpectatorCount(),
                    playerNames
            ));
        }
        return gson.toJson(list);
    }

    public String getLeaderboardJson() {
        return eloService.getLeaderboardJson();
    }

    public synchronized GameRoom joinGame(Channel conn, String playerName, String targetRoomId) {
        Player player = getOrCreatePlayer(conn, playerName);

        GameRoom room = null;
        if (targetRoomId != null && !targetRoomId.trim().isEmpty()) {
            room = rooms.get(targetRoomId);
            if (room != null && room.isFull()) {
                return null;
            }
        }

        if (room == null) {
            room = findWaitingRoom();
        }

        if (room == null) {
            String roomId = "ROOM-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
            room = new GameRoom(roomId);
            room.setEloService(eloService);
            rooms.put(roomId, room);
        }

        room.addPlayer(player);
        playerRoom.put(player.getId(), room.getRoomId());

        broadcastRoomsList();
        return room;
    }

    public synchronized GameRoom spectateGame(Channel conn, String playerName, String targetRoomId) {
        GameRoom room = rooms.get(targetRoomId);
        if (room == null) return null;

        Player player = getOrCreatePlayer(conn, playerName);
        room.addSpectator(player);
        playerRoom.put(player.getId(), room.getRoomId());

        broadcastRoomsList();
        return room;
    }

    public synchronized GameRoom addAI(Channel conn, int difficulty) {
        Player player = playerByConn.get(conn);
        if (player == null) return null;

        GameRoom room = getRoomByPlayer(player);
        if (room != null && !room.isFull()) {
            room.addAI(difficulty);
            broadcastRoomsList();
            return room;
        }
        return null;
    }

    public synchronized GameRoom addAI(Channel conn) {
        return addAI(conn, 4);
    }

    public synchronized GameRoom reconnect(Channel conn, String sessionId) {
        String playerId = sessionToPlayer.get(sessionId);
        if (playerId == null) return null;

        Player oldPlayer = playerById.get(playerId);
        if (oldPlayer == null) return null;

        String roomId = playerRoom.get(playerId);
        if (roomId == null) return null;

        GameRoom room = rooms.get(roomId);
        if (room == null) return null;

        playerByConn.remove(oldPlayer.getConnection());

        Player newPlayer = new Player(playerId, oldPlayer.getName(), conn, sessionId);
        newPlayer.setStone(oldPlayer.getStone());
        newPlayer.setWins(oldPlayer.getWins());
        newPlayer.setLosses(oldPlayer.getLosses());
        room.replacePlayer(oldPlayer, newPlayer);

        playerByConn.put(conn, newPlayer);
        playerById.put(playerId, newPlayer);

        logger.info("[AUDIT] reconnect playerId={} roomId={}", playerId, roomId);
        return room;
    }

    private Player getOrCreatePlayer(Channel conn, String playerName) {
        Player player = playerByConn.get(conn);
        if (player == null) {
            String playerId = UUID.randomUUID().toString().substring(0, 8);
            String sessionId = UUID.randomUUID().toString().substring(0, 12);
            player = new Player(playerId, playerName, conn, sessionId);
            playerByConn.put(conn, player);
            playerById.put(playerId, player);
            sessionToPlayer.put(sessionId, playerId);
        } else {
            player.setName(playerName);
        }
        return player;
    }

    public void broadcastRoomsList() {
        String dataJson = getRoomsListJson();
        GameMessage msg = new GameMessage(GameMessage.Type.ROOM_LIST);
        msg.setData(dataJson);
        String json = gson.toJson(msg);

        for (Player p : playerByConn.values()) {
            if (!playerRoom.containsKey(p.getId()) && p.isConnected()) {
                p.sendMessage(json);
            }
        }
    }

    public synchronized void handleDisconnect(Channel conn) {
        Player player = playerByConn.remove(conn);
        if (player == null) return;

        playerById.remove(player.getId());
        sessionToPlayer.remove(player.getSessionId());
        leaveRoom(player);
        // IP 释放由 GameServerHandler.handlerRemoved 统一处理
    }

    public synchronized void leaveRoom(Channel conn) {
        Player player = playerByConn.get(conn);
        if (player != null) {
            leaveRoom(player);
        }
    }

    private void leaveRoom(Player player) {
        String roomId = playerRoom.remove(player.getId());
        if (roomId != null) {
            GameRoom room = rooms.get(roomId);
            if (room != null) {
                room.handleDisconnect(player);
                if (room.getPlayerCount() == 0) {
                    GameMessage kickMsg = new GameMessage(GameMessage.Type.ERROR);
                    kickMsg.setMessage("房间内玩家已全部离开，房间解散");
                    String kickJson = gson.toJson(kickMsg);

                    for (Player spectator : room.getSpectators()) {
                        playerRoom.remove(spectator.getId());
                        spectator.sendMessage(kickJson);
                    }
                    room.getSpectators().clear();

                    rooms.remove(roomId);
                    logger.info("[CLEANUP] 房间 {} 已清理", roomId);
                }
                broadcastRoomsList();
            }
        }
    }

    public Player getPlayer(Channel conn) {
        return playerByConn.get(conn);
    }

    public GameRoom getRoomByPlayer(Player player) {
        String roomId = playerRoom.get(player.getId());
        if (roomId == null) return null;
        return rooms.get(roomId);
    }

    private GameRoom findWaitingRoom() {
        for (GameRoom room : rooms.values()) {
            if (room.getState() == GameRoom.State.WAITING && !room.isFull()) {
                return room;
            }
        }
        return null;
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalPlayers", playerByConn.size());
        stats.put("totalRooms", rooms.size());
        stats.put("playingRooms", rooms.values().stream()
                .filter(r -> r.getState() == GameRoom.State.PLAYING).count());
        stats.put("waitingRooms", rooms.values().stream()
                .filter(r -> r.getState() == GameRoom.State.WAITING).count());
        return stats;
    }

    /**
     * 优雅停机：通知所有房间内玩家服务器即将关闭
     */
    public void notifyAllShutdown() {
        for (GameRoom room : rooms.values()) {
            room.notifyShutdown();
        }
        logger.info("[SHUTDOWN] 已通知所有 {} 个房间的玩家", rooms.size());
    }
}
