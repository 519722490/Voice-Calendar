package com.cyx.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record EventRequest(
        @NotBlank(message = "日程标题不能为空")
        String title,

        @NotNull(message = "开始时间不能为空")
        LocalDateTime startTime,

        LocalDateTime endTime,
        String location,
        String description,
        String tag,
        LocalDateTime reminderTime
) {
}
