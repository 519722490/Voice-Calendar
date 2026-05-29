package com.cyx.backend.event;

import java.util.List;
import java.util.Optional;

public interface CalendarEventRepository {
    List<CalendarEvent> findAll();

    Optional<CalendarEvent> findById(Long id);

    CalendarEvent save(CalendarEvent event);

    boolean deleteById(Long id);
}
