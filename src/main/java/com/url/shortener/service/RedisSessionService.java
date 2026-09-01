package com.url.shortener.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.url.shortener.exception.InvalidTokenException;
import com.url.shortener.models.RefreshSession;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class RedisSessionService {

    private static final String SESSION_KEY_PREFIX = "auth:session:";
    private static final String USER_SESSIONS_KEY_PREFIX = "auth:user-sessions:";
    private static final String ACCESS_TOKEN_BLACKLIST_PREFIX = "auth:blacklist:";
    private static final String GOOGLE_OAUTH_STATE_PREFIX = "auth:google-oauth-state:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisSessionService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void storeSession(RefreshSession session) {
        String sessionKey = SESSION_KEY_PREFIX + session.getSessionId();
        Duration ttl = Duration.between(OffsetDateTime.now(ZoneOffset.UTC), session.getExpiresAt());
        if (ttl.isNegative() || ttl.isZero()) {
            throw new InvalidTokenException("Refresh token session is already expired");
        }
        redisTemplate.opsForValue().set(sessionKey, serialize(session), ttl);
        redisTemplate.opsForSet().add(USER_SESSIONS_KEY_PREFIX + session.getUserId(), session.getSessionId());
        redisTemplate.expire(USER_SESSIONS_KEY_PREFIX + session.getUserId(), ttl);
    }

    public Optional<RefreshSession> getSession(String sessionId) {
        String raw = redisTemplate.opsForValue().get(SESSION_KEY_PREFIX + sessionId);
        if (raw == null) {
            return Optional.empty();
        }
        return Optional.of(deserialize(raw));
    }

    public void invalidateSession(String sessionId, UUID userId) {
        redisTemplate.delete(SESSION_KEY_PREFIX + sessionId);
        redisTemplate.opsForSet().remove(USER_SESSIONS_KEY_PREFIX + userId, sessionId);
    }

    public void invalidateAllSessions(UUID userId) {
        String key = USER_SESSIONS_KEY_PREFIX + userId;
        Set<String> sessionIds = redisTemplate.opsForSet().members(key);
        if (sessionIds != null) {
            sessionIds.forEach(sessionId -> redisTemplate.delete(SESSION_KEY_PREFIX + sessionId));
        }
        redisTemplate.delete(key);
    }

    public void blacklistAccessToken(String tokenId, Duration ttl) {
        if (tokenId == null || ttl.isNegative() || ttl.isZero()) {
            return;
        }
        redisTemplate.opsForValue().set(ACCESS_TOKEN_BLACKLIST_PREFIX + tokenId, "1", ttl);
    }

    public boolean isAccessTokenBlacklisted(String tokenId) {
        if (tokenId == null) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(ACCESS_TOKEN_BLACKLIST_PREFIX + tokenId));
    }

    public void storeGoogleOAuthState(String state, String codeVerifier, Duration ttl) {
        redisTemplate.opsForValue().set(GOOGLE_OAUTH_STATE_PREFIX + state, codeVerifier, ttl);
    }

    /**
     * Returns the PKCE verifier exactly once, preventing OAuth state replay.
     */
    public Optional<String> consumeGoogleOAuthState(String state) {
        return Optional.ofNullable(redisTemplate.opsForValue().getAndDelete(GOOGLE_OAUTH_STATE_PREFIX + state));
    }

    private String serialize(RefreshSession session) {
        try {
            return objectMapper.writeValueAsString(session);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize refresh session", exception);
        }
    }

    private RefreshSession deserialize(String raw) {
        try {
            return objectMapper.readValue(raw, RefreshSession.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to deserialize refresh session", exception);
        }
    }
}
