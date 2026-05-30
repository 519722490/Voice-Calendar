package com.cyx.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CalendarAgentPlan(
        List<CalendarAgentIntent> actions,
        String summary,
        Double confidence
) {
    public CalendarAgentPlan {
        actions = actions == null ? List.of() : actions.stream()
                .filter(action -> action != null)
                .toList();
    }
}
