package com.url.shortener.service;

import com.url.shortener.dtos.AuthResponse;
import com.url.shortener.dtos.LoginRequest;
import com.url.shortener.dtos.RefreshTokenRequest;
import com.url.shortener.dtos.RegisterRequest;
import com.url.shortener.dtos.UserResponse;
import com.url.shortener.exception.DuplicateResourceException;
import com.url.shortener.exception.InvalidTokenException;
import com.url.shortener.models.RefreshSession;
import com.url.shortener.models.Role;
import com.url.shortener.models.User;
import com.url.shortener.repo.UserRepository;
import com.url.shortener.security.JwtService;
import com.url.shortener.util.ClientInfo;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RedisSessionService redisSessionService;
    private final UserService userService;

    public AuthService(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        AuthenticationManager authenticationManager,
        JwtService jwtService,
        RedisSessionService redisSessionService,
        UserService userService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.redisSessionService = redisSessionService;
        this.userService = userService;
    }

    public AuthResponse register(RegisterRequest request, ClientInfo clientInfo) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Email is already registered");
        }

        User user = new User();
        user.setEmail(request.getEmail().trim().toLowerCase());
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(Role.ROLE_USER);
        User savedUser = userRepository.save(user);

        return buildAuthResponse(savedUser, clientInfo);
    }

    public AuthResponse login(LoginRequest request, ClientInfo clientInfo) {
        Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.getEmail().trim().toLowerCase(), request.getPassword())
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        User user = userService.findByEmail(request.getEmail().trim().toLowerCase());
        return buildAuthResponse(user, clientInfo);
    }

    /**
     * Reuses the application's users table and JWT/Redis session lifecycle for Google identities.
     */
    public AuthResponse loginWithGoogle(String email, String displayName, ClientInfo clientInfo) {
        String normalizedEmail = email.trim().toLowerCase();
        User user = userRepository.findByEmail(normalizedEmail).orElseGet(() -> {
            User newUser = new User();
            newUser.setEmail(normalizedEmail);
            newUser.setUsername(displayName == null || displayName.isBlank() ? normalizedEmail : displayName.trim());
            // The existing schema requires a password. This unguessable value is never returned or used by OAuth.
            newUser.setPassword(passwordEncoder.encode(generateRandomSecret()));
            newUser.setRole(Role.ROLE_USER);
            return userRepository.save(newUser);
        });
        return buildAuthResponse(user, clientInfo);
    }

    public AuthResponse refresh(RefreshTokenRequest request, ClientInfo clientInfo) {
        String refreshToken = request.getRefreshToken();
        if (!jwtService.isTokenValid(refreshToken, JwtService.TOKEN_TYPE_REFRESH)) {
            throw new InvalidTokenException("Refresh token is invalid or expired");
        }

        String sessionId = jwtService.extractSessionId(refreshToken);
        RefreshSession session = redisSessionService.getSession(sessionId)
            .orElseThrow(() -> new InvalidTokenException("Refresh session not found"));

        if (!refreshToken.equals(session.getRefreshToken())) {
            throw new InvalidTokenException("Refresh token has been rotated or revoked");
        }

        User user = userService.findById(session.getUserId());
        if (jwtService.extractTokenVersion(refreshToken) != user.getTokenVersion()) {
            redisSessionService.invalidateSession(sessionId, user.getId());
            throw new InvalidTokenException("Refresh token has been invalidated");
        }
        redisSessionService.invalidateSession(sessionId, user.getId());
        return buildAuthResponse(user, clientInfo);
    }

    public void logout(String accessToken, String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            if (!jwtService.isTokenValid(refreshToken, JwtService.TOKEN_TYPE_REFRESH)) {
                throw new InvalidTokenException("Refresh token is invalid or expired");
            }
            String sessionId = jwtService.extractSessionId(refreshToken);
            UUID userId = jwtService.extractUserId(refreshToken);
            redisSessionService.invalidateSession(sessionId, userId);
        }

        if (accessToken != null && !accessToken.isBlank()) {
            Duration ttl = Duration.between(OffsetDateTime.now(ZoneOffset.UTC).toInstant(), jwtService.extractExpiration(accessToken));
            redisSessionService.blacklistAccessToken(jwtService.extractId(accessToken), ttl);
        }
    }

    public void logoutAll(String accessToken) {
        User user = userService.findById(jwtService.extractUserId(accessToken));
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);
        UUID userId = user.getId();
        redisSessionService.invalidateAllSessions(userId);
        Duration ttl = Duration.between(OffsetDateTime.now(ZoneOffset.UTC).toInstant(), jwtService.extractExpiration(accessToken));
        redisSessionService.blacklistAccessToken(jwtService.extractId(accessToken), ttl);
    }

    public UserResponse currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserDetailsImpl userDetails)) {
            throw new InvalidTokenException("No authenticated user found");
        }
        return userService.toResponse(userService.findById(userDetails.getId()));
    }

    private AuthResponse buildAuthResponse(User user, ClientInfo clientInfo) {
        String sessionId = UUID.randomUUID().toString();
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user, sessionId);

        RefreshSession session = RefreshSession.builder()
            .sessionId(sessionId)
            .userId(user.getId())
            .email(user.getEmail())
            .refreshToken(refreshToken)
            .loginAt(OffsetDateTime.now(ZoneOffset.UTC))
            .expiresAt(OffsetDateTime.now(ZoneOffset.UTC).plus(jwtService.getRefreshTokenExpiration()))
            .deviceInfo(clientInfo.getDeviceInfo())
            .ipAddress(clientInfo.getIpAddress())
            .build();
        redisSessionService.storeSession(session);

        return AuthResponse.builder()
            .accessToken(accessToken)
            .refreshToken(refreshToken)
            .tokenType("Bearer")
            .accessTokenExpiresInSeconds(jwtService.getAccessTokenExpiration().toSeconds())
            .user(userService.toResponse(user))
            .build();
    }

    private String generateRandomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
