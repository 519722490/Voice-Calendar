package com.cyx.backend.event;

import java.util.Locale;

public enum RecurrenceType {
    DAILY,
    WEEKLY,
    MONTHLY;

    public static RecurrenceType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("重复类型不能为空");
        }
        try {
            return RecurrenceType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("不支持的重复类型：" + value);
        }
    }
}
