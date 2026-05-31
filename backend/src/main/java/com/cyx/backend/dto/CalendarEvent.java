package com.cyx.backend.dto;

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
        Instant updatedAt,
        String sourceType,
        Long recurringEventId,
        String instanceKey
) {
    public CalendarEvent(
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
        this(
                id,
                title,
                startTime,
                endTime,
                location,
                description,
                tag,
                reminderTime,
                createdAt,
                updatedAt,
                "SINGLE",
                null,
                id == null ? null : "event-" + id
        );
    }
}
