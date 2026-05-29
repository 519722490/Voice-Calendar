package com.cyx.backend.service;

import com.cyx.backend.dto.CalendarEvent;
import com.cyx.backend.dto.EventRequest;
import com.cyx.backend.entity.CalendarEventEntity;
import com.cyx.backend.exception.EventNotFoundException;
import com.cyx.backend.repository.CalendarEventJpaRepository;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CalendarEventService {
    private final CalendarEventJpaRepository repository;

    public CalendarEventService(CalendarEventJpaRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    @Transactional
    void seedDemoEvents() {
        if (repository.count() > 0) {
            return;
        }

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

    @Transactional(readOnly = true)
    public List<CalendarEvent> findEvents(LocalDate date) {
        List<CalendarEventEntity> events = date == null
                ? repository.findAllByOrderByStartTimeAsc()
                : repository.findEventsOnDate(date.atStartOfDay(), date.plusDays(1).atStartOfDay());

        return events.stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CalendarEvent getEvent(Long id) {
        return repository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new EventNotFoundException(id));
    }

    @Transactional
    public CalendarEvent createEvent(EventRequest request) {
        validateTimeRange(request.startTime(), request.endTime());
        Instant now = Instant.now();
        CalendarEventEntity event = repository.save(new CalendarEventEntity(
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
        return toResponse(event);
    }

    @Transactional
    public CalendarEvent updateEvent(Long id, EventRequest request) {
        CalendarEventEntity existing = repository.findById(id)
                .orElseThrow(() -> new EventNotFoundException(id));
        validateTimeRange(request.startTime(), request.endTime());
        CalendarEventEntity event = repository.save(new CalendarEventEntity(
                existing.getId(),
                normalizeTitle(request.title()),
                request.startTime(),
                request.endTime(),
                normalizeText(request.location()),
                normalizeText(request.description()),
                defaultTag(request.tag()),
                request.reminderTime(),
                existing.getCreatedAt(),
                Instant.now()
        ));
        return toResponse(event);
    }

    @Transactional
    public void deleteEvent(Long id) {
        if (!repository.existsById(id)) {
            throw new EventNotFoundException(id);
        }
        repository.deleteById(id);
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

    private CalendarEvent toResponse(CalendarEventEntity entity) {
        return new CalendarEvent(
                entity.getId(),
                entity.getTitle(),
                entity.getStartTime(),
                entity.getEndTime(),
                entity.getLocation(),
                entity.getDescription(),
                entity.getTag(),
                entity.getReminderTime(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
