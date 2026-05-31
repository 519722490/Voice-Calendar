package com.cyx.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AssistantChatRequest(
        @NotBlank(message = "会话 id 不能为空")
        String conversationId,

        @NotBlank(message = "消息内容不能为空")
        @Size(max = 50, message = "消息最多 50 个字")
        String message
) {
}
