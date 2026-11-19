package com.gomoku.game;

import com.google.gson.Gson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 限时活动：指定玩法在一段时间内获得额外经验加成。
 *
 * 为什么加成要写进对局记录，而不是查询时现算：
 * 经验是「对局记录的函数」，活动结束后如果现算，历史经验会整体缩水甚至掉级；
 * 结算时把当时的活动标题与倍率刻进记录，历史收益才是稳定的。
 * 同一局只取「当时覆盖该玩法、加成最高」的一个活动，避免叠加失控。
 *
 * 活动对真人局与人机局一视同仁：单人玩家也需要成长反馈，
 * 而 ELO 与竞技战绩仍只认真人计分局，互不影响。
 */
@Service
public class EventService {

    static final int MAX_TITLE_LENGTH = 40;
    static final int MAX_KEPT = 100;
    static final int MAX_LIVE = 5;
    static final int MIN_PERCENT = 10;
    static final int MAX_PERCENT = 200;

    public static final String ALL_GAMES = "ALL";

    /** 时长选项（小时），供管理端下拉框直接使用 */
    public static final Map<String, Long> DURATION_HOURS = new LinkedHashMap<>();
    static {
        DURATION_HOURS.put("24h", 24L);
        DURATION_HOURS.put("3d", 24L * 3);
        DURATION_HOURS.put("7d", 24L * 7);
        DURATION_HOURS.put("14d", 24L * 14);
        DURATION_HOURS.put("30d", 24L * 30);
    }

    /** 管理端推荐的加成档位 */
    public static final List<Integer> PERCENT_OPTIONS = Collections.unmodifiableList(Arrays.asList(25, 50, 100));

    private final Path path;
    private final Gson gson = new Gson();

    /** 新的在前 */
    private final LinkedList<Event> events = new LinkedList<>();

    @Autowired
    public EventService() {
        this(Paths.get("data", "events.json"));
    }

    /** 供测试注入临时路径 */
    public EventService(Path path) {
        this.path = path;
    }

    /** 一个限时活动 */
    public static class Event {
        public String id;
        public String title;
        /** GameType.name() 或 ALL */
        public String gameType;
        /** 额外经验百分比：50 = 基础经验之外再 +50% */
        public int xpPercent;
        public long startsAt;
        public long endsAt;
        /** 管理员主动暂停 */
        public boolean active;
    }

    @PostConstruct
    public synchronized void init() {
        if (!Files.exists(path)) return;
        try {
            Event[] stored = gson.fromJson(Files.readString(path), Event[].class);
            events.clear();
            if (stored != null) {
                for (Event item : stored) {
                    if (item != null && item.id != null && item.title != null) events.add(item);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法读取活动文件", e);
        }
    }

    /** 当前时间（测试可覆盖） */
    long now() { return System.currentTimeMillis(); }

    public synchronized Event create(String title, String gameType, int xpPercent, String duration) {
        String cleanTitle = title == null ? "" : title.trim();
        if (cleanTitle.isEmpty()) throw new IllegalArgumentException("活动标题不能为空");
        if (cleanTitle.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("活动标题不能超过 " + MAX_TITLE_LENGTH + " 字");
        }
        String type = normalizeGameType(gameType);
        if (xpPercent < MIN_PERCENT || xpPercent > MAX_PERCENT) {
            throw new IllegalArgumentException("经验加成需在 " + MIN_PERCENT + "%–" + MAX_PERCENT + "% 之间");
        }
        Long hours = DURATION_HOURS.get(duration == null || duration.trim().isEmpty() ? "7d" : duration.trim());
        if (hours == null) throw new IllegalArgumentException("未知的活动时长选项");

        long ts = now();
        Event item = new Event();
        item.id = "EVT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        item.title = cleanTitle;
        item.gameType = type;
        item.xpPercent = xpPercent;
        item.startsAt = ts;
        item.endsAt = ts + hours * 3600_000L;
        item.active = true;
        events.addFirst(item);
        while (events.size() > MAX_KEPT) events.removeLast();
        persist();
        return copy(item);
    }

    /** 大厅/结算使用：当前生效的活动（最多 5 个），加成高的在前 */
    public synchronized List<Event> listLive() {
        long ts = now();
        List<Event> result = new ArrayList<>();
        for (Event item : events) {
            if (isLive(item, ts)) result.add(copy(item));
        }
        result.sort(Comparator.comparingInt((Event e) -> e.xpPercent).reversed()
                .thenComparingLong(e -> e.endsAt));
        if (result.size() > MAX_LIVE) return new ArrayList<>(result.subList(0, MAX_LIVE));
        return result;
    }

    /** 管理端列表：全部记录（含已下线/已过期） */
    public synchronized List<Event> listAll() {
        List<Event> result = new ArrayList<>(events.size());
        for (Event item : events) result.add(copy(item));
        return result;
    }

    /**
     * 对指定玩法、指定时刻生效的最佳活动；没有则返回 null。
     * 并列时取后创建的那个（列表新→旧，只在严格更高时替换）。
     */
    public synchronized Event activeFor(GameType gameType, long ts) {
        if (gameType == null) return null;
        Event best = null;
        for (Event item : events) {
            if (!isLive(item, ts)) continue;
            if (!ALL_GAMES.equals(item.gameType) && !gameType.name().equals(item.gameType)) continue;
            if (best == null || item.xpPercent > best.xpPercent) best = item;
        }
        return best == null ? null : copy(best);
    }

    public synchronized Event setActive(String id, boolean active) {
        for (Event item : events) {
            if (!item.id.equals(id)) continue;
            item.active = active;
            persist();
            return copy(item);
        }
        return null;
    }

    public synchronized boolean delete(String id) {
        Iterator<Event> it = events.iterator();
        while (it.hasNext()) {
            if (!it.next().id.equals(id)) continue;
            it.remove();
            persist();
            return true;
        }
        return false;
    }

    public static String gameLabel(String gameType) {
        if (gameType == null) return "";
        if (ALL_GAMES.equals(gameType)) return "全部玩法";
        try {
            return GameType.valueOf(gameType).getDisplayName();
        } catch (IllegalArgumentException e) {
            return gameType;
        }
    }

    private static String normalizeGameType(String gameType) {
        String value = gameType == null ? "" : gameType.trim().toUpperCase();
        if (value.isEmpty()) return ALL_GAMES;
        if (ALL_GAMES.equals(value)) return ALL_GAMES;
        try {
            return GameType.valueOf(value).name();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的玩法");
        }
    }

    private static boolean isLive(Event item, long ts) {
        return item.active && ts >= item.startsAt && ts < item.endsAt;
    }

    private static Event copy(Event source) {
        Event copy = new Event();
        copy.id = source.id;
        copy.title = source.title;
        copy.gameType = source.gameType;
        copy.xpPercent = source.xpPercent;
        copy.startsAt = source.startsAt;
        copy.endsAt = source.endsAt;
        copy.active = source.active;
        return copy;
    }

    private synchronized void persist() {
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, gson.toJson(events), StandardCharsets.UTF_8);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) {
            throw new IllegalStateException("无法保存活动", e);
        }
    }
}
