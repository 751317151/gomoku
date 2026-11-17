package com.gomoku.game;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * 签到与留存奖励：以独立文件记录签到日期，不依赖对局记录。
 *
 * 设计取舍：
 * - 对局记录只保留最近 5000 条，连续活跃天数会因老记录滚出内存而断层；
 *   签到记录单独存，且「今天来点一下」本身就该算活跃，不必先打一局。
 * - 奖励只换算成经验值叠加到等级体系，不写成对局记录：
 *   因此对局数、胜率、ELO、排行榜都不受影响，不能靠签到刷竞技战绩。
 * - 连续里程碑与回归礼包都有明确门槛，避免每日无脑领同一份奖励。
 */
@Service
public class CheckInService {
    /** 签到记录最多保留多少天日期，防止文件无限增长（足够覆盖最长连续里程碑） */
    private static final int MAX_DAYS = 400;
    /** 断签达到该天数后回来签到，视为回归并发礼包 */
    private static final int COMEBACK_GAP_DAYS = 3;

    /** 每日基础签到经验 */
    private static final int BASE_XP = 15;
    /** 回归礼包经验 */
    private static final int COMEBACK_XP = 50;
    /** 连续签到里程碑：连续天数 -> 额外经验 */
    private static final LinkedHashMap<Integer, Integer> MILESTONES = new LinkedHashMap<>();
    static {
        MILESTONES.put(3, 30);
        MILESTONES.put(7, 80);
        MILESTONES.put(14, 150);
        MILESTONES.put(30, 400);
    }

    private final Path path;
    private final Gson gson = new Gson();
    private final Map<String, CheckIn> checkIns = new LinkedHashMap<>();

    public CheckInService() {
        this(Paths.get("data", "checkins.json"));
    }

    /** 供测试注入临时路径 */
    public CheckInService(Path path) {
        this.path = path;
    }

    @PostConstruct
    public synchronized void init() {
        if (!Files.exists(path)) return;
        try {
            Map<String, CheckIn> stored = gson.fromJson(
                    Files.readString(path), new TypeToken<Map<String, CheckIn>>() {}.getType());
            if (stored == null) throw new IllegalStateException("签到文件为空");
            checkIns.clear();
            checkIns.putAll(stored);
        } catch (Exception e) {
            throw new IllegalStateException("无法读取签到文件", e);
        }
    }

    /** 当前日期；测试可子类覆盖以模拟连续签到与回归场景 */
    protected LocalDate today() {
        return LocalDate.now(ZoneId.systemDefault());
    }

    /**
     * 执行签到。同一天重复签到不会重复发奖。
     * 返回本次奖励明细与当前连续天数；identityId 无效时返回 null。
     */
    public synchronized Map<String, Object> checkIn(String identityId) {
        if (identityId == null || identityId.isEmpty()) return null;
        LocalDate today = today();
        CheckIn record = checkIns.get(identityId);
        boolean existed = record != null;
        if (record == null) {
            record = new CheckIn();
            checkIns.put(identityId, record);
        }
        if (record.dates != null && record.dates.contains(today.toString())) {
            Map<String, Object> result = summary(record, 0, false, 0, false);
            result.put("alreadySigned", true);
            return result;
        }

        int bonusXp = BASE_XP;
        // 连续天数：昨天签过则延续，否则从 1 重新开始
        int streak = 1;
        if (record.dates != null && !record.dates.isEmpty()) {
            LocalDate last = LocalDate.parse(record.dates.get(record.dates.size() - 1));
            if (last.equals(today.minusDays(1))) {
                streak = record.streak + 1;
            }
        }

        // 回归礼包：上次签到距今天达到门槛，且不是首次签到
        boolean comeback = false;
        if (record.dates != null && !record.dates.isEmpty()) {
            LocalDate last = LocalDate.parse(record.dates.get(record.dates.size() - 1));
            long gap = java.time.temporal.ChronoUnit.DAYS.between(last, today);
            if (gap >= COMEBACK_GAP_DAYS) {
                comeback = true;
                bonusXp += COMEBACK_XP;
            }
        }

        // 连续里程碑：每个里程碑只在同一连续周期内领一次，断签重置后需重新达成
        int milestone = 0;
        for (Map.Entry<Integer, Integer> entry : MILESTONES.entrySet()) {
            if (streak == entry.getKey()) {
                bonusXp += entry.getValue();
                milestone = entry.getKey();
                break;
            }
        }

        CheckIn before = copyOf(record);
        if (record.dates == null) record.dates = new ArrayList<>();
        record.dates.add(today.toString());
        while (record.dates.size() > MAX_DAYS) record.dates.remove(0);
        record.streak = streak;
        record.longest = Math.max(record.longest, streak);
        record.totalDays++;
        record.bonusXp += bonusXp;
        persistOrRollback(identityId, before, existed);

        Map<String, Object> result = summary(record, bonusXp, comeback, milestone, false);
        result.put("alreadySigned", false);
        return result;
    }

