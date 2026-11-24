package com.gomoku.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GameRecommendationServiceTest {

    @TempDir Path dir;

    private static GameRecord record(String id, String type, String identity, boolean aiOnly, long endedAt) {
        GameRecord r = new GameRecord();
        r.setId(id);
        r.setGameType(type);
        r.setStartedAt(endedAt - 1000);
        r.setEndedAt(endedAt);
        GameRecord.Participant p = new GameRecord.Participant(
                "p-" + id, "recommend", 1, "WIN", false, 1200, 1200, 0);
        p.setIdentityId(identity);
        r.getParticipants().add(p);
        if (aiOnly) {
            GameRecord.Participant ai = new GameRecord.Participant(
                    "ai-" + id, "电脑", 2, "LOSE", true, 1200, 1200, 0);
            r.getParticipants().add(ai);
        } else {
            GameRecord.Participant other = new GameRecord.Participant(
                    "other-" + id, "对手", 2, "LOSE", false, 1200, 1200, 0);
            other.setIdentityId("other-" + id);
            r.getParticipants().add(other);
        }
        return r;
    }

    @Test
    void waitingPlayersOutrankEverythingElse() throws Exception {
        GameRecordService records = new GameRecordService(Files.createTempDirectory("rec-wait"));
        try {
            GameRecommendationService service = new GameRecommendationService(records);
            Map<String, Object> overview = Map.of(
                    "games", List.of(Map.of("gameType", "UNO", "waitingPlayers", 3)));
            Map<String, Object> result = service.nextGames(null, overview, 3);
            List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("recommendations");
            assertEquals("UNO", rows.get(0).get("gameType"));
            assertEquals(3, rows.get(0).get("waitingPlayers"));
            assertTrue(String.valueOf(rows.get(0).get("reason")).contains("等待"));
            assertFalse((Boolean) result.get("identityBound"));
        } finally { records.shutdown(); }
    }

    @Test
    void newPlayerGetsUnexploredGamesWithoutWaitingRooms() throws Exception {
        GameRecordService records = new GameRecordService(Files.createTempDirectory("rec-new"));
        try {
            GameRecommendationService service = new GameRecommendationService(records);
            Map<String, Object> result = service.nextGames(null, Map.of(), 4);
            List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("recommendations");
            assertEquals(4, rows.size());
            assertTrue(rows.stream().allMatch(r -> ((Integer) r.get("personalGames")) == 0));
            assertTrue(rows.stream().allMatch(r -> String.valueOf(r.get("reason")).contains("还没玩过")));
        } finally { records.shutdown(); }
    }

    @Test
    void personalPlayCountsPreferLessPlayedGame() throws Exception {
        GameRecordService records = new GameRecordService(Files.createTempDirectory("rec-personal"));
        try {
            long now = System.currentTimeMillis();
            records.saveAsync(record("g1", "GOMOKU", "me", true, now - 3000));
            records.saveAsync(record("g2", "GOMOKU", "me", true, now - 2000));
            records.saveAsync(record("g3", "GOMOKU", "me", true, now - 1000));
            records.flushForTest();
            GameRecommendationService service = new GameRecommendationService(records);
            Map<String, Object> result = service.nextGames("me", Map.of(), 6);
            List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("recommendations");
            // GOMOKU 被玩了 3 次，不应进入「未体验优先」的前几名；接口最多返回 6 个。
            assertTrue(rows.stream().noneMatch(r -> "GOMOKU".equals(r.get("gameType"))),
                    "常玩玩法不应压过未体验玩法");
        } finally { records.shutdown(); }
    }

    @Test
    void aiOnlyRecordsDoNotAffectGlobalPopularity() throws Exception {
        GameRecordService records = new GameRecordService(Files.createTempDirectory("rec-ai"));
        try {
            long now = System.currentTimeMillis();
            records.saveAsync(record("ai1", "CARD", "me", true, now - 2000));
            records.saveAsync(record("human1", "NIM", "other", false, now - 1000));
            records.flushForTest();
            GameRecommendationService service = new GameRecommendationService(records);
            List<Map<String, Object>> rows = (List<Map<String, Object>>)
                    service.nextGames(null, Map.of(), 30).get("recommendations");
            Map<String, Object> card = rows.stream()
                    .filter(r -> "CARD".equals(r.get("gameType"))).findFirst().orElseThrow();
            Map<String, Object> nim = rows.stream()
                    .filter(r -> "NIM".equals(r.get("gameType"))).findFirst().orElseThrow();
            assertEquals(0L, card.get("recentGlobalGames"), "人机局不计入全站热门");
            assertTrue(((Long) nim.get("recentGlobalGames")) > 0);
        } finally { records.shutdown(); }
    }

    @Test
    void freshlyFinishedGameCountsBeforeAsyncArchiveDrain() throws Exception {
        GameRecordService records = new GameRecordService(Files.createTempDirectory("rec-pending"));
        try {
            records.saveAsync(record("fresh", "BLACKJACK", "left", false, System.currentTimeMillis()));
            GameRecommendationService service = new GameRecommendationService(records);
            List<Map<String, Object>> rows = (List<Map<String, Object>>)
                    service.nextGames(null, Map.of(), 6).get("recommendations");
            Map<String, Object> blackjack = rows.stream()
                    .filter(row -> "BLACKJACK".equals(row.get("gameType"))).findFirst().orElseThrow();
            assertEquals(1L, blackjack.get("recentGlobalGames"),
                    "异步落盘前，刚结束的真人局也应参与推荐热度");
        } finally { records.shutdown(); }
    }
}
