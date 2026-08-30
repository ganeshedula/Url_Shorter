package com.url.shortener.security;

import com.url.shortener.config.AppProperties;
import com.url.shortener.models.Role;
import com.url.shortener.models.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtService {

    public static final String CLAIM_USER_ID = "userId";
    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_TOKEN_TYPE = "tokenType";
    public static final String CLAIM_SESSION_ID = "sessionId";
    public static final String CLAIM_TOKEN_VERSION = "tokenVersion";
    public static final String TOKEN_TYPE_ACCESS = "ACCESS";
    public static final String TOKEN_TYPE_REFRESH = "REFRESH";

    private final SecretKey secretKey;
    private final Duration accessTokenExpiration;
    private final Duration refreshTokenExpiration;

    public JwtService(AppProperties appProperties) {
        this.secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(appProperties.getJwt().getSecret()));
        this.accessTokenExpiration = appProperties.getJwt().getAccessTokenExpiration();
        this.refreshTokenExpiration = appProperties.getJwt().getRefreshTokenExpiration();
    }

    public String generateAccessToken(User user) {
        return buildToken(
            user,
            accessTokenExpiration,
            TOKEN_TYPE_ACCESS,
            null
        );
    }

    public String generateRefreshToken(User user, String sessionId) {
        return buildToken(
            user,
            refreshTokenExpiration,
            TOKEN_TYPE_REFRESH,
            sessionId
        );
    }

    public Claims extractClaims(String token) {
        return parseSignedClaims(token).getPayload();
    }

    public boolean isTokenValid(String token, String expectedType) {
        Claims claims = extractClaims(token);
        return expectedType.equals(claims.get(CLAIM_TOKEN_TYPE, String.class))
            && claims.getExpiration().toInstant().isAfter(Instant.now());
    }

    public String extractEmail(String token) {
        return extractClaims(token).get(CLAIM_EMAIL, String.class);
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(extractClaims(token).get(CLAIM_USER_ID, String.class));
    }

    public String extractSessionId(String token) {
        return extractClaims(token).get(CLAIM_SESSION_ID, String.class);
    }

    public long extractTokenVersion(String token) {
        Long tokenVersion = extractClaims(token).get(CLAIM_TOKEN_VERSION, Long.class);
        return tokenVersion == null ? 0L : tokenVersion;
    }

    public Instant extractExpiration(String token) {
        return extractClaims(token).getExpiration().toInstant();
    }

    public String extractId(String token) {
        return extractClaims(token).getId();
    }

    public Duration getAccessTokenExpiration() {
        return accessTokenExpiration;
    }

    public Duration getRefreshTokenExpiration() {
        return refreshTokenExpiration;
    }

    private String buildToken(User user, Duration expiration, String tokenType, String sessionId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(expiration);
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put(CLAIM_USER_ID, user.getId().toString());
        claims.put(CLAIM_EMAIL, user.getEmail());
        claims.put(CLAIM_ROLE, user.getRole().name());
        claims.put(CLAIM_TOKEN_TYPE, tokenType);
        claims.put(CLAIM_TOKEN_VERSION, user.getTokenVersion());
        if (sessionId != null) {
            claims.put(CLAIM_SESSION_ID, sessionId);
        }

        return Jwts.builder()
            .id(UUID.randomUUID().toString())
            .subject(user.getEmail())
            .claims(claims)
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiresAt))
            .signWith(secretKey)
            .compact();
    }

    private Jws<Claims> parseSignedClaims(String token) throws JwtException {
        return Jwts.parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token);
    }
}
