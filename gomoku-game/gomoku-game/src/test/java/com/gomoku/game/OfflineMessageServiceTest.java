package com.gomoku.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 离线私聊单测：暂存、投递后清空、上限、过期、持久化。 */
class OfflineMessageServiceTest {
    @TempDir Path dir;

    /** 可控时钟，便于验证 TTL */
    private static class FixedClock extends OfflineMessageService {
        long clock = 1_700_000_000_000L;
        FixedClock(Path path) { super(path); }
        @Override long now() { return clock; }
        void advanceDays(long days) { clock += days * 24 * 3600 * 1000L; }
    }

    private FixedClock newService() {
        FixedClock service = new FixedClock(dir.resolve("offline.json"));
        service.init();
        return service;
    }

    @Test
    void storeThenDrainDeliversOnce() {
        FixedClock service = newService();
        assertEquals(1, service.store("to", "from", "甲", "你好"));
        assertEquals(2, service.store("to", "from", "甲", "在吗"), "返回的是该收件人信箱当前条数");
        assertEquals(2, service.pendingCount("to"));

        List<OfflineMessageService.Message> messages = service.drain("to");
        assertEquals(2, messages.size());
        assertEquals("你好", messages.get(0).text, "旧的排在前，保证聊天顺序");
        assertEquals("甲", messages.get(0).fromName);
        assertEquals(0, service.pendingCount("to"), "投递后即清空");
        assertTrue(service.drain("to").isEmpty(), "重复投递返回空");
    }

    @Test
    void drainForEmptyMailboxReturnsEmpty() {
        FixedClock service = newService();
        assertTrue(service.drain("nobody").isEmpty());
        assertTrue(service.drain(null).isEmpty());
    }

    @Test
    void emptyTextIsIgnored() {
        FixedClock service = newService();
        assertEquals(0, service.store("to", "from", "甲", ""));
        assertEquals(0, service.store("to", "from", "甲", null));
        assertEquals(0, service.pendingCount("to"));
    }

    @Test
    void capDropsOldestMessages() {
        FixedClock service = newService();
        for (int i = 0; i < OfflineMessageService.MAX_PER_RECIPIENT + 10; i++) {
            service.store("to", "from", "甲", "消息" + i);
        }
        assertEquals(OfflineMessageService.MAX_PER_RECIPIENT, service.pendingCount("to"));
        List<OfflineMessageService.Message> messages = service.drain("to");
        int lastIndex = OfflineMessageService.MAX_PER_RECIPIENT + 10 - 1;
        assertTrue(messages.get(messages.size() - 1).text.endsWith(String.valueOf(lastIndex)),
                "最新一条必须保留（最旧的被丢弃）");
        assertFalse(messages.get(0).text.contains("消息0"), "超出上限的最旧消息应被丢弃");
    }

    @Test
    void expiredMessagesArePurged() {
        FixedClock service = newService();
        service.store("to", "from", "甲", "七天前的消息");
        service.advanceDays(8);
        assertEquals(0, service.pendingCount("to"), "超过保留期应自动清理");
    }

    @Test
    void messagesSurviveReload() {
        FixedClock service = newService();
        service.store("to", "from", "甲", "离线留言");
        assertTrue(Files.exists(dir.resolve("offline.json")));

        FixedClock reloaded = new FixedClock(dir.resolve("offline.json"));
        reloaded.init();
        assertEquals(1, reloaded.pendingCount("to"), "重启后留言仍在，等对方上线投递");
        assertEquals("离线留言", reloaded.drain("to").get(0).text);
    }

    @Test
    void recipientsAreIndependent() {
        FixedClock service = newService();
        service.store("a", "x", "甲", "给 a");
        service.store("b", "x", "甲", "给 b");
        assertEquals(1, service.pendingCount("a"));
        assertEquals(1, service.pendingCount("b"));
        assertEquals("给 a", service.drain("a").get(0).text);
        assertEquals(1, service.pendingCount("b"), "取走 a 的信箱不影响 b");
    }
}
