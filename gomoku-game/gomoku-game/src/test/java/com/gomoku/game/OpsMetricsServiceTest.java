package com.gomoku.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 运营指标单测：日活、留存、玩法热度、时段分布与 AI 局排除。
 *
 * 用可控时钟 + 手工构造记录，避免依赖真实时间与真实对局，
 * 这样 D1/D7 这类按自然日聚合的断言才能稳定。
 */
class OpsMetricsServiceTest {
    @TempDir Path dir;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 固定「今天」 */
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);

    private GameRecordService records;
    private OpsMetricsService metrics;

    /** 每个用例结束前关掉写线程，否则异步落盘句柄会让 @TempDir 删除失败 */
    @org.junit.jupiter.api.AfterEach
    void shutdown() {
        if (records != null) records.shutdown();
    }

    /** 建一个可控时钟 + 固定时区的指标服务 */
    private OpsMetricsService newMetrics() {
        records = new GameRecordService(dir.resolve("games"));
        records.init();
        OpsMetricsService service = new OpsMetricsService(records) {
            @Override long now() {
                return TODAY.atTime(12, 0).atZone(ZONE).toInstant().toEpochMilli();
            }
            @Override ZoneId zone() { return ZONE; }
        };
        metrics = service;
        return service;
    }

    /** 造一局：day 当天，含给定真人身份；aiOnly 时只有 AI */
    private void record(LocalDate day, String gameType, boolean ranked, boolean aiOnly, String... identities) {
        GameRecord record = new GameRecord();
        record.setId("T-" + day + "-" + gameType + "-" + System.nanoTime());
        record.setGameType(gameType);
        record.setStartedAt(day.atTime(10, 0).atZone(ZONE).toInstant().toEpochMilli());
        record.setEndedAt(day.atTime(11, 0).atZone(ZONE).toInstant().toEpochMilli());
        record.setRanked(ranked);
        List<GameRecord.Participant> participants = new ArrayList<>();
        if (aiOnly) {
            GameRecord.Participant ai = new GameRecord.Participant();
            ai.setName("电脑");
            ai.setAi(true);
            participants.add(ai);
        }
        for (String id : identities) {
            GameRecord.Participant p = new GameRecord.Participant();
            p.setName("玩家" + id);
            p.setIdentityId(id);
            participants.add(p);
        }
        record.setParticipants(participants);
        records.saveAsync(record);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @Test
    void dailyActiveCountsDistinctRealPlayers() {
        OpsMetricsService service = newMetrics();
        // 同一天两局、同一批人 → 日活仍是 2
        record(TODAY, "GOMOKU", true, false, "a", "b");
        record(TODAY, "UNO", false, false, "a", "c");

        Map<String, Object> overview = service.overview(7);
        assertEquals(3, overview.get("todayActive"), "同一天多个对局的身份应去重");
        assertEquals(3, overview.get("activeUsers"));
        assertEquals(2, overview.get("totalGames"));
        assertEquals(1, overview.get("rankedGames"), "只有计分局计入 rankedGames");
    }

    @Test
    void aiOnlyGamesDoNotCountAsActivity() {
        OpsMetricsService service = newMetrics();
        record(TODAY, "GOMOKU", false, true); // 只有 AI
        Map<String, Object> overview = service.overview(7);
        assertEquals(0, overview.get("todayActive"), "人机局不能刷日活");
        assertEquals(0, overview.get("totalGames"));
    }

    @Test
    void trendFillsMissingDaysWithZero() {
        OpsMetricsService service = newMetrics();
        record(TODAY.minusDays(2), "GOMOKU", true, false, "a", "b");
        List<Map<String, Object>> trend = list(service.overview(5).get("trend"));
        assertEquals(5, trend.size(), "窗口内每天都要有一行");
        assertEquals(TODAY.minusDays(4).toString(), trend.get(0).get("date"));
        assertEquals(TODAY.toString(), trend.get(4).get("date"));
        assertEquals(0, trend.get(0).get("users"), "没有对局的日子补 0");
        assertEquals(0, trend.get(0).get("games"));
        assertEquals(2, trend.get(2).get("users"), "有对局的日子给出真实日活");
    }

    @Test
    void retentionComputesD1AndD7AgainstCohort() {
        OpsMetricsService service = newMetrics();
        // 14 天窗口的 cohort 是窗口首日（今天 -13 天），D1/D7 都还在窗口内
        LocalDate cohort = TODAY.minusDays(13);
        record(cohort, "GOMOKU", true, false, "a", "b", "c", "d");
        record(cohort.plusDays(1), "GOMOKU", true, false, "a", "b");       // D1 回来 2/4 = 50%
        record(cohort.plusDays(7), "GOMOKU", true, false, "a");           // D7 回来 1/4 = 25%

        @SuppressWarnings("unchecked")
        Map<String, Object> retention = (Map<String, Object>) service.overview(14).get("retention");
        assertEquals(cohort.toString(), retention.get("cohortDate"));
        assertEquals(4, retention.get("cohortSize"));
        assertEquals(50.0, retention.get("d1"));
        assertEquals(25.0, retention.get("d7"));
    }

    @Test
    void retentionIsNullWhenWindowTooShort() {
        OpsMetricsService service = newMetrics();
        record(TODAY, "GOMOKU", true, false, "a", "b");
        @SuppressWarnings("unchecked")
        Map<String, Object> retention = (Map<String, Object>) service.overview(1).get("retention");
        assertNull(retention.get("d1"), "首日就是今天时 D1 无意义，应为 null 而不是 0");
        assertNull(retention.get("d7"));
        assertEquals(2, retention.get("cohortSize"));
    }

    @Test
    void gameTypeRankingSortsByGamesWithShare() {
        OpsMetricsService service = newMetrics();
        record(TODAY, "GOMOKU", true, false, "a", "b");
        record(TODAY, "GOMOKU", true, false, "a", "c");
        record(TODAY, "UNO", false, false, "a", "b", "c", "d");

        List<Map<String, Object>> types = list(service.overview(3).get("gameTypes"));
        assertEquals("GOMOKU", types.get(0).get("gameType"), "局数多的玩法排在前");
        assertEquals(2, types.get(0).get("games"));
        assertEquals(66.7, types.get(0).get("share"), "占比按局数计算（2/3）");
        assertEquals("UNO", types.get(1).get("gameType"));
        assertEquals(4.0, types.get(1).get("avgPlayers"), "平均参与人数 4 人");
    }

    @Test
    void hourlyBucketsCoverWholeDay() {
        OpsMetricsService service = newMetrics();
        record(TODAY, "GOMOKU", true, false, "a", "b");
        List<Map<String, Object>> hourly = list(service.overview(1).get("hourly"));
        assertEquals(8, hourly.size(), "24 小时应压成 8 个时段");
        int total = 0;
        for (Map<String, Object> row : hourly) total += (Integer) row.get("games");
        assertEquals(1, total, "所有时段的局数之和应等于总局数");
        assertEquals(1, hourly.get(3).get("games"), "11:00 落在 09:00-11:59 时段");
    }

    @Test
    void windowIsClamped() {
        OpsMetricsService service = newMetrics();
        assertEquals(1, service.overview(0).get("windowDays"));
        assertEquals(90, service.overview(500).get("windowDays"));
    }

    @Test
    void definitionsAreExposed() {
        OpsMetricsService service = newMetrics();
        List<?> definitions = (List<?>) service.overview(7).get("definitions");
        assertFalse(definitions.isEmpty(), "口径说明要随数据一起下发，避免看板数字被误读");
    }

    @Test
    void recordsOutsideWindowAreIgnored() {
        OpsMetricsService service = newMetrics();
        record(TODAY.minusDays(30), "GOMOKU", true, false, "old1", "old2");
        record(TODAY, "GOMOKU", true, false, "a");
        Map<String, Object> overview = service.overview(7);
        assertEquals(1, overview.get("totalGames"), "窗口外的对局不计入");
        assertEquals(1, overview.get("activeUsers"));
    }

    @Test
    void nowIsStableForTesting() {
        // 防回归：确认测试用时钟真的生效，否则上述按日期断言会随真实时间漂移
        OpsMetricsService service = newMetrics();
        assertEquals(TODAY, Instant.ofEpochMilli(service.now()).atZone(ZONE).toLocalDate());
    }
}
