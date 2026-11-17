package com.gomoku.game;

import com.gomoku.model.Player;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AI 与排行榜隔离测试
 *
 * 背景：AI 在房间里是一个普通 Player 对象，早期实现无差别地为它注册 ELO 默认分，
 * 结果「电脑」以 1200 分混进真人排行榜，占掉名额。这里钉住三点：
 * 1. AI 玩家带 isAi 标记
 * 2. AI 不进排行榜
 * 3. AI 局不产生 ELO 变化
 */
class AiLeaderboardIsolationTest {

    @Test
    void aiPlayerIsFlagged() {
        GameRoom room = new GameRoom("ROOM-AI-FLAG", GameType.GOMOKU);
        room.addPlayer(new Player("h", "真人甲", null));
        room.addAI(2);

        Player ai = room.getPlayers().stream().filter(Player::isAi).findFirst().orElse(null);
        assertNotNull(ai, "房间里的 AI 应带 isAi 标记");
        assertEquals("电脑", ai.getName());
    }

    @Test
    void aiDoesNotAppearInLeaderboard() throws Exception {
        EloService elo = new EloService(
                Files.createTempDirectory("elo-lb").resolve("elo.properties"), null);

        GameRoom room = new GameRoom("ROOM-AI-LB", GameType.GOMOKU);
        room.setEloService(elo);
        room.addPlayer(new Player("h", "真人乙", null));
        room.addAI(2);

        // 认输制造终局
        room.surrender(room.getPlayers().get(0));

        List<Map<String, Object>> board = elo.getLeaderboard(GameType.GOMOKU, 20);
        List<String> names = board.stream().map(m -> (String) m.get("name")).collect(Collectors.toList());

        assertFalse(names.contains("电脑"), "AI 不应出现在排行榜里，实际: " + names);
        assertTrue(names.contains("真人乙"), "真人应出现在排行榜里，实际: " + names);
    }

    @Test
    void aiGameProducesNoEloChange() throws Exception {
        GameRoom room = new GameRoom("ROOM-AI-ELO", GameType.GOMOKU);
        room.setEloService(new EloService(
                Files.createTempDirectory("elo-aigame").resolve("elo.properties"), null));

        Player human = new Player("h", "真人丙", null);
        room.addPlayer(human);
        room.addAI(2);
        room.surrender(human);

        // 人类认输 → AI 胜，但 AI 局不结算，双方都不该有变化
        for (Player p : room.getPlayers()) {
            assertEquals(0, p.getLastEloChange(),
                    p.getName() + " 在 AI 局中不应产生 ELO 变化");
        }
    }

    @Test
    void legacyAiEntryIsFilteredFromLeaderboard() throws Exception {
        EloService elo = new EloService(
                Files.createTempDirectory("elo-legacy").resolve("elo.properties"), null);
        // 模拟历史数据里已经存在的「电脑」条目
        elo.updateRating(new Player("a", "电脑", null), new Player("b", "真人丁", null),
                GameType.GOMOKU);

        List<String> names = elo.getLeaderboard(GameType.GOMOKU, 20).stream()
                .map(m -> (String) m.get("name")).collect(Collectors.toList());
        assertFalse(names.contains("电脑"), "残留的 AI 条目也应被排行榜过滤，实际: " + names);
    }
}
