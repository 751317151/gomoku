package com.gomoku.server;

import com.gomoku.game.GameRecordService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 历史存档不是可静默丢弃的日志；写入失败重试期间，将部署健康检查标为 DOWN。 */
@Component("gameRecords")
public class GameRecordHealthIndicator implements HealthIndicator {
    private final GameRecordService records;

    public GameRecordHealthIndicator(GameRecordService records) { this.records = records; }

    @Override
    public Health health() {
        Map<String, Object> status = records.getStorageStatus();
        boolean ready = Boolean.TRUE.equals(status.get("ready"));
        boolean failed = ((Number) status.get("failedRecords")).longValue() > 0;
        return (ready && !failed ? Health.up() : Health.down()).withDetails(status).build();
    }
}
