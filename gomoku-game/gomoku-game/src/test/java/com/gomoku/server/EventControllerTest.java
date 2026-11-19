package com.gomoku.server;

import com.gomoku.game.EventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 活动接口：大厅公开可读，管理端发奖配置必须带令牌且即改即生效。 */
class EventControllerTest {

    @TempDir Path dir;

    private MockMvc mvc(String adminToken) {
        EventService service = new EventService(dir.resolve("events.json"));
        service.init();
        return MockMvcBuilders.standaloneSetup(
                new EventController(service, new AdminGuard(adminToken))).build();
    }

    @Test
    void publicEndpointNeedsNoTokenAndAdminEndpointsDo() throws Exception {
        MockMvc mvc = mvc("secret");
        mvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events").isArray())
                .andExpect(jsonPath("$.serverTime").isNumber());

        mvc.perform(get("/api/admin/events"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/events").header("X-Admin-Token", "wrong"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/events").header("X-Admin-Token", "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.durations").isArray())
                .andExpect(jsonPath("$.percents").isArray())
                .andExpect(jsonPath("$.gameTypes[0].value").value("ALL"));

        mvc("").perform(get("/api/admin/events"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void publishThenPauseThenDelete() throws Exception {
        MockMvc mvc = mvc("secret");
        String created = mvc.perform(post("/api/admin/events")
                        .header("X-Admin-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"周末五子棋加成\",\"gameType\":\"GOMOKU\",\"xpPercent\":50,\"duration\":\"24h\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event.title").value("周末五子棋加成"))
                .andExpect(jsonPath("$.event.gameLabel").value("五子棋"))
                .andExpect(jsonPath("$.event.xpPercent").value(50))
                .andExpect(jsonPath("$.event.live").value(true))
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(created, "$.event.id");

        mvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].id").value(id))
                .andExpect(jsonPath("$.events[0].gameLabel").value("五子棋"))
                .andExpect(jsonPath("$.events[0].active").doesNotExist());

        mvc.perform(post("/api/admin/events/" + id)
                        .header("X-Admin-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event.live").value(false));

        mvc.perform(get("/api/events"))
                .andExpect(jsonPath("$.events").isEmpty());

        mvc.perform(post("/api/admin/events/" + id)
                        .header("X-Admin-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/events"))
                .andExpect(jsonPath("$.events[0].id").value(id));

        mvc.perform(delete("/api/admin/events/" + id).header("X-Admin-Token", "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
        mvc.perform(delete("/api/admin/events/" + id).header("X-Admin-Token", "secret"))
                .andExpect(status().isNotFound());
    }

    @Test
    void validationErrorsAndMissingFieldsReturn400() throws Exception {
        MockMvc mvc = mvc("secret");
        mvc.perform(post("/api/admin/events")
                        .header("X-Admin-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"gameType\":\"ALL\",\"xpPercent\":50}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/events")
                        .header("X-Admin-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"活动\",\"gameType\":\"CHESS\",\"xpPercent\":50}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("未知的玩法"));
        mvc.perform(post("/api/admin/events")
                        .header("X-Admin-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"活动\",\"gameType\":\"ALL\",\"xpPercent\":5}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/events")
                        .header("X-Admin-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"活动\",\"gameType\":\"ALL\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/events")
                        .header("X-Admin-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"活动\",\"gameType\":\"ALL\",\"xpPercent\":50,\"duration\":\"2h\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("未知的活动时长选项"));
        mvc.perform(post("/api/admin/events/EVT-NONE")
                        .header("X-Admin-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/events/EVT-NONE")
                        .header("X-Admin-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true}"))
                .andExpect(status().isNotFound());
    }
}
