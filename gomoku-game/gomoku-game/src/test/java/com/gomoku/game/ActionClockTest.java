package com.gomoku.game;

import com.gomoku.model.Player;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ActionClockTest {
    private GameRoom room;
    private final List<EmbeddedChannel> channels = new ArrayList<>();

    private void create(GameType type, int count) {
        room = new GameRoom("CLOCK", type, true, count);
        for (int i = 1; i <= count; i++) {
            EmbeddedChannel channel = new EmbeddedChannel(); channels.add(channel);
            room.addPlayer(new Player("clock-" + i, "玩家" + i, channel));
        }
        ReflectionTestUtils.invokeMethod(room, "cancelTurnTimer");
    }
    private void tick() { ReflectionTestUtils.invokeMethod(room, "tickTimers"); }
    private Map<String, Object> clocks() { return room.buildSyncDataForReconnect(room.getPlayers().get(0)).getTimers(); }
    private int[] steps() { return (int[]) clocks().get("seatSteps"); }
    private int[] totals() { return (int[]) clocks().get("seatTotals"); }
    private Player seat(int side) { return room.getPlayers().stream().filter(p -> p.getStone() == side).findFirst().orElseThrow(); }
    @AfterEach void close() { if (room != null) room.notifyShutdown(); channels.forEach(EmbeddedChannel::finishAndReleaseAll); }

    @Test void allUnsubmittedPlayersRunClocksAndSubmittedPlayerStops() {
        create(GameType.HIGH_CARD, 3);
        tick(); assertArrayEquals(new int[]{29,29,29}, steps());
        assertTrue(room.handleCommand(seat(3), MoveCommand.action(10), -1));
        tick(); assertArrayEquals(new int[]{28,28,29}, steps());
        assertArrayEquals(new int[]{298,298,299}, totals());
        assertEquals(List.of(1,2), clocks().get("awaitingSides"));
    }
    @Test void otherSubmissionsDoNotExtendDeadlineAndNewRoundResetsSteps() {
        create(GameType.HIGH_CARD, 3); tick(); tick();
        assertTrue(room.handleCommand(seat(1), MoveCommand.action(10), -1));
        assertArrayEquals(new int[]{28,28,28}, steps());
        assertTrue(room.handleCommand(seat(2), MoveCommand.action(9), -1));
        assertArrayEquals(new int[]{28,28,28}, steps());
        assertTrue(room.handleCommand(seat(3), MoveCommand.action(8), -1));
        assertArrayEquals(new int[]{30,30,30}, steps());
        assertArrayEquals(new int[]{298,298,298}, totals());
    }
    @Test void simultaneousTimeoutEliminatesEveryExpiredSeatWithoutASeatOrderWinner() {
        create(GameType.HIGH_CARD, 3);
        assertTrue(room.handleCommand(seat(3), MoveCommand.action(10), -1));
        ReflectionTestUtils.setField(room, "seatStepTimeLeft", new int[]{1,1,30});
        tick(); assertEquals(GameRoom.State.FINISHED, room.getState());
        assertEquals(List.of(3), room.getEngine().getWinningSides());
    }
    @Test void everyoneTimingOutTogetherIsADraw() {
        create(GameType.HIGH_CARD, 3);
        ReflectionTestUtils.setField(room, "seatStepTimeLeft", new int[]{1,1,1});
        tick(); assertEquals(GameRoom.State.FINISHED, room.getState());
        assertEquals("draw", room.buildSyncDataForReconnect(seat(1)).getGameOver().getWinner());
    }
    @Test void oneSeatTimingOutDoesNotResetOtherPendingSeats() {
        create(GameType.HIGH_CARD, 4);
        ReflectionTestUtils.setField(room, "seatStepTimeLeft", new int[]{1,10,12,15});
        tick();
        assertEquals(GameRoom.State.PLAYING, room.getState());
        assertArrayEquals(new int[]{0,9,11,14}, steps());
        assertEquals(List.of(2,3,4), clocks().get("awaitingSides"));
        assertEquals(1, room.getEngine().getActionRound());
    }
    @Test void cardActionsDoNotResetTurnDeadline() {
        create(GameType.CARD, 2); tick();
        int before = steps()[0];
        @SuppressWarnings("unchecked") Map<String,Object> view = (Map<String,Object>)room.getEngine().getViewState(1);
        @SuppressWarnings("unchecked") List<Map<String,Object>> players = (List<Map<String,Object>>)view.get("players");
        @SuppressWarnings("unchecked") List<Map<String,Object>> hand = (List<Map<String,Object>>)players.get(0).get("hand");
        int mana = ((Number)players.get(0).get("mana")).intValue();
        boolean played = false;
        for (int i=0; i<hand.size(); i++) if (((Number)hand.get(i).get("cost")).intValue() <= mana
                && room.handleCommand(seat(1), MoveCommand.action(i), -1)) { played = true; break; }
        assertTrue(played); assertEquals(before, steps()[0]);
        assertTrue(room.handleEndTurn(seat(1)));
        assertEquals(room.getEngine().getStepTimeSeconds(), steps()[1]);
    }
    @Test void quizSecondSeatCanAnswerAndOnlyPendingSeatKeepsRunning() {
        create(GameType.QUIZ, 2); tick();
        assertTrue(room.handleCommand(seat(2), MoveCommand.action(0), -1));
        tick(); assertArrayEquals(new int[]{23,24}, steps());
        assertTrue(room.handleCommand(seat(1), MoveCommand.action(1), -1));
        assertEquals(1, room.getEngine().getActionRound());
        assertArrayEquals(new int[]{25,25}, steps());
        assertTrue(room.handleCommand(seat(2), MoveCommand.action(2), -1));
    }
}
