package com.gomoku.game;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * 赛季段位：以自然月为赛季，赛季分从对局记录派生（胜 +3 / 平 +1 / 负 0）。
 *
 * 设计取舍：
 * - 不重置、不改写 ELO。ELO 是长期累计资产，赛季只是「本窗口表现」的另一种视图，
 *   因此零数据迁移、零历史损失；玩家不会因为新赛季开始而丢失已有积分。
 * - 赛季分只认真人对局，AI 局不计，避免刷人机冲段。
 * - 段位完全由赛季分映射，规则公开透明，随时可从记录复算。
 */
@Service
public class SeasonService {
    /** 段位阶梯：累计赛季分下限 -> 名称，从低到高 */
    private static final int[] TIER_FLOORS = {0, 10, 25, 50, 90, 150};
    private static final String[] TIER_NAMES = {"青铜", "白银", "黄金", "铂金", "钻石", "王者"};

    private final GameRecordService records;
    private final Path path;
    private final Gson gson = new Gson();

    /** 上一次已结算的赛季 id（yyyy-MM）；为空表示从未结算 */
    private String lastSettledSeasonId;
    /** identityId -> 赛季 id -> 结算快照（分数、段位、结算时间） */
    private final Map<String, Map<String, Map<String, Object>>> settlements = new HashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public SeasonService(GameRecordService records) {
        this(records, Paths.get("data", "seasons.json"));
    }

    /** 供测试注入存储路径 */
    public SeasonService(GameRecordService records, Path path) {
        this.records = records;
        this.path = path;
    }

    @PostConstruct
    public synchronized void init() {
        if (!Files.exists(path)) return;
        try {
            Map<String, Object> stored = gson.fromJson(Files.readString(path),
                    new TypeToken<Map<String, Object>>() {}.getType());
            if (stored == null) return;
            Object last = stored.get("lastSettledSeasonId");
            if (last instanceof String) lastSettledSeasonId = (String) last;
            Object players = stored.get("players");
            @SuppressWarnings("unchecked")
            Map<String, Object> playersMap = (Map<String, Object>) players;
            if (playersMap == null) return;
            for (Map.Entry<String, Object> e : playersMap.entrySet()) {
                if (!(e.getValue() instanceof Map)) continue;
                Map<String, Map<String, Object>> seasons = new HashMap<>();
                for (Map.Entry<String, Object> se : ((Map<String, Object>) e.getValue()).entrySet()) {
                    if (se.getValue() instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> snapshot = (Map<String, Object>) se.getValue();
                        seasons.put(se.getKey(), snapshot);
                    }
                }
                settlements.put(e.getKey(), seasons);
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法读取赛季结算文件", e);
        }
    }

    /**
     * 结算上一赛季：月切换后的第一次查询触发。
     *
     * - 只结算「上一个自然月」：更早的月份在服务升级前已错过窗口，跳过（无历史可补）；
     * - 结算一次后记录 lastSettledSeasonId，本自然月内幂等；
     * - 榜单按持久身份聚合，AI 不入榜（复用 getSeasonResults 的过滤）。
     */
    private synchronized void settlePastSeasons() {
        LocalDate today = today();
        LocalDate prevMonth = today.minusMonths(1).withDayOfMonth(1);
        String seasonId = seasonIdOf(prevMonth);
        if (seasonId.equals(lastSettledSeasonId)) return;

        long since = prevMonth.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long until = prevMonth.plusMonths(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        for (Map<String, Object> row : records.getSeasonResults(since, until)) {
            String identityId = String.valueOf(row.get("identityId"));
            int points = row.get("points") instanceof Number ? ((Number) row.get("points")).intValue() : 0;
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("seasonId", seasonId);
            snapshot.put("points", points);
            snapshot.put("tier", tierFor(points).get("tier"));
            snapshot.put("games", row.getOrDefault("games", 0));
            snapshot.put("wins", row.getOrDefault("wins", 0));
            snapshot.put("settledAt", System.currentTimeMillis());
            settlements.computeIfAbsent(identityId, k -> new HashMap<>()).put(seasonId, snapshot);
        }
        lastSettledSeasonId = seasonId;
        persist();
    }

    /** 某身份的赛季称号历史（按赛季倒序），供「我的赛季称号」展示 */
    public synchronized List<Map<String, Object>> settlementHistory(String identityId) {
        List<Map<String, Object>> history = new ArrayList<>();
        Map<String, Map<String, Object>> seasons = identityId == null ? null : settlements.get(identityId);
        if (seasons == null) return history;
        seasons.values().stream()
                .sorted(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("seasonId"))).reversed())
                .forEach(history::add);
        return history;
    }

