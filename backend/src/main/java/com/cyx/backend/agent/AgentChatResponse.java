package com.cyx.backend.agent;

public record AgentChatResponse(
        String content,
        boolean aiEnabled
) {
}
