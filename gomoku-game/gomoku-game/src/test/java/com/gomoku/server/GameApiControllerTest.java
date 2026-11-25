package com.gomoku.server;

import com.gomoku.game.AccountService;
import com.gomoku.game.GameRecommendationService;
import com.gomoku.game.GameRecordService;
import com.gomoku.game.GuestIdentityService;
import com.gomoku.game.FriendService;
import com.gomoku.game.PartyService;
import com.gomoku.game.ProductAnalyticsService;
import com.gomoku.game.RoomManager;
import com.gomoku.game.SeasonService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 推荐接口必须服务端记录曝光，避免只靠客户端上报伪造漏斗。 */
class GameApiControllerTest {

    @TempDir Path dir;

    @AfterEach
    void stopAnalytics() {
        if (analytics != null) analytics.shutdown();
    }

    private ProductAnalyticsService analytics;

    @Test
    void recommendationsEndpointRecordsServerSideExposure() throws Exception {
        Path recordsDir = dir.resolve("records");
        Files.createDirectories(recordsDir);
        GameRecordService records = new GameRecordService(recordsDir);
        try {
            SeasonService seasons = new SeasonService(records, dir.resolve("seasons.json"));
            GuestIdentityService guests = new GuestIdentityService(dir.resolve("guests.json"));
            guests.init();
            AccountService accounts = new AccountService(dir.resolve("accounts.json"), guests);
            accounts.init();
            FriendService friends = new FriendService(dir.resolve("friends.json"), accounts);
            friends.init();
            RoomManager rooms = new RoomManager(new SimpleMeterRegistry(),
                    new com.gomoku.game.EloService() {
                        @Override public java.util.List<Map<String, Object>> getLeaderboard(
                                com.gomoku.game.GameType gameType, int topN) {
                            return new java.util.ArrayList<>();
                        }
                    },
                    records, seasons, friends, new PartyService(),
                    new com.gomoku.game.EventService(dir.resolve("events.json")));
            GameRecommendationService recommendations = new GameRecommendationService(records);
            analytics = new ProductAnalyticsService(dir.resolve("analytics"));
            analytics.init();
            GameApiController controller = new GameApiController(
                    rooms, records, seasons, guests, recommendations, analytics);
            MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

            mockMvc.perform(get("/api/recommendations").param("limit", "3"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.recommendations.length()").value(3))
                    .andExpect(jsonPath("$.identityBound").value(false));

            Map<String, Object> funnel = analytics.overview(1);
            @SuppressWarnings("unchecked")
            Map<String, Object> recommendation = (Map<String, Object>) funnel.get("recommendation");
            org.junit.jupiter.api.Assertions.assertEquals(0, recommendation.get("shownUsers"),
                    "匿名请求没有持久身份，不能伪造访客");

            String token = guests.issue().get("token");
            mockMvc.perform(get("/api/recommendations").param("limit", "3")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.identityBound").value(true));
            analytics.flush();
            recommendation = (Map<String, Object>) analytics.overview(1).get("recommendation");
            org.junit.jupiter.api.Assertions.assertEquals(1, recommendation.get("shownUsers"));
            org.junit.jupiter.api.Assertions.assertEquals(1, recommendation.get("shownEvents"));
        } finally {
            records.shutdown();
        }
    }
}
