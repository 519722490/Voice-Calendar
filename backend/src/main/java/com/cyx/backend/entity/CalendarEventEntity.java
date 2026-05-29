package com.cyx.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "calendar_events",
        indexes = @Index(name = "idx_calendar_events_user_start_time", columnList = "user_id,startTime")
)
public class CalendarEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false)
    private LocalDateTime startTime;

    private LocalDateTime endTime;

    @Column(length = 120)
    private String location;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false, length = 40)
    private String tag;

    private LocalDateTime reminderTime;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected CalendarEventEntity() {
    }

    public CalendarEventEntity(
            Long id,
            Long userId,
            String title,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String location,
            String description,
            String tag,
            LocalDateTime reminderTime,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.userId = userId;
        this.title = title;
        this.startTime = startTime;
        this.endTime = endTime;
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

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
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

    public LocalDateTime getReminderTime() {
        return reminderTime;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
