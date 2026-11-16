package com.gomoku.game;

import com.gomoku.model.Player;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对局记录持久化测试
 *
 * 这里钉住的是「静默失效」类缺陷 —— 存档失败不会抛异常、也不会影响对局结束，
 * 所以必须用「查得到 / 字段对得上」来证明它真的写了：
 * 1. 终局后记录进索引，且胜负结果、ELO 前后值都正确
 * 2. AI 局标 ranked=false（不能污染真人战绩）
 * 3. 战绩统计只算计分局，AI 局不计
 * 4. 落盘后可从磁盘按 ID 取回完整棋谱
 */
class GameRecordServiceTest {

    @Test
    void dailyProgressUsesIdentityAndCountsStreak() throws Exception {
        GameRecordService svc = newService();
        String identity = "guest-daily";
        long today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 3600000L;
        long yesterday = LocalDate.now().minusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 3600000L;
        svc.saveAsync(dailyRecord("TODAY-1", today, identity, "WIN", "GOMOKU"));
        svc.saveAsync(dailyRecord("TODAY-2", today, identity, "LOSE", "REVERSI"));
        svc.saveAsync(dailyRecord("YESTERDAY", yesterday, identity, "WIN", "GOMOKU"));

        Map<String, Object> progress = svc.getIdentityDailyProgress(identity);
        assertEquals(2, progress.get("todayGames"));
        assertEquals(1, progress.get("todayWins"));
        assertEquals(2, progress.get("todayGameTypes"));
        assertEquals(2, progress.get("streakDays"));
        assertEquals(Boolean.FALSE, progress.get("gamesDone"));
        assertEquals(Boolean.TRUE, progress.get("winsDone"));
    }

    @Test
    void dailyProgressIncludesHumanMultiplayerCasualGames() throws Exception {
        GameRecordService svc = newService();
        String identity = "guest-multi";
        long today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 3600000L;
        GameRecord rec = dailyRecord("MULTI", today, identity, "WIN", "UNO");
        rec.setRanked(false); // 多人休闲局不计 ELO，但仍应激励活跃
        svc.saveAsync(rec);

        Map<String, Object> progress = svc.getIdentityDailyProgress(identity);
        assertEquals(1, progress.get("todayGames"));
        assertEquals(1, progress.get("todayWins"));
        assertEquals(1, progress.get("todayGameTypes"));
    }

    private static GameRecord dailyRecord(String roomId, long endedAt, String identity, String result, String gameType) {
        GameRecord rec = new GameRecord();
        rec.setRoomId(roomId); rec.setGameType(gameType); rec.setEndedAt(endedAt); rec.setRanked(true);
        GameRecord.Participant p = new GameRecord.Participant("p-" + roomId, "每日玩家", 1, result, false, 1200, 1200, 0);
        p.setIdentityId(identity);
        rec.getParticipants().add(p);
        return rec;
    }

    private Player newPlayer(String id, String name) {
        return new Player(id, name, null);
    }

    private GameRecordService newService() throws Exception {
        return new GameRecordService(Files.createTempDirectory("record-test"));
    }

    @Test
    void finishedGameIsIndexedWithResults() throws Exception {
        GameRecordService svc = newService();
        GameRoom room = new GameRoom("ROOM-REC", GameType.GOMOKU);
        room.setEloService(new EloService(
                Files.createTempDirectory("elo-rec").resolve("elo.properties"), null));
        room.setRecordService(svc);

        Player a = newPlayer("a", "甲");
        Player b = newPlayer("b", "乙");
        room.addPlayer(a);
        room.addPlayer(b);
        room.handleMove(a, 7, 7, 1);
        // 乙认输 → 甲胜
        room.surrender(b);

        List<GameRecord> games = svc.getRecentGames(10);
        assertEquals(1, games.size(), "终局后应有 1 条记录");

        GameRecord rec = games.get(0);
        assertEquals("GOMOKU", rec.getGameType());
        assertEquals("ROOM-REC", rec.getRoomId());
        assertEquals("甲", rec.getWinnerName(), "胜者应是甲");
        assertEquals(1, rec.getMoveCount(), "手数应与引擎一致");
        assertTrue(rec.isRanked(), "双真人局应标记为计分局");
        assertEquals(2, rec.getParticipants().size());

        GameRecord.Participant winner = findByName(rec, "甲");
        GameRecord.Participant loser = findByName(rec, "乙");
        assertNotNull(winner);
        assertNotNull(loser);

        assertEquals("WIN", winner.getResult());
        assertEquals("LOSE", loser.getResult());
        assertFalse(winner.isAi());
        assertFalse(loser.isAi());

        // 胜者加分、败者减分，且变化量互为相反数
        assertTrue(winner.getEloChange() > 0, "胜者应加分，实际 " + winner.getEloChange());
        assertEquals(-winner.getEloChange(), loser.getEloChange(), "双方变化量应互为相反数");
        assertEquals(1200, winner.getEloBefore(), "初始分应为 1200");
        assertEquals(winner.getEloBefore() + winner.getEloChange(), winner.getEloAfter(),
                "after 应等于 before + change");
    }

