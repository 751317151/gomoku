package com.gomoku.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 运营治理单测：举报去重、处置生效/到期、撤销、持久化、管理视图。 */
class ModerationServiceTest {
    @TempDir Path dir;

    /** 可控时钟的治理服务：模拟到期而不真的等 */
    private static class FixedClockModeration extends ModerationService {
        long clock = 1_700_000_000_000L;
        FixedClockModeration(Path path) { super(path); }
        @Override long now() { return clock; }
        void advanceHours(long hours) { clock += hours * 3600_000L; }
    }

    private FixedClockModeration newService() {
        FixedClockModeration service = new FixedClockModeration(dir.resolve("moderation.json"));
        service.init();
        return service;
    }

    @Test
    void reportIsStoredWithEvidence() {
        FixedClockModeration service = newService();
        ModerationService.Report report = service.report("id-a", "甲", "id-b", "乙",
                "ROOM-ABC123", "GOMOKU", "abuse", "一直骂人");
        assertNotNull(report);
        assertTrue(report.id.startsWith("REP-"));
        assertEquals("id-a", report.reporterId);
        assertEquals("id-b", report.targetId);
        assertEquals("abuse", report.reason);
        assertEquals("ROOM-ABC123", report.roomId);
        assertFalse(report.handled, "新举报默认未处置");
    }

    @Test
    void duplicateReportWithinWindowIsDropped() {
        FixedClockModeration service = newService();
        assertNotNull(service.report("id-a", "甲", "id-b", "乙", null, null, "abuse", null));
        assertNull(service.report("id-a", "甲", "id-b", "乙", null, null, "abuse", null),
                "同一举报人 10 分钟内重复举报应被去重");
        service.advanceHours(1);
        assertNotNull(service.report("id-a", "甲", "id-b", "乙", null, null, "abuse", null),
                "过了去重窗口应允许再举报");
    }

    @Test
    void cannotReportSelfAndUnknownReasonFallsBackToOther() {
        FixedClockModeration service = newService();
        assertThrows(IllegalArgumentException.class,
                () -> service.report("id-a", "甲", "id-a", "甲", null, null, "abuse", null));
        ModerationService.Report report = service.report("id-a", "甲", "id-b", "乙",
                null, null, "nonsense", null);
        assertEquals("other", report.reason, "未知原因码应归入 other");
    }

    @Test
    void banBlocksAndExpires() {
        FixedClockModeration service = newService();
        service.sanction("id-b", "ban", "1h", "辱骂", "admin");
        assertTrue(service.isBanned("id-b"));
        assertFalse(service.isMuted("id-b"));
        assertEquals(3600, service.remainingSeconds("id-b"), "1 小时封禁剩余 3600 秒");

        service.advanceHours(1);
        assertFalse(service.isBanned("id-b"), "到期后自动失效");
        assertEquals(0, service.remainingSeconds("id-b"));
        assertNotNull(service.activeSanction("id-b") == null ? "ok" : null, "过期处置不应再算生效");
    }

    @Test
    void muteBlocksChatButNotBan() {
        FixedClockModeration service = newService();
        service.sanction("id-c", "mute", "24h", "刷屏", "admin");
        assertTrue(service.isMuted("id-c"));
        assertFalse(service.isBanned("id-c"), "禁言不等于封禁，仍可游玩");
    }

    @Test
    void foreverSanctionHasNoExpiry() {
        FixedClockModeration service = newService();
        service.sanction("id-d", "ban", "forever", "严重违规", "admin");
        assertEquals(-1, service.remainingSeconds("id-d"), "永久封禁剩余时间用 -1 表示");
        service.advanceHours(24 * 365);
        assertTrue(service.isBanned("id-d"), "永久封禁不会自然过期");
    }

    @Test
    void unsupportedDurationRejected() {
        FixedClockModeration service = newService();
        assertThrows(IllegalArgumentException.class,
                () -> service.sanction("id-e", "ban", "15m", null, "admin"));
    }

    @Test
    void liftRemovesSanction() {
        FixedClockModeration service = newService();
        service.sanction("id-f", "ban", "7d", null, "admin");
        assertTrue(service.isBanned("id-f"));
        assertTrue(service.lift("id-f"));
        assertFalse(service.isBanned("id-f"));
        assertFalse(service.lift("id-f"), "重复撤销返回 false");
    }

    @Test
    void sanctionsAndReportsSurviveReload() {
        FixedClockModeration service = newService();
        service.report("id-a", "甲", "id-b", "乙", "ROOM-1", "GOMOKU", "spam", null);
        service.sanction("id-b", "ban", "24h", "刷屏", "admin");
        assertTrue(Files.exists(dir.resolve("moderation.json")));

        FixedClockModeration reloaded = new FixedClockModeration(dir.resolve("moderation.json"));
        reloaded.init();
        assertEquals(1, reloaded.reports(false, 0).size(), "举报应持久化");
        assertTrue(reloaded.isBanned("id-b"), "处置应持久化");
    }

    @Test
    void markHandledAndFilterPending() {
        FixedClockModeration service = newService();
        ModerationService.Report first = service.report("id-a", "甲", "id-b", "乙", null, null, "abuse", null);
        service.report("id-c", "丙", "id-d", "丁", null, null, "spam", null);
        assertEquals(2, service.reports(true, 0).size(), "两条都未处置");

        assertTrue(service.markHandled(first.id, "ban/24h"));
        List<ModerationService.Report> pending = service.reports(true, 0);
        assertEquals(1, pending.size(), "已处置的举报应从待处理列表移除");
        ModerationService.Report handled = service.reports(false, 0).stream()
                .filter(r -> r.id.equals(first.id)).findFirst().orElseThrow(AssertionError::new);
        assertTrue(handled.handled);
        assertEquals("ban/24h", handled.handledNote);
        assertFalse(service.markHandled("REP-NOTEXIST", null));
    }

    @Test
    void reportQueueIsCapped() {
        FixedClockModeration service = newService();
        for (int i = 0; i < ModerationService.MAX_REPORTS + 20; i++) {
            service.advanceHours(1); // 避开去重窗口（也保证每条都不同举报人）
            service.report("reporter-" + i, "举报人" + i, "target", "目标", null, null, "abuse", null);
        }
        assertEquals(ModerationService.MAX_REPORTS, service.reports(false, 0).size(),
                "举报队列应有上限，防止文件无限增长");
    }

    @Test
    void reportListIsNewestFirstAndLimited() {
        FixedClockModeration service = newService();
        for (int i = 0; i < 5; i++) {
            service.advanceHours(1);
            service.report("reporter-" + i, "丙" + i, "target", "目标", null, null, "abuse", null);
        }
        List<ModerationService.Report> top2 = service.reports(false, 2);
        assertEquals(2, top2.size());
        assertEquals("reporter-4", top2.get(0).reporterId, "最新的举报应排在最前");
    }
}
