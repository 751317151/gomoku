package com.gomoku.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 运营公告单测：校验、有效期、上下线、容量上限与持久化。 */
class AnnouncementServiceTest {

    @TempDir Path dir;

    private static class FixedClockAnnouncements extends AnnouncementService {
        long clock = 1_700_000_000_000L;
        FixedClockAnnouncements(Path path) { super(path); }
        @Override long now() { return clock; }
        void advanceHours(long hours) { clock += hours * 3600_000L; }
    }

    private FixedClockAnnouncements newService() {
        FixedClockAnnouncements service = new FixedClockAnnouncements(dir.resolve("announcements.json"));
        service.init();
        return service;
    }

    @Test
    void createTrimsFieldsAndAppliesDefaults() {
        FixedClockAnnouncements service = newService();
        AnnouncementService.Announcement item = service.create(
                "  周末双倍经验  ", "  周六 20:00 起，所有对局经验翻倍  ", null, null);
        assertNotNull(item.id);
        assertTrue(item.id.startsWith("ANN-"));
        assertEquals("周末双倍经验", item.title);
        assertEquals("周六 20:00 起，所有对局经验翻倍", item.body);
        assertEquals("info", item.level, "未指定级别按普通处理");
        assertEquals(1_700_000_000_000L, item.createdAt);
        assertEquals(item.createdAt + 7 * 24 * 3600_000L, item.expiresAt, "默认有效期 7 天");
        assertTrue(item.active);
    }

    @Test
    void invalidInputIsRejectedWithoutSideEffects() {
        FixedClockAnnouncements service = newService();
        assertThrows(IllegalArgumentException.class, () -> service.create(" ", "内容", "info", "7d"));
        assertThrows(IllegalArgumentException.class, () -> service.create("标".repeat(41), "内容", "info", "7d"));
        assertThrows(IllegalArgumentException.class, () -> service.create("标题", "  ", "info", "7d"));
        assertThrows(IllegalArgumentException.class, () -> service.create("标题", "字".repeat(501), "info", "7d"));
        assertThrows(IllegalArgumentException.class, () -> service.create("标题", "内容", "urgent", "7d"));
        assertThrows(IllegalArgumentException.class, () -> service.create("标题", "内容", "info", "2h"));
        assertTrue(service.listAll().isEmpty(), "校验失败不应写入任何记录");
    }

    @Test
    void activeListIsNewestFirstCappedAndHidesOfflineOrExpired() {
        FixedClockAnnouncements service = newService();
        service.create("第一条", "内容", "info", "7d");
        service.advanceHours(1);
        AnnouncementService.Announcement second = service.create("第二条", "内容", "important", "7d");
        service.advanceHours(1);
        service.create("第三条", "内容", "maintenance", "24h");
        service.advanceHours(1);
        service.create("第四条", "内容", "info", "7d");

        List<AnnouncementService.Announcement> active = service.listActive();
        assertEquals(3, active.size(), "大厅最多展示 3 条");
        assertEquals("第四条", active.get(0).title, "最新的在前");
        assertEquals("第三条", active.get(1).title);
        assertEquals("第二条", active.get(2).title);

        assertNotNull(service.setActive(second.id, false));
        assertFalse(service.listActive().stream().anyMatch(a -> a.id.equals(second.id)), "下线后不再展示");

        service.advanceHours(24);
        assertFalse(service.listActive().stream().anyMatch(a -> a.title.equals("第三条")), "到期后自动隐藏");
        assertEquals(4, service.listAll().size(), "管理端仍能看到全部记录");
    }

    @Test
    void foreverAnnouncementNeverExpires() {
        FixedClockAnnouncements service = newService();
        service.create("长期公告", "内容", "important", "forever");
        service.advanceHours(24 * 365 * 5L);
        assertEquals(1, service.listActive().size());
    }

    @Test
    void keepsOnlyNewestHundredRecords() {
        FixedClockAnnouncements service = newService();
        for (int i = 0; i < 105; i++) {
            service.create("公告 " + i, "内容", "info", "7d");
            service.advanceHours(1);
        }
        List<AnnouncementService.Announcement> all = service.listAll();
        assertEquals(100, all.size());
        assertEquals("公告 104", all.get(0).title);
        assertEquals("公告 5", all.get(99).title, "超出上限时从最旧的开始丢弃");
    }

    @Test
    void stateSurvivesReloadAndDeletePersists() {
        Path file = dir.resolve("announcements.json");
        FixedClockAnnouncements first = new FixedClockAnnouncements(file);
        first.init();
        AnnouncementService.Announcement keep = first.create("保留", "内容", "important", "forever");
        AnnouncementService.Announcement drop = first.create("删除", "内容", "info", "7d");
        first.setActive(keep.id, false);
        assertTrue(Files.exists(file));

        FixedClockAnnouncements loaded = new FixedClockAnnouncements(file);
        loaded.init();
        List<AnnouncementService.Announcement> all = loaded.listAll();
        assertEquals(2, all.size());
        assertEquals("删除", all.get(0).title);
        assertTrue(all.get(0).active);
        assertFalse(all.get(1).active, "下线状态应持久化");

        assertTrue(loaded.delete(drop.id));
        assertFalse(loaded.delete("ANN-NOTFOUND"));
        FixedClockAnnouncements again = new FixedClockAnnouncements(file);
        again.init();
        assertEquals(1, again.listAll().size());
    }

    @Test
    void unknownIdToggleReturnsNull() {
        FixedClockAnnouncements service = newService();
        assertNull(service.setActive("ANN-NOTFOUND", true));
    }
}