    /** 查询签到状态，不发奖。未签到过返回默认状态。 */
    public synchronized Map<String, Object> status(String identityId) {
        if (identityId == null || identityId.isEmpty()) return null;
        CheckIn record = checkIns.get(identityId);
        if (record == null) record = new CheckIn();
        LocalDate today = today();
        boolean signedToday = record.dates != null && record.dates.contains(today.toString());
        // 今天还没签时，展示若今天签上将达到的连续天数，给用户「再签一天就到里程碑」的期待
        int projectedStreak = signedToday ? record.streak : nextStreak(record, today);
        Map<String, Object> result = summary(record, 0, false, 0, signedToday);
        result.put("signedToday", signedToday);
        result.put("streak", projectedStreak);
        result.put("nextMilestone", nextMilestone(projectedStreak));
        result.put("comebackAvailable", comebackAvailable(record, today));
        return result;
    }

    /** 该身份累计签到经验，供成长体系叠加 */
    public synchronized int bonusXpFor(String identityId) {
        CheckIn record = identityId == null ? null : checkIns.get(identityId);
        return record == null ? 0 : record.bonusXp;
    }

    private static int nextStreak(CheckIn record, LocalDate today) {
        if (record == null || record.dates == null || record.dates.isEmpty()) return 1;
        LocalDate last = LocalDate.parse(record.dates.get(record.dates.size() - 1));
        return last.equals(today.minusDays(1)) ? record.streak + 1 : 1;
    }

    private static boolean comebackAvailable(CheckIn record, LocalDate today) {
        if (record == null || record.dates == null || record.dates.isEmpty()) return false;
        if (record.dates.contains(today.toString())) return false;
        LocalDate last = LocalDate.parse(record.dates.get(record.dates.size() - 1));
        return java.time.temporal.ChronoUnit.DAYS.between(last, today) >= COMEBACK_GAP_DAYS;
    }

    private static int nextMilestone(int streak) {
        for (int day : MILESTONES.keySet()) if (day > streak) return day;
        return 0;
    }

    private static Map<String, Object> summary(CheckIn record, int rewardXp,
                                               boolean comeback, int milestone, boolean signedToday) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rewardXp", rewardXp);
        result.put("streak", record.streak);
        result.put("longest", record.longest);
        result.put("totalDays", record.totalDays);
        result.put("bonusXp", record.bonusXp);
        result.put("comeback", comeback);
        result.put("milestone", milestone);
        result.put("signedToday", signedToday);
        return result;
    }

    private void persistOrRollback(String identityId, CheckIn before, boolean existed) {
        try {
            persist();
        } catch (RuntimeException e) {
            if (existed) checkIns.put(identityId, before); else checkIns.remove(identityId);
            throw e;
        }
    }

    private static CheckIn copyOf(CheckIn source) {
        CheckIn copy = new CheckIn();
        copy.dates = source.dates == null ? null : new ArrayList<>(source.dates);
        copy.streak = source.streak;
        copy.longest = source.longest;
        copy.totalDays = source.totalDays;
        copy.bonusXp = source.bonusXp;
        return copy;
    }

    private void persist() {
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, gson.toJson(checkIns), StandardCharsets.UTF_8);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) {
            throw new IllegalStateException("无法保存签到记录", e);
        }
    }

    /** 签到持久化模型 */
    public static class CheckIn {
        public List<String> dates = new ArrayList<>();
        public int streak;
        public int longest;
        public int totalDays;
        public int bonusXp;
    }
}
