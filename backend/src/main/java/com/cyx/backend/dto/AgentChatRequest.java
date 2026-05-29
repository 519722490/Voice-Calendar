package com.cyx.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record AgentChatRequest(
        @NotBlank(message = "消息不能为空")
        String message,
        String conversationId
) {
}
