package com.gomoku.game;

import com.gomoku.model.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GuestIdentityTest {
    @TempDir Path dir;
    @Test void serverIssuedTokenSurvivesRestartAndDiskContainsOnlyHash() throws Exception {
        Path path = dir.resolve("guests.json"); GuestIdentityService first = new GuestIdentityService(path); first.init();
        Map<String, String> issued = first.issue();
        assertEquals(issued.get("id"), first.resolve(issued.get("token")));
        assertFalse(Files.readString(path).contains(issued.get("token")));
        GuestIdentityService restarted = new GuestIdentityService(path); restarted.init();
        assertEquals(issued.get("id"), restarted.resolve(issued.get("token")));
        assertNull(restarted.resolve("fake-token"));
    }
    @Test void identicalNicknamesDoNotShareRatingAndRenamePreservesIt() {
        EloService elo = new EloService(dir.resolve("elo.properties"), dir.resolve("old.json"));
        Player a = new Player("a", "同名", null), b = new Player("b", "同名", null);
        a.setIdentityId("guest-a"); b.setIdentityId("guest-b"); elo.getRating(a, GameType.GOMOKU); elo.getRating(b, GameType.GOMOKU);
        elo.updateRating(a, b, GameType.GOMOKU);
        assertTrue(elo.getRating(a, GameType.GOMOKU) > elo.getRating(b, GameType.GOMOKU));
        a.setName("新昵称"); assertEquals(a.getElo(), elo.getRating(a, GameType.GOMOKU));
        EloService restarted = new EloService(dir.resolve("elo.properties"), dir.resolve("old.json"));
        assertEquals(a.getElo(), restarted.getRating(a, GameType.GOMOKU));
    }
    @Test void newIdentityDoesNotClaimOldNicknameStats() {
        GameRecordService records = new GameRecordService(dir.resolve("games")); records.init();
        GameRecord game = new GameRecord(); game.setGameType("GOMOKU"); game.setRanked(true);
        GameRecord.Participant a = new GameRecord.Participant("p", "重名", 1, "WIN", false, 1200, 1216, 16);
        a.setIdentityId("guest-a"); game.getParticipants().add(a); records.saveAsync(game); records.flushForTest();
        assertEquals(1, records.getIdentityStats("guest-a", "GOMOKU").get("wins"));
        assertEquals(0, records.getIdentityStats("guest-b", "GOMOKU").get("wins"));
    }
}
