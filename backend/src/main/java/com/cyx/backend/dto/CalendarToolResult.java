package com.cyx.backend.dto;



public record CalendarToolResult(
        boolean success,
        String message,
        CalendarEvent event
) {
    public static CalendarToolResult success(String message, CalendarEvent event) {
        return new CalendarToolResult(true, message, event);
    }
}
