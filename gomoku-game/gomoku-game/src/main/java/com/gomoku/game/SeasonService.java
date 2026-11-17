package com.gomoku.game;

import org.springframework.stereotype.Service;

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

    public SeasonService(GameRecordService records) {
        this.records = records;
    }

    /** 当前赛季信息（月份、起止、剩余天数、个人赛季数据与段位、赛季榜） */
    public Map<String, Object> current(String identityId, int topN) {
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
