package com.gomoku.game;

import com.gomoku.model.Player;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class DiceRacePressRoomTest {
    @Test void phasesHaveSeparateVersionsAndOnlyPendingRiskPlayersRunClocks() {
        GameRoom room = new GameRoom("DICE-PRESS-CLOCK", GameType.DICE_RACE, true, 3);
        List<EmbeddedChannel> channels = new ArrayList<>();
        try {
            // 固定掷出 4：两名冒险者均成功，稳健玩家不进入二次决策。
            ReflectionTestUtils.setField(room.getEngine(), "random", new Random() {
                @Override public int nextInt(int bound) { return 3; }
            });
            for (int side = 1; side <= 3; side++) {
                EmbeddedChannel channel = new EmbeddedChannel(); channels.add(channel);
                assertTrue(room.addPlayer(new Player("p" + side, "玩家" + side, channel)));
            }
            ReflectionTestUtils.invokeMethod(room, "cancelTurnTimer");
            ReflectionTestUtils.setField(room, "seatStepTimeLeft", new int[]{25,25,25});
            ReflectionTestUtils.setField(room, "totalTimeLeft", new int[]{240,240,240});
            List<Player> players = room.getPlayers();
            String chooseVersion = room.buildSyncDataForReconnect(players.get(0)).getRoomData().getActionVersion();
            ReflectionTestUtils.invokeMethod(room, "tickTimers");
            assertTrue(room.handleCommand(players.get(0), MoveCommand.action(1), -1, chooseVersion));
            ReflectionTestUtils.invokeMethod(room, "tickTimers");
            assertArrayEquals(new int[]{24,23,23}, (int[]) clocks(room, players).get("seatSteps"));
            assertTrue(room.handleCommand(players.get(2), MoveCommand.action(0), -1, chooseVersion));
            assertTrue(room.handleCommand(players.get(1), MoveCommand.action(1), -1, chooseVersion));
            String pressVersion = room.buildSyncDataForReconnect(players.get(0)).getRoomData().getActionVersion();
            assertNotEquals(chooseVersion, pressVersion);
            assertArrayEquals(new int[]{25,25,25}, (int[]) clocks(room, players).get("seatSteps"));
            ReflectionTestUtils.invokeMethod(room, "tickTimers");
            assertArrayEquals(new int[]{24,24,25}, (int[]) clocks(room, players).get("seatSteps"));
            assertEquals(List.of(1,2), clocks(room, players).get("awaitingSides"));
            assertFalse(room.handleCommand(players.get(0), MoveCommand.action(5), -1, chooseVersion));
            assertTrue(room.handleCommand(players.get(0), MoveCommand.action(5), -1, pressVersion));
            ReflectionTestUtils.invokeMethod(room, "tickTimers");
            assertArrayEquals(new int[]{24,23,25}, (int[]) clocks(room, players).get("seatSteps"));
            assertEquals(pressVersion, room.buildSyncDataForReconnect(players.get(1)).getRoomData().getActionVersion());
            assertTrue(room.handleCommand(players.get(1), MoveCommand.action(3), -1, pressVersion));
            assertNotEquals(pressVersion, room.buildSyncDataForReconnect(players.get(0)).getRoomData().getActionVersion());
            assertFalse(room.handleCommand(players.get(0), MoveCommand.action(4), -1, pressVersion));
            assertArrayEquals(new int[]{25,25,25}, (int[]) clocks(room, players).get("seatSteps"));
            assertEquals(12, ((DiceRaceEngine) room.getEngine()).getScore(1));
            assertEquals(8, ((DiceRaceEngine) room.getEngine()).getScore(2));
        } finally { room.notifyShutdown(); channels.forEach(EmbeddedChannel::finishAndReleaseAll); }
    }
    private Map<String, Object> clocks(GameRoom room, List<Player> players) {
        return room.buildSyncDataForReconnect(players.get(0)).getTimers();
    }
}
