package com.gomoku.game;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 产品事件漏斗：进入大厅 → 选择玩法 → 成功入座 → 开局 → 完局 → 再来一局。
 *
 * 为什么单独做事件，而不是继续从对局记录派生：
 * - 对局记录只能看到「已经打完」的人，看不到进了大厅却没开局、匹配失败或等待中离开的流失。
 * - 这些阶段必须由服务端在真实边界上记录，前端无法伪造开局/完局/再来一局。
 *
 * 隐私与容量：
 * - 不落原始游客身份，只保存「服务端盐 + 身份」的 SHA-256 前 16 位；盐单独保存在 data/analytics.salt。
 * - 按自然日一个 JSON 文件，只保留最近 90 天；每个阶段/模式最多保留 10000 个去重身份，防止文件无限增长。
 * - 统计写入失败只记日志，绝不影响开局、落子或结算。磁盘仍是异步/去抖写入，不在对局线程里做重 IO。
 */
@Service
public class ProductAnalyticsService {

    private static final Logger logger = LoggerFactory.getLogger(ProductAnalyticsService.class);
    private static final Gson GSON = new GsonBuilder().create();
    private static final int MAX_DAYS = 90;
    private static final int MAX_USERS_PER_BUCKET = 10_000;
    private static final int HASH_HEX_LENGTH = 16;
    private static final long FLUSH_INTERVAL_SECONDS = 10;
    private static final String[] WAIT_LABELS = {"<1s", "1–5s", "5–15s", "15–30s", "30–60s", "≥60s"};

    public enum Stage {
        LOBBY_ENTER("lobbyEnter", "进入大厅"),
        RECOMMEND_SHOWN("recommendShown", "推荐曝光"),
        MODE_SELECT("modeSelect", "选择玩法"),
        MATCH_START("matchStart", "成功入座"),
        GAME_START("gameStart", "开局"),
        GAME_FINISH("gameFinish", "完局"),
        REMATCH("rematch", "再来一局");

        final String key;
        final String label;

        Stage(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    /** 入口模式：由客户端声明，服务端再按房间事实校正（有电脑即人机）。 */
    public static final List<String> MODES = List.of(
            "QUICK", "RECOMMENDED", "PRIVATE", "CODE", "AI", "PARTY", "DUEL");

    private final Path dir;
    private final Path saltPath;
    private final Map<LocalDate, DayBucket> days = new TreeMap<>();
    /** 有变更的日期集合：跨日时只写「今天」会丢掉刚跨过午夜那批旧日事件。 */
    private final Set<LocalDate> dirtyDays = new HashSet<>();
    private volatile String salt;
    private volatile LocalDate lastMaintainedDay;
    private ScheduledExecutorService flusher;

    /**
     * Spring 装配入口：目录可配置，测试/多实例部署可各自隔离。
     * 默认仍是应用工作目录下的 data/analytics，与既有运行数据保持一致。
     */
    @Autowired
    public ProductAnalyticsService(
            @Value("${gomoku.analytics.directory:data/analytics}") String directory) {
        this(Paths.get(directory));
    }

    /** 供测试注入隔离目录。 */
    public ProductAnalyticsService(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
        this.saltPath = this.dir.resolveSibling("analytics.salt");
    }

    @PostConstruct
    public synchronized void init() {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            logger.warn("无法创建产品事件目录 {}，本次运行只在内存中统计", dir, e);
        }
        loadSalt();
        loadDays();
        prune();
        flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Product-Analytics-Flush");
            t.setDaemon(true);
            return t;
        });
        flusher.scheduleWithFixedDelay(this::flushSafely,
                FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        if (flusher != null) flusher.shutdownNow();
        flushSafely();
    }

    /** 当前时间（测试可覆盖）。 */
    long now() {
        return System.currentTimeMillis();
    }

    /** 统计时区（测试可覆盖，必须与运营看板一致）。 */
    ZoneId zone() {
        return ZoneId.systemDefault();
    }

    // ============ 记录入口：所有方法都不向调用方抛异常 ============

    public void recordLobbyEnter(String identityId) {
        record(Stage.LOBBY_ENTER, identityId, null, 0);
    }

