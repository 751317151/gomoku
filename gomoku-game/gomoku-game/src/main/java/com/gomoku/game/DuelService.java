package com.gomoku.game;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.*;
import java.util.function.Function;

/**
 * 好友 1v1 约战邀请：发邀请不占座，接受成功才一次安排双方进入新私房。
 *
 * 设计取舍：
 * - 纯内存状态，邀请 2 分钟有效；好友关系、身份和在线状态由接入层再次校验。
 * - 开局期间冻结双方涉及的邀请；不持有本服务锁调用房间层，失败保留邀请供重试。
 * - 同一对好友只保留一条待处理邀请；发起方最多 3 条、接收方最多 5 条，防刷屏。
 */
@Service
public class DuelService {

    static final long INVITE_TTL_MS = 2 * 60 * 1000L;
    static final int MAX_PENDING_OUTGOING = 3;
    static final int MAX_PENDING_INCOMING = 5;
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final SecureRandom random = new SecureRandom();
    /** inviteId -> 邀请 */
    private final Map<String, Duel> invites = new LinkedHashMap<>();
    private final Set<String> launching = new HashSet<>();

    /** 返回不可变邀请，调用方不能修改归属、玩法或有效期。 */
    public static final class Duel {
        public final String id;
        public final String fromId;
        public final String toId;
        public final String gameType;
        public final long createdAt;
        public final long expiresAt;

        private Duel(String id, String fromId, String toId, String gameType, long createdAt) {
            this.id = id;
            this.fromId = fromId;
            this.toId = toId;
            this.gameType = gameType;
            this.createdAt = createdAt;
            this.expiresAt = createdAt + INVITE_TTL_MS;
        }
    }

    /** 成功后返回被作废的全部邀请，接入层据此刷新涉及的好友面板。 */
    public static final class Started<T> {
        public final T result;
        public final List<Duel> clearedInvites;

        private Started(T result, List<Duel> clearedInvites) {
            this.result = result;
            this.clearedInvites = List.copyOf(clearedInvites);
        }
    }

    public static class DuelException extends RuntimeException {
        private final String code;
        public DuelException(String code, String message) {
            super(message);
            this.code = code;
        }
        public String getCode() { return code; }
    }

    /** 当前时间（测试可覆盖） */
    long now() { return System.currentTimeMillis(); }

