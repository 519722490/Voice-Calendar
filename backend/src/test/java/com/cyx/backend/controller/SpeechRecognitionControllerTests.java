package com.cyx.backend.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SpeechRecognitionControllerTests {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldReturnSpeechRecognitionConfigWithoutApiKey() throws Exception {
        mockMvc.perform(get("/api/speech/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("dashscope"))
                .andExpect(jsonPath("$.model").value("fun-asr-realtime"))
                .andExpect(jsonPath("$.endpoint").value("wss://dashscope.aliyuncs.com/api-ws/v1/inference"))
                .andExpect(jsonPath("$.sampleRate").value(16000))
                .andExpect(jsonPath("$.format").value("pcm"))
                .andExpect(jsonPath("$.apiKeyConfigured").isBoolean());
    }
}
