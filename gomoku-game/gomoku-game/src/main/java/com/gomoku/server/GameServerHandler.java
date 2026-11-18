package com.gomoku.server;

import com.gomoku.game.CardBattleEngine;
import com.gomoku.game.GameRoom;
import com.gomoku.game.GameType;
import com.gomoku.game.MoveCommand;
import com.gomoku.game.RoomManager;
import com.gomoku.model.GameMessage;
import com.gomoku.model.Player;
import com.gomoku.util.NameSanitizer;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.EnumMap;
import java.util.function.BiConsumer;

/**
 * 游戏消息处理器
 *
 * 支持两种输入：
 * 1. TextWebSocketFrame — JSON 协议（传统方式）
 * 2. GameMessage — 二进制协议（由 WebSocketFrameRouter 解码后传入）
 *
 * 出站时通过 BinaryMessageEncoder 自动编码二进制帧（如果客户端支持）。
 */
@Component
@ChannelHandler.Sharable
public class GameServerHandler extends SimpleChannelInboundHandler<Object> {

    private static final Logger logger = LoggerFactory.getLogger(GameServerHandler.class);
    private static final Gson gson = new Gson();
    /** 卡牌攻击指令的 action 标记（负数，与手牌下标区分） */
    private static final int ATTACK_MARKER = -2;
    private final RoomManager roomManager;
    @Autowired private com.gomoku.game.GuestIdentityService guests;
    @Autowired private com.gomoku.game.FriendService friends;
    @Autowired private com.gomoku.game.ModerationService moderation;

    private final EnumMap<GameMessage.Type, BiConsumer<Channel, GameMessage>> handlers = new EnumMap<>(GameMessage.Type.class);

    @Autowired
    public GameServerHandler(RoomManager roomManager) {
        this.roomManager = roomManager;
        registerHandlers();
    }

    private void registerHandlers() {
        handlers.put(GameMessage.Type.GET_ROOMS, (conn, msg) -> handleGetRooms(conn));
        handlers.put(GameMessage.Type.HALL_BIND, this::handleHallBind);
        handlers.put(GameMessage.Type.JOIN, this::handleJoin);
        handlers.put(GameMessage.Type.SPECTATE, this::handleSpectate);
        handlers.put(GameMessage.Type.ADD_AI, this::handleAddAI);
        handlers.put(GameMessage.Type.MOVE, this::handleMove);
        handlers.put(GameMessage.Type.PASS, this::handlePass);
        handlers.put(GameMessage.Type.END_TURN, this::handleEndTurn);
        handlers.put(GameMessage.Type.CHAT, this::handleChat);
        handlers.put(GameMessage.Type.DIRECT_MSG, this::handleDirectMsg);
        handlers.put(GameMessage.Type.REPORT, this::handleReport);
        handlers.put(GameMessage.Type.RESTART, (conn, msg) -> handleRestart(conn));
        handlers.put(GameMessage.Type.LEAVE, (conn, msg) -> handleLeave(conn));
        handlers.put(GameMessage.Type.RECONNECT, this::handleReconnect);
        handlers.put(GameMessage.Type.SURRENDER, (conn, msg) -> handleSurrender(conn));
        handlers.put(GameMessage.Type.PING, (conn, msg) -> {});
    }

    // ============ 入站处理：支持 JSON 和 Binary 两种协议 ============

    private static final io.netty.util.AttributeKey<Boolean> IP_CHECKED =
            io.netty.util.AttributeKey.valueOf("ipChecked");
    private static final io.netty.util.AttributeKey<Boolean> IP_RELEASED =
            io.netty.util.AttributeKey.valueOf("ipReleased");

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        // IP 限流：仅在首次消息时检查一次
        Boolean checked = ctx.channel().attr(IP_CHECKED).get();
        if (checked == null) {
            ctx.channel().attr(IP_CHECKED).set(true);
            String ip = clientIp(ctx.channel());
            if (ip != null) {
                ctx.channel().attr(ClientAddressHandler.CLIENT_IP).set(ip);
                if (!roomManager.checkIpLimit(ip)) {
                    logger.warn("IP 连接数超限，拒绝: {}", ip);
                    // checkIpLimit 内部已回退计数，标记已释放防止 handlerRemoved 再减
                    ctx.channel().attr(IP_RELEASED).set(true);
                    sendError(ctx.channel(), "连接数超限，同一网络最多允许16个连接");
                    ctx.close();
                    return;
                }
            }
        }

