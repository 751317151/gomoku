package com.gomoku.server;

import com.gomoku.game.AccountService;
import com.gomoku.game.FriendService;
import com.gomoku.game.GameRecordService;
import com.gomoku.game.GameRoom;
import com.gomoku.game.GameType;
import com.gomoku.game.GuestIdentityService;
import com.gomoku.game.ModerationService;
import com.gomoku.game.PartyService;
import com.gomoku.game.RoomManager;
import com.gomoku.model.GameMessage;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 在线好友的临时队伍。接受邀请不占座，队长开局时由房间服务一次安排全队。 */
@RestController
@RequestMapping("/api/party")
public class PartyController {

    private final PartyService parties;
    private final FriendService friends;
    private final RoomManager roomManager;
    private final AccountService accounts;
    private final GameRecordService records;
    private final GuestIdentityService guests;
    private final ModerationService moderation;

    public PartyController(PartyService parties, FriendService friends, RoomManager roomManager,
                           AccountService accounts, GameRecordService records,
                           GuestIdentityService guests, ModerationService moderation) {
        this.parties = parties;
        this.friends = friends;
        this.roomManager = roomManager;
        this.accounts = accounts;
        this.records = records;
        this.guests = guests;
        this.moderation = moderation;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> mine(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String identityId = resolve(authorization);
        if (identityId == null) return unauthorized();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("party", partyView(identityId));
        List<Map<String, Object>> invites = new ArrayList<>();
        for (PartyService.Invite invite : parties.invitesFor(identityId)) {
            invites.add(Map.of("id", invite.id, "partyId", invite.partyId,
                    "fromName", displayName(invite.fromId, roomManager.getIdentityPresence(invite.fromId)),
                    "expiresAt", invite.expiresAt, "memberCount", parties.memberCount(invite.partyId)));
        }
        body.put("invites", invites);
        body.put("maxMembers", PartyService.MAX_MEMBERS);
        return ok(body);
    }

    @PostMapping("/invite")
    public ResponseEntity<Map<String, Object>> invite(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String identityId = resolve(authorization);
        if (identityId == null) return unauthorized();
        String target = body.get("target");
        if (target == null || !friends.areFriends(identityId, target)) {
            return error(400, "INVALID", "只能邀请好友组队");
        }
        if (!roomManager.isIdentityOnline(identityId) || !roomManager.isIdentityOnline(target)) {
            return error(409, "CONFLICT", "组队需要双方在线，请等待好友上线后再邀请");
        }
        if (moderation.isBanned(identityId) || moderation.isBanned(target)) {
            return error(403, "UNAVAILABLE", "暂时无法发出组队邀请");
        }
        try {
            PartyService.Invite invite = parties.invite(identityId, target);
            roomManager.sendToIdentity(target, event("party_invite", identityId, invite.partyId));
            roomManager.refreshPartyBadges(identityId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("message", "组队邀请已发送");
            result.put("party", partyView(identityId));
            result.put("inviteId", invite.id);
            return ok(result);
        } catch (PartyService.PartyException e) {
            return error(e);
        }
    }

    @PostMapping("/invites/{inviteId}/accept")
    public ResponseEntity<Map<String, Object>> accept(
            @PathVariable String inviteId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String identityId = resolve(authorization);
        if (identityId == null) return unauthorized();
        if (!roomManager.isIdentityOnline(identityId)) {
            return error(409, "CONFLICT", "请先连接大厅再加入队伍");
        }
        try {
            PartyService.Invite invite = parties.inviteFor(identityId, inviteId);
            if (!friends.areFriends(identityId, invite.fromId)) {
                return error(409, "CONFLICT", "好友关系已解除，不能接受这条邀请");
            }
            if (moderation.isBanned(identityId) || moderation.isBanned(invite.fromId)) {
                return error(403, "UNAVAILABLE", "暂时无法加入队伍");
            }
            PartyService.Party party = parties.accept(identityId, inviteId);
            notifyParty(party, "party_joined", identityId);
            roomManager.refreshPartyBadges(identityId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("message", "已加入队伍");
            result.put("party", partyView(identityId));
            return ok(result);
        } catch (PartyService.PartyException e) {
            return error(e);
        }
    }

    @PostMapping("/invites/{inviteId}/decline")
    public ResponseEntity<Map<String, Object>> decline(
            @PathVariable String inviteId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String identityId = resolve(authorization);
        if (identityId == null) return unauthorized();
        try {
            parties.decline(identityId, inviteId);
            return ok(Map.of("message", "已拒绝邀请"));
        } catch (PartyService.PartyException e) {
            return error(e);
        }
    }

    @DeleteMapping("/members")
    public ResponseEntity<Map<String, Object>> leave(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String identityId = resolve(authorization);
        if (identityId == null) return unauthorized();
        try {
            PartyService.Party before = parties.partyOf(identityId);
            PartyService.Party after = parties.leave(identityId);
            if (before != null) {
                if (after != null) notifyParty(after, "party_left", identityId);
                roomManager.sendToIdentity(identityId, event("party_left_self", identityId, before.id));
                roomManager.refreshPartyBadges(identityId);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("message", "已退出队伍");
            result.put("party", null);
            return ok(result);
        } catch (PartyService.PartyException e) {
            return error(e);
        }
    }

    /** 只接受玩法，房号、座位与实际人数全部由服务端决定。 */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String identityId = resolve(authorization);
        if (identityId == null) return unauthorized();
        String rawType = body.get("gameType");
        if (rawType == null) return error(400, "INVALID", "请先选择玩法");
        GameType type;
        try { type = GameType.valueOf(rawType.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return error(400, "INVALID", "不支持该玩法"); }
        try {
            GameRoom room = parties.launch(identityId, party -> roomManager.startPartyGame(party, type, moderation::isBanned));
            return ok(Map.of("roomId", room.getRoomId(), "gameType", type.name(), "playerCount", room.getTargetPlayers()));
        } catch (PartyService.PartyException e) {
            return error(e);
        }
    }

    private void notifyParty(PartyService.Party party, String kind, String aboutId) {
        GameMessage update = event(kind, aboutId, party.id);
        for (String memberId : party.members) roomManager.sendToIdentity(memberId, update);
    }

    private GameMessage event(String kind, String identityId, String partyId) {
        return RoomManager.partyEvent(kind, identityId, displayName(identityId, roomManager.getIdentityPresence(identityId)), partyId);
    }

    private Map<String, Object> partyView(String identityId) {
        PartyService.Party party = parties.partyOf(identityId);
        if (party == null) return null;
        List<Map<String, Object>> members = new ArrayList<>();
        for (String memberId : party.members) {
            Map<String, Object> presence = roomManager.getIdentityPresence(memberId);
            // 队伍可能包括队长的两位互不相识的好友，不向队伍公开对方私房位置。
            members.add(Map.of("id", memberId, "name", displayName(memberId, presence),
                    "leader", memberId.equals(party.leaderId), "online", Boolean.TRUE.equals(presence.get("online")),
                    "inRoom", presence.containsKey("roomId")));
        }
        return Map.of("id", party.id, "leaderId", party.leaderId, "isLeader", party.leaderId.equals(identityId),
                "createdAt", party.createdAt, "members", members);
    }

    private String displayName(String identityId, Map<String, Object> presence) {
        if (presence.get("name") != null) return String.valueOf(presence.get("name"));
        Map<String, String> account = accounts.findByIdentity(identityId);
        if (account != null) {
            String nickname = account.get("nickname");
            if (nickname != null && !nickname.isEmpty()) return nickname;
            return account.get("username");
        }
        String recordName = records.getLastKnownName(identityId);
        if (recordName != null && !recordName.isEmpty()) return recordName;
        return "玩家" + identityId.substring(Math.max(0, identityId.length() - 4));
    }

    private String resolve(String header) {
        return guests.resolve(header != null && header.startsWith("Bearer ") ? header.substring(7) : null);
    }

    private static ResponseEntity<Map<String, Object>> error(PartyService.PartyException e) {
        int status;
        switch (e.getCode()) {
            case "NOT_FOUND": status = 404; break;
            case "CONFLICT": status = 409; break;
            case "LIMIT": status = 429; break;
            case "UNAUTHORIZED": status = 403; break;
            default: status = 400;
        }
        return error(status, e.getCode(), e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return error(401, "UNAUTHORIZED", "请先进入大厅获取身份");
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code, "message", message));
    }
}