    /** 推荐已实际展示给当前身份；一次响应只记一条曝光事件。 */
    public void recordRecommendationShown(String identityId, int games) {
        if (games <= 0) return;
        record(Stage.RECOMMEND_SHOWN, identityId, null, 0);
    }

    public void recordModeSelect(String identityId) {
        record(Stage.MODE_SELECT, identityId, null, 0);
    }

    /** 带入口玩法的选择事件；未声明玩法的旧调用仍按中性桶记录。 */
    public void recordModeSelect(String identityId, String mode) {
        record(Stage.MODE_SELECT, identityId, mode, 0);
    }

    public void recordMatchStart(String identityId, String mode) {
        record(Stage.MATCH_START, identityId, mode, 0);
    }

    public void recordGameStart(String identityId, String mode, long waitMs) {
        record(Stage.GAME_START, identityId, mode, waitMs);
    }

    public void recordGameFinish(String identityId, String mode) {
        record(Stage.GAME_FINISH, identityId, mode, 0);
    }

    public void recordRematch(String identityId, String mode) {
        record(Stage.REMATCH, identityId, mode, 0);
    }

    public void recordMatchFail(String reason) {
        try {
            synchronized (this) {
                LocalDate day = today();
                DayBucket bucket = dayFor(day);
                bucket.matchFail++;
                String safe = reason == null || reason.isBlank() ? "UNKNOWN" : reason;
                bucket.matchFailReasons.merge(safe, 1, Integer::sum);
                dirtyDays.add(day);
            }
        } catch (RuntimeException e) {
            logger.warn("记录匹配失败事件失败（不影响对局）", e);
        }
    }

    public void recordReconnect(boolean success) {
        try {
            synchronized (this) {
                LocalDate day = today();
                DayBucket bucket = dayFor(day);
                if (success) bucket.reconnectSuccess++;
                else bucket.reconnectFailure++;
                dirtyDays.add(day);
            }
        } catch (RuntimeException e) {
            logger.warn("记录重连事件失败（不影响对局）", e);
        }
    }

    private void record(Stage stage, String identityId, String mode, long waitMs) {
        if (identityId == null || identityId.isBlank()) return;
        try {
            synchronized (this) {
                LocalDate day = today();
                DayBucket bucket = dayFor(day);
                String safeMode = normalizeMode(mode);
                String hash = hash(identityId);
                // 大厅进入没有玩法维度：用中性键保存，避免把所有访客都算进 QUICK。
                String modeKey = mode == null || mode.isBlank() ? "NEUTRAL" : safeMode;
                bucket.counts.computeIfAbsent(stage.key, k -> new LinkedHashMap<>())
                        .merge(modeKey, 1, Integer::sum);
                Set<String> users = bucket.users
                        .computeIfAbsent(stage.key, k -> new LinkedHashMap<>())
                        .computeIfAbsent(modeKey, k -> new LinkedHashSet<>());
                if (users.size() < MAX_USERS_PER_BUCKET || users.contains(hash)) users.add(hash);
                if (stage == Stage.GAME_START && waitMs >= 0) {
                    bucket.wait.computeIfAbsent(modeKey, k -> new WaitBucket()).add(waitMs);
                }
                dirtyDays.add(day);
            }
        } catch (RuntimeException e) {
            logger.warn("记录产品事件失败（不影响对局）stage={}", stage.key, e);
        }
    }

