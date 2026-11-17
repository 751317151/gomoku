package com.gomoku.server;

import com.gomoku.game.AccountService;
import com.gomoku.game.FriendService;
import com.gomoku.game.GameRecordService;
import com.gomoku.game.GuestIdentityService;
import com.gomoku.game.RoomManager;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 好友 REST 接口：好友码/用户名互加、同意/拒绝、删除、好友在线状态与房间位置。
 *
 * 展示名解析顺序：实时在线昵称 → 账号昵称 → 最近对局昵称 → 身份后缀。
 * 全部返回 no-store；业务错误映射成带 code 的 JSON（与 AccountController 同款）。
 */
@RestController
@RequestMapping("/api/friends")
public class FriendController {

    private final FriendService friends;
    private final RoomManager roomManager;
    private final AccountService accounts;
    private final GameRecordService records;
    private final GuestIdentityService guests;

    public FriendController(FriendService friends, RoomManager roomManager,
                            AccountService accounts, GameRecordService records,
                            GuestIdentityService guests) {
        this.friends = friends;
        this.roomManager = roomManager;
        this.accounts = accounts;
        this.records = records;
        this.guests = guests;
    }

    /** 我的好友全景：好友码、好友列表（含在线/房间）、收到的与发出的申请 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String identityId = guests.resolve(token(authorization));
        if (identityId == null) return error(HttpStatus.UNAUTHORIZED.value(), "UNAUTHORIZED", "请先进入大厅获取身份");
        // 打开面板即补发好友码：老身份没分配过也能拿到自己的码
        String code = friends.friendCodeOf(identityId);

        Map<String, Object> me = new LinkedHashMap<>();
        me.put("id", identityId);
        me.put("code", code);

        List<Map<String, Object>> friendList = new ArrayList<>();
        for (String friendId : friends.friendsOf(identityId)) {
            Map<String, Object> entry = baseEntry(friendId);
            entry.putAll(presenceFields(friendId));
            friendList.add(entry);
        }

        List<Map<String, Object>> incoming = new ArrayList<>();
        for (String fromId : friends.incomingOf(identityId)) incoming.add(baseEntry(fromId));

        List<Map<String, Object>> outgoing = new ArrayList<>();
        for (String toId : friends.outgoingOf(identityId)) outgoing.add(baseEntry(toId));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("me", me);
        result.put("friends", friendList);
        result.put("incoming", incoming);
        result.put("outgoing", outgoing);
        return ok(result);
    }

    /** 发出好友申请：target 可以是对方好友码（8 位）或用户名 */
    @PostMapping("/requests")
    public ResponseEntity<Map<String, Object>> sendRequest(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String identityId = guests.resolve(token(authorization));
        if (identityId == null) return error(HttpStatus.UNAUTHORIZED.value(), "UNAUTHORIZED", "请先进入大厅获取身份");
        String target = friends.resolveTarget(body == null ? null : body.get("target"));
        if (target == null) {
            return error(HttpStatus.NOT_FOUND.value(), "NOT_FOUND", "找不到该好友码或用户名");
        }
        try {
            friends.request(identityId, target);
        } catch (FriendService.FriendException e) {
            return error(statusOf(e), e);
        }
        // 对方在线时立即收到大厅提醒（不在线时靠打开面板时的 REST 拉取兜底）
        roomManager.sendToIdentity(target, RoomManager.friendEvent("request", identityId, displayName(identityId, null)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "申请已发送，等待对方同意");
        result.put("target", baseEntry(target));
        return ok(result);
    }

    /** 同意好友申请（path 变量为对方身份 ID，来自 GET /api/friends 的 incoming） */
    @PostMapping("/requests/{id}/accept")
    public ResponseEntity<Map<String, Object>> accept(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String identityId = requireIdentity(authorization);
        if (identityId == null) return unauthorized();
        try {
            friends.accept(identityId, id);
        } catch (FriendService.FriendException e) {
            return error(statusOf(e), e);
        }
        // 让发起方实时知道申请被通过（双方面板都会在收到事件后刷新）
        roomManager.sendToIdentity(id, RoomManager.friendEvent("accepted", identityId, displayName(identityId, null)));
        return message("已添加好友");
    }

    /** 拒绝好友申请 */
    @PostMapping("/requests/{id}/decline")
    public ResponseEntity<Map<String, Object>> decline(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String identityId = requireIdentity(authorization);
        if (identityId == null) return unauthorized();
        try {
            friends.decline(identityId, id);
        } catch (FriendService.FriendException e) {
            return error(statusOf(e), e);
        }
        return message("已拒绝申请");
    }

    /** 撤回我发出的申请 */
    @DeleteMapping("/requests/{id}")
    public ResponseEntity<Map<String, Object>> cancel(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String identityId = requireIdentity(authorization);
        if (identityId == null) return unauthorized();
        try {
            friends.cancel(identityId, id);
        } catch (FriendService.FriendException e) {
            return error(statusOf(e), e);
        }
        return message("已撤回申请");
    }

    /** 删除好友（双向移除） */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> remove(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String identityId = requireIdentity(authorization);
        if (identityId == null) return unauthorized();
        try {
            friends.remove(identityId, id);
        } catch (FriendService.FriendException e) {
            return error(statusOf(e), e);
        }
        return message("已删除好友");
    }

    // ============ 视图组装 ============

    /** 基础条目：身份 + 解析出的展示名（申请列表只需要这两项） */
    private Map<String, Object> baseEntry(String identityId) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", identityId);
        entry.put("name", displayName(identityId, null));
        return entry;
    }

