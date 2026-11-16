package com.gomoku.server;

import com.gomoku.game.GuestIdentityService;
import com.gomoku.game.PlayerProgressService;
import com.gomoku.game.GameRecordService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.*;

@RestController
public class GuestController {
    private final GuestIdentityService guests;
    private final GameRecordService records;
    private final PlayerProgressService progress;
    public GuestController(GuestIdentityService guests, GameRecordService records, PlayerProgressService progress) {
        this.guests = guests; this.records = records; this.progress = progress;
    }
    @PostMapping("/api/guest")
    public ResponseEntity<Map<String, String>> guest(@RequestHeader(value="Authorization", required=false) String authorization) {
        String id = guests.resolve(token(authorization));
        Map<String, String> result;
        if (id == null) result = guests.issue();
        else { result = new LinkedHashMap<>(); result.put("id", id); }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
    @GetMapping("/api/me/stats")
    public ResponseEntity<Map<String, Object>> stats(@RequestHeader(value="Authorization", required=false) String authorization,
            @RequestParam(required=false) String gameType) {
        String id = guests.resolve(token(authorization));
        if (id == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(records.getIdentityStats(id, gameType));
    }
    @GetMapping("/api/me/daily-progress")
    public ResponseEntity<Map<String, Object>> dailyProgress(
            @RequestHeader(value="Authorization", required=false) String authorization) {
        String id = guests.resolve(token(authorization));
        if (id == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(records.getIdentityDailyProgress(id));
    }
    @GetMapping("/api/me/progress")
    public ResponseEntity<Map<String, Object>> playerProgress(
            @RequestHeader(value="Authorization", required=false) String authorization) {
        String id = guests.resolve(token(authorization));
        if (id == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(progress.getProgress(id));
    }
    private static String token(String header) { return header != null && header.startsWith("Bearer ") ? header.substring(7) : null; }
}
