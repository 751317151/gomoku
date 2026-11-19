package com.gomoku.game;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * 运营数据：日活趋势、留存、玩法热度与时段分布。
 *
 * 为什么从对局记录派生而不是另建埋点表：
 * - 项目已有 5000 条摘要索引，覆盖足够长的窗口，且「打了一局」是当前唯一有意义的活跃信号
 *   （大厅停留时长没有采集，凭空造一个数字只会误导运营）。
 * - 纯派生意味着零迁移、零额外写入，也不会因为埋点丢事件而失真。
 *
 * 口径（写在返回值里，避免看板数字被误读）：
 * - 活跃 = 当天有真人参与的对局（AI 局不算，否则挂机刷人机就能刷高日活）
 * - 留存 D1/D7 = 窗口首日活跃用户中，次日/第 7 日仍有真人对局的比例
 * - 玩法热度 = 窗口内该玩法真人对局局数
 */
@Service
public class OpsMetricsService {

    private final GameRecordService records;

    public OpsMetricsService(GameRecordService records) {
        this.records = records;
    }

    /** 当前时间（测试可覆盖） */
    long now() { return System.currentTimeMillis(); }

    /** 时区（测试可覆盖，保证按自然日聚合） */
    ZoneId zone() { return ZoneId.systemDefault(); }

    /**
     * 运营总览。
     *
     * @param days 统计窗口天数（含今天），限制在 1–90
     */
    public Map<String, Object> overview(int days) {
        int window = Math.max(1, Math.min(days, 90));
        List<GameRecord> all = records.snapshotRecentRecords();
        long now = now();
        ZoneId zone = zone();
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        LocalDate from = today.minusDays(window - 1L);

        // 按自然日聚合：活跃身份集合、局数、玩法计数、参与人次
        Map<LocalDate, Set<String>> dailyUsers = new LinkedHashMap<>();
        Map<LocalDate, Integer> dailyGames = new LinkedHashMap<>();
        Map<String, Integer> gameTypeGames = new LinkedHashMap<>();
        Map<String, Integer> gameTypePlayers = new LinkedHashMap<>();
        Map<Integer, Integer> hourly = new LinkedHashMap<>();
        for (int h = 0; h < 24; h++) hourly.put(h, 0);
        Set<String> windowUsers = new HashSet<>();
        int totalGames = 0;
        int rankedGames = 0;

        for (GameRecord record : all) {
            long ended = record.getEndedAt();
            if (ended <= 0) continue;
            LocalDate day = Instant.ofEpochMilli(ended).atZone(zone).toLocalDate();
            if (day.isBefore(from) || day.isAfter(today)) continue;

            Set<String> dayUsers = dailyUsers.computeIfAbsent(day, d -> new HashSet<>());
            Set<String> gameUsers = new HashSet<>();
            for (GameRecord.Participant p : record.getParticipants()) {
                if (p.isAi() || p.getIdentityId() == null) continue;
                dayUsers.add(p.getIdentityId());
                gameUsers.add(p.getIdentityId());
                windowUsers.add(p.getIdentityId());
            }
            // 全员 AI 的局不计入运营口径（真人观战不算活跃）
            if (gameUsers.isEmpty()) continue;

            totalGames++;
            if (record.isRanked()) rankedGames++;
            dailyGames.merge(day, 1, Integer::sum);
            String type = record.getGameType() == null ? "UNKNOWN" : record.getGameType();
            gameTypeGames.merge(type, 1, Integer::sum);
            gameTypePlayers.merge(type, gameUsers.size(), Integer::sum);
            int hour = Instant.ofEpochMilli(ended).atZone(zone).getHour();
            hourly.merge(hour, 1, Integer::sum);
        }

        // 逐日趋势（缺失日补 0，前端画折线不需要自己对齐）
        List<Map<String, Object>> trend = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", d.toString());
            row.put("users", dailyUsers.getOrDefault(d, Collections.emptySet()).size());
            row.put("games", dailyGames.getOrDefault(d, 0));
            trend.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("windowDays", window);
        result.put("from", from.toString());
        result.put("to", today.toString());
        result.put("activeUsers", windowUsers.size());
        result.put("todayActive", dailyUsers.getOrDefault(today, Collections.emptySet()).size());
        result.put("totalGames", totalGames);
        result.put("rankedGames", rankedGames);
        result.put("trend", trend);
        result.put("gameTypes", gameTypeRows(gameTypeGames, gameTypePlayers, totalGames));
        result.put("hourly", hourlyRows(hourly));
        result.put("retention", retention(dailyUsers, today, from));
        result.put("definitions", definitions());
        return result;
    }

    /** 玩法排行：局数降序，附占比与平均参与人数 */
    private static List<Map<String, Object>> gameTypeRows(Map<String, Integer> games,
                                                          Map<String, Integer> players,
                                                          int total) {
        List<Map<String, Object>> rows = new ArrayList<>();
        games.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("gameType", e.getKey());
                    row.put("games", e.getValue());
                    row.put("share", total == 0 ? 0 : Math.round(e.getValue() * 1000.0 / total) / 10.0);
                    int count = e.getValue();
                    int people = players.getOrDefault(e.getKey(), 0);
                    row.put("avgPlayers", count == 0 ? 0 : Math.round(people * 10.0 / count) / 10.0);
                    rows.add(row);
                });
        return rows;
    }

    /** 时段分布：把 24 小时压成 8 个 3 小时段，避免看板过长 */
    private static List<Map<String, Object>> hourlyRows(Map<Integer, Integer> hourly) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int slot = 0; slot < 8; slot++) {
            int games = 0;
            for (int h = slot * 3; h < slot * 3 + 3; h++) games += hourly.getOrDefault(h, 0);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slot", String.format("%02d:00-%02d:59", slot * 3, slot * 3 + 2));
            row.put("games", games);
            rows.add(row);
        }
        return rows;
    }

    /**
     * 留存：以窗口首日为基准日（cohort），看该日活跃用户后续回访。
     * 首日太近（今天）时 D1 无意义，返回 null 而不是 0，避免误读成「留存为零」。
     */
    private static Map<String, Object> retention(Map<LocalDate, Set<String>> dailyUsers,
                                                 LocalDate today, LocalDate from) {
        Set<String> cohort = dailyUsers.getOrDefault(from, Collections.emptySet());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cohortDate", from.toString());
        result.put("cohortSize", cohort.size());
        result.put("d1", retentionRate(cohort, dailyUsers.get(from.plusDays(1)), today, from.plusDays(1)));
        result.put("d7", retentionRate(cohort, dailyUsers.get(from.plusDays(7)), today, from.plusDays(7)));
        return result;
    }

    private static Object retentionRate(Set<String> cohort, Set<String> returned,
                                        LocalDate today, LocalDate targetDay) {
        if (cohort.isEmpty() || targetDay.isAfter(today)) return null;
        if (returned == null || returned.isEmpty()) return 0.0;
        int retained = 0;
        for (String id : cohort) if (returned.contains(id)) retained++;
        return Math.round(retained * 1000.0 / cohort.size()) / 10.0;
    }

    private static List<String> definitions() {
        return Arrays.asList(
                "活跃用户 = 当天参与过真人对局的持久身份（AI 局不计）",
                "留存 D1/D7 = 窗口首日活跃用户中次日/第 7 日仍活跃的比例",
                "玩法热度 = 窗口内该玩法真人对局局数",
                "时段分布按服务器本地时区，3 小时一段"
        );
    }
}
