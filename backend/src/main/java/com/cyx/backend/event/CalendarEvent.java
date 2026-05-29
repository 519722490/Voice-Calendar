package com.cyx.backend.event;

import java.time.Instant;
import java.time.LocalDateTime;

public record CalendarEvent(
        Long id,
        String title,
        LocalDateTime startTime,
        LocalDateTime endTime,
        String location,
        String description,
        String tag,
        LocalDateTime reminderTime,
        Instant createdAt,
        Instant updatedAt
) {
}
