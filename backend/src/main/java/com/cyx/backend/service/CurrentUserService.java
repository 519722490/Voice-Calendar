package com.cyx.backend.service;

import com.cyx.backend.security.AuthenticatedUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserService {
    public Long requireCurrentUserId() {
        return requireCurrentUser().id();
    }

    public AuthenticatedUser requireCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user;
        }

        throw new IllegalArgumentException("请先登录");
    }
}
