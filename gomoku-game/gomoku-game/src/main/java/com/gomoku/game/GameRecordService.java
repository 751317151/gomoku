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
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.function.Consumer;

/**
 * 对局记录服务 — 落盘 + 战绩查询
 *
 * 存储结构：
 * <pre>
 * data/games/
 *   index.jsonl          每局一行摘要（不含棋谱），保留为可重放的历史日志
 *   archive.sqlite       可重建的全量摘要/参与者索引，供长期战绩与成长查询
 *   20261111-143022-a1b2c3.json   单局完整记录（含棋谱，供复盘）
 * </pre>
 *
 * 设计取舍：
 * - 摘要与详情分开存：战绩/列表查询只读 index.jsonl，不必解析动辄几十 KB 的棋谱
 * - 写盘走单线程守护线程：终局路径（持有房间写锁）不能因为 IO 卡住
 * - 大厅内存只保留最近 {@link #MAX_INDEX_ENTRIES} 条摘要，长期资产查询不受该窗口限制
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
    private GameArchive archive;
    private boolean initialized;
    private boolean drainScheduled;
    /** 仅记录本进程中的身份变更版本，供有界成长缓存失效；持久真相仍来自完整历史。 */
    private final Map<String, Long> identityRevisions = new HashMap<>();
    /** 尚未持久化的不可变快照；读接口叠加这部分，终局后立即可见且不会重复计算。 */
    private final LinkedHashMap<String, PendingRecord> pending = new LinkedHashMap<>();

    private static final class PendingRecord {
        final GameRecord detail;
        final GameRecord summary;
        boolean journaled;
        int failures;
        PendingRecord(GameRecord detail) {
            this.detail = detail;
            this.summary = stripMoves(detail);
        }
    }

    /** 摘要索引：最新的在前 */
    private final LinkedList<GameRecord> recent = new LinkedList<>();
    /** 玩家名 → 该玩家的摘要（最新的在前） */
    private final Map<String, LinkedList<GameRecord>> byPlayer = new ConcurrentHashMap<>();

    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Game-Record-Writer");
        t.setDaemon(true);
        return t;
    });

    public GameRecordService() {
        this(Paths.get("data", "games"));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GameRecordService(@org.springframework.beans.factory.annotation.Value("${gomoku.records.directory:data/games}") String baseDir) {
        this(Paths.get(baseDir));
    }

    /** 供测试注入自定义目录 */
    public GameRecordService(Path baseDir) {
        this.baseDir = baseDir;
        this.indexPath = baseDir.resolve("index.jsonl");
    }

    @PostConstruct
    public synchronized void init() {
        if (initialized) return;
        archive = new GameArchive(baseDir);
        List<GameRecord> loaded = archive.recent(MAX_INDEX_ENTRIES);
        // index() 在头部添加；必须从旧到新恢复，不能在重启后反转最近对局和 ELO。
        Collections.reverse(loaded);
        for (GameRecord record : loaded) index(record);
        initialized = true;
    }

    @PreDestroy
    public void shutdown() {
        if (!awaitPending(3000)) logger.error("停机时仍有对局尚未持久化：{}", getStorageStatus());
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
    public synchronized void saveAsync(GameRecord record) {
        if (record == null) return;
        if (writer.isShutdown()) throw new IllegalStateException("对局记录服务已停止");
        init();
        if (record.getId() == null) {
            record.setId(newRecordId());
        }
        GameArchive.validate(record);
        if (pending.containsKey(record.getId()) || archive.contains(record.getId())) return;
        // 内存索引只放「摘要副本」：一局棋谱可达几十 KB，若把原始对象留在索引里，
        // 内存会随对局数线性增长（5000 条 × 数十 KB 就是数百 MB）。
        GameRecord detail = stripMoves(record);
        detail.setInitialBoard(copyBoard(record.getInitialBoard()));
        if (record.getMoveHistory() != null && record.getMoveHistory().size() <= MAX_STORED_MOVES) {
            detail.setMoveHistory(copyMoves(record.getMoveHistory()));
            if (record.getMoveEffects() != null) {
                List<List<int[]>> effects = new ArrayList<>();
                for (List<int[]> effect : record.getMoveEffects()) effects.add(effect == null ? null : copyMoves(effect));
                detail.setMoveEffects(effects);
            }
        }
        PendingRecord accepted = new PendingRecord(detail);
        pending.put(detail.getId(), accepted);
        java.util.Set<String> identities = new java.util.HashSet<>();
        for (GameRecord.Participant participant : detail.getParticipants()) {
            if (!participant.isAi() && participant.getIdentityId() != null && identities.add(participant.getIdentityId())) {
                identityRevisions.merge(participant.getIdentityId(), 1L, Long::sum);
            }
        }
        index(accepted.summary);
        if (!drainScheduled) {
            drainScheduled = true;
            writer.execute(this::drainWrites);
        }
    }

    private static List<int[]> copyMoves(List<int[]> moves) {
        List<int[]> copy = new ArrayList<>(moves.size());
        for (int[] move : moves) copy.add(move == null ? null : move.clone());
        return copy;
    }

    private static int[][] copyBoard(int[][] board) {
        if (board == null) return null;
        int[][] copy = new int[board.length][];
        for (int r = 0; r < board.length; r++) {
            copy[r] = board[r] == null ? null : board[r].clone();
        }
        return copy;
    }

    private String newRecordId() {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        return stamp + "-" + UUID.randomUUID();
    }

    private synchronized void index(GameRecord record) {
        recent.addFirst(record);
        while (recent.size() > MAX_INDEX_ENTRIES) {
            GameRecord dropped = recent.removeLast();
            for (GameRecord.Participant p : dropped.getParticipants()) {
                if (p.getName() == null) continue;
                LinkedList<GameRecord> list = byPlayer.get(p.getName());
                if (list != null) {
                    list.remove(dropped);
                    if (list.isEmpty()) byPlayer.remove(p.getName());
                }
            }
        }
        for (GameRecord.Participant p : record.getParticipants()) {
            if (p.getName() == null) continue;
            byPlayer.computeIfAbsent(p.getName(), k -> new LinkedList<>()).addFirst(record);
        }
    }

    private void drainWrites() {
        while (true) {
            PendingRecord next;
            synchronized (this) {
                if (pending.isEmpty()) { drainScheduled = false; notifyAll(); return; }
                next = pending.values().iterator().next();
            }
            try {
                writeToDisk(next);
                synchronized (this) { pending.remove(next.summary.getId()); notifyAll(); }
            } catch (IOException | RuntimeException e) {
                int failures;
                synchronized (this) { failures = ++next.failures; }
                logger.error("对局存档失败，将保序重试 id={} attempt={}", next.summary.getId(), failures, e);
                if (!writer.isShutdown()) writer.schedule(this::drainWrites,
                        Math.min(30, 1L << Math.min(failures - 1, 5)), TimeUnit.SECONDS);
                return;
            }
        }
    }

    /** 单线程保序：详情原子替换 → 摘要日志强制落盘 → 索引事务。重启可从日志补齐索引。 */
    private void writeToDisk(PendingRecord accepted) throws IOException {
        if (!accepted.journaled) {
            Files.createDirectories(baseDir);
            Path detail = baseDir.resolve(accepted.detail.getId() + ".json");
            Path temporary = detail.resolveSibling(detail.getFileName() + ".tmp");
            byte[] json = gson.toJson(accepted.detail).getBytes(StandardCharsets.UTF_8);
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writeFully(output, ByteBuffer.wrap(json)); output.force(true);
            }
            try { Files.move(temporary, detail, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, detail, StandardCopyOption.REPLACE_EXISTING); }
            // 若上一次崩溃留下半行，先隔开，不能让下一局粘到损坏行后也变得不可恢复。
            try (FileChannel output = FileChannel.open(indexPath, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                long size = output.size();
                if (size > 0) {
                    ByteBuffer last = ByteBuffer.allocate(1); output.position(size - 1); output.read(last);
                    output.position(size);
                    if (last.array()[0] != '\n') writeFully(output, ByteBuffer.wrap(new byte[] {'\n'}));
                }
                output.position(output.size());
                writeFully(output, ByteBuffer.wrap((gson.toJson(accepted.summary) + "\n").getBytes(StandardCharsets.UTF_8)));
                output.force(true);
            }
            accepted.journaled = true;
        }
        archive.insert(accepted.summary);
        archive.markJournaled(Files.size(indexPath));
    }

    private static void writeFully(FileChannel output, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) output.write(bytes);
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
        copy.setEventId(src.getEventId());
        copy.setEventTitle(src.getEventTitle());
        copy.setEventXpPercent(src.getEventXpPercent());
        List<GameRecord.Participant> participants = new ArrayList<>();
        for (GameRecord.Participant source : src.getParticipants()) {
            GameRecord.Participant participant = new GameRecord.Participant(source.getPlayerId(), source.getName(),
                    source.getSide(), source.getResult(), source.isAi(), source.getEloBefore(), source.getEloAfter(), source.getEloChange());
            participant.setIdentityId(source.getIdentityId()); participant.setDeck(source.getDeck());
            participant.setDeckLabel(source.getDeckLabel()); participant.setDeckSummary(source.getDeckSummary());
            participants.add(participant);
        }
        copy.setParticipants(participants);
        return copy;
    }

    // ============ 读取 ============

    /** 最近的若干局（不分游戏类型） */
    public synchronized List<GameRecord> getRecentGames(int limit) {
        return recent.stream().limit(Math.max(1, limit)).map(GameRecordService::stripMoves).collect(Collectors.toList());
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
                .map(GameRecordService::stripMoves)
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
        return list.stream().limit(Math.max(1, limit)).map(GameRecordService::stripMoves).collect(Collectors.toList());
    }

    /**
     * 某玩家在指定游戏下的战绩汇总（只统计计分局，AI 局不计）
     * @return { total, wins, losses, draws, winRate, elo, gameType }
     */
    public synchronized Map<String, Object> getPlayerStats(String playerName, String gameType) {
        List<GameRecord> list = new ArrayList<>();
        visitNameRecords(playerName, list::add);
        Collections.reverse(list);
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
        for (GameRecord record : getIdentityRecords(identityId)) {
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
     * 只按持久身份统计，避免昵称重名互相污染。人机对局照常计入——单人玩家同样需要任务与成长。
     */
    public synchronized Map<String, Object> getIdentityDailyProgress(String identityId) {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        int todayGames = 0, todayWins = 0;
        java.util.Set<String> todayTypes = new java.util.HashSet<>();
        java.util.Set<LocalDate> activeDays = new java.util.HashSet<>();
        for (GameRecord record : getIdentityRecords(identityId)) {
            GameRecord.Participant participant = findParticipantByIdentity(record, identityId);
            // 每日活跃统计身份参与的全部对局（含人机、多人休闲房）；这里排除的只是 AI 自己的身份。
            // ranked 只决定 ELO/竞技战绩，不能让多人或人机玩法从运营任务里消失。
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
        for (GameRecord record : recordsInWindow(cutoff, Long.MAX_VALUE)) {
            if (record.getEndedAt() <= 0 || record.getEndedAt() < cutoff) continue;
            for (GameRecord.Participant p : record.getParticipants()) {
                if (p.isAi() || p.getIdentityId() == null) continue;
                WeeklyRow row = rows.computeIfAbsent(p.getIdentityId(), k -> new WeeklyRow());
                if (row.name == null && p.getName() != null) row.name = p.getName();
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
            for (GameRecord record : recordsInWindow(since, until)) {
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
        for (GameRecord record : recordsInWindow(since, until)) {
            long ended = record.getEndedAt();
            if (ended < since || ended >= until) continue;
            for (GameRecord.Participant p : record.getParticipants()) {
                if (p.isAi() || p.getIdentityId() == null) continue;
                WeeklyRow row = rows.computeIfAbsent(p.getIdentityId(), k -> new WeeklyRow());
                if (row.name == null && p.getName() != null) row.name = p.getName();
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
     * 某个持久身份参与过的全部对局（最新的在前，含人机局）。
     *
     * 成长体系（等级/成就）需要遍历历史，但只认稳定身份：
     * 昵称会重复，session 会过期，只有 identityId 能跨设备延续进度。
     * 人机局也计入经验（单人模式要能成长），这里排除的只是 AI 自己的身份。
     */
    public synchronized List<GameRecord> getIdentityRecords(String identityId) {
        List<GameRecord> result = new ArrayList<>();
        visitIdentityRecords(identityId, result::add);
        Collections.reverse(result);
        return result;
    }

    /** 按存档顺序从旧到新流式读取全历史；成长计算无需把全部历史再装入内存。 */
    public synchronized long visitIdentityRecords(String identityId, Consumer<GameRecord> visitor) {
        if (identityId == null) return 0;
        init();
        archive.visitIdentity(identityId, record -> {
            if (!pending.containsKey(record.getId())) visitor.accept(record);
        });
        for (PendingRecord record : pending.values()) {
            GameRecord.Participant me = findParticipantByIdentity(record.summary, identityId);
            if (me != null && !me.isAi()) visitor.accept(stripMoves(record.summary));
        }
        return getIdentityRevision(identityId);
    }

    public synchronized long getIdentityRevision(String identityId) {
        return identityRevisions.getOrDefault(identityId, 0L);
    }

    private void visitNameRecords(String name, Consumer<GameRecord> visitor) {
        if (name == null) return;
        init();
        archive.visitName(name, record -> {
            if (!pending.containsKey(record.getId())) visitor.accept(record);
        });
        for (PendingRecord record : pending.values()) {
            GameRecord.Participant me = findParticipant(record.summary, name);
            if (me != null && !me.isAi()) visitor.accept(stripMoves(record.summary));
        }
    }

    /** 日期窗口独立于大厅 5000 条窗口：高活跃时每日任务、赛季、运营统计也不能缩水。 */
    public synchronized List<GameRecord> recordsInWindow(long since, long until) {
        init();
        List<GameRecord> result = new ArrayList<>();
        archive.visitWindow(since, until, record -> {
            if (!pending.containsKey(record.getId())) result.add(record);
        });
        for (PendingRecord record : pending.values()) {
            long ended = record.summary.getEndedAt();
            if (ended >= since && ended < until) result.add(stripMoves(record.summary));
        }
        Collections.reverse(result);
        return result;
    }

    /** 推荐热度直接聚合索引，避免每位大厅访客反序列化窗口内所有摘要。 */
    public synchronized Map<String, Long> getSocialGameCounts(long since, long until) {
        init();
        Map<String, Long> counts = new LinkedHashMap<>(archive.socialGameCounts(since, until));
        for (PendingRecord record : pending.values()) {
            GameRecord summary = record.summary;
            long ended = summary.getEndedAt();
            if (ended < since || ended >= until || summary.getGameType() == null) continue;
            int humans = 0;
            for (GameRecord.Participant participant : summary.getParticipants()) {
                if (!participant.isAi() && ++humans >= 2) {
                    counts.merge(summary.getGameType().toUpperCase(java.util.Locale.ROOT), 1L, Long::sum);
                    break;
                }
            }
        }
        return counts;
    }

    /**
     * 运营指标用的全量摘要快照（最新在前）。
     *
     * 返回摘要副本，避免调用方修改索引或仍在等待持久化的快照。
     * 抽这个方法而不是把字段暴露出去，是为了让 OpsMetricsService 不必知道 recent 的锁细节。
     */
    public synchronized List<GameRecord> snapshotRecentRecords() {
        return recent.stream().map(GameRecordService::stripMoves).collect(Collectors.toList());
    }

    /**
     * 某持久身份最近一次真人对局使用的昵称（好友列表离线展示用）。
     * 摘要索引最新的在前，找到即返回；该身份没有真人记录返回 null。
     */
    public synchronized String getLastKnownName(String identityId) {
        if (identityId == null) return null;
        init();
        List<PendingRecord> unsaved = new ArrayList<>(pending.values());
        Collections.reverse(unsaved);
        for (PendingRecord record : unsaved) {
            GameRecord.Participant participant = findParticipantByIdentity(record.summary, identityId);
            if (participant != null && !participant.isAi() && participant.getName() != null) {
                return participant.getName();
            }
        }
        return archive.lastName(identityId);
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
                init();
                PendingRecord unsaved = pending.get(id);
                if (unsaved != null) return gson.fromJson(gson.toJson(unsaved.detail), GameRecord.class);
                return archive.get(id);
            }
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
            if (r.getEventId() != null) {
                view.put("eventId", r.getEventId());
                view.put("eventTitle", r.getEventTitle());
                view.put("eventXpPercent", r.getEventXpPercent());
            }
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
        if (!awaitPending(10000)) throw new IllegalStateException("对局存档队列未在时限内清空");
    }

    private synchronized boolean awaitPending(long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!pending.isEmpty()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            try { TimeUnit.NANOSECONDS.timedWait(this, remaining); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return true;
    }

    /** 只报告数量，不暴露身份或对局明细；持久化失败时健康检查应及时变红。 */
    public synchronized Map<String, Object> getStorageStatus() {
        long failed = pending.values().stream().filter(record -> record.failures > 0).count();
        return Map.of("ready", initialized, "pendingRecords", pending.size(), "failedRecords", failed);
    }

    /** 便于测试：直接从 map 里查某玩家的记录条数 */
    synchronized int indexedPlayerCount(String playerName) {
        LinkedList<GameRecord> list = byPlayer.get(playerName);
        return list == null ? 0 : list.size();
    }

}
