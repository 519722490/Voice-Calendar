package com.cyx.backend.event;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CalendarEventService {
    private final CalendarEventRepository repository;

    public CalendarEventService(CalendarEventRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void seedDemoEvents() {
        LocalDate today = LocalDate.now();
        createEvent(new EventRequest(
                "整理今日日程",
                today.atTime(9, 0),
                today.atTime(9, 30),
                "语音日历原型",
                "确认今天要完成的功能和接口",
                "计划",
                today.atTime(8, 50)
        ));
        createEvent(new EventRequest(
                "项目功能评审",
                today.atTime(14, 30),
                today.atTime(15, 30),
                "线上会议",
                "讨论日程 CRUD 和前端接入方式",
                "会议",
                today.atTime(14, 10)
        ));
        createEvent(new EventRequest(
                "准备演示脚本",
                today.plusDays(2).atTime(16, 0),
                today.plusDays(2).atTime(17, 0),
                "答辩材料",
                "梳理语音创建和查询日程的演示路径",
                "演示",
                null
        ));
    }

    public List<CalendarEvent> findEvents(LocalDate date) {
        return repository.findAll().stream()
                .filter(event -> date == null || occursOn(event, date))
                .sorted(Comparator.comparing(CalendarEvent::startTime))
                .toList();
    }

    public CalendarEvent getEvent(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new EventNotFoundException(id));
    }

    public CalendarEvent createEvent(EventRequest request) {
        validateTimeRange(request.startTime(), request.endTime());
        Instant now = Instant.now();
        return repository.save(new CalendarEvent(
                null,
                normalizeTitle(request.title()),
                request.startTime(),
                request.endTime(),
                normalizeText(request.location()),
                normalizeText(request.description()),
                defaultTag(request.tag()),
                request.reminderTime(),
                now,
                now
        ));
    }

    public CalendarEvent updateEvent(Long id, EventRequest request) {
        CalendarEvent existing = getEvent(id);
        validateTimeRange(request.startTime(), request.endTime());
        return repository.save(new CalendarEvent(
                existing.id(),
                normalizeTitle(request.title()),
                request.startTime(),
                request.endTime(),
                normalizeText(request.location()),
                normalizeText(request.description()),
                defaultTag(request.tag()),
                request.reminderTime(),
                existing.createdAt(),
                Instant.now()
        ));
    }

    public void deleteEvent(Long id) {
        if (!repository.deleteById(id)) {
            throw new EventNotFoundException(id);
        }
    }

    private boolean occursOn(CalendarEvent event, LocalDate date) {
        LocalDate startDate = event.startTime().toLocalDate();
        LocalDate endDate = event.endTime() == null ? startDate : event.endTime().toLocalDate();
        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }

    private void validateTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        if (endTime != null && !endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("结束时间必须晚于开始时间");
        }
    }

    private String normalizeTitle(String title) {
        return title.trim();
    }

    private String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String defaultTag(String tag) {
        String normalized = normalizeText(tag);
        return normalized == null ? "日程" : normalized;
    }
}
