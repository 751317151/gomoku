package com.gomoku.game;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

/**
 * 好友关系服务：好友码 + 好友图，持久化 data/friends.json。
 *
 * 设计取舍：
 * - 好友码：每个持久身份一个 8 位大写编码（去掉 I/O/0/1 等易混淆字符），随机生成、
 *   只分配一次并落盘。游客与账号通用；账号还可以直接用用户名加好友。
 * - 好友图按 identityId 存储：昵称可变、可重复，身份才是主键。展示名由接口层实时解析。
 * - 加好友需要对方同意（incoming = 待我同意，outgoing = 我发出的申请），
 *   避免单方面把陌生人拉进社交圈。
 * - 磁盘只存身份 ID 与关系，不存昵称、不存凭证。
 */
@Service
public class FriendService {

    /** 好友码长度：8 位去混淆编码，与 6 位房号同款手抄体验 */
    private static final int CODE_LENGTH = 8;
    /** 好友码字符集：去掉 I/O/0/1，手抄不易错 */
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    /** 单个身份好友上限 */
    static final int MAX_FRIENDS = 50;
    /** 每个方向最多挂起的申请数（防刷屏） */
    static final int MAX_PENDING = 20;

    private final Path path;
    private final AccountService accounts;
    private final Gson gson = new Gson();
    private final SecureRandom random = new SecureRandom();

    /** 身份 -> 好友码 */
    private final Map<String, String> codes = new LinkedHashMap<>();
    /** 好友码 -> 身份（内存倒排索引，启动时重建） */
    private final Map<String, String> codeToIdentity = new HashMap<>();
    /** 身份 -> 好友图节点 */
    private final Map<String, Node> graph = new LinkedHashMap<>();

    @Autowired
    public FriendService(AccountService accounts) {
        this(Paths.get("data", "friends.json"), accounts);
    }

    /** 供测试注入临时路径 */
    public FriendService(Path path, AccountService accounts) {
        this.path = path;
        this.accounts = accounts;
    }

    /** 好友图节点：好友 + 两个方向的待处理申请 */
    public static class Node {
        public List<String> friends = new ArrayList<>();
        public List<String> incoming = new ArrayList<>();
        public List<String> outgoing = new ArrayList<>();

        /** 深拷贝：落盘失败时按节点回滚 */
        Node copy() {
            Node clone = new Node();
            clone.friends.addAll(friends);
            clone.incoming.addAll(incoming);
            clone.outgoing.addAll(outgoing);
            return clone;
        }
    }

    /** 磁盘顶层结构：好友码目录与好友图同文件 */
    private static class Store {
        @SerializedName("codes")
        public Map<String, String> codes;
        @SerializedName("graph")
        public Map<String, Node> graph;
    }

    /** 好友业务错误，携带给前端的机器可读 code（映射见 FriendController） */
    public static class FriendException extends RuntimeException {
        private final String code;
        public FriendException(String code, String message) {
            super(message);
            this.code = code;
        }
        public String getCode() { return code; }
    }

