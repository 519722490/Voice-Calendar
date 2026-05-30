package com.cyx.backend.event;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum CalendarEventTag {
    MEETING("会议"),
    WORK("工作"),
    STUDY("学习"),
    LIFE("生活"),
    EXERCISE("运动"),
    TRAVEL("出行"),
    REMINDER("提醒"),
    OTHER("其他");

    private static final Set<String> LABELS = Arrays.stream(values())
            .map(CalendarEventTag::label)
            .collect(Collectors.toUnmodifiableSet());

    private final String label;

    CalendarEventTag(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return OTHER.label;
        }
        String normalized = value.trim();
        return LABELS.contains(normalized) ? normalized : OTHER.label;
    }

    public static String allowedLabelsText() {
        return String.join("、", LABELS);
    }
}