    private static String seasonIdOf(LocalDate month) {
        return month.getYear() + "-" + String.format("%02d", month.getMonthValue());
    }

    private void persist() {
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("lastSettledSeasonId", lastSettledSeasonId);
            stored.put("players", settlements);
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, gson.toJson(stored), StandardCharsets.UTF_8);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) {
            throw new IllegalStateException("无法保存赛季结算", e);
        }
    }

    /** 当前赛季信息（月份、起止、剩余天数、个人赛季数据与段位、赛季榜） */
    public Map<String, Object> current(String identityId, int topN) {
        settlePastSeasons();
        LocalDate today = today();
        LocalDate seasonStart = today.withDayOfMonth(1);
        LocalDate seasonEnd = seasonStart.plusMonths(1);
        long since = seasonStart.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long until = seasonEnd.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();

        Map<String, Object> season = new LinkedHashMap<>();
        season.put("id", seasonStart.getYear() + "-" + String.format("%02d", seasonStart.getMonthValue()));
        season.put("label", seasonStart.getYear() + " 年 " + seasonStart.getMonthValue() + " 月赛季");
        season.put("startDate", seasonStart.toString());
        season.put("endDate", seasonEnd.minusDays(1).toString());
        season.put("daysLeft", (int) java.time.temporal.ChronoUnit.DAYS.between(today, seasonEnd));
        Map<String, Object> me = records.getSeasonStats(identityId, since, until);
        int points = intOf(me.get("points"));
        Map<String, Object> tier = tierFor(points);
        me.putAll(tier);
        season.put("me", me);

        List<Map<String, Object>> board = records.getSeasonLeaderboard(since, until, topN);
        // 给榜单每行补段位，前端无需重复实现阶梯
        for (Map<String, Object> row : board) row.putAll(tierFor(intOf(row.get("points"))));
        season.put("leaderboard", board);
        return season;
    }

    /** 段位映射：返回名称、当前段位下限、下一段位下限与进度 */
    static Map<String, Object> tierFor(int points) {
        int index = 0;
        for (int i = 0; i < TIER_FLOORS.length; i++) {
            if (points >= TIER_FLOORS[i]) index = i; else break;
        }
        int floor = TIER_FLOORS[index];
        boolean maxed = index == TIER_FLOORS.length - 1;
        int nextFloor = maxed ? floor : TIER_FLOORS[index + 1];
        int progress = maxed ? 100 : (int) Math.min(100, Math.round((points - floor) * 100.0 / (nextFloor - floor)));
        Map<String, Object> tier = new LinkedHashMap<>();
        tier.put("tier", TIER_NAMES[index]);
        tier.put("tierIndex", index);
        tier.put("tierFloor", floor);
        tier.put("nextTierFloor", nextFloor);
        tier.put("nextTier", maxed ? TIER_NAMES[index] : TIER_NAMES[index + 1]);
        tier.put("tierProgress", progress);
        tier.put("maxTier", maxed);
        return tier;
    }

    /** 当前日期；测试可子类覆盖 */
    protected LocalDate today() {
        return LocalDate.now(ZoneId.systemDefault());
    }

    private static int intOf(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }
}
