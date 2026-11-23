package com.gomoku.game;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 运营治理：玩家举报 + 禁言/封禁处置，持久化 data/moderation.json。
 *
 * 为什么需要它：项目已经有陌生人房间、公开聊天、好友私聊，
 * 但没有任何处置手段——出现辱骂/刷屏/挂机骚扰时，运营只能重启服务。
 * 真正的线上运营必须有「举报 → 处置 → 生效」的闭环。
 *
 * 设计取舍：
 * - 身份与处置分离：举报记录带证据快照（谁举报谁、房间、玩法、原因、时间），
 *   处置按 identityId 落地（昵称可变、可重复，身份才稳定）。
 * - 处置只做两档：禁言（保留游玩能力）与封禁（拒绝进入）。
 *   更细的权限（房主踢人、临时禁言）后续再加，先把最小可用闭环做实。
 * - 到期自动失效：封禁/禁言都带 expiresAt（0 = 永久），查询时惰性判定，
 *   不清删记录，便于审计。
 * - 同一个被举报人短时间大量举报只记一条（谁举报谁去重），防止刷举报列表。
 */
@Service
public class ModerationService {

    /** 累计保留的举报条数上限 */
    static final int MAX_REPORTS = 500;
    /** 同一举报人对同一被举报人的去重窗口 */
    static final long DEDUP_WINDOW_MS = 10 * 60 * 1000L;
    /** 允许选择的处置时长（小时）：0 = 永久 */
    public static final Map<String, Long> DURATIONS = new LinkedHashMap<>();
    static {
        DURATIONS.put("1h", 1L);
        DURATIONS.put("24h", 24L);
        DURATIONS.put("7d", 24L * 7);
        DURATIONS.put("30d", 24L * 30);
        DURATIONS.put("forever", 0L);
    }

    private final Path path;
    private final Gson gson = new Gson();

    /** 举报记录（新的在前） */
    private final LinkedList<Report> reports = new LinkedList<>();
    /** 身份 -> 处置状态 */
    private final Map<String, Sanction> sanctions = new LinkedHashMap<>();

    @Autowired
    public ModerationService() {
        this(Paths.get("data", "moderation.json"));
    }

    /** 供测试注入临时路径 */
    public ModerationService(Path path) {
        this.path = path;
    }

    /** 持久化模型：举报队列 + 处置表同文件 */
    private static class Store {
        public List<Report> reports;
        public Map<String, Sanction> sanctions;
    }

    /** 一条举报 */
    public static class Report {
        public String id;
        /** 举报人身份 */
        public String reporterId;
        public String reporterName;
        /** 被举报人身份 */
        public String targetId;
        public String targetName;
        /** 房间与玩法（取证用） */
        public String roomId;
        public String gameType;
        /** 原因分类：abuse / spam / cheating / afk / other */
        public String reason;
        /** 补充说明 */
        public String detail;
        public long createdAt;
        /** 是否已处置 */
        public boolean handled;
        /** 处置备注 */
        public String handledNote;
    }

    /** 处置状态 */
    public static class Sanction {
        /** mute / ban */
        public String action;
        /** 生效时间 */
        public long since;
        /** 到期时间；0 = 永久 */
        public long expiresAt;
        public String reason;
        /** 执行人（管理员标识，可为机器名） */
        public String by;
    }

    @PostConstruct
    public synchronized void init() {
        if (!Files.exists(path)) return;
        try {
            Store stored = gson.fromJson(Files.readString(path), Store.class);
            if (stored == null) throw new IllegalStateException("治理文件为空");
            reports.clear();
            if (stored.reports != null) reports.addAll(stored.reports);
            sanctions.clear();
            if (stored.sanctions != null) sanctions.putAll(stored.sanctions);
        } catch (Exception e) {
            throw new IllegalStateException("无法读取治理文件", e);
        }
    }

    /** 当前时间（测试可覆盖） */
    long now() { return System.currentTimeMillis(); }

    /**
     * 提交举报。返回 null 表示被去重（同一举报人 10 分钟内已举报过同一人），
     * 调用方可据此回「已收到重复举报」而不是再记一条。
     */
    public synchronized Report report(String reporterId, String reporterName,
                                      String targetId, String targetName,
                                      String roomId, String gameType,
                                      String reason, String detail) {
        if (reporterId == null || targetId == null) {
            throw new IllegalArgumentException("举报双方身份不能为空");
        }
        if (reporterId.equals(targetId)) {
            throw new IllegalArgumentException("不能举报自己");
        }
        String normalizedReason = normalizeReason(reason);
        long ts = now();
        for (Report existing : reports) {
            if (!reporterId.equals(existing.reporterId)) continue;
            if (!targetId.equals(existing.targetId)) continue;
            if (ts - existing.createdAt < DEDUP_WINDOW_MS) return null;
            break; // 列表按时间倒序，第一条更早的命中即说明已过去重窗口
        }
        Report report = new Report();
        report.id = "REP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        report.reporterId = reporterId;
        report.reporterName = truncate(reporterName, 32);
        report.targetId = targetId;
        report.targetName = truncate(targetName, 32);
        report.roomId = roomId;
        report.gameType = gameType;
        report.reason = normalizedReason;
        report.detail = truncate(detail, 300);
        report.createdAt = ts;
        reports.addFirst(report);
        while (reports.size() > MAX_REPORTS) reports.removeLast();
        persist();
        return report;
    }

