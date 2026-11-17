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
 *
 * 断线重连：连接断开时保留 playerById/sessionToPlayer/playerRoom 映射，
 * 玩家在房间内保留席位（宽限期见 GameRoom），超过 {@link #SESSION_TTL_MS} 未重连才清理。
 */
@Service
public class RoomManager {

    private static final Logger logger = LoggerFactory.getLogger(RoomManager.class);
    private static final Gson gson = new Gson();
    private static final int MAX_CONNECTIONS_PER_IP = 16;
    private static final long ROOM_IDLE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);
    /** 断线会话保留时长（超时后清理 playerId/session 映射并踢出房间） */
    private static final long SESSION_TTL_MS = TimeUnit.MINUTES.toMillis(10);

    // 房间ID -> 房间
    private final ConcurrentHashMap<String, GameRoom> rooms = new ConcurrentHashMap<>();
    // WebSocket Channel -> 玩家
    private final ConcurrentHashMap<Channel, Player> playerByConn = new ConcurrentHashMap<>();
    // 玩家ID -> 玩家（断线后保留，供重连）
    private final ConcurrentHashMap<String, Player> playerById = new ConcurrentHashMap<>();
    // SessionID -> 玩家ID（断线重连用，断线后保留）
    private final ConcurrentHashMap<String, String> sessionToPlayer = new ConcurrentHashMap<>();
    // 玩家ID -> 房间ID
    private final ConcurrentHashMap<String, String> playerRoom = new ConcurrentHashMap<>();
    // IP -> 连接数（IP 限流）
    private final ConcurrentHashMap<String, Integer> ipConnectionCount = new ConcurrentHashMap<>();
    // 玩家ID -> 断线时刻（用于 TTL 清理）
    private final ConcurrentHashMap<String, Long> disconnectedAt = new ConcurrentHashMap<>();
    // 连接 -> 持久身份（大厅在线判定用；LEAVE 不清，掉线才清，
    // 否则玩家离开房间回大厅后好友会误判其离线）
    private final ConcurrentHashMap<Channel, String> identityByConn = new ConcurrentHashMap<>();
    // 持久身份 -> 最近一次绑定的昵称（人不在房间时 presence 仍能给出展示名）
    private final ConcurrentHashMap<String, String> identityNames = new ConcurrentHashMap<>();

    // 僵尸房间清理定时任务
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Room-Cleanup-Thread");
        t.setDaemon(true);
        return t;
    });

    private final EloService eloService;
    private final GameRecordService recordService;
    private final SeasonService seasonService;
    private final FriendService friends;

    public RoomManager(MeterRegistry meterRegistry, EloService eloService,
                       GameRecordService recordService, SeasonService seasonService,
                       FriendService friendService) {
        this.eloService = eloService;
        this.recordService = recordService;
        this.seasonService = seasonService;
        this.friends = friendService;
        // 每 60 秒扫描一次僵尸房间/过期会话
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
     * 清理超时空房间 + 过期断线会话
     */
    private synchronized void cleanupIdleRooms() {
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

        // 清理断线超过 TTL 的会话（踢出房间 + 删除重连映射）
        for (Map.Entry<String, Long> entry : disconnectedAt.entrySet()) {
            String playerId = entry.getKey();
            if (now - entry.getValue() <= SESSION_TTL_MS) continue;
            Player player = playerById.remove(playerId);
            if (player != null) {
                if (player.getSessionId() != null) {
                    sessionToPlayer.remove(player.getSessionId());
                }
                forceLeave(player);
                logger.info("[CLEANUP] 清理过期断线会话 playerId={} playerName={}", playerId, player.getName());
            }
            disconnectedAt.remove(playerId);
        }
    }

    /** 凭证仅改变持久身份；实时连接仍使用独立 playerId，重名不会合并席位。 */
    public synchronized boolean bindIdentity(Channel conn, String name, String identityId) {
        if (identityId == null) return false;
        Player player = getOrCreatePlayer(conn, name);
        if (player.getIdentityId() != null && !identityId.equals(player.getIdentityId())) return false;
        // 上线边缘触发：该身份此前没有任何活动连接才算「上线」，避免每次 JOIN/换房都打扰好友
        boolean wasOnline = identityByConn.containsValue(identityId);
        player.setIdentityId(identityId);
        identityByConn.put(conn, identityId);
        identityNames.put(identityId, player.getName());
        if (!wasOnline) {
            pushFriendEvent("online", identityId, player.getName());
        }
        return true;
    }

    /** 该身份当前是否在线（连接级判定，大厅绑定即在线）——私聊可达性检查用 */
    public boolean isIdentityOnline(String identityId) {
        return identityId != null && identityByConn.containsValue(identityId);
    }

    /**
     * 大厅推送：把一条消息发给某身份的全部活动连接（好友系统实时提醒用）。
     * 连接级寻址，不依赖房间成员关系——人在大厅也能收到。
     */
    public void sendToIdentity(String identityId, GameMessage msg) {
        if (identityId == null || msg == null) return;
        String json = gson.toJson(msg);
        for (Map.Entry<Channel, String> e : identityByConn.entrySet()) {
            if (!identityId.equals(e.getValue())) continue;
            Player p = playerByConn.get(e.getKey());
            if (p != null && p.isConnected()) p.sendMessage(json);
        }
    }

    /** 给某身份的全部在线好友推送同一条好友事件（下线/上线广播用）。 */
    public void pushFriendEvent(String kind, String aboutId, String aboutName) {
        if (friends == null || aboutId == null) return;
        GameMessage msg = friendEvent(kind, aboutId, aboutName);
        for (String friendId : friends.friendsOf(aboutId)) {
            sendToIdentity(friendId, msg);
        }
    }

    /** 构造好友事件消息（data 为 JSON：{kind, id, name}；kind = online/offline/request/accepted）。 */
    public static GameMessage friendEvent(String kind, String aboutId, String aboutName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", kind);
        payload.put("id", aboutId);
        payload.put("name", aboutName);
        GameMessage msg = new GameMessage(GameMessage.Type.FRIEND_EVENT);
        msg.setData(gson.toJson(payload));
        return msg;
    }

    public Player getPlayerBySession(String sessionId) {
        String id = sessionId == null ? null : sessionToPlayer.get(sessionId);
        return id == null ? null : playerById.get(id);
    }

    public String getRoomsListJson() {
        return gson.toJson(getRoomsList());
    }

    public List<RoomListItemDto> getRoomsList() {
        List<RoomListItemDto> list = new ArrayList<>();
        for (GameRoom room : rooms.values()) {
            // 私有房只对知道房号的人可见，不进公共列表
            if (room.isPrivateRoom()) continue;
            List<String> playerNames = room.getPlayers().stream()
                    .map(Player::getName)
                    .collect(Collectors.toList());
            RoomListItemDto item = new RoomListItemDto(
                    room.getRoomId(),
                    room.getState().name(),
                    room.getPlayerCount(),
                    room.getSpectatorCount(),
                    playerNames,
                    room.getGameType().name()
            );
            item.setMinPlayers(room.getGameType().getMinPlayers());
            item.setMaxPlayers(room.getGameType().getMaxPlayers());
            item.setTargetPlayers(room.getTargetPlayers());
            list.add(item);
        }
        return list;
    }

    public List<Map<String, Object>> getLeaderboard(String gameTypeName, int topN) {
        GameType type = GameType.fromString(gameTypeName);
        return eloService.getLeaderboard(type, topN);
    }

    public synchronized GameRoom joinGame(Channel conn, String playerName, String targetRoomId, GameType gameType) {
        return joinGame(conn, playerName, targetRoomId, gameType, null);
    }

    /**
     * @param deck 卡牌套牌名（null = 不指定/默认）
     */
    public synchronized GameRoom joinGame(Channel conn, String playerName, String targetRoomId,
                                          GameType gameType, String deck) {
        return joinGame(conn, playerName, targetRoomId, gameType, deck, null);
    }

    /**
     * @param deckCards 自定义套牌配比（JSON），仅 deck=CUSTOM 时有意义
     */
    public synchronized GameRoom joinGame(Channel conn, String playerName, String targetRoomId,
                                          GameType gameType, String deck, String deckCards) {
        return joinGame(conn, playerName, targetRoomId, gameType, deck, deckCards, false);
    }

    /**
     * @param createRoom true = 强制新建私有房间（大厅「创建房间」），
     *                   false = 自动匹配同类型等待房间
     */
    public synchronized GameRoom joinGame(Channel conn, String playerName, String targetRoomId,
                                          GameType gameType, String deck, String deckCards,
                                          boolean createRoom) {
        return joinGame(conn, playerName, targetRoomId, gameType, deck, deckCards, createRoom, 0);
    }

    public synchronized GameRoom joinGame(Channel conn, String playerName, String targetRoomId,
                                          GameType gameType, String deck, String deckCards,
                                          boolean createRoom, int requestedPlayers) {
        int targetPlayers;
        try { targetPlayers = gameType.resolvePlayerCount(requestedPlayers); }
        catch (IllegalArgumentException e) { return null; }
        Player player = getOrCreatePlayer(conn, playerName);
        if (deck != null && !deck.isEmpty()) {
            player.setDeck(deck);
        }
        if (deckCards != null && !deckCards.isEmpty()) {
            player.setDeckCards(deckCards);
        }

        GameRoom room = null;
        if (targetRoomId != null && !targetRoomId.trim().isEmpty()) {
            // 显式房号：只认这一间，找不到就明确失败。
            // 之前找不到会继续走自动匹配，等于「输错房号被塞进陌生人的房间」。
            String normalized = normalizeRoomId(targetRoomId);
            room = rooms.get(normalized);
            if (room == null) return null;
            if (room.getState() != GameRoom.State.WAITING
                    || room.isFull()
                    || room.getGameType() != gameType) {
                return null;
            }
        }

        if (room == null && !createRoom) {
            room = findWaitingRoom(gameType, targetPlayers);
        }

        if (room == null) {
            String roomId = "ROOM-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
            // createRoom=true → 私有房：不进大厅列表、不参与匹配，只能凭房号加入
            room = new GameRoom(roomId, gameType, createRoom, targetPlayers);
            room.setEloService(eloService);
            room.setRecordService(recordService);
            if (seasonService != null) room.setSeasonService(seasonService);
            rooms.put(roomId, room);
        }

        GameRoom previousRoom = getRoomByPlayer(player);
        if (previousRoom != null && previousRoom != room) player = switchRoom(player, previousRoom);
        if (!room.addPlayer(player)) return null;
        playerRoom.put(player.getId(), room.getRoomId());

        broadcastRoomsList();
        return room;
    }

    /**
     * 房号归一化：接受「ROOM-ABC123」「abc123」「 ABC123 」三种写法。
     *
     * 玩家从聊天里复制房号时经常会带上/去掉前缀，前端不该是唯一一道宽容层。
     */
    public static String normalizeRoomId(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase();
        if (s.isEmpty()) return null;
        return s.startsWith("ROOM-") ? s : "ROOM-" + s;
    }

    public synchronized GameRoom spectateGame(Channel conn, String playerName, String targetRoomId) {
        GameRoom room = rooms.get(normalizeRoomId(targetRoomId));
        if (room == null) return null;

        Player player = getOrCreatePlayer(conn, playerName);
        GameRoom previousRoom = getRoomByPlayer(player);
        // 参战者不能同时成为本房观众，否则会收到两份视图并可继续用原座位出招。
        if (previousRoom == room && room.getPlayers().contains(player)) return null;
        if (previousRoom != null && previousRoom != room) player = switchRoom(player, previousRoom);
        room.addSpectator(player);
        playerRoom.put(player.getId(), room.getRoomId());

        broadcastRoomsList();
        return room;
    }

    public synchronized GameRoom addAI(Channel conn, int difficulty) {
        return addAI(conn, difficulty, null);
    }

    /**
     * @param aiDeck AI 使用的套牌名（null = 默认均衡）
     */
    public synchronized GameRoom addAI(Channel conn, int difficulty, String aiDeck) {
        Player player = playerByConn.get(conn);
        if (player == null) return null;

        GameRoom room = getRoomByPlayer(player);
        if (room != null && !room.isFull()) {
            room.addAI(difficulty, aiDeck);
            broadcastRoomsList();
            return room;
        }
        return null;
    }

    public synchronized GameRoom addAI(Channel conn) {
        return addAI(conn, 4);
    }

    /**
     * 断线重连：根据 sessionId 恢复席位（连接断开后会话保留 SESSION_TTL_MS）
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

        if (!room.hasPlayer(oldPlayer)) return null;
        Player boundPlayer = playerByConn.get(conn);
        if (boundPlayer != null && boundPlayer != oldPlayer) return null;
        if (oldPlayer.getConnection() == conn) return room;

        Player newPlayer = new Player(playerId, oldPlayer.getName(), conn, sessionId);
        newPlayer.setIdentityId(oldPlayer.getIdentityId());
        newPlayer.setDeck(oldPlayer.getDeck());
        newPlayer.setDeckCards(oldPlayer.getDeckCards());
        newPlayer.setStone(oldPlayer.getStone());
        newPlayer.setWins(oldPlayer.getWins());
        newPlayer.setLosses(oldPlayer.getLosses());
        newPlayer.setElo(oldPlayer.getElo());
        newPlayer.setLastEloChange(oldPlayer.getLastEloChange());

        playerByConn.remove(oldPlayer.getConnection(), oldPlayer);
        room.replacePlayer(oldPlayer, newPlayer);
        playerByConn.put(conn, newPlayer);
        playerById.put(playerId, newPlayer);
        disconnectedAt.remove(playerId);
        // 重连后新连接接管该身份的大厅在线状态（旧连接随后关闭，不会触发下线边缘）
        if (newPlayer.getIdentityId() != null) {
            identityByConn.put(conn, newPlayer.getIdentityId());
        }
        // 切换后旧连接不再能行动，也不继续接收房间消息。
        if (oldPlayer.getConnection() != null) oldPlayer.getConnection().close();

        logger.info("[AUDIT] reconnect playerId={} roomId={}", playerId, roomId);
        return room;
    }

    private Player getOrCreatePlayer(Channel conn, String playerName) {
        Player player = playerByConn.get(conn);
        if (player == null) {
            String playerId = UUID.randomUUID().toString().substring(0, 8);
            String sessionId = UUID.randomUUID().toString().replace("-", "");
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

    /**
     * 连接断开（掉线）：保留会话与席位，玩家可凭 sessionId 重连
     */
    public synchronized void handleDisconnect(Channel conn) {
        // 连接级身份先摘除：reconnect 关闭旧连接也会走到这里，此时新连接已登记，不会误判离线
        String identityId = identityByConn.remove(conn);
        Player player = playerByConn.remove(conn);
        if (player == null) return;

        GameRoom room = getRoomByPlayer(player);
        if (room != null) {
            room.handleDisconnect(player);
        }

        disconnectedAt.put(player.getId(), System.currentTimeMillis());
        // 下线边缘：该身份没有其他活动连接才通知好友（多标签页/双端掉一个不算下线）
        if (identityId != null && !identityByConn.containsValue(identityId)) {
            pushFriendEvent("offline", identityId, player.getName());
        }
        // IP 释放由 GameServerHandler.handlerRemoved 统一处理
        broadcastRoomsList();
    }

    public synchronized void leaveRoom(Channel conn) {
        Player player = playerByConn.remove(conn);
        if (player != null) {
            forceLeave(player);
        }
    }

    /**
     * 显式离场：移除会话映射与席位（LEAVE / TTL 清理）
     */
    private void forceLeave(Player player) {
        playerById.remove(player.getId());
        if (player.getSessionId() != null) {
            sessionToPlayer.remove(player.getSessionId());
        }
        disconnectedAt.remove(player.getId());

        String roomId = playerRoom.remove(player.getId());
        if (roomId != null) {
            GameRoom room = rooms.get(roomId);
            if (room != null) {
                room.removePlayer(player);
                removeEmptyRoom(room);
                broadcastRoomsList();
            }
        }
    }

    /** 离场的旧对象保留原座位用于存档，新房间使用独立对象，避免覆盖历史参与者。 */
    private Player switchRoom(Player player, GameRoom previousRoom) {
        previousRoom.removePlayer(player);
        playerRoom.remove(player.getId());
        removeEmptyRoom(previousRoom);
        Player replacement = new Player(player.getId(), player.getName(), player.getConnection(), player.getSessionId());
        replacement.setIdentityId(player.getIdentityId());
        replacement.setDeck(player.getDeck());
        replacement.setDeckCards(player.getDeckCards());
        replacement.setWins(player.getWins());
        replacement.setLosses(player.getLosses());
        replacement.setElo(player.getElo());
        playerByConn.put(replacement.getConnection(), replacement);
        playerById.put(replacement.getId(), replacement);
        return replacement;
    }

    private void removeEmptyRoom(GameRoom room) {
        if (room.getPlayerCount() == 0) {
            GameMessage kickMsg = new GameMessage(GameMessage.Type.ERROR);
            kickMsg.setMessage("房间内玩家已全部离开，房间解散");
            String kickJson = gson.toJson(kickMsg);

            for (Player spectator : room.getSpectators()) {
                playerRoom.remove(spectator.getId(), room.getRoomId());
                spectator.sendMessage(kickJson);
            }
            room.getSpectators().clear();

            rooms.remove(room.getRoomId(), room);
            room.notifyShutdown();
            logger.info("[CLEANUP] 房间 {} 已清理", room.getRoomId());
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

    private GameRoom findWaitingRoom(GameType gameType, int targetPlayers) {
        for (GameRoom room : rooms.values()) {
            if (room.getState() != GameRoom.State.WAITING
                    || room.isFull()
                    || room.getGameType() != gameType
                    // 私有房只能凭房号加入，不能被自动匹配
                    || room.isPrivateRoom() || room.getTargetPlayers() != targetPlayers) {
                continue;
            }
            // 只匹配「还坐着人且在线」的等待房间。
            // 掉线玩家仍有 60s 重连宽限，这期间座位必须给他留着；
            // 若此时把新人匹配进来，座位被占，原玩家重连回来就无处可坐，
            // 宽限期也就失去意义了。这类房间由 cleanupIdleRooms 之后回收。
            boolean ghostRoom = room.getPlayers().stream().anyMatch(p -> !p.isConnected());
            if (ghostRoom) continue;
            return room;
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
     * 按持久身份查询在线状态与房间位置（好友面板展示 + 一键加入好友房间）。
     *
     * 在线判定走连接级身份表（identityByConn）：人在大厅但没进房间也算在线；
     * 同一身份可能同时开着多个标签页（多个 Player），优先取在线的那个取昵称/房间；
     * 房间位置对好友可见是「邀请」语义 —— 私有房也返回房号，因为只有好友能查到。
     */
    public Map<String, Object> getIdentityPresence(String identityId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("online", identityId != null && identityByConn.containsValue(identityId));
        if (identityId == null) return result;
        Player online = null;
        Player any = null;
        for (Player p : playerById.values()) {
            if (!identityId.equals(p.getIdentityId())) continue;
            if (online == null && p.isConnected()) online = p;
            if (any == null) any = p;
        }
        Player best = online != null ? online : any;
        if (best == null) {
            // 人在大厅但没进过房间（或刚 LEAVE）：用最近一次绑定的昵称兜底
            String lastName = identityNames.get(identityId);
            if (lastName != null) result.put("name", lastName);
            return result;
        }
        result.put("name", best.getName());
        if (!best.isConnected()) return result;
        String roomId = playerRoom.get(best.getId());
        if (roomId == null) return result;
        GameRoom room = rooms.get(roomId);
        if (room == null) return result;
        result.put("roomId", room.getRoomId());
        result.put("roomState", room.getState().name());
        result.put("gameType", room.getGameType().name());
        result.put("joinable", room.getState() == GameRoom.State.WAITING && !room.isFull());
        return result;
    }

    /**
     * 优雅停机：通知所有房间内玩家服务器即将关闭
     */
    public void notifyAllShutdown() {
        cleanupExecutor.shutdownNow();
        for (GameRoom room : rooms.values()) {
            room.notifyShutdown();
        }
        logger.info("[SHUTDOWN] 已通知所有 {} 个房间的玩家", rooms.size());
    }
}
