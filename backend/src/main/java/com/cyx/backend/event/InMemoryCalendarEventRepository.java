package com.cyx.backend.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryCalendarEventRepository implements CalendarEventRepository {
    private final ConcurrentMap<Long, CalendarEvent> events = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong();

    @Override
    public List<CalendarEvent> findAll() {
        return new ArrayList<>(events.values());
    }

    @Override
    public Optional<CalendarEvent> findById(Long id) {
        return Optional.ofNullable(events.get(id));
    }

    @Override
    public CalendarEvent save(CalendarEvent event) {
        Long id = event.id() == null ? idGenerator.incrementAndGet() : event.id();
        CalendarEvent stored = new CalendarEvent(
                id,
                event.title(),
                event.startTime(),
                event.endTime(),
                event.location(),
                event.description(),
                event.tag(),
                event.reminderTime(),
                event.createdAt(),
                event.updatedAt()
        );
        events.put(id, stored);
        return stored;
    }

    @Override
    public boolean deleteById(Long id) {
        return events.remove(id) != null;
    }
}
