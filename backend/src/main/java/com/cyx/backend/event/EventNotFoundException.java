package com.cyx.backend.event;

public class EventNotFoundException extends RuntimeException {
    public EventNotFoundException(Long id) {
        super("日程不存在: " + id);
    }
}
