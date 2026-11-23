package com.gomoku.server;

import com.gomoku.game.*;
import com.gomoku.model.GameMessage;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/** 好友约战：邀请不占房，接收人同意时服务端一次安排双方进入同一私房。 */
@RestController
@RequestMapping("/api/duels")
public class DuelController {
    private final DuelService duels;
    private final FriendService friends;
    private final RoomManager rooms;
    private final AccountService accounts;
    private final GameRecordService records;
    private final GuestIdentityService guests;
    private final ModerationService moderation;

    public DuelController(DuelService duels, FriendService friends, RoomManager rooms,
                          AccountService accounts, GameRecordService records,
                          GuestIdentityService guests, ModerationService moderation) {
        this.duels = duels;
        this.friends = friends;
        this.rooms = rooms;
        this.accounts = accounts;
        this.records = records;
        this.guests = guests;
        this.moderation = moderation;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String identityId = resolve(authorization);
        if (identityId == null) return unauthorized();
        return ok(Map.of("incoming", duels.invitesFor(identityId).stream().map(this::view).collect(Collectors.toList()),
                "outgoing", duels.outgoingFor(identityId).stream().map(this::view).collect(Collectors.toList()),
                "serverTime", System.currentTimeMillis()));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> invite(
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String identityId = resolve(authorization);
        if (identityId == null) return unauthorized();
        try {
            String target = body == null ? null : body.get("target");
            GameType type = parseType(body == null ? null : body.get("gameType"));
            requireFriends(identityId, target, false);
            rooms.validateDuelPlayers(identityId, target, moderation::isBanned);
            DuelService.Duel invite = duels.invite(identityId, target, type.name());
            notifyBoth(invite, "duel_invite", identityId);
            return ok(Map.of("invite", view(invite), "message", "约战邀请已发送"));
        } catch (DuelService.DuelException e) {
            return error(e);
        }
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<Map<String, Object>> accept(@PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String identityId = resolve(authorization);
        if (identityId == null) return unauthorized();
        try {
            DuelService.Started<GameRoom> started = duels.accept(identityId, id, invite -> {
                requireFriends(invite.fromId, invite.toId, true);
                return rooms.startDuelGame(invite.fromId, invite.toId,
                        GameType.valueOf(invite.gameType), moderation::isBanned);
            });
            // 开局会作废双方与第三人的其他邀请；所有受影响页面都需刷新列表。
            Set<String> affected = new LinkedHashSet<>();
            for (DuelService.Duel cleared : started.clearedInvites) {
                affected.add(cleared.fromId);
                affected.add(cleared.toId);
            }
            GameMessage update = event("duel_started", identityId);
            for (String affectedId : affected) rooms.sendToIdentity(affectedId, update);
            GameRoom room = started.result;
            return ok(Map.of("roomId", room.getRoomId(), "gameType", room.getGameType().name(),
                    "message", "约战已开始，正在进入房间"));
        } catch (DuelService.DuelException e) {
            return error(e);
        }
    }

    @PostMapping("/{id}/decline")
    public ResponseEntity<Map<String, Object>> decline(@PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String identityId = resolve(authorization);
        if (identityId == null) return unauthorized();
        try {
            notifyBoth(duels.decline(identityId, id), "duel_decline", identityId);
            return ok(Map.of("message", "已拒绝约战"));
        } catch (DuelService.DuelException e) {
            return error(e);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String identityId = resolve(authorization);
        if (identityId == null) return unauthorized();
        try {
            notifyBoth(duels.cancel(identityId, id), "duel_cancel", identityId);
            return ok(Map.of("message", "已撤回约战"));
        } catch (DuelService.DuelException e) {
            return error(e);
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> invalidBody() {
        return error(400, "INVALID", "约战参数格式无效");
    }

    private void requireFriends(String fromId, String toId, boolean accepting) {
        if (toId == null || toId.isBlank() || fromId.equals(toId)) {
            throw new DuelService.DuelException("INVALID", "请选择另一位好友约战");
        }
        if (!friends.areFriends(fromId, toId)) {
            throw new DuelService.DuelException(accepting ? "CONFLICT" : "INVALID",
                    accepting ? "好友关系已解除，不能接受这条约战" : "只能邀请好友约战");
        }
    }

    private GameType parseType(String rawType) {
        if (rawType == null || rawType.isBlank()) throw new DuelService.DuelException("INVALID", "请先选择玩法");
        GameType type;
        try { type = GameType.valueOf(rawType.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new DuelService.DuelException("INVALID", "不支持该玩法"); }
        if (type.getMinPlayers() > 2 || type.getMaxPlayers() < 2) {
            throw new DuelService.DuelException("INVALID", "该玩法不支持双人约战");
        }
        return type;
    }

    private Map<String, Object> view(DuelService.Duel invite) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", invite.id);
        result.put("fromId", invite.fromId);
        result.put("fromName", displayName(invite.fromId));
        result.put("toId", invite.toId);
        result.put("toName", displayName(invite.toId));
        result.put("gameType", invite.gameType);
        result.put("gameLabel", GameType.valueOf(invite.gameType).getDisplayName());
        result.put("createdAt", invite.createdAt);
        result.put("expiresAt", invite.expiresAt);
        return result;
    }

    private String displayName(String identityId) {
        Map<String, Object> presence = rooms.getIdentityPresence(identityId);
        if (presence.get("name") != null) return String.valueOf(presence.get("name"));
        Map<String, String> account = accounts.findByIdentity(identityId);
        if (account != null) {
            String nickname = account.get("nickname");
            if (nickname != null && !nickname.isBlank()) return nickname;
            return account.get("username");
        }
        String recordName = records.getLastKnownName(identityId);
        if (recordName != null && !recordName.isBlank()) return recordName;
        return "玩家" + identityId.substring(Math.max(0, identityId.length() - 4));
    }

    private GameMessage event(String kind, String identityId) {
        return RoomManager.friendEvent(kind, identityId, displayName(identityId));
    }

    private void notifyBoth(DuelService.Duel invite, String kind, String identityId) {
        GameMessage update = event(kind, identityId);
        rooms.sendToIdentity(invite.fromId, update);
        rooms.sendToIdentity(invite.toId, update);
    }

    private String resolve(String header) {
        // 任意约战请求都顺手回收过期邀请，包括无效身份的请求。
        duels.pendingCount();
        return guests.resolve(header != null && header.startsWith("Bearer ") ? header.substring(7) : null);
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return error(401, "UNAUTHORIZED", "请先进入大厅获取身份");
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<Map<String, Object>> error(DuelService.DuelException e) {
        int status;
        switch (e.getCode()) {
            case "NOT_FOUND": status = 404; break;
            case "CONFLICT": status = 409; break;
            case "LIMIT": status = 429; break;
            case "UNAVAILABLE":
            case "UNAUTHORIZED": status = 403; break;
            default: status = 400;
        }
        return error(status, e.getCode(), e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code, "message", message));
    }
}
