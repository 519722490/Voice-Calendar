package com.cyx.backend.common;

import java.time.Instant;
import java.util.List;

public record ApiError(
        Instant timestamp,
        int status,
        String message,
        List<String> details
) {
    public static ApiError of(int status, String message, List<String> details) {
        return new ApiError(Instant.now(), status, message, details);
    }
}
