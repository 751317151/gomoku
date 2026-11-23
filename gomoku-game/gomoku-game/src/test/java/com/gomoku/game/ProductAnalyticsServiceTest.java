package com.gomoku.game;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 产品漏斗单测：去重、AI/真人拆分、等待分桶、失败率、留存边界与隐私最小化。
 * 用固定时钟和隔离目录，避免依赖机器时区与真实运行数据。
 */
class ProductAnalyticsServiceTest {

    @TempDir Path dir;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 21);

    private static class FixedClock extends ProductAnalyticsService {
        long clock = TODAY.atTime(12, 0).atZone(ZONE).toInstant().toEpochMilli();

        FixedClock(Path dir) { super(dir); }

        @Override long now() { return clock; }

        @Override ZoneId zone() { return ZONE; }

        void advanceDays(int days) { clock += days * 86_400_000L; }
    }

    private FixedClock service;

    private FixedClock newService() {
        FixedClock created = new FixedClock(dir.resolve("analytics"));
        created.init();
        service = created;
        return created;
    }

    @AfterEach
    void shutdown() {
        if (service != null) service.shutdown();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> stages(ProductAnalyticsService service) {
        return (List<Map<String, Object>>) service.overview(7).get("stages");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stage(List<Map<String, Object>> rows, String key) {
        for (Map<String, Object> row : rows) if (key.equals(row.get("key"))) return row;
        throw new AssertionError("缺少阶段 " + key);
    }

    @Test
    void funnelDeduplicatesUsersAndCountsEventsSeparately() {
        FixedClock s = newService();
        s.recordLobbyEnter("u1");
        s.recordLobbyEnter("u1"); // 刷新/重连不应抬高漏斗人数
        s.recordLobbyEnter("u2");
        s.recordModeSelect("u1");
        s.recordMatchStart("u1", "QUICK");
        s.recordGameStart("u1", "QUICK", 3200);
        s.recordGameFinish("u1", "QUICK");
        s.recordRematch("u1", "QUICK");

        List<Map<String, Object>> rows = stages(s);
        assertEquals(2, stage(rows, "lobbyEnter").get("users"));
        assertEquals(3, stage(rows, "lobbyEnter").get("events"), "重复进入计为事件，但用户去重");
        assertEquals(1, stage(rows, "matchStart").get("users"));
        assertEquals(1, stage(rows, "rematch").get("users"));
        assertEquals(50.0, stage(rows, "matchStart").get("conversion"), "1/2 进入大厅的人成功入座");
        assertEquals(50.0, stage(rows, "gameFinish").get("conversion"), "完局用户同样以进入大厅人数为分母");
    }

    @Test
    void aiAndHumanStagesAreSeparated() {
        FixedClock s = newService();
        s.recordLobbyEnter("human");
        s.recordMatchStart("human", "QUICK");
        s.recordGameStart("human", "QUICK", 900);
        s.recordGameFinish("human", "QUICK");

        s.recordLobbyEnter("ai-user");
        s.recordMatchStart("ai-user", "AI");
        s.recordGameStart("ai-user", "AI", 400);
        s.recordGameFinish("ai-user", "AI");

        Map<String, Object> result = s.overview(7);
        @SuppressWarnings("unchecked") List<Map<String, Object>> human =
                (List<Map<String, Object>>) result.get("humanStages");
        @SuppressWarnings("unchecked") List<Map<String, Object>> ai =
                (List<Map<String, Object>>) result.get("aiStages");
        assertEquals(1, stage(human, "gameFinish").get("users"));
        assertEquals(1, stage(ai, "gameFinish").get("users"));
        assertEquals(0, stage(ai, "lobbyEnter").get("users"), "AI 身份不进入真人漏斗");
    }

    @Test
    void waitTimeBucketsExcludeAiAndUseMedianSummaryShape() {
        FixedClock s = newService();
        s.recordLobbyEnter("a");
        s.recordMatchStart("a", "QUICK");
        s.recordGameStart("a", "QUICK", 250);
        s.recordMatchStart("b", "QUICK");
        s.recordGameStart("b", "QUICK", 1200);
        s.recordMatchStart("c", "QUICK");
        s.recordGameStart("c", "QUICK", 9000);
        s.recordMatchStart("d", "AI");
        s.recordGameStart("d", "AI", 60000);

        @SuppressWarnings("unchecked") Map<String, Object> wait =
                (Map<String, Object>) s.overview(7).get("wait");
        assertEquals(3L, wait.get("samples"), "人机等待不应污染匹配等待时长");
        assertEquals(3483L, wait.get("avgMs"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> buckets =
                (List<Map<String, Object>>) wait.get("buckets");
        assertEquals(6, buckets.size());
        assertEquals(1L, buckets.get(0).get("count"));
        assertEquals(1L, buckets.get(1).get("count"));
        assertEquals(1L, buckets.get(2).get("count"), "9 秒落在 5–15 秒桶");
    }

    @Test
    void matchFailureRateAndReconnectRateAreDerived() {
        FixedClock s = newService();
        s.recordLobbyEnter("a");
        s.recordMatchStart("a", "QUICK");
        s.recordMatchFail("ROOM_NOT_FOUND");
        s.recordMatchFail("ROOM_UNAVAILABLE");
        s.recordReconnect(true);
        s.recordReconnect(true);
        s.recordReconnect(false);

        Map<String, Object> result = s.overview(7);
        @SuppressWarnings("unchecked") Map<String, Object> fail =
                (Map<String, Object>) result.get("matchFail");
        assertEquals(2, fail.get("count"));
        assertEquals(3L, fail.get("attempts"));
        assertEquals(66.7, fail.get("rate"));
        @SuppressWarnings("unchecked") Map<String, Object> reconnect =
                (Map<String, Object>) result.get("reconnect");
        assertEquals(2, reconnect.get("success"));
        assertEquals(1, reconnect.get("failure"));
        assertEquals(66.7, reconnect.get("rate"));
    }

    @Test
    void gameModeRankingSortsByStartsAndKeepsFinishRate() {
        FixedClock s = newService();
        s.recordMatchStart("a", "QUICK");
        s.recordGameStart("a", "QUICK", 500);
        s.recordGameFinish("a", "QUICK");
        s.recordMatchStart("b", "QUICK");
        s.recordGameStart("b", "QUICK", 700);
        s.recordMatchStart("c", "AI");
        s.recordGameStart("c", "AI", 100);
        s.recordGameFinish("c", "AI");

        @SuppressWarnings("unchecked") List<Map<String, Object>> modes =
                (List<Map<String, Object>>) s.overview(7).get("gameModes");
        assertEquals("QUICK", modes.get(0).get("mode"), "开局次数多的模式在前");
        assertEquals(2, modes.get(0).get("starts"));
        assertEquals(1, modes.get(0).get("finishes"));
        assertEquals(50.0, modes.get(0).get("finishRate"));
        assertEquals("AI", modes.get(1).get("mode"));
    }

    @Test
    void identityIsHashedAndSaltIsPersistedWithoutRawId() throws Exception {
        FixedClock s = newService();
        s.recordLobbyEnter("raw-secret-identity");
        s.flush();

        Path salt = dir.resolve("analytics.salt");
        assertTrue(Files.exists(salt), "盐要单独持久化，保证重启后同一身份仍可去重");
        assertTrue(Files.readString(salt).trim().matches("[0-9a-f]{64}"));

        try (var files = Files.list(dir.resolve("analytics"))) {
            Path day = files.filter(p -> p.getFileName().toString().endsWith(".json")).findFirst().orElseThrow();
            String json = Files.readString(day);
            assertFalse(json.contains("raw-secret-identity"), "分析日志不能出现原始游客身份");
            assertTrue(json.matches("(?s).*\\\"[0-9a-f]{16}\\\".*"), "保存的是加盐哈希摘要");
        }

        FixedClock loaded = new FixedClock(dir.resolve("analytics"));
        loaded.init();
        try {
            loaded.recordGameFinish("raw-secret-identity", "QUICK");
            @SuppressWarnings("unchecked") List<Map<String, Object>> rows =
                    (List<Map<String, Object>>) loaded.overview(7).get("stages");
            assertEquals(1, stage(rows, "lobbyEnter").get("users"), "重启加载同一盐后仍按同一身份去重");
        } finally {
            loaded.shutdown();
        }
    }

    @Test
    void keepsOnlyRecentDaysAndToleratesCorruptFiles() throws Exception {
        FixedClock s = newService();
        s.recordLobbyEnter("old");
        s.flush();
        Files.writeString(dir.resolve("analytics").resolve("not-a-day.json"), "{broken");

        s.advanceDays(95);
        s.recordLobbyEnter("new");
        s.flush();

        assertTrue(s.retainedDays() <= 90, "内存只保留 90 天");
        try (var files = Files.list(dir.resolve("analytics"))) {
            long dayFiles = files.filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.json")).count();
            assertTrue(dayFiles <= 90, "磁盘按日文件也不能无限增长");
        }
        assertEquals(TODAY.plusDays(95).toString(), s.overview(1).get("to"));
    }

    @Test
    void unknownModeFallsBackToQuickAndNullIdentityIsIgnored() {
        FixedClock s = newService();
        assertEquals("QUICK", ProductAnalyticsService.normalizeMode("not-a-mode"));
        assertEquals("AI", ProductAnalyticsService.normalizeMode(" ai "));
        s.recordLobbyEnter(null);
        s.recordLobbyEnter("");
        @SuppressWarnings("unchecked") List<Map<String, Object>> rows =
                (List<Map<String, Object>>) s.overview(7).get("stages");
        assertEquals(0, stage(rows, "lobbyEnter").get("users"), "无持久身份的请求不制造访客");
    }

    @Test
    void emptyWindowStillReturnsFullShape() {
        FixedClock s = newService();
        Map<String, Object> result = s.overview(7);
        assertEquals(7, result.get("windowDays"));
        assertEquals(TODAY.toString(), result.get("to"));
        assertEquals(6, ((List<?>) result.get("stages")).size());
        assertEquals(7, ((List<?>) result.get("daily")).size(), "无事件也要按天补零");
        assertFalse(((List<?>) result.get("definitions")).isEmpty());
    }

    @Test
    void flushWritesEveryDirtyDayAcrossMidnight() throws Exception {
        FixedClock s = newService();
        s.recordLobbyEnter("before-midnight");
        s.clock = TODAY.plusDays(1).atTime(0, 0, 1).atZone(ZONE).toInstant().toEpochMilli();
        s.recordLobbyEnter("after-midnight");
        s.flush();

        assertTrue(Files.exists(dir.resolve("analytics").resolve(TODAY + ".json")));
        assertTrue(Files.exists(dir.resolve("analytics").resolve(TODAY.plusDays(1) + ".json")));
    }

    @Test
    void oldWaitBucketWithoutHistogramDoesNotCrashOverview() throws Exception {
        Files.createDirectories(dir.resolve("analytics"));
        Files.writeString(dir.resolve("analytics").resolve(TODAY + ".json"),
                "{\"date\":\"" + TODAY + "\",\"wait\":{\"QUICK\":{\"count\":1,\"totalMs\":900}}}");

        FixedClock s = newService();
        @SuppressWarnings("unchecked") Map<String, Object> wait =
                (Map<String, Object>) s.overview(1).get("wait");
        assertEquals(1L, wait.get("samples"));
        assertEquals(6, ((List<?>) wait.get("buckets")).size());
    }

    @Test
    void overviewStaysConsistentWhileEventsAreRecordedConcurrently() throws Exception {
        FixedClock s = newService();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int writer = 0; writer < 2; writer++) {
                final int id = writer;
                tasks.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 500; i++) {
                        String user = "user-" + id + "-" + (i % 20);
                        s.recordLobbyEnter(user);
                        s.recordMatchStart(user, "QUICK");
                        s.recordGameStart(user, "QUICK", i);
                        s.recordGameFinish(user, "QUICK");
                    }
                    return null;
                }));
            }
            // 运营看板与对局事件并发读取；旧实现会在集合扩容时抛 ConcurrentModificationException。
            tasks.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 300; i++) {
                    Map<String, Object> result = s.overview(7);
                    assertEquals(6, ((List<?>) result.get("stages")).size());
                    assertEquals(7, ((List<?>) result.get("daily")).size());
                }
                return null;
            }));
            start.countDown();
            for (Future<?> task : tasks) task.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        List<Map<String, Object>> rows = stages(s);
        assertEquals(40, stage(rows, "gameFinish").get("users"), "两个写入线程共 40 个去重身份");
        assertEquals(1000, stage(rows, "gameFinish").get("events"));
    }

}
