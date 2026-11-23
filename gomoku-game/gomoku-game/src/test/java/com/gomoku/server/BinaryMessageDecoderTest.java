package com.gomoku.server;

import com.gomoku.model.GameMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BinaryMessageDecoderTest {

    private final EmbeddedChannel channel = new EmbeddedChannel(new BinaryMessageDecoder());

    @AfterEach
    void close() {
        channel.finishAndReleaseAll();
    }

    @Test
    void decodesLegacyMoveWithoutOrigin() {
        writeFrame(movePayload(3, 5, null, 41));

        GameMessage move = channel.readInbound();
        assertEquals(GameMessage.Type.MOVE, move.getType());
        assertEquals(3, move.getRow());
        assertEquals(5, move.getCol());
        assertEquals(-1, move.getFromRow(), "旧格式无起点应为 -1");
        assertEquals(-1, move.getFromCol(), "旧格式无起点应为 -1");
        assertEquals(41, move.getMoveSeq());
        assertNull(channel.readInbound(), "一帧只应产出一条消息");
    }

    @Test
    void decodesMoveWithOriginMarker() {
        writeFrame(movePayload(6, 2, new int[] {1, 4}, 77));

        GameMessage move = channel.readInbound();
        assertEquals(GameMessage.Type.MOVE, move.getType());
        assertEquals(6, move.getRow());
        assertEquals(2, move.getCol());
        assertEquals(1, move.getFromRow());
        assertEquals(4, move.getFromCol());
        assertEquals(77, move.getMoveSeq());
        assertNull(channel.readInbound());
    }

    @Test
    void decodesBackToBackFramesWithMixedFormats() {
        ByteBuf both = Unpooled.buffer();
        appendFrame(both, movePayload(7, 7, new int[] {0, 0}, 1));
        appendFrame(both, movePayload(2, 3, null, 2));
        channel.writeInbound(both);

        GameMessage first = channel.readInbound();
        GameMessage second = channel.readInbound();
        assertEquals(0, first.getFromRow());
        assertEquals(0, first.getFromCol());
        assertEquals(1, first.getMoveSeq());
        assertEquals(-1, second.getFromRow());
        assertEquals(-1, second.getFromCol());
        assertEquals(2, second.getMoveSeq());
        assertNull(channel.readInbound());
    }

    @Test
    void waitsForCompleteExtendedFrameBeforeDecoding() {
        ByteBuf full = frame(movePayload(6, 2, new int[] {1, 4}, 77));
        ByteBuf firstPart = full.readRetainedSlice(full.readableBytes() - 2);
        ByteBuf lastPart = full.readRetainedSlice(full.readableBytes());
        full.release();
        channel.writeInbound(firstPart);
        assertNull(channel.readInbound(), "序号未到齐时不能提前产出错误消息");

        channel.writeInbound(lastPart);
        GameMessage move = channel.readInbound();
        assertEquals(6, move.getRow());
        assertEquals(2, move.getCol());
        assertEquals(1, move.getFromRow());
        assertEquals(4, move.getFromCol());
        assertEquals(77, move.getMoveSeq());
    }

    private void writeFrame(ByteBuf payload) {
        channel.writeInbound(frame(payload));
    }

    private static ByteBuf frame(ByteBuf payload) {
        ByteBuf buffer = Unpooled.buffer();
        appendFrame(buffer, payload);
        return buffer;
    }

    private static void appendFrame(ByteBuf target, ByteBuf payload) {
        target.writeShort(GameProtocol.MAGIC);
        target.writeByte(GameProtocol.VERSION);
        target.writeByte(GameProtocol.TYPE_MOVE);
        target.writeBytes(payload);
        payload.release();
    }

    private static ByteBuf movePayload(int row, int col, int[] origin, int moveSeq) {
        ByteBuf payload = Unpooled.buffer();
        payload.writeByte(row);
        payload.writeByte(col);
        if (origin != null) {
            payload.writeByte(0x80);
            payload.writeByte(origin[0]);
            payload.writeByte(origin[1]);
        }
        payload.writeInt(moveSeq);
        return payload;
    }
}
