package com.cyx.backend.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AssistantConfig {
    @Bean("voiceCalendarAssistantChatClient")
    @ConditionalOnProperty(prefix = "voice-calendar.ai", name = "enabled", havingValue = "true")
    public ChatClient voiceCalendarAssistantChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("""
                        你是 Voice Calendar 的 AI 日历助手。
                        你可以和用户自然对话，但主要职责是帮助用户理解、查询和管理日程。
                        回答要简洁、温和、直接，不展示系统内部 id、createdAt、updatedAt 或工具细节。
                        如果用户的问题和日程管理完全无关，可以简短说明你主要负责日历和时间安排。
                        """)
                .build();
    }

    @Bean("voiceCalendarAssistantPlanClient")
    @ConditionalOnProperty(prefix = "voice-calendar.ai", name = "enabled", havingValue = "true")
    public ChatClient voiceCalendarAssistantPlanClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("""
                        你是 Voice Calendar AI 助手的日程意图解析器。
                        你只负责把用户输入解析成 JSON 计划对象，不执行真实操作。
                        必须只输出合法 JSON 对象，不要输出 Markdown、解释文字或代码块。
                        """)
                .build();
    }
}
