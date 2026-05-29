package com.cyx.backend.security;

public record AuthenticatedUser(
        Long id,
        String username
) {
}
