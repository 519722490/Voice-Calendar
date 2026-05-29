package com.cyx.backend.dto;

public record AuthResponse(
        String token,
        UserProfile user
) {
}
