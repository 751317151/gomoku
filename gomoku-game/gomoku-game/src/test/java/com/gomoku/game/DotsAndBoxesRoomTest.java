package com.gomoku.game;

import com.gomoku.dto.GameSyncDto;
import com.gomoku.model.Player;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DotsAndBoxesRoomTest {
    @Test void tiedTwoPlayerGameBroadcastsDrawWithoutAwardingWinsOrElo(@TempDir Path dir) {
        GameRoom room=new GameRoom("DOTS-DRAW",GameType.DOTS_BOXES,true,2);
        room.setEloService(new EloService(dir.resolve("elo.properties"),null));
        List<EmbeddedChannel> channels=new ArrayList<>();List<Player> players=new ArrayList<>();
        try {
            for(int side=1;side<=2;side++) {
                EmbeddedChannel channel=new EmbeddedChannel();channels.add(channel);
                Player player=new Player("draw"+side,"平局"+side,channel);players.add(player);assertTrue(room.addPlayer(player));
            }
            ReflectionTestUtils.invokeMethod(room,"cancelTurnTimer");
            DotsAndBoxesBoard engine=(DotsAndBoxesBoard)room.getEngine();
            int seq=0;
            for(int row=0;row<9;row++) for(int col=0;col<9;col++) if(DotsAndBoxesBoard.isEdge(row,col))
                assertTrue(room.handleMove(players.get(engine.getCurrentTurn()-1),row,col,++seq));
            assertEquals(GameRoom.State.FINISHED,room.getState());
            assertEquals(8,engine.getScore(1));assertEquals(8,engine.getScore(2));
            assertTrue(engine.getWinningSides().isEmpty());
            for(int i=0;i<players.size();i++) {
                Player player=players.get(i);
                assertEquals(0,player.getWins());assertEquals(0,player.getLosses());
                assertEquals(1200,player.getElo());assertEquals(0,player.getLastEloChange());
                EmbeddedChannel channel=channels.get(i);channel.runPendingTasks();
                JsonObject end=null;Object message;
                while((message=channel.readOutbound())!=null) {
                    try {
                        if(message instanceof TextWebSocketFrame) {
                            JsonObject json=JsonParser.parseString(((TextWebSocketFrame)message).text()).getAsJsonObject();
                            if("GAME_OVER".equals(json.get("type").getAsString())) end=json;
                        }
                    } finally {ReferenceCountUtil.release(message);}
                }
                assertNotNull(end);assertEquals("draw",end.get("winner").getAsString());
                JsonObject data=JsonParser.parseString(end.get("data").getAsString()).getAsJsonObject();
                assertEquals(0,data.getAsJsonArray("winningSides").size());
                assertEquals(0,data.get("eloChange").getAsInt());
            }
        } finally {room.notifyShutdown();channels.forEach(EmbeddedChannel::finishAndReleaseAll);}
    }

    @Test void captureKeepsActorResetsStepClockAndSurvivesSpectatorReconnect() {
        GameRoom room=new GameRoom("DOTS-CLOCK",GameType.DOTS_BOXES,true,3);
        List<EmbeddedChannel> channels=new ArrayList<>();List<Player> players=new ArrayList<>();
        try {
            for(int side=1;side<=3;side++) {
                EmbeddedChannel channel=new EmbeddedChannel();channels.add(channel);
                Player player=new Player("dots"+side,"画边"+side,channel);players.add(player);assertTrue(room.addPlayer(player));
            }
            ReflectionTestUtils.invokeMethod(room,"cancelTurnTimer");
            int[][] setup={{0,1},{1,0},{2,1},{0,3},{1,4},{2,3}};
            for(int i=0;i<setup.length;i++) assertTrue(room.handleMove(players.get(i%3),setup[i][0],setup[i][1],i+1));
            ReflectionTestUtils.setField(room,"stepTimeLeft",7);
            ReflectionTestUtils.setField(room,"seatStepTimeLeft",new int[]{7,30,30});
            assertTrue(room.handleMove(players.get(0),1,2,7));
            GameSyncDto sync=room.buildSyncDataForReconnect(players.get(0));
            assertEquals("DOTS_BOXES",sync.getGameType());assertEquals(1,sync.getRoomData().getCurrentTurn());
            assertEquals(30,sync.getTimers().get("stepTime"));
            assertEquals(1,sync.getBoard()[1][1]);assertEquals(1,sync.getBoard()[1][3]);
            assertEquals(2,((Map<?,?>)sync.getView()).get("lastCaptured"));
            assertFalse(room.handleMove(players.get(0),0,5,7));assertEquals(7,room.getEngine().getMoveSeq());
            assertTrue(room.handleMove(players.get(0),0,5,8));assertEquals(2,room.getEngine().getCurrentTurn());
            EmbeddedChannel watcherChannel=new EmbeddedChannel();channels.add(watcherChannel);
            Player watcher=new Player("dots-watch","观众",watcherChannel);room.addSpectator(watcher);
            GameSyncDto publicSync=room.buildSyncDataForReconnect(watcher);
            assertEquals(8,publicSync.getMoveSeq());assertEquals(1,publicSync.getBoard()[1][3]);
            assertEquals(3,((Map<?,?>)publicSync.getView()).get("playerCount"));
        } finally {room.notifyShutdown();channels.forEach(EmbeddedChannel::finishAndReleaseAll);}
    }
}
