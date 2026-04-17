package com.gomoku.server;

import com.gomoku.game.GameRoom;
import com.gomoku.game.RoomManager;
import com.gomoku.model.GameMessage;
import com.gomoku.model.Player;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.logging.Logger;

@Component
@ChannelHandler.Sharable
public class GameServerHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private static final Logger logger = Logger.getLogger(GameServerHandler.class.getName());
    private static final Gson gson = new Gson();
    private final RoomManager roomManager;

    @Autowired
    public GameServerHandler(RoomManager roomManager) {
        this.roomManager = roomManager;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        logger.info("新连接：" + ctx.channel().remoteAddress());
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        logger.info("连接断开：" + ctx.channel().remoteAddress());
        roomManager.handleDisconnect(ctx.channel());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) throws Exception {
        String message = frame.text();
        try {
            GameMessage msg = gson.fromJson(message, GameMessage.class);
            handleMessage(ctx.channel(), msg);
        } catch (JsonSyntaxException e) {
            logger.warning("无效消息格式：" + message);
            sendError(ctx.channel(), "消息格式错误");
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.severe("WebSocket错误：" + cause.getMessage());
        roomManager.handleDisconnect(ctx.channel());
        ctx.close();
    }

    /**
     * 处理客户端消息
     */
    private void handleMessage(Channel conn, GameMessage msg) {
        if (msg.getType() == null) {
            sendError(conn, "消息类型不能为空");
            return;
        }

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
        String playerName = msg.getPlayerName();
        if (playerName == null || playerName.trim().isEmpty()) {
            playerName = "玩家" + (int)(Math.random() * 9000 + 1000);
        }
        playerName = playerName.trim().substring(0, Math.min(playerName.trim().length(), 16));

        GameRoom room = roomManager.joinGame(conn, playerName, msg.getRoomId());
        if (room == null) {
            sendError(conn, "加入房间失败（房间可能已满或不存在）");
        }
    }
    
    /**
     * 处理观战
     */
    private void handleSpectate(Channel conn, GameMessage msg) {
        String playerName = msg.getPlayerName();
        if (playerName == null || playerName.trim().isEmpty()) {
            playerName = "观众" + (int)(Math.random() * 9000 + 1000);
        }
        playerName = playerName.trim().substring(0, Math.min(playerName.trim().length(), 16));

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

        String text = msg.getMessage();
        if (text == null || text.trim().isEmpty()) return;
        text = text.trim().substring(0, Math.min(text.trim().length(), 200));

        room.handleChat(player, text);
    }

    /**
     * 处理重新开始
     */
    private void handleRestart(Channel conn) {
        Player player = roomManager.getPlayer(conn);
        if (player == null) return;

        GameRoom room = roomManager.getRoomByPlayer(player);
        if (room == null) return;

        room.requestRestart(player);
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
