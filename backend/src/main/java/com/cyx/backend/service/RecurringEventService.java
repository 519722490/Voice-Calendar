package com.cyx.backend.service;

import com.cyx.backend.dto.CalendarEvent;
import com.cyx.backend.dto.RecurringEventRequest;
import com.cyx.backend.dto.RecurringEventResponse;
import com.cyx.backend.entity.RecurringEventEntity;
import com.cyx.backend.event.CalendarEventTag;
import com.cyx.backend.event.RecurrenceType;
import com.cyx.backend.exception.EventNotFoundException;
import com.cyx.backend.repository.RecurringEventRepository;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecurringEventService {
    private static final long MAX_RECURRENCE_DAYS = 366;
    private static final long MAX_INSTANCE_QUERY_DAYS = 370;

    private final RecurringEventRepository repository;

    public RecurringEventService(RecurringEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public RecurringEventResponse createEvent(Long userId, RecurringEventRequest request) {
        RecurrenceType recurrenceType = RecurrenceType.parse(request.recurrenceType());
        int intervalValue = request.intervalValue() == null ? 1 : request.intervalValue();
        String normalizedDaysOfWeek = normalizeDaysOfWeek(request.daysOfWeek(), recurrenceType, request.startDate());
        validateRequest(request, recurrenceType, intervalValue);

        Instant now = Instant.now();
        RecurringEventEntity event = repository.save(new RecurringEventEntity(
                null,
                userId,
                normalizeTitle(request.title()),
                request.startDate(),
                request.endDate(),
                request.startTime(),
                request.endTime(),
                recurrenceType.name(),
                intervalValue,
                normalizedDaysOfWeek,
                normalizeText(request.location()),
                normalizeText(request.description()),
                CalendarEventTag.normalize(request.tag()),
                request.reminderTime(),
                now,
                now
        ));
        return toResponse(event);
    }

    @Transactional(readOnly = true)
    public List<RecurringEventResponse> findEvents(Long userId) {
        return repository.findAllByUserIdOrderByStartDateAscStartTimeAsc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public RecurringEventResponse getEvent(Long userId, Long id) {
        return repository.findByIdAndUserId(id, userId)
                .map(this::toResponse)
                .orElseThrow(() -> new EventNotFoundException(id));
    }

    @Transactional
    public void deleteEvent(Long userId, Long id) {
        if (!repository.existsByIdAndUserId(id, userId)) {
            throw new EventNotFoundException(id);
        }
        repository.deleteByIdAndUserId(id, userId);
    }

    @Transactional(readOnly = true)
    public List<CalendarEvent> findInstances(Long userId, LocalDate date) {
        return findInstances(userId, date, date);
    }

    @Transactional(readOnly = true)
    public List<CalendarEvent> findInstances(Long userId, LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            return List.of();
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("查询结束日期不能早于开始日期");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_INSTANCE_QUERY_DAYS) {
            throw new IllegalArgumentException("重复日程实例查询范围不能超过 " + MAX_INSTANCE_QUERY_DAYS + " 天");
        }

        List<RecurringEventEntity> rules = repository
                .findByUserIdAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartTimeAsc(userId, to, from);

        return rules.stream()
                .flatMap(rule -> expandRule(rule, from, to).stream())
                .sorted((left, right) -> left.startTime().compareTo(right.startTime()))
                .toList();
    }

    private List<CalendarEvent> expandRule(RecurringEventEntity rule, LocalDate from, LocalDate to) {
        LocalDate start = maxDate(from, rule.getStartDate());
        LocalDate end = minDate(to, rule.getEndDate());
        if (end.isBefore(start)) {
            return List.of();
        }

        return start.datesUntil(end.plusDays(1))
                .filter(date -> matches(rule, date))
                .map(date -> toInstance(rule, date))
                .toList();
    }

    private boolean matches(RecurringEventEntity rule, LocalDate date) {
        RecurrenceType recurrenceType = RecurrenceType.parse(rule.getRecurrenceType());
        int intervalValue = rule.getIntervalValue() == null ? 1 : rule.getIntervalValue();

        return switch (recurrenceType) {
            case DAILY -> ChronoUnit.DAYS.between(rule.getStartDate(), date) % intervalValue == 0;
            case WEEKLY -> matchesWeekly(rule, date, intervalValue);
            case MONTHLY -> ChronoUnit.MONTHS.between(
                    rule.getStartDate().withDayOfMonth(1),
                    date.withDayOfMonth(1)
            ) % intervalValue == 0 && rule.getStartDate().getDayOfMonth() == date.getDayOfMonth();
        };
    }

    private boolean matchesWeekly(RecurringEventEntity rule, LocalDate date, int intervalValue) {
        Set<DayOfWeek> daysOfWeek = parseDaysOfWeek(rule.getDaysOfWeek());
        if (!daysOfWeek.contains(date.getDayOfWeek())) {
            return false;
        }
        LocalDate ruleWeekStart = rule.getStartDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate dateWeekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return ChronoUnit.WEEKS.between(ruleWeekStart, dateWeekStart) % intervalValue == 0;
    }

    private CalendarEvent toInstance(RecurringEventEntity rule, LocalDate date) {
        LocalDateTime startTime = LocalDateTime.of(date, rule.getStartTime());
        LocalDateTime endTime = rule.getEndTime() == null ? null : LocalDateTime.of(date, rule.getEndTime());
        LocalDateTime reminderTime = rule.getReminderTime() == null ? null : LocalDateTime.of(date, rule.getReminderTime());
        String instanceKey = "recurring-" + rule.getId() + "-" + date;

        return new CalendarEvent(
                null,
                rule.getTitle(),
                startTime,
                endTime,
                rule.getLocation(),
                rule.getDescription(),
                rule.getTag(),
                reminderTime,
                rule.getCreatedAt(),
                rule.getUpdatedAt(),
                "RECURRING",
                rule.getId(),
                instanceKey
        );
    }

    private void validateRequest(RecurringEventRequest request, RecurrenceType recurrenceType, int intervalValue) {
        if (request.endDate().isBefore(request.startDate())) {
            throw new IllegalArgumentException("重复结束日期不能早于开始日期");
        }
        if (intervalValue < 1) {
            throw new IllegalArgumentException("重复间隔必须大于等于 1");
        }
        if (request.endTime() != null && !request.endTime().isAfter(request.startTime())) {
            throw new IllegalArgumentException("结束时间必须晚于开始时间");
        }
        if (ChronoUnit.DAYS.between(request.startDate(), request.endDate()) > MAX_RECURRENCE_DAYS) {
            throw new IllegalArgumentException("重复日程范围不能超过 " + MAX_RECURRENCE_DAYS + " 天");
        }
        if (recurrenceType == RecurrenceType.MONTHLY) {
            throw new IllegalArgumentException("暂不支持按月重复日程");
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

    private String normalizeDaysOfWeek(String value, RecurrenceType recurrenceType, LocalDate startDate) {
        if (recurrenceType != RecurrenceType.WEEKLY) {
            return null;
        }
        Set<DayOfWeek> daysOfWeek = value == null || value.isBlank()
                ? EnumSet.of(startDate.getDayOfWeek())
                : parseDaysOfWeek(value);
        return daysOfWeek.stream()
                .map(DayOfWeek::name)
                .sorted()
                .collect(Collectors.joining(","));
    }

    private Set<DayOfWeek> parseDaysOfWeek(String value) {
        if (value == null || value.isBlank()) {
            return EnumSet.noneOf(DayOfWeek.class);
        }
        return Arrays.stream(value.split("[,，、\\s]+"))
                .filter(part -> !part.isBlank())
                .map(this::parseDayOfWeek)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
    }

    private DayOfWeek parseDayOfWeek(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "1", "MON", "MONDAY", "周一", "星期一", "礼拜一" -> DayOfWeek.MONDAY;
            case "2", "TUE", "TUESDAY", "周二", "星期二", "礼拜二" -> DayOfWeek.TUESDAY;
            case "3", "WED", "WEDNESDAY", "周三", "星期三", "礼拜三" -> DayOfWeek.WEDNESDAY;
            case "4", "THU", "THURSDAY", "周四", "星期四", "礼拜四" -> DayOfWeek.THURSDAY;
            case "5", "FRI", "FRIDAY", "周五", "星期五", "礼拜五" -> DayOfWeek.FRIDAY;
            case "6", "SAT", "SATURDAY", "周六", "星期六", "礼拜六" -> DayOfWeek.SATURDAY;
            case "7", "SUN", "SUNDAY", "周日", "周天", "星期日", "星期天", "礼拜日", "礼拜天" -> DayOfWeek.SUNDAY;
            default -> throw new IllegalArgumentException("不支持的星期表达：" + value);
        };
    }

    private LocalDate maxDate(LocalDate left, LocalDate right) {
        return left.isAfter(right) ? left : right;
    }

    private LocalDate minDate(LocalDate left, LocalDate right) {
        return left.isBefore(right) ? left : right;
    }

    private RecurringEventResponse toResponse(RecurringEventEntity entity) {
        return new RecurringEventResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getStartDate(),
                entity.getEndDate(),
                entity.getStartTime(),
                entity.getEndTime(),
                entity.getRecurrenceType(),
                entity.getIntervalValue(),
                entity.getDaysOfWeek(),
                entity.getLocation(),
                entity.getDescription(),
                entity.getTag(),
                entity.getReminderTime(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
