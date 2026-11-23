package com.gomoku.game;

import com.google.gson.Gson;
import com.gomoku.server.GameRecordHealthIndicator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** 资产越过大厅窗口、旧日志迁移/重建与异步故障恢复，均使用真实 SQLite 文件验证。 */
class GameArchiveTest {
    @TempDir Path dir;
    private final List<GameRecordService> opened = new ArrayList<>();
    private static final Gson GSON = new Gson();

    @AfterEach
    void closeServices() {
        opened.forEach(GameRecordService::shutdown);
    }

    private GameRecordService open() {
        GameRecordService records = new GameRecordService(dir);
        opened.add(records);
        records.init();
        return records;
    }

    private static GameRecord game(String id, String identity, String name, String type, String result, long endedAt) {
        GameRecord record = new GameRecord();
        record.setId(id); record.setGameType(type); record.setRanked(true);
        record.setStartedAt(endedAt - 60000); record.setEndedAt(endedAt); record.setMoveCount(7);
        GameRecord.Participant me = new GameRecord.Participant("p-" + id, name, 1, result, false, 1200, 1216, 16);
        me.setIdentityId(identity);
        GameRecord.Participant other = new GameRecord.Participant("o-" + id, "对手", 2, "LOSE", false, 1200, 1184, -16);
        other.setIdentityId("guest-other");
        record.getParticipants().add(me); record.getParticipants().add(other);
        return record;
    }

