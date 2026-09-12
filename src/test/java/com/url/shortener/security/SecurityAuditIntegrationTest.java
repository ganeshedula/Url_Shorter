package com.url.shortener.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.url.shortener.dtos.EmailRequest;
import com.url.shortener.dtos.LoginRequest;
import com.url.shortener.models.RefreshSession;
import com.url.shortener.models.Role;
import com.url.shortener.models.User;
import com.url.shortener.repo.UrlMappingRepository;
import com.url.shortener.repo.UserRepository;
import com.url.shortener.service.EmailService;
import com.url.shortener.service.GeoLocationClient;
import com.url.shortener.service.RedisSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
class SecurityAuditIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UrlMappingRepository urlMappingRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private RedisSessionService redisSessionService;

    @MockBean
    private GeoLocationClient geoLocationClient;

    @MockBean
    private EmailService emailService;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    private final Map<String, RefreshSession> sessionsById = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> sessionsByUser = new ConcurrentHashMap<>();
    private final Set<String> blacklistedTokenIds = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void setUp() {
        sessionsById.clear();
        sessionsByUser.clear();
        blacklistedTokenIds.clear();
        urlMappingRepository.deleteAll();
        userRepository.deleteAll();

        when(geoLocationClient.lookup(anyString())).thenReturn(Optional.empty());

        // Default redis mock for RateLimitFilter to allow requests through
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L);

        // RedisSessionService mocks
        doAnswer(invocation -> {
            RefreshSession session = invocation.getArgument(0);
            sessionsById.put(session.getSessionId(), session);
            sessionsByUser.computeIfAbsent(session.getUserId(), ignored -> ConcurrentHashMap.newKeySet()).add(session.getSessionId());
            return null;
        }).when(redisSessionService).storeSession(any(RefreshSession.class));

        when(redisSessionService.getSession(anyString()))
            .thenAnswer(invocation -> Optional.ofNullable(sessionsById.get(invocation.getArgument(0))));

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

        when(redisSessionService.isAccessTokenBlacklisted(anyString()))
            .thenAnswer(invocation -> blacklistedTokenIds.contains(invocation.getArgument(0)));
    }

    @Test
    @DisplayName("BOLA/IDOR: User B cannot view, update, or delete User A's short URL")
    void bola_userCannotAccessOrMutateAnotherUsersUrl() throws Exception {
        createUser("alice@example.com");
        createUser("bob@example.com");

        String aliceToken = login("alice@example.com");
        String bobToken = login("bob@example.com");

        // Alice creates a short URL
        String aliceUrlBody = mockMvc.perform(post("/api/url")
                .header("Authorization", "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://example.com/alice-secret\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String aliceUrlId = objectMapper.readTree(aliceUrlBody).path("data").path("id").asText();

        // Bob attempts to fetch Alice's URL & analytics -> 404 (IDOR/BOLA prevention)
        mockMvc.perform(get("/api/url/" + aliceUrlId)
                .header("Authorization", "Bearer " + bobToken))
            .andExpect(status().isNotFound());

        // Bob attempts to update Alice's URL -> 404
        mockMvc.perform(put("/api/url/" + aliceUrlId)
                .header("Authorization", "Bearer " + bobToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
            .andExpect(status().isNotFound());

        // Bob attempts to delete Alice's URL -> 404
        mockMvc.perform(delete("/api/url/" + aliceUrlId)
                .header("Authorization", "Bearer " + bobToken))
            .andExpect(status().isNotFound());

        // Bob's list does not include Alice's URL
        mockMvc.perform(get("/api/url/my")
                .header("Authorization", "Bearer " + bobToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isEmpty());

        // Alice can access her own URL and analytics
        mockMvc.perform(get("/api/url/" + aliceUrlId)
                .header("Authorization", "Bearer " + aliceToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.originalUrl").value("https://example.com/alice-secret"))
            .andExpect(jsonPath("$.data.clickCount").value(0));

        // Alice can update her own URL
        mockMvc.perform(put("/api/url/" + aliceUrlId)
                .header("Authorization", "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.active").value(false));

        // Alice can delete her own URL
        mockMvc.perform(delete("/api/url/" + aliceUrlId)
                .header("Authorization", "Bearer " + aliceToken))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Input Validation: Reject dangerous URL schemes (javascript, data, file, ftp)")
    void inputValidation_dangerousSchemesRejected() throws Exception {
        createUser("validator@example.com");
        String token = login("validator@example.com");

        String[] dangerousUrls = {
            "javascript:alert(1)",
            "JAVASCRIPT:prompt(document.cookie)",
            "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
            "file:///etc/passwd",
            "ftp://ftp.example.com/secret.txt"
        };

        for (String badUrl : dangerousUrls) {
            mockMvc.perform(post("/api/url")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"url\":\"" + badUrl + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        }
    }

    @Test
    @DisplayName("Input Validation: Reject URLs containing CRLF characters to prevent HTTP response splitting")
    void inputValidation_crlfCharactersRejected() throws Exception {
        createUser("crlf@example.com");
        String token = login("crlf@example.com");

        String urlWithCrlf = "https://example.com/redirect\r\nSet-Cookie:admin=true";
        mockMvc.perform(post("/api/url")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + urlWithCrlf + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("Input Validation: Reject URLs exceeding maximum allowed length of 2048 characters")
    void inputValidation_excessiveUrlLengthRejected() throws Exception {
        createUser("length@example.com");
        String token = login("length@example.com");

        String oversizedUrl = "https://example.com/" + "a".repeat(2040);
        assertThat(oversizedUrl.length()).isGreaterThan(2048);

        mockMvc.perform(post("/api/url")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + oversizedUrl + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("Input Validation: Reject past expiration dates and blank URLs")
    void inputValidation_pastExpirationDateAndBlankUrlRejected() throws Exception {
        createUser("validation@example.com");
        String token = login("validation@example.com");

        // Blank URL
        mockMvc.perform(post("/api/url")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"   \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));

        // Past expiration date
        OffsetDateTime pastDate = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
        mockMvc.perform(post("/api/url")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://example.com\",\"expirationDate\":\"" + pastDate + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("Error Handling: Malformed JSON produces HTTP 400 with structured response, not 500")
    void errorHandling_malformedJsonReturns400() throws Exception {
        createUser("json@example.com");
        String token = login("json@example.com");

        mockMvc.perform(post("/api/url")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\": "))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("Malformed JSON request body"));
    }

    @Test
    @DisplayName("User Enumeration: Password reset request returns identical 200 response for existing and non-existing emails")
    void userEnumeration_forgotPasswordReturnsIdenticalResponse() throws Exception {
        createUser("existing@example.com");

        EmailRequest existingRequest = new EmailRequest();
        existingRequest.setEmail("existing@example.com");

        EmailRequest nonExistingRequest = new EmailRequest();
        nonExistingRequest.setEmail("nonexistent@example.com");

        String expectedMessage = "If an account exists for this email, a verification code has been sent.";

        // Existing user
        mockMvc.perform(post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(existingRequest)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value(expectedMessage));

        // Non-existent user
        mockMvc.perform(post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(nonExistingRequest)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value(expectedMessage));
    }

    @Test
    @DisplayName("Security Headers: Responses include X-Content-Type-Options nosniff")
    void securityHeaders_xContentTypeOptionsPresent() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    @DisplayName("Rate Limiting: Exceeding request threshold returns 429 Too Many Requests with Retry-After header")
    void rateLimiting_thresholdExceededReturns429() throws Exception {
        // Configure Redis mock to simulate rate limit exceeded
        when(valueOperations.increment(anyString())).thenReturn(100L);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(45L);

        mockMvc.perform(post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"test@example.com\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "45"))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("Too many requests"));
    }

    @Test
    @DisplayName("Authentication: Unauthenticated requests to protected endpoints return 401 Unauthorized")
    void authentication_unauthenticatedRequestsReturn401() throws Exception {
        mockMvc.perform(get("/api/url/my"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/url")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://example.com\"}"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/auth/me"))
            .andExpect(status().isUnauthorized());
    }

    private String login(String email) throws Exception {
        LoginRequest request = new LoginRequest();
        request.setEmail(email);
        request.setPassword("password123");
        String body = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(body).path("data").path("accessToken").asText();
    }

    private void createUser(String email) {
        User user = new User();
        user.setEmail(email);
        user.setUsername(email.split("@")[0]);
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(Role.ROLE_USER);
        user.setEmailVerified(true);
        userRepository.save(user);
    }
}
