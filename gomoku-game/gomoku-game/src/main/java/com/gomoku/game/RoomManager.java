package com.gomoku.game;

import com.gomoku.dto.RoomListItemDto;
import com.gomoku.model.GameMessage;
import com.gomoku.model.Player;
import com.google.gson.Gson;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import java.util.function.Predicate;
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
    // 连接 -> 当次绑定使用的凭证。只用于区分同账号不同设备，不暴露给客户端。
    private final ConcurrentHashMap<Channel, String> tokenByConn = new ConcurrentHashMap<>();
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
    private final PartyService parties;
    private final EventService events;
    private final ProductAnalyticsService analytics;

    /**
     * 入座上下文：JOIN 成功后才把「哪次入口、从入座到开局等了多久」写进产品事件。
     * 存 playerId 而非 Player，避免重连替换对象后丢失。
     */
    private final ConcurrentHashMap<String, JoinContext> joinContexts = new ConcurrentHashMap<>();

    /**
     * Spring 装配入口。RoomManager 同时保留给单测的无统计构造器，
     * 多构造器场景必须显式标注，否则 Spring 会报「No default constructor found」。
     */
    @Autowired
    public RoomManager(MeterRegistry meterRegistry, EloService eloService,
                       GameRecordService recordService, SeasonService seasonService,
                       FriendService friendService, PartyService partyService,
                       EventService eventService, ObjectProvider<ProductAnalyticsService> analyticsProvider) {
        this(meterRegistry, eloService, recordService, seasonService, friendService, partyService,
                eventService, analyticsProvider.getIfAvailable());
    }

    /** 单测与不带统计的嵌入式启动可直接用这个构造器。 */
    public RoomManager(MeterRegistry meterRegistry, EloService eloService,
                       GameRecordService recordService, SeasonService seasonService,
                       FriendService friendService, PartyService partyService,
                       EventService eventService) {
        this(meterRegistry, eloService, recordService, seasonService, friendService, partyService,
                eventService, (ProductAnalyticsService) null);
    }

    public RoomManager(MeterRegistry meterRegistry, EloService eloService,
                       GameRecordService recordService, SeasonService seasonService,
                       FriendService friendService, PartyService partyService,
                       EventService eventService, ProductAnalyticsService analytics) {
        this.eloService = eloService;
        this.recordService = recordService;
        this.seasonService = seasonService;
        this.friends = friendService;
        this.parties = partyService;
        this.events = eventService;
        this.analytics = analytics;
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
                clearJoinContexts(entry.getKey());
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
        if (parties != null) {
            Set<String> online = identityByConn.entrySet().stream().filter(e -> e.getKey().isActive())
                    .map(Map.Entry::getValue).collect(Collectors.toSet());
            parties.expireOfflineMembers(online);
        }
    }

    /** 凭证仅改变持久身份；实时连接仍使用独立 playerId，重名不会合并席位。 */
    public synchronized boolean bindIdentity(Channel conn, String name, String identityId) {
        return bindIdentity(conn, name, identityId, null);
    }

    /**
     * 绑定大厅身份；同时记录连接使用的凭证，便于账号撤销时只断开对应设备，
     * 而不是误伤同一账号在当前设备上的连接。
     */
    public synchronized boolean bindIdentity(Channel conn, String name, String identityId, String token) {
        if (identityId == null) return false;
        String bound = identityByConn.get(conn);
        if (bound != null && !identityId.equals(bound)) return false;
        Player existing = playerByConn.get(conn);
        if (existing != null && existing.getIdentityId() != null && !identityId.equals(existing.getIdentityId())) return false;
        Player player = getOrCreatePlayer(conn, name);
        // 上线边缘触发：该身份此前没有任何活动连接才算「上线」，避免每次 JOIN/换房都打扰好友
        boolean wasOnline = identityByConn.containsValue(identityId);
        player.setIdentityId(identityId);
        identityByConn.put(conn, identityId);
        if (token != null) tokenByConn.put(conn, token);
        identityNames.put(identityId, player.getName());
        if (parties != null) parties.memberConnected(identityId);
        if (!wasOnline) {
            pushFriendEvent("online", identityId, player.getName());
        }
        return true;
    }

    /**
     * 凭证轮换或撤销后，把旧 token 绑定的活动连接迁移到新身份 ID。
     *
     * 账号身份 ID 本身不变，但这个入口为未来的身份合并/迁移保留一致性：
     * 不改变玩家席位，只更新好友在线、组队与私聊使用的映射。
     */
    public synchronized boolean rebindIdentity(Channel conn, String oldIdentityId, String newIdentityId) {
        if (conn == null || oldIdentityId == null || newIdentityId == null) return false;
        if (!oldIdentityId.equals(identityByConn.get(conn))) return false;
        identityByConn.put(conn, newIdentityId);
        Player player = playerByConn.get(conn);
        if (player != null && oldIdentityId.equals(player.getIdentityId())) {
            player.setIdentityId(newIdentityId);
        }
        identityNames.put(newIdentityId, player == null ? playerNameOf(oldIdentityId) : player.getName());
        if (parties != null) {
            parties.memberConnected(newIdentityId);
        }
        return true;
    }

    /** 断开属于指定身份的全部活动连接；用于账号主动退出或会话被撤销。 */
    public synchronized int disconnectIdentity(String identityId, String reason) {
        if (identityId == null) return 0;
        String message = reason == null || reason.isEmpty() ? "账号会话已失效，请重新登录" : reason;
        int count = 0;
        for (Map.Entry<Channel, String> entry : new ArrayList<>(identityByConn.entrySet())) {
            if (!identityId.equals(entry.getValue())) continue;
            Channel conn = entry.getKey();
            GameMessage out = new GameMessage(GameMessage.Type.ERROR);
            out.setMessage(message);
            Player.sendMessageTo(conn, gson.toJson(out));
            conn.close();
            count++;
        }
        return count;
    }

    /**
     * 断开同身份下不属于当前凭证的连接；用于「退出其他设备」。
     * 未记录凭证的旧连接无法区分，保持不动，避免误踢当前设备。
     */
    public synchronized int disconnectOtherSessions(String identityId, String currentToken, String reason) {
        if (identityId == null || currentToken == null) return 0;
        String message = reason == null || reason.isEmpty() ? "账号已在其他设备退出" : reason;
        int count = 0;
        for (Map.Entry<Channel, String> entry : new ArrayList<>(identityByConn.entrySet())) {
            if (!identityId.equals(entry.getValue())) continue;
            String boundToken = tokenByConn.get(entry.getKey());
            if (boundToken == null || boundToken.equals(currentToken)) continue;
            Channel conn = entry.getKey();
            GameMessage out = new GameMessage(GameMessage.Type.ERROR);
            out.setMessage(message);
            Player.sendMessageTo(conn, gson.toJson(out));
            conn.close();
            count++;
        }
        return count;
    }

    /** 该身份当前是否在线（连接级判定，大厅绑定即在线）——私聊可达性检查用 */
    public boolean isIdentityOnline(String identityId) {
        return identityId != null && identityByConn.entrySet().stream()
                .anyMatch(e -> identityId.equals(e.getValue()) && e.getKey().isActive());
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
            // LEAVE 已释放座位对象，但大厅连接仍绑定该身份。
            Player.sendMessageTo(e.getKey(), json);
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

    /**
     * 构造队伍事件（复用 FRIEND_EVENT 通道，前端按 kind 前缀区分）。
     * kind = party_invite / party_joined / party_left / party_ready / party_disbanded。
     * payload 额外带 partyId 与操作者身份，前端据此刷新队伍面板。
     */
    public static GameMessage partyEvent(String kind, String aboutId, String aboutName, String partyId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", kind);
        payload.put("id", aboutId);
        payload.put("name", aboutName);
        payload.put("partyId", partyId);
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
            List<Player> occupants = new ArrayList<>(room.getPlayers());
            List<String> playerNames = occupants.stream().map(Player::getName).collect(Collectors.toList());
            RoomListItemDto item = new RoomListItemDto(
                    room.getRoomId(),
                    room.getState().name(),
                    occupants.size(),
                    room.getSpectatorCount(),
                    playerNames,
                    room.getGameType().name()
            );
            item.setMinPlayers(room.getGameType().getMinPlayers());
            item.setMaxPlayers(room.getGameType().getMaxPlayers());
            item.setTargetPlayers(room.getTargetPlayers());
            if (parties != null) {
                item.setPartyIds(occupants.stream().map(p -> parties.partyIdOf(p.getIdentityId()))
                        .filter(Objects::nonNull).distinct().collect(Collectors.toList()));
            }
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
        return joinGame(conn, playerName, targetRoomId, gameType, deck, deckCards, createRoom,
                requestedPlayers, "QUICK");
    }

    /**
     * @param mode 入口来源（QUICK/PRIVATE/CODE/AI/PARTY/DUEL）。
     *             只是埋点维度，不能改变匹配/建房行为；未知值会被收敛到 QUICK。
     */
    public synchronized GameRoom joinGame(Channel conn, String playerName, String targetRoomId,
                                          GameType gameType, String deck, String deckCards,
                                          boolean createRoom, int requestedPlayers, String mode) {
        String analyticsMode = ProductAnalyticsService.normalizeMode(mode);
        if (createRoom && ("QUICK".equals(analyticsMode) || "CODE".equals(analyticsMode))) {
            analyticsMode = "PRIVATE";
        }
        int targetPlayers;
        try { targetPlayers = gameType.resolvePlayerCount(requestedPlayers); }
        catch (IllegalArgumentException e) {
            recordMatchFail(targetRoomId, analyticsMode, "INVALID_PLAYERS");
            return null;
        }
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
            if (room == null) { recordMatchFail(targetRoomId, analyticsMode, "ROOM_NOT_FOUND"); return null; }
            if (room.getState() != GameRoom.State.WAITING
                    || room.isFull()
                    || room.getGameType() != gameType) {
                recordMatchFail(targetRoomId, analyticsMode, "ROOM_UNAVAILABLE");
                return null;
            }
        }

        if (room == null && !createRoom) {
            room = findWaitingRoom(gameType, targetPlayers);
        }

        if (room == null) {
            // createRoom=true → 私有房：不进大厅列表、不参与匹配，只能凭房号加入
            room = newRoom(gameType, createRoom, targetPlayers);
            rooms.put(room.getRoomId(), room);
        }

        GameRoom previousRoom = getRoomByPlayer(player);
        if (previousRoom != null && previousRoom != room) player = switchRoom(player, previousRoom);
        String playerId = player.getId();
        // 必须先登记入座上下文再 addPlayer：第二个真人补位会立刻触发 startGame，
        // 开局回调若晚于上下文登记就会把真实入口误判成 PARTY。
        boolean tracked = beginJoin(player, analyticsMode, room, playerId);
        if (!room.addPlayer(player)) {
            if (tracked) joinContexts.remove(joinKey(room, playerId));
            recordMatchFail(targetRoomId, analyticsMode, "ROOM_UNAVAILABLE");
            return null;
        }
        playerRoom.put(playerId, room.getRoomId());
        if (tracked) completeJoin(player, analyticsMode, room, playerId);

        broadcastRoomsList();
        return room;
    }

    /**
     * 在 addPlayer 之前登记上下文，保证「第二人补位立即开局」时开局回调拿得到入口与等待起点。
     * 只登记真人身份；无持久身份的旧客户端不制造漏斗用户。
     */
    private boolean beginJoin(Player player, String mode, GameRoom room, String playerId) {
        if (analytics == null || player == null || player.isAi()) return false;
        String identityId = player.getIdentityId();
        if (identityId == null) return false;
        joinContexts.put(joinKey(room, playerId),
                new JoinContext(identityId, mode, System.currentTimeMillis(), false));
        return true;
    }

    /**
     * 入座成功后才记录「选择玩法 → 成功入座」。
     * 人机房一旦有 AI 入座，后续开局/完局会改记为 AI，避免把挂机刷人机算成真人活跃。
     */
    private void completeJoin(Player player, String mode, GameRoom room, String playerId) {
        if (analytics == null || player == null || player.isAi()) return;
        JoinContext context = joinContexts.get(joinKey(room, playerId));
        if (context == null) return;
        // 先按房间事实校正，再落「选择玩法/成功入座」，保证同一局的入口口径一致。
        // addPlayer 可能已经触发开局（第二人补位），此时开局回调同样会先做这一步。
        if (room.getGameType() != null) markAiIfNeeded(room, mode);
        String identityId = context.identityId;
        analytics.recordModeSelect(identityId, context.mode);
        analytics.recordMatchStart(identityId, context.mode);
    }

    private void markAiIfNeeded(GameRoom room, String mode) {
        if (room == null || analytics == null) return;
        if ("AI".equals(mode)) return;
        boolean hasAi = room.getPlayers().stream().anyMatch(Player::isAi);
        if (!hasAi) return;
        for (Player p : room.getPlayers()) {
            if (p.isAi()) continue;
            JoinContext context = joinContexts.get(joinKey(room, p.getId()));
            if (context != null) context.mode = "AI";
        }
    }

    /** 房间开局时由 GameRoom 回调；只记录真人身份，AI 不产生活跃。 */
    void onGameStarted(GameRoom room) {
        if (analytics == null || room == null) return;
        markAiIfNeeded(room, "QUICK");
        long now = System.currentTimeMillis();
        for (Player p : room.getPlayers()) {
            if (p.isAi()) continue;
            JoinContext context = joinContexts.get(joinKey(room, p.getId()));
            if (context == null) {
                // 组队/约战等不经 JOIN 的服务端开局：补一条入座上下文，保证漏斗不断。
                if (p.getIdentityId() == null) continue;
                context = new JoinContext(p.getIdentityId(), "PARTY", now, false);
                joinContexts.put(joinKey(room, p.getId()), context);
            }
            context.started = true;
            // 新一轮真实开局才复位终局幂等标记；onRematch 已在此之前读过上局的 finished。
            context.finished = false;
            // 再来一局不再重复计入「匹配等待」：首次开局用真实入座到开局时长，
            // 后续开局传 -1，仅计开局事件、不污染平均等待。
            long waitMs = context.waitRecorded ? -1 : Math.max(0, now - context.joinedAt);
            context.waitRecorded = true;
            analytics.recordGameStart(context.identityId, context.mode, waitMs);
        }
    }

    /** 房间终局时由 GameRoom 回调。 */
    void onGameFinished(GameRoom room) {
        if (analytics == null || room == null) return;
        markAiIfNeeded(room, "QUICK");
        for (Player p : room.getPlayers()) {
            if (p.isAi()) continue;
            JoinContext context = joinContexts.get(joinKey(room, p.getId()));
            if (context == null) continue;
            // 同一局可能因断线判负、离场和引擎终局多次触发回调，只计第一次。
            if (context.finished) continue;
            context.finished = true;
            analytics.recordGameFinish(context.identityId, context.mode);
        }
    }

    /** 同一房间再来一局：只有上一局确实完局且身份仍在座时才计数。 */
    void onRematch(GameRoom room) {
        if (analytics == null || room == null) return;
        markAiIfNeeded(room, "QUICK");
        for (Player p : room.getPlayers()) {
            if (p.isAi()) continue;
            JoinContext context = joinContexts.get(joinKey(room, p.getId()));
            if (context == null || !context.finished) continue;
            analytics.recordRematch(context.identityId, context.mode);
        }
    }

    private void recordMatchFail(String targetRoomId, String mode, String reason) {
        if (analytics == null) return;
        String safeReason = targetRoomId != null && !targetRoomId.isBlank()
                ? reason + (mode != null && "CODE".equals(mode) ? "" : "_BY_CODE") : reason;
        analytics.recordMatchFail(safeReason);
    }

    static final class JoinContext {
        final String identityId;
        volatile String mode;
        final long joinedAt;
        volatile boolean started;
        volatile boolean waitRecorded;
        volatile boolean finished;

        JoinContext(String identityId, String mode, long joinedAt, boolean started) {
            this.identityId = identityId;
            this.mode = mode;
            this.joinedAt = joinedAt;
            this.started = started;
        }
    }

    /**
     * 在同一个房间生命周期锁下验证整队并入座。只移动在大厅的连接，
     * 因而拒绝时没有房间分配，也不会让任何队员误离开已有对局。
     */
    public synchronized GameRoom startPartyGame(PartyService.Party party, GameType type, Predicate<String> banned) {
        int size = party.members.size();
        if (size < type.getMinPlayers() || size > type.getMaxPlayers()) {
            throw new PartyService.PartyException("INVALID", type.getDisplayName() + "支持 "
                    + (type.getMinPlayers() == type.getMaxPlayers() ? String.valueOf(type.getMaxPlayers())
                    : type.getMinPlayers() + "–" + type.getMaxPlayers()) + " 人，当前队伍有 " + size + " 人");
        }

        LobbyRoster roster = validateLobbyRoster(party.members, banned,
                (code, message) -> new PartyService.PartyException("CONFLICT", message));
        return startPrivateRoster(roster, type, "PARTY");
    }

    /** 发约战只检查大厅状态，不建房、不占座，也不释放断线保留席位。 */
    public synchronized void validateDuelPlayers(String fromId, String toId, Predicate<String> banned) {
        validateDuelRoster(fromId, toId, banned);
    }

    /** 接受约战时在房间生命周期锁内重验双方，随后一次入座并直接 GAME_START。 */
    public synchronized GameRoom startDuelGame(String fromId, String toId, GameType type, Predicate<String> banned) {
        if (type == null || type.getMinPlayers() > 2 || type.getMaxPlayers() < 2) {
            throw new DuelService.DuelException("INVALID", "该玩法不支持双人约战");
        }
        return startPrivateRoster(validateDuelRoster(fromId, toId, banned), type, "DUEL");
    }

    private LobbyRoster validateDuelRoster(String fromId, String toId, Predicate<String> banned) {
        if (fromId == null || toId == null || fromId.equals(toId)) {
            throw new DuelService.DuelException("INVALID", "约战双方身份无效");
        }
        return validateLobbyRoster(List.of(fromId, toId), banned, DuelService.DuelException::new);
    }

    private static final class LobbyRoster {
        final List<String> members;
        final Map<String, Channel> channels;
        final List<Player> staleSeats;

        LobbyRoster(List<String> members, Map<String, Channel> channels, List<Player> staleSeats) {
            this.members = List.copyOf(members);
            this.channels = channels;
            this.staleSeats = staleSeats;
        }
    }

    /** 组队与约战共享在线、单连接、封禁和活跃席位校验；这里无状态写入。 */
    private LobbyRoster validateLobbyRoster(List<String> members, Predicate<String> banned,
            BiFunction<String, String, ? extends RuntimeException> failure) {
        Set<String> memberIds = new HashSet<>(members);
        Map<String, Channel> channels = new HashMap<>();
        for (Map.Entry<Channel, String> entry : identityByConn.entrySet()) {
            String id = entry.getValue();
            Channel conn = entry.getKey();
            if (!memberIds.contains(id) || !conn.isActive()) continue;
            if (channels.putIfAbsent(id, conn) != null) {
                throw failure.apply("CONFLICT", unavailableName(id) + "打开了多个游戏页面，请保留一个");
            }
        }
        for (String id : members) {
            if (!channels.containsKey(id)) throw failure.apply("CONFLICT", unavailableName(id) + "不在线");
            if (banned.test(id)) throw failure.apply("UNAVAILABLE", unavailableName(id) + "暂时无法参加对局");
        }
        // 同身份的保留席位分两种：
        // - 连接仍活跃：玩家确实还在别处玩，拒绝（不能把人从进行中的棋局拽出来）；
        // - 已断线（60 秒重连宽限期内）：玩家已经用新连接回到大厅并发起组队，
        //   这里顺手释放旧席位，否则「网络抖一下」就会卡住整队开局。
        List<Player> staleSeats = new ArrayList<>();
        for (Player player : playerById.values()) {
            if (!memberIds.contains(player.getIdentityId())) continue;
            GameRoom occupied = getRoomByPlayer(player);
            if (occupied == null) continue;
            if (player.isConnected()) throw failure.apply("CONFLICT",
                    unavailableName(player.getIdentityId()) + "仍在房间中，请先返回大厅");
            staleSeats.add(player);
        }
        for (String id : members) {
            if (!channels.get(id).isActive()) throw failure.apply("CONFLICT", unavailableName(id) + "不在线");
        }
        return new LobbyRoster(members, channels, staleSeats);
    }

    /** 必须在 RoomManager 锁内使用已经校验的名单，所有业务拒绝都发生在此之前。 */
    private GameRoom startPrivateRoster(LobbyRoster validated, GameType type, String mode) {
        for (Player stale : validated.staleSeats) forceLeave(stale);
        int size = validated.members.size();
        GameRoom room = newRoom(type, true, size);
        List<Player> roster = new ArrayList<>(size);
        long joinedAt = System.currentTimeMillis();
        for (String id : validated.members) {
            Channel conn = validated.channels.get(id);
            Player player = playerByConn.get(conn);
            if (player == null) player = getOrCreatePlayer(conn, identityNames.getOrDefault(id, "玩家"));
            if (type == GameType.CARD) {
                // 好友直接开局没有单独确认套牌的步骤，本次使用均衡预组。
                player.setDeck("BALANCED");
                player.setDeckCards(null);
            }
            roster.add(player);
            if (analytics != null && player.getIdentityId() != null) {
                joinContexts.put(joinKey(room, player.getId()),
                        new JoinContext(player.getIdentityId(), mode, joinedAt, false));
                analytics.recordModeSelect(player.getIdentityId(), mode);
                analytics.recordMatchStart(player.getIdentityId(), mode);
            }
        }
        rooms.put(room.getRoomId(), room);
        // GAME_START 发出前所有座位都已可由对应的真实连接和重连会话寻址。
        for (Player player : roster) playerRoom.put(player.getId(), room.getRoomId());
        room.startWithParty(roster);
        broadcastRoomsList();
        return room;
    }

    private String unavailableName(String identityId) {
        return identityNames.getOrDefault(identityId, "有玩家");
    }

    /**
     * 入座上下文按「房间 + 玩家」隔离：切房会保留 playerId，只按玩家建键会覆盖旧房上下文，
     * 旧房延迟终局回调就会把上一局算到新入口上。
     */
    private static String joinKey(GameRoom room, String playerId) {
        return room.getRoomId() + "|" + playerId;
    }

    /** 房间销毁后其入座上下文不会再被回调使用，按房间前缀清掉，避免长期在线反复切房累积。 */
    private void clearJoinContexts(String roomId) {
        joinContexts.keySet().removeIf(key -> key.startsWith(roomId + "|"));
    }

    private GameRoom newRoom(GameType type, boolean privateRoom, int size) {
        String id;
        do {
            id = "ROOM-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        } while (rooms.containsKey(id));
        GameRoom room = new GameRoom(id, type, privateRoom, size);
        room.setEloService(eloService);
        room.setRecordService(recordService);
        if (seasonService != null) room.setSeasonService(seasonService);
        if (parties != null) room.setPartyService(parties);
        if (events != null) room.setEventService(events);
        room.setRoomManager(this);
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
            markAiIfNeeded(room, "QUICK");
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
            String playerId;
            do { playerId = UUID.randomUUID().toString().substring(0, 8); }
            while (playerById.containsKey(playerId));
            String sessionId = UUID.randomUUID().toString().replace("-", "");
            player = new Player(playerId, playerName, conn, sessionId);
            player.setIdentityId(identityByConn.get(conn));
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

    /** 组队关系变化时，公开房卡和房间座位重新下发徽标；各自的局面视角不变。 */
    public void refreshPartyBadges(String identityId) {
        for (GameRoom room : rooms.values()) {
            if (room.getPlayers().stream().anyMatch(p -> identityId.equals(p.getIdentityId()))) {
                room.refreshPartyBadges();
            }
        }
        broadcastRoomsList();
    }

    /**
     * 连接断开（掉线）：保留会话与席位，玩家可凭 sessionId 重连
     */
    public synchronized void handleDisconnect(Channel conn) {
        // 连接级身份先摘除：reconnect 关闭旧连接也会走到这里，此时新连接已登记，不会误判离线
        String identityId = identityByConn.remove(conn);
        tokenByConn.remove(conn);
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
        // 同一玩家可能因切房在多个房间留下延迟回调，离开时一并清理全部房间上下文。
        joinContexts.keySet().removeIf(key -> key.endsWith("|" + player.getId()));
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
            clearJoinContexts(room.getRoomId());
            room.notifyShutdown();
            logger.info("[CLEANUP] 房间 {} 已清理", room.getRoomId());
        }
    }

    public Player getPlayer(Channel conn) {
        return playerByConn.get(conn);
    }

    private String playerNameOf(String identityId) {
        String name = identityNames.get(identityId);
        return name == null ? "玩家" : name;
    }

    public GameRoom getRoomByPlayer(Player player) {
        String roomId = playerRoom.get(player.getId());
        if (roomId == null) return null;
        return rooms.get(roomId);
    }

    private GameRoom findWaitingRoom(GameType gameType, int targetPlayers) {
        for (GameRoom room : rooms.values()) {
            // 匹配只选完全可用的公开等待房，终局后剩下人的房间不再参与匹配
            if (room.getGameType() != gameType
                    || room.getTargetPlayers() != targetPlayers
                    || !room.isAvailableForMatching()) {
                continue;
            }
            // 只匹配「还坐着人且在线」的等待房间。
            // 掉线玩家仍有 60s 重连宽限，这期间座位必须给他留着；
            // 若此时把新人匹配进来，座位被占，原玩家重连回来就无处可坐，
            // 宽限期也就失去意义了。这类房间由 cleanupIdleRooms 之后回收。
            //
            // AI 座位没有连接，不能按掉线处理：之前把「补了一部分电脑」的多人等待房
            // 误判成幽灵房，导致这类房间永远匹配不到真人。电脑占的是座位，不是人。
            if (hasDisconnectedHuman(room)) continue;
            return room;
        }
        return null;
    }

    private static boolean hasDisconnectedHuman(GameRoom room) {
        return room.getPlayers().stream().anyMatch(p -> !p.isAi() && !p.isConnected());
    }

    private static int connectedHumanCount(GameRoom room) {
        return (int) room.getPlayers().stream().filter(p -> !p.isAi() && p.isConnected()).count();
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
     * 匹配总览：按玩法统计「可被快速匹配加入」的公开等待房，以及进行中的房间。
     *
     * 口径与 {@link #findWaitingRoom} 保持一致（公开、未满、人类座位全部在线），
     * 大厅据此展示「N 人等待」，避免把掉线宽限期中的房间当成可加入。
     * AI 座位既不算等待人数，也不阻止房间被匹配——补了电脑的房间仍欢迎真人补位。
     *
     * 这是只读统计，不产生排队状态：匹配仍由 JOIN(createRoom=false) 原子完成，
     * 不引入第二份「谁在排队」的真相。
     */
    public Map<String, Object> getMatchOverview() {
        Map<GameType, int[]> perType = new EnumMap<>(GameType.class);
        for (GameType type : GameType.values()) perType.put(type, new int[3]);
        int waitingRooms = 0;
        int waitingPlayers = 0;
        int playingRooms = 0;
        for (GameRoom room : rooms.values()) {
            if (room.isPrivateRoom()) continue;
            int[] counts = perType.get(room.getGameType());
            if (counts == null) continue;
            if (room.getState() == GameRoom.State.WAITING) {
                if (!room.isAvailableForMatching()) continue;
                int humans = connectedHumanCount(room);
                if (humans == 0) continue;
                counts[0]++;
                counts[1] += humans;
                waitingRooms++;
                waitingPlayers += humans;
            } else if (room.getState() == GameRoom.State.PLAYING) {
                counts[2]++;
                playingRooms++;
            }
        }
        List<Map<String, Object>> games = new ArrayList<>();
        for (GameType type : GameType.values()) {
            int[] c = perType.get(type);
            Map<String, Object> game = new LinkedHashMap<>();
            game.put("gameType", type.name());
            game.put("name", type.getDisplayName());
            game.put("waitingRooms", c[0]);
            game.put("waitingPlayers", c[1]);
            game.put("playingRooms", c[2]);
            games.add(game);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("onlinePlayers", playerByConn.size());
        result.put("waitingRooms", waitingRooms);
        result.put("waitingPlayers", waitingPlayers);
        result.put("playingRooms", playingRooms);
        result.put("games", games);
        return result;
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
