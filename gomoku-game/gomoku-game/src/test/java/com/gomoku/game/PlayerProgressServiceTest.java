package com.gomoku.game;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 成长体系回归：等级曲线、经验来源、成就解锁。
 *
 * 重点是「多人休闲局也有成长」—— 这是 ELO 覆盖不到的那一半玩法，
 * 也是此前「打完一局没有任何长期反馈」的缺口。
 */
class PlayerProgressServiceTest {

    private static class CountingRecords extends GameRecordService {
        int scans;
        CountingRecords() throws Exception { super(Files.createTempDirectory("progress-cache")); }
        @Override
        public synchronized long visitIdentityRecords(String identityId, java.util.function.Consumer<GameRecord> visitor) {
            scans++;
            return super.visitIdentityRecords(identityId, visitor);
        }
    }

    @Test
    void cachedProgressInvalidatesOnlyForThatIdentityAndDoesNotCacheRewardXp() throws Exception {
        CountingRecords records = new CountingRecords();
        try {
            PlayerProgressService service = new PlayerProgressService(records);
            GameRecord first = game("CACHE-1", "GOMOKU", 1000L, 2000L, "a", "WIN", 2);
            first.setId("CACHE-1"); records.saveAsync(first);
            assertEquals(75, service.getProgress("a").get("xp"));
            int scans = records.scans;
            assertEquals(125, service.getProgress("a", 50).get("xp"));
            records.saveAsync(game("CACHE-OTHER", "UNO", 3000L, 4000L, "b", "WIN", 2));
            assertEquals(75, service.getProgress("a").get("xp"));
            records.saveAsync(first); // 幂等重试也不应使缓存失效。
            assertEquals(75, service.getProgress("a").get("xp"));
            assertEquals(scans, records.scans);
            records.saveAsync(game("CACHE-2", "GOMOKU", 5000L, 6000L, "a", "LOSE", 2));
            assertEquals(95, service.getProgress("a").get("xp"));
            assertEquals(scans + 1, records.scans);
        } finally { records.shutdown(); }
    }

    @Test
    void boundedProgressCacheCanEvictAndRebuildWithoutLosingAssets() throws Exception {
        CountingRecords records = new CountingRecords();
        try {
            PlayerProgressService service = new PlayerProgressService(records);
            records.saveAsync(game("CACHE-KEEP", "GOMOKU", 1000L, 2000L, "owner", "WIN", 2));
            Map<String, Object> expected = service.getProgress("owner");
            for (int i = 0; i < 512; i++) service.getProgress("visitor-" + i);
            int before = records.scans;
            assertEquals(expected, service.getProgress("owner"));
            assertEquals(before + 1, records.scans, "冷身份应从完整历史重建，而不是让缓存随身份数无限增长");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> achievements = (List<Map<String, Object>>) expected.get("achievements");
            achievements.get(0).put("unlocked", false);
            assertTrue(unlocked(service.getProgress("owner"), "first-game"), "API 调用方不能篡改缓存中的成就");
        } finally { records.shutdown(); }
    }

    private static GameRecordService newRecords() throws Exception {
        return new GameRecordService(Files.createTempDirectory("progress-test"));
    }

    private static GameRecord game(String roomId, String gameType, long startedAt, long endedAt,
                                   String identity, String result, int humans) {
        GameRecord rec = new GameRecord();
        rec.setRoomId(roomId);
        rec.setGameType(gameType);
        rec.setStartedAt(startedAt);
        rec.setEndedAt(endedAt);
        rec.setMoveCount(10);
        GameRecord.Participant me = new GameRecord.Participant(
                "p-" + roomId, "成长玩家", 1, result, false, 1200, 1200, 0);
        me.setIdentityId(identity);
        rec.getParticipants().add(me);
        for (int i = 2; i <= humans; i++) {
            GameRecord.Participant other = new GameRecord.Participant(
                    "p-" + roomId + "-" + i, "对手" + i, i, "LOSE", false, 1200, 1200, 0);
            other.setIdentityId("guest-other-" + roomId + "-" + i);
            rec.getParticipants().add(other);
        }
        return rec;
    }

