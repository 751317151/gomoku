package com.gomoku.game;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.*;

/**
 * 好友 1v1 约战邀请状态。当前仅提供服务层，尚未接入 REST / WebSocket / UI。
 *
 * 设计取舍：
 * - 纯内存状态，邀请 2 分钟有效；房间本身仍走既有 JOIN 链路，计时/ELO/存档零改动。
 * - 好友关系、身份认证、房间可加入性须由接入层校验，本服务不占座或开局。
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

    /** 返回不可变邀请，调用方不能修改归属、房号或有效期。 */
    public static final class Duel {
        public final String id;
        public final String fromId;
        public final String toId;
        public final String roomId;
        public final String gameType;
        public final long createdAt;
        public final long expiresAt;

        private Duel(String id, String fromId, String toId, String roomId, String gameType, long createdAt) {
            this.id = id;
            this.fromId = fromId;
            this.toId = toId;
            this.roomId = roomId;
            this.gameType = gameType;
            this.createdAt = createdAt;
            this.expiresAt = createdAt + INVITE_TTL_MS;
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

    public synchronized Duel invite(String fromId, String toId, String roomId, String gameType) {
        if (isBlank(fromId) || isBlank(toId) || isBlank(roomId) || isBlank(gameType)) {
            throw new DuelException("INVALID", "约战参数不完整");
        }
        if (fromId.equals(toId)) throw new DuelException("INVALID", "不能约战自己");
        purgeExpired();
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
        Duel duel = new Duel(inviteId, fromId, toId, roomId, gameType, now());
        invites.put(duel.id, duel);
        return duel;
    }

    /** 校验邀请归属与有效期，但不移除；控制器先验房间再真正接受。 */
    public synchronized Duel findForTarget(String identityId, String inviteId) {
        purgeExpired();
        Duel duel = inviteId == null ? null : invites.get(inviteId);
        if (duel == null) throw new DuelException("NOT_FOUND", "约战邀请已失效");
        if (!duel.toId.equals(identityId)) {
            throw new DuelException("UNAUTHORIZED", "这不是给你的约战邀请");
        }
        return duel;
    }

    public synchronized Duel accept(String identityId, String inviteId) {
        Duel duel = findForTarget(identityId, inviteId);
        invites.remove(duel.id);
        return duel;
    }

    public synchronized Duel decline(String identityId, String inviteId) {
        Duel duel = findForTarget(identityId, inviteId);
        invites.remove(duel.id);
        return duel;
    }

    /** 丢弃（房间已失效等），不校验接收人；供控制器在确认归属后清理。 */
    public synchronized void drop(String inviteId) {
        if (inviteId != null) invites.remove(inviteId);
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
        invites.values().removeIf(d -> d.expiresAt <= ts);
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
