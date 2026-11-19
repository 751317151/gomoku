package com.gomoku.game;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DailyQuestServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 5, 20);

    @TempDir
    Path temp;

    private final List<GameRecordService> opened = new ArrayList<>();

    @AfterEach
    void closeServices() {
        for (GameRecordService service : opened) service.shutdown();
    }

    @Test
    void newIdentitySeesAllQuestsUnclaimed() throws Exception {
        FixedQuestService quests = service(records());
        Map<String, Object> status = quests.status("guest-new");

        assertEquals(DAY.toString(), status.get("date"));
        assertEquals(5, quests(status).size());
        for (Map<String, Object> quest : quests(status)) {
            assertEquals(Boolean.FALSE, quest.get("claimable"), quest.get("id") + " 不应可领");
            assertEquals(Boolean.FALSE, quest.get("claimed"), quest.get("id") + " 不应已领");
        }
        assertEquals(0, status.get("claimableXp"));
        assertEquals(0, status.get("bonusXp"));
        assertEquals(0, status.get("streakDays"));
    }

    @Test
    void todayRecordsDriveQuestProgress() throws Exception {
        GameRecordService records = records();
        FixedQuestService quests = service(records);
        records.saveAsync(game("G1", "guest-play", DAY, "WIN", "GOMOKU", 2, 0, 0));
        records.saveAsync(game("G2", "guest-play", DAY, "LOSE", "REVERSI", 2, 0, 1));
        records.saveAsync(game("G3", "guest-play", DAY, "WIN", "UNO", 4, 0, 2));

        Map<String, Object> status = quests.status("guest-play");
        assertEquals(3, status.get("todayGames"));
        assertEquals(2, status.get("todayWins"));
        assertEquals(3, status.get("todayGameTypes"));
        assertEquals(1, status.get("todayMultiplayerGames"));
        assertEquals(1, status.get("streakDays"));
        assertEquals(Boolean.TRUE, quest(status, "games-3").get("claimable"));
        assertEquals(Boolean.TRUE, quest(status, "win-1").get("claimable"));
        assertEquals(Boolean.TRUE, quest(status, "variety-2").get("claimable"));
        assertEquals(Boolean.TRUE, quest(status, "multiplayer-1").get("claimable"));
        assertEquals(Boolean.FALSE, quest(status, "streak-3").get("claimable"));
        assertEquals(145, status.get("claimableXp"));
    }

    @Test
    void aiOpponentGamesCountTowardSoloPlayerGoals() throws Exception {
        GameRecordService records = records();
        FixedQuestService quests = service(records);
        records.saveAsync(game("S1", "guest-solo", DAY, "WIN", "GOMOKU", 1, 1, 0));

        Map<String, Object> status = quests.status("guest-solo");
        assertEquals(1, status.get("todayGames"), "单人打电脑也应计入每日任务");
        assertEquals(1, status.get("todayWins"));
        assertEquals(Boolean.TRUE, quest(status, "win-1").get("claimable"));
    }

    @Test
    void multiplayerQuestRequiresThreeHumans() throws Exception {
        GameRecordService records = records();
        FixedQuestService quests = service(records);
        records.saveAsync(game("M1", "guest-multi", DAY, "WIN", "UNO", 2, 1, 1));
        assertEquals(0, quests.status("guest-multi").get("todayMultiplayerGames"),
                "2 名真人 + 1 个电脑不算 3 人局");

        records.saveAsync(game("M2", "guest-multi", DAY, "WIN", "UNO", 3, 0, 2));
        Map<String, Object> status = quests.status("guest-multi");
        assertEquals(1, status.get("todayMultiplayerGames"));
        assertEquals(Boolean.TRUE, quest(status, "multiplayer-1").get("claimable"));
    }

    @Test
    void claimGrantsExperienceOnlyOnce() throws Exception {
        GameRecordService records = records();
        FixedQuestService quests = service(records);
        for (int i = 1; i <= 3; i++) {
            records.saveAsync(game("C" + i, "guest-claim", DAY, "LOSE", "GOMOKU", 2, 0, i));
        }

        Map<String, Object> first = quests.claim("guest-claim", "games-3");
        assertEquals(Boolean.TRUE, first.get("ok"));
        assertEquals(30, first.get("rewardXp"));
        Map<String, Object> status = statusOf(first);
        assertEquals(30, status.get("bonusXp"));
        assertEquals(30, status.get("claimedXp"));
        assertEquals(Boolean.TRUE, quest(status, "games-3").get("claimed"));
        assertEquals(0, status.get("claimableXp"), "三局同玩法全负，领取后没有其它可领任务");

        Map<String, Object> again = quests.claim("guest-claim", "games-3");
        assertEquals(Boolean.FALSE, again.get("ok"));
        assertEquals("ALREADY_CLAIMED", again.get("reason"));
        assertEquals(30, quests.bonusXpFor("guest-claim"));
    }

    @Test
    void incompleteQuestCannotBeClaimed() throws Exception {
        GameRecordService records = records();
        FixedQuestService quests = service(records);
        records.saveAsync(game("L1", "guest-incomplete", DAY, "LOSE", "GOMOKU", 2, 0, 0));

        Map<String, Object> result = quests.claim("guest-incomplete", "win-1");
        assertEquals(Boolean.FALSE, result.get("ok"));
        assertEquals("NOT_COMPLETED", result.get("reason"));
        assertEquals(0, quests.bonusXpFor("guest-incomplete"));
    }

    @Test
    void unknownQuestIsRejected() throws Exception {
        FixedQuestService quests = service(records());
        Map<String, Object> result = quests.claim("guest-unknown", "no-such-quest");
        assertEquals(Boolean.FALSE, result.get("ok"));
        assertEquals("UNKNOWN_QUEST", result.get("reason"));
    }

    @Test
    void yesterdayRecordsDoNotCountToday() throws Exception {
        GameRecordService records = records();
        FixedQuestService quests = service(records);
        records.saveAsync(game("Y1", "guest-yesterday", DAY.minusDays(1), "WIN", "GOMOKU", 2, 0, 0));

        Map<String, Object> status = quests.status("guest-yesterday");
        assertEquals(0, status.get("todayGames"));
        assertEquals(0, status.get("streakDays"));
        assertEquals("NOT_COMPLETED", quests.claim("guest-yesterday", "win-1").get("reason"));
    }

    @Test
    void claimsSurviveRestart() throws Exception {
        GameRecordService records = records();
        Path file = temp.resolve("quests-persist.json");
        FixedQuestService first = new FixedQuestService(file, records, DAY);
        records.saveAsync(game("P1", "guest-restart", DAY, "WIN", "GOMOKU", 2, 0, 0));
        assertEquals(Boolean.TRUE, first.claim("guest-restart", "win-1").get("ok"));

        FixedQuestService second = new FixedQuestService(file, records, DAY);
        second.init();
        Map<String, Object> status = second.status("guest-restart");
        assertEquals(Boolean.TRUE, quest(status, "win-1").get("claimed"));
        assertEquals(40, status.get("bonusXp"));
        assertEquals(40, second.bonusXpFor("guest-restart"));
    }

    @Test
    void nextDayResetsClaimsButKeepsBonusExperience() throws Exception {
        GameRecordService records = records();
        FixedQuestService quests = service(records);
        records.saveAsync(game("R1", "guest-reset", DAY, "WIN", "GOMOKU", 2, 0, 0));
        assertEquals(Boolean.TRUE, quests.claim("guest-reset", "win-1").get("ok"));

        quests.setDate(DAY.plusDays(1));
        Map<String, Object> nextDay = quests.status("guest-reset");
        assertEquals(Boolean.FALSE, quest(nextDay, "win-1").get("claimed"), "新的一天应重新可领");
        assertEquals(Boolean.FALSE, quest(nextDay, "win-1").get("claimable"), "新的一天需要重新完成");
        assertEquals(40, nextDay.get("bonusXp"), "已领取经验属于终身累计，不应被跨天清零");

        records.saveAsync(game("R2", "guest-reset", DAY.plusDays(1), "WIN", "GOMOKU", 2, 0, 1));
        assertEquals(Boolean.TRUE, quests.claim("guest-reset", "win-1").get("ok"));
        assertEquals(80, quests.bonusXpFor("guest-reset"));
    }

    @Test
    void streakQuestUnlocksAfterThreeActiveDays() throws Exception {
        GameRecordService records = records();
        FixedQuestService quests = service(records);
        records.saveAsync(game("K1", "guest-streak", DAY.minusDays(2), "LOSE", "GOMOKU", 2, 0, 0));
        records.saveAsync(game("K2", "guest-streak", DAY.minusDays(1), "LOSE", "GOMOKU", 2, 0, 1));
        records.saveAsync(game("K3", "guest-streak", DAY, "LOSE", "GOMOKU", 2, 0, 2));

        Map<String, Object> status = quests.status("guest-streak");
        assertEquals(3, status.get("streakDays"));
        assertEquals(Boolean.TRUE, quest(status, "streak-3").get("claimable"));
        Map<String, Object> claim = quests.claim("guest-streak", "streak-3");
        assertEquals(Boolean.TRUE, claim.get("ok"));
        assertEquals(50, claim.get("rewardXp"));
    }

    @Test
    void invalidIdentityIsRejected() throws Exception {
        FixedQuestService quests = service(records());
        assertNull(quests.status(null));
        assertNull(quests.status(""));
        assertNull(quests.claim(null, "games-3"));
        assertEquals(0, quests.bonusXpFor(null));
    }

    // ============ helpers ============

    private GameRecordService records() throws Exception {
        GameRecordService service = new GameRecordService(temp.resolve("records-" + opened.size()));
        opened.add(service);
        return service;
    }

    private FixedQuestService service(GameRecordService records) {
        return new FixedQuestService(
                temp.resolve("quests-" + System.nanoTime() + ".json"), records, DAY);
    }

    private static GameRecord game(String id, String identity, LocalDate day, String result, String type,
                                   int humans, int aiCount, int seed) {
        GameRecord record = new GameRecord();
        record.setId("quest-" + id);
        record.setGameType(type);
        record.setStartedAt(day.atTime(9, 0).atZone(ZONE).toInstant().toEpochMilli() + seed);
        record.setEndedAt(day.atTime(10, 0).atZone(ZONE).toInstant().toEpochMilli() + seed);
        GameRecord.Participant me = new GameRecord.Participant("p-" + id, "我", 1, result,
                false, 1200, 1200, 0);
        me.setIdentityId(identity);
        record.getParticipants().add(me);
        for (int i = 2; i <= humans; i++) {
            GameRecord.Participant other = new GameRecord.Participant("h" + i + "-" + id, "玩家" + i, i,
                    "LOSE", false, 1200, 1200, 0);
            other.setIdentityId("other-" + i + "-" + id);
            record.getParticipants().add(other);
        }
        for (int i = 1; i <= aiCount; i++) {
            record.getParticipants().add(new GameRecord.Participant("ai" + i + "-" + id, "电脑" + i, 10 + i,
                    "LOSE", true, 1200, 1200, 0));
        }
        return record;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> quests(Map<String, Object> status) {
        return (List<Map<String, Object>>) status.get("quests");
    }

    private static Map<String, Object> quest(Map<String, Object> status, String id) {
        for (Map<String, Object> quest : quests(status)) {
            if (id.equals(quest.get("id"))) return quest;
        }
        throw new AssertionError("缺少任务 " + id);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> statusOf(Map<String, Object> claimResult) {
        return (Map<String, Object>) claimResult.get("status");
    }

    /** 固定日期与时区的服务，让「今天/跨天」可稳定断言 */
    private static final class FixedQuestService extends DailyQuestService {
        private LocalDate date;

        FixedQuestService(Path path, GameRecordService records, LocalDate date) {
            super(path, records);
            this.date = date;
        }

        @Override
        protected LocalDate today() {
            return date;
        }

        @Override
        protected ZoneId zone() {
            return ZONE;
        }

        void setDate(LocalDate date) {
            this.date = date;
        }
    }
}
