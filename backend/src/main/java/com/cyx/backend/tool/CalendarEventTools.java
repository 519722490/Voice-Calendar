package com.cyx.backend.tool;

import com.cyx.backend.dto.CalendarEvent;
import com.cyx.backend.dto.CalendarToolResult;
import com.cyx.backend.dto.EventRequest;
import com.cyx.backend.dto.RecurringEventRequest;
import com.cyx.backend.dto.RecurringEventResponse;
import com.cyx.backend.service.CalendarEventService;
import com.cyx.backend.service.CurrentUserService;
import com.cyx.backend.service.RecurringEventService;
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
    private final RecurringEventService recurringEventService;
    private final CurrentUserService currentUserService;

    public CalendarEventTools(
            CalendarEventService eventService,
            RecurringEventService recurringEventService,
            CurrentUserService currentUserService
    ) {
        this.eventService = eventService;
        this.recurringEventService = recurringEventService;
        this.currentUserService = currentUserService;
    }

    @Tool(description = "创建新的单次日程。date 必须是 yyyy-MM-dd，startTime/endTime/reminderTime 必须是 HH:mm。只要用户表达了日程动作或内容，就可以把该动作或内容作为标题，例如“今天下午三点开会”的标题是“开会”。缺少日期或开始时间时不要调用。用户说每天、每周、每月、工作日、本周每天、今年每天、每周一三五等周期表达时不要调用本工具，也不要展开成多条普通日程。")
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

    @Tool(description = "创建重复日程规则。只用于每天、每周、每月、工作日、本周每天、今年每天、每周一三五等周期表达；不要把周期日程展开成多个普通日程。startDate/endDate 必须是 yyyy-MM-dd，startTime/endTime/reminderTime 必须是 HH:mm，endDate 必填且不允许无限重复。自动模式第一版遇到周期日程应拒绝执行并提示切换审查模式；如果仍调用本工具，后端会强校验日期范围。")
    public RecurringEventResponse createRecurringEvent(
            @ToolParam(description = "重复日程标题，例如：背单词") String title,
            @ToolParam(description = "重复开始日期，格式 yyyy-MM-dd") String startDate,
            @ToolParam(description = "重复结束日期，格式 yyyy-MM-dd，必填") String endDate,
            @ToolParam(description = "每次开始时间，格式 HH:mm") String startTime,
            @ToolParam(description = "每次结束时间，格式 HH:mm，可选", required = false) String endTime,
            @ToolParam(description = "重复类型：DAILY、WEEKLY、MONTHLY") String recurrenceType,
            @ToolParam(description = "重复间隔，例如每天为 1，每两天为 2，可选，默认 1", required = false) Integer intervalValue,
            @ToolParam(description = "每周重复的星期集合，例如 MON,WED,FRI；WEEKLY 时可选", required = false) String daysOfWeek,
            @ToolParam(description = "地点，可选", required = false) String location,
            @ToolParam(description = "备注，可选", required = false) String description,
            @ToolParam(description = "标签，必须从固定值中选择：会议、工作、学习、生活、运动、出行、提醒、其他；识别不出来用“其他”。", required = false) String tag,
            @ToolParam(description = "提醒时间，格式 HH:mm，可选", required = false) String reminderTime
    ) {
        return recurringEventService.createEvent(currentUserService.requireCurrentUserId(), new RecurringEventRequest(
                title,
                LocalDate.parse(startDate),
                LocalDate.parse(endDate),
                LocalTime.parse(startTime),
                isBlank(endTime) ? null : LocalTime.parse(endTime),
                recurrenceType,
                intervalValue,
                daysOfWeek,
                location,
                description,
                tag,
                isBlank(reminderTime) ? null : LocalTime.parse(reminderTime)
        ));
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

    @Tool(description = "删除或取消指定 id 的单次日程。用户说删除、取消、撤销、删掉、移除、不去了、不参加、作废、不再、不用、不 + 日程内容 + 了都表示删除意图，例如“今天下午不背单词了”。只有在用户提供的标题、日期、时间等信息能唯一定位目标单次日程，或系统内部已有明确 id 时才调用；用户说每天、每周、每月、工作日、本周每天、今年每天、每周一三五等周期表达时不要调用本工具，也不要把周期删除当成今天的单次删除；用户只说“刚刚的、最近的、上一个、它、那个”等模糊引用时不要调用。回复用户时不要展示 id。")
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
