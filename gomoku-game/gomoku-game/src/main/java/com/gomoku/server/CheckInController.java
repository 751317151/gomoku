package com.gomoku.server;

import com.gomoku.game.CheckInService;
import com.gomoku.game.GuestIdentityService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 签到 REST 接口。必须携带有效游客/账号凭证，奖励按 identityId 归属。
 */
@RestController
@RequestMapping("/api/checkin")
public class CheckInController {
    private final CheckInService checkIns;
    private final GuestIdentityService guests;

    public CheckInController(CheckInService checkIns, GuestIdentityService guests) {
        this.checkIns = checkIns;
        this.guests = guests;
    }

    /** 查询今日是否已签、连续天数、下一里程碑、回归礼包可用性 */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String id = guests.resolve(token(authorization));
        if (id == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ok(checkIns.status(id));
    }

    /** 执行签到；同一天重复签到不重复发奖 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> checkIn(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String id = guests.resolve(token(authorization));
        if (id == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ok(checkIns.checkIn(id));
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static String token(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }
}