        if (msg instanceof TextWebSocketFrame) {
            // JSON 协议路径
            handleJsonFrame(ctx, (TextWebSocketFrame) msg);
        } else if (msg instanceof GameMessage) {
            // 二进制协议路径（由 WebSocketFrameRouter 解码后传入）
            handleMessage(ctx.channel(), (GameMessage) msg);
        }
    }

    private void handleJsonFrame(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        String message = frame.text();
        try {
            GameMessage msg = gson.fromJson(message, GameMessage.class);
            if (msg.getType() == null) {
                sendError(ctx.channel(), "消息类型不能为空");
                return;
            }
            handleMessage(ctx.channel(), msg);
        } catch (JsonSyntaxException e) {
            logger.warn("无效 JSON 消息（{} 字符）", message.length());
            sendError(ctx.channel(), "消息格式错误");
        } catch (Exception e) {
            logger.warn("消息处理异常", e);
            sendError(ctx.channel(), "服务器内部错误");
        }
    }

    // ============ 生命周期 ============

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        logger.info("新连接：{} (IP: {})", ctx.channel().remoteAddress(), clientIp(ctx.channel()));
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        logger.info("连接断开：{}", ctx.channel().remoteAddress());
        try {
            roomManager.handleDisconnect(ctx.channel());
        } catch (Exception e) {
            logger.warn("断开连接处理异常", e);
        }
        // 始终释放 IP 计数（幂等：只释放一次）
        releaseIpOnce(ctx.channel());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("WebSocket错误：{}", cause.getMessage(), cause);
        try {
            roomManager.handleDisconnect(ctx.channel());
        } catch (Exception e) {
            logger.warn("异常断开处理失败", e);
        }
        releaseIpOnce(ctx.channel());
        ctx.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            if (event.state() == io.netty.handler.timeout.IdleState.READER_IDLE) {
                logger.info("心跳超时，断开连接：{}", ctx.channel().remoteAddress());
                roomManager.handleDisconnect(ctx.channel());
                releaseIpOnce(ctx.channel());
                ctx.close();
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    /**
     * 幂等释放 IP 连接计数，同一 Channel 只释放一次
     */
    private void releaseIpOnce(Channel ch) {
        if (ch.attr(IP_RELEASED).compareAndSet(null, true)) {
            String ip = ch.attr(ClientAddressHandler.CLIENT_IP).getAndSet(null);
            if (ip != null) {
                roomManager.releaseIp(ip);
            }
        }
    }

    // ============ 消息分发 ============

    private void handleMessage(Channel conn, GameMessage msg) {
        BiConsumer<Channel, GameMessage> handler = handlers.get(msg.getType());
        if (handler != null) {
            handler.accept(conn, msg);
        } else {
            sendError(conn, "未知消息类型");
        }
    }

    // ============ Handler 实现 ============

    private void handleGetRooms(Channel conn) {
        GameMessage res = new GameMessage(GameMessage.Type.ROOM_LIST);
        res.setData(roomManager.getRoomsListJson());
        Player.sendMessageTo(conn, gson.toJson(res));
    }

    /**
     * 大厅身份绑定：连接建立后携带 token 上报，不进房。
     * 好友在线状态与实时推送（FRIEND_EVENT）依赖这条消息——
     * 旧客户端不发也不受影响（不显示在线、收不到推送，走轮询兜底）。
     */
    private void handleHallBind(Channel conn, GameMessage msg) {
        String identityId = guests.resolve(msg.getGuestToken());
        if (identityId == null) {
            sendError(conn, "游客凭证无效，请重新进入大厅");
            return;
        }
        // 封禁拦截：被封禁身份连大厅都不让进，避免换个房间继续骚扰
        if (moderation.isBanned(identityId)) {
            sendError(conn, "账号已被封禁，如有疑问请联系管理员");
            return;
        }
        String playerName = NameSanitizer.sanitize(msg.getPlayerName());
        if (!roomManager.bindIdentity(conn, playerName, identityId)) {
            sendError(conn, "游客凭证无效，请重新进入大厅");
        }
    }

    private void handleJoin(Channel conn, GameMessage msg) {
        Player existing = roomManager.getPlayer(conn);
        if (existing != null && !existing.canPerformAction()) return;

        String playerName = NameSanitizer.sanitize(msg.getPlayerName());
        // 封禁拦截（旧客户端不发 HALL_BIND 时也会走到这里）
        String resolvedIdentity = guests.resolve(msg.getGuestToken());
        if (resolvedIdentity != null && moderation.isBanned(resolvedIdentity)) {
            sendError(conn, "账号已被封禁，如有疑问请联系管理员");
            return;
        }
        if (msg.getGuestToken() != null && !roomManager.bindIdentity(conn, playerName, resolvedIdentity)) {
            sendError(conn, "游客凭证无效，请重新进入大厅"); return;
        }
        GameType gameType = GameType.fromString(msg.getGameType());
        // 套牌必须在「创建玩家」之后、「加入房间」之前设定：
        // 首次 JOIN 时 getPlayer(conn) 还是 null，之前那样写套牌永远记不上（实测只剩 BALANCED）。
        // 而且第二个玩家加入会立刻触发 startGame，所以必须在 addPlayer 之前传进去。
        GameRoom room = roomManager.joinGame(conn, playerName, msg.getRoomId(), gameType,
                msg.getDeck(), msg.getDeckCards(), msg.isCreateRoom(), msg.getPlayerCount());
        if (room == null) {
            boolean byCode = msg.getRoomId() != null && !msg.getRoomId().trim().isEmpty();
            sendError(conn, byCode
                    ? "加入房间失败（房间号不存在、已开局或已满）"
                    : "加入房间失败（房间可能已满或不存在）");
            return;
        }
        if (room.getState() == GameRoom.State.WAITING) {
            Player player = roomManager.getPlayer(conn);
            if (player != null && player.getSessionId() != null) {
                GameMessage sessionMsg = new GameMessage(GameMessage.Type.WAITING);
                sessionMsg.setRoomId(room.getRoomId());
                sessionMsg.setPlayerId(player.getId());
                sessionMsg.setSessionId(player.getSessionId());
                sessionMsg.setGameType(room.getGameType().name());
                sessionMsg.setMessage("已加入房间");
                player.sendMessage(gson.toJson(sessionMsg));
            }
        }
    }

    private void handleSpectate(Channel conn, GameMessage msg) {
        String playerName = NameSanitizer.sanitize(msg.getPlayerName());
        if (msg.getPlayerName() == null || msg.getPlayerName().trim().isEmpty()) {
            playerName = "观众" + (int) (Math.random() * 9000 + 1000);
        }
        if (msg.getRoomId() == null) {
            sendError(conn, "观战需要指定房间ID");
            return;
        }
        GameRoom room = roomManager.spectateGame(conn, playerName, msg.getRoomId());
        if (room == null) {
            sendError(conn, "观战失败（房间可能不存在）");
        }
    }

    private void handleLeave(Channel conn) {
        roomManager.leaveRoom(conn);
        handleGetRooms(conn);
    }

    private void handleAddAI(Channel conn, GameMessage msg) {
        // 多人一键开局会连续填充座位；300ms 静默限流会丢请求并永久卡在 7/8。
        // 房间的等待状态与固定容量已限制成功添加次数。
        int difficulty = 4; // 默认中等
        if (msg.getData() != null) {
            try { difficulty = Integer.parseInt(msg.getData()); } catch (NumberFormatException ignored) {}
        }
        GameRoom room = roomManager.addAI(conn, difficulty, msg.getDeck());
        if (room == null) sendError(conn, "添加电脑失败");
    }

    private void handleMove(Channel conn, GameMessage msg) {
        Player player = roomManager.getPlayer(conn);
        if (player == null) { sendError(conn, "请先加入游戏"); return; }
        // 注意：落子不做 300ms 限流。静默丢弃一个合法落子会让客户端与服务端
        // 手数永久错位（客户端已自增 moveSeq，服务端未落子），后续每步都被拒绝。
        // 合法性已由轮次校验 + moveSeq 幂等校验 + 引擎规则三重把关，无需再限流。

        GameRoom room = roomManager.getRoomByPlayer(player);
        if (room == null) { sendError(conn, "房间不存在"); return; }

        // 卡牌攻击：用坐标字段表达「哪个随从打谁」
        //   row = 己方随从下标，col = 目标下标（-1 = 打英雄）
        // 单独走一条路径，因为它不消耗手牌、也不推进出牌阶段的语义
        if (room.getEngine() instanceof CardBattleEngine && msg.getAction() == ATTACK_MARKER) {
            if (!room.handleAttack(player, msg.getRow(), msg.getCol(), msg.getActionVersion())) {
                if (msg.getActionVersion() != null) { sendActionSync(player, room); return; }
                sendError(conn, "无法攻击");
            }
            return;
        }

        // 按玩法把消息翻译成行动指令：非棋盘玩法用 action（技能/道具下标），
        // 棋类用坐标。房间层只认 MoveCommand，不关心具体是哪一种。
        MoveCommand command;
        if (msg.getAction() >= 0) {
            // 卡牌里「火球术/鼓舞」需要玩家指定目标，其余行动无目标
            command = msg.getTargetSide() > 0
                    ? MoveCommand.withTarget(msg.getAction(), msg.getTargetSide(), msg.getTargetIndex())
                    : MoveCommand.action(msg.getAction());
        } else {
            command = MoveCommand.of(msg.getFromRow(), msg.getFromCol(), msg.getRow(), msg.getCol());
        }

        boolean success = room.handleCommand(player, command, msg.getMoveSeq(), msg.getActionVersion());
        if (!success) {
            if (msg.getActionVersion() != null) { sendActionSync(player, room); return; }
            // 回带服务端真实手数，客户端据此校准 moveSeq，避免序号错位后永久失败
            sendError(conn, "无效落子", String.valueOf(room.getEngine().getMoveSeq()));
        }
    }

    private void handlePass(Channel conn, GameMessage msg) {
        Player player = roomManager.getPlayer(conn);
        if (player == null) return;
        // 卡牌等一回合多行动的玩法：PASS 语义化为「结束回合并交出攻击」
        GameRoom room = roomManager.getRoomByPlayer(player);
        if (room == null) return;
        if (!room.getEngine().autoEndsTurn()) {
            if (!room.handleEndTurn(player, msg.getActionVersion())) {
                if (msg.getActionVersion() != null) sendActionSync(player, room);
                else sendError(conn, "当前不能结束回合");
            }
            return;
        }
        if (!room.handlePass(player, msg.getActionVersion())) {
            if (msg.getActionVersion() != null) sendActionSync(player, room);
            else sendError(conn, "当前不能停一手");
        }
    }

    /**
     * 结束回合（一回合多行动的玩法）。
     *
     * 注意不做 300ms 限流：玩家可能连点「结束回合」，静默丢弃会让客户端
     * 以为自己还在本回合，而服务端已经换手，界面与真实轮次脱节。
     */
    private void handleEndTurn(Channel conn, GameMessage msg) {
        Player player = roomManager.getPlayer(conn);
        if (player == null) return;
        GameRoom room = roomManager.getRoomByPlayer(player);
        if (room == null) return;
        if (!room.handleEndTurn(player, msg.getActionVersion())) {
            if (msg.getActionVersion() != null) sendActionSync(player, room);
            else sendError(conn, "当前不能结束回合");
        }
    }

    private void handleChat(Channel conn, GameMessage msg) {
        Player player = roomManager.getPlayer(conn);
        if (player == null) return;
        GameRoom room = roomManager.getRoomByPlayer(player);
        if (room == null) return;
        String text = NameSanitizer.sanitizeChat(msg.getMessage());
        if (text == null) return;
        // 禁言：仍可继续对局，只是发不出消息（含房间聊天与好友私聊）
        if (moderation.isMuted(player.getIdentityId())) {
            sendError(conn, "你已被禁言，暂时不能聊天");
            return;
        }
        room.handleChat(player, text);
    }

    /**
     * 举报：把当前房间里的某位玩家连同证据快照交给治理服务。
     * 只要求「在同一房间里」——被举报人不需要在线（掉线挂机同样可举报）。
     */
    private void handleReport(Channel conn, GameMessage msg) {
        Player reporter = roomManager.getPlayer(conn);
        if (reporter == null || reporter.getIdentityId() == null) {
            sendError(conn, "请先进入房间再举报");
            return;
        }
        GameRoom room = roomManager.getRoomByPlayer(reporter);
        if (room == null) {
            sendError(conn, "请先进入房间再举报");
            return;
        }
        String targetId = msg.getData();
        if (targetId == null || targetId.isEmpty()) {
            sendError(conn, "请选择要举报的玩家");
            return;
        }
        Player target = null;
        for (Player p : room.getPlayers()) {
            if (targetId.equals(p.getId())) target = p;
        }
        if (target == null) {
            sendError(conn, "只能举报同一房间里的玩家");
            return;
        }
        if (target.isAi()) {
            sendError(conn, "电脑玩家无需举报");
            return;
        }
        String reason = reasonCode(msg.getAction());
        try {
            com.gomoku.game.ModerationService.Report saved = moderation.report(
                    reporter.getIdentityId(), reporter.getName(),
                    target.getIdentityId(), target.getName(),
                    room.getRoomId(), room.getGameType().name(),
                    reason, msg.getMessage());
            if (saved == null) {
                sendError(conn, "已收到你的举报，请勿重复提交");
                return;
            }
            logger.info("[AUDIT] report {} by {} on {} in {}",
                    saved.id, saved.reporterId, saved.targetId, saved.roomId);
            GameMessage ok = new GameMessage(GameMessage.Type.GAME_CHAT);
            ok.setRoomId(room.getRoomId());
            // 系统消息也要带发送者名，否则前端聊天会渲染成「undefined：…」
            ok.setPlayerName("系统");
            ok.setMessage("举报已提交，运营会尽快核实处理");
            reporter.sendMessage(gson.toJson(ok));
        } catch (IllegalArgumentException e) {
            sendError(conn, e.getMessage());
        }
    }

    /** 举报原因码：action 字段复用作原因下标（0=辱骂 1=刷屏 2=作弊 3=挂机 4=其他） */
    private static String reasonCode(int action) {
        switch (action) {
            case 0: return "abuse";
            case 1: return "spam";
            case 2: return "cheating";
            case 3: return "afk";
            default: return "other";
        }
    }

    /**
     * 好友私聊：只允许好友之间、对方在线才送达（v1 不做离线消息，未送达有明确报错）。
     * 与房间聊天同款清洗（去控制字符、200 字截断）与 1 秒限流（超限静默丢弃）。
     * 接收方是连接级身份寻址：人在大厅或对局中都能收到。
     */
    private void handleDirectMsg(Channel conn, GameMessage msg) {
        Player sender = roomManager.getPlayer(conn);
        if (sender == null || sender.getIdentityId() == null) {
            sendError(conn, "请先进入大厅获取身份");
            return;
        }
        if (!sender.canSendChat()) return; // 限流窗口内静默丢弃，与房间聊天一致
        String target = msg.getData();
        String text = NameSanitizer.sanitizeChat(msg.getMessage());
        if (target == null || target.isEmpty() || text == null) return;
        if (moderation.isMuted(sender.getIdentityId())) {
            sendError(conn, "你已被禁言，暂时不能私聊");
            return;
        }
        if (!friends.areFriends(sender.getIdentityId(), target)) {
            sendError(conn, "只能给好友发私聊");
            return;
        }
        if (!roomManager.isIdentityOnline(target)) {
            sendError(conn, "对方当前不在线，私聊未送达");
            return;
        }
        GameMessage out = new GameMessage(GameMessage.Type.DIRECT_MSG);
        out.setPlayerId(sender.getIdentityId());
        out.setPlayerName(sender.getName());
        out.setMessage(text);
        roomManager.sendToIdentity(target, out);
    }

    private void handleRestart(Channel conn) {
        Player player = roomManager.getPlayer(conn);
        if (player == null) return;
        if (!player.canPerformAction()) return;
        GameRoom room = roomManager.getRoomByPlayer(player);
        if (room == null) return;
        room.requestRestart(player);
    }

    private void handleSurrender(Channel conn) {
        Player player = roomManager.getPlayer(conn);
        if (player == null) { sendError(conn, "请先加入游戏"); return; }
        GameRoom room = roomManager.getRoomByPlayer(player);
        if (room == null) return;
        room.surrender(player);
    }

    private void handleReconnect(Channel conn, GameMessage msg) {
        if (msg.getSessionId() == null) { sendError(conn, "重连需要 sessionId"); return; }
        GameRoom room = roomManager.reconnect(conn, msg.getSessionId());
        if (room == null) {
            sendError(conn, "重连失败（会话已过期）");
        } else {
            Player player = roomManager.getPlayer(conn);
            if (player != null) {
                GameMessage syncMsg = new GameMessage(GameMessage.Type.GAME_SYNC);
                syncMsg.setRoomId(room.getRoomId());
                syncMsg.setPlayerId(player.getId());
                syncMsg.setSessionId(player.getSessionId());
                syncMsg.setStone(player.getStone());
                syncMsg.setGameType(room.getGameType().name());
                syncMsg.setMessage("重连成功");
                syncMsg.setData(gson.toJson(room.buildSyncDataForReconnect(player)));
                player.sendMessage(gson.toJson(syncMsg));
            }
            room.broadcastRoomInfo();
        }
    }

    // ============ 发送辅助 ============

    private void sendActionSync(Player player, GameRoom room) {
        GameMessage sync = new GameMessage(GameMessage.Type.GAME_SYNC);
        sync.setRoomId(room.getRoomId());
        sync.setPlayerId(player.getId());
        sync.setStone(room.hasPlayer(player) ? player.getStone() : 0);
        sync.setGameType(room.getGameType().name());
        sync.setMessage("操作未生效，已同步最新局面");
        sync.setData(gson.toJson(room.buildSyncDataForReconnect(player)));
        player.sendMessage(gson.toJson(sync));
    }

    private void sendError(Channel conn, String errorMsg) {
        sendError(conn, errorMsg, null);
    }

    /**
     * 发送错误消息；data 非空时携带附加信息（如服务端当前手数，供客户端校准 moveSeq）
     */
    private void sendError(Channel conn, String errorMsg, String data) {
        GameMessage error = new GameMessage(GameMessage.Type.ERROR);
        error.setMessage(errorMsg);
        if (data != null) error.setData(data);
        Player.sendMessageTo(conn, gson.toJson(error));
    }

    private String clientIp(Channel channel) {
        String ip = channel.attr(ClientAddressHandler.CLIENT_IP).get();
        return ip != null ? ip : getIp(channel);
    }

    private String getIp(Channel channel) {
        if (channel.remoteAddress() instanceof InetSocketAddress) {
            return ((InetSocketAddress) channel.remoteAddress()).getAddress().getHostAddress();
        }
        return null;
    }
}
