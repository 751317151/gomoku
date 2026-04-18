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
     * 前端首次加载或手动刷新时调用，无需 WebSocket 连接
     */
    @GetMapping("/api/rooms")
    public String getRooms() {
        return roomManager.getRoomsListJson();
    }
}
