package com.url.shortener.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.url.shortener.config.AppProperties;
import com.url.shortener.dtos.ApiResponse;
import com.url.shortener.util.ClientInfoExtractor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final ClientInfoExtractor clientInfoExtractor;

    public RateLimitFilter(
        StringRedisTemplate redisTemplate,
        ObjectMapper objectMapper,
        AppProperties appProperties,
        ClientInfoExtractor clientInfoExtractor
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
        this.clientInfoExtractor = clientInfoExtractor;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        if (!appProperties.getRateLimit().isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        RateLimitPolicy policy = resolvePolicy(request);
        if (policy == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = policy.keyPrefix() + currentIdentifier(request);
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, policy.window());
            }
            if (count != null && count > policy.limit()) {
                writeTooManyRequests(response, key, policy);
                return;
            }
        } catch (RuntimeException exception) {
            log.debug("Rate limiting unavailable for {} {}: {}", request.getMethod(), request.getRequestURI(), exception.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private RateLimitPolicy resolvePolicy(HttpServletRequest request) {
        String method = request.getMethod();
        String uri = request.getRequestURI();

        if ("POST".equals(method) && uri.startsWith("/api/auth/")) {
            return new RateLimitPolicy("rate:auth:", appProperties.getRateLimit().getAuthRequestsPerMinute(), WINDOW);
        }
        if ("GET".equals(method) && ("/api/auth/google".equals(uri) || "/api/auth/google/callback".equals(uri))) {
            return new RateLimitPolicy("rate:auth:", appProperties.getRateLimit().getAuthRequestsPerMinute(), WINDOW);
        }
        if (uri.startsWith("/api/url")) {
            return new RateLimitPolicy("rate:url:", appProperties.getRateLimit().getUrlCreateRequestsPerMinute(), WINDOW);
        }
        if ("GET".equals(method) && uri.matches("^/[A-Za-z0-9]{6,20}$")) {
            return new RateLimitPolicy("rate:redirect:", appProperties.getRateLimit().getRedirectRequestsPerMinute(), WINDOW);
        }
        return null;
    }

    private String currentIdentifier(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken)) {
            String name = authentication.getName();
            if (StringUtils.hasText(name)) {
                return "user:" + name.toLowerCase();
            }
        }
        return "ip:" + clientInfoExtractor.extract(request).getIpAddress();
    }

    private void writeTooManyRequests(HttpServletResponse response, String key, RateLimitPolicy policy) throws IOException {
        Long retryAfterSeconds = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            response.setHeader("Retry-After", retryAfterSeconds.toString());
        }
        objectMapper.writeValue(
            response.getOutputStream(),
            ApiResponse.failure("Too many requests", List.of("Rate limit exceeded. Please try again later."))
        );
    }

    private record RateLimitPolicy(String keyPrefix, int limit, Duration window) {
        private RateLimitPolicy {
            Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be positive");
            }
            Objects.requireNonNull(window, "window must not be null");
        }
    }
}

