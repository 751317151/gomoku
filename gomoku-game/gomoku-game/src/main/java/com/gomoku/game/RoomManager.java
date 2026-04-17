package com.gomoku.game;

import com.gomoku.model.GameMessage;
import com.gomoku.model.Player;
import com.google.gson.Gson;
import io.netty.channel.Channel;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 房间管理器 - 管理所有游戏房间和玩家
 */
@Service
public class RoomManager {

    private static final Logger logger = Logger.getLogger(RoomManager.class.getName());
    private static final Gson gson = new Gson();

    // 房间ID -> 房间
    private final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();
    // WebSocket -> 玩家
    private final Map<Channel, Player> playerByConn = new ConcurrentHashMap<>();
    // 玩家ID -> 玩家
    private final Map<String, Player> playerById = new ConcurrentHashMap<>();
    // 玩家ID -> 房间ID
    private final Map<String, String> playerRoom = new ConcurrentHashMap<>();

    /**
     * 获取所有房间列表
     */
    public String getRoomsListJson() {
        StringBuilder sb = new StringBuilder("[");
        int count = 0;
        for (GameRoom room : rooms.values()) {
            if (count > 0) sb.append(",");
            sb.append("{\"roomId\":\"").append(room.getRoomId()).append("\",");
            sb.append("\"state\":\"").append(room.getState().name()).append("\",");
            sb.append("\"playerCount\":").append(room.getPlayerCount()).append(",");
            sb.append("\"spectatorCount\":").append(room.getSpectatorCount()).append(",");
            
            sb.append("\"players\":[");
            for (int i = 0; i < room.getPlayers().size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(room.getPlayers().get(i).getName()).append("\"");
            }
            sb.append("]}");
            count++;
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 玩家加入：指定房间或自动匹配
     */
    public GameRoom joinGame(Channel conn, String playerName, String targetRoomId) {
        // 创建或获取玩家
        Player player = getOrCreatePlayer(conn, playerName);

        GameRoom room = null;
        if (targetRoomId != null && !targetRoomId.trim().isEmpty()) {
            room = rooms.get(targetRoomId);
            if (room != null && room.isFull()) {
                return null; // 房间已满
            }
        }

        if (room == null) {
            room = findWaitingRoom();
        }
        
        if (room == null) {
            // 创建新房间
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
    public GameRoom spectateGame(Channel conn, String playerName, String targetRoomId) {
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
    public GameRoom addAI(Channel conn) {
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

    private Player getOrCreatePlayer(Channel conn, String playerName) {
        Player player = playerByConn.get(conn);
        if (player == null) {
            String playerId = UUID.randomUUID().toString().substring(0, 8);
            player = new Player(playerId, playerName, conn);
            playerByConn.put(conn, player);
            playerById.put(playerId, player);
        } else {
            player.setName(playerName);
        }
        return player;
    }

    /**
     * 广播房间列表给所有不在房间内的玩家
     */
    public void broadcastRoomsList() {
        String json = "{\"type\":\"ROOM_LIST\",\"data\":" + getRoomsListJson() + "}";
        for (Player p : playerByConn.values()) {
            if (!playerRoom.containsKey(p.getId())) {
                p.sendMessage(json);
            }
        }
    }

    /**
     * 玩家断开
     */
    public void handleDisconnect(Channel conn) {
        Player player = playerByConn.remove(conn);
        if (player == null) return;

        playerById.remove(player.getId());
        leaveRoom(player);
    }
    
    /**
     * 离开房间
     */
    public void leaveRoom(Channel conn) {
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
                    logger.info("房间 " + roomId + " 已清理");
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
