package com.cyx.backend.repository;

import com.cyx.backend.entity.CalendarEventEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CalendarEventJpaRepository extends JpaRepository<CalendarEventEntity, Long> {
    List<CalendarEventEntity> findAllByUserIdOrderByStartTimeAsc(Long userId);

    Optional<CalendarEventEntity> findByIdAndUserId(Long id, Long userId);

    boolean existsByIdAndUserId(Long id, Long userId);

    void deleteByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);

    List<CalendarEventEntity> findByUserIdIsNull();

    @Query("""
            select event from CalendarEventEntity event
            where event.userId = :userId
            and (
                (
                    event.endTime is null
                    and event.startTime >= :dayStart
                    and event.startTime < :nextDayStart
                ) or (
                    event.endTime is not null
                    and event.startTime < :nextDayStart
                    and event.endTime >= :dayStart
                )
            )
            order by event.startTime asc
            """)
    List<CalendarEventEntity> findEventsOnDate(
            @Param("userId") Long userId,
            @Param("dayStart") LocalDateTime dayStart,
            @Param("nextDayStart") LocalDateTime nextDayStart
    );

    @Query("""
            select event from CalendarEventEntity event
            where event.userId = :userId
            and (
                (
                    event.endTime is null
                    and event.startTime >= :rangeStart
                    and event.startTime < :rangeEndExclusive
                ) or (
                    event.endTime is not null
                    and event.startTime < :rangeEndExclusive
                    and event.endTime >= :rangeStart
                )
            )
            order by event.startTime asc
            """)
    List<CalendarEventEntity> findEventsInRange(
            @Param("userId") Long userId,
            @Param("rangeStart") LocalDateTime rangeStart,
            @Param("rangeEndExclusive") LocalDateTime rangeEndExclusive
    );
}
