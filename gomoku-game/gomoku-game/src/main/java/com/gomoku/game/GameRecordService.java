package com.gomoku.game;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 对局记录服务 — 落盘 + 战绩查询
 *
 * 存储结构：
 * <pre>
 * data/games/
 *   index.jsonl          每局一行摘要（不含棋谱），启动时加载用于列表与战绩统计
 *   20261111-143022-a1b2c3.json   单局完整记录（含棋谱，供复盘）
 * </pre>
 *
 * 设计取舍：
 * - 摘要与详情分开存：战绩/列表查询只读 index.jsonl，不必解析动辄几十 KB 的棋谱
 * - 写盘走单线程守护线程：终局路径（持有房间写锁）不能因为 IO 卡住
 * - 内存只保留最近 {@link #MAX_INDEX_ENTRIES} 条摘要，老记录仍可从磁盘按 ID 取回
 */
@Service
public class GameRecordService {

    private static final Logger logger = LoggerFactory.getLogger(GameRecordService.class);
    private static final Gson gson = new GsonBuilder().serializeNulls().create();
    /** 内存中保留的摘要条数上限 */
    private static final int MAX_INDEX_ENTRIES = 5000;
    /** 单局棋谱超过该手数就不再存棋谱（只留元信息），避免单文件过大 */
    private static final int MAX_STORED_MOVES = 600;

    private final Path baseDir;
    private final Path indexPath;

    /** 摘要索引：最新的在前 */
    private final LinkedList<GameRecord> recent = new LinkedList<>();
    /** 玩家名 → 该玩家的摘要（最新的在前） */
    private final Map<String, LinkedList<GameRecord>> byPlayer = new ConcurrentHashMap<>();

    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Game-Record-Writer");
        t.setDaemon(true);
        return t;
    });

    public GameRecordService() {
        this(Paths.get("data", "games"));
    }

    /** 供测试注入自定义目录 */
    public GameRecordService(Path baseDir) {
        this.baseDir = baseDir;
        this.indexPath = baseDir.resolve("index.jsonl");
    }

    @PostConstruct
    public void init() {
        loadIndex();
    }

    @PreDestroy
    public void shutdown() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(3, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ============ 写入 ============

    /**
     * 异步存档一局。调用方在终局路径上，不等待落盘结果。
     */
    public void saveAsync(GameRecord record) {
        if (record == null) return;
        if (record.getId() == null) {
            record.setId(newRecordId());
        }
        // 内存索引只放「摘要副本」：一局棋谱可达几十 KB，若把原始对象留在索引里，
        // 内存会随对局数线性增长（5000 条 × 数十 KB 就是数百 MB）。
        index(stripMoves(record));
        // 截断超长棋谱后再交给写线程
        if (record.getMoveHistory() != null && record.getMoveHistory().size() > MAX_STORED_MOVES) {
            record.setMoveHistory(null);
            record.setMoveEffects(null);
        }
        writer.submit(() -> writeToDisk(record));
    }

    private String newRecordId() {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        return stamp + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    private synchronized void index(GameRecord record) {
        recent.addFirst(record);
        while (recent.size() > MAX_INDEX_ENTRIES) {
            GameRecord dropped = recent.removeLast();
            for (GameRecord.Participant p : dropped.getParticipants()) {
                if (p.getName() == null) continue;
                LinkedList<GameRecord> list = byPlayer.get(p.getName());
                if (list != null) list.remove(dropped);
            }
        }
        for (GameRecord.Participant p : record.getParticipants()) {
            if (p.getName() == null) continue;
            byPlayer.computeIfAbsent(p.getName(), k -> new LinkedList<>()).addFirst(record);
        }
    }

    private void writeToDisk(GameRecord record) {
        try {
            Files.createDirectories(baseDir);
            // 详情：完整记录（含棋谱）
            Path detail = baseDir.resolve(record.getId() + ".json");
            Files.write(detail, gson.toJson(record).getBytes(StandardCharsets.UTF_8));

            // 摘要：一行一条，供列表/战绩快速读取
            GameRecord summary = stripMoves(record);
            String line = gson.toJson(summary) + System.lineSeparator();
            Files.write(indexPath, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.warn("对局记录写入失败 id={}", record.getId(), e);
        }
    }

    private static GameRecord stripMoves(GameRecord src) {
        GameRecord copy = new GameRecord();
        copy.setId(src.getId());
        copy.setGameType(src.getGameType());
        copy.setRoomId(src.getRoomId());
        copy.setStartedAt(src.getStartedAt());
        copy.setEndedAt(src.getEndedAt());
        copy.setWinnerName(src.getWinnerName());
        copy.setReason(src.getReason());
        copy.setMoveCount(src.getMoveCount());
        copy.setRanked(src.isRanked());
        copy.setParticipants(src.getParticipants());
        return copy;
    }

    // ============ 读取 ============

    private void loadIndex() {
        if (!Files.exists(indexPath)) return;
        int loaded = 0;
        try {
            List<String> lines = Files.readAllLines(indexPath, StandardCharsets.UTF_8);
            // 从后往前读，保留最新的 MAX_INDEX_ENTRIES 条
            for (int i = lines.size() - 1; i >= 0 && loaded < MAX_INDEX_ENTRIES; i--) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;
                try {
                    GameRecord record = gson.fromJson(line, GameRecord.class);
                    if (record == null) continue;
                    record.setMoveHistory(null);
                    record.setMoveEffects(null);
                    index(record);
                    loaded++;
                } catch (Exception ignored) {
                    // 单行损坏不该影响整体加载
                }
            }
            logger.info("对局记录索引已加载：{} 条（来自 {}）", loaded, indexPath);
        } catch (IOException e) {
            logger.warn("对局记录索引读取失败: {}", indexPath, e);
        }
    }

    /** 最近的若干局（不分游戏类型） */
    public synchronized List<GameRecord> getRecentGames(int limit) {
        return recent.stream().limit(Math.max(1, limit)).collect(Collectors.toList());
    }

    /** 最近的若干局，限定游戏类型（null 或空表示不限） */
    public synchronized List<GameRecord> getRecentGames(int limit, String gameType) {
        return getRecentGames(limit, gameType, null);
    }

    /**
     * 最近的若干局，限定游戏类型与实际生效套牌。
     *
     * <p>套牌过滤匹配任意一方的套牌，并且必须先过滤再 limit：如果先截取最近 N 局，
     * 再在前端筛套牌，目标套牌可能因为夹在大量其他对局之间而整页为空。</p>
     *
     * @param gameType null 或空表示不限
     * @param deck     BALANCED / AGGRO / CONTROL / CUSTOM，大小写不敏感；null 或空表示不限
     */
    public synchronized List<GameRecord> getRecentGames(int limit, String gameType, String deck) {
        String wantedDeck = deck == null ? null : deck.trim();
        if (wantedDeck != null && wantedDeck.isEmpty()) wantedDeck = null;
        final String deckFilter = wantedDeck;
        return recent.stream()
                .filter(r -> gameType == null || gameType.isEmpty() || gameType.equalsIgnoreCase(r.getGameType()))
                .filter(r -> deckFilter == null || hasDeck(r, deckFilter))
                .limit(Math.max(1, limit))
                .collect(Collectors.toList());
    }

    /** 任意一方使用指定套牌即算命中（过滤的是「这场对局」，不是某个玩家） */
    private static boolean hasDeck(GameRecord record, String deck) {
        for (GameRecord.Participant p : record.getParticipants()) {
            if (p.getDeck() != null && p.getDeck().equalsIgnoreCase(deck)) return true;
        }
        return false;
    }

    /** 某玩家最近的若干局 */
    public synchronized List<GameRecord> getPlayerGames(String playerName, int limit) {
        LinkedList<GameRecord> list = byPlayer.get(playerName);
        if (list == null) return new ArrayList<>();
        return list.stream().limit(Math.max(1, limit)).collect(Collectors.toList());
    }

    /**
     * 某玩家在指定游戏下的战绩汇总（只统计计分局，AI 局不计）
     * @return { total, wins, losses, draws, winRate, elo, gameType }
     */
    public synchronized Map<String, Object> getPlayerStats(String playerName, String gameType) {
        LinkedList<GameRecord> list = byPlayer.get(playerName);
        int total = 0, wins = 0, losses = 0, draws = 0;
        int lastElo = 0;
        if (list != null) {
            // 记录按时间倒序，先出现的即最新，用于取当前 ELO
            for (GameRecord r : list) {
                if (!r.isRanked()) continue;
                if (gameType != null && !gameType.isEmpty() && !gameType.equalsIgnoreCase(r.getGameType())) continue;
                GameRecord.Participant me = findParticipant(r, playerName);
                if (me == null || me.isAi()) continue;
                total++;
                if ("WIN".equals(me.getResult())) wins++;
                else if ("LOSE".equals(me.getResult())) losses++;
                else draws++;
                if (lastElo == 0 && me.getEloAfter() > 0) lastElo = me.getEloAfter();
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("name", playerName);
        stats.put("gameType", gameType == null ? "" : gameType);
        stats.put("total", total);
        stats.put("wins", wins);
        stats.put("losses", losses);
        stats.put("draws", draws);
        stats.put("winRate", total == 0 ? 0.0 : Math.round(wins * 1000.0 / total) / 10.0);
        stats.put("elo", lastElo);
        return stats;
    }

    /**
     * 新身份不按昵称认领旧战绩，避免重名冒领。
     *
     * 统计口径：所有真人对局（含 2–8 人休闲局）。ELO 只认计分局，
     * 否则多人局默认分 1200 会被误显示成「竞技分」。
     */
    public synchronized Map<String, Object> getIdentityStats(String identityId, String gameType) {
        int wins = 0, losses = 0, draws = 0;
        int rankedTotal = 0, rankedWins = 0, rankedLosses = 0, rankedDraws = 0, elo = 0;
        for (GameRecord record : recent) {
            if (gameType != null && !gameType.isEmpty() && !gameType.equalsIgnoreCase(record.getGameType())) continue;
            GameRecord.Participant me = findParticipantByIdentity(record, identityId);
            if (me == null || me.isAi()) continue;
            boolean win = "WIN".equals(me.getResult());
            boolean draw = !win && !"LOSE".equals(me.getResult());
            if (win) wins++; else if (draw) draws++; else losses++;
            if (record.isRanked()) {
                rankedTotal++;
                if (win) rankedWins++; else if (draw) rankedDraws++; else rankedLosses++;
                if (elo == 0 && me.getEloAfter() > 0) elo = me.getEloAfter();
            }
        }
        int total = wins + losses + draws;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("wins", wins); result.put("losses", losses); result.put("draws", draws);
        result.put("total", total); result.put("elo", elo);
        result.put("winRate", total == 0 ? 0 : Math.round(wins * 1000.0 / total) / 10.0);
        result.put("rankedTotal", rankedTotal);
        result.put("rankedWins", rankedWins);
        result.put("rankedLosses", rankedLosses);
        result.put("rankedDraws", rankedDraws);
        result.put("casualTotal", total - rankedTotal);
        return result;
    }

    /**
     * 面向大厅的轻量运营进度：今日完成对局、今日胜场、今日体验过的玩法数、连续活跃天数。
     * 只按持久身份统计，避免昵称重名互相污染；AI 局不算进每日任务。
     */
    public synchronized Map<String, Object> getIdentityDailyProgress(String identityId) {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        int todayGames = 0, todayWins = 0;
        java.util.Set<String> todayTypes = new java.util.HashSet<>();
        java.util.Set<LocalDate> activeDays = new java.util.HashSet<>();
        for (GameRecord record : recent) {
            GameRecord.Participant participant = findParticipantByIdentity(record, identityId);
            // 每日活跃统计的是所有真人对局（包括多人休闲房）；只有 AI 局不计入。
            // ranked 只决定 ELO/竞技战绩，不能让多人玩法从运营任务里消失。
            if (participant == null || participant.isAi()) continue;
            LocalDate day = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(record.getEndedAt()), ZoneId.systemDefault());
            activeDays.add(day);
            if (day.equals(today)) {
                todayGames++;
                if ("WIN".equals(participant.getResult())) todayWins++;
                if (record.getGameType() != null) todayTypes.add(record.getGameType());
            }
        }
        int streak = 0;
        LocalDate cursor = today;
        while (activeDays.contains(cursor)) { streak++; cursor = cursor.minusDays(1); }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", today.toString());
        result.put("todayGames", todayGames);
        result.put("todayWins", todayWins);
        result.put("todayGameTypes", todayTypes.size());
        result.put("streakDays", streak);
        result.put("gamesGoal", 3);
        result.put("winsGoal", 1);
        result.put("gameTypesGoal", 2);
        result.put("streakGoal", 3);
        result.put("gamesDone", todayGames >= 3);
        result.put("winsDone", todayWins >= 1);
        result.put("gameTypesDone", todayTypes.size() >= 2);
        result.put("streakDone", streak >= 3);
        return result;
    }

    /**
     * 本周活跃榜：最近 7 天所有真人玩法（含 2–8 人休闲局）按胜场、对局数排序。
     *
     * ELO 榜只覆盖双人竞技，多人玩法玩家此前没有任何排名曝光；
     * 这个榜单用「谁在持续玩」来补足运营侧的另一半。
     */
    public synchronized List<Map<String, Object>> getWeeklyActivityLeaderboard(int topN) {
        long cutoff = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(7);
        Map<String, WeeklyRow> rows = new HashMap<>();
        for (GameRecord record : recent) {
            if (record.getEndedAt() <= 0 || record.getEndedAt() < cutoff) continue;
            for (GameRecord.Participant p : record.getParticipants()) {
                if (p.isAi() || p.getIdentityId() == null) continue;
                WeeklyRow row = rows.computeIfAbsent(p.getIdentityId(), k -> new WeeklyRow());
                if (p.getName() != null) row.name = p.getName();
                row.games++;
                if ("WIN".equals(p.getResult())) row.wins++;
                if (record.getGameType() != null) row.types.add(record.getGameType());
            }
        }
        return rows.values().stream()
                .sorted(Comparator.comparingInt((WeeklyRow r) -> r.wins).reversed()
                        .thenComparing(Comparator.comparingInt((WeeklyRow r) -> r.games).reversed())
                        .thenComparing(r -> r.name == null ? "" : r.name))
                .limit(Math.max(1, topN))
                .map(r -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("name", r.name);
                    view.put("wins", r.wins);
                    view.put("games", r.games);
                    view.put("gameTypes", r.types.size());
                    return view;
                })
                .collect(Collectors.toList());
    }

    private static final class WeeklyRow {
        String name;
        int wins;
        int games;
        final java.util.Set<String> types = new java.util.HashSet<>();
    }

    /**
     * 某身份在一个时间窗口内的赛季战绩（只算真人对局，AI 局不计）。
     * 赛季分：胜 +3、平 +1、负 0。纯派生，不改写任何记录或 ELO。
     */
    public synchronized Map<String, Object> getSeasonStats(String identityId, long since, long until) {
        int wins = 0, draws = 0, losses = 0;
        if (identityId != null) {
            for (GameRecord record : recent) {
                long ended = record.getEndedAt();
                if (ended < since || ended >= until) continue;
                GameRecord.Participant me = findParticipantByIdentity(record, identityId);
                if (me == null || me.isAi()) continue;
                if ("WIN".equals(me.getResult())) wins++;
                else if ("LOSE".equals(me.getResult())) losses++;
                else draws++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("wins", wins);
        result.put("draws", draws);
        result.put("losses", losses);
        result.put("games", wins + draws + losses);
        result.put("points", wins * 3 + draws);
        return result;
    }

    /**
     * 赛季排行榜：时间窗口内按赛季分降序（同分再按胜场、对局数）。
     * 与 ELO 榜互补——ELO 是长期累计，赛季分只看本窗口表现，给新人和追赶者机会。
     */
    public synchronized List<Map<String, Object>> getSeasonLeaderboard(long since, long until, int topN) {
        List<Map<String, Object>> full = getSeasonResults(since, until);
        return full.stream()
                .peek(row -> row.remove("identityId"))
                .limit(Math.max(1, topN))
                .collect(Collectors.toList());
    }

    /**
     * 赛季完整结果（含 identityId）。
     *
     * 赛季结算需要按持久身份写历史称号；对外 API 不暴露身份，由榜单方法过滤。
     */
    public synchronized List<Map<String, Object>> getSeasonResults(long since, long until) {
        Map<String, WeeklyRow> rows = new HashMap<>();
        Map<String, Integer> points = new HashMap<>();
        for (GameRecord record : recent) {
            long ended = record.getEndedAt();
            if (ended < since || ended >= until) continue;
            for (GameRecord.Participant p : record.getParticipants()) {
                if (p.isAi() || p.getIdentityId() == null) continue;
                WeeklyRow row = rows.computeIfAbsent(p.getIdentityId(), k -> new WeeklyRow());
                if (p.getName() != null) row.name = p.getName();
                row.games++;
                if ("WIN".equals(p.getResult())) {
                    row.wins++;
                    points.merge(p.getIdentityId(), 3, Integer::sum);
                } else if (!"LOSE".equals(p.getResult())) {
                    points.merge(p.getIdentityId(), 1, Integer::sum);
                }
            }
        }
        return rows.entrySet().stream()
                .sorted(Comparator
                        .comparingInt((Map.Entry<String, WeeklyRow> e) -> points.getOrDefault(e.getKey(), 0)).reversed()
                        .thenComparing(Comparator.comparingInt((Map.Entry<String, WeeklyRow> e) -> e.getValue().wins).reversed())
                        .thenComparing(e -> e.getValue().name == null ? "" : e.getValue().name))
                .map(e -> {
                    WeeklyRow r = e.getValue();
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("identityId", e.getKey());
                    view.put("name", r.name);
                    view.put("points", points.getOrDefault(e.getKey(), 0));
                    view.put("wins", r.wins);
                    view.put("games", r.games);
                    return view;
                })
                .collect(Collectors.toList());
    }

    /**
     * 某个持久身份参与过的全部真人对局（最新的在前）。
     *
     * 成长体系（等级/成就）需要遍历历史，但只认稳定身份：
     * 昵称会重复，session 会过期，只有 identityId 能跨设备延续进度。
     * AI 局不返回，避免刷人机刷经验。
     */
    public synchronized List<GameRecord> getIdentityRecords(String identityId) {
        List<GameRecord> result = new ArrayList<>();
        if (identityId == null) return result;
        for (GameRecord record : recent) {
            GameRecord.Participant participant = findParticipantByIdentity(record, identityId);
            if (participant != null && !participant.isAi()) result.add(record);
        }
        return result;
    }

    private static GameRecord.Participant findParticipant(GameRecord record, String playerName) {
        for (GameRecord.Participant p : record.getParticipants()) {
            if (playerName.equals(p.getName())) return p;
        }
        return null;
    }

    private static GameRecord.Participant findParticipantByIdentity(GameRecord record, String identityId) {
        if (identityId == null) return null;
        for (GameRecord.Participant p : record.getParticipants()) {
            if (identityId.equals(p.getIdentityId())) return p;
        }
        return null;
    }

    /** 按 ID 读取完整记录（含棋谱），不存在返回 null */
    public GameRecord getRecord(String id) {
        if (id == null || id.isEmpty()) return null;
        // 防目录穿越：ID 只允许字母数字与连字符
        if (!id.matches("[A-Za-z0-9-]+")) return null;
        Path file = baseDir.resolve(id + ".json");
        if (!Files.exists(file)) {
            // 内存里可能还有（写盘尚未完成）
            synchronized (this) {
                for (GameRecord r : recent) {
                    if (id.equals(r.getId())) return r;
                }
            }
            return null;
        }
        try {
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            return gson.fromJson(json, GameRecord.class);
        } catch (Exception e) {
            logger.warn("对局记录读取失败 id={}", id, e);
            return null;
        }
    }

    /** 记录总数（内存索引范围） */
    public synchronized int getRecordCount() {
        return recent.size();
    }

    /** 排行榜用的「最近对局」精简视图（避免前端拿到一堆字段） */
    public synchronized List<Map<String, Object>> getRecentGameViews(int limit, String gameType) {
        return getRecentGameViews(limit, gameType, null);
    }

    /** 最近对局精简视图，支持按实际生效套牌筛选 */
    public synchronized List<Map<String, Object>> getRecentGameViews(int limit, String gameType, String deck) {
        List<Map<String, Object>> views = new ArrayList<>();
        for (GameRecord r : getRecentGames(limit, gameType, deck)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", r.getId());
            view.put("gameType", r.getGameType());
            // roomId 是把「实时对局」和「存档记录」对上的唯一键
            view.put("roomId", r.getRoomId());
            view.put("startedAt", r.getStartedAt());
            view.put("endedAt", r.getEndedAt());
            view.put("winnerName", r.getWinnerName());
            view.put("reason", r.getReason());
            view.put("moveCount", r.getMoveCount());
            view.put("ranked", r.isRanked());
            List<Map<String, Object>> ps = new ArrayList<>();
            for (GameRecord.Participant p : r.getParticipants()) {
                Map<String, Object> pv = new LinkedHashMap<>();
                pv.put("name", p.getName());
                pv.put("side", p.getSide());
                pv.put("result", p.getResult());
                pv.put("ai", p.isAi());
                pv.put("eloChange", p.getEloChange());
                pv.put("eloAfter", p.getEloAfter());
                // 仅卡牌对局有值：预组只有 deck/deckLabel，自定义再带配比摘要
                if (p.getDeck() != null) {
                    pv.put("deck", p.getDeck());
                    pv.put("deckLabel", p.getDeckLabel());
                    if (p.getDeckSummary() != null) pv.put("deckSummary", p.getDeckSummary());
                }
                ps.add(pv);
            }
            // 稳定排序：先手方在前
            ps.sort(Comparator.comparingInt(m -> ((Integer) m.get("side"))));
            view.put("participants", ps);
            views.add(view);
        }
        return views;
    }

    /** 便于测试：等待所有待写任务落盘 */
    public void flushForTest() {
        try {
            writer.submit(() -> {}).get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    /** 便于测试：直接从 map 里查某玩家的记录条数 */
    int indexedPlayerCount(String playerName) {
        LinkedList<GameRecord> list = byPlayer.get(playerName);
        return list == null ? 0 : list.size();
    }

    // 保留 HashMap 引用，避免 IDE 误报未使用（预留扩展）
    @SuppressWarnings("unused")
    private final Map<String, Integer> reserved = new HashMap<>();
}
