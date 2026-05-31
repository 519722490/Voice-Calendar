package com.cyx.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record AssistantChatRequest(
        @NotBlank(message = "会话 id 不能为空")
        String conversationId,

        @NotBlank(message = "消息内容不能为空")
        String message
) {
}
