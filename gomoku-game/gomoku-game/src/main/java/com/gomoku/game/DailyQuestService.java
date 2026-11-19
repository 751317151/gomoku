package com.gomoku.game;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * 每日任务：当日达成对局目标后主动领取经验。
 *
 * 设计取舍：
 * - 进度从对局记录实时派生，不另存计数：重启、补写记录后自动一致，没有第二份真相。
 * - 只有「领取」落盘（日期 + 已领任务 + 累计经验），一天一清；经验计入等级，
 *   但不写对局记录，因此战绩/ELO/排行榜不受影响。
 * - 与签到互补：签到奖励「来了就有」，任务奖励「玩过才拿」，给上线一个明确理由。
 */
@Service
public class DailyQuestService {

    /** 任务领取记录的容量上限，超出后淘汰日期最老的条目 */
    private static final int MAX_IDENTITIES = 5000;

    private static final List<Quest> QUESTS = List.of(
            new Quest("games-3", "热身三局", "今日完成 3 局对局", "🎮", "games", 3, 30),
            new Quest("win-1", "旗开得胜", "今日赢下 1 局", "🏆", "wins", 1, 40),
            new Quest("variety-2", "多面玩家", "今日体验 2 种玩法", "🧩", "gameTypes", 2, 35),
            new Quest("multiplayer-1", "组局开黑", "今日完成 1 局 3 人以上对局", "🔥", "multiplayer", 1, 40),
            new Quest("streak-3", "常来常往", "连续活跃达到 3 天", "📅", "streak", 3, 50)
    );

    private final Path path;
    private final GameRecordService records;
    private final Gson gson = new Gson();
    private final Map<String, State> states = new LinkedHashMap<>();

    @Autowired
    public DailyQuestService(GameRecordService records) {
        this(Paths.get("data", "daily_quests.json"), records);
    }

    /** 供测试注入临时路径 */
    public DailyQuestService(Path path, GameRecordService records) {
        this.path = path;
        this.records = records;
    }

    @PostConstruct
    public synchronized void init() {
        if (!Files.exists(path)) return;
        try {
            Map<String, State> stored = gson.fromJson(
                    Files.readString(path), new TypeToken<Map<String, State>>() {}.getType());
            if (stored == null) throw new IllegalStateException("每日任务文件为空");
            states.clear();
            states.putAll(stored);
        } catch (Exception e) {
            throw new IllegalStateException("无法读取每日任务文件", e);
        }
    }

    /** 当前日期；测试可子类覆盖以模拟跨天 */
    protected LocalDate today() {
        return LocalDate.now(ZoneId.systemDefault());
    }

    /** 统计时区；与对局记录的时间口径保持一致 */
    protected ZoneId zone() {
        return ZoneId.systemDefault();
    }

