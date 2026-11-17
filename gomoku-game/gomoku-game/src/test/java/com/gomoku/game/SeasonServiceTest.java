package com.gomoku.game;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 赛季段位测试：月窗口、赛季分、段位映射、AI 不计、榜单排序、不碰 ELO。 */
class SeasonServiceTest {

    private static GameRecordService newRecords() throws Exception {
        return new GameRecordService(Files.createTempDirectory("season-test"));
    }

    private static GameRecord game(String roomId, long endedAt, String identity, String result) {
        GameRecord rec = new GameRecord();
        rec.setRoomId(roomId);
        rec.setGameType("GOMOKU");
        rec.setStartedAt(endedAt - 1000);
        rec.setEndedAt(endedAt);
        rec.setMoveCount(10);
        GameRecord.Participant me = new GameRecord.Participant("p-" + roomId, "赛季玩家", 1, result, false, 1200, 1200, 0);
        me.setIdentityId(identity);
        rec.getParticipants().add(me);
        return rec;
    }

    private static GameRecord aiGame(String roomId, long endedAt, String identity) {
        GameRecord rec = game(roomId, endedAt, identity, "WIN");
        rec.getParticipants().get(0).setAi(true);
        return rec;
    }

    /** 固定「今天」为 2026-01-15，赛季窗口即 2026-01 */
    private static class FixedSeason extends SeasonService {
        FixedSeason(GameRecordService records) { super(records); }
        @Override protected LocalDate today() { return LocalDate.of(2026, 1, 15); }
    }

