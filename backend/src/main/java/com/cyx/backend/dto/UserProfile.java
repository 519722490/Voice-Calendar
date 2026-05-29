package com.cyx.backend.dto;

import java.time.Instant;

public record UserProfile(
        Long id,
        String username,
        String displayName,
        Instant createdAt
) {
}
