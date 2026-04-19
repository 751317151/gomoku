package com.gomoku.game;

import com.gomoku.model.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ELO 积分服务 — 计算引擎 + 文件持久化
 *
 * ELO 算法：
 *   Ea = 1 / (1 + 10^((Rb - Ra) / 400))
 *   Ra' = Ra + K * (Sa - Ea)    Sa = 实际得分 (胜1, 负0, 平0.5)
 *
 * 默认参数：初始 1200，K=32
 */
@Service
public class EloService {

    private static final Logger logger = LoggerFactory.getLogger(EloService.class);
    private static final int DEFAULT_ELO = 1200;
    private static final int K_FACTOR = 32;

    // 玩家名 -> ELO 积分
    private final ConcurrentHashMap<String, Integer> ratings = new ConcurrentHashMap<>();
    private final Path storagePath;

    public EloService() {
        this.storagePath = Paths.get("elo_ratings.json");
        loadRatings();
    }

    /**
     * 获取玩家 ELO（不存在则初始化为默认值）
     */
    public int getRating(String playerName) {
        return ratings.computeIfAbsent(playerName, k -> DEFAULT_ELO);
    }

    /**
     * 更新双方 ELO 积分，返回 [winnerNewElo, loserNewElo, eloChange]
     */
    public int[] updateRating(Player winner, Player loser) {
        String winnerName = winner.getName();
        String loserName = loser.getName();
        int ra = getRating(winnerName);
        int rb = getRating(loserName);

        double ea = 1.0 / (1.0 + Math.pow(10, (rb - ra) / 400.0));
        int change = (int) Math.round(K_FACTOR * (1.0 - ea));

        int newWinnerElo = ra + change;
        int newLoserElo = rb - change;

        ratings.put(winnerName, newWinnerElo);
        ratings.put(loserName, newLoserElo);

        winner.setElo(newWinnerElo);
        loser.setElo(newLoserElo);

        saveRatings();
        logger.info("[ELO] {} {}(+{}) vs {} {}(-{})",
                winnerName, newWinnerElo, change, loserName, newLoserElo, change);

        return new int[]{newWinnerElo, newLoserElo, change};
    }

    /**
     * 平局时更新双方 ELO
     */
    public void updateDraw(Player p1, Player p2) {
        String n1 = p1.getName(), n2 = p2.getName();
        int r1 = getRating(n1), r2 = getRating(n2);

        double ea1 = 1.0 / (1.0 + Math.pow(10, (r2 - r1) / 400.0));
        double ea2 = 1.0 / (1.0 + Math.pow(10, (r1 - r2) / 400.0));

        int change1 = (int) Math.round(K_FACTOR * (0.5 - ea1));
        int change2 = (int) Math.round(K_FACTOR * (0.5 - ea2));

        int newR1 = r1 + change1;
        int newR2 = r2 + change2;

        ratings.put(n1, newR1);
        ratings.put(n2, newR2);
        p1.setElo(newR1);
        p2.setElo(newR2);

        saveRatings();
    }

    /**
     * 获取排行榜（按 ELO 降序，最多 top N）
     */
    public List<Map<String, Object>> getLeaderboard(int topN) {
        return ratings.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(topN)
                .collect(ArrayList::new,
                        (list, entry) -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("name", entry.getKey());
                            row.put("elo", entry.getValue());
                            list.add(row);
                        },
                        ArrayList::addAll);
    }

    public String getLeaderboardJson() {
        return new com.google.gson.Gson().toJson(getLeaderboard(20));
    }

    // ============ 持久化 ============

    private void loadRatings() {
        if (!Files.exists(storagePath)) return;
        try (Reader reader = Files.newBufferedReader(storagePath)) {
            Properties props = new Properties();
            props.load(reader);
            for (String key : props.stringPropertyNames()) {
                try {
                    ratings.put(key, Integer.parseInt(props.getProperty(key)));
                } catch (NumberFormatException ignored) {}
            }
            logger.info("ELO 数据已加载，共 {} 条记录", ratings.size());
        } catch (IOException e) {
            logger.warn("ELO 数据加载失败", e);
        }
    }

    private void saveRatings() {
        try (Writer writer = Files.newBufferedWriter(storagePath)) {
            Properties props = new Properties();
            ratings.forEach((name, elo) -> props.setProperty(name, String.valueOf(elo)));
            props.store(writer, "ELO Ratings");
        } catch (IOException e) {
            logger.warn("ELO 数据保存失败", e);
        }
    }
}
