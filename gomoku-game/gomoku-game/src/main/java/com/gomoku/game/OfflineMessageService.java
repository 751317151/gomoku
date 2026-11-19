package com.gomoku.game;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 离线私聊暂存：对方不在线时把消息存起来，上线后一次性投递。
 *
 * 为什么需要它：私聊 v1 只在对方在线时送达，好友看到「对方不在线」只能无从下手；
 * 而社交产品的基本预期是「发出去，对方回来能看到」。
 *
 * 设计取舍：
 * - 只做「好友 → 好友」暂存（准入由调用方先校验 areFriends），不做陌生人留言。
 * - 上限与过期双保险：单个收件人最多 {@link #MAX_PER_RECIPIENT} 条、保留 {@link #TTL_MS}；
 *   超限时丢最旧的一条，保证不会因为长时间不在线把内存/磁盘撑爆。
 * - 投递后即删除（一次送达语义），不做未读计数持久化——前端仍有内存未读徽标。
 * - 与其它服务同款原子落盘；读盘失败直接启动失败，避免静默丢消息。
 */
@Service
public class OfflineMessageService {

    /** 单个收件人最多暂存多少条 */
    static final int MAX_PER_RECIPIENT = 50;
    /** 暂存保留时长（7 天） */
    static final long TTL_MS = 7L * 24 * 3600 * 1000;
    /** 最多保留多少个收件人的信箱（防止全局无限增长） */
    static final int MAX_RECIPIENTS = 2000;

    private final Path path;
    private final Gson gson = new Gson();
    /** 收件人身份 -> 消息列表（旧的在前） */
    private final Map<String, List<Message>> mailboxes = new LinkedHashMap<>();

    public OfflineMessageService() {
        this(Paths.get("data", "offline_messages.json"));
    }

    /** 供测试注入临时路径 */
    public OfflineMessageService(Path path) {
        this.path = path;
    }

    /** 一条暂存消息 */
    public static class Message {
        public String fromId;
        public String fromName;
        public String text;
        public long at;
    }

    @PostConstruct
    public synchronized void init() {
        if (!Files.exists(path)) return;
        try {
            Map<String, List<Message>> stored = gson.fromJson(
                    Files.readString(path), new TypeToken<Map<String, List<Message>>>() {}.getType());
            if (stored != null) mailboxes.putAll(stored);
            purgeExpired();
        } catch (Exception e) {
            throw new IllegalStateException("无法读取离线消息文件", e);
        }
    }

    /** 当前时间（测试可覆盖） */
    long now() { return System.currentTimeMillis(); }

    /**
     * 暂存一条消息。返回剩余条数（便于调用方提示「已为你保存」）。
     * 收件人身份为空时抛 IllegalArgumentException。
     */
    public synchronized int store(String toId, String fromId, String fromName, String text) {
        if (toId == null || toId.isEmpty()) throw new IllegalArgumentException("收件人不能为空");
        if (text == null || text.isEmpty()) return 0;
        purgeExpired();
        List<Message> box = mailboxes.computeIfAbsent(toId, k -> new ArrayList<>());
        Message message = new Message();
        message.fromId = fromId;
        message.fromName = fromName;
        message.text = text;
        message.at = now();
        box.add(message);
        // 超限丢最旧，保证长时间不在线的信箱不会无限增长
        while (box.size() > MAX_PER_RECIPIENT) box.remove(0);
        // 全局收件人数上限：丢最早建立信箱的（LinkedHashMap 插入序，但重插会保序）
        while (mailboxes.size() > MAX_RECIPIENTS) {
            String oldest = mailboxes.keySet().iterator().next();
            if (oldest.equals(toId)) break; // 至少保证本次收件人保留
            mailboxes.remove(oldest);
        }
        persist();
        return box.size();
    }

    /** 取出并清空某人的信箱（上线投递时调用）。没有消息返回空列表。 */
    public synchronized List<Message> drain(String identityId) {
        if (identityId == null) return Collections.emptyList();
        purgeExpired();
        List<Message> box = mailboxes.remove(identityId);
        if (box == null || box.isEmpty()) return Collections.emptyList();
        persist();
        return new ArrayList<>(box);
    }

    /** 待投递条数（不做已读状态，仅用于运营/测试观察） */
    public synchronized int pendingCount(String identityId) {
        purgeExpired();
        List<Message> box = mailboxes.get(identityId);
        return box == null ? 0 : box.size();
    }

    /** 当前有多少个有待投递消息的信箱（运营观察用） */
    public synchronized int mailboxCount() {
        purgeExpired();
        return mailboxes.size();
    }

    /** 清空过期消息；无变化时不落盘 */
    private void purgeExpired() {
        long cutoff = now() - TTL_MS;
        boolean changed = false;
        Iterator<Map.Entry<String, List<Message>>> it = mailboxes.entrySet().iterator();
        while (it.hasNext()) {
            List<Message> box = it.next().getValue();
            int before = box.size();
            box.removeIf(m -> m.at < cutoff);
            if (box.isEmpty()) { it.remove(); changed = true; }
            else if (box.size() != before) changed = true;
        }
        if (changed) persist();
    }

    private synchronized void persist() {
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, gson.toJson(mailboxes), StandardCharsets.UTF_8);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) {
            throw new IllegalStateException("无法保存离线消息", e);
        }
    }
}
