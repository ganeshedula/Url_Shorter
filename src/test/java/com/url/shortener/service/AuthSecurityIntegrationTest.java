package com.url.shortener.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.url.shortener.dtos.LoginRequest;
import com.url.shortener.dtos.RefreshTokenRequest;
import com.url.shortener.models.RefreshSession;
import com.url.shortener.models.Role;
import com.url.shortener.models.User;
import com.url.shortener.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private RedisSessionService redisSessionService;

    @MockBean
    private GeoLocationClient geoLocationClient;

    private final Map<String, RefreshSession> sessionsById = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> sessionsByUser = new ConcurrentHashMap<>();
    private final Set<String> blacklistedTokenIds = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void setUp() {
        sessionsById.clear();
        sessionsByUser.clear();
        blacklistedTokenIds.clear();
        userRepository.deleteAll();

        User user = new User();
        user.setEmail("security@example.com");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(Role.ROLE_USER);
        userRepository.save(user);

        when(geoLocationClient.lookup(anyString())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            RefreshSession session = invocation.getArgument(0);
            sessionsById.put(session.getSessionId(), session);
            sessionsByUser.computeIfAbsent(session.getUserId(), ignored -> ConcurrentHashMap.newKeySet()).add(session.getSessionId());
            return null;
        }).when(redisSessionService).storeSession(any(RefreshSession.class));
        when(redisSessionService.getSession(anyString())).thenAnswer(invocation -> Optional.ofNullable(sessionsById.get(invocation.getArgument(0))));
        doAnswer(invocation -> {
            String sessionId = invocation.getArgument(0);
            UUID userId = invocation.getArgument(1);
            sessionsById.remove(sessionId);
            Set<String> userSessions = sessionsByUser.get(userId);
            if (userSessions != null) {
                userSessions.remove(sessionId);
            }
            return null;
        }).when(redisSessionService).invalidateSession(anyString(), any(UUID.class));
        doAnswer(invocation -> {
            UUID userId = invocation.getArgument(0);
            Set<String> sessionIds = sessionsByUser.remove(userId);
            if (sessionIds != null) {
                sessionIds.forEach(sessionsById::remove);
            }
            return null;
        }).when(redisSessionService).invalidateAllSessions(any(UUID.class));
        doAnswer(invocation -> {
            blacklistedTokenIds.add(invocation.getArgument(0));
            return null;
        }).when(redisSessionService).blacklistAccessToken(anyString(), any(Duration.class));
        when(redisSessionService.isAccessTokenBlacklisted(anyString())).thenAnswer(invocation -> blacklistedTokenIds.contains(invocation.getArgument(0)));
    }

    @Test
    void logoutAllInvalidatesAllPreviouslyIssuedSessions() throws Exception {
        AuthTokens browserA = login();
        AuthTokens browserB = login();

        mockMvc.perform(post("/api/auth/logout-all")
                .header("Authorization", "Bearer " + browserA.accessToken()))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/auth/me")
                .header("Authorization", "Bearer " + browserA.accessToken()))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/auth/me")
                .header("Authorization", "Bearer " + browserB.accessToken()))
            .andExpect(status().isUnauthorized());

        RefreshTokenRequest refreshTokenRequest = new RefreshTokenRequest();
        refreshTokenRequest.setRefreshToken(browserB.refreshToken());
        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(refreshTokenRequest)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutCurrentSessionKeepsOtherSessionsWorking() throws Exception {
        AuthTokens browserA = login();
        AuthTokens browserB = login();

        RefreshTokenRequest refreshTokenRequest = new RefreshTokenRequest();
        refreshTokenRequest.setRefreshToken(browserA.refreshToken());
        mockMvc.perform(post("/api/auth/logout")
                .header("Authorization", "Bearer " + browserA.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(refreshTokenRequest)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/auth/me")
                .header("Authorization", "Bearer " + browserA.accessToken()))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/auth/me")
                .header("Authorization", "Bearer " + browserB.accessToken()))
            .andExpect(status().isOk());
    }

    @Test
    void userCanLoginAgainAfterLogoutAll() throws Exception {
        AuthTokens browserA = login();

        mockMvc.perform(post("/api/auth/logout-all")
                .header("Authorization", "Bearer " + browserA.accessToken()))
            .andExpect(status().isOk());

        AuthTokens freshLogin = login();

        mockMvc.perform(get("/api/auth/me")
                .header("Authorization", "Bearer " + freshLogin.accessToken()))
            .andExpect(status().isOk());

        User user = userRepository.findByEmail("security@example.com").orElseThrow();
        assertThat(user.getTokenVersion()).isEqualTo(1L);
    }

    private AuthTokens login() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setEmail("security@example.com");
        request.setPassword("password123");

        String responseBody = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode data = objectMapper.readTree(responseBody).path("data");
        return new AuthTokens(data.path("accessToken").asText(), data.path("refreshToken").asText());
    }

    private record AuthTokens(String accessToken, String refreshToken) {
    }
}