    public synchronized Duel invite(String fromId, String toId, String gameType) {
        if (isBlank(fromId) || isBlank(toId) || isBlank(gameType)) {
            throw new DuelException("INVALID", "约战参数不完整");
        }
        if (fromId.equals(toId)) throw new DuelException("INVALID", "不能约战自己");
        purgeExpired();
        requireIdle(fromId, toId);
        GameType type;
        try { type = GameType.valueOf(gameType.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new DuelException("INVALID", "不支持该玩法"); }
        if (type.getMinPlayers() > 2 || type.getMaxPlayers() < 2) {
            throw new DuelException("INVALID", "该玩法不支持双人约战");
        }
        for (Duel pending : invites.values()) {
            if ((pending.fromId.equals(fromId) && pending.toId.equals(toId))
                    || (pending.fromId.equals(toId) && pending.toId.equals(fromId))) {
                throw new DuelException("CONFLICT", "双方已有待处理的约战邀请，请先回应");
            }
        }
        long outgoing = invites.values().stream().filter(d -> d.fromId.equals(fromId)).count();
        if (outgoing >= MAX_PENDING_OUTGOING) {
            throw new DuelException("LIMIT", "你还有未处理的约战邀请，请稍后再试");
        }
        long incoming = invites.values().stream().filter(d -> d.toId.equals(toId)).count();
        if (incoming >= MAX_PENDING_INCOMING) {
            throw new DuelException("LIMIT", "对方未处理的约战邀请过多，请稍后再试");
        }
        String inviteId;
        do {
            inviteId = "DUEL-" + randomCode(6);
        } while (invites.containsKey(inviteId));
        Duel duel = new Duel(inviteId, fromId, toId, type.name(), now());
        invites.put(duel.id, duel);
        return duel;
    }

    /** 校验邀请归属与有效期；接受时仍会在同一临界区复核。 */
    public synchronized Duel findForTarget(String identityId, String inviteId) {
        purgeExpired();
        Duel duel = inviteId == null ? null : invites.get(inviteId);
        if (duel == null) throw new DuelException("NOT_FOUND", "约战邀请已失效");
        if (!duel.toId.equals(identityId)) {
            throw new DuelException("UNAUTHORIZED", "这不是给你的约战邀请");
        }
        return duel;
    }

    /**
     * 冻结双方后释放邀请锁，再调用房间服务。重叠邀请不能同时开局，
     * 拒绝/撤回也不能中途消费邀请；房间校验失败时释放冻结，原邀请仍可重试。
     */
    public <T> Started<T> accept(String identityId, String inviteId, Function<Duel, T> start) {
        Duel duel;
        synchronized (this) {
            duel = findForTarget(identityId, inviteId);
            requireIdle(duel.fromId, duel.toId);
            launching.add(duel.fromId);
            launching.add(duel.toId);
        }
        try {
            T result = start.apply(duel);
            synchronized (this) {
                List<Duel> cleared = new ArrayList<>();
                Iterator<Duel> pending = invites.values().iterator();
                while (pending.hasNext()) {
                    Duel candidate = pending.next();
                    if (involves(candidate, duel.fromId) || involves(candidate, duel.toId)) {
                        cleared.add(candidate);
                        pending.remove();
                    }
                }
                return new Started<>(result, cleared);
            }
        } finally {
            synchronized (this) {
                launching.remove(duel.fromId);
                launching.remove(duel.toId);
            }
        }
    }

    public synchronized Duel decline(String identityId, String inviteId) {
        Duel duel = findForTarget(identityId, inviteId);
        requireIdle(duel.fromId, duel.toId);
        invites.remove(duel.id);
        return duel;
    }

    /** 仅发起人可撤回，接收方使用 decline。 */
    public synchronized Duel cancel(String identityId, String inviteId) {
        purgeExpired();
        Duel duel = inviteId == null ? null : invites.get(inviteId);
        if (duel == null) throw new DuelException("NOT_FOUND", "约战邀请已失效");
        if (!duel.fromId.equals(identityId)) throw new DuelException("UNAUTHORIZED", "只能撤回自己发出的约战");
        requireIdle(duel.fromId, duel.toId);
        invites.remove(inviteId);
        return duel;
    }

    public synchronized List<Duel> invitesFor(String identityId) {
        purgeExpired();
        List<Duel> result = new ArrayList<>();
        for (Duel duel : invites.values()) {
            if (duel.toId.equals(identityId)) result.add(duel);
        }
        return List.copyOf(result);
    }

    public synchronized List<Duel> outgoingFor(String identityId) {
        purgeExpired();
        List<Duel> result = new ArrayList<>();
        for (Duel duel : invites.values()) {
            if (duel.fromId.equals(identityId)) result.add(duel);
        }
        return List.copyOf(result);
    }

    public synchronized int pendingCount() {
        purgeExpired();
        return invites.size();
    }

    private void purgeExpired() {
        long ts = now();
        invites.values().removeIf(d -> d.expiresAt <= ts
                && !launching.contains(d.fromId) && !launching.contains(d.toId));
    }

    private void requireIdle(String fromId, String toId) {
        if (launching.contains(fromId) || launching.contains(toId)) {
            throw new DuelException("CONFLICT", "约战正在开局，请稍后重试");
        }
    }

    private static boolean involves(Duel invite, String identityId) {
        return invite.fromId.equals(identityId) || invite.toId.equals(identityId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String randomCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }
}
