package com.gomoku.server;

import com.gomoku.model.GameMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 二进制协议编码器：GameMessage → ByteBuf → BinaryWebSocketFrame
 *
 * 在 Pipeline 中位于 GameServerHandler 之后，将出站的 GameMessage 编码为二进制帧。
 * 仅当 Channel 的 PROTOCOL_KEY 属性为 "binary" 时激活。
 */
@ChannelHandler.Sharable
public class BinaryMessageEncoder extends MessageToMessageEncoder<GameMessage> {

    private static final Logger logger = LoggerFactory.getLogger(BinaryMessageEncoder.class);

    @Override
    protected void encode(ChannelHandlerContext ctx, GameMessage msg, List<Object> out) {
        ByteBuf buf = Unpooled.buffer();

        // Header
        buf.writeShort(GameProtocol.MAGIC);
        buf.writeByte(GameProtocol.VERSION);

        byte typeCode = encodeType(msg.getType());
        buf.writeByte(typeCode);

        // Payload
        switch (typeCode) {
            case GameProtocol.TYPE_PING:
            case GameProtocol.TYPE_GET_ROOMS:
            case GameProtocol.TYPE_LEAVE:
            case GameProtocol.TYPE_ADD_AI:
            case GameProtocol.TYPE_RESTART:
            case GameProtocol.TYPE_SURRENDER:
                // 无 payload
                break;

            case GameProtocol.TYPE_JOIN:
                writeString(buf, msg.getPlayerName());
                writeNullableString(buf, msg.getRoomId());
                break;

            case GameProtocol.TYPE_SPECTATE:
                writeString(buf, msg.getPlayerName());
                writeString(buf, msg.getRoomId());
                break;

            case GameProtocol.TYPE_MOVE:
                buf.writeByte(msg.getRow());
                buf.writeByte(msg.getCol());
                buf.writeInt(msg.getMoveSeq());
                break;

            case GameProtocol.TYPE_CHAT:
                writeString(buf, msg.getMessage());
                break;

            case GameProtocol.TYPE_RECONNECT:
                writeString(buf, msg.getSessionId());
                break;

            // 服务端消息
            case GameProtocol.TYPE_WAITING:
                writeNullableString(buf, msg.getRoomId());
                writeNullableString(buf, msg.getPlayerId());
                writeNullableString(buf, msg.getSessionId());
                writeNullableString(buf, msg.getMessage());
                break;

            case GameProtocol.TYPE_GAME_START:
                writeNullableString(buf, msg.getRoomId());
                writeNullableString(buf, msg.getPlayerId());
                buf.writeByte(msg.getStone());
                writeNullableString(buf, msg.getMessage());
                writeNullableString(buf, msg.getData());
                break;

            case GameProtocol.TYPE_GAME_MOVE:
                writeNullableString(buf, msg.getRoomId());
                writeNullableString(buf, msg.getPlayerId());
                writeNullableString(buf, msg.getPlayerName());
                buf.writeByte(msg.getRow());
                buf.writeByte(msg.getCol());
                buf.writeByte(msg.getStone());
                buf.writeInt(msg.getMoveSeq());
                writeNullableString(buf, msg.getData());
                break;

            case GameProtocol.TYPE_GAME_OVER:
                writeNullableString(buf, msg.getRoomId());
                writeNullableString(buf, msg.getWinner());
                writeNullableString(buf, msg.getMessage());
                writeNullableString(buf, msg.getData());
                break;

            case GameProtocol.TYPE_GAME_CHAT:
                writeNullableString(buf, msg.getRoomId());
                writeNullableString(buf, msg.getPlayerId());
                writeNullableString(buf, msg.getPlayerName());
                writeNullableString(buf, msg.getMessage());
                break;

            case GameProtocol.TYPE_GAME_SYNC:
                writeNullableString(buf, msg.getRoomId());
                writeNullableString(buf, msg.getPlayerId());
                writeNullableString(buf, msg.getSessionId());
                buf.writeByte(msg.getStone());
                writeNullableString(buf, msg.getMessage());
                writeNullableString(buf, msg.getData());
                break;

            case GameProtocol.TYPE_ROOM_INFO:
            case GameProtocol.TYPE_ROOM_LIST:
                writeNullableString(buf, msg.getRoomId());
                writeNullableString(buf, msg.getData());
                break;

            case GameProtocol.TYPE_RESTART_REQ:
                writeNullableString(buf, msg.getRoomId());
                writeNullableString(buf, msg.getPlayerName());
                writeNullableString(buf, msg.getMessage());
                break;

            case GameProtocol.TYPE_ERROR:
                writeNullableString(buf, msg.getMessage());
                break;

            default:
                // 兜底：将整个 message JSON 作为 payload
                if (msg.getData() != null) {
                    writeString(buf, msg.getData());
                }
                break;
        }

        out.add(new BinaryWebSocketFrame(buf));
    }

    private void writeString(ByteBuf buf, String str) {
        if (str == null) {
            buf.writeShort(0);
            return;
        }
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        buf.writeShort(bytes.length);
        buf.writeBytes(bytes);
    }

    private void writeNullableString(ByteBuf buf, String str) {
        if (str == null) {
            buf.writeByte(0);
        } else {
            buf.writeByte(1);
            writeString(buf, str);
        }
    }

    private byte encodeType(GameMessage.Type type) {
        switch (type) {
            case GET_ROOMS:       return GameProtocol.TYPE_GET_ROOMS;
            case JOIN:            return GameProtocol.TYPE_JOIN;
            case SPECTATE:        return GameProtocol.TYPE_SPECTATE;
            case ADD_AI:          return GameProtocol.TYPE_ADD_AI;
            case MOVE:            return GameProtocol.TYPE_MOVE;
            case CHAT:            return GameProtocol.TYPE_CHAT;
            case RESTART:         return GameProtocol.TYPE_RESTART;
            case LEAVE:           return GameProtocol.TYPE_LEAVE;
            case RECONNECT:       return GameProtocol.TYPE_RECONNECT;
            case SURRENDER:       return GameProtocol.TYPE_SURRENDER;
            case PING:            return GameProtocol.TYPE_PING;
            case ROOM_LIST:       return GameProtocol.TYPE_ROOM_LIST;
            case ROOM_INFO:       return GameProtocol.TYPE_ROOM_INFO;
            case GAME_START:      return GameProtocol.TYPE_GAME_START;
            case GAME_SYNC:       return GameProtocol.TYPE_GAME_SYNC;
            case GAME_MOVE:       return GameProtocol.TYPE_GAME_MOVE;
            case GAME_OVER:       return GameProtocol.TYPE_GAME_OVER;
            case GAME_CHAT:       return GameProtocol.TYPE_GAME_CHAT;
            case WAITING:         return GameProtocol.TYPE_WAITING;
            case RESTART_REQUEST: return GameProtocol.TYPE_RESTART_REQ;
            case ERROR:           return GameProtocol.TYPE_ERROR;
            default:              return 0;
        }
    }
}
