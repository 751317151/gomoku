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
 * ELO 积分服务 — 计算引擎 + 文件持久化（按游戏类型分池）
 *
 * ELO 算法：
 *   Ea = 1 / (1 + 10^((Rb - Ra) / 400))
 *   Ra' = Ra + K * (Sa - Ea)    Sa = 实际得分 (胜1, 负0, 平0.5)
 *
 * 默认参数：初始 1200，K=32
 * 存储：data/elo_ratings.properties，key = "玩家名#GAMETYPE"
 *       （旧版 elo_ratings.json 为无后缀 key，启动时自动迁移为五子棋积分）
 */
@Service
public class EloService {

    private static final Logger logger = LoggerFactory.getLogger(EloService.class);
    private static final int DEFAULT_ELO = 1200;
    private static final int K_FACTOR = 32;
    private static final char KEY_SEPARATOR = '#';

    // "玩家名#GAMETYPE" -> ELO 积分
    private final ConcurrentHashMap<String, String> displayNames = new ConcurrentHashMap<>();
    public int getRating(Player player, GameType type) {
        displayNames.put(player.getRatingKey(), player.getName());
        return getRating(player.getRatingKey(), type);
    }
    private final ConcurrentHashMap<String, Integer> ratings = new ConcurrentHashMap<>();
    private final Path storagePath;
    private final Path legacyPath;

    public EloService() {
        this(Paths.get("data", "elo_ratings.properties"), Paths.get("elo_ratings.json"));
    }

    /** 供测试注入自定义路径 */
    EloService(Path storagePath, Path legacyPath) {
        this.storagePath = storagePath;
        this.legacyPath = legacyPath;
        loadRatings();
    }

    private static String key(String playerName, GameType type) {
        return playerName + KEY_SEPARATOR + type.name();
    }

    /**
     * 获取玩家在某游戏下的 ELO（不存在则初始化为默认值）
     */
    public int getRating(String playerName, GameType type) {
        return ratings.computeIfAbsent(key(playerName, type), k -> DEFAULT_ELO);
    }

    /**
     * 更新双方 ELO 积分，返回 [winnerNewElo, loserNewElo, eloChange]
     */
    public int[] updateRating(Player winner, Player loser, GameType type) {
        String winnerKey = key(winner.getRatingKey(), type);
        String loserKey = key(loser.getRatingKey(), type);
        int ra = getRating(winner.getRatingKey(), type);
        int rb = getRating(loser.getRatingKey(), type);

        double ea = 1.0 / (1.0 + Math.pow(10, (rb - ra) / 400.0));
        int change = (int) Math.round(K_FACTOR * (1.0 - ea));

        int newWinnerElo = ra + change;
        int newLoserElo = rb - change;

        ratings.put(winnerKey, newWinnerElo);
        ratings.put(loserKey, newLoserElo);

        winner.setElo(newWinnerElo);
        loser.setElo(newLoserElo);

        saveRatings();
        logger.info("[ELO][{}] {} {}(+{}) vs {} {}(-{})",
                type, winner.getName(), newWinnerElo, change, loser.getName(), newLoserElo, change);

        return new int[]{newWinnerElo, newLoserElo, change};
    }

    /**
     * 平局时更新双方 ELO
     */
    public void updateDraw(Player p1, Player p2, GameType type) {
        int r1 = getRating(p1.getRatingKey(), type);
        int r2 = getRating(p2.getRatingKey(), type);

        double ea1 = 1.0 / (1.0 + Math.pow(10, (r2 - r1) / 400.0));
        double ea2 = 1.0 / (1.0 + Math.pow(10, (r1 - r2) / 400.0));

        int change1 = (int) Math.round(K_FACTOR * (0.5 - ea1));
        int change2 = (int) Math.round(K_FACTOR * (0.5 - ea2));

        int newR1 = r1 + change1;
        int newR2 = r2 + change2;

        ratings.put(key(p1.getRatingKey(), type), newR1);
        ratings.put(key(p2.getRatingKey(), type), newR2);
        p1.setElo(newR1);
        p2.setElo(newR2);

        saveRatings();
    }

