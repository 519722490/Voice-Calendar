package com.cyx.backend.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cyx.backend.dto.AgentActionResult;
import com.cyx.backend.dto.CalendarAgentIntent;
import com.cyx.backend.dto.CalendarAgentPlan;
import com.cyx.backend.dto.PendingAgentAction;
import com.cyx.backend.dto.PendingRecurringAgentAction;
import com.cyx.backend.repository.UserRepository;
import com.cyx.backend.service.AgentConfirmationStore;
import com.cyx.backend.service.AgentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(properties = "voice-calendar.ai.enabled=false")
@AutoConfigureMockMvc
class AgentControllerTests {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AgentConfirmationStore confirmationStore;

    @Autowired
    private AgentService agentService;

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldReturnDisabledMessageWhenAiIsNotEnabled() throws Exception {
        String token = TestAuthHelper.registerUser(mockMvc, objectMapper, "agent_user").token();

        mockMvc.perform(post("/api/agent/chat")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "帮我查看今天的日程",
                                  "conversationId": "test"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiEnabled").value(false))
                .andExpect(jsonPath("$.content").isNotEmpty());
    }

    @Test
    void shouldConfirmPendingDeleteAction() throws Exception {
        TestAuthHelper.TestUser user = TestAuthHelper.registerUser(mockMvc, objectMapper, "agent_confirm_user");
        String createdJson = mockMvc.perform(post("/api/events")
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "待删除会议",
                                  "startTime": "2030-03-04T15:00:00",
                                  "endTime": "2030-03-04T16:00:00"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long eventId = objectMapper.readTree(createdJson).get("id").asLong();
        Long userId = userRepository.findByUsername(user.username()).orElseThrow().getId();
        PendingAgentAction pendingAction = confirmationStore.save(userId, new PendingAgentAction(
                null,
                null,
                "DELETE",
                eventId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));

        mockMvc.perform(post("/api/agent/confirm")
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": "%s"
                                }
                                """.formatted(pendingAction.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.action").value("DELETE"))
                .andExpect(jsonPath("$.content").value(containsString("标题：待删除会议")))
                .andExpect(jsonPath("$.content").value(containsString("时间：2030-03-04 15:00-16:00")))
                .andExpect(jsonPath("$.content").value(not(containsString("#" + eventId))));

        mockMvc.perform(post("/api/agent/confirm")
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": "%s"
                                }
                                """.formatted(pendingAction.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void shouldConfirmPendingCreateAction() throws Exception {
        TestAuthHelper.TestUser user = TestAuthHelper.registerUser(mockMvc, objectMapper, "ag_create");
        Long userId = userRepository.findByUsername(user.username()).orElseThrow().getId();
        PendingAgentAction pendingAction = confirmationStore.save(userId, new PendingAgentAction(
                null,
                null,
                "CREATE",
                null,
                "背单词",
                "2030-04-05",
                "15:00",
                null,
                null,
                null,
                "学习",
                null
        ));

        mockMvc.perform(post("/api/agent/confirm")
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": "%s"
                                }
                                """.formatted(pendingAction.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.action").value("CREATE"))
                .andExpect(jsonPath("$.content").value(containsString("标题：背单词")))
                .andExpect(jsonPath("$.content").value(containsString("时间：2030-04-05 15:00")));

        mockMvc.perform(get("/api/events")
                        .header("Authorization", "Bearer " + user.token())
                        .param("date", "2030-04-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("背单词"));
    }

    @Test
    void shouldCancelPendingAction() throws Exception {
        TestAuthHelper.TestUser user = TestAuthHelper.registerUser(mockMvc, objectMapper, "ag_cancel");
        Long userId = userRepository.findByUsername(user.username()).orElseThrow().getId();
        PendingAgentAction pendingAction = confirmationStore.save(userId, new PendingAgentAction(
                null,
                null,
                "CREATE",
                null,
                "背单词",
                "2030-04-05",
                "15:00",
                null,
                null,
                null,
                "学习",
                null
        ));

        mockMvc.perform(post("/api/agent/cancel")
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": "%s"
                                }
                                """.formatted(pendingAction.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.content").value("已取消执行。"));

        mockMvc.perform(post("/api/agent/confirm")
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": "%s"
                                }
                                """.formatted(pendingAction.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void shouldConfirmPendingQueryAction() throws Exception {
        TestAuthHelper.TestUser user = TestAuthHelper.registerUser(mockMvc, objectMapper, "ag_query");
        mockMvc.perform(post("/api/events")
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "查询测试日程",
                                  "startTime": "2030-04-06T09:00:00"
                                }
                                """))
                .andExpect(status().isCreated());

        Long userId = userRepository.findByUsername(user.username()).orElseThrow().getId();
        PendingAgentAction pendingAction = confirmationStore.save(userId, new PendingAgentAction(
                null,
                null,
                "QUERY",
                null,
                null,
                "2030-04-06",
                null,
                null,
                null,
                null,
                null,
                null
        ));

        mockMvc.perform(post("/api/agent/confirm")
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": "%s"
                                }
                                """.formatted(pendingAction.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.action").value("QUERY"))
                .andExpect(jsonPath("$.content").value(containsString("查询测试日程")))
                .andExpect(jsonPath("$.candidates", hasSize(1)));
    }

    @Test
    void shouldConfirmPendingRecurringCreateAction() throws Exception {
        TestAuthHelper.TestUser user = TestAuthHelper.registerUser(mockMvc, objectMapper, "ag_recur");
        Long userId = userRepository.findByUsername(user.username()).orElseThrow().getId();
        PendingRecurringAgentAction pendingAction = confirmationStore.saveRecurring(userId, new PendingRecurringAgentAction(
                null,
                null,
                "CREATE_RECURRING",
                null,
                "背单词",
                "2030-06-01",
                "2030-06-03",
                "20:00",
                null,
                "DAILY",
                1,
                List.of(),
                null,
                null,
                "学习",
                null
        ));

        mockMvc.perform(post("/api/agent/confirm")
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": "%s"
                                }
                                """.formatted(pendingAction.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.action").value("CREATE_RECURRING"))
                .andExpect(jsonPath("$.content").value(containsString("已添加重复日程")))
                .andExpect(jsonPath("$.content").value(containsString("标题：背单词")));

        mockMvc.perform(get("/api/events")
                        .header("Authorization", "Bearer " + user.token())
                        .param("date", "2030-06-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sourceType").value("RECURRING"))
                .andExpect(jsonPath("$[0].title").value("背单词"));
    }

    @Test
    void shouldConfirmPendingRecurringDeleteAction() throws Exception {
        TestAuthHelper.TestUser user = TestAuthHelper.registerUser(mockMvc, objectMapper, "ag_recur_del");
        String createdJson = mockMvc.perform(post("/api/recurring-events")
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "背单词",
                                  "startDate": "2030-07-01",
                                  "endDate": "2030-07-07",
                                  "startTime": "20:00",
                                  "recurrenceType": "DAILY",
                                  "tag": "学习"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long recurringEventId = objectMapper.readTree(createdJson).get("id").asLong();
        Long userId = userRepository.findByUsername(user.username()).orElseThrow().getId();
        PendingRecurringAgentAction pendingAction = confirmationStore.saveRecurring(userId, new PendingRecurringAgentAction(
                null,
                null,
                "DELETE_RECURRING",
                recurringEventId,
                "背单词",
                "2030-07-01",
                "2030-07-07",
                "20:00",
                null,
                "DAILY",
                1,
                List.of(),
                null,
                null,
                "学习",
                null
        ));

        mockMvc.perform(post("/api/agent/confirm")
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": "%s"
                                }
                                """.formatted(pendingAction.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.action").value("DELETE_RECURRING"))
                .andExpect(jsonPath("$.content").value(containsString("已删除重复日程")))
                .andExpect(jsonPath("$.content").value(containsString("标题：背单词")));

        mockMvc.perform(get("/api/events")
                        .header("Authorization", "Bearer " + user.token())
                        .param("date", "2030-07-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void shouldPrepareRecurringDeleteInsteadOfSingleDeleteWhenIntentIsRecurring() throws Exception {
        TestAuthHelper.TestUser user = TestAuthHelper.registerUser(mockMvc, objectMapper, "ag_recur_intent_del");
        mockMvc.perform(post("/api/events")
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "背单词",
                                  "startTime": "2030-08-03T20:00:00",
                                  "tag": "学习"
                                }
                                """))
                .andExpect(status().isCreated());
        String recurringJson = mockMvc.perform(post("/api/recurring-events")
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "背单词",
                                  "startDate": "2030-08-01",
                                  "endDate": "2030-08-07",
                                  "startTime": "20:00",
                                  "recurrenceType": "DAILY",
                                  "tag": "学习"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long recurringEventId = objectMapper.readTree(recurringJson).get("id").asLong();
        Long userId = userRepository.findByUsername(user.username()).orElseThrow().getId();

        CalendarAgentIntent intent = new CalendarAgentIntent(
                "DELETE",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "背单词",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                "DAILY",
                "2030-08-01",
                "2030-08-07",
                1,
                List.of(),
                0.95,
                "用户要删除本周每天背单词"
        );

        AgentActionResult result = ReflectionTestUtils.invokeMethod(
                agentService,
                "executeReviewedAction",
                1,
                intent,
                "review",
                userId,
                0.95
        );

        assertThat(result).isNotNull();
        assertThat(result.action()).isEqualTo("DELETE_RECURRING");
        assertThat(result.needsConfirmation()).isTrue();
        assertThat(result.pendingAction()).isNull();
        assertThat(result.pendingRecurringAction()).isNotNull();
        assertThat(result.pendingRecurringAction().recurringEventId()).isEqualTo(recurringEventId);
    }

    @Test
    void shouldForceRecurringIntentWhenModelMissesRecurringFlagForSingleRecurringRequest() {
        CalendarAgentIntent missedIntent = new CalendarAgentIntent(
                "DELETE",
                null,
                "2030-08-03",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "背单词",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                List.of(),
                0.95,
                "用户要删除背单词"
        );
        CalendarAgentPlan plan = new CalendarAgentPlan(List.of(missedIntent), "用户要删除日程", 0.95);

        CalendarAgentPlan guardedPlan = ReflectionTestUtils.invokeMethod(
                agentService,
                "applyRecurringKeywordGuard",
                "删除本周每天背单词",
                plan
        );

        assertThat(guardedPlan).isNotNull();
        assertThat(guardedPlan.actions()).hasSize(1);
        assertThat(guardedPlan.actions().getFirst().recurring()).isTrue();
        assertThat(guardedPlan.actions().getFirst().recurrenceType()).isEqualTo("DAILY");
    }
}
