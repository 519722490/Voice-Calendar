package com.cyx.backend.service;

import com.cyx.backend.dto.AuthResponse;
import com.cyx.backend.dto.LoginRequest;
import com.cyx.backend.dto.RegisterRequest;
import com.cyx.backend.dto.UserProfile;
import com.cyx.backend.entity.UserEntity;
import com.cyx.backend.repository.UserRepository;
import com.cyx.backend.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String username = normalizeUsername(request.username());

        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("用户名已存在");
        }

        UserEntity user = userRepository.save(new UserEntity(
                username,
                passwordEncoder.encode(request.password()),
                normalizeDisplayName(request.displayName())
        ));

        return toAuthResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        UserEntity user = userRepository.findByUsername(normalizeUsername(request.username()))
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        return toAuthResponse(user);
    }

    @Transactional(readOnly = true)
    public UserProfile getProfile(Long userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        return toProfile(user);
    }

    private AuthResponse toAuthResponse(UserEntity user) {
        return new AuthResponse(jwtService.generateToken(user), toProfile(user));
    }

    private UserProfile toProfile(UserEntity user) {
        return new UserProfile(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getCreatedAt()
        );
    }

    private String normalizeUsername(String username) {
        return username.trim().toLowerCase();
    }

    private String normalizeDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        return displayName.trim();
    }
}
