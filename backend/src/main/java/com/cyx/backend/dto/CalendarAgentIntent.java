package com.cyx.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CalendarAgentIntent(
        String action,
        String title,
        String date,
        String startTime,
        String endTime,
        String location,
        String description,
        String tag,
        String reminderTime,
        Long targetId,
        String targetTitleKeyword,
        String targetStartTime,
        String targetStartTimeFrom,
        String targetStartTimeTo,
        String newTitle,
        String newDate,
        String newStartTime,
        String newEndTime,
        String newLocation,
        String newDescription,
        String newTag,
        String newReminderTime,
        Double confidence,
        String reason
) {
}
