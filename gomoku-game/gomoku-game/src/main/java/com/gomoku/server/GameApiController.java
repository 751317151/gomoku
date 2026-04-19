package com.gomoku.server;

import com.gomoku.game.RoomManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API 控制器 - 提供 HTTP 接口
 */
@RestController
public class GameApiController {

    private final RoomManager roomManager;

    @Autowired
    public GameApiController(RoomManager roomManager) {
        this.roomManager = roomManager;
    }

    /**
     * 获取房间列表（HTTP GET）
     */
    @GetMapping("/api/rooms")
    public String getRooms() {
        return roomManager.getRoomsListJson();
    }

    /**
     * 获取 ELO 排行榜（HTTP GET）
     */
    @GetMapping("/api/leaderboard")
    public String getLeaderboard() {
        return roomManager.getLeaderboardJson();
    }
}
