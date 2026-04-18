package com.gomoku.game;

import com.gomoku.dto.RoomListItemDto;
import com.gomoku.model.GameMessage;
import com.gomoku.model.Player;
import com.google.gson.Gson;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 房间管理器 - 管理所有游戏房间和玩家
 */
@Service
public class RoomManager {

    private static final Logger logger = LoggerFactory.getLogger(RoomManager.class);
    private static final Gson gson = new Gson();

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

    /**
     * 获取所有房间列表（DTO）
     */
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

    /**
     * 玩家加入：指定房间或自动匹配
     */
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
            rooms.put(roomId, room);
        }

        room.addPlayer(player);
        playerRoom.put(player.getId(), room.getRoomId());

        broadcastRoomsList();
        return room;
    }

    /**
     * 玩家观战
     */
    public synchronized GameRoom spectateGame(Channel conn, String playerName, String targetRoomId) {
        GameRoom room = rooms.get(targetRoomId);
        if (room == null) return null;

        Player player = getOrCreatePlayer(conn, playerName);
        room.addSpectator(player);
        playerRoom.put(player.getId(), room.getRoomId());

        broadcastRoomsList();
        return room;
    }

    /**
     * 为单人房间添加电脑
     */
    public synchronized GameRoom addAI(Channel conn) {
        Player player = playerByConn.get(conn);
        if (player == null) return null;

        GameRoom room = getRoomByPlayer(player);
        if (room != null && !room.isFull()) {
            room.addAI();
            broadcastRoomsList();
            return room;
        }
        return null;
    }

    /**
     * 断线重连
     */
    public synchronized GameRoom reconnect(Channel conn, String sessionId) {
        String playerId = sessionToPlayer.get(sessionId);
        if (playerId == null) return null;

        Player oldPlayer = playerById.get(playerId);
        if (oldPlayer == null) return null;

        String roomId = playerRoom.get(playerId);
        if (roomId == null) return null;

        GameRoom room = rooms.get(roomId);
        if (room == null) return null;

        // 更新连接：先移除旧连接映射，再创建新 Player 并更新房间中的引用
        playerByConn.remove(oldPlayer.getConnection());

        // 创建新 Player 对象，保留原有的 stone/wins/losses
        Player newPlayer = new Player(playerId, oldPlayer.getName(), conn, sessionId);
        newPlayer.setStone(oldPlayer.getStone());
        newPlayer.setWins(oldPlayer.getWins());
        newPlayer.setLosses(oldPlayer.getLosses());
        // 需要确保新玩家对象在房间中替换旧引用
        room.replacePlayer(oldPlayer, newPlayer);

        playerByConn.put(conn, newPlayer);
        playerById.put(playerId, newPlayer);

        logger.info("玩家 {} 断线重连，房间 {}", newPlayer.getName(), roomId);
        return room;
    }

    /**
     * 创建或获取玩家
     */
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

    /**
     * 广播房间列表给所有不在房间内的玩家
     */
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

    /**
     * 玩家断开
     */
    public synchronized void handleDisconnect(Channel conn) {
        Player player = playerByConn.remove(conn);
        if (player == null) return;

        playerById.remove(player.getId());
        sessionToPlayer.remove(player.getSessionId());
        leaveRoom(player);
    }

    /**
     * 离开房间（通过 Channel）
     */
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
                // 清理空房间
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
                    logger.info("房间 {} 已清理", roomId);
                }
                broadcastRoomsList();
            }
        }
    }

    /**
     * 根据连接获取玩家
     */
    public Player getPlayer(Channel conn) {
        return playerByConn.get(conn);
    }

    /**
     * 根据玩家获取其房间
     */
    public GameRoom getRoomByPlayer(Player player) {
        String roomId = playerRoom.get(player.getId());
        if (roomId == null) return null;
        return rooms.get(roomId);
    }

    /**
     * 查找等待中的房间
     */
    private GameRoom findWaitingRoom() {
        for (GameRoom room : rooms.values()) {
            if (room.getState() == GameRoom.State.WAITING && !room.isFull()) {
                return room;
            }
        }
        return null;
    }

    /**
     * 获取服务器统计信息
     */
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
}
