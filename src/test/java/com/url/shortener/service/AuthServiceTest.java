package com.url.shortener.service;

import com.url.shortener.dtos.LoginRequest;
import com.url.shortener.dtos.RegisterRequest;
import com.url.shortener.exception.DuplicateResourceException;
import com.url.shortener.models.User;
import com.url.shortener.repo.UserRepository;
import com.url.shortener.security.JwtService;
import com.url.shortener.util.ClientInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtService jwtService;

    @Mock
    private RedisSessionService redisSessionService;

    @Mock
    private UserService userService;

    @InjectMocks
    private AuthService authService;

    private ClientInfo clientInfo;

    @BeforeEach
    void setUp() {
        clientInfo = ClientInfo.builder()
            .browser("Chrome")
            .operatingSystem("macOS")
            .ipAddress("127.0.0.1")
            .country("Unknown")
            .deviceInfo("Chrome on macOS")
            .build();
    }

    @Test
    void registerThrowsWhenEmailAlreadyExists() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("user@example.com");
        request.setPassword("password123");

        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request, clientInfo))
            .isInstanceOf(DuplicateResourceException.class)
            .hasMessageContaining("already registered");
    }

    @Test
    void registerEncodesPasswordAndStoresSession() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("user@example.com");
        request.setPassword("password123");

        User savedUser = new User();
        savedUser.setId(UUID.randomUUID());
        savedUser.setEmail("user@example.com");
        savedUser.setPassword("encoded-password");

        when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("encoded-password");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(jwtService.generateAccessToken(savedUser)).thenReturn("access-token");
        when(jwtService.generateRefreshToken(any(User.class), any(String.class))).thenReturn("refresh-token");
        when(jwtService.getAccessTokenExpiration()).thenReturn(Duration.ofMinutes(15));
        when(jwtService.getRefreshTokenExpiration()).thenReturn(Duration.ofDays(7));
        when(userService.toResponse(savedUser)).thenReturn(null);

        authService.register(request, clientInfo);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPassword()).isEqualTo("encoded-password");
        verify(redisSessionService).storeSession(any());
    }

    @Test
    void googleLoginUsesExistingUserWithoutCreatingDuplicate() {
        User existingUser = new User();
        existingUser.setId(UUID.randomUUID());
        existingUser.setEmail("user@example.com");

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(existingUser));
        when(jwtService.generateAccessToken(existingUser)).thenReturn("access-token");
        when(jwtService.generateRefreshToken(any(User.class), any(String.class))).thenReturn("refresh-token");
        when(jwtService.getAccessTokenExpiration()).thenReturn(Duration.ofMinutes(15));
        when(jwtService.getRefreshTokenExpiration()).thenReturn(Duration.ofDays(7));
        when(userService.toResponse(existingUser)).thenReturn(null);

        authService.loginWithGoogle("USER@example.com", "Google User", clientInfo);

        verify(userRepository, org.mockito.Mockito.never()).save(any(User.class));
        verify(redisSessionService).storeSession(any());
    }

    @Test
    void googleLoginCreatesUserInExistingUsersTableWhenEmailIsNew() {
        User savedUser = new User();
        savedUser.setId(UUID.randomUUID());
        savedUser.setEmail("new@example.com");

        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(passwordEncoder.encode(any(String.class))).thenReturn("random-password-hash");
        when(jwtService.generateAccessToken(savedUser)).thenReturn("access-token");
        when(jwtService.generateRefreshToken(any(User.class), any(String.class))).thenReturn("refresh-token");
        when(jwtService.getAccessTokenExpiration()).thenReturn(Duration.ofMinutes(15));
        when(jwtService.getRefreshTokenExpiration()).thenReturn(Duration.ofDays(7));
        when(userService.toResponse(savedUser)).thenReturn(null);

        authService.loginWithGoogle("new@example.com", "New Google User", clientInfo);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("new@example.com");
        assertThat(userCaptor.getValue().getUsername()).isEqualTo("New Google User");
        assertThat(userCaptor.getValue().getPassword()).isEqualTo("random-password-hash");
    }
}
