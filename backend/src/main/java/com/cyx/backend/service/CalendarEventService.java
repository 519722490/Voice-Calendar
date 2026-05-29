package com.cyx.backend.service;

import com.cyx.backend.dto.CalendarEvent;
import com.cyx.backend.dto.EventRequest;
import com.cyx.backend.entity.CalendarEventEntity;
import com.cyx.backend.exception.EventNotFoundException;
import com.cyx.backend.repository.CalendarEventJpaRepository;
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

    @Transactional(readOnly = true)
    public List<CalendarEvent> findEvents(Long userId, LocalDate date) {
        List<CalendarEventEntity> events = date == null
                ? repository.findAllByUserIdOrderByStartTimeAsc(userId)
                : repository.findEventsOnDate(userId, date.atStartOfDay(), date.plusDays(1).atStartOfDay());

        return events.stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CalendarEvent getEvent(Long userId, Long id) {
        return repository.findByIdAndUserId(id, userId)
                .map(this::toResponse)
                .orElseThrow(() -> new EventNotFoundException(id));
    }

    @Transactional
    public CalendarEvent createEvent(Long userId, EventRequest request) {
        validateTimeRange(request.startTime(), request.endTime());
        Instant now = Instant.now();
        CalendarEventEntity event = repository.save(new CalendarEventEntity(
                null,
                userId,
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
    public CalendarEvent updateEvent(Long userId, Long id, EventRequest request) {
        CalendarEventEntity existing = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new EventNotFoundException(id));
        validateTimeRange(request.startTime(), request.endTime());
        CalendarEventEntity event = repository.save(new CalendarEventEntity(
                existing.getId(),
                userId,
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
    public void deleteEvent(Long userId, Long id) {
        if (!repository.existsByIdAndUserId(id, userId)) {
            throw new EventNotFoundException(id);
        }
        repository.deleteByIdAndUserId(id, userId);
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
