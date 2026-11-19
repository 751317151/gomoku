package com.gomoku.server;

import com.gomoku.game.AnnouncementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 公告接口：玩家侧公开可读，管理侧必须带令牌，上下线即时影响大厅列表。 */
class AnnouncementControllerTest {

    @TempDir Path dir;

    private MockMvc mvc(String adminToken) {
        AnnouncementService service = new AnnouncementService(dir.resolve("announcements.json"));
        service.init();
        return MockMvcBuilders.standaloneSetup(
                new AnnouncementController(service, new AdminGuard(adminToken))).build();
    }

    @Test
    void publicEndpointNeedsNoTokenAndAdminEndpointsDo() throws Exception {
        MockMvc mvc = mvc("secret");
        mvc.perform(get("/api/announcements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.announcements").isArray())
                .andExpect(jsonPath("$.serverTime").isNumber());

        mvc.perform(get("/api/admin/announcements"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/announcements").header("X-Admin-Token", "wrong"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/announcements").header("X-Admin-Token", "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.levels").isArray())
                .andExpect(jsonPath("$.ttls").isArray());

        mvc("").perform(get("/api/admin/announcements"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void publishThenOfflineThenDelete() throws Exception {
        MockMvc mvc = mvc("secret");
        String created = mvc.perform(post("/api/admin/announcements")
                        .header("X-Admin-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"维护通知\",\"body\":\"今晚 23:00 停机维护\",\"level\":\"maintenance\",\"ttl\":\"24h\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.announcement.title").value("维护通知"))
                .andExpect(jsonPath("$.announcement.active").value(true))
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(created, "$.announcement.id");

        mvc.perform(get("/api/announcements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.announcements[0].id").value(id))
                .andExpect(jsonPath("$.announcements[0].body").value("今晚 23:00 停机维护"))
                .andExpect(jsonPath("$.announcements[0].active").doesNotExist());

        mvc.perform(post("/api/admin/announcements/" + id)
                        .header("X-Admin-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.announcement.active").value(false));

        mvc.perform(get("/api/announcements"))
                .andExpect(jsonPath("$.announcements").isEmpty());

        mvc.perform(post("/api/admin/announcements/" + id)
                        .header("X-Admin-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/announcements"))
                .andExpect(jsonPath("$.announcements[0].id").value(id));

        mvc.perform(delete("/api/admin/announcements/" + id).header("X-Admin-Token", "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
        mvc.perform(delete("/api/admin/announcements/" + id).header("X-Admin-Token", "secret"))
                .andExpect(status().isNotFound());
    }

    @Test
    void validationErrorsAndMissingFieldsReturn400() throws Exception {
        MockMvc mvc = mvc("secret");
        mvc.perform(post("/api/admin/announcements")
                        .header("X-Admin-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"body\":\"内容\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/announcements")
                        .header("X-Admin-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"标题\",\"body\":\"内容\",\"level\":\"info\",\"ttl\":\"2h\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("未知的有效期选项"));
        mvc.perform(post("/api/admin/announcements/ANN-NONE")
                        .header("X-Admin-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/announcements/ANN-NONE")
                        .header("X-Admin-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true}"))
                .andExpect(status().isNotFound());
    }
}