    /** 把客户端入口收敛到固定集合，未知值按中性入口处理。 */
    public static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) return "QUICK";
        String upper = mode.trim().toUpperCase(Locale.ROOT);
        return MODES.contains(upper) ? upper : "QUICK";
    }

    // ============ 运营看板聚合 ============

    /**
     * 窗口漏斗与质量指标。
     * 所有用户数都是窗口内按加盐哈希去重；阶段事件数包含重复访问。
     */
    public synchronized Map<String, Object> overview(int requestedDays) {
        int window = Math.max(1, Math.min(requestedDays, MAX_DAYS));
        LocalDate today = today();
        LocalDate from = today.minusDays(window - 1L);

        Map<String, Set<String>> allUsers = new LinkedHashMap<>();
        Map<String, Set<String>> humanUsers = new LinkedHashMap<>();
        Map<String, Set<String>> aiUsers = new LinkedHashMap<>();
        Map<String, Integer> allEvents = new LinkedHashMap<>();
        Map<String, Map<String, Set<String>>> usersByMode = new LinkedHashMap<>();
        Map<String, Integer> matchStartEventsByMode = new LinkedHashMap<>();
        Map<String, Integer> gameStartEventsByMode = new LinkedHashMap<>();
        Map<String, Integer> finishEventsByMode = new LinkedHashMap<>();
        Map<String, Integer> rematchEventsByMode = new LinkedHashMap<>();
        Map<String, Long> waitCount = new LinkedHashMap<>();
        Map<String, Long> waitTotal = new LinkedHashMap<>();
        Map<String, long[]> waitBuckets = new LinkedHashMap<>();
        int matchFail = 0;
        Map<String, Integer> matchFailReasons = new LinkedHashMap<>();
        int reconnectSuccess = 0;
        int reconnectFailure = 0;
        Set<String> recommendationUsers = new HashSet<>();
        Set<String> recommendedMatchUsers = new HashSet<>();
        Set<String> recommendedFinishUsers = new HashSet<>();
        int recommendationEvents = 0;
        int recommendedStarts = 0;
        int recommendedFinishes = 0;
        List<Map<String, Object>> dailyFunnel = new ArrayList<>();

        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            DayBucket bucket = days.get(d);
            Map<String, Object> dayRow = new LinkedHashMap<>();
            dayRow.put("date", d.toString());
            if (bucket == null) {
                for (Stage stage : Stage.values()) dayRow.put(stage.key, 0);
                dailyFunnel.add(dayRow);
                continue;
            }
            matchFail += bucket.matchFail;
            bucket.matchFailReasons.forEach((k, v) -> matchFailReasons.merge(k, v, Integer::sum));
            reconnectSuccess += bucket.reconnectSuccess;
            reconnectFailure += bucket.reconnectFailure;
            Map<String, Set<String>> recommendationShown = bucket.users.get(Stage.RECOMMEND_SHOWN.key);
            if (recommendationShown != null) {
                for (Set<String> hashes : recommendationShown.values()) recommendationUsers.addAll(hashes);
            }
            Map<String, Integer> recommendationCounts = bucket.counts.get(Stage.RECOMMEND_SHOWN.key);
            if (recommendationCounts != null) {
                for (Integer amount : recommendationCounts.values()) recommendationEvents += amount;
            }
            Map<String, Set<String>> recommendedStages = bucket.users.get(Stage.MATCH_START.key);
            if (recommendedStages != null) {
                recommendedMatchUsers.addAll(recommendedStages.getOrDefault("RECOMMENDED", Set.of()));
            }
            recommendedStages = bucket.users.get(Stage.GAME_START.key);
            if (recommendedStages != null) {
                recommendedStarts += bucket.counts.getOrDefault(Stage.GAME_START.key, Map.of())
                        .getOrDefault("RECOMMENDED", 0);
            }
            Map<String, Set<String>> recommendedFinishesByMode = bucket.users.get(Stage.GAME_FINISH.key);
            if (recommendedFinishesByMode != null) {
                recommendedFinishUsers.addAll(recommendedFinishesByMode.getOrDefault("RECOMMENDED", Set.of()));
            }
            Map<String, Integer> recommendedFinishCounts = bucket.counts.get(Stage.GAME_FINISH.key);
            if (recommendedFinishCounts != null) {
                recommendedFinishes += recommendedFinishCounts.getOrDefault("RECOMMENDED", 0);
            }

            for (Stage stage : Stage.values()) {
                Map<String, Set<String>> modes = bucket.users.get(stage.key);
                int dayUsers = 0;
                if (modes != null) {
                    Set<String> dayUnion = new HashSet<>();
                    for (Map.Entry<String, Set<String>> entry : modes.entrySet()) {
                        String mode = entry.getKey();
                        Set<String> hashes = entry.getValue();
                        dayUnion.addAll(hashes);
                        allUsers.computeIfAbsent(stage.key, k -> new HashSet<>()).addAll(hashes);
                        if ("AI".equals(mode)) {
                            aiUsers.computeIfAbsent(stage.key, k -> new HashSet<>()).addAll(hashes);
                        } else {
                            humanUsers.computeIfAbsent(stage.key, k -> new HashSet<>()).addAll(hashes);
                        }
                        usersByMode.computeIfAbsent(mode, k -> new LinkedHashMap<>())
                                .computeIfAbsent(stage.key, k -> new HashSet<>()).addAll(hashes);
                    }
                    dayUsers = dayUnion.size();
                }
                dayRow.put(stage.key, dayUsers);
                Map<String, Integer> countModes = bucket.counts.get(stage.key);
                if (countModes != null) {
                    for (Integer n : countModes.values()) allEvents.merge(stage.key, n, Integer::sum);
                }
            }
            dailyFunnel.add(dayRow);

            for (Map.Entry<String, WaitBucket> entry : bucket.wait.entrySet()) {
                WaitBucket w = entry.getValue();
                if (w == null || w.count <= 0) continue;
                waitCount.merge(entry.getKey(), (long) w.count, Long::sum);
                waitTotal.merge(entry.getKey(), w.totalMs, Long::sum);
                long[] aggregate = waitBuckets.computeIfAbsent(entry.getKey(), k -> new long[WAIT_LABELS.length]);
                for (int i = 0; i < WAIT_LABELS.length && i < w.buckets.length; i++) aggregate[i] += w.buckets[i];
            }
            Map<String, Integer> starts = bucket.counts.get(Stage.GAME_START.key);
            Map<String, Integer> finishes = bucket.counts.get(Stage.GAME_FINISH.key);
            Map<String, Integer> rematches = bucket.counts.get(Stage.REMATCH.key);
            Map<String, Integer> matches = bucket.counts.get(Stage.MATCH_START.key);
            if (matches != null) matches.forEach((k, v) -> matchStartEventsByMode.merge(k, v, Integer::sum));
            if (starts != null) starts.forEach((k, v) -> gameStartEventsByMode.merge(k, v, Integer::sum));
            if (finishes != null) finishes.forEach((k, v) -> finishEventsByMode.merge(k, v, Integer::sum));
            if (rematches != null) rematches.forEach((k, v) -> rematchEventsByMode.merge(k, v, Integer::sum));
        }

        int lobbyUsers = size(allUsers.get(Stage.LOBBY_ENTER.key));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("windowDays", window);
        result.put("from", from.toString());
        result.put("to", today.toString());
        result.put("stages", stageRows(allUsers, allEvents, lobbyUsers));
        result.put("humanStages", stageRows(humanUsers, null, size(humanUsers.get(Stage.LOBBY_ENTER.key))));
        result.put("aiStages", stageRows(aiUsers, null, size(aiUsers.get(Stage.LOBBY_ENTER.key))));
        Map<String, Object> recommendation = new LinkedHashMap<>();
        recommendation.put("shownUsers", recommendationUsers.size());
        recommendation.put("shownEvents", recommendationEvents);
        recommendation.put("matchUsers", recommendedMatchUsers.size());
        recommendation.put("matchRate", percent(recommendedMatchUsers.size(), recommendationUsers.size()));
        recommendation.put("startEvents", recommendedStarts);
        recommendation.put("finishUsers", recommendedFinishUsers.size());
        recommendation.put("finishEvents", recommendedFinishes);
        recommendation.put("finishRate", percent(recommendedFinishes, recommendedStarts));
        result.put("recommendation", recommendation);
        result.put("gameModes", gameModeRows(usersByMode, gameStartEventsByMode, finishEventsByMode, rematchEventsByMode));
        result.put("wait", waitSummary(waitCount, waitTotal, waitBuckets));
        result.put("matchFail", matchFailSummary(matchFail, matchFailReasons, matchStartEventsByMode));
        result.put("reconnect", reconnectSummary(reconnectSuccess, reconnectFailure));
        result.put("daily", dailyFunnel);
        result.put("definitions", definitions());
        return result;
    }

    private static List<Map<String, Object>> stageRows(Map<String, Set<String>> users,
                                                       Map<String, Integer> events,
                                                       int baseUsers) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Stage stage : Stage.values()) {
            int count = size(users.get(stage.key));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", stage.key);
            row.put("label", stage.label);
            row.put("users", count);
            row.put("events", events == null ? count : events.getOrDefault(stage.key, 0));
            double conversion = baseUsers <= 0 ? 0.0 : Math.round(count * 1000.0 / baseUsers) / 10.0;
            row.put("conversion", conversion);
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> gameModeRows(Map<String, Map<String, Set<String>>> usersByMode,
                                                          Map<String, Integer> starts,
                                                          Map<String, Integer> finishes,
                                                          Map<String, Integer> rematches) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String mode : MODES) {
            int startCount = starts.getOrDefault(mode, 0);
            int finishCount = finishes.getOrDefault(mode, 0);
            if (startCount == 0 && finishCount == 0 && !usersByMode.containsKey(mode)) continue;
            Map<String, Set<String>> stageUsers = usersByMode.getOrDefault(mode, Collections.emptyMap());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mode", mode);
            row.put("label", modeLabel(mode));
            row.put("starts", startCount);
            row.put("finishes", finishCount);
            row.put("rematches", rematches.getOrDefault(mode, 0));
            row.put("startUsers", size(stageUsers.get(Stage.GAME_START.key)));
            row.put("finishUsers", size(stageUsers.get(Stage.GAME_FINISH.key)));
            row.put("finishRate", startCount == 0 ? 0.0
                    : Math.round(finishCount * 1000.0 / startCount) / 10.0);
            row.put("rematchRate", finishCount == 0 ? 0.0
                    : Math.round(rematches.getOrDefault(mode, 0) * 1000.0 / finishCount) / 10.0);
            rows.add(row);
        }
        rows.sort((a, b) -> Integer.compare((Integer) b.get("starts"), (Integer) a.get("starts")));
        return rows;
    }

    private static Map<String, Object> waitSummary(Map<String, Long> counts, Map<String, Long> totals,
                                                   Map<String, long[]> bucketsByMode) {
        long count = 0;
        long total = 0;
        long[] merged = new long[WAIT_LABELS.length];
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            String mode = entry.getKey();
            if ("AI".equals(mode) || "PARTY".equals(mode) || "DUEL".equals(mode)) continue;
            count += entry.getValue();
            total += totals.getOrDefault(mode, 0L);
            long[] values = bucketsByMode.get(mode);
            if (values != null) for (int i = 0; i < merged.length; i++) merged[i] += values[i];
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("samples", count);
        result.put("avgMs", count == 0 ? 0 : Math.round(total * 1.0 / count));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < WAIT_LABELS.length; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", WAIT_LABELS[i]);
            row.put("count", merged[i]);
            rows.add(row);
        }
        result.put("buckets", rows);
        return result;
    }

    private static Map<String, Object> matchFailSummary(int fails, Map<String, Integer> reasons,
                                                        Map<String, Integer> startsByMode) {
        int starts = 0;
        for (Integer value : startsByMode.values()) starts += value;
        long attempts = (long) starts + fails;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", fails);
        result.put("attempts", attempts);
        result.put("rate", attempts == 0 ? 0.0 : Math.round(fails * 1000.0 / attempts) / 10.0);
        result.put("reasons", new LinkedHashMap<>(reasons));
        return result;
    }

    private static Map<String, Object> reconnectSummary(int success, int failure) {
        int total = success + failure;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("failure", failure);
        result.put("rate", total == 0 ? null : Math.round(success * 1000.0 / total) / 10.0);
        return result;
    }

    private static List<String> definitions() {
        return Arrays.asList(
                "漏斗按窗口内去重身份统计；身份仅保存加盐 SHA-256 前 16 位，不保存游客 ID",
                "进入大厅 = 完成大厅身份绑定；选择玩法 = 提交带玩法的对局请求；成功入座 = 服务端接受并入座",
                "开局/完局/再来一局 = 服务端实际开始、结束及同一房间再开，前端无法伪造",
                "推荐曝光 = 服务端实际返回推荐；推荐入座/开局/完局只统计 entryMode=RECOMMENDED 的真实服务端事件",
                "等待时长仅统计快速匹配、创建房间与房号加入，不含人机、组队和约战",
                "匹配失败率 = 被服务端拒绝的入房请求 /（成功入座 + 被拒请求）",
                "模式由客户端入口声明，服务端再按房间事实校正：只要座位上有电脑就归为人机"
        );
    }

    private static double percent(long numerator, long denominator) {
        return denominator <= 0 ? 0.0 : Math.round(numerator * 1000.0 / denominator) / 10.0;
    }

    private static String modeLabel(String mode) {
        switch (mode) {
            case "QUICK": return "快速匹配";
            case "RECOMMENDED": return "推荐匹配";
            case "PRIVATE": return "创建房间";
            case "CODE": return "房号加入";
            case "AI": return "人机对战";
            case "PARTY": return "组队开局";
            case "DUEL": return "好友约战";
            default: return mode;
        }
    }

    private static int size(Set<String> set) {
        return set == null ? 0 : set.size();
    }

    // ============ 持久化 ============

    private DayBucket dayFor(LocalDate date) {
        DayBucket bucket = days.computeIfAbsent(date, d -> {
            DayBucket created = new DayBucket();
            created.date = d.toString();
            return created;
        });
        if (!date.equals(lastMaintainedDay)) {
            lastMaintainedDay = date;
            prune();
        }
        return bucket;
    }

    private LocalDate today() {
        return Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate();
    }

    /** 去抖落盘：只在调度线程执行，序列化在锁内、文件写入在锁外。 */
    private void flushSafely() {
        try {
            flush();
        } catch (RuntimeException e) {
            logger.warn("产品事件落盘失败，将在下个周期重试", e);
        }
    }

    synchronized String snapshotJson() {
        return GSON.toJson(days);
    }

    public void flush() {
        Map<LocalDate, String> payload = new LinkedHashMap<>();
        synchronized (this) {
            if (dirtyDays.isEmpty()) return;
            for (LocalDate date : new ArrayList<>(dirtyDays)) {
                DayBucket bucket = days.get(date);
                if (bucket != null) payload.put(date, GSON.toJson(bucket));
            }
            dirtyDays.clear();
        }
        try {
            Files.createDirectories(dir);
            for (Map.Entry<LocalDate, String> entry : payload.entrySet()) {
                Path file = dayFile(entry.getKey());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, entry.getValue(), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            synchronized (this) {
                // 任一日失败都整体重试；重复写已成功的日文件是幂等的。
                dirtyDays.addAll(payload.keySet());
            }
            throw new IllegalStateException("无法写入产品事件文件", e);
        }
    }

    private void loadDays() {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (!name.matches("\\d{4}-\\d{2}-\\d{2}\\.json")) continue;
                try {
                    DayBucket bucket = GSON.fromJson(Files.readString(file), DayBucket.class);
                    if (bucket == null || bucket.date == null) throw new IllegalArgumentException("空文件");
                    LocalDate date = LocalDate.parse(bucket.date);
                    if (!name.equals(bucket.date + ".json")) throw new IllegalArgumentException("日期与文件名不一致");
                    bucket.normalize();
                    days.put(date, bucket);
                } catch (RuntimeException e) {
                    logger.warn("跳过损坏的产品事件文件 {}", file.getFileName());
                }
            }
        } catch (IOException e) {
            logger.warn("读取产品事件目录失败，将从空数据开始", e);
        }
    }

    private void loadSalt() {
        try {
            if (Files.exists(saltPath)) {
                String stored = Files.readString(saltPath, StandardCharsets.UTF_8).trim();
                if (stored.matches("[0-9a-f]{64}")) {
                    salt = stored;
                    return;
                }
            }
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            String generated = hex(bytes);
            Files.createDirectories(saltPath.getParent());
            Path tmp = saltPath.resolveSibling(saltPath.getFileName() + ".tmp");
            Files.writeString(tmp, generated, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, saltPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, saltPath, StandardCopyOption.REPLACE_EXISTING);
            }
            salt = generated;
        } catch (IOException e) {
            // 极端情况下宁可本进程内可用，也不能让统计文件问题阻断游戏启动。
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            salt = hex(bytes);
            logger.warn("无法持久化产品事件盐，本次运行的重启前去重仍可用", e);
        }
    }

    private String hash(String identityId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((salt == null ? "" : salt).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(identityId.getBytes(StandardCharsets.UTF_8));
            String full = hex(digest.digest());
            return full.substring(0, HASH_HEX_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少 SHA-256", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            sb.append(Character.forDigit((value >> 4) & 0xF, 16));
            sb.append(Character.forDigit(value & 0xF, 16));
        }
        return sb.toString();
    }

    private Path dayFile(LocalDate date) {
        return dir.resolve(date + ".json");
    }

    /** 只保留最近 MAX_DAYS 天；删除的是本服务命名的按日文件，不触碰其他数据。 */
    private void prune() {
        LocalDate cutoff = today().minusDays(MAX_DAYS - 1L);
        days.keySet().removeIf(d -> d.isBefore(cutoff));
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (!name.matches("\\d{4}-\\d{2}-\\d{2}\\.json")) continue;
                try {
                    LocalDate date = LocalDate.parse(name.substring(0, 10));
                    if (date.isBefore(cutoff)) Files.deleteIfExists(file);
                } catch (RuntimeException ignored) {
                    // 文件名不合法时不当作本服务文件处理
                }
            }
        } catch (IOException e) {
            logger.warn("清理过期产品事件文件失败", e);
        }
    }

    /** 测试辅助：当前内存中的天数。 */
    synchronized int retainedDays() {
        return days.size();
    }

    // ============ 数据模型（Gson 可直接读写） ============

    static class DayBucket {
        String date;
        Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
        Map<String, Map<String, Set<String>>> users = new LinkedHashMap<>();
        Map<String, WaitBucket> wait = new LinkedHashMap<>();
        int matchFail;
        Map<String, Integer> matchFailReasons = new LinkedHashMap<>();
        int reconnectSuccess;
        int reconnectFailure;

        /** 兼容手工编辑/旧文件：把 null 补成空结构，避免一次损坏拖垮整日统计。 */
        void normalize() {
            if (counts == null) counts = new LinkedHashMap<>();
            if (users == null) users = new LinkedHashMap<>();
            if (wait == null) wait = new LinkedHashMap<>();
            if (matchFailReasons == null) matchFailReasons = new LinkedHashMap<>();
            counts.replaceAll((k, v) -> v == null ? new LinkedHashMap<>() : v);
            users.replaceAll((k, v) -> v == null ? new LinkedHashMap<>() : v);
            users.replaceAll((stage, modes) -> {
                modes.replaceAll((mode, hashes) -> hashes == null ? new LinkedHashSet<>() : hashes);
                return modes;
            });
            wait.replaceAll((k, v) -> {
                if (v == null) return new WaitBucket();
                if (v.buckets == null || v.buckets.length != WAIT_LABELS.length) {
                    long[] fixed = new long[WAIT_LABELS.length];
                    if (v.buckets != null) {
                        System.arraycopy(v.buckets, 0, fixed, 0, Math.min(v.buckets.length, fixed.length));
                    }
                    v.buckets = fixed;
                }
                return v;
            });
        }
    }

    static class WaitBucket {
        int count;
        long totalMs;
        long[] buckets = new long[WAIT_LABELS.length];

        void add(long waitMs) {
            long ms = Math.max(0, waitMs);
            count++;
            totalMs += ms;
            int index;
            if (ms < 1000) index = 0;
            else if (ms < 5000) index = 1;
            else if (ms < 15000) index = 2;
            else if (ms < 30000) index = 3;
            else if (ms < 60000) index = 4;
            else index = 5;
            if (buckets == null) buckets = new long[WAIT_LABELS.length];
            buckets[index]++;
        }
    }
}
