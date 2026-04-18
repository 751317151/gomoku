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

@Component
@ChannelHandler.Sharable
public class GameServerHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private static final Logger logger = LoggerFactory.getLogger(GameServerHandler.class);
    private static final Gson gson = new Gson();
    private final RoomManager roomManager;

    @Autowired
    public GameServerHandler(RoomManager roomManager) {
        this.roomManager = roomManager;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        logger.info("新连接：{}", ctx.channel().remoteAddress());
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
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
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

    /**
     * 处理 IdleStateEvent（心跳超时）
     */
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

    /**
     * 处理客户端消息
     */
    private void handleMessage(Channel conn, GameMessage msg) {
        switch (msg.getType()) {
            case GET_ROOMS:
                handleGetRooms(conn);
                break;
            case JOIN:
                handleJoin(conn, msg);
                break;
            case SPECTATE:
                handleSpectate(conn, msg);
                break;
            case ADD_AI:
                handleAddAI(conn);
                break;
            case MOVE:
                handleMove(conn, msg);
                break;
            case CHAT:
                handleChat(conn, msg);
                break;
            case RESTART:
                handleRestart(conn);
                break;
            case LEAVE:
                handleLeave(conn);
                break;
            case RECONNECT:
                handleReconnect(conn, msg);
                break;
            case SURRENDER:
                handleSurrender(conn);
                break;
            case PING:
                // 心跳响应，无需处理
                break;
            default:
                sendError(conn, "未知消息类型");
        }
    }

    private void handleGetRooms(Channel conn) {
        GameMessage res = new GameMessage(GameMessage.Type.ROOM_LIST);
        res.setData(roomManager.getRoomsListJson());
        if (conn != null && conn.isActive()) {
            conn.writeAndFlush(new TextWebSocketFrame(gson.toJson(res)));
        }
    }

    /**
     * 处理加入游戏
     */
    private void handleJoin(Channel conn, GameMessage msg) {
        Player existing = roomManager.getPlayer(conn);
        if (existing != null && !existing.canPerformAction()) {
            return; // 操作限流
        }

        String playerName = NameSanitizer.sanitize(msg.getPlayerName());
        GameRoom room = roomManager.joinGame(conn, playerName, msg.getRoomId());
        if (room == null) {
            sendError(conn, "加入房间失败（房间可能已满或不存在）");
            return;
        }
        // 只在等待状态时发送 WAITING（含 sessionId），避免覆盖 GAME_START
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

    /**
     * 处理观战
     */
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
        handleGetRooms(conn); // 返回大厅后刷新房间列表
    }

    private void handleAddAI(Channel conn) {
        Player player = roomManager.getPlayer(conn);
        if (player != null && !player.canPerformAction()) {
            return; // 操作限流
        }

        GameRoom room = roomManager.addAI(conn);
        if (room == null) {
            sendError(conn, "添加电脑失败");
        }
    }

    /**
     * 处理落子
     */
    private void handleMove(Channel conn, GameMessage msg) {
        Player player = roomManager.getPlayer(conn);
        if (player == null) {
            sendError(conn, "请先加入游戏");
            return;
        }

        if (!player.canPerformAction()) {
            return; // 操作限流
        }

        GameRoom room = roomManager.getRoomByPlayer(player);
        if (room == null) {
            sendError(conn, "房间不存在");
            return;
        }

        boolean success = room.handleMove(player, msg.getRow(), msg.getCol());
        if (!success) {
            sendError(conn, "无效落子");
        }
    }

    /**
     * 处理聊天
     */
    private void handleChat(Channel conn, GameMessage msg) {
        Player player = roomManager.getPlayer(conn);
        if (player == null) return;

        GameRoom room = roomManager.getRoomByPlayer(player);
        if (room == null) return;

        String text = NameSanitizer.sanitizeChat(msg.getMessage());
        if (text == null) return;

        room.handleChat(player, text);
    }

    /**
     * 处理重新开始
     */
    private void handleRestart(Channel conn) {
        Player player = roomManager.getPlayer(conn);
        if (player == null) return;

        if (!player.canPerformAction()) {
            return; // 操作限流
        }

        GameRoom room = roomManager.getRoomByPlayer(player);
        if (room == null) return;

        room.requestRestart(player);
    }

    /**
     * 处理认输
     */
    private void handleSurrender(Channel conn) {
        Player player = roomManager.getPlayer(conn);
        if (player == null) {
            sendError(conn, "请先加入游戏");
            return;
        }

        GameRoom room = roomManager.getRoomByPlayer(player);
        if (room == null) return;

        room.surrender(player);
    }

    /**
     * 处理断线重连
     */
    private void handleReconnect(Channel conn, GameMessage msg) {
        if (msg.getSessionId() == null) {
            sendError(conn, "重连需要 sessionId");
            return;
        }

        GameRoom room = roomManager.reconnect(conn, msg.getSessionId());
        if (room == null) {
            sendError(conn, "重连失败（会话已过期）");
        } else {
            // 重连成功，发送完整游戏状态
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

    /**
     * 发送错误消息
     */
    private void sendError(Channel conn, String errorMsg) {
        GameMessage error = new GameMessage(GameMessage.Type.ERROR);
        error.setMessage(errorMsg);
        if (conn != null && conn.isActive()) {
            conn.writeAndFlush(new TextWebSocketFrame(gson.toJson(error)));
        }
    }
}
