package com.cyx.backend.repository;

import com.cyx.backend.entity.RecurringEventEntity;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecurringEventRepository extends JpaRepository<RecurringEventEntity, Long> {
    List<RecurringEventEntity> findAllByUserIdOrderByStartDateAscStartTimeAsc(Long userId);

    Optional<RecurringEventEntity> findByIdAndUserId(Long id, Long userId);

    boolean existsByIdAndUserId(Long id, Long userId);

    void deleteByIdAndUserId(Long id, Long userId);

    List<RecurringEventEntity> findByUserIdAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartTimeAsc(
            Long userId,
            LocalDate rangeEnd,
            LocalDate rangeStart
    );
}
