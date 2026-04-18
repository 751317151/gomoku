package com.gomoku.server;

import com.gomoku.game.GameRoom;
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
    private final RoomManager roomManager;

    private final EnumMap<GameMessage.Type, BiConsumer<Channel, GameMessage>> handlers = new EnumMap<>(GameMessage.Type.class);

    @Autowired
    public GameServerHandler(RoomManager roomManager) {
        this.roomManager = roomManager;
        registerHandlers();
    }

    private void registerHandlers() {
        handlers.put(GameMessage.Type.GET_ROOMS, (conn, msg) -> handleGetRooms(conn));
        handlers.put(GameMessage.Type.JOIN, this::handleJoin);
        handlers.put(GameMessage.Type.SPECTATE, this::handleSpectate);
        handlers.put(GameMessage.Type.ADD_AI, (conn, msg) -> handleAddAI(conn));
        handlers.put(GameMessage.Type.MOVE, this::handleMove);
        handlers.put(GameMessage.Type.CHAT, this::handleChat);
        handlers.put(GameMessage.Type.RESTART, (conn, msg) -> handleRestart(conn));
        handlers.put(GameMessage.Type.LEAVE, (conn, msg) -> handleLeave(conn));
        handlers.put(GameMessage.Type.RECONNECT, this::handleReconnect);
        handlers.put(GameMessage.Type.SURRENDER, (conn, msg) -> handleSurrender(conn));
        handlers.put(GameMessage.Type.PING, (conn, msg) -> {});
    }

    // ============ 入站处理：支持 JSON 和 Binary 两种协议 ============

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
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
            logger.warn("无效消息格式：{}", message);
            sendError(ctx.channel(), "消息格式错误");
        } catch (Exception e) {
            logger.warn("消息处理异常", e);
            sendError(ctx.channel(), "服务器内部错误");
        }
    }

    // ============ 生命周期 ============

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        String ip = getIp(ctx.channel());
        if (ip != null && !roomManager.checkIpLimit(ip)) {
            logger.warn("IP 连接数超限，拒绝: {}", ip);
            ctx.close();
            return;
        }
        logger.info("新连接：{} (IP: {})", ctx.channel().remoteAddress(), ip);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        logger.info("连接断开：{}", ctx.channel().remoteAddress());
        try {
            roomManager.handleDisconnect(ctx.channel());
        } catch (Exception e) {
            logger.warn("断开连接处理异常", e);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("WebSocket错误：{}", cause.getMessage(), cause);
        try {
            roomManager.handleDisconnect(ctx.channel());
        } catch (Exception e) {
            logger.warn("异常断开处理失败", e);
        }
        ctx.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            if (event.state() == io.netty.handler.timeout.IdleState.READER_IDLE) {
                logger.info("心跳超时，断开连接：{}", ctx.channel().remoteAddress());
                roomManager.handleDisconnect(ctx.channel());
                ctx.close();
            }
        } else {
            super.userEventTriggered(ctx, evt);
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

    private void handleJoin(Channel conn, GameMessage msg) {
        Player existing = roomManager.getPlayer(conn);
        if (existing != null && !existing.canPerformAction()) return;

        String playerName = NameSanitizer.sanitize(msg.getPlayerName());
        GameRoom room = roomManager.joinGame(conn, playerName, msg.getRoomId());
        if (room == null) {
            sendError(conn, "加入房间失败（房间可能已满或不存在）");
            return;
        }
        if (room.getState() == GameRoom.State.WAITING) {
            Player player = roomManager.getPlayer(conn);
            if (player != null && player.getSessionId() != null) {
                GameMessage sessionMsg = new GameMessage(GameMessage.Type.WAITING);
                sessionMsg.setRoomId(room.getRoomId());
                sessionMsg.setPlayerId(player.getId());
                sessionMsg.setSessionId(player.getSessionId());
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

    private void handleAddAI(Channel conn) {
        Player player = roomManager.getPlayer(conn);
        if (player != null && !player.canPerformAction()) return;
        GameRoom room = roomManager.addAI(conn);
        if (room == null) sendError(conn, "添加电脑失败");
    }

    private void handleMove(Channel conn, GameMessage msg) {
        Player player = roomManager.getPlayer(conn);
        if (player == null) { sendError(conn, "请先加入游戏"); return; }
        if (!player.canPerformAction()) return;

        GameRoom room = roomManager.getRoomByPlayer(player);
        if (room == null) { sendError(conn, "房间不存在"); return; }

        boolean success = room.handleMove(player, msg.getRow(), msg.getCol(), msg.getMoveSeq());
        if (!success) sendError(conn, "无效落子");
    }

    private void handleChat(Channel conn, GameMessage msg) {
        Player player = roomManager.getPlayer(conn);
        if (player == null) return;
        GameRoom room = roomManager.getRoomByPlayer(player);
        if (room == null) return;
        String text = NameSanitizer.sanitizeChat(msg.getMessage());
        if (text == null) return;
        room.handleChat(player, text);
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
                syncMsg.setMessage("重连成功");
                syncMsg.setData(gson.toJson(room.buildSyncDataForReconnect(player)));
                player.sendMessage(gson.toJson(syncMsg));
            }
            room.broadcastRoomInfo();
        }
    }

    // ============ 发送辅助 ============

    private void sendError(Channel conn, String errorMsg) {
        GameMessage error = new GameMessage(GameMessage.Type.ERROR);
        error.setMessage(errorMsg);
        Player.sendMessageTo(conn, gson.toJson(error));
    }

    private String getIp(Channel channel) {
        if (channel.remoteAddress() instanceof InetSocketAddress) {
            return ((InetSocketAddress) channel.remoteAddress()).getAddress().getHostAddress();
        }
        return null;
    }
}
