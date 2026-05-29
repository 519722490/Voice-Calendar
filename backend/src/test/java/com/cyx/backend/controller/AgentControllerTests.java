package com.cyx.backend.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
}
