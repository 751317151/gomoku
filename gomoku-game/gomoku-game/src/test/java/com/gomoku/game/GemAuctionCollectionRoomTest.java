package com.gomoku.game;

import com.gomoku.model.Player;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GemAuctionCollectionRoomTest {
    @Test void hiddenPassUsesOneRoundVersionAndOnlyPendingPlayersRunClocks() {
        GameRoom room = new GameRoom("GEM-COLLECTION-CLOCK", GameType.GEM_AUCTION, true, 3);
        List<EmbeddedChannel> channels = new ArrayList<>();
        try {
            for(int side=1;side<=3;side++) {
                EmbeddedChannel channel=new EmbeddedChannel(); channels.add(channel);
                assertTrue(room.addPlayer(new Player("gem"+side,"收藏家"+side,channel)));
            }
            ReflectionTestUtils.invokeMethod(room,"cancelTurnTimer");
            ReflectionTestUtils.setField(room,"seatStepTimeLeft",new int[]{30,30,30});
            ReflectionTestUtils.setField(room,"totalTimeLeft",new int[]{300,300,300});
            List<Player> players=room.getPlayers();
            String version=room.buildSyncDataForReconnect(players.get(0)).getRoomData().getActionVersion();
            ReflectionTestUtils.invokeMethod(room,"tickTimers");
            assertTrue(room.handleCommand(players.get(0),MoveCommand.action(0),-1,version));
            ReflectionTestUtils.invokeMethod(room,"tickTimers");
            Map<String,Object> clocks=room.buildSyncDataForReconnect(players.get(0)).getTimers();
            assertArrayEquals(new int[]{29,28,28},(int[]) clocks.get("seatSteps"));
            assertEquals(List.of(2,3),clocks.get("awaitingSides"));
            assertEquals(version,room.buildSyncDataForReconnect(players.get(1)).getRoomData().getActionVersion());
            assertFalse(room.handleCommand(players.get(0),MoveCommand.action(1),-1,version));
            assertTrue(room.handleCommand(players.get(1),MoveCommand.action(2),-1,version));
            assertTrue(room.handleCommand(players.get(2),MoveCommand.action(1),-1,version));
            assertNotEquals(version,room.buildSyncDataForReconnect(players.get(0)).getRoomData().getActionVersion());
            assertFalse(room.handleCommand(players.get(0),MoveCommand.action(10),-1,version));
            assertArrayEquals(new int[]{30,30,30},(int[])room.buildSyncDataForReconnect(players.get(0)).getTimers().get("seatSteps"));
            GemAuctionEngine engine=(GemAuctionEngine)room.getEngine();
            assertEquals(30,engine.getCoins(1)); assertEquals(28,engine.getCoins(2));
            assertEquals(3,engine.getGems(2)); assertEquals(0,engine.getGems(1));
        } finally { room.notifyShutdown(); channels.forEach(EmbeddedChannel::finishAndReleaseAll); }
    }
}
