package com.cyx.backend.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AgentService {
    private final ObjectProvider<ChatClient> chatClientProvider;
    private final boolean aiEnabled;

    public AgentService(
            ObjectProvider<ChatClient> chatClientProvider,
            @Value("${voice-calendar.ai.enabled:false}") boolean aiEnabled
    ) {
        this.chatClientProvider = chatClientProvider;
        this.aiEnabled = aiEnabled;
    }

    public AgentChatResponse chat(AgentChatRequest request) {
        ChatClient chatClient = chatClientProvider.getIfAvailable();

        if (!aiEnabled || chatClient == null) {
            return new AgentChatResponse(
                    "AI Agent 框架已接入，但当前未启用模型。请配置 AI_DASHSCOPE_API_KEY，并设置 VOICE_CALENDAR_AI_ENABLED=true 后再调用。",
                    false
            );
        }

        try {
            String content = chatClient.prompt()
                    .user(request.message())
                    .call()
                    .content();
            return new AgentChatResponse(content, true);
        } catch (Exception exception) {
            return new AgentChatResponse("AI 调用失败：" + exception.getMessage(), true);
        }
    }
}
