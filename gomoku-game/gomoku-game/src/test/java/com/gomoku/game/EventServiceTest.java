package com.gomoku.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 限时活动单测：校验、生效窗口、玩法匹配、最高加成、持久化与容量上限。 */
class EventServiceTest {

    @TempDir Path dir;

    private static class FixedClockEvents extends EventService {
        long clock = 1_700_000_000_000L;
        FixedClockEvents(Path path) { super(path); }
        @Override long now() { return clock; }
        void advanceHours(long hours) { clock += hours * 3600_000L; }
    }

    private FixedClockEvents newService() {
        FixedClockEvents service = new FixedClockEvents(dir.resolve("events.json"));
        service.init();
        return service;
    }

    @Test
    void createTrimsFieldsAndAppliesDefaults() {
        FixedClockEvents service = newService();
        EventService.Event item = service.create("  周末双倍经验  ", null, 50, null);
        assertNotNull(item.id);
        assertTrue(item.id.startsWith("EVT-"));
        assertEquals("周末双倍经验", item.title);
        assertEquals("ALL", item.gameType, "未指定玩法默认覆盖全部");
        assertEquals(50, item.xpPercent);
        assertEquals(1_700_000_000_000L, item.startsAt);
        assertEquals(item.startsAt + 7 * 24 * 3600_000L, item.endsAt, "默认时长 7 天");
        assertTrue(item.active);
    }

    @Test
    void invalidInputIsRejectedWithoutSideEffects() {
        FixedClockEvents service = newService();
        assertThrows(IllegalArgumentException.class, () -> service.create(" ", "ALL", 50, "7d"));
        assertThrows(IllegalArgumentException.class, () -> service.create("标".repeat(41), "ALL", 50, "7d"));
        assertThrows(IllegalArgumentException.class, () -> service.create("标题", "CHESS", 50, "7d"));
        assertThrows(IllegalArgumentException.class, () -> service.create("标题", "ALL", 5, "7d"));
        assertThrows(IllegalArgumentException.class, () -> service.create("标题", "ALL", 250, "7d"));
        assertThrows(IllegalArgumentException.class, () -> service.create("标题", "ALL", 50, "2h"));
        assertTrue(service.listAll().isEmpty(), "校验失败不应写入任何记录");
    }

    @Test
    void activeForMatchesGameTypeAndPrefersHighestPercent() {
        FixedClockEvents service = newService();
        service.create("五子棋小活动", "GOMOKU", 25, "7d");
        service.advanceHours(1);
        EventService.Event all = service.create("全玩法大活动", "ALL", 100, "7d");

        EventService.Event gomoku = service.activeFor(GameType.GOMOKU, service.clock);
        assertNotNull(gomoku);
        assertEquals("全玩法大活动", gomoku.title, "多个活动命中时取加成最高的");
        assertEquals(100, gomoku.xpPercent);
        EventService.Event reversi = service.activeFor(GameType.REVERSI, service.clock);
        assertNotNull(reversi, "全玩法活动覆盖未单独配置的玩法");
        assertEquals("全玩法大活动", reversi.title);

        service.setActive(all.id, false);
        EventService.Event onlyGomoku = service.activeFor(GameType.GOMOKU, service.clock);
        assertEquals("五子棋小活动", onlyGomoku.title);
        assertEquals(25, onlyGomoku.xpPercent);
        assertNull(service.activeFor(GameType.REVERSI, service.clock), "限定玩法的活动不应惠及其他玩法");
    }

    @Test
    void activeForIgnoresPausedExpiredAndChronologicallyInactiveEvents() {
        FixedClockEvents service = newService();
        EventService.Event shortEvent = service.create("仅 24 小时", "GOMOKU", 50, "24h");
        service.advanceHours(25);
        assertNull(service.activeFor(GameType.GOMOKU, service.clock), "到期后不再生效");

        EventService.Event paused = service.create("暂停中", "GOMOKU", 80, "7d");
        service.setActive(paused.id, false);
        assertNull(service.activeFor(GameType.GOMOKU, service.clock), "暂停的活动不生效");
        assertNotNull(service.activeFor(GameType.GOMOKU, shortEvent.startsAt), "历史时刻仍可查到当时生效的活动");
    }

    @Test
    void listLiveIsSortedCappedAndExcludesInactive() {
        FixedClockEvents service = newService();
        service.create("加成 25", "ALL", 25, "7d");
        service.create("加成 100", "ALL", 100, "7d");
        service.create("加成 50", "GOMOKU", 50, "7d");
        service.create("加成 10", "ALL", 10, "7d");
        service.advanceHours(1);
        EventService.Event paused = service.create("加成 200 但暂停", "ALL", 200, "7d");
        service.setActive(paused.id, false);
        service.advanceHours(1);
        service.create("加成 30", "ALL", 30, "7d");
        service.advanceHours(1);
        service.create("加成 40", "GOMOKU", 40, "7d");

        List<EventService.Event> live = service.listLive();
        assertEquals(EventService.MAX_LIVE, live.size(), "大厅最多展示 5 个活动");
        assertEquals(100, live.get(0).xpPercent, "加成高的在前");
        assertEquals(50, live.get(1).xpPercent);
        assertEquals(40, live.get(2).xpPercent);
        assertEquals(30, live.get(3).xpPercent);
        assertEquals(25, live.get(4).xpPercent, "第 6 个活动因上限被截掉");
        assertTrue(live.stream().noneMatch(e -> e.title.contains("暂停")));
    }

    @Test
    void keepsOnlyNewestHundredRecords() {
        FixedClockEvents service = newService();
        for (int i = 0; i < 105; i++) {
            service.create("活动 " + i, "ALL", 10 + (i % 5) * 10, "30d");
            service.advanceHours(1);
        }
        List<EventService.Event> all = service.listAll();
        assertEquals(100, all.size());
        assertEquals("活动 104", all.get(0).title);
        assertEquals("活动 5", all.get(99).title, "超出上限时从最旧的开始丢弃");
    }

    @Test
    void stateSurvivesReloadAndDeletePersists() {
        Path file = dir.resolve("events.json");
        FixedClockEvents first = new FixedClockEvents(file);
        first.init();
        EventService.Event keep = first.create("保留", "GO9", 50, "30d");
        EventService.Event drop = first.create("删除", "ALL", 25, "7d");
        first.setActive(keep.id, false);
        assertTrue(Files.exists(file));

        FixedClockEvents loaded = new FixedClockEvents(file);
        loaded.init();
        List<EventService.Event> all = loaded.listAll();
        assertEquals(2, all.size());
        assertEquals("删除", all.get(0).title);
        assertTrue(all.get(0).active);
        assertFalse(all.get(1).active, "暂停状态应持久化");
        assertEquals("GO9", all.get(1).gameType);

        assertTrue(loaded.delete(drop.id));
        assertFalse(loaded.delete("EVT-NOTFOUND"));
        FixedClockEvents again = new FixedClockEvents(file);
        again.init();
        assertEquals(1, again.listAll().size());
    }

    @Test
    void unknownIdToggleReturnsNullAndLabelsAreReadable() {
        FixedClockEvents service = newService();
        assertNull(service.setActive("EVT-NOTFOUND", true));
        assertEquals("全部玩法", EventService.gameLabel("ALL"));
        assertEquals("五子棋", EventService.gameLabel("GOMOKU"));
    }
}
