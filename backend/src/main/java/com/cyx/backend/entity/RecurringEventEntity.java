package com.cyx.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(
        name = "recurring_events",
        indexes = @Index(name = "idx_recurring_events_user_range", columnList = "user_id,startDate,endDate")
)
public class RecurringEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false)
    private LocalTime startTime;

    private LocalTime endTime;

    @Column(nullable = false, length = 20)
    private String recurrenceType;

    @Column(nullable = false)
    private Integer intervalValue;

    @Column(length = 80)
    private String daysOfWeek;

    @Column(length = 120)
    private String location;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false, length = 40)
    private String tag;

    private LocalTime reminderTime;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected RecurringEventEntity() {
    }

    public RecurringEventEntity(
            Long id,
            Long userId,
            String title,
            LocalDate startDate,
            LocalDate endDate,
            LocalTime startTime,
            LocalTime endTime,
            String recurrenceType,
            Integer intervalValue,
            String daysOfWeek,
            String location,
            String description,
            String tag,
            LocalTime reminderTime,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.userId = userId;
        this.title = title;
        this.startDate = startDate;
        this.endDate = endDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.recurrenceType = recurrenceType;
        this.intervalValue = intervalValue;
        this.daysOfWeek = daysOfWeek;
        this.location = location;
        this.description = description;
        this.tag = tag;
        this.reminderTime = reminderTime;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTitle() {
        return title;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public String getRecurrenceType() {
        return recurrenceType;
    }

    public Integer getIntervalValue() {
        return intervalValue;
    }

    public String getDaysOfWeek() {
        return daysOfWeek;
    }

    public String getLocation() {
        return location;
    }

    public String getDescription() {
        return description;
    }

    public String getTag() {
        return tag;
    }

    public LocalTime getReminderTime() {
        return reminderTime;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
