package com.cyx.backend.agent.tools;

import com.cyx.backend.event.CalendarEvent;
import com.cyx.backend.event.CalendarEventService;
import com.cyx.backend.event.EventRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class CalendarEventTools {
    private final CalendarEventService eventService;

    public CalendarEventTools(CalendarEventService eventService) {
        this.eventService = eventService;
    }

    @Tool(description = "创建新的日程。date 必须是 yyyy-MM-dd，startTime/endTime/reminderTime 必须是 HH:mm。缺少标题、日期或开始时间时不要调用。")
    public CalendarToolResult createCalendarEvent(
            @ToolParam(description = "日程标题，例如：项目评审") String title,
            @ToolParam(description = "日程日期，格式 yyyy-MM-dd，例如：2026-05-29") String date,
            @ToolParam(description = "开始时间，格式 HH:mm，例如：14:30") String startTime,
            @ToolParam(description = "结束时间，格式 HH:mm，可选", required = false) String endTime,
            @ToolParam(description = "地点，可选", required = false) String location,
            @ToolParam(description = "备注，可选", required = false) String description,
            @ToolParam(description = "标签，可选，例如：会议、学习、生活", required = false) String tag,
            @ToolParam(description = "提醒时间，格式 HH:mm，可选", required = false) String reminderTime
    ) {
        CalendarEvent event = eventService.createEvent(toRequest(
                title,
                date,
                startTime,
                endTime,
                location,
                description,
                tag,
                reminderTime
        ));
        return CalendarToolResult.success("日程创建成功", event);
    }

    @Tool(description = "查询日程。date 为空时返回全部日程；date 不为空时必须是 yyyy-MM-dd。")
    public List<CalendarEvent> listCalendarEvents(
            @ToolParam(description = "查询日期，格式 yyyy-MM-dd，可选", required = false) String date
    ) {
        LocalDate parsedDate = isBlank(date) ? null : LocalDate.parse(date);
        return eventService.findEvents(parsedDate);
    }

    @Tool(description = "根据日程 id 查询单个日程详情。")
    public CalendarEvent getCalendarEvent(
            @ToolParam(description = "日程 id") Long id
    ) {
        return eventService.getEvent(id);
    }

    @Tool(description = "修改已有日程。只在用户明确要修改某个 id 对应的日程时调用；未传的字段会保留原值。date 必须是 yyyy-MM-dd，时间必须是 HH:mm。")
    public CalendarToolResult updateCalendarEvent(
            @ToolParam(description = "要修改的日程 id") Long id,
            @ToolParam(description = "新标题，可选", required = false) String title,
            @ToolParam(description = "新日期，格式 yyyy-MM-dd，可选", required = false) String date,
            @ToolParam(description = "新开始时间，格式 HH:mm，可选", required = false) String startTime,
            @ToolParam(description = "新结束时间，格式 HH:mm，可选", required = false) String endTime,
            @ToolParam(description = "新地点，可选", required = false) String location,
            @ToolParam(description = "新备注，可选", required = false) String description,
            @ToolParam(description = "新标签，可选", required = false) String tag,
            @ToolParam(description = "新提醒时间，格式 HH:mm，可选", required = false) String reminderTime
    ) {
        CalendarEvent existing = eventService.getEvent(id);
        LocalDate resolvedDate = isBlank(date) ? existing.startTime().toLocalDate() : LocalDate.parse(date);
        String resolvedStartTime = isBlank(startTime) ? existing.startTime().toLocalTime().toString() : startTime;
        String resolvedEndTime = isBlank(endTime)
                ? existing.endTime() == null ? null : existing.endTime().toLocalTime().toString()
                : endTime;
        String resolvedReminderTime = isBlank(reminderTime)
                ? existing.reminderTime() == null ? null : existing.reminderTime().toLocalTime().toString()
                : reminderTime;

        CalendarEvent updated = eventService.updateEvent(id, toRequest(
                isBlank(title) ? existing.title() : title,
                resolvedDate.toString(),
                resolvedStartTime,
                resolvedEndTime,
                isBlank(location) ? existing.location() : location,
                isBlank(description) ? existing.description() : description,
                isBlank(tag) ? existing.tag() : tag,
                resolvedReminderTime
        ));
        return CalendarToolResult.success("日程修改成功", updated);
    }

    @Tool(description = "删除指定 id 的日程。删除前必须确认用户已经明确要删除该日程。")
    public CalendarToolResult deleteCalendarEvent(
            @ToolParam(description = "要删除的日程 id") Long id
    ) {
        eventService.deleteEvent(id);
        return CalendarToolResult.success("日程删除成功", null);
    }

    private EventRequest toRequest(
            String title,
            String date,
            String startTime,
            String endTime,
            String location,
            String description,
            String tag,
            String reminderTime
    ) {
        LocalDate parsedDate = LocalDate.parse(date);
        LocalDateTime parsedStartTime = LocalDateTime.of(parsedDate, LocalTime.parse(startTime));
        LocalDateTime parsedEndTime = isBlank(endTime) ? null : LocalDateTime.of(parsedDate, LocalTime.parse(endTime));
        LocalDateTime parsedReminderTime = isBlank(reminderTime)
                ? null
                : LocalDateTime.of(parsedDate, LocalTime.parse(reminderTime));

        return new EventRequest(
                title,
                parsedStartTime,
                parsedEndTime,
                location,
                description,
                tag,
                parsedReminderTime
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
