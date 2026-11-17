package com.gomoku.game;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 成长体系：等级 + 成就，全部从对局记录派生。
 *
 * 设计取舍：
 * - 不落库。等级和成就是「记录的函数」，重启、换设备、补写记录后自动一致；
 *   若单独存一份进度，就必须处理与记录不一致的修复逻辑，这里直接不引入第二份真相。
 * - 覆盖全部玩法（含 2–8 人休闲局）。ELO 只服务双人竞技，多人玩法此前没有成长反馈，
 *   这正是「打完一局没有下一步」的留存缺口。
 * - 只认持久身份（identityId），AI 局不计经验，避免刷人机升级。
 */
@Service
public class PlayerProgressService {

    /** 完成一局的基础经验 */
    private static final int XP_PER_GAME = 20;
    /** 胜利额外经验 */
    private static final int XP_PER_WIN = 30;
    /** 平局额外经验（鼓励打完，而不是中途退出） */
    private static final int XP_PER_DRAW = 10;
    /** 多人局（3 人及以上）额外经验：多人玩法更难凑齐，值得更多激励 */
    private static final int XP_MULTIPLAYER_BONUS = 10;
    /** 首次体验某个玩法的经验 */
    private static final int XP_NEW_GAME_TYPE = 25;

    private final GameRecordService records;

    public PlayerProgressService(GameRecordService records) {
        this.records = records;
    }

    /** 升到下一级所需经验：等级越高，门槛越高 */
    static int xpToNextLevel(int level) {
        return 100 + 60 * (level - 1);
    }

    /** 从累计经验换算等级与段位称号 */
    static int levelForXp(int xp) {
        int level = 1;
        int remaining = Math.max(0, xp);
        while (remaining >= xpToNextLevel(level)) {
            remaining -= xpToNextLevel(level);
            level++;
        }
        return level;
    }

    static String titleForLevel(int level) {
        if (level >= 20) return "传奇";
        if (level >= 15) return "宗师";
        if (level >= 11) return "大师";
        if (level >= 8) return "高手";
        if (level >= 5) return "棋手";
        if (level >= 3) return "学徒";
        return "新手";
    }

    public Map<String, Object> getProgress(String identityId) {
        return getProgress(identityId, 0);
    }

