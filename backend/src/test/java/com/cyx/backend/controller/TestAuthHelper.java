package com.cyx.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

final class TestAuthHelper {
    private TestAuthHelper() {
    }

    static TestUser registerUser(MockMvc mockMvc, ObjectMapper objectMapper, String usernamePrefix) throws Exception {
        String username = usernamePrefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String json = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", "123456",
                                "displayName", "测试用户"
                        ))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String token = objectMapper.readTree(json).path("token").asText();
        return new TestUser(username, token);
    }

    record TestUser(String username, String token) {
    }
}