    @Test
    void aiGameIsNotRanked() throws Exception {
        GameRecordService svc = newService();
        GameRoom room = new GameRoom("ROOM-AI", GameType.GOMOKU);
        room.setEloService(new EloService(
                Files.createTempDirectory("elo-ai").resolve("elo.properties"), null));
        room.setRecordService(svc);

        Player human = newPlayer("h", "真人");
        room.addPlayer(human);
        room.addAI(2);
        // addAI 时会立即 startGame，之后再认输即可
        room.surrender(human);

        List<GameRecord> games = svc.getRecentGames(10);
        assertEquals(1, games.size(), "AI 局也应存档（便于复盘）");

        GameRecord rec = games.get(0);
        assertFalse(rec.isRanked(), "含 AI 的局不应标记为计分局");

        boolean hasAi = false;
        for (GameRecord.Participant p : rec.getParticipants()) {
            if (p.isAi()) hasAi = true;
        }
        assertTrue(hasAi, "参与者里应有 AI 标记");
    }

    @Test
    void playerStatsIgnoreAiGames() throws Exception {
        GameRecordService svc = newService();

        // 一局真人 PvP
        GameRoom pvp = new GameRoom("ROOM-PVP", GameType.GOMOKU);
        pvp.setEloService(new EloService(
                Files.createTempDirectory("elo-pvp").resolve("elo.properties"), null));
        pvp.setRecordService(svc);
        Player a = newPlayer("a", "统计甲");
        Player b = newPlayer("b", "统计乙");
        pvp.addPlayer(a);
        pvp.addPlayer(b);
        pvp.surrender(b); // 甲胜

        // 一局人机
        GameRoom pve = new GameRoom("ROOM-PVE", GameType.GOMOKU);
        pve.setEloService(new EloService(
                Files.createTempDirectory("elo-pve").resolve("elo.properties"), null));
        pve.setRecordService(svc);
        Player c = newPlayer("c", "统计甲");
        pve.addPlayer(c);
        pve.addAI(2);
        pve.surrender(c); // 甲胜（对 AI）

        Map<String, Object> stats = svc.getPlayerStats("统计甲", "GOMOKU");
        assertEquals(1, stats.get("total"), "只统计计分局，AI 局不计入 total");
        assertEquals(1, stats.get("wins"));
        assertEquals(0, stats.get("losses"));
        assertEquals(100.0, ((Number) stats.get("winRate")).doubleValue(), 0.01);
    }

    @Test
    void recordSurvivesDiskRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("record-disk");
        GameRecordService svc = new GameRecordService(dir);
        svc.init();

        GameRecord rec = new GameRecord();
        rec.setGameType("XIANGQI");
        rec.setRoomId("ROOM-DISK");
        rec.setStartedAt(1000L);
        rec.setEndedAt(2000L);
        rec.setWinnerName("甲");
        rec.setReason("认输");
        rec.setMoveCount(2);
        rec.setRanked(true);
        rec.getParticipants().add(new GameRecord.Participant(
                "p1", "甲", 1, "WIN", false, 1200, 1216, 16));
        rec.getParticipants().add(new GameRecord.Participant(
                "p2", "乙", 2, "LOSE", false, 1200, 1184, -16));
        rec.setMoveHistory(new java.util.ArrayList<>(List.of(
                new int[]{7, 4, 1}, new int[]{2, 6, 2})));

        svc.saveAsync(rec);
        svc.flushForTest();

        String id = rec.getId();
        assertNotNull(id, "saveAsync 应自动分配记录 ID");

        // 磁盘文件应存在
        assertTrue(Files.exists(dir.resolve(id + ".json")), "详情文件应落盘");
        assertTrue(Files.exists(dir.resolve("index.jsonl")), "摘要索引应落盘");

        // 详情应能取回棋谱
        GameRecord loaded = svc.getRecord(id);
        assertNotNull(loaded, "应能按 ID 取回记录");
        assertEquals("XIANGQI", loaded.getGameType());
        assertNotNull(loaded.getMoveHistory(), "详情应保留棋谱");
        assertEquals(2, loaded.getMoveHistory().size());
        assertEquals(7, loaded.getMoveHistory().get(0)[0]);