    /**
     * 获取某游戏的排行榜（按 ELO 降序，最多 top N）
     *
     * AI 不应出现在真人榜里。新版已在写入侧跳过 AI（GameRoom.addPlayer 只为真人注册积分），
     * 但历史数据里可能残留「电脑」这类条目（旧版本无差别注册），所以此处按 AI 昵称兜底过滤。
     */
    public List<Map<String, Object>> getLeaderboard(GameType type, int topN) {
        String suffix = KEY_SEPARATOR + type.name();
        return ratings.entrySet().stream()
                .filter(e -> e.getKey().endsWith(suffix))
                .filter(e -> !isAiName(e.getKey().substring(0, e.getKey().length() - suffix.length())))
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(topN)
                .collect(ArrayList::new,
                        (list, entry) -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            String identity = entry.getKey().substring(0, entry.getKey().length() - suffix.length());
                            row.put("name", displayNames.getOrDefault(identity, identity.startsWith("guest-") ? "游客" : identity));
                            row.put("elo", entry.getValue());
                            list.add(row);
                        },
                        ArrayList::addAll);
    }

    /** AI 玩家的固定昵称（见 GameRoom.addAI） */
    private static final String AI_NAME = "电脑";

    private static boolean isAiName(String name) {
        return AI_NAME.equals(name) || name.startsWith("AI-") || name.endsWith("(AI)");
    }

    // ============ 持久化 ============

    private void loadRatings() {
        boolean migrated = false;
        // 旧版文件：无后缀 key（视为五子棋积分）
        if (legacyPath != null && Files.exists(legacyPath)) {
            Map<String, Integer> legacy = readProperties(legacyPath);
            for (Map.Entry<String, Integer> e : legacy.entrySet()) {
                String name = e.getKey();
                if (name.indexOf(KEY_SEPARATOR) < 0) {
                    ratings.putIfAbsent(key(name, GameType.GOMOKU), e.getValue());
                }
            }
            logger.info("已读取旧版 ELO 文件 {}，共 {} 条（视为五子棋积分）", legacyPath, legacy.size());
            migrated = true;
        }
        // 新版文件：带后缀 key
        if (Files.exists(storagePath)) {
            Map<String, Integer> stored = readProperties(storagePath);
            try (Reader reader = Files.newBufferedReader(storagePath)) {
                Properties props = new Properties(); props.load(reader);
                for (String k : props.stringPropertyNames()) if (k.startsWith("display:")) displayNames.put(k.substring(8), props.getProperty(k));
            } catch (IOException e) { logger.warn("无法读取游客昵称", e); }
            for (Map.Entry<String, Integer> e : stored.entrySet()) {
                if (e.getKey().indexOf(KEY_SEPARATOR) >= 0) {
                    ratings.put(e.getKey(), e.getValue());
                }
            }
            logger.info("ELO 数据已加载（{}）：共 {} 条记录", storagePath, ratings.size());
        } else if (migrated) {
            saveRatings(); // 迁移落盘到新路径
            logger.info("ELO 数据已迁移至 {}", storagePath);
        }
    }

    private Map<String, Integer> readProperties(Path path) {
        Map<String, Integer> result = new LinkedHashMap<>();
        try (Reader reader = Files.newBufferedReader(path)) {
            Properties props = new Properties();
            props.load(reader);
            for (String k : props.stringPropertyNames()) {
                try {
                    result.put(k, Integer.parseInt(props.getProperty(k)));
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException e) {
            logger.warn("ELO 数据读取失败: {}", path, e);
        }
        return result;
    }

    private void saveRatings() {
        try {
            if (storagePath.getParent() != null) {
                Files.createDirectories(storagePath.getParent());
            }
            try (Writer writer = Files.newBufferedWriter(storagePath)) {
                Properties props = new Properties();
                ratings.forEach((k, elo) -> props.setProperty(k, String.valueOf(elo)));
                displayNames.forEach((k, name) -> props.setProperty("display:" + k, name));
                props.store(writer, "ELO Ratings (name#GAMETYPE)");
            }
        } catch (IOException e) {
            logger.warn("ELO 数据保存失败", e);
        }
    }
}