    /**
     * 叠加签到等运营来源的经验。签到奖励单独存，不写成对局记录，
     * 因此战绩/ELO/对局数不受影响；这里只把它计入等级经验。
     */
    public Map<String, Object> getProgress(String identityId, int bonusXp) {
        List<GameRecord> games = records.getIdentityRecords(identityId);

        int xp = Math.max(0, bonusXp);
        int wins = 0, draws = 0, losses = 0;
        int multiplayerGames = 0;
        int maxWinStreak = 0, currentStreak = 0;
        Set<String> types = new HashSet<>();
        boolean nightOwl = false;
        boolean speedrun = false;
        ZoneId zone = ZoneId.systemDefault();

        // getIdentityRecords 按时间倒序返回：窗口内连败/连胜判断需要按时间正序
        List<GameRecord> ordered = new ArrayList<>(games);
        for (int i = 0, j = ordered.size() - 1; i < j; i++, j--) {
            java.util.Collections.swap(ordered, i, j);
        }

        for (GameRecord record : ordered) {
            GameRecord.Participant me = participantOf(record, identityId);
            if (me == null) continue;
            boolean isWin = "WIN".equals(me.getResult());
            boolean isDraw = !isWin && !"LOSE".equals(me.getResult());

            xp += XP_PER_GAME;
            if (isWin) { xp += XP_PER_WIN; wins++; }
            else if (isDraw) { xp += XP_PER_DRAW; draws++; }
            else losses++;

            int humanPlayers = 0;
            for (GameRecord.Participant p : record.getParticipants()) {
                if (!p.isAi()) humanPlayers++;
            }
            if (humanPlayers > 2) { multiplayerGames++; xp += XP_MULTIPLAYER_BONUS; }

            String type = record.getGameType();
            if (type != null && types.add(type)) xp += XP_NEW_GAME_TYPE;

            if (isWin) {
                currentStreak++;
                maxWinStreak = Math.max(maxWinStreak, currentStreak);
            } else {
                currentStreak = 0;
            }

            long endedAt = record.getEndedAt() > 0 ? record.getEndedAt() : record.getStartedAt();
            if (endedAt > 0) {
                int hour = Instant.ofEpochMilli(endedAt).atZone(zone).getHour();
                if (hour >= 0 && hour < 5) nightOwl = true;
                long duration = record.getEndedAt() - record.getStartedAt();
                // 只认合理区间：负数/0 是旧数据缺失，不能冒充快棋
                if (duration > 0 && duration <= 120_000L && record.getMoveCount() >= 3) speedrun = true;
            }
        }

        int level = levelForXp(xp);
        int consumed = 0;
        for (int l = 1; l < level; l++) consumed += xpToNextLevel(l);
        int intoLevel = xp - consumed;
        int nextLevelXp = xpToNextLevel(level);

        int total = wins + draws + losses;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("xp", xp);
        result.put("level", level);
        result.put("title", titleForLevel(level));
        result.put("levelXp", intoLevel);
        result.put("levelXpGoal", nextLevelXp);
        result.put("levelProgress", Math.min(100, Math.round(intoLevel * 1000.0 / nextLevelXp) / 10.0));
        result.put("totalGames", total);
        result.put("wins", wins);
        result.put("draws", draws);
        result.put("losses", losses);
        result.put("winStreak", maxWinStreak);
        result.put("gameTypes", types.size());
        result.put("multiplayerGames", multiplayerGames);

        List<Map<String, Object>> achievements = new ArrayList<>();
        achievements.add(achievement("first-game", "初出茅庐", "完成第一局对局", total, 1, total >= 1));
        achievements.add(achievement("first-win", "首战告捷", "赢下第一局", wins, 1, wins >= 1));
        achievements.add(achievement("games-10", "常客", "累计完成 10 局", total, 10, total >= 10));
        achievements.add(achievement("games-50", "老玩家", "累计完成 50 局", total, 50, total >= 50));
        achievements.add(achievement("games-100", "百战", "累计完成 100 局", total, 100, total >= 100));
        achievements.add(achievement("streak-3", "三连胜", "取得 3 连胜", maxWinStreak, 3, maxWinStreak >= 3));
        achievements.add(achievement("variety-3", "多面手", "体验 3 种玩法", types.size(), 3, types.size() >= 3));
        achievements.add(achievement("variety-8", "全能选手", "体验 8 种玩法", types.size(), 8, types.size() >= 8));
        achievements.add(achievement("multiplayer-5", "组局达人", "完成 5 局 3 人以上对局", multiplayerGames, 5, multiplayerGames >= 5));
        achievements.add(achievement("night-owl", "夜猫子", "在凌晨 0–5 点完成一局", nightOwl ? 1 : 0, 1, nightOwl));
        achievements.add(achievement("speedrun", "闪电战", "2 分钟内结束一局", speedrun ? 1 : 0, 1, speedrun));
        result.put("achievements", achievements);

        int unlocked = 0;
        for (Map<String, Object> a : achievements) if (Boolean.TRUE.equals(a.get("unlocked"))) unlocked++;
        result.put("achievementsUnlocked", unlocked);
        result.put("achievementsTotal", achievements.size());
        return result;
    }

    private static Map<String, Object> achievement(String id, String name, String desc,
                                                   int progress, int goal, boolean unlocked) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", id);
        a.put("name", name);
        a.put("desc", desc);
        a.put("progress", Math.min(progress, goal));
        a.put("goal", goal);
        a.put("unlocked", unlocked);
        return a;
    }

    private static GameRecord.Participant participantOf(GameRecord record, String identityId) {
        if (identityId == null) return null;
        for (GameRecord.Participant p : record.getParticipants()) {
            if (identityId.equals(p.getIdentityId()) && !p.isAi()) return p;
        }
        return null;
    }
}