    private long todayAtNoon() {
        return LocalDate.now().atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private void log(List<GameRecord> records) throws Exception {
        StringBuilder data = new StringBuilder();
        for (GameRecord record : records) data.append(GSON.toJson(record)).append('\n');
        Files.writeString(dir.resolve("index.jsonl"), data.toString(), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private boolean unlocked(Map<String, Object> progress, String id) {
        return ((List<Map<String, Object>>) progress.get("achievements")).stream()
                .anyMatch(a -> id.equals(a.get("id")) && Boolean.TRUE.equals(a.get("unlocked")));
    }

    @Test
    void legacyHistoryBeyondFiveThousandPreservesLifetimeAssetsAndWindowMetrics() throws Exception {
        long noon = todayAtNoon();
        List<GameRecord> legacy = new ArrayList<>();
        GameRecord first = game("LEGACY-1", "veteran", "老玩家", "GOMOKU", "WIN", noon);
        first.setEventXpPercent(50); first.setEventId("EVENT-ENDED");
        legacy.add(first);
        GameRecord second = game("LEGACY-2", "veteran", "老玩家", "GOMOKU", "WIN", noon + 1);
        second.getParticipants().get(0).setEloAfter(1232);
        legacy.add(second);
        GameRecord third = game("LEGACY-3", "veteran", "老玩家", "UNO", "WIN", noon + 2);
        third.setRanked(false);
        GameRecord.Participant teammate = new GameRecord.Participant("third", "第三人", 3, "LOSE", false, 1200, 1200, 0);
        teammate.setIdentityId("guest-third"); third.getParticipants().add(teammate);
        legacy.add(third);
        for (int i = 0; i < 5002; i++) {
            legacy.add(game("FILLER-" + i, "filler", "新玩家", "REVERSI", "LOSE", noon + 3 + i));
        }
        log(legacy);
        byte[] originalLog = Files.readAllBytes(dir.resolve("index.jsonl"));
        GameRecordService records = open();

        assertEquals(5000, records.getRecordCount(), "大厅缓存继续有界");
        assertTrue(records.getRecentGames(5000).stream().noneMatch(record -> record.getId().startsWith("LEGACY")));
        Map<String, Object> progress = new PlayerProgressService(records).getProgress("veteran");
        assertEquals(248, progress.get("xp"), "113 活动首胜 + 50 连胜 + 85 多人首胜");
        assertEquals(3, progress.get("wins")); assertEquals(3, progress.get("winStreak"));
        assertEquals(2, progress.get("gameTypes")); assertEquals(1, progress.get("multiplayerGames"));
        assertTrue(unlocked(progress, "streak-3")); assertTrue(unlocked(progress, "mode-win-uno"));
        assertEquals(3, records.getIdentityStats("veteran", null).get("total"));
        assertEquals(1232, records.getIdentityStats("veteran", "GOMOKU").get("elo"));
        assertEquals(2, records.getPlayerStats("老玩家", "GOMOKU").get("total"));
        assertEquals("老玩家", records.getLastKnownName("veteran"));
        assertEquals(3, records.getIdentityDailyProgress("veteran").get("todayGames"));
        assertEquals(9, records.getSeasonStats("veteran", noon, noon + 100000).get("points"));
        assertEquals(5005, new OpsMetricsService(records).overview(1).get("totalGames"));
        assertTrue(records.getWeeklyActivityLeaderboard(10).stream().anyMatch(row -> "老玩家".equals(row.get("name"))));
        assertArrayEquals(originalLog, Files.readAllBytes(dir.resolve("index.jsonl")), "迁移不得改写旧日志");

        records.saveAsync(game("RETURN-1", "veteran", "回归玩家", "GOMOKU", "LOSE", noon + 6000));
        assertEquals(268, new PlayerProgressService(records).getProgress("veteran").get("xp"));
        records.flushForTest();
        Map<String, Object> expected = new PlayerProgressService(records).getProgress("veteran", 75);
        GameRecordService restarted = open();
        assertEquals(expected, new PlayerProgressService(restarted).getProgress("veteran", 75));
        assertEquals("RETURN-1", restarted.getRecentGames(1).get(0).getId());
        assertEquals("回归玩家", restarted.getLastKnownName("veteran"));
        assertEquals(5000, restarted.getRecordCount());
        assertEquals(4, restarted.getIdentityStats("veteran", null).get("total"));
    }

    @Test
    void duplicateLegacyLinesAndRepeatedStartupDoNotReawardOrOverwriteFirstRecord() throws Exception {
        GameRecord first = game("SAME-ID", "a", "甲", "GOMOKU", "WIN", todayAtNoon());
        GameRecord duplicate = game("SAME-ID", "a", "甲", "GOMOKU", "LOSE", todayAtNoon());
        log(List.of(first, first, duplicate));
        GameRecordService records = open(); records.init();
        assertEquals(1, records.getRecordCount());
        assertEquals(75, new PlayerProgressService(records).getProgress("a").get("xp"));
        assertEquals(75, new PlayerProgressService(open()).getProgress("a").get("xp"));
        records.saveAsync(duplicate); records.flushForTest();
        assertEquals(75, new PlayerProgressService(records).getProgress("a").get("xp"));
        assertEquals(3, Files.readAllLines(dir.resolve("index.jsonl")).size(), "重复保存不应再次追加日志");
    }

    @Test
    void pendingAndCommittedCopiesAreCountedOnceAndCallerCannotMutateAcceptedRecord() {
        GameRecordService records = open();
        GameRecord record = game("SNAPSHOT", "a", "原名", "GOMOKU", "WIN", todayAtNoon());
        record.setMoveHistory(new ArrayList<>(List.of(new int[] {7, 7, 1})));
        records.saveAsync(record);
        record.getParticipants().get(0).setResult("LOSE"); record.getParticipants().get(0).setName("篡改");
        record.getMoveHistory().get(0)[0] = 99;
        record.setEventXpPercent(200);
        records.getRecentGames(1).get(0).getParticipants().get(0).setResult("LOSE");
        assertEquals(75, new PlayerProgressService(records).getProgress("a").get("xp"));
        records.flushForTest();
        assertEquals(1, records.getIdentityStats("a", null).get("total"));
        GameRecord saved = records.getRecord("SNAPSHOT");
        assertEquals("WIN", saved.getParticipants().get(0).getResult());
        assertEquals("原名", saved.getParticipants().get(0).getName());
        assertEquals(7, saved.getMoveHistory().get(0)[0]);
        assertEquals(0, saved.getEventXpPercent());
    }

    @Test
    void restartKeepsChronologyEventFirstGameBonusStreakAndLatestElo() {
        GameRecordService records = open();
        long noon = todayAtNoon();
        GameRecord first = game("ORDER-1", "a", "旧名", "GOMOKU", "WIN", noon);
        first.setEventXpPercent(50);
        GameRecord second = game("ORDER-2", "a", "新名", "GOMOKU", "LOSE", noon + 1);
        second.getParticipants().get(0).setEloAfter(1200);
        records.saveAsync(first); records.saveAsync(second); records.flushForTest();
        Map<String, Object> progress = new PlayerProgressService(records).getProgress("a");
        assertEquals(133, progress.get("xp"));
        GameRecordService restarted = open();
        assertEquals(progress, new PlayerProgressService(restarted).getProgress("a"));
        assertEquals("ORDER-2", restarted.getRecentGames(1).get(0).getId());
        assertEquals(1200, restarted.getIdentityStats("a", "GOMOKU").get("elo"));
        assertEquals("新名", restarted.getLastKnownName("a"));
    }

    @Test
    void archiveCanBeRebuiltFromUnchangedLegacyLog() throws Exception {
        GameRecordService records = open();
        records.saveAsync(game("REBUILD", "a", "甲", "UNO", "WIN", todayAtNoon()));
        records.flushForTest();
        Map<String, Object> expected = new PlayerProgressService(records).getProgress("a");
        records.shutdown();
        for (String suffix : List.of("", "-wal", "-shm")) Files.deleteIfExists(dir.resolve("archive.sqlite" + suffix));
        GameRecordService rebuilt = open();
        assertEquals(expected, new PlayerProgressService(rebuilt).getProgress("a"));
        assertNotNull(rebuilt.getRecord("REBUILD"));
    }

    @Test
    void rebuildUsesJournalOffsetAndOnlyScansNewAppends() throws Exception {
        GameRecordService svc = open();
        svc.saveAsync(game("OFFSET-1", "a", "甲", "GOMOKU", "WIN", todayAtNoon()));
        svc.flushForTest();
        long journaledBytes = Files.size(dir.resolve("index.jsonl"));
        assertEquals(journaledBytes, new GameArchive(dir).importedOffsetForTest(),
                "索引事务提交后应把日志字节偏移持久化");
        svc.shutdown();
        for (String suffix : List.of("", "-wal", "-shm")) Files.deleteIfExists(dir.resolve("archive.sqlite" + suffix));
        GameRecordService rebuilt = open();
        assertEquals(75, new PlayerProgressService(rebuilt).getProgress("a").get("xp"));
        rebuilt.saveAsync(game("OFFSET-2", "a", "甲", "GOMOKU", "LOSE", todayAtNoon() + 1));
        rebuilt.flushForTest();
        GameRecordService restarted = open();
        assertEquals(List.of("OFFSET-1", "OFFSET-2"), restarted.getIdentityRecords("a").stream()
                .map(GameRecord::getId).sorted().collect(java.util.stream.Collectors.toList()));
        assertEquals(95, new PlayerProgressService(restarted).getProgress("a").get("xp"));
        assertEquals(2, restarted.getRecordCount());
    }

    @Test
    void replacedJournalIsReplayedFromStartInsteadOfTrustingAStaleOffset() throws Exception {
        GameRecordService svc = open();
        svc.saveAsync(game("OLD-LOG-1", "a", "甲", "GOMOKU", "WIN", todayAtNoon()));
        svc.flushForTest();
        svc.shutdown();
        // 备份恢复/人工轮转可能用另一份日志替换同名文件：旧偏移仍可能小于新文件长度，
        // 若直接按字节跳读，会从新日志中间开始并悄悄丢掉开头对局。
        log(List.of(game("NEW-LOG-1", "a", "甲", "GOMOKU", "WIN", todayAtNoon() + 1),
                game("NEW-LOG-2", "a", "甲", "GOMOKU", "LOSE", todayAtNoon() + 2)));
        GameRecordService restarted = open();
        List<String> ids = restarted.getIdentityRecords("a").stream()
                .map(GameRecord::getId).collect(java.util.stream.Collectors.toList());
        assertTrue(ids.containsAll(List.of("OLD-LOG-1", "NEW-LOG-1", "NEW-LOG-2")),
                "替换后的日志必须从头重放，旧索引记录也不能丢: " + ids);
        assertEquals(145, new PlayerProgressService(restarted).getProgress("a").get("xp"));
    }

    @Test
    void truncatedJournalIsReplayedFromStartToRecoverRemainingLines() throws Exception {
        GameRecordService svc = open();
        svc.saveAsync(game("TRUNCATE-1", "a", "甲", "GOMOKU", "WIN", todayAtNoon()));
        svc.saveAsync(game("TRUNCATE-2", "a", "甲", "GOMOKU", "WIN", todayAtNoon() + 1));
        svc.flushForTest();
        svc.shutdown();
        List<String> lines = Files.readAllLines(dir.resolve("index.jsonl"));
        Files.write(dir.resolve("index.jsonl"), (lines.get(0) + "\n").getBytes(StandardCharsets.UTF_8));
        GameRecordService restarted = open();
        // 日志被截断只说明「可重放来源少了行」，已提交的索引不能被反向删掉玩家资产。
        assertEquals(2, restarted.getRecordCount());
        assertEquals(125, new PlayerProgressService(restarted).getProgress("a").get("xp"));
        assertNotNull(restarted.getIdentityStats("a", null).get("total"));
    }

    @Test
    void damagedPartialTailDoesNotSwallowNextValidGame() throws Exception {
        Files.writeString(dir.resolve("index.jsonl"), "{\"id\":\"BROKEN");
        GameRecordService records = open();
        records.saveAsync(game("AFTER-TAIL", "a", "甲", "GOMOKU", "WIN", todayAtNoon()));
        records.flushForTest();
        GameRecordService restarted = open();
        assertEquals(1, restarted.getRecordCount());
        assertEquals(75, new PlayerProgressService(restarted).getProgress("a").get("xp"));
        assertEquals(2, Files.readAllLines(dir.resolve("index.jsonl")).size());
    }

    @Test
    void identityIsolationAndAiFlagsSurviveMigration() throws Exception {
        GameRecord record = game("IDENTITY", "owner", "同名", "UNO", "WIN", todayAtNoon());
        record.setRanked(false);
        record.getParticipants().get(1).setIdentityId("ai-id"); record.getParticipants().get(1).setAi(true);
        log(List.of(record));
        GameRecordService records = open();
        assertEquals(75, new PlayerProgressService(records).getProgress("owner").get("xp"));
        assertEquals(0, new PlayerProgressService(records).getProgress("impostor").get("xp"));
        assertEquals(0, new PlayerProgressService(records).getProgress("ai-id").get("xp"));
        assertEquals(0, records.getIdentityStats("owner", null).get("elo"));
        assertEquals(0, records.getPlayerStats("同名", "UNO").get("total"));
    }

    @Test
    void ioFailureIsVisibleThenRetriesInOrderWithoutDoubleCounting() throws Exception {
        GameRecordService records = open();
        GameRecordHealthIndicator health = new GameRecordHealthIndicator(records);
        Files.createDirectory(dir.resolve("index.jsonl")); // 真实不可写的日志路径，不模拟 DAO。
        records.saveAsync(game("RETRY-1", "a", "甲", "GOMOKU", "WIN", todayAtNoon()));
        records.saveAsync(game("RETRY-2", "a", "甲", "GOMOKU", "WIN", todayAtNoon() + 1));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (((Number) records.getStorageStatus().get("failedRecords")).longValue() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(Status.DOWN, health.health().getStatus());
        assertEquals(125, new PlayerProgressService(records).getProgress("a").get("xp"));
        Files.delete(dir.resolve("index.jsonl"));
        records.flushForTest();
        assertEquals(Status.UP, health.health().getStatus());
        assertEquals(2, Files.readAllLines(dir.resolve("index.jsonl")).size());
        assertEquals(List.of("RETRY-2", "RETRY-1"), records.getIdentityRecords("a").stream()
                .map(GameRecord::getId).collect(java.util.stream.Collectors.toList()));
        assertEquals(125, new PlayerProgressService(open()).getProgress("a").get("xp"));
    }

    @Test
    void uniqueIdIsIdempotentUnderConcurrentSaves() throws Exception {
        GameRecordService records = open();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> calls = new ArrayList<>();
            for (int i = 0; i < 12; i++) calls.add(() -> {
                records.saveAsync(game("CONCURRENT", "a", "甲", "GOMOKU", "WIN", todayAtNoon())); return null;
            });
            for (Future<Void> call : pool.invokeAll(calls)) call.get(10, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        records.flushForTest();
        assertEquals(1, records.getRecordCount());
        assertEquals(75, new PlayerProgressService(records).getProgress("a").get("xp"));
        assertEquals(1, Files.readAllLines(dir.resolve("index.jsonl")).size());
    }

    @Test
    void databaseWriteFailureRetriesAfterJournalWithoutAppendingTheGameTwice() throws Exception {
        GameRecordService records = open();
        GameRecordHealthIndicator health = new GameRecordHealthIndicator(records);
        try (Connection lock = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("archive.sqlite"));
             Statement statement = lock.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            try {
                records.saveAsync(game("DB-RETRY", "a", "甲", "GOMOKU", "WIN", todayAtNoon()));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (((Number) records.getStorageStatus().get("failedRecords")).longValue() == 0 && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals(Status.DOWN, health.health().getStatus());
                assertEquals(1, Files.readAllLines(dir.resolve("index.jsonl")).size(), "日志已先于索引提交持久化");
            } finally { statement.execute("ROLLBACK"); }
        }
        records.flushForTest();
        assertEquals(Status.UP, health.health().getStatus());
        assertEquals(1, Files.readAllLines(dir.resolve("index.jsonl")).size(), "索引失败重试不能再次追加同一摘要");
        assertEquals(75, new PlayerProgressService(open()).getProgress("a").get("xp"));
    }

    @Test
    void invalidRecordCannotEscapeArchiveDirectoryOrPartiallyAwardProgress() {
        GameRecordService records = open();
        GameRecord invalid = game("../outside", "a", "甲", "GOMOKU", "WIN", todayAtNoon());
        assertThrows(IllegalArgumentException.class, () -> records.saveAsync(invalid));
        invalid.setId("NULL-PARTICIPANTS"); invalid.setParticipants(null);
        assertThrows(IllegalArgumentException.class, () -> records.saveAsync(invalid));
        assertEquals(0, records.getRecordCount());
        assertEquals(0, new PlayerProgressService(records).getProgress("a").get("xp"));
    }

    @Test
    void newerSchemaAndCorruptDatabaseFailStartupInsteadOfResettingPlayerAssets() throws Exception {
        GameRecordService records = open(); records.shutdown();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("archive.sqlite"));
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version=2");
        }
        assertThrows(IllegalStateException.class, () -> new GameArchive(dir));
        Files.writeString(dir.resolve("archive.sqlite"), "not a database");
        assertThrows(IllegalStateException.class, () -> new GameArchive(dir));
        assertEquals("not a database", Files.readString(dir.resolve("archive.sqlite")));
    }
}
