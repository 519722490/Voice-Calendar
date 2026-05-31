package com.cyx.backend.dto;

import java.time.Instant;
import java.util.List;

public record PendingRecurringAgentAction(
        String id,
        Instant expiresAt,
        String action,
        Long recurringEventId,
        String title,
        String startDate,
        String endDate,
        String startTime,
        String endTime,
        String recurrenceType,
        Integer intervalValue,
        List<String> daysOfWeek,
        String location,
        String description,
        String tag,
        String reminderTime
) {
    public PendingRecurringAgentAction {
        daysOfWeek = daysOfWeek == null ? List.of() : List.copyOf(daysOfWeek);
    }
}
