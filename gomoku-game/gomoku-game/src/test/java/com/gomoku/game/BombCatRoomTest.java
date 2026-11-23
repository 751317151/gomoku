package com.gomoku.game;

import com.gomoku.dto.GameSyncDto;
import com.gomoku.model.Player;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 炸弹猫真实房间链路：多人入座、逐人视图与行动指令。 */
class BombCatRoomTest {

    private static Player player(String id) {
        return new Player(id, id, new EmbeddedChannel());
    }

    @Test
    void fourPlayerRoomUsesActionProtocolAndHidesHandsPerViewer() {
        GameRoom room = new GameRoom("BOMB-ROOM", GameType.BOMB_CAT, true, 4);
        Player one = player("one");
        Player two = player("two");
        Player three = player("three");
        Player four = player("four");
        assertTrue(room.addPlayer(one));
        assertTrue(room.addPlayer(two));
        assertTrue(room.addPlayer(three));
        assertTrue(room.addPlayer(four));

        GameSyncDto syncOne = room.buildSyncDataForReconnect(one);
        GameSyncDto syncTwo = room.buildSyncDataForReconnect(two);
        assertEquals("BOMB_CAT", syncOne.getGameType());
        assertNull(syncOne.getBoard(), "非棋盘玩法不下发虚构盘面");
        Map<?, ?> viewOne = (Map<?, ?>) syncOne.getView();
        Map<?, ?> viewTwo = (Map<?, ?>) syncTwo.getView();
        List<?> playersOne = (List<?>) viewOne.get("players");
        List<?> playersTwo = (List<?>) viewTwo.get("players");
        assertTrue(((Map<?, ?>) playersOne.get(0)).containsKey("hand"));
        assertFalse(((Map<?, ?>) playersOne.get(1)).containsKey("hand"));
        assertTrue(((Map<?, ?>) playersTwo.get(1)).containsKey("hand"));
        assertFalse(((Map<?, ?>) playersTwo.get(0)).containsKey("hand"));

        assertTrue(room.handleCommand(one, MoveCommand.action(BombCatEngine.DRAW_ACTION), -1));
        assertTrue(room.getEngine().getMoveCount() >= 1);
        BombCatEngine engine = (BombCatEngine) room.getEngine();
        if (engine.getPendingReinsertSide() == 1) {
            // 首抽可能正好是炸弹：拆弹器生效后仍由本人决定放回位置，不能提前换手。
            assertEquals(1, engine.getCurrentTurn());
            assertTrue(room.handleCommand(one, MoveCommand.action(BombCatEngine.REINSERT_MIDDLE), -1));
        }
        assertEquals(2, room.getEngine().getCurrentTurn());
    }

    @Test
    void aiCanResolvePendingBombReinsertWithoutStalling() {
        BombCatEngine engine = new BombCatEngine();
        engine.configurePlayers(2);
        engine.reset();
        engine.setHandForTest(1, List.of(new BombCatEngine.Card(BombCatEngine.CardType.DEFUSE)));
        engine.setDeckForTest(List.of(new BombCatEngine.Card(BombCatEngine.CardType.BOMB)));
        engine.forceTurnForTest(1);
        assertTrue(engine.applyMove(MoveCommand.action(BombCatEngine.DRAW_ACTION), 1));
        assertEquals(1, engine.getPendingReinsertSide());
        MoveCommand command = BombCatAI.INSTANCE.decide(engine, 1, 4);
        assertNotNull(command);
        assertTrue(engine.applyMove(command, 1));
        assertEquals(0, engine.getPendingReinsertSide());
        assertEquals(2, engine.getCurrentTurn());
    }
}
