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
        assertEquals(11, p.get("achievementsTotal"));
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

    @SuppressWarnings("unchecked")
    private static boolean unlocked(Map<String, Object> progress, String id) {
        for (Map<String, Object> a : (List<Map<String, Object>>) progress.get("achievements")) {
            if (id.equals(a.get("id"))) return Boolean.TRUE.equals(a.get("unlocked"));
        }
        fail("未找到成就：" + id);
        return false;
    }
}