    /** 好友条目的在线状态与房间位置（房间位置对好友可见 = 邀请语义） */
    private Map<String, Object> presenceFields(String friendId) {
        Map<String, Object> presence = roomManager.getIdentityPresence(friendId);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("online", Boolean.TRUE.equals(presence.get("online")));
        if (presence.get("name") != null) {
            fields.put("name", displayName(friendId, presence));
        }
        fields.put("roomId", presence.get("roomId"));
        fields.put("roomState", presence.get("roomState"));
        fields.put("gameType", presence.get("gameType"));
        fields.put("joinable", Boolean.TRUE.equals(presence.get("joinable")));
        return fields;
    }

    /**
     * 展示名解析：实时在线昵称 → 账号昵称 → 最近对局昵称 → 身份后缀。
     * （presence 传入时优先用实时昵称，避免多一次账号/记录查询。）
     */
    private String displayName(String identityId, Map<String, Object> presence) {
        if (presence != null && presence.get("name") != null) {
            return String.valueOf(presence.get("name"));
        }
        Map<String, String> account = accounts.findByIdentity(identityId);
        if (account != null) {
            String nickname = account.get("nickname");
            if (nickname != null && !nickname.isEmpty()) return nickname;
            return account.get("username");
        }
        String recordName = records.getLastKnownName(identityId);
        if (recordName != null && !recordName.isEmpty()) return recordName;
        String tail = identityId.length() <= 4 ? identityId : identityId.substring(identityId.length() - 4);
        return "玩家" + tail;
    }

    /** 从请求头解析持久身份；无效返回 null */
    private String requireIdentity(String authorization) {
        return guests.resolve(token(authorization));
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return error(HttpStatus.UNAUTHORIZED.value(), "UNAUTHORIZED", "请先进入大厅获取身份");
    }

    private static ResponseEntity<Map<String, Object>> message(String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", text);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<Map<String, Object>> error(int status, FriendService.FriendException e) {
        return error(status, e.getCode(), e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }

    private static int statusOf(FriendService.FriendException e) {
        switch (e.getCode()) {
            case "NOT_FOUND": return HttpStatus.NOT_FOUND.value();
            case "CONFLICT": return HttpStatus.CONFLICT.value();
            case "LIMIT": return HttpStatus.TOO_MANY_REQUESTS.value();
            case "UNAUTHORIZED": return HttpStatus.UNAUTHORIZED.value();
            default: return HttpStatus.BAD_REQUEST.value();
        }
    }

    private static String token(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }
}
