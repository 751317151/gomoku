package com.gomoku.game;

import com.gomoku.dto.GameSyncDto;
import com.gomoku.model.Player;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 暗棋真实房间链路：翻子协议、隐藏盘面与随机开局同步。
 *
 * 这里只验证公开 DTO 和真实房间入口；完整落盘/回放由协议冒烟与全量回归覆盖。
 */
class DarkChessRoomTest {

    private static Player player(String id) {
        return new Player(id, id, new EmbeddedChannel());
    }

    @Test
    void roomFlipUsesCoordinateProtocolAndHidesOtherPieces() {
        GameRoom room = new GameRoom("DARK-FLIP", GameType.DARK_CHESS);
        Player red = player("red");
        Player black = player("black");
        assertTrue(room.addPlayer(red));
        assertTrue(room.addPlayer(black));

        // 红方翻开角落；无起点的 MOVE 必须被解释成翻子，而不是非法落子。
        assertTrue(room.handleMove(red, 0, 0, 1));
        assertEquals(1, room.getEngine().getMoveCount());
        assertFalse(room.getEngine().isOver());

        GameSyncDto sync = room.buildSyncDataForReconnect(black);
        int[][] board = sync.getBoard();
        assertNotNull(board);
        int hidden = 0;
        for (int r = 0; r < DarkChessBoard.ROWS; r++) {
            for (int c = 0; c < DarkChessBoard.COLS; c++) {
                if ((r != 0 || c != 0) && board[r][c] == DarkChessBoard.HIDDEN) hidden++;
            }
        }
        assertEquals(31, hidden, "除已翻开的角格外，其余棋子仍须隐藏");
    }

    @Test
    void resetRandomizesHiddenOpeningAndExposesOnlyPublicView() {
        GameRoom room = new GameRoom("DARK-SYNC", GameType.DARK_CHESS);
        Player red = player("red2");
        Player black = player("black2");
        room.addPlayer(red);
        room.addPlayer(black);

        GameSyncDto sync = room.buildSyncDataForReconnect(red);
        assertNotNull(sync.getBoard());
        assertEquals(DarkChessBoard.HIDDEN, sync.getBoard()[0][0]);
        assertEquals(DarkChessBoard.HIDDEN, sync.getBoard()[3][7]);
        assertEquals(1, sync.getRoomData().getCurrentTurn());
        assertFalse(room.getEngine().isOver());
    }
}
