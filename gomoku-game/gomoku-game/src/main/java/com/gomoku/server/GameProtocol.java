package com.gomoku.server;

/**
 * 自定义二进制协议常量定义
 *
 * 协议帧格式：
 * +----------+----------+----------+============+
 * | 魔数(2B) | 版本(1B) | 类型(1B) | Payload    |
 * | 0xCAFE   | 0x01     | see below| UTF-8 JSON |
 * +----------+----------+----------+============+
 *
 * Payload 对每种 Type 有不同结构，使用紧凑的二进制字段：
 * - 字符串: short(len) + bytes(utf8)
 * - int: 4 bytes (big-endian)
 * - byte: 1 byte
 *
 * 设计要点：
 * 1. 魔数用于快速识别协议类型（区分 JSON 文本帧和二进制帧）
 * 2. 版本号预留，便于后续协议升级
 * 3. 类型码映射到 GameMessage.Type 的序号
 * 4. 跟 JSON 协议共用同一个 WebSocket 路径 "/"，靠魔数区分
 */
public final class GameProtocol {

    private GameProtocol() {}

    // 魔数：二进制帧以 0xCAFE 开头，JSON 帧以 '{' (0x7B) 开头
    public static final short MAGIC = (short) 0xCAFE;
    public static final byte VERSION = 0x01;

    // 类型码（紧凑 byte 值，映射到 GameMessage.Type）
    // 客户端 -> 服务端
    public static final byte TYPE_GET_ROOMS      = 1;
    public static final byte TYPE_JOIN           = 2;
    public static final byte TYPE_SPECTATE       = 3;
    public static final byte TYPE_ADD_AI         = 4;
    public static final byte TYPE_MOVE           = 5;
    public static final byte TYPE_CHAT           = 6;
    public static final byte TYPE_RESTART        = 7;
    public static final byte TYPE_LEAVE          = 8;
    public static final byte TYPE_RECONNECT      = 9;
    public static final byte TYPE_SURRENDER      = 10;
    public static final byte TYPE_PING           = 11;

    // 服务端 -> 客户端
    public static final byte TYPE_ROOM_LIST      = 50;
    public static final byte TYPE_ROOM_INFO      = 51;
    public static final byte TYPE_GAME_START     = 52;
    public static final byte TYPE_GAME_SYNC      = 53;
    public static final byte TYPE_GAME_MOVE      = 54;
    public static final byte TYPE_GAME_OVER      = 55;
    public static final byte TYPE_GAME_CHAT      = 56;
    public static final byte TYPE_WAITING        = 57;
    public static final byte TYPE_RESTART_REQ    = 58;
    public static final byte TYPE_ERROR          = 59;

    // 帧头部长度: magic(2) + version(1) + type(1) = 4 bytes
    public static final int HEADER_SIZE = 4;
}
