package com.gomoku.server;

import com.gomoku.dto.RoomListItemDto;
import com.gomoku.game.GameRecord;
import com.gomoku.game.GameRecordService;
import com.gomoku.game.RoomManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST API 控制器 - 提供 HTTP 接口
 */
@RestController
public class GameApiController {

    /** 对局列表一次最多返回多少条（防止前端拉全量） */
    private static final int MAX_LIMIT = 100;

    private final RoomManager roomManager;
    private final GameRecordService recordService;

    @Autowired
    public GameApiController(RoomManager roomManager, GameRecordService recordService) {
        this.roomManager = roomManager;
        this.recordService = recordService;
    }

    /**
     * 获取房间列表（HTTP GET）
     */
    @GetMapping("/api/rooms")
    public List<RoomListItemDto> getRooms() {
        return roomManager.getRoomsList();
    }

    /**
     * 获取 ELO 排行榜（HTTP GET，按游戏类型）
     * @param gameType GOMOKU / REVERSI / GO9，默认五子棋
     */
    @GetMapping("/api/leaderboard")
    public List<Map<String, Object>> getLeaderboard(
            @RequestParam(required = false, defaultValue = "GOMOKU") String gameType) {
        return roomManager.getLeaderboard(gameType, 20);
    }

    /**
     * 本周活跃榜：最近 7 天全部真人对局的胜场与活跃度（含多人休闲局）。
     */
    @GetMapping("/api/leaderboard/weekly")
    public List<Map<String, Object>> getWeeklyLeaderboard(
            @RequestParam(required = false, defaultValue = "10") int limit) {
        return recordService.getWeeklyActivityLeaderboard(clamp(limit));
    }

    /**
     * 服务器实时统计（在线人数/房间数）
     */
    @GetMapping("/api/stats")
    public Map<String, Object> getStats() {
        return roomManager.getStats();
    }

    // ============ 对局记录 / 战绩 ============

    /**
     * 最近对局列表（不含棋谱）
     * @param limit 条数，默认 20，最大 100
     * @param gameType 可选，限定游戏类型
     * @param deck 可选，限定实际生效套牌（BALANCED / AGGRO / CONTROL / CUSTOM）
     */
    @GetMapping("/api/games")
    public List<Map<String, Object>> getRecentGames(
            @RequestParam(required = false, defaultValue = "20") int limit,
            @RequestParam(required = false) String gameType,
            @RequestParam(required = false) String deck) {
        return recordService.getRecentGameViews(clamp(limit), gameType, deck);
    }

    /**
     * 单局详情（含完整棋谱，供复盘）
     */
    @GetMapping("/api/games/{id}")
    public ResponseEntity<GameRecord> getGame(@PathVariable String id) {
        GameRecord record = recordService.getRecord(id);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(record);
    }

    /**
     * 玩家战绩汇总
     * @param gameType 可选，限定游戏类型；不传则统计所有游戏
     */
    @GetMapping("/api/stats/player/{name}")
    public Map<String, Object> getPlayerStats(
            @PathVariable String name,
            @RequestParam(required = false) String gameType) {
        return recordService.getPlayerStats(name, gameType);
    }

    /**
     * 玩家最近对局（不含棋谱）
     */
    @GetMapping("/api/player/{name}/games")
    public List<GameRecord> getPlayerGames(
            @PathVariable String name,
            @RequestParam(required = false, defaultValue = "20") int limit) {
        return recordService.getPlayerGames(name, clamp(limit));
    }

    private static int clamp(int limit) {
        if (limit <= 0) return 20;
        return Math.min(limit, MAX_LIMIT);
    }
}