    @Test
    void firstWinGrantsExperienceAndUnlocksStarterAchievements() throws Exception {
        GameRecordService records = newRecords();
        PlayerProgressService service = new PlayerProgressService(records);
        String me = "guest-progress";

        records.saveAsync(game("R1", "GOMOKU", 1000L, 2000L, me, "WIN", 2));
        Map<String, Object> p = service.getProgress(me);

        // 20 基础 + 30 胜利 + 25 新玩法
        assertEquals(75, p.get("xp"));
        assertEquals(1, p.get("level"));
        assertEquals(75, p.get("levelXp"));
        assertEquals(100, p.get("levelXpGoal"));
        assertEquals(1, p.get("totalGames"));
        assertEquals(1, p.get("wins"));
        assertTrue(unlocked(p, "first-game"));
        assertTrue(unlocked(p, "first-win"));
        assertFalse(unlocked(p, "games-10"));
    }

    @Test
    void levelGrowsWithAccumulatedExperience() throws Exception {
        GameRecordService records = newRecords();
        PlayerProgressService service = new PlayerProgressService(records);
        String me = "guest-level";

        // 两局胜利：75 + 50 = 125 → 超过 100，升到 2 级
        records.saveAsync(game("L1", "GOMOKU", 1000L, 2000L, me, "WIN", 2));
        records.saveAsync(game("L2", "GOMOKU", 3000L, 4000L, me, "WIN", 2));
        Map<String, Object> p = service.getProgress(me);

        assertEquals(125, p.get("xp"));
        assertEquals(2, p.get("level"));
        assertEquals(25, p.get("levelXp"));
        assertEquals("新手", p.get("title"), "2 级仍是新手段位，3 级起才是学徒");
        assertTrue(unlocked(p, "streak-3") == false, "只有两连胜，三连胜不应解锁");
    }

    @Test
    void multiplayerGamesAlsoEarnExperience() throws Exception {
        GameRecordService records = newRecords();
        PlayerProgressService service = new PlayerProgressService(records);
        String me = "guest-multi-progress";

        // 4 人 UNO：20 + 30 + 10 多人 + 25 新玩法
        records.saveAsync(game("M1", "UNO", 1000L, 2000L, me, "WIN", 4));
        Map<String, Object> p = service.getProgress(me);

        assertEquals(85, p.get("xp"));
        assertEquals(1, p.get("multiplayerGames"));
        assertEquals(1, p.get("totalGames"));
    }

    @Test
    void eventBonusComesFromTheRecordAndDoesNotLeakToLaterGames() throws Exception {
        GameRecordService records = newRecords();
        PlayerProgressService service = new PlayerProgressService(records);
        String me = "guest-event-bonus";

        GameRecord withEvent = game("E1", "GOMOKU", 1000L, 2000L, me, "WIN", 2);
        withEvent.setEventId("EVT-1");
        withEvent.setEventTitle("周末加成");
        withEvent.setEventXpPercent(50);
        records.saveAsync(withEvent);

        // 基础 20 + 胜利 30 + 新玩法 25 = 75；活动再 +50% = 38（四舍五入）
        assertEquals(113, service.getProgress(me).get("xp"));

        // 次日活动已结束：新对局没有刻活动，只拿 20 基础经验，历史加成不缩水
        records.saveAsync(game("E2", "GOMOKU", 3000L, 4000L, me, "LOSE", 2));
        assertEquals(133, service.getProgress(me).get("xp"));
    }

    @Test
    void threeWinsUnlockStreakAndVarietyTracksGameTypes() throws Exception {
        GameRecordService records = newRecords();
        PlayerProgressService service = new PlayerProgressService(records);
        String me = "guest-streak";

        records.saveAsync(game("S1", "GOMOKU", 1000L, 2000L, me, "WIN", 2));
        records.saveAsync(game("S2", "GOMOKU", 3000L, 4000L, me, "WIN", 2));
        records.saveAsync(game("S3", "UNO", 5000L, 6000L, me, "WIN", 4));
        Map<String, Object> p = service.getProgress(me);

        assertTrue(unlocked(p, "streak-3"));
        assertEquals(3, p.get("winStreak"));
        assertEquals(2, p.get("gameTypes"));
        assertEquals(3, p.get("wins"));
    }

