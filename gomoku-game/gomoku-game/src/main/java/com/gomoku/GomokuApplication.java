package com.gomoku;

import com.gomoku.game.GameRoom;
import com.gomoku.game.RoomManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import javax.annotation.PreDestroy;

@SpringBootApplication
public class GomokuApplication {

    private static final Logger logger = LoggerFactory.getLogger(GomokuApplication.class);
    private static RoomManager roomManager;

    public static void main(String[] args) {
        SpringApplication.run(GomokuApplication.class, args);
    }

    /**
     * 由 Spring 注入 RoomManager（在 main 之后）
     */
    @org.springframework.beans.factory.annotation.Autowired
    public void setRoomManager(RoomManager rm) {
        roomManager = rm;
    }

    @PreDestroy
    public void onShutdown() {
        logger.info("[SHUTDOWN] 开始优雅停机...");
        // 1. 通知所有客户端
        if (roomManager != null) {
            roomManager.notifyAllShutdown();
        }
        // 2. 关闭 AI 线程池
        GameRoom.shutdownAIExecutor();
        logger.info("[SHUTDOWN] 优雅停机完成");
    }
}
