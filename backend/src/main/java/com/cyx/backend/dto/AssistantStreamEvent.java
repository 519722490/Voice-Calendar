package com.cyx.backend.dto;

public record AssistantStreamEvent(
        String type,
        String content,
        boolean refreshEvents
) {
    public static AssistantStreamEvent delta(String content) {
        return new AssistantStreamEvent("delta", content, false);
    }

    public static AssistantStreamEvent done(boolean refreshEvents) {
        return new AssistantStreamEvent("done", "", refreshEvents);
    }

    public static AssistantStreamEvent error(String content) {
        return new AssistantStreamEvent("error", content, false);
    }
}
