package com.cyx.backend.dto;

import java.util.List;

public record AgentActionResult(
        int index,
        String action,
        boolean success,
        boolean needsConfirmation,
        String message,
        CalendarEvent event,
        List<CalendarEvent> candidates,
        PendingAgentAction pendingAction
) {
    public AgentActionResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public static AgentActionResult fromResponse(int index, AgentChatResponse response) {
        return new AgentActionResult(
                index,
                response.action(),
                response.success(),
                response.needsConfirmation(),
                response.content(),
                response.event(),
                response.candidates(),
                response.pendingAction()
        );
    }
}
