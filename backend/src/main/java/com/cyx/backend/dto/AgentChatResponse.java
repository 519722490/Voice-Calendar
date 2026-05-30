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
        PendingAgentAction pendingAction,
        boolean batch,
        List<AgentActionResult> results
) {
    public AgentChatResponse {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        results = results == null ? List.of() : List.copyOf(results);
    }

    public AgentChatResponse(String content, boolean aiEnabled) {
        this(content, aiEnabled, aiEnabled, "auto", "NONE", false, null, List.of(), null, false, List.of());
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
                null,
                false,
                List.of()
        );
    }

    public static AgentChatResponse done(
            String content,
            String mode,
            String action,
            CalendarEvent event,
            List<CalendarEvent> candidates
    ) {
        return new AgentChatResponse(content, true, true, mode, action, false, event, candidates, null, false, List.of());
    }

    public static AgentChatResponse failed(String content, String mode, String action, List<CalendarEvent> candidates) {
        return new AgentChatResponse(content, true, false, mode, action, false, null, candidates, null, false, List.of());
    }

    public static AgentChatResponse confirmation(
            String content,
            String action,
            List<CalendarEvent> candidates,
            PendingAgentAction pendingAction
    ) {
        return new AgentChatResponse(content, true, false, "review", action, true, null, candidates, pendingAction, false, List.of());
    }

    public static AgentChatResponse batch(String content, String mode, String action, boolean success, List<AgentActionResult> results) {
        List<AgentActionResult> batchResults = results == null ? List.of() : List.copyOf(results);
        List<CalendarEvent> candidates = batchResults.size() == 1 ? batchResults.getFirst().candidates() : List.of();
        CalendarEvent event = batchResults.size() == 1 ? batchResults.getFirst().event() : null;
        PendingAgentAction pendingAction = batchResults.stream()
                .map(AgentActionResult::pendingAction)
                .filter(actionItem -> actionItem != null)
                .findFirst()
                .orElse(null);
        boolean needsConfirmation = pendingAction != null;

        return new AgentChatResponse(
                content,
                true,
                success,
                mode,
                action,
                needsConfirmation,
                event,
                candidates,
                pendingAction,
                batchResults.size() > 1,
                batchResults
        );
    }
}
