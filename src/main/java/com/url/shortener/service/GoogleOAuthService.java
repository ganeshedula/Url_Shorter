package com.url.shortener.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.url.shortener.config.AppProperties;
import com.url.shortener.dtos.AuthResponse;
import com.url.shortener.exception.BadRequestException;
import com.url.shortener.util.ClientInfo;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

@Service
public class GoogleOAuthService {

    private static final String GOOGLE_AUTHORIZATION_URI = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String GOOGLE_TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String GOOGLE_JWK_SET_URI = "https://www.googleapis.com/oauth2/v3/certs";
    private static final String GOOGLE_ISSUER = "https://accounts.google.com";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private final AppProperties appProperties;
    private final RedisSessionService redisSessionService;
    private final AuthService authService;
    private final RestClient restClient;

    public GoogleOAuthService(
        AppProperties appProperties,
        RedisSessionService redisSessionService,
        AuthService authService,
        RestClient.Builder restClientBuilder
    ) {
        this.appProperties = appProperties;
        this.redisSessionService = redisSessionService;
        this.authService = authService;
        this.restClient = restClientBuilder.build();
    }

    public String createAuthorizationUrl() {
        AppProperties.Google google = google();
        String state = randomUrlSafeValue();
        String codeVerifier = randomUrlSafeValue();
        redisSessionService.storeGoogleOAuthState(state, codeVerifier, STATE_TTL);

        return UriComponentsBuilder.fromUriString(GOOGLE_AUTHORIZATION_URI)
            .queryParam("client_id", google.getClientId())
            .queryParam("redirect_uri", google.getRedirectUri())
            .queryParam("response_type", "code")
            .queryParam("scope", "openid email profile")
            .queryParam("state", state)
            .queryParam("code_challenge", sha256Base64Url(codeVerifier))
            .queryParam("code_challenge_method", "S256")
            .build()
            .encode()
            .toUriString();
    }

    public AuthResponse handleCallback(String code, String state, ClientInfo clientInfo) {
        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            throw new BadRequestException("Google sign-in could not be verified");
        }

        String codeVerifier = redisSessionService.consumeGoogleOAuthState(state)
            .orElseThrow(() -> new BadRequestException("Google sign-in session expired or is invalid"));
        AppProperties.Google google = google();

        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", google.getClientId());
        form.add("client_secret", google.getClientSecret());
        form.add("redirect_uri", google.getRedirectUri());
        form.add("grant_type", "authorization_code");
        form.add("code_verifier", codeVerifier);

        GoogleTokenResponse tokenResponse;
        try {
            tokenResponse = restClient.post()
                .uri(GOOGLE_TOKEN_URI)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(GoogleTokenResponse.class);
        } catch (RuntimeException exception) {
            throw new BadRequestException("Google could not complete sign-in");
        }

        if (tokenResponse == null || tokenResponse.idToken() == null || tokenResponse.idToken().isBlank()) {
            throw new BadRequestException("Google did not return a valid identity");
        }

        Jwt identity = jwtDecoder(google.getClientId()).decode(tokenResponse.idToken());
        String email = identity.getClaimAsString("email");
        if (!Boolean.TRUE.equals(identity.getClaim("email_verified")) || email == null || email.isBlank()) {
            throw new BadRequestException("A verified Google email address is required");
        }

        return authService.loginWithGoogle(email, identity.getClaimAsString("name"), clientInfo);
    }

    private JwtDecoder jwtDecoder(String clientId) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(GOOGLE_JWK_SET_URI).build();
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience().contains(clientId)
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid Google token audience", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(GOOGLE_ISSUER), audienceValidator
        ));
        return decoder;
    }

    private AppProperties.Google google() {
        AppProperties.Google google = appProperties.getOauth().getGoogle();
        if (google.getClientId().isBlank() || google.getClientSecret().isBlank() || google.getRedirectUri().isBlank()) {
            throw new BadRequestException("Google sign-in is not configured");
        }
        return google;
    }

    private String randomUrlSafeValue() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256Base64Url(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create OAuth challenge", exception);
        }
    }

    private record GoogleTokenResponse(@JsonProperty("id_token") String idToken) { }
}
