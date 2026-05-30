package com.cyx.backend.tool;

import com.cyx.backend.dto.CalendarEvent;
import com.cyx.backend.dto.CalendarToolResult;
import com.cyx.backend.dto.EventRequest;
import com.cyx.backend.service.CalendarEventService;
import com.cyx.backend.service.CurrentUserService;
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
    private final CurrentUserService currentUserService;

    public CalendarEventTools(CalendarEventService eventService, CurrentUserService currentUserService) {
        this.eventService = eventService;
        this.currentUserService = currentUserService;
    }

    @Tool(description = "创建新的日程。date 必须是 yyyy-MM-dd，startTime/endTime/reminderTime 必须是 HH:mm。只要用户表达了日程动作或内容，就可以把该动作或内容作为标题，例如“今天下午三点开会”的标题是“开会”。缺少日期或开始时间时不要调用。")
    public CalendarToolResult createCalendarEvent(
            @ToolParam(description = "日程标题，例如：项目评审") String title,
            @ToolParam(description = "日程日期，格式 yyyy-MM-dd，例如：2026-05-29") String date,
            @ToolParam(description = "开始时间，格式 HH:mm，例如：14:30") String startTime,
            @ToolParam(description = "结束时间，格式 HH:mm，可选", required = false) String endTime,
            @ToolParam(description = "地点，可选", required = false) String location,
            @ToolParam(description = "备注，可选", required = false) String description,
            @ToolParam(description = "标签，必须从固定值中选择：会议、工作、学习、生活、运动、出行、提醒、其他；识别不出来用“其他”。", required = false) String tag,
            @ToolParam(description = "提醒时间，格式 HH:mm，可选", required = false) String reminderTime
    ) {
        CalendarEvent event = eventService.createEvent(currentUserService.requireCurrentUserId(), toRequest(
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
        return eventService.findEvents(currentUserService.requireCurrentUserId(), parsedDate);
    }

    @Tool(description = "根据日程 id 查询单个日程详情。仅在系统内部已经有明确 id 时使用；不要要求用户提供 id，也不要在回复用户时展示 id。")
    public CalendarEvent getCalendarEvent(
            @ToolParam(description = "日程 id") Long id
    ) {
        return eventService.getEvent(currentUserService.requireCurrentUserId(), id);
    }

    @Tool(description = "修改已有日程。只有在用户提供的标题、日期、时间等信息能唯一定位目标日程，或系统内部已有明确 id 时才调用；用户只说“刚刚的、最近的、上一个、它、那个”等模糊引用时不要调用。未传的字段会保留原值。date 必须是 yyyy-MM-dd，时间必须是 HH:mm。回复用户时不要展示 id。")
    public CalendarToolResult updateCalendarEvent(
            @ToolParam(description = "要修改的日程 id") Long id,
            @ToolParam(description = "新标题，可选", required = false) String title,
            @ToolParam(description = "新日期，格式 yyyy-MM-dd，可选", required = false) String date,
            @ToolParam(description = "新开始时间，格式 HH:mm，可选", required = false) String startTime,
            @ToolParam(description = "新结束时间，格式 HH:mm，可选", required = false) String endTime,
            @ToolParam(description = "新地点，可选", required = false) String location,
            @ToolParam(description = "新备注，可选", required = false) String description,
            @ToolParam(description = "新标签，可选，必须从固定值中选择：会议、工作、学习、生活、运动、出行、提醒、其他；识别不出来用“其他”。", required = false) String tag,
            @ToolParam(description = "新提醒时间，格式 HH:mm，可选", required = false) String reminderTime
    ) {
        Long userId = currentUserService.requireCurrentUserId();
        CalendarEvent existing = eventService.getEvent(userId, id);
        LocalDate resolvedDate = isBlank(date) ? existing.startTime().toLocalDate() : LocalDate.parse(date);
        String resolvedStartTime = isBlank(startTime) ? existing.startTime().toLocalTime().toString() : startTime;
        String resolvedEndTime = isBlank(endTime)
                ? existing.endTime() == null ? null : existing.endTime().toLocalTime().toString()
                : endTime;
        String resolvedReminderTime = isBlank(reminderTime)
                ? existing.reminderTime() == null ? null : existing.reminderTime().toLocalTime().toString()
                : reminderTime;

        CalendarEvent updated = eventService.updateEvent(userId, id, toRequest(
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

    @Tool(description = "删除指定 id 的日程。只有在用户提供的标题、日期、时间等信息能唯一定位目标日程，或系统内部已有明确 id 时才调用；用户只说“刚刚的、最近的、上一个、它、那个”等模糊引用时不要调用。回复用户时不要展示 id。")
    public CalendarToolResult deleteCalendarEvent(
            @ToolParam(description = "要删除的日程 id") Long id
    ) {
        eventService.deleteEvent(currentUserService.requireCurrentUserId(), id);
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
