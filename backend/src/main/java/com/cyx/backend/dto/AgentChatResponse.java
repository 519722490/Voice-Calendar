package com.cyx.backend.dto;

import java.util.List;

public record AgentChatResponse(
        String content,
        boolean aiEnabled,
        boolean success,
        String mode,
        String action,
        boolean needsConfirmation,
        CalendarEvent event,
        List<CalendarEvent> candidates,
        PendingAgentAction pendingAction
) {
    public AgentChatResponse {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public AgentChatResponse(String content, boolean aiEnabled) {
        this(content, aiEnabled, aiEnabled, "auto", "NONE", false, null, List.of(), null);
    }

    public static AgentChatResponse disabled(String mode) {
        return new AgentChatResponse(
                "AI Agent 框架已接入，但当前未启用模型。请配置 AI_DASHSCOPE_API_KEY，并设置 VOICE_CALENDAR_AI_ENABLED=true 后再调用。",
                false,
                false,
                mode,
                "NONE",
                false,
                null,
                List.of(),
                null
        );
    }

    public static AgentChatResponse done(
            String content,
            String mode,
            String action,
            CalendarEvent event,
            List<CalendarEvent> candidates
    ) {
        return new AgentChatResponse(content, true, true, mode, action, false, event, candidates, null);
    }

    public static AgentChatResponse failed(String content, String mode, String action, List<CalendarEvent> candidates) {
        return new AgentChatResponse(content, true, false, mode, action, false, null, candidates, null);
    }

    public static AgentChatResponse confirmation(
            String content,
            String action,
            List<CalendarEvent> candidates,
            PendingAgentAction pendingAction
    ) {
        return new AgentChatResponse(content, true, false, "review", action, true, null, candidates, pendingAction);
    }
}