    /**
     * 处置：action = mute / ban。duration 取 {@link #DURATIONS} 的键。
     * 返回写入后的处置状态（expiresAt 已换算成绝对时间）。
     */
    public synchronized Sanction sanction(String targetId, String action, String duration,
                                          String reason, String by) {
        if (targetId == null || targetId.isEmpty()) throw new IllegalArgumentException("处置身份不能为空");
        String normalizedAction = "ban".equals(action) ? "ban" : "mute";
        Long hours = DURATIONS.get(duration == null ? "" : duration);
        if (hours == null) throw new IllegalArgumentException("不支持的处置时长");
        Sanction sanction = new Sanction();
        sanction.action = normalizedAction;
        sanction.since = now();
        sanction.expiresAt = hours == 0L ? 0L : sanction.since + hours * 3600_000L;
        sanction.reason = truncate(reason, 200);
        sanction.by = truncate(by, 64);
        sanctions.put(targetId, sanction);
        persist();
        return sanction;
    }

    /** 撤销处置（误操作 / 申诉通过） */
    public synchronized boolean lift(String targetId) {
        if (sanctions.remove(targetId) == null) return false;
        persist();
        return true;
    }

    /** 当前生效的处置；无处置或已过期返回 null */
    public synchronized Sanction activeSanction(String identityId) {
        if (identityId == null) return null;
        Sanction sanction = sanctions.get(identityId);
        if (sanction == null) return null;
        long ts = now();
        if (sanction.expiresAt > 0 && ts >= sanction.expiresAt) return null;
        return sanction;
    }

    /** 是否被封禁（拒绝进入大厅） */
    public synchronized boolean isBanned(String identityId) {
        Sanction s = activeSanction(identityId);
        return s != null && "ban".equals(s.action);
    }

    /** 是否被禁言（仍可游玩，但不能聊天/私聊） */
    public synchronized boolean isMuted(String identityId) {
        Sanction s = activeSanction(identityId);
        return s != null && "mute".equals(s.action);
    }

    /** 处置剩余秒数；永久返回 -1，无处置返回 0 */
    public synchronized long remainingSeconds(String identityId) {
        Sanction s = activeSanction(identityId);
        if (s == null) return 0;
        if (s.expiresAt == 0) return -1;
        return Math.max(1, (s.expiresAt - now()) / 1000);
    }

    /** 举报列表（新的在前），可只取未处置 */
    public synchronized List<Report> reports(boolean onlyPending, int limit) {
        List<Report> result = new ArrayList<>();
        for (Report report : reports) {
            if (onlyPending && report.handled) continue;
            result.add(copy(report));
            if (limit > 0 && result.size() >= limit) break;
        }
        return result;
    }

    /** 标记举报已处置 */
    public synchronized boolean markHandled(String reportId, String note) {
        for (Report report : reports) {
            if (!report.id.equals(reportId)) continue;
            report.handled = true;
            report.handledNote = truncate(note, 200);
            persist();
            return true;
        }
        return false;
    }

    /** 处罚表快照（管理端展示，含已过期项便于审计） */
    public synchronized List<Map<String, Object>> sanctionList() {
        List<Map<String, Object>> result = new ArrayList<>();
        long ts = now();
        for (Map.Entry<String, Sanction> e : sanctions.entrySet()) {
            Sanction s = e.getValue();
            boolean expired = s.expiresAt > 0 && ts >= s.expiresAt;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.getKey());
            row.put("action", s.action);
            row.put("since", s.since);
            row.put("expiresAt", s.expiresAt);
            row.put("active", !expired);
            row.put("reason", s.reason);
            row.put("by", s.by);
            result.add(row);
        }
        result.sort((a, b) -> Long.compare((long) b.get("since"), (long) a.get("since")));
        return result;
    }

    private static String normalizeReason(String reason) {
        if (reason == null) return "other";
        switch (reason) {
            case "abuse":
            case "spam":
            case "cheating":
            case "afk":
                return reason;
            default:
                return "other";
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.length() > max) trimmed = trimmed.substring(0, max);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Report copy(Report source) {
        Report copy = new Report();
        copy.id = source.id;
        copy.reporterId = source.reporterId;
        copy.reporterName = source.reporterName;
        copy.targetId = source.targetId;
        copy.targetName = source.targetName;
        copy.roomId = source.roomId;
        copy.gameType = source.gameType;
        copy.reason = source.reason;
        copy.detail = source.detail;
        copy.createdAt = source.createdAt;
        copy.handled = source.handled;
        copy.handledNote = source.handledNote;
        return copy;
    }

    private synchronized void persist() {
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Store store = new Store();
            store.reports = new ArrayList<>(reports);
            store.sanctions = new LinkedHashMap<>(sanctions);
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, gson.toJson(store), StandardCharsets.UTF_8);
            moveIntoPlace(temporary, path);
        } catch (Exception e) {
            throw new IllegalStateException("无法保存治理数据", e);
        }
    }

    /**
     * Windows 上杀毒/索引扫描会短暂占用刚写完的临时文件，原子替换随即报 AccessDenied。
     * 这不是数据损坏，短暂重试即可；连续失败仍然抛出，由调用方明确感知，绝不静默丢处置。
     */
    private static void moveIntoPlace(Path temporary, Path path) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                try {
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (FileSystemException e) {
                if (attempt >= 3) throw e;
                try {
                    Thread.sleep(25L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