    @PostConstruct
    public synchronized void init() {
        if (!Files.exists(path)) return;
        try {
            Store stored = gson.fromJson(Files.readString(path), Store.class);
            if (stored == null) throw new IllegalStateException("好友文件为空");
            if (stored.codes != null) {
                for (Map.Entry<String, String> e : stored.codes.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) continue;
                    String code = e.getValue().toUpperCase(Locale.ROOT);
                    if (codeToIdentity.containsKey(code)) continue; // 历史脏数据：重复码保留第一个
                    codes.put(e.getKey(), code);
                    codeToIdentity.put(code, e.getKey());
                }
            }
            if (stored.graph != null) {
                for (Map.Entry<String, Node> e : stored.graph.entrySet()) {
                    graph.put(e.getKey(), e.getValue() == null ? new Node() : e.getValue());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法读取好友文件", e);
        }
    }

    /** 持久身份 → 好友码（没有则分配一个唯一码并落盘）。 */
    public synchronized String friendCodeOf(String identityId) {
        requireIdentity(identityId);
        String existing = codes.get(identityId);
        if (existing != null) return existing;
        String code = allocateCode();
        codes.put(identityId, code);
        codeToIdentity.put(code, identityId);
        try {
            persist();
        } catch (RuntimeException e) {
            codes.remove(identityId);
            codeToIdentity.remove(code);
            throw e;
        }
        return code;
    }

    /**
     * 解析加好友的目标身份：优先按好友码（8 位编码），找不到再按用户名。
     * 两者都查不到返回 null，由调用方给出「找不到」的业务错误。
     */
    public synchronized String resolveTarget(String query) {
        if (query == null) return null;
        String trimmed = query.trim();
        if (trimmed.isEmpty()) return null;
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (upper.matches("[" + CODE_ALPHABET + "]{8}")) {
            String byCode = codeToIdentity.get(upper);
            if (byCode != null) return byCode;
        }
        return accounts.identityOfUsername(trimmed);
    }

    /** 发出好友申请：不能加自己、不能重复、双方容量都要有余量。 */
    public synchronized void request(String fromId, String targetId) {
        requireIdentity(fromId);
        requireIdentity(targetId);
        if (fromId.equals(targetId)) throw new FriendException("INVALID", "不能添加自己");
        if (!isKnownIdentity(targetId)) throw new FriendException("NOT_FOUND", "找不到该好友码或用户名");
        Node mine = node(fromId);
        Node theirs = node(targetId);
        if (mine.friends.contains(targetId) || theirs.friends.contains(fromId)) {
            throw new FriendException("CONFLICT", "已经是好友");
        }
        if (mine.incoming.contains(targetId)) {
            throw new FriendException("CONFLICT", "对方已向你申请，请在好友面板直接同意");
        }
        if (mine.outgoing.contains(targetId)) {
            throw new FriendException("CONFLICT", "申请已发送，等待对方同意");
        }
        if (mine.friends.size() >= MAX_FRIENDS || theirs.friends.size() >= MAX_FRIENDS) {
            throw new FriendException("LIMIT", "好友数量已达上限 " + MAX_FRIENDS);
        }
        if (mine.outgoing.size() >= MAX_PENDING) {
            throw new FriendException("LIMIT", "待处理的申请过多，请先等待对方处理");
        }
        if (theirs.incoming.size() >= MAX_PENDING) {
            throw new FriendException("LIMIT", "对方待处理的申请过多，请稍后再试");
        }
        Node mineBackup = mine.copy();
        Node theirsBackup = theirs.copy();
        mine.outgoing.add(targetId);
        theirs.incoming.add(fromId);
        persistOrRollback(fromId, mineBackup, targetId, theirsBackup);
    }

    /** 同意申请：对方必须在我的 incoming 里；双方好友上限都要有余量。 */
    public synchronized void accept(String identityId, String fromId) {
        requireIdentity(identityId);
        requireIdentity(fromId);
        Node mine = node(identityId);
        if (!mine.incoming.contains(fromId)) throw new FriendException("NOT_FOUND", "没有这条好友申请");
        Node theirs = node(fromId);
        if (mine.friends.size() >= MAX_FRIENDS || theirs.friends.size() >= MAX_FRIENDS) {
            throw new FriendException("LIMIT", "好友数量已达上限 " + MAX_FRIENDS);
        }
        Node mineBackup = mine.copy();
        Node theirsBackup = theirs.copy();
        mine.incoming.remove(fromId);
        theirs.outgoing.remove(identityId);
        mine.friends.add(fromId);
        theirs.friends.add(identityId);
        persistOrRollback(identityId, mineBackup, fromId, theirsBackup);
    }

    /** 拒绝申请：清掉我的 incoming 与对方的 outgoing。 */
    public synchronized void decline(String identityId, String fromId) {
        requireIdentity(identityId);
        Node mine = node(identityId);
        if (!mine.incoming.contains(fromId)) throw new FriendException("NOT_FOUND", "没有这条好友申请");
        Node theirs = node(fromId);
        Node mineBackup = mine.copy();
        Node theirsBackup = theirs.copy();
        mine.incoming.remove(fromId);
        theirs.outgoing.remove(identityId);
        persistOrRollback(identityId, mineBackup, fromId, theirsBackup);
    }

    /** 撤回我发出的申请：清掉我的 outgoing 与对方的 incoming。 */
    public synchronized void cancel(String identityId, String targetId) {
        requireIdentity(identityId);
        Node mine = node(identityId);
        if (!mine.outgoing.contains(targetId)) throw new FriendException("NOT_FOUND", "没有这条已发送的申请");
        Node theirs = node(targetId);
        Node mineBackup = mine.copy();
        Node theirsBackup = theirs.copy();
        mine.outgoing.remove(targetId);
        theirs.incoming.remove(identityId);
        persistOrRollback(identityId, mineBackup, targetId, theirsBackup);
    }

    /** 删除好友：双向移除，关系不保留历史。 */
    public synchronized void remove(String identityId, String friendId) {
        requireIdentity(identityId);
        Node mine = node(identityId);
        if (!mine.friends.contains(friendId)) throw new FriendException("NOT_FOUND", "你们还不是好友");
        Node theirs = node(friendId);
        Node mineBackup = mine.copy();
        Node theirsBackup = theirs.copy();
        mine.friends.remove(friendId);
        theirs.friends.remove(identityId);
        persistOrRollback(identityId, mineBackup, friendId, theirsBackup);
    }

    // ============ 查询（只读，供接口层组装视图） ============

    /** 我的好友身份列表（展示昵称与在线状态由接口层实时解析） */
    public synchronized List<String> friendsOf(String identityId) {
        requireIdentity(identityId);
        return new ArrayList<>(node(identityId).friends);
    }

    /** 待我同意的申请（对方身份列表） */
    public synchronized List<String> incomingOf(String identityId) {
        requireIdentity(identityId);
        return new ArrayList<>(node(identityId).incoming);
    }

    /** 我发出、等待对方同意的申请（对方身份列表） */
    public synchronized List<String> outgoingOf(String identityId) {
        requireIdentity(identityId);
        return new ArrayList<>(node(identityId).outgoing);
    }

    /** 好友码 → 身份 */
    public synchronized String identityOfCode(String code) {
        if (code == null) return null;
        return codeToIdentity.get(code.trim().toUpperCase(Locale.ROOT));
    }

    /** 该身份是否可以被申请（有好友码或绑定账号；防往凭空身份写申请） */
    public synchronized boolean isKnownIdentity(String identityId) {
        return identityId != null && (codes.containsKey(identityId) || accounts.findByIdentity(identityId) != null);
    }

    /** 两人是否互为好友（私聊准入：只允许好友之间私聊，防陌生人骚扰） */
    public synchronized boolean areFriends(String a, String b) {
        if (a == null || b == null || a.equals(b)) return false;
        return node(a).friends.contains(b);
    }

    // ============ 内部实现 ============

    private static void requireIdentity(String identityId) {
        if (identityId == null || identityId.isEmpty()) {
            throw new FriendException("UNAUTHORIZED", "请先进入大厅获取身份");
        }
    }

    /** 取节点（懒创建：空身份也返回空节点，查询路径不抛错） */
    private Node node(String identityId) {
        return graph.computeIfAbsent(identityId, k -> new Node());
    }

    /** 分配一个不与现有码冲突的好友码 */
    private String allocateCode() {
        for (int attempt = 0; attempt < 100; attempt++) {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            }
            String code = sb.toString();
            if (!codeToIdentity.containsKey(code)) return code;
        }
        throw new IllegalStateException("好友码分配失败：冲突次数过多");
    }

    /** 变更后落盘；失败时把受影响节点回滚到变更前，保证内存与磁盘一致 */
    private void persistOrRollback(String idA, Node backupA, String idB, Node backupB) {
        try {
            persist();
        } catch (RuntimeException e) {
            graph.put(idA, backupA);
            if (idB != null && !idB.equals(idA)) graph.put(idB, backupB);
            throw e;
        }
    }

    private synchronized void persist() {
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Store snapshot = new Store();
            snapshot.codes = codes;
            snapshot.graph = graph;
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, gson.toJson(snapshot), StandardCharsets.UTF_8);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) {
            throw new IllegalStateException("无法保存好友数据", e);
        }
    }
}
