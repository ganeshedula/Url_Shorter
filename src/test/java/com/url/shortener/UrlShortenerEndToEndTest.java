package com.url.shortener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.url.shortener.dtos.LoginRequest;
import com.url.shortener.dtos.OtpVerificationRequest;
import com.url.shortener.dtos.RefreshTokenRequest;
import com.url.shortener.dtos.RegisterRequest;
import com.url.shortener.models.OtpPurpose;
import com.url.shortener.models.RefreshSession;
import com.url.shortener.repo.ClickEventRepository;
import com.url.shortener.repo.UrlMappingRepository;
import com.url.shortener.repo.UserRepository;
import com.url.shortener.service.EmailService;
import com.url.shortener.service.GeoLocationClient;
import com.url.shortener.service.RedisSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UrlShortenerEndToEndTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UrlMappingRepository urlMappingRepository;

    @Autowired
    private ClickEventRepository clickEventRepository;

    @MockBean
    private RedisSessionService redisSessionService;

    @MockBean
    private EmailService emailService;

    @MockBean
    private GeoLocationClient geoLocationClient;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    private final Map<String, String> inMemoryRedis = new ConcurrentHashMap<>();
    private final Map<String, RefreshSession> sessionsById = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> sessionsByUser = new ConcurrentHashMap<>();
    private final Set<String> blacklistedTokenIds = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void setUp() {
        inMemoryRedis.clear();
        sessionsById.clear();
        sessionsByUser.clear();
        blacklistedTokenIds.clear();

        clickEventRepository.deleteAll();
        urlMappingRepository.deleteAll();
        userRepository.deleteAll();

        when(geoLocationClient.lookup(anyString())).thenReturn(Optional.empty());

        // Setup StringRedisTemplate value operations with in-memory map
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenAnswer(inv -> inMemoryRedis.get(inv.getArgument(0)));
        doAnswer(inv -> {
            inMemoryRedis.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        doAnswer(inv -> {
            inMemoryRedis.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), anyString());
        when(valueOperations.increment(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            long val = inMemoryRedis.containsKey(key) ? Long.parseLong(inMemoryRedis.get(key)) + 1 : 1L;
            inMemoryRedis.put(key, String.valueOf(val));
            return val;
        });
        when(redisTemplate.hasKey(anyString())).thenAnswer(inv -> inMemoryRedis.containsKey(inv.getArgument(0)));
        doAnswer(inv -> {
            Object arg = inv.getArgument(0);
            if (arg instanceof Collection<?> collection) {
                collection.forEach(item -> inMemoryRedis.remove(item.toString()));
            } else if (arg instanceof String key) {
                inMemoryRedis.remove(key);
            }
            return null;
        }).when(redisTemplate).delete(any(Collection.class));
        doAnswer(inv -> {
            inMemoryRedis.remove(inv.getArgument(0).toString());
            return Boolean.TRUE;
        }).when(redisTemplate).delete(anyString());

        // Setup RedisSessionService mocks
        doAnswer(inv -> {
            RefreshSession session = inv.getArgument(0);
            sessionsById.put(session.getSessionId(), session);
            sessionsByUser.computeIfAbsent(session.getUserId(), ignored -> ConcurrentHashMap.newKeySet()).add(session.getSessionId());
            return null;
        }).when(redisSessionService).storeSession(any(RefreshSession.class));

        when(redisSessionService.getSession(anyString()))
            .thenAnswer(inv -> Optional.ofNullable(sessionsById.get(inv.getArgument(0))));

        doAnswer(inv -> {
            String sessionId = inv.getArgument(0);
            UUID userId = inv.getArgument(1);
            sessionsById.remove(sessionId);
            Set<String> userSessions = sessionsByUser.get(userId);
            if (userSessions != null) {
                userSessions.remove(sessionId);
            }
            return null;
        }).when(redisSessionService).invalidateSession(anyString(), any(UUID.class));

        doAnswer(inv -> {
            UUID userId = inv.getArgument(0);
            Set<String> sessionIds = sessionsByUser.remove(userId);
            if (sessionIds != null) {
                sessionIds.forEach(sessionsById::remove);
            }
            return null;
        }).when(redisSessionService).invalidateAllSessions(any(UUID.class));

        doAnswer(inv -> {
            blacklistedTokenIds.add(inv.getArgument(0));
            return null;
        }).when(redisSessionService).blacklistAccessToken(anyString(), any(Duration.class));

        when(redisSessionService.isAccessTokenBlacklisted(anyString()))
            .thenAnswer(inv -> blacklistedTokenIds.contains(inv.getArgument(0)));
    }

    @Test
    @DisplayName("Complete User and URL Shortening Lifecycle End-to-End")
    void completeEndToEndUserAndUrlLifecycle() throws Exception {
        String email = "lifecycle@example.com";
        String password = "StrongPassword123!";
        String username = "lifecycleuser";

        // Step 1: User Registration
        RegisterRequest registerRequest = new RegisterRequest();
        registerRequest.setEmail(email);
        registerRequest.setPassword(password);
        registerRequest.setUsername(username);

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerRequest)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        // Capture OTP sent via email
        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendOtp(eq(email), eq(username), otpCaptor.capture(), anyLong(), eq(OtpPurpose.ACCOUNT_VERIFICATION));
        String capturedOtp = otpCaptor.getValue();
        assertThat(capturedOtp).matches("\\d{6}");

        // Step 2: Verify Registration OTP
        OtpVerificationRequest otpRequest = new OtpVerificationRequest();
        otpRequest.setEmail(email);
        otpRequest.setOtp(capturedOtp);

        mockMvc.perform(post("/api/auth/verify-registration-otp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(otpRequest)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());

        // Step 3: Login
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail(email);
        loginRequest.setPassword(password);

        String loginResponseStr = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn().getResponse().getContentAsString();

        JsonNode loginData = objectMapper.readTree(loginResponseStr).path("data");
        String accessToken = loginData.path("accessToken").asText();
        String refreshToken = loginData.path("refreshToken").asText();

        // Step 4: Verify authenticated user profile
        mockMvc.perform(get("/api/auth/me")
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.email").value(email))
            .andExpect(jsonPath("$.data.username").value(username));

        // Step 5: Create a Short URL
        String originalUrl = "https://github.com/torvalds/linux";
        String createUrlResponse = mockMvc.perform(post("/api/url")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + originalUrl + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn().getResponse().getContentAsString();

        JsonNode urlData = objectMapper.readTree(createUrlResponse).path("data");
        String urlId = urlData.path("id").asText();
        String shortCode = urlData.path("shortCode").asText();
        assertThat(shortCode).matches("[a-zA-Z0-9]{6,20}");

        // Step 6: Public Redirect (Anonymous click)
        mockMvc.perform(get("/" + shortCode))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", originalUrl));

        // Step 7: Verify Click Analytics
        mockMvc.perform(get("/api/url/" + urlId)
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.clickCount").value(1))
            .andExpect(jsonPath("$.data.recentClicks").isArray());

        // Step 8: List My URLs
        mockMvc.perform(get("/api/url/my")
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].shortCode").value(shortCode));

        // Step 9: Update URL (Deactivate)
        mockMvc.perform(put("/api/url/" + urlId)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.active").value(false));

        // Step 10: Inactive redirect should be rejected with 400 Bad Request
        mockMvc.perform(get("/" + shortCode))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Short URL is inactive"));

        // Step 11: Reactivate URL
        mockMvc.perform(put("/api/url/" + urlId)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.active").value(true));

        // Step 12: Refresh Token
        RefreshTokenRequest refreshReq = new RefreshTokenRequest();
        refreshReq.setRefreshToken(refreshToken);

        String refreshResponseStr = mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(refreshReq)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn().getResponse().getContentAsString();

        JsonNode refreshData = objectMapper.readTree(refreshResponseStr).path("data");
        String newAccessToken = refreshData.path("accessToken").asText();
        String newRefreshToken = refreshData.path("refreshToken").asText();
        assertThat(newAccessToken).isNotEmpty();
        assertThat(newRefreshToken).isNotEqualTo(refreshToken);

        // Step 13: Logout
        RefreshTokenRequest logoutReq = new RefreshTokenRequest();
        logoutReq.setRefreshToken(newRefreshToken);

        mockMvc.perform(post("/api/auth/logout")
                .header("Authorization", "Bearer " + newAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(logoutReq)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("Logout successful"));

        // Step 14: Verify token blacklisting
        mockMvc.perform(get("/api/auth/me")
                .header("Authorization", "Bearer " + newAccessToken))
            .andExpect(status().isUnauthorized());

        // Step 15: Log back in and delete the short URL
        String reLoginStr = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String finalToken = objectMapper.readTree(reLoginStr).path("data").path("accessToken").asText();

        mockMvc.perform(delete("/api/url/" + urlId)
                .header("Authorization", "Bearer " + finalToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("Short URL deleted successfully"));

        // URL and redirect no longer exist
        mockMvc.perform(get("/api/url/" + urlId)
                .header("Authorization", "Bearer " + finalToken))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/" + shortCode))
            .andExpect(status().isNotFound());
    }
}