    @Test
    void noGamesMeansLevelOneWithNoAchievements() throws Exception {
        PlayerProgressService service = new PlayerProgressService(newRecords());
        Map<String, Object> p = service.getProgress("guest-empty");

        assertEquals(0, p.get("xp"));
        assertEquals(1, p.get("level"));
        assertEquals(0, p.get("totalGames"));
        assertEquals(0, p.get("achievementsUnlocked"));
        assertEquals(12 + GameType.values().length, p.get("achievementsTotal"),
                "基础 11 项 + 全模式制霸 + 每种玩法一个首胜");
        assertEquals("新手", p.get("title"));
    }

    @Test
    void levelCurveIsMonotonic() {
        int previousThreshold = -1;
        for (int level = 1; level <= 20; level++) {
            int step = PlayerProgressService.xpToNextLevel(level);
            assertTrue(step > previousThreshold, "等级越高升级所需经验应递增");
            previousThreshold = step;
        }
        assertEquals(1, PlayerProgressService.levelForXp(0));
        assertEquals(1, PlayerProgressService.levelForXp(99));
        assertEquals(2, PlayerProgressService.levelForXp(100));
        assertEquals("新手", PlayerProgressService.titleForLevel(2));
        assertEquals("学徒", PlayerProgressService.titleForLevel(3));
        assertEquals("棋手", PlayerProgressService.titleForLevel(5));
    }

    @Test
    void checkInBonusAddsXpWithoutInflatingGameStats() throws Exception {
        GameRecordService records = newRecords();
        PlayerProgressService service = new PlayerProgressService(records);
        String me = "guest-checkin";

        records.saveAsync(game("R1", "GOMOKU", 1000L, 2000L, me, "WIN", 2));
        Map<String, Object> plain = service.getProgress(me);
        Map<String, Object> withBonus = service.getProgress(me, 75);

        // 签到经验计入等级经验，但对局数/胜场/成就不受影响
        assertEquals((Integer) plain.get("xp") + 75, withBonus.get("xp"));
        assertEquals(plain.get("totalGames"), withBonus.get("totalGames"));
        assertEquals(plain.get("wins"), withBonus.get("wins"));
        assertEquals(plain.get("achievementsUnlocked"), withBonus.get("achievementsUnlocked"));
    }

    @Test
    void modeFirstWinAchievementsTrackEachGameType() throws Exception {
        GameRecordService records = newRecords();
        PlayerProgressService service = new PlayerProgressService(records);
        String me = "guest-modes";

        records.saveAsync(game("G1", "GOMOKU", 1000L, 2000L, me, "WIN", 2));
        records.saveAsync(game("U1", "UNO", 3000L, 4000L, me, "WIN", 4));
        records.saveAsync(game("R1", "REVERSI", 5000L, 6000L, me, "LOSE", 2));
        Map<String, Object> p = service.getProgress(me);

        assertTrue(unlocked(p, "mode-win-gomoku"));
        assertTrue(unlocked(p, "mode-win-uno"));
        assertFalse(unlocked(p, "mode-win-reversi"), "只体验未获胜不应解锁该玩法首胜");
        assertEquals(2, achievement(p, "all-modes").get("progress"), "胜场玩法数应累计");
        assertEquals(GameType.values().length, achievement(p, "all-modes").get("goal"));
        assertFalse(unlocked(p, "all-modes"));
    }

    @Test
    void winningEveryGameTypeUnlocksAllModes() throws Exception {
        GameRecordService records = newRecords();
        PlayerProgressService service = new PlayerProgressService(records);
        String me = "guest-all-modes";

        long t = 1000L;
        for (GameType type : GameType.values()) {
            records.saveAsync(game("ALL-" + type.name(), type.name(), t, t + 1000L, me, "WIN", 2));
            t += 2000L;
        }
        Map<String, Object> p = service.getProgress(me);

        for (GameType type : GameType.values()) {
            assertTrue(unlocked(p, "mode-win-" + type.name().toLowerCase()), "每种玩法首胜都应解锁：" + type);
        }
        assertTrue(unlocked(p, "all-modes"));
        assertEquals(GameType.values().length, p.get("gameTypes"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> achievement(Map<String, Object> progress, String id) {
        for (Map<String, Object> a : (List<Map<String, Object>>) progress.get("achievements")) {
            if (id.equals(a.get("id"))) return a;
        }
        fail("未找到成就：" + id);
        return null;
    }

    private static boolean unlocked(Map<String, Object> progress, String id) {
        return Boolean.TRUE.equals(achievement(progress, id).get("unlocked"));
    }
}
