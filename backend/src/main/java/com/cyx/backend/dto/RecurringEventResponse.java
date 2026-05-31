package com.cyx.backend.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public record RecurringEventResponse(
        Long id,
        String title,
        LocalDate startDate,
        LocalDate endDate,
        LocalTime startTime,
        LocalTime endTime,
        String recurrenceType,
        Integer intervalValue,
        String daysOfWeek,
        String location,
        String description,
        String tag,
        LocalTime reminderTime,
        Instant createdAt,
        Instant updatedAt
) {
}
