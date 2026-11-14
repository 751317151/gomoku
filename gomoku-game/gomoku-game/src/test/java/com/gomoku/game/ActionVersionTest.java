package com.gomoku.game;

import com.gomoku.model.Player;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ActionVersionTest {
    private GameRoom room;
    private final List<EmbeddedChannel> channels = new ArrayList<>();

    private void create(GameType type, int count) {
        room = new GameRoom("ROOM-VERSION", type, true, count);
        for (int i = 1; i <= count; i++) {
            EmbeddedChannel channel = new EmbeddedChannel(); channels.add(channel);
            room.addPlayer(new Player("p" + i, "玩家" + i, channel));
        }
        ReflectionTestUtils.invokeMethod(room, "cancelTurnTimer");
    }
    private Player seat(int side) { return room.getPlayers().stream().filter(p -> p.getStone() == side).findFirst().orElseThrow(); }
    private String version() { return room.buildSyncDataForReconnect(seat(1)).getRoomData().getActionVersion(); }
    @AfterEach void close() { if (room != null) room.notifyShutdown(); channels.forEach(EmbeddedChannel::finishAndReleaseAll); }

    @Test void simultaneousSubmissionsShareVersionUntilWholeRoundCompletes() {
        create(GameType.HIGH_CARD, 3);
        String initial = version();
        assertTrue(room.handleCommand(seat(3), MoveCommand.action(10), -1, initial));
        assertEquals(initial, version());
        assertFalse(room.handleCommand(seat(3), MoveCommand.action(9), -1, initial));
        assertTrue(room.handleCommand(seat(2), MoveCommand.action(9), -1, initial));
        assertTrue(room.handleCommand(seat(1), MoveCommand.action(8), -1, initial));
        assertNotEquals(initial, version());
        int seq = room.getEngine().getMoveSeq();
        assertFalse(room.handleCommand(seat(1), MoveCommand.action(7), -1, initial));
        assertEquals(seq, room.getEngine().getMoveSeq());
        assertTrue(room.handleCommand(seat(1), MoveCommand.action(7), -1, version()));
    }

    @Test void quizOldQuestionCannotAnswerNewQuestion() {
        create(GameType.QUIZ, 2);
        String initial = version();
        assertTrue(room.handleCommand(seat(2), MoveCommand.action(1), -1, initial));
        assertTrue(room.handleCommand(seat(1), MoveCommand.action(0), -1, initial));
        assertFalse(room.handleCommand(seat(1), MoveCommand.action(0), -1, initial));
        assertTrue(room.handleCommand(seat(2), MoveCommand.action(3), -1, version()));
    }

    @Test void cardDoubleClickCannotConsumeAnotherCardOrEndTurn() {
        create(GameType.CARD, 2);
        String initial = version();
        @SuppressWarnings("unchecked") Map<String,Object> view = (Map<String,Object>)room.getEngine().getViewState(1);
        @SuppressWarnings("unchecked") List<Map<String,Object>> players = (List<Map<String,Object>>)view.get("players");
        @SuppressWarnings("unchecked") List<Map<String,Object>> hand = (List<Map<String,Object>>)players.get(0).get("hand");
        int mana = ((Number)players.get(0).get("mana")).intValue();
        boolean played = false;
        for (int i = 0; i < hand.size(); i++) if (((Number)hand.get(i).get("cost")).intValue() <= mana
                && room.handleCommand(seat(1), MoveCommand.action(i), -1, initial)) {
            played = true;
            int seq = room.getEngine().getMoveSeq();
            assertNotEquals(initial, version());
            assertFalse(room.handleCommand(seat(1), MoveCommand.action(i), -1, initial));
            assertFalse(room.handleAttack(seat(1), 0, -1, initial));
            assertFalse(room.handleEndTurn(seat(1), initial));
            assertEquals(seq, room.getEngine().getMoveSeq());
            assertTrue(room.handleEndTurn(seat(1), version()));
            break;
        }
        assertTrue(played);
    }

    @Test void delayedEndTurnCannotSkipThePlayersNextTurn() {
        create(GameType.CARD, 2);
        String initial = version();
        assertTrue(room.handleEndTurn(seat(1), initial));
        assertTrue(room.handleEndTurn(seat(2), version()));
        assertFalse(room.handleEndTurn(seat(1), initial));
        assertEquals(1, room.getEngine().getCurrentTurn());
        assertTrue(room.handleEndTurn(seat(1), version()));
    }

    @Test void oldGameCommandsRemainInvalidAfterRestartEvenAtSequenceZero() {
        create(GameType.HIGH_CARD, 2);
        String initial = version();
        room.surrender(seat(1));
        for (Player player : room.getPlayers()) room.requestRestart(player);
        ReflectionTestUtils.invokeMethod(room, "cancelTurnTimer");
        assertEquals(GameRoom.State.PLAYING, room.getState());
        assertEquals(0, room.getEngine().getMoveSeq());
        assertFalse(room.handleCommand(seat(1), MoveCommand.action(10), -1, initial));
        assertTrue(room.handleCommand(seat(1), MoveCommand.action(10), -1, version()));
    }

    @Test void boardPassAndMovesUseSameVersionProtection() {
        create(GameType.GO9, 2);
        String initial = version();
        assertTrue(room.handlePass(seat(1), initial));
        assertFalse(room.handleCommand(seat(2), MoveCommand.at(0,0), -1, initial));
        assertFalse(room.handlePass(seat(2), initial));
        assertTrue(room.handleCommand(seat(2), MoveCommand.at(0,0), -1, version()));
    }

    @Test void foreignOrEliminatedPlayerCannotUsePublicVersion() {
        create(GameType.HIGH_CARD, 3);
        EmbeddedChannel channel = new EmbeddedChannel(); channels.add(channel);
        Player foreign = new Player("outside", "观众", channel); foreign.setStone(1);
        assertFalse(room.handleCommand(foreign, MoveCommand.action(10), -1, version()));
        room.surrender(seat(1));
        assertFalse(room.handleCommand(seat(1), MoveCommand.action(10), -1, version()));
        assertTrue(room.handleCommand(seat(2), MoveCommand.action(10), -1, version()));
    }

    @Test void legacyClientWithoutVersionStillWorks() {
        create(GameType.QUIZ, 2);
        assertTrue(room.handleCommand(seat(2), MoveCommand.action(0), -1));
        assertTrue(room.handleCommand(seat(1), MoveCommand.action(1), -1));
    }
}
