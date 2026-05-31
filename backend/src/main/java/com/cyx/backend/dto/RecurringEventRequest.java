package com.cyx.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;

public record RecurringEventRequest(
        @NotBlank(message = "重复日程标题不能为空")
        String title,

        @NotNull(message = "重复开始日期不能为空")
        LocalDate startDate,

        @NotNull(message = "重复结束日期不能为空")
        LocalDate endDate,

        @NotNull(message = "开始时间不能为空")
        LocalTime startTime,

        LocalTime endTime,

        @NotBlank(message = "重复类型不能为空")
        String recurrenceType,

        Integer intervalValue,
        String daysOfWeek,
        String location,
        String description,
        String tag,
        LocalTime reminderTime
) {
}
