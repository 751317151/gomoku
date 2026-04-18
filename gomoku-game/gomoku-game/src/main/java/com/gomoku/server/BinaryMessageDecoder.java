package com.gomoku.server;

import com.gomoku.model.GameMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 二进制协议解码器：ByteBuf → GameMessage
 *
 * 帧格式：MAGIC(2) + VERSION(1) + TYPE(1) + PAYLOAD(variable)
 *
 * Payload 格式按 Type 不同：
 * - 简单消息（PING/LEAVE/GET_ROOMS/ADD_AI/RESTART/SURRENDER）: 无 payload
 * - 带字符串的消息（JOIN/SPECTATE/RECONNECT/CHAT）: 字段按序写入
 * - 落子（MOVE）: row(1) + col(1) + moveSeq(4)
 * - 服务端消息: 按 type 分别解析
 */
public class BinaryMessageDecoder extends ByteToMessageDecoder {

    private static final Logger logger = LoggerFactory.getLogger(BinaryMessageDecoder.class);

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // 至少需要头部 4 字节
        if (in.readableBytes() < GameProtocol.HEADER_SIZE) {
            return;
        }

        // 标记当前读位置，用于回退
        in.markReaderIndex();

        short magic = in.readShort();
        if (magic != GameProtocol.MAGIC) {
            in.resetReaderIndex();
            // 不是二进制协议，跳过（由 JSON 处理器处理）
            return;
        }

        byte version = in.readByte();
        byte typeCode = in.readByte();

        GameMessage.Type type = decodeType(typeCode);
        if (type == null) {
            logger.warn("未知二进制消息类型: {}", typeCode);
            return;
        }

        GameMessage msg = new GameMessage(type);

        try {
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
                    msg.setPlayerName(readString(in));
                    msg.setRoomId(readNullableString(in));
                    break;

                case GameProtocol.TYPE_SPECTATE:
                    msg.setPlayerName(readString(in));
                    msg.setRoomId(readString(in));
                    break;

                case GameProtocol.TYPE_MOVE:
                    if (in.readableBytes() < 6) { in.resetReaderIndex(); return; }
                    msg.setRow(in.readUnsignedByte());
                    msg.setCol(in.readUnsignedByte());
                    msg.setMoveSeq(in.readInt());
                    break;

                case GameProtocol.TYPE_CHAT:
                    msg.setMessage(readString(in));
                    break;

                case GameProtocol.TYPE_RECONNECT:
                    msg.setSessionId(readString(in));
                    break;

                // 服务端消息（解码器也会收到自己发的？不会，这里是入站）
                default:
                    // 尝试读取剩余为 JSON data
                    if (in.readableBytes() > 0) {
                        msg.setData(readString(in));
                    }
                    break;
            }
        } catch (IndexOutOfBoundsException e) {
            // 数据不完整，等待更多数据
            in.resetReaderIndex();
            return;
        }

        out.add(msg);
    }

    private String readString(ByteBuf in) {
        if (in.readableBytes() < 2) throw new IndexOutOfBoundsException();
        int len = in.readUnsignedShort();
        if (in.readableBytes() < len) throw new IndexOutOfBoundsException();
        byte[] bytes = new byte[len];
        in.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private String readNullableString(ByteBuf in) {
        if (in.readableBytes() < 1) throw new IndexOutOfBoundsException();
        byte flag = in.readByte();
        if (flag == 0) return null;
        return readString(in);
    }

    private GameMessage.Type decodeType(byte code) {
        switch (code) {
            case GameProtocol.TYPE_GET_ROOMS:   return GameMessage.Type.GET_ROOMS;
            case GameProtocol.TYPE_JOIN:        return GameMessage.Type.JOIN;
            case GameProtocol.TYPE_SPECTATE:    return GameMessage.Type.SPECTATE;
            case GameProtocol.TYPE_ADD_AI:      return GameMessage.Type.ADD_AI;
            case GameProtocol.TYPE_MOVE:        return GameMessage.Type.MOVE;
            case GameProtocol.TYPE_CHAT:        return GameMessage.Type.CHAT;
            case GameProtocol.TYPE_RESTART:     return GameMessage.Type.RESTART;
            case GameProtocol.TYPE_LEAVE:       return GameMessage.Type.LEAVE;
            case GameProtocol.TYPE_RECONNECT:   return GameMessage.Type.RECONNECT;
            case GameProtocol.TYPE_SURRENDER:   return GameMessage.Type.SURRENDER;
            case GameProtocol.TYPE_PING:        return GameMessage.Type.PING;
            case GameProtocol.TYPE_ROOM_LIST:   return GameMessage.Type.ROOM_LIST;
            case GameProtocol.TYPE_ROOM_INFO:   return GameMessage.Type.ROOM_INFO;
            case GameProtocol.TYPE_GAME_START:  return GameMessage.Type.GAME_START;
            case GameProtocol.TYPE_GAME_SYNC:   return GameMessage.Type.GAME_SYNC;
            case GameProtocol.TYPE_GAME_MOVE:   return GameMessage.Type.GAME_MOVE;
            case GameProtocol.TYPE_GAME_OVER:   return GameMessage.Type.GAME_OVER;
            case GameProtocol.TYPE_GAME_CHAT:   return GameMessage.Type.GAME_CHAT;
            case GameProtocol.TYPE_WAITING:     return GameMessage.Type.WAITING;
            case GameProtocol.TYPE_RESTART_REQ: return GameMessage.Type.RESTART_REQUEST;
            case GameProtocol.TYPE_ERROR:       return GameMessage.Type.ERROR;
            default: return null;
        }
    }
}
