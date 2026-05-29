package com.cyx.backend.agent.tools;

public record CurrentTimeResult(
        String currentDateTime,
        String currentDate,
        String zoneId
) {
}
