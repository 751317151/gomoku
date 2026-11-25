package com.gomoku.game;

import com.gomoku.dto.GameSyncDto;
import com.gomoku.model.Player;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class UnoRoomTest {
    @Test void endingDrawAdvancesVersionAndReconnectRestoresPrivateDecision() {
        GameRoom room = new GameRoom("UNO-DRAW", GameType.UNO, true, 4);
        List<EmbeddedChannel> channels = new ArrayList<>();
        try {
            List<Player> players = join(room, channels);
            UnoBattleEngine engine = (UnoBattleEngine) room.getEngine();
            engine.setTopForTest(0, 3);
            engine.setHandForTest(1, List.of(card(1, 4), card(2, 5)));
            engine.setDeckForTest(List.of(card(0, 8)), List.of());
            String initial = room.buildSyncDataForReconnect(players.get(0)).getRoomData().getActionVersion();
            assertTrue(room.handleCommand(players.get(0), MoveCommand.action(UnoBattleEngine.DRAW_ACTION), -1, initial));
            GameSyncDto sync = room.buildSyncDataForReconnect(players.get(0));
            Map<?, ?> view = (Map<?, ?>) sync.getView();
            assertEquals(1, sync.getMoveSeq());
            assertEquals(1, view.get("drawnPendingSide"));
            assertEquals(true, view.get("canEndDraw"));
            String decision = sync.getRoomData().getActionVersion();
            assertNotEquals(initial, decision);
            assertFalse(room.handleCommand(players.get(0), MoveCommand.action(UnoBattleEngine.END_DRAW_ACTION), -1, initial));
            assertTrue(room.handleCommand(players.get(0), MoveCommand.action(UnoBattleEngine.END_DRAW_ACTION), -1, decision));
            sync = room.buildSyncDataForReconnect(players.get(0));
            assertEquals(2, sync.getMoveSeq());
            assertEquals(2, sync.getRoomData().getCurrentTurn());
            assertNotEquals(decision, sync.getRoomData().getActionVersion());
            assertEquals(2, engine.getMoveHistory().size());
            assertFalse(room.handleCommand(players.get(0), MoveCommand.action(UnoBattleEngine.END_DRAW_ACTION), -1, decision));
        } finally { room.notifyShutdown(); channels.forEach(EmbeddedChannel::finishAndReleaseAll); }
    }

    @Test void departedExchangeIsRejectedAndSpectatorHasNoHandOrActions() {
        GameRoom room = new GameRoom("UNO-EXCHANGE", GameType.UNO, true, 4);
        List<EmbeddedChannel> channels = new ArrayList<>();
        try {
            List<Player> players = join(room, channels);
            UnoBattleEngine engine = (UnoBattleEngine) room.getEngine();
            engine.setTopForTest(0, 3);
            engine.setHandForTest(1, List.of(card(0, 7), card(2, 5)));
            room.removePlayer(players.get(1));
            assertFalse(engine.isActive(2));
            assertFalse(room.handleCommand(players.get(0), MoveCommand.withTarget(0, 2, 2), -1));
            assertEquals(0, engine.getMoveSeq());
            assertFalse(room.handleCommand(players.get(1), MoveCommand.action(UnoBattleEngine.CATCH_UNO_ACTION), -1));
            EmbeddedChannel channel = new EmbeddedChannel(); channels.add(channel);
            Player spectator = new Player("uno-view", "观众", channel); room.addSpectator(spectator);
            Map<?, ?> publicView = (Map<?, ?>) room.buildSyncDataForReconnect(spectator).getView();
            for (Object player : (List<?>) publicView.get("players")) assertFalse(((Map<?, ?>) player).containsKey("hand"));
            for (String flag : List.of("canDraw", "canEndDraw", "canCallUno", "canCatchUno")) assertEquals(false, publicView.get(flag));
            assertTrue(room.handleCommand(players.get(0), MoveCommand.withTarget(0, 2, 3), -1));
            assertEquals(1, engine.getMoveSeq());
            assertEquals(3, engine.getMoveHistory().get(0)[4]);
        } finally { room.notifyShutdown(); channels.forEach(EmbeddedChannel::finishAndReleaseAll); }
    }

    private static List<Player> join(GameRoom room, List<EmbeddedChannel> channels) {
        List<Player> players = new ArrayList<>();
        for (int side = 1; side <= 4; side++) {
            EmbeddedChannel channel = new EmbeddedChannel(); channels.add(channel);
            Player player = new Player("uno-" + side, "玩家" + side, channel); players.add(player);
            assertTrue(room.addPlayer(player));
        }
        ReflectionTestUtils.invokeMethod(room, "cancelTurnTimer");
        return players;
    }
    private static UnoBattleEngine.Card card(int color, int value) { return new UnoBattleEngine.Card(color, value); }
}
