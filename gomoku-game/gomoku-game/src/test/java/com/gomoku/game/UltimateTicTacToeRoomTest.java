package com.gomoku.game;

import com.gomoku.dto.GameSyncDto;
import com.gomoku.model.Player;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UltimateTicTacToeRoomTest {

    @Test
    void coordinatesAndForcedRegionSurvivePlayerAndSpectatorSync() {
        GameRoom room = new GameRoom("UTTT-SYNC", GameType.ULTIMATE_TTT);
        Player cross = new Player("cross", "叉方", new EmbeddedChannel());
        Player circle = new Player("circle", "圈方", new EmbeddedChannel());
        Player watcher = new Player("watcher", "观众", new EmbeddedChannel());
        try {
            assertTrue(room.addPlayer(cross));
            assertTrue(room.addPlayer(circle));
            room.addSpectator(watcher);
            assertInstanceOf(UltimateTicTacToeBoard.class, room.getEngine());
            assertTrue(room.handleMove(cross, 4, 4, 1));
            GameSyncDto sync = room.buildSyncDataForReconnect(circle);
            assertEquals("ULTIMATE_TTT", sync.getGameType());
            assertEquals(1, sync.getBoard()[4][4]);
            assertEquals(1, sync.getMoveSeq());
            assertEquals(2, sync.getRoomData().getCurrentTurn());
            Map<?, ?> view = (Map<?, ?>) sync.getView();
            assertEquals(4, view.get("nextBoard"));
            assertEquals(8, ((List<?>) view.get("legalMoves")).size());
            assertEquals(4, ((Map<?, ?>) sync.getRoomData().getView()).get("nextBoard"));
            GameSyncDto publicSync = room.buildSyncDataForReconnect(watcher);
            assertEquals(4, ((Map<?, ?>) publicSync.getView()).get("nextBoard"));
            assertEquals(1, publicSync.getBoard()[4][4]);
            assertFalse(room.handleMove(circle, 0, 0, 2));
            assertEquals(1, room.getEngine().getMoveSeq());
            assertTrue(room.handleMove(circle, 3, 3, 2));
            assertEquals(0, ((Map<?, ?>) room.buildSyncDataForReconnect(cross).getView()).get("nextBoard"));
        } finally {
            room.notifyShutdown();
            ((EmbeddedChannel) cross.getConnection()).finishAndReleaseAll();
            ((EmbeddedChannel) circle.getConnection()).finishAndReleaseAll();
            ((EmbeddedChannel) watcher.getConnection()).finishAndReleaseAll();
        }
    }
}