        // 摘要索引里不应带棋谱（避免列表查询解析大文件）
        List<GameRecord> recent = svc.getRecentGames(10);
        assertEquals(1, recent.size());
        assertNull(recent.get(0).getMoveHistory(), "内存摘要不应带棋谱");
    }

    @Test
    void indexReloadsFromDisk() throws Exception {
        Path dir = Files.createTempDirectory("record-reload");
        GameRecordService first = new GameRecordService(dir);
        first.init();

        GameRecord rec = new GameRecord();
        rec.setGameType("CONNECT4");
        rec.setEndedAt(3000L);
        rec.setWinnerName("甲");
        rec.setMoveCount(9);
        rec.setRanked(true);
        rec.getParticipants().add(new GameRecord.Participant(
                "p1", "甲", 1, "WIN", false, 1200, 1216, 16));
        rec.getParticipants().add(new GameRecord.Participant(
                "p2", "乙", 2, "LOSE", false, 1200, 1184, -16));
        first.saveAsync(rec);
        first.flushForTest();

        // 模拟重启：新实例读同一个目录
        GameRecordService second = new GameRecordService(dir);
        second.init();

        List<GameRecord> games = second.getRecentGames(10);
        assertEquals(1, games.size(), "重启后应能读回索引");
        assertEquals("CONNECT4", games.get(0).getGameType());
        assertEquals(9, games.get(0).getMoveCount());

        // 玩家索引也应重建（战绩页依赖它）
        Map<String, Object> stats = second.getPlayerStats("甲", "CONNECT4");
        assertEquals(1, stats.get("total"));
        assertEquals(1, stats.get("wins"));
    }

    @Test
    void recentGamesCanFilterByActualDeckBeforeLimit() throws Exception {
        GameRecordService svc = newService();

        // 时间顺序：AGGRO（较旧）→ 五子棋（无关）→ CONTROL（最新）
        svc.saveAsync(cardRecord("ROOM-OLD-AGGRO", "AGGRO", "CONTROL", 1000L));
        GameRecord gomoku = new GameRecord();
        gomoku.setGameType("GOMOKU");
        gomoku.setRoomId("ROOM-MID-GOMOKU");
        gomoku.setEndedAt(2000L);
        gomoku.getParticipants().add(new GameRecord.Participant(
                "g1", "甲", 1, "WIN", false, 1200, 1216, 16));
        gomoku.getParticipants().add(new GameRecord.Participant(
                "g2", "乙", 2, "LOSE", false, 1200, 1184, -16));
        svc.saveAsync(gomoku);
        svc.saveAsync(cardRecord("ROOM-NEW-CONTROL", "CONTROL", "BALANCED", 3000L));

        // limit=1 仍能命中较旧的 AGGRO：证明先过滤、后截断。
        // 若先取最近 1 条再筛，会得到空列表，这是本轮要钉住的核心回归。
        List<GameRecord> aggro = svc.getRecentGames(1, "CARD", "AGGRO");
        assertEquals(1, aggro.size(), "套牌过滤应先于 limit");
        assertEquals("ROOM-OLD-AGGRO", aggro.get(0).getRoomId());
        assertTrue(hasDeck(aggro.get(0), "AGGRO"), "任意一方使用该套牌即应命中");

        // 套牌名大小写不敏感，空白等价于不筛选
        assertEquals(1, svc.getRecentGames(10, "card", "aggro").size());
        assertEquals(2, svc.getRecentGames(10, "CARD", "  ").size());
        assertTrue(svc.getRecentGames(10, "CARD", "BALANCED").stream()
                .anyMatch(r -> "ROOM-NEW-CONTROL".equals(r.getRoomId())),
                "匹配第二方的套牌也算命中");

        // 视图接口走同一套过滤逻辑，并保留参与者套牌字段
        List<Map<String, Object>> views = svc.getRecentGameViews(1, "CARD", "AGGRO");
        assertEquals(1, views.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ps = (List<Map<String, Object>>) views.get(0).get("participants");
        assertTrue(ps.stream().anyMatch(m -> "AGGRO".equals(m.get("deck"))));
    }

    private static GameRecord cardRecord(String roomId, String deckA, String deckB, long endedAt) {
        GameRecord rec = new GameRecord();
        rec.setGameType("CARD");
        rec.setRoomId(roomId);
        rec.setEndedAt(endedAt);
        rec.setMoveCount(12);
        rec.setRanked(false);
        GameRecord.Participant a = new GameRecord.Participant(
                "a", "甲", 1, "WIN", false, 1200, 1200, 0);
        a.setDeck(deckA);
        a.setDeckLabel(deckA);
        GameRecord.Participant b = new GameRecord.Participant(
                "b", "乙", 2, "LOSE", false, 1200, 1200, 0);
        b.setDeck(deckB);
        b.setDeckLabel(deckB);
        rec.getParticipants().add(a);
        rec.getParticipants().add(b);
        return rec;
    }

    private static boolean hasDeck(GameRecord rec, String deck) {
        return rec.getParticipants().stream()
                .anyMatch(p -> deck.equals(p.getDeck()));
    }

    @Test
    void illegalIdCannotEscapeDirectory() throws Exception {
        GameRecordService svc = newService();
        assertNull(svc.getRecord("../../etc/passwd"), "带路径分隔符的 ID 应被拒绝");
        assertNull(svc.getRecord("a/b"), "斜杠应被拒绝");
        assertNull(svc.getRecord(""), "空 ID 应返回 null");
    }

    private static GameRecord.Participant findByName(GameRecord rec, String name) {
        for (GameRecord.Participant p : rec.getParticipants()) {
            if (name.equals(p.getName())) return p;
        }
        return null;
    }
}