    /** 今日任务全景：进度、可领取状态、累计任务经验与连续活跃天数 */
    public synchronized Map<String, Object> status(String identityId) {
        if (identityId == null || identityId.isEmpty()) return null;
        LocalDate today = today();
        Metrics metrics = metrics(identityId, today);
        State state = states.get(identityId);
        Set<String> claimed = state != null && today.toString().equals(state.date)
                ? new HashSet<>(state.claimed) : Collections.emptySet();

        List<Map<String, Object>> views = new ArrayList<>();
        int claimableXp = 0;
        int claimedXp = 0;
        for (Quest quest : QUESTS) {
            int progress = Math.min(quest.progress(metrics), quest.goal);
            boolean claimedToday = claimed.contains(quest.id);
            boolean claimable = !claimedToday && progress >= quest.goal;
            if (claimable) claimableXp += quest.rewardXp;
            if (claimedToday) claimedXp += quest.rewardXp;

            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", quest.id);
            view.put("name", quest.name);
            view.put("desc", quest.desc);
            view.put("icon", quest.icon);
            view.put("progress", progress);
            view.put("goal", quest.goal);
            view.put("rewardXp", quest.rewardXp);
            view.put("claimed", claimedToday);
            view.put("claimable", claimable);
            views.add(view);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", today.toString());
        result.put("quests", views);
        result.put("claimableXp", claimableXp);
        result.put("claimedXp", claimedXp);
        result.put("bonusXp", state == null ? 0 : state.bonusXp);
        result.put("streakDays", metrics.streak);
        result.put("todayGames", metrics.games);
        result.put("todayWins", metrics.wins);
        result.put("todayGameTypes", metrics.gameTypes);
        result.put("todayMultiplayerGames", metrics.multiplayer);
        return result;
    }

    /**
     * 领取一个已完成任务的经验。返回 ok=false 的失败原因供接口层映射状态码：
     * UNKNOWN_QUEST / NOT_COMPLETED / ALREADY_CLAIMED。
     */
    public synchronized Map<String, Object> claim(String identityId, String questId) {
        if (identityId == null || identityId.isEmpty()) return null;
        Quest quest = questOf(questId);
        if (quest == null) return failure("UNKNOWN_QUEST");

        LocalDate today = today();
        Metrics metrics = metrics(identityId, today);
        boolean existed = states.containsKey(identityId);
        State state = states.get(identityId);
        boolean sameDay = state != null && today.toString().equals(state.date);
        if (sameDay && state.claimed.contains(quest.id)) return failure("ALREADY_CLAIMED");
        if (quest.progress(metrics) < quest.goal) return failure("NOT_COMPLETED");

        State before = state == null ? null : state.copy();
        if (state == null) {
            state = new State();
            states.put(identityId, state);
        }
        state.date = today.toString();
        if (state.claimed == null) state.claimed = new ArrayList<>();
        if (!sameDay) state.claimed.clear();
        state.claimed.add(quest.id);
        state.bonusXp += quest.rewardXp;
        try {
            pruneIfNeeded(identityId);
            persist();
        } catch (RuntimeException e) {
            if (existed) states.put(identityId, before); else states.remove(identityId);
            throw e;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("questId", quest.id);
        result.put("rewardXp", quest.rewardXp);
        result.put("status", status(identityId));
        return result;
    }

    /** 该身份累计领取的任务经验，供成长体系叠加 */
    public synchronized int bonusXpFor(String identityId) {
        State state = identityId == null ? null : states.get(identityId);
        return state == null ? 0 : state.bonusXp;
    }

    private static Quest questOf(String questId) {
        for (Quest quest : QUESTS) if (quest.id.equals(questId)) return quest;
        return null;
    }

    private static Map<String, Object> failure(String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("reason", reason);
        return result;
    }

    private Metrics metrics(String identityId, LocalDate today) {
        ZoneId zone = zone();
        long start = today.atStartOfDay(zone).toInstant().toEpochMilli();
        long end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        int games = 0, wins = 0, multiplayer = 0;
        Set<String> types = new HashSet<>();
        Set<LocalDate> activeDays = new HashSet<>();

        for (GameRecord record : records.getIdentityRecords(identityId)) {
            long endedAt = record.getEndedAt() > 0 ? record.getEndedAt() : record.getStartedAt();
            if (endedAt <= 0) continue;
            LocalDate day = Instant.ofEpochMilli(endedAt).atZone(zone).toLocalDate();
            activeDays.add(day);
            if (endedAt < start || endedAt >= end) continue;

            GameRecord.Participant me = participantOf(record, identityId);
            if (me == null) continue;
            games++;
            if ("WIN".equals(me.getResult())) wins++;
            if (record.getGameType() != null) types.add(record.getGameType());

            int humans = 0;
            for (GameRecord.Participant p : record.getParticipants()) {
                if (!p.isAi()) humans++;
            }
            if (humans >= 3) multiplayer++;
        }

        int streak = 0;
        for (LocalDate cursor = today; activeDays.contains(cursor); cursor = cursor.minusDays(1)) {
            streak++;
        }
        return new Metrics(games, wins, types.size(), multiplayer, streak);
    }

    private static GameRecord.Participant participantOf(GameRecord record, String identityId) {
        for (GameRecord.Participant p : record.getParticipants()) {
            if (identityId.equals(p.getIdentityId()) && !p.isAi()) return p;
        }
        return null;
    }

    /** 超出容量时淘汰日期最老的条目，避免文件随身份数无限增长 */
    private void pruneIfNeeded(String keepIdentity) {
        while (states.size() > MAX_IDENTITIES) {
            String oldestKey = null;
            String oldestDate = null;
            for (Map.Entry<String, State> entry : states.entrySet()) {
                if (entry.getKey().equals(keepIdentity)) continue;
                String date = entry.getValue().date == null ? "" : entry.getValue().date;
                if (oldestDate == null || date.compareTo(oldestDate) < 0) {
                    oldestDate = date;
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey == null) break;
            states.remove(oldestKey);
        }
    }

    private void persist() {
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, gson.toJson(states), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法保存每日任务记录", e);
        }
    }

    /** 任务定义与当日指标到进度的换算 */
    private static final class Quest {
        final String id;
        final String name;
        final String desc;
        final String icon;
        final String metric;
        final int goal;
        final int rewardXp;

        Quest(String id, String name, String desc, String icon, String metric, int goal, int rewardXp) {
            this.id = id;
            this.name = name;
            this.desc = desc;
            this.icon = icon;
            this.metric = metric;
            this.goal = goal;
            this.rewardXp = rewardXp;
        }

        int progress(Metrics metrics) {
            switch (metric) {
                case "games": return metrics.games;
                case "wins": return metrics.wins;
                case "gameTypes": return metrics.gameTypes;
                case "multiplayer": return metrics.multiplayer;
                case "streak": return metrics.streak;
                default: return 0;
            }
        }
    }

    private static final class Metrics {
        final int games;
        final int wins;
        final int gameTypes;
        final int multiplayer;
        final int streak;

        Metrics(int games, int wins, int gameTypes, int multiplayer, int streak) {
            this.games = games;
            this.wins = wins;
            this.gameTypes = gameTypes;
            this.multiplayer = multiplayer;
            this.streak = streak;
        }
    }

    /** 领取持久化模型：日期 + 当日已领任务 + 终身累计任务经验 */
    public static class State {
        public String date;
        public List<String> claimed = new ArrayList<>();
        public int bonusXp;

        State copy() {
            State copy = new State();
            copy.date = date;
            copy.claimed = claimed == null ? new ArrayList<>() : new ArrayList<>(claimed);
            copy.bonusXp = bonusXp;
            return copy;
        }
    }
}
