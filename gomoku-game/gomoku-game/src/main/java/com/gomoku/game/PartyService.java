package com.gomoku.game;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.*;
import java.util.function.Function;

/** 临时组队：邀请须由好友确认，成员按持久身份保留，服务重启后不恢复。 */
@Service
public class PartyService {

    public static final int MAX_MEMBERS = 8;
    static final long INVITE_TTL_MS = 2 * 60 * 1000L;
    static final long OFFLINE_TTL_MS = 10 * 60 * 1000L;
    static final int MAX_PENDING_INVITES = 5;
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Party> parties = new LinkedHashMap<>();
    private final Map<String, String> partyOf = new HashMap<>();
    private final Map<String, Invite> invites = new LinkedHashMap<>();
    private final Map<String, Long> offlineSince = new HashMap<>();
    private final Set<String> launching = new HashSet<>();

    /** 不可变快照：HTTP 渲染和进房时都不依赖可变成员列表。 */
    public static final class Party {
        public final String id;
        public final String leaderId;
        public final List<String> members;
        public final long createdAt;

        private Party(String id, List<String> members, long createdAt) {
            this.id = id;
            this.leaderId = members.get(0);
            this.members = List.copyOf(members);
            this.createdAt = createdAt;
        }
    }

    public static final class Invite {
        public final String id;
        public final String partyId;
        public final String fromId;
        public final String toId;
        public final long expiresAt;

        private Invite(String id, String partyId, String fromId, String toId, long expiresAt) {
            this.id = id;
            this.partyId = partyId;
            this.fromId = fromId;
            this.toId = toId;
            this.expiresAt = expiresAt;
        }
    }

    public static class PartyException extends RuntimeException {
        private final String code;
        public PartyException(String code, String message) {
            super(message);
            this.code = code;
        }
        public String getCode() { return code; }
    }

    long now() { return System.currentTimeMillis(); }

    /** 第一条有效邀请自动建队；失败不留下空挂队伍。 */
    public synchronized Invite invite(String fromId, String toId) {
        if (fromId == null || toId == null) throw new PartyException("INVALID", "参数不完整");
        if (fromId.equals(toId)) throw new PartyException("INVALID", "不能邀请自己");
        purgeExpiredInvites();
        Party party = partyOf(fromId);
        if (party != null) {
            requireLeader(fromId, party, "只有队长可以邀请好友");
            requireIdle(party);
            if (party.members.contains(toId)) throw new PartyException("CONFLICT", "对方已在你的队伍里");
            if (party.members.size() >= MAX_MEMBERS) throw new PartyException("LIMIT", "队伍最多 8 人");
        }
        if (partyOf.containsKey(toId)) throw new PartyException("CONFLICT", "对方已在其他队伍中");
        int incoming = 0;
        int outgoing = 0;
        for (Invite pending : invites.values()) {
            if (pending.toId.equals(toId)) {
                incoming++;
                if (party != null && pending.partyId.equals(party.id)) {
                    throw new PartyException("CONFLICT", "已邀请过对方，等待回应");
                }
            }
            if (pending.fromId.equals(fromId)) outgoing++;
        }
        if (incoming >= MAX_PENDING_INVITES || outgoing >= MAX_PENDING_INVITES) {
            throw new PartyException("LIMIT", "待处理的邀请过多，请稍后再试");
        }
        if (party == null) party = create(fromId);
        String inviteId = uniqueCode("PINV-", 6, invites);
        Invite invite = new Invite(inviteId, party.id, fromId, toId, now() + INVITE_TTL_MS);
        invites.put(invite.id, invite);
        return invite;
    }

    /** 路由层据此复核好友关系；身份与有效期在实际写入时再检查。 */
    public synchronized Invite inviteFor(String identityId, String inviteId) {
        purgeExpiredInvites();
        Invite invite = invites.get(inviteId);
        if (invite == null) throw new PartyException("NOT_FOUND", "邀请已失效");
        if (!invite.toId.equals(identityId)) throw new PartyException("UNAUTHORIZED", "这不是给你的邀请");
        return invite;
    }

