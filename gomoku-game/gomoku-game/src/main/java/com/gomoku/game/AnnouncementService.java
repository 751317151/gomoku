package com.gomoku.game;

import com.google.gson.Gson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 运营公告：管理员发布的全服消息（活动、维护、版本说明），持久化 data/announcements.json。
 *
 * 为什么是「服务端存 + 大厅拉取」而不是走 WebSocket 广播：
 * 公告是低频、必须可追溯的内容，玩家晚到也应该看到；拉取天然支持重连和刷新，
 * 不需要为离线玩家补发消息。展示时按级别排序、按 id 记住「已读关闭」。
 *
 * 设计取舍：
 * - 有效期由服务端判定（active && 未过期），客户端只做展示，避免各端时钟不一致；
 * - 只保留最近 100 条，超出部分从最旧的开始丢弃，防止运营文件无限膨胀；
 * - 同时最多返回 3 条生效公告，避免大厅被横幅淹没。
 */
@Service
public class AnnouncementService {

    static final int MAX_TITLE_LENGTH = 40;
    static final int MAX_BODY_LENGTH = 500;
    static final int MAX_KEPT = 100;
    static final int MAX_ACTIVE = 3;

    /** 有效期选项（小时）：0 = 永久 */
    public static final Map<String, Long> TTL_HOURS = new LinkedHashMap<>();
    static {
        TTL_HOURS.put("24h", 24L);
        TTL_HOURS.put("7d", 24L * 7);
        TTL_HOURS.put("30d", 24L * 30);
        TTL_HOURS.put("forever", 0L);
    }

    /** 级别：普通 / 重要 / 维护 */
    public static final Set<String> LEVELS = new LinkedHashSet<>(Arrays.asList("info", "important", "maintenance"));

    private final Path path;
    private final Gson gson = new Gson();

    /** 新的在前 */
    private final LinkedList<Announcement> announcements = new LinkedList<>();

    @Autowired
    public AnnouncementService() {
        this(Paths.get("data", "announcements.json"));
    }

    /** 供测试注入临时路径 */
    public AnnouncementService(Path path) {
        this.path = path;
    }

    /** 一条公告 */
    public static class Announcement {
        public String id;
        public String title;
        public String body;
        /** info / important / maintenance */
        public String level;
        public long createdAt;
        /** 到期时间；0 = 永久 */
        public long expiresAt;
        /** 管理员主动下线 */
        public boolean active;
    }

    @PostConstruct
    public synchronized void init() {
        if (!Files.exists(path)) return;
        try {
            Announcement[] stored = gson.fromJson(Files.readString(path), Announcement[].class);
            announcements.clear();
            if (stored != null) {
                for (Announcement item : stored) {
                    if (item != null && item.id != null && item.title != null) announcements.add(item);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法读取公告文件", e);
        }
    }

    /** 当前时间（测试可覆盖） */
    long now() { return System.currentTimeMillis(); }

    public synchronized Announcement create(String title, String body, String level, String ttl) {
        String cleanTitle = title == null ? "" : title.trim();
        String cleanBody = body == null ? "" : body.trim();
        if (cleanTitle.isEmpty()) throw new IllegalArgumentException("公告标题不能为空");
        if (cleanTitle.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("公告标题不能超过 " + MAX_TITLE_LENGTH + " 字");
        }
        if (cleanBody.isEmpty()) throw new IllegalArgumentException("公告内容不能为空");
        if (cleanBody.length() > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("公告内容不能超过 " + MAX_BODY_LENGTH + " 字");
        }
        String levelKey = level == null || level.trim().isEmpty() ? "info" : level.trim().toLowerCase();
        if (!LEVELS.contains(levelKey)) throw new IllegalArgumentException("未知的公告级别");
        Long hours = TTL_HOURS.get(ttl == null || ttl.trim().isEmpty() ? "7d" : ttl.trim());
        if (hours == null) throw new IllegalArgumentException("未知的有效期选项");

        long ts = now();
        Announcement item = new Announcement();
        item.id = "ANN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        item.title = cleanTitle;
        item.body = cleanBody;
        item.level = levelKey;
        item.createdAt = ts;
        item.expiresAt = hours == 0 ? 0 : ts + hours * 3600_000L;
        item.active = true;
        announcements.addFirst(item);
        while (announcements.size() > MAX_KEPT) announcements.removeLast();
        persist();
        return copy(item);
    }

    /** 大厅可见公告：生效、未过期，最多 3 条 */
    public synchronized List<Announcement> listActive() {
        List<Announcement> result = new ArrayList<>();
        long ts = now();
        for (Announcement item : announcements) {
            if (!item.active) continue;
            if (isExpired(item, ts)) continue;
            result.add(copy(item));
            if (result.size() >= MAX_ACTIVE) break;
        }
        return result;
    }

    /** 管理端列表：全部记录（含已下线/已过期） */
    public synchronized List<Announcement> listAll() {
        List<Announcement> result = new ArrayList<>(announcements.size());
        for (Announcement item : announcements) result.add(copy(item));
        return result;
    }

    public synchronized Announcement setActive(String id, boolean active) {
        for (Announcement item : announcements) {
            if (!item.id.equals(id)) continue;
            item.active = active;
            persist();
            return copy(item);
        }
        return null;
    }

    public synchronized boolean delete(String id) {
        Iterator<Announcement> it = announcements.iterator();
        while (it.hasNext()) {
            if (!it.next().id.equals(id)) continue;
            it.remove();
            persist();
            return true;
        }
        return false;
    }

    private static boolean isExpired(Announcement item, long ts) {
        return item.expiresAt > 0 && ts >= item.expiresAt;
    }

    private static Announcement copy(Announcement source) {
        Announcement copy = new Announcement();
        copy.id = source.id;
        copy.title = source.title;
        copy.body = source.body;
        copy.level = source.level;
        copy.createdAt = source.createdAt;
        copy.expiresAt = source.expiresAt;
        copy.active = source.active;
        return copy;
    }

    private synchronized void persist() {
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, gson.toJson(announcements), StandardCharsets.UTF_8);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) {
            throw new IllegalStateException("无法保存公告", e);
        }
    }
}
