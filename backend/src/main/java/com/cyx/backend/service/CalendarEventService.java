package com.cyx.backend.service;

import com.cyx.backend.dto.CalendarEvent;
import com.cyx.backend.dto.EventRequest;
import com.cyx.backend.entity.CalendarEventEntity;
import com.cyx.backend.event.CalendarEventTag;
import com.cyx.backend.exception.EventNotFoundException;
import com.cyx.backend.repository.CalendarEventJpaRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CalendarEventService {
    private static final long MAX_RANGE_QUERY_DAYS = 370;

    private final CalendarEventJpaRepository repository;
    private final RecurringEventService recurringEventService;

    public CalendarEventService(CalendarEventJpaRepository repository, RecurringEventService recurringEventService) {
        this.repository = repository;
        this.recurringEventService = recurringEventService;
    }

    @Transactional(readOnly = true)
    public List<CalendarEvent> findEvents(Long userId, LocalDate date) {
        List<CalendarEventEntity> events = date == null
                ? repository.findAllByUserIdOrderByStartTimeAsc(userId)
                : repository.findEventsOnDate(userId, date.atStartOfDay(), date.plusDays(1).atStartOfDay());

        List<CalendarEvent> singleEvents = events.stream()
                .map(this::toResponse)
                .toList();
        if (date == null) {
            return singleEvents;
        }
        return mergeAndSort(singleEvents, recurringEventService.findInstances(userId, date));
    }

    @Transactional(readOnly = true)
    public List<CalendarEvent> findEvents(Long userId, LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("查询开始日期和结束日期不能为空");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("查询结束日期不能早于开始日期");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_QUERY_DAYS) {
            throw new IllegalArgumentException("查询范围不能超过 " + MAX_RANGE_QUERY_DAYS + " 天");
        }

        List<CalendarEvent> singleEvents = repository
                .findEventsInRange(userId, from.atStartOfDay(), to.plusDays(1).atStartOfDay())
                .stream()
                .map(this::toResponse)
                .toList();
        return mergeAndSort(singleEvents, recurringEventService.findInstances(userId, from, to));
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
                normalizeTag(request.tag()),
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
                normalizeTag(request.tag()),
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

    private String normalizeTag(String tag) {
        return CalendarEventTag.normalize(tag);
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

    private List<CalendarEvent> mergeAndSort(List<CalendarEvent> singleEvents, List<CalendarEvent> recurringInstances) {
        return java.util.stream.Stream.concat(singleEvents.stream(), recurringInstances.stream())
                .sorted(Comparator.comparing(CalendarEvent::startTime))
                .toList();
    }
}
