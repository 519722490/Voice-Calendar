package com.cyx.backend.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldCreateFindUpdateAndDeleteEvent() throws Exception {
        String token = TestAuthHelper.registerUser(mockMvc, objectMapper, "event_user").token();

        String createdJson = mockMvc.perform(post("/api/events")
                        .header("Authorization", "Bearer " + token)
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

        mockMvc.perform(get("/api/events")
                        .header("Authorization", "Bearer " + token)
                        .param("date", "2030-01-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(eventId))
                .andExpect(jsonPath("$[0].title").value("接口联调"));

        mockMvc.perform(put("/api/events/{id}", eventId)
                        .header("Authorization", "Bearer " + token)
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

        mockMvc.perform(delete("/api/events/{id}", eventId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/events/{id}", eventId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldRejectInvalidTimeRange() throws Exception {
        String token = TestAuthHelper.registerUser(mockMvc, objectMapper, "invalid_time_user").token();

        mockMvc.perform(post("/api/events")
                        .header("Authorization", "Bearer " + token)
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

    @Test
    void shouldNormalizeUnknownTagToOther() throws Exception {
        String token = TestAuthHelper.registerUser(mockMvc, objectMapper, "unknown_tag_user").token();

        mockMvc.perform(post("/api/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "自定义标签测试",
                                  "startTime": "2030-01-02T10:00:00",
                                  "tag": "项目会议"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tag").value("其他"));
    }

    @Test
    void shouldKeepEventsIsolatedBetweenUsers() throws Exception {
        String ownerToken = TestAuthHelper.registerUser(mockMvc, objectMapper, "owner_user").token();
        String otherToken = TestAuthHelper.registerUser(mockMvc, objectMapper, "other_user").token();

        String createdJson = mockMvc.perform(post("/api/events")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "仅自己可见",
                                  "startTime": "2030-02-03T09:00:00",
                                  "endTime": "2030-02-03T10:00:00"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Long eventId = JsonTestHelper.extractId(createdJson);

        mockMvc.perform(get("/api/events")
                        .header("Authorization", "Bearer " + otherToken)
                        .param("date", "2030-02-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(get("/api/events/{id}", eventId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturnRecurringInstancesWithoutExpandingSingleEvents() throws Exception {
        String token = TestAuthHelper.registerUser(mockMvc, objectMapper, "recurring_user").token();

        String recurringJson = mockMvc.perform(post("/api/recurring-events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "背单词",
                                  "startDate": "2030-05-01",
                                  "endDate": "2030-05-03",
                                  "startTime": "20:00",
                                  "recurrenceType": "DAILY",
                                  "intervalValue": 1,
                                  "tag": "学习"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.title").value("背单词"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        Long recurringId = JsonTestHelper.extractId(recurringJson);

        mockMvc.perform(get("/api/events")
                        .header("Authorization", "Bearer " + token)
                        .param("from", "2030-05-01")
                        .param("to", "2030-05-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].id").doesNotExist())
                .andExpect(jsonPath("$[0].sourceType").value("RECURRING"))
                .andExpect(jsonPath("$[0].recurringEventId").value(recurringId))
                .andExpect(jsonPath("$[0].startTime").value("2030-05-01T20:00:00"))
                .andExpect(jsonPath("$[1].startTime").value("2030-05-02T20:00:00"))
                .andExpect(jsonPath("$[2].startTime").value("2030-05-03T20:00:00"));

        mockMvc.perform(get("/api/recurring-events")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void shouldUpdateWholeRecurringRuleAndRefreshInstances() throws Exception {
        String token = TestAuthHelper.registerUser(mockMvc, objectMapper, "recurring_update_user").token();

        String recurringJson = mockMvc.perform(post("/api/recurring-events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "背单词",
                                  "startDate": "2030-06-01",
                                  "endDate": "2030-06-03",
                                  "startTime": "20:00",
                                  "recurrenceType": "DAILY",
                                  "intervalValue": 1,
                                  "tag": "学习"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long recurringId = JsonTestHelper.extractId(recurringJson);

        mockMvc.perform(put("/api/recurring-events/{id}", recurringId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "复习英语",
                                  "startDate": "2030-06-01",
                                  "endDate": "2030-06-03",
                                  "startTime": "21:00",
                                  "recurrenceType": "DAILY",
                                  "intervalValue": 1,
                                  "tag": "学习",
                                  "location": "书房"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(recurringId))
                .andExpect(jsonPath("$.title").value("复习英语"))
                .andExpect(jsonPath("$.startTime").value("21:00:00"))
                .andExpect(jsonPath("$.location").value("书房"));

        mockMvc.perform(get("/api/events")
                        .header("Authorization", "Bearer " + token)
                        .param("date", "2030-06-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sourceType").value("RECURRING"))
                .andExpect(jsonPath("$[0].recurringEventId").value(recurringId))
                .andExpect(jsonPath("$[0].title").value("复习英语"))
                .andExpect(jsonPath("$[0].startTime").value("2030-06-02T21:00:00"))
                .andExpect(jsonPath("$[0].location").value("书房"));
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
