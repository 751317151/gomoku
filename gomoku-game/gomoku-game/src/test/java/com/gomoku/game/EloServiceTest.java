package com.gomoku.game;

import com.gomoku.model.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ELO 服务测试：分游戏积分隔离 / 持久化 / 旧格式迁移
 */
class EloServiceTest {

    @TempDir
    Path tempDir;

    private Player player(String name, int elo) {
        Player p = new Player("id-" + name, name, null);
        p.setElo(elo);
        return p;
    }

    @Test
    void ratingsIsolatedPerGameType() {
        Path storage = tempDir.resolve("elo.properties");
        EloService service = new EloService(storage, null);

        int initial = service.getRating("Alice", GameType.GOMOKU);
        assertEquals(1200, initial);

        // Alice 在五子棋池获胜
        service.updateRating(player("Alice", 1200), player("Bob", 1200), GameType.GOMOKU);

        assertTrue(service.getRating("Alice", GameType.GOMOKU) > 1200, "五子棋积分应上涨");
        assertTrue(service.getRating("Bob", GameType.GOMOKU) < 1200, "败者积分应下降");
        assertEquals(1200, service.getRating("Alice", GameType.REVERSI), "黑白棋池不受影响");
        assertEquals(1200, service.getRating("Alice", GameType.GO9), "围棋池不受影响");
    }

    @Test
    void updateRatingIsSymmetric() {
        Path storage = tempDir.resolve("elo.properties");
        EloService service = new EloService(storage, null);

        Player winner = player("W", 1200);
        Player loser = player("L", 1200);
        int[] result = service.updateRating(winner, loser, GameType.GOMOKU);

        assertEquals(winner.getElo(), result[0]);
        assertEquals(loser.getElo(), result[1]);
        assertEquals(result[2], result[0] - 1200);
        assertEquals(result[2], 1200 - result[1]);
        assertTrue(result[2] > 0);
        assertEquals(1200 + (1200 - result[1]), result[0], "零和校验");
    }

    @Test
    void updateDrawChangesBoth() {
        Path storage = tempDir.resolve("elo.properties");
        EloService service = new EloService(storage, null);

        Player p1 = player("P1", 1400);
        Player p2 = player("P2", 1000);
        service.updateDraw(p1, p2, GameType.REVERSI);

        assertTrue(p1.getElo() < 1400, "高分方平局应掉分");
        assertTrue(p2.getElo() > 1000, "低分方平局应涨分");
    }

    @Test
    void persistenceAcrossInstances() throws Exception {
        Path storage = tempDir.resolve("elo.properties");
        EloService first = new EloService(storage, null);
        first.updateRating(player("Carol", 1200), player("Dave", 1200), GameType.GO9);
        int carolElo = first.getRating("Carol", GameType.GO9);
        assertTrue(Files.exists(storage), "应写入存储文件");

        EloService second = new EloService(storage, null);
        assertEquals(carolElo, second.getRating("Carol", GameType.GO9), "重启后积分应保留");
    }

    @Test
    void legacyFileMigratedAsGomoku() throws Exception {
        Path legacy = tempDir.resolve("elo_ratings.json");
        Path storage = tempDir.resolve("data").resolve("elo.properties");
        // 旧格式：Properties 格式、无游戏后缀
        Files.write(legacy, ("#ELO Ratings\n" +
                "OldPlayer=1350\n" +
                "Another=1100\n").getBytes());

        EloService service = new EloService(storage, legacy);

        assertEquals(1350, service.getRating("OldPlayer", GameType.GOMOKU), "旧记录应迁移为五子棋积分");
        assertEquals(1200, service.getRating("OldPlayer", GameType.REVERSI), "其他游戏仍为默认分");
        assertTrue(Files.exists(storage), "迁移后应写入新文件");
    }

    @Test
    void leaderboardFilteredByGameType() {
        Path storage = tempDir.resolve("elo.properties");
        EloService service = new EloService(storage, null);

        service.updateRating(player("Alice", 1200), player("Bob", 1200), GameType.GOMOKU);
        service.updateRating(player("Carol", 1200), player("Dave", 1200), GameType.REVERSI);

        List<Map<String, Object>> gomokuBoard = service.getLeaderboard(GameType.GOMOKU, 10);
        assertEquals(2, gomokuBoard.size());
        assertTrue(gomokuBoard.stream().allMatch(r -> {
            String n = (String) r.get("name");
            return n.equals("Alice") || n.equals("Bob");
        }), "五子棋榜不应混入其他游戏玩家");

        assertTrue((int) gomokuBoard.get(0).get("elo") >= (int) gomokuBoard.get(1).get("elo"), "降序排列");
    }

    @Test
    void leaderboardRankingOrder() {
        Path storage = tempDir.resolve("elo.properties");
        EloService service = new EloService(storage, null);

        // 制造分差
        for (int i = 0; i < 5; i++) {
            service.updateRating(player("Winner", 1200), player("Loser", 1200), GameType.REVERSI);
        }

        List<Map<String, Object>> board = service.getLeaderboard(GameType.REVERSI, 10);
        assertEquals(2, board.size());
        assertEquals("Winner", board.get(0).get("name"), "胜者应排第一");
    }
}
