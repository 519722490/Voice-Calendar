package com.cyx.backend.dto;

import java.time.Instant;

public record PendingAgentAction(
        String id,
        Instant expiresAt,
        String action,
        Long eventId,
        String title,
        String date,
        String startTime,
        String endTime,
        String location,
        String description,
        String tag,
        String reminderTime
) {
}
