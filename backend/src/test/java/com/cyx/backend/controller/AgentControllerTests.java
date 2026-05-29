package com.cyx.backend.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cyx.backend.dto.PendingAgentAction;
import com.cyx.backend.repository.UserRepository;
import com.cyx.backend.service.AgentConfirmationStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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
                .andExpect(jsonPath("$.action").value("DELETE"));

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
}
