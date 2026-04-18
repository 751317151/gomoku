package com.gomoku;

import com.gomoku.game.GameRoom;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import javax.annotation.PreDestroy;

@SpringBootApplication
public class GomokuApplication {
    public static void main(String[] args) {
        SpringApplication.run(GomokuApplication.class, args);
    }

    @PreDestroy
    public void onShutdown() {
        GameRoom.shutdownAIExecutor();
    }
}
