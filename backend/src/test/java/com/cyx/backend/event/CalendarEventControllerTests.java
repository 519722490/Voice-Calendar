package com.cyx.backend.event;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class CalendarEventControllerTests {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldCreateFindUpdateAndDeleteEvent() throws Exception {
        String createdJson = mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "接口联调",
                                  "startTime": "2030-01-02T09:00:00",
                                  "endTime": "2030-01-02T10:00:00",
                                  "location": "开发环境",
                                  "description": "验证日程接口",
                                  "tag": "开发",
                                  "reminderTime": "2030-01-02T08:50:00"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.title").value("接口联调"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        Long eventId = JsonTestHelper.extractId(createdJson);

        mockMvc.perform(get("/api/events").param("date", "2030-01-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(eventId))
                .andExpect(jsonPath("$[0].title").value("接口联调"));

        mockMvc.perform(put("/api/events/{id}", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "接口联调复盘",
                                  "startTime": "2030-01-02T11:00:00",
                                  "endTime": "2030-01-02T12:00:00",
                                  "location": "会议室",
                                  "description": "更新后的日程",
                                  "tag": "复盘"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("接口联调复盘"))
                .andExpect(jsonPath("$.location").value("会议室"));

        mockMvc.perform(delete("/api/events/{id}", eventId))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/events/{id}", eventId))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldRejectInvalidTimeRange() throws Exception {
        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "错误时间",
                                  "startTime": "2030-01-02T10:00:00",
                                  "endTime": "2030-01-02T09:00:00"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("结束时间必须晚于开始时间"));
    }

    static class JsonTestHelper {
        private JsonTestHelper() {
        }

        static Long extractId(String json) {
            String marker = "\"id\":";
            int start = json.indexOf(marker) + marker.length();
            int end = json.indexOf(',', start);
            return Long.parseLong(json.substring(start, end).trim());
        }
    }
}