    public synchronized Party accept(String identityId, String inviteId) {
        Invite invite = inviteFor(identityId, inviteId);
        Party party = parties.get(invite.partyId);
        requireIdle(party);
        if (partyOf.containsKey(identityId)) throw new PartyException("CONFLICT", "你已在队伍中，请先退出");
        if (party.members.size() >= MAX_MEMBERS) {
            invites.remove(invite.id);
            throw new PartyException("LIMIT", "队伍最多 8 人");
        }
        List<String> members = new ArrayList<>(party.members);
        members.add(identityId);
        Party updated = new Party(party.id, members, party.createdAt);
        parties.put(updated.id, updated);
        partyOf.put(identityId, updated.id);
        // 接受后，其余待处理的邀请都不再可用。
        invites.values().removeIf(i -> i.toId.equals(identityId));
        return updated;
    }

    public synchronized void decline(String identityId, String inviteId) {
        invites.remove(inviteFor(identityId, inviteId).id);
    }

    /** 队长退出后顺延，最后一人退出则解散。 */
    public synchronized Party leave(String identityId) {
        Party party = partyOf(identityId);
        if (party == null) return null;
        requireIdle(party);
        partyOf.remove(identityId);
        offlineSince.remove(identityId);
        invites.values().removeIf(i -> i.fromId.equals(identityId));
        List<String> members = new ArrayList<>(party.members);
        members.remove(identityId);
        if (members.isEmpty()) {
            parties.remove(party.id);
            return null;
        }
        Party updated = new Party(party.id, members, party.createdAt);
        parties.put(updated.id, updated);
        return updated;
    }

    /** 冻结本次名单，但调用房间层时不持有队伍锁（房间渲染也会读取队伍）。 */
    public <T> T launch(String leaderId, Function<Party, T> start) {
        Party party;
        synchronized (this) {
            party = partyOf(leaderId);
            if (party == null) throw new PartyException("NOT_FOUND", "你还没有队伍");
            requireLeader(leaderId, party, "只有队长可以带队开局");
            requireIdle(party);
            launching.add(party.id);
        }
        try {
            return start.apply(party);
        } finally {
            synchronized (this) { launching.remove(party.id); }
        }
    }

    public synchronized Party partyOf(String identityId) {
        return parties.get(partyOf.get(identityId));
    }

    public synchronized String partyIdOf(String identityId) {
        return partyOf.get(identityId);
    }

    public synchronized List<Invite> invitesFor(String identityId) {
        purgeExpiredInvites();
        List<Invite> result = new ArrayList<>();
        for (Invite invite : invites.values()) if (invite.toId.equals(identityId)) result.add(invite);
        return result;
    }

    public synchronized int memberCount(String partyId) {
        Party party = parties.get(partyId);
        return party == null ? 0 : party.members.size();
    }

    /** 由房间清理任务调用；短暂断线保留队伍，长时间离线才让出队伍席位。 */
    synchronized void expireOfflineMembers(Set<String> onlineIdentities) {
        purgeExpiredInvites();
        long time = now();
        for (String id : new ArrayList<>(partyOf.keySet())) {
            if (onlineIdentities.contains(id)) {
                offlineSince.remove(id);
                continue;
            }
            long since = offlineSince.computeIfAbsent(id, ignored -> time);
            if (time - since >= OFFLINE_TTL_MS && !launching.contains(partyOf.get(id))) leave(id);
        }
    }

    synchronized void memberConnected(String identityId) {
        offlineSince.remove(identityId);
    }

    private Party create(String leaderId) {
        String id = uniqueCode("PTY-", 4, parties);
        Party party = new Party(id, List.of(leaderId), now());
        parties.put(id, party);
        partyOf.put(leaderId, id);
        // 成为队长后不能同时加入别的队伍。
        invites.values().removeIf(i -> i.toId.equals(leaderId));
        return party;
    }

    private static void requireLeader(String identityId, Party party, String message) {
        if (!party.leaderId.equals(identityId)) throw new PartyException("UNAUTHORIZED", message);
    }

    private void requireIdle(Party party) {
        if (launching.contains(party.id)) throw new PartyException("CONFLICT", "队伍正在开局，请稍后再试");
    }

    private void purgeExpiredInvites() {
        long time = now();
        invites.values().removeIf(i -> i.expiresAt <= time || !parties.containsKey(i.partyId));
    }

    private String uniqueCode(String prefix, int length, Map<String, ?> used) {
        String code;
        do {
            StringBuilder sb = new StringBuilder(prefix);
            for (int i = 0; i < length; i++) sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            code = sb.toString();
        } while (used.containsKey(code));
        return code;
    }
}
