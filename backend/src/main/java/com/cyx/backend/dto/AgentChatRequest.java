package com.cyx.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentChatRequest(
        @NotBlank(message = "消息不能为空")
        @Size(max = 50, message = "消息最多 50 个字")
        String message,
        String conversationId,
        String mode
) {
}
