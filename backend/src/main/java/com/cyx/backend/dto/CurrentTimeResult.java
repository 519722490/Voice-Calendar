package com.cyx.backend.dto;

public record CurrentTimeResult(
        String currentDateTime,
        String currentDate,
        String zoneId
) {
}
