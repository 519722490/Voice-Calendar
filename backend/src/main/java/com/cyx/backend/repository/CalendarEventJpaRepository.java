package com.cyx.backend.repository;

import com.cyx.backend.entity.CalendarEventEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CalendarEventJpaRepository extends JpaRepository<CalendarEventEntity, Long> {
    List<CalendarEventEntity> findAllByOrderByStartTimeAsc();

    @Query("""
            select event from CalendarEventEntity event
            where (
                event.endTime is null
                and event.startTime >= :dayStart
                and event.startTime < :nextDayStart
            ) or (
                event.endTime is not null
                and event.startTime < :nextDayStart
                and event.endTime >= :dayStart
            )
            order by event.startTime asc
            """)
    List<CalendarEventEntity> findEventsOnDate(
            @Param("dayStart") LocalDateTime dayStart,
            @Param("nextDayStart") LocalDateTime nextDayStart
    );
}
