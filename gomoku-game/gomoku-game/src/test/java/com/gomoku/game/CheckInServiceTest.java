package com.gomoku.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 签到留存测试：防重复、连续、断裂、里程碑、回归、持久化、经验叠加。 */
class CheckInServiceTest {
    @TempDir Path dir;

    /** 用可变「今天」驱动签到，模拟跨天场景 */
    private static class Clock extends CheckInService {
        private LocalDate date;
        Clock(Path path, LocalDate date) { super(path); this.date = date; }
        void setDate(LocalDate d) { this.date = d; }
        @Override protected LocalDate today() { return date; }
    }

    private Clock newService(LocalDate start) {
        Clock c = new Clock(dir.resolve("checkins.json"), start);
        c.init();
        return c;
    }

    /** 把日期切到指定那天再签到 */
    private static Map<String, Object> sign(Clock c, String date, String id) {
        c.setDate(LocalDate.parse(date));
        return c.checkIn(id);
    }

    @Test
    void firstCheckInGivesBaseXpAndStreakOne() {
        CheckInService service = newService(LocalDate.of(2026, 1, 1));
        Map<String, Object> r = service.checkIn("u1");
        assertEquals(false, r.get("alreadySigned"));
        assertEquals(1, r.get("streak"));
        assertEquals(15, r.get("rewardXp"));
        assertEquals(15, service.bonusXpFor("u1"));
        assertEquals(false, r.get("comeback"));
    }

    @Test
    void sameDayCheckInDoesNotRewardTwice() {
        Clock c = newService(LocalDate.of(2026, 1, 1));
        c.checkIn("u1");
        Map<String, Object> again = c.checkIn("u1");
        assertEquals(true, again.get("alreadySigned"));
        assertEquals(0, again.get("rewardXp"));
        assertEquals(15, c.bonusXpFor("u1"));
    }

    @Test
    void consecutiveDaysBuildStreakAndMilestoneReward() {
        Clock c = newService(LocalDate.of(2026, 1, 1));
        sign(c, "2026-01-01", "u1");
        sign(c, "2026-01-02", "u1");
        Map<String, Object> day3 = sign(c, "2026-01-03", "u1");
        assertEquals(3, day3.get("streak"));
        assertEquals(3, day3.get("milestone"), "连续第 3 天触发里程碑");
        // 15 基础 ×3 + 30 里程碑 = 75
        assertEquals(75, c.bonusXpFor("u1"));
    }

    @Test
    void brokenStreakResetsToOne() {
        Clock c = newService(LocalDate.of(2026, 1, 1));
        sign(c, "2026-01-01", "u1");
        sign(c, "2026-01-02", "u1");
        // 跳过 1/3，1/5 再签 → 断签重置
        Map<String, Object> r = sign(c, "2026-01-05", "u1");
        assertEquals(1, r.get("streak"));
        assertEquals(2, r.get("longest"), "历史最长连续应保留");
    }

    @Test
    void comebackGivesExtraRewardAfterGap() {
        Clock c = newService(LocalDate.of(2026, 1, 1));
        sign(c, "2026-01-01", "u1");
        Map<String, Object> r = sign(c, "2026-01-05", "u1");
        assertEquals(true, r.get("comeback"));
        // 15 基础 + 50 回归 = 65
        assertEquals(65, r.get("rewardXp"));
        assertEquals(1, r.get("streak"), "回归后连续重新开始");
    }

    @Test
    void statusReflectsSignedTodayAndProjectedStreak() {
        Clock c = newService(LocalDate.of(2026, 1, 1));
        Map<String, Object> before = c.status("u1");
        assertEquals(false, before.get("signedToday"));
        assertEquals(1, before.get("streak"), "首次签到前的预期连续为 1");
        assertEquals(3, before.get("nextMilestone"));

        c.checkIn("u1");
        Map<String, Object> after = c.status("u1");
        assertEquals(true, after.get("signedToday"));
        assertEquals(3, after.get("nextMilestone"));
    }

    @Test
    void checkInsSurviveRestart() throws Exception {
        Clock c = newService(LocalDate.of(2026, 1, 1));
        c.checkIn("u1");
        CheckInService restarted = new CheckInService(dir.resolve("checkins.json"));
        restarted.init();
        assertEquals(15, restarted.bonusXpFor("u1"), "重启后签到奖励仍在");
        Map<String, Object> r = restarted.checkIn("u1");
        assertEquals(false, r.get("alreadySigned"), "新服务用真实日期，与 1/1 不同应可再签");
    }

    @Test
    void invalidIdentityRejected() {
        CheckInService c = newService(LocalDate.of(2026, 1, 1));
        assertNull(c.checkIn(null));
        assertNull(c.checkIn(""));
        assertNull(c.status(null));
    }

    @Test
    void manyDaysStayBoundedAndReadable() throws Exception {
        Clock c = newService(LocalDate.of(2026, 1, 1));
        // 连续签 60 天，落盘后仍可读
        for (int i = 0; i < 60; i++) {
            c.setDate(LocalDate.of(2026, 1, 1).plusDays(i));
            c.checkIn("u1");
        }
        String disk = Files.readString(dir.resolve("checkins.json"));
        assertTrue(disk.contains("bonusXp"));
        assertTrue(c.bonusXpFor("u1") > 900);
    }
}