    private static long at(int year, int month, int day) {
        return LocalDate.of(year, month, day).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @Test
    void seasonWindowIsCalendarMonth() throws Exception {
        SeasonService season = new FixedSeason(newRecords());
        Map<String, Object> s = season.current("u1", 10);
        assertEquals("2026-01", s.get("id"));
        assertEquals("2026-01-01", s.get("startDate"));
        assertEquals("2026-01-31", s.get("endDate"));
        assertEquals(17, s.get("daysLeft"), "1/15 距 2/1（含当日）还有 17 天");
    }

    @Test
    void pointsCountOnlyCurrentMonth() throws Exception {
        GameRecordService records = newRecords();
        SeasonService season = new FixedSeason(records);
        // 本月 3 胜 + 1 平 = 3*3+1 = 10 分
        records.saveAsync(game("a", at(2026, 1, 5), "u1", "WIN"));
        records.saveAsync(game("b", at(2026, 1, 6), "u1", "WIN"));
        records.saveAsync(game("c", at(2026, 1, 7), "u1", "WIN"));
        records.saveAsync(game("d", at(2026, 1, 8), "u1", "DRAW"));
        // 上月与下月不应计入
        records.saveAsync(game("e", at(2025, 12, 30), "u1", "WIN"));
        records.saveAsync(game("f", at(2026, 2, 1), "u1", "WIN"));
        records.flushForTest();

        @SuppressWarnings("unchecked")
        Map<String, Object> me = (Map<String, Object>) season.current("u1", 10).get("me");
        assertEquals(3, me.get("wins"));
        assertEquals(1, me.get("draws"));
        assertEquals(10, me.get("points"));
    }

    @Test
    void aiGamesDoNotCount() throws Exception {
        GameRecordService records = newRecords();
        SeasonService season = new FixedSeason(records);
        records.saveAsync(aiGame("ai", at(2026, 1, 5), "u1"));
        records.flushForTest();
        @SuppressWarnings("unchecked")
        Map<String, Object> me = (Map<String, Object>) season.current("u1", 10).get("me");
        assertEquals(0, me.get("games"), "AI 局不能计入赛季分");
        assertEquals("青铜", me.get("tier"));
    }

    @Test
    void tierMapping() {
        assertEquals("青铜", SeasonService.tierFor(0).get("tier"));
        assertEquals("白银", SeasonService.tierFor(10).get("tier"));
        assertEquals("黄金", SeasonService.tierFor(25).get("tier"));
        assertEquals("铂金", SeasonService.tierFor(50).get("tier"));
        assertEquals("钻石", SeasonService.tierFor(90).get("tier"));
        assertEquals("王者", SeasonService.tierFor(150).get("tier"));
        assertEquals(true, SeasonService.tierFor(999).get("maxTier"));
        // 白银区间 10-24，12 分，下一段位下限为 25
        assertEquals("白银", SeasonService.tierFor(12).get("tier"));
        assertEquals(25, SeasonService.tierFor(12).get("nextTierFloor"));
    }

    @Test
    void leaderboardSortedByPointsThenWins() throws Exception {
        GameRecordService records = newRecords();
        SeasonService season = new FixedSeason(records);
        records.saveAsync(game("a", at(2026, 1, 5), "u1", "WIN"));
        records.saveAsync(game("b", at(2026, 1, 5), "u1", "WIN"));
        records.saveAsync(game("c", at(2026, 1, 5), "u2", "WIN"));
        records.flushForTest();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> board = (List<Map<String, Object>>) season.current("u1", 10).get("leaderboard");
        assertEquals(2, board.size());
        assertEquals(6, board.get(0).get("points"), "两胜者 6 分应排第一");
        assertEquals(3, board.get(1).get("points"), "一胜者 3 分应排第二");
        assertEquals("青铜", board.get(0).get("tier"), "6 分 < 10 仍为青铜");
    }

    @Test
    void seasonDoesNotTouchElo() throws Exception {
        GameRecordService records = newRecords();
        EloService elo = new EloService(
                Files.createTempDirectory("season-elo").resolve("elo.properties"),
                Files.createTempDirectory("season-elo-old").resolve("old.json"));
        SeasonService season = new FixedSeason(records);
        records.saveAsync(game("a", at(2026, 1, 5), "u1", "WIN"));
        records.flushForTest();
        int before = elo.getRating("u1", GameType.GOMOKU);
        season.current("u1", 10);
        assertEquals(before, elo.getRating("u1", GameType.GOMOKU), "赛季查询不能改动 ELO");
        assertEquals(1200, before);
    }

    /** 固定「今天」为 2026-02-05：上一赛季即 2026-01，用于结算测试 */
    private static class SettleSeason extends SeasonService {
        SettleSeason(GameRecordService records, java.nio.file.Path path) { super(records, path); }
        @Override protected LocalDate today() { return LocalDate.of(2026, 2, 5); }
    }

    @Test
    void monthChangeSettlesPreviousSeasonWithTiers() throws Exception {
        java.nio.file.Path path = Files.createTempFile("season-settle", ".json");
        Files.deleteIfExists(path);
        GameRecordService records = newRecords();
        // 上赛季（2026-01）：u1 四胜 12 分（白银）、u2 一平 1 分（青铜）、AI 不入结算
        records.saveAsync(game("s1", at(2026, 1, 5), "u1", "WIN"));
        records.saveAsync(game("s2", at(2026, 1, 6), "u1", "WIN"));
        records.saveAsync(game("s3", at(2026, 1, 7), "u1", "WIN"));
        records.saveAsync(game("s4", at(2026, 1, 8), "u1", "WIN"));
        records.saveAsync(game("s5", at(2026, 1, 9), "u2", "DRAW"));
        records.saveAsync(aiGame("s6", at(2026, 1, 10), "ai-ghost"));
        records.flushForTest();

        SeasonService season = new SettleSeason(records, path);
        season.current("u1", 10); // 查询触发结算

        List<Map<String, Object>> u1 = season.settlementHistory("u1");
        assertEquals(1, u1.size(), "只结算上一赛季");
        assertEquals("2026-01", u1.get(0).get("seasonId"));
        assertEquals(12, u1.get(0).get("points"));
        assertEquals("白银", u1.get(0).get("tier"));

        List<Map<String, Object>> u2 = season.settlementHistory("u2");
        assertEquals("青铜", u2.get(0).get("tier"));
        assertNull(season.settlementHistory("ai-ghost").isEmpty() ? null : null, "AI 不应获得称号");
        assertEquals(0, season.settlementHistory("ai-ghost").size());

        // 同月再次查询：幂等，不重复结算
        season.current("u1", 10);
        assertEquals(1, season.settlementHistory("u1").size());
    }

    @Test
    void settlementSurvivesRestartAndDoesNotResettle() throws Exception {
        java.nio.file.Path dir = Files.createTempDirectory("season-settle-disk");
        java.nio.file.Path path = dir.resolve("seasons.json");
        GameRecordService records = new GameRecordService(dir);
        records.saveAsync(game("r1", at(2026, 1, 20), "u1", "WIN"));
        records.flushForTest();

        SeasonService first = new SettleSeason(records, path);
        first.current("u1", 10);
        assertEquals(1, first.settlementHistory("u1").size());

        // 重启：读回结算文件，且不重复结算（same lastSettled marker）
        SeasonService second = new SettleSeason(records, path);
        second.init();
        second.current("u1", 10);
        assertEquals(1, second.settlementHistory("u1").size());
        assertEquals("2026-01", second.settlementHistory("u1").get(0).get("seasonId"));
    }

    @Test
    void tierOfReturnsCurrentSeasonTierOrNull() throws Exception {
        GameRecordService records = newRecords();
        SeasonService season = new SeasonService(records, Files.createTempFile("season-tier", ".json"));
        long now = System.currentTimeMillis();
        for (int i = 0; i < 4; i++) records.saveAsync(game("tier" + i, now - i * 1000L, "u-tier", "WIN"));
        records.flushForTest();

        assertEquals("白银", season.tierOf("u-tier"), "本月 4 胜 12 分应落在白银段");
        assertNull(season.tierOf(null));
        assertNull(season.tierOf("nobody"), "0 分玩家不给徽章");
    }
}
