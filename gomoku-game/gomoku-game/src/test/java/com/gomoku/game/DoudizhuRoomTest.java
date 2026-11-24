package com.gomoku.game;

import com.gomoku.dto.GameSyncDto;
import com.gomoku.model.Player;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 斗地主真实房间链路：三人入座、叫地主、手牌视角与出牌广播。 */
class DoudizhuRoomTest {

    private static Player player(String id) {
        return new Player(id, id, new EmbeddedChannel());
    }

    @Test
    void threePlayersBidAndOnlyLandlordSeesOwnHand() {
        GameRoom room = new GameRoom("DDZ-ROOM", GameType.DOUDIZHU, true, 3);
        Player one = player("one");
        Player two = player("two");
        Player three = player("three");
        assertTrue(room.addPlayer(one));
        assertTrue(room.addPlayer(two));
        assertTrue(room.addPlayer(three));

        GameSyncDto syncOne = room.buildSyncDataForReconnect(one);
        GameSyncDto syncTwo = room.buildSyncDataForReconnect(two);
        assertEquals("DOUDIZHU", syncOne.getGameType());
        assertNull(syncOne.getBoard(), "非棋盘玩法不下发虚构盘面");
        Map<?, ?> viewOne = (Map<?, ?>) syncOne.getView();
        Map<?, ?> viewTwo = (Map<?, ?>) syncTwo.getView();
        List<?> playersOne = (List<?>) viewOne.get("players");
        assertTrue(((Map<?, ?>) playersOne.get(0)).containsKey("hand"));
        assertFalse(((Map<?, ?>) playersOne.get(1)).containsKey("hand"));
        assertTrue(((Map<?, ?>) ((List<?>) viewTwo.get("players")).get(1)).containsKey("hand"));
        assertFalse(((Map<?, ?>) ((List<?>) viewTwo.get("players")).get(0)).containsKey("hand"));

        assertTrue(room.handleCommand(one, MoveCommand.action(DoudizhuEngine.NO_BID_ACTION), -1));
        assertTrue(room.handleCommand(two, MoveCommand.action(2), -1));
        assertTrue(room.handleCommand(three, MoveCommand.action(DoudizhuEngine.NO_BID_ACTION), -1));
        DoudizhuEngine engine = (DoudizhuEngine) room.getEngine();
        assertFalse(engine.isBidding());
        assertEquals(2, engine.getLandlord());
        assertEquals(2, engine.getCurrentTurn(), "地主先手");
        assertEquals(20, engine.getHand(2).size());
    }
}
