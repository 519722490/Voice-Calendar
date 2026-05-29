package com.cyx.backend.dto;

public record AgentChatResponse(
        String content,
        boolean aiEnabled
) {
}
