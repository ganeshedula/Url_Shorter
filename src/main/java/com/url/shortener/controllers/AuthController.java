package com.url.shortener.controllers;

import com.url.shortener.dtos.ApiResponse;
import com.url.shortener.dtos.AuthResponse;
import com.url.shortener.dtos.LoginRequest;
import com.url.shortener.dtos.RefreshTokenRequest;
import com.url.shortener.dtos.RegisterRequest;
import com.url.shortener.dtos.UserResponse;
import com.url.shortener.config.AppProperties;
import com.url.shortener.service.AuthService;
import com.url.shortener.service.GoogleOAuthService;
import com.url.shortener.util.ClientInfoExtractor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService authService;
    private final ClientInfoExtractor clientInfoExtractor;
    private final GoogleOAuthService googleOAuthService;
    private final AppProperties appProperties;

    public AuthController(
        AuthService authService,
        ClientInfoExtractor clientInfoExtractor,
        GoogleOAuthService googleOAuthService,
        AppProperties appProperties
    ) {
        this.authService = authService;
        this.clientInfoExtractor = clientInfoExtractor;
        this.googleOAuthService = googleOAuthService;
        this.appProperties = appProperties;
    }

    @PostMapping("/register")
    @Operation(summary = "Register a new user")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
        @Valid @RequestBody RegisterRequest request,
        HttpServletRequest httpServletRequest
    ) {
        AuthResponse response = authService.register(request, clientInfoExtractor.extract(httpServletRequest));
        return ResponseEntity.ok(ApiResponse.success("User registered successfully", response));
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate a user and issue JWT tokens")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
        @Valid @RequestBody LoginRequest request,
        HttpServletRequest httpServletRequest
    ) {
        AuthResponse response = authService.login(request, clientInfoExtractor.extract(httpServletRequest));
        return ResponseEntity.ok(ApiResponse.success("Login successful", response));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh access token and rotate refresh token")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
        @Valid @RequestBody RefreshTokenRequest request,
        HttpServletRequest httpServletRequest
    ) {
        AuthResponse response = authService.refresh(request, clientInfoExtractor.extract(httpServletRequest));
        return ResponseEntity.ok(ApiResponse.success("Token refreshed successfully", response));
    }

    @GetMapping("/google")
    @Operation(summary = "Begin Google OAuth sign-in")
    public ResponseEntity<Void> google() {
        return redirect(googleOAuthService.createAuthorizationUrl());
    }

    @GetMapping("/google/callback")
    @Operation(summary = "Complete Google OAuth sign-in")
    public ResponseEntity<Void> googleCallback(
        @org.springframework.web.bind.annotation.RequestParam(required = false) String code,
        @org.springframework.web.bind.annotation.RequestParam(required = false) String state,
        @org.springframework.web.bind.annotation.RequestParam(required = false) String error,
        HttpServletRequest request
    ) {
        if (error != null) {
            return redirect(frontendCallback("oauthError=google_sign_in_cancelled"));
        }
        try {
            AuthResponse response = googleOAuthService.handleCallback(code, state, clientInfoExtractor.extract(request));
            String fragment = "accessToken=" + encode(response.getAccessToken())
                + "&refreshToken=" + encode(response.getRefreshToken());
            return redirect(frontendCallback(fragment));
        } catch (RuntimeException exception) {
            return redirect(frontendCallback("oauthError=google_sign_in_failed"));
        }
    }

    @PostMapping("/logout")
    @Operation(summary = "Logout current session")
    public ResponseEntity<ApiResponse<Void>> logout(
        @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
        @Valid @RequestBody(required = false) RefreshTokenRequest request
    ) {
        authService.logout(extractAccessToken(authorizationHeader), request == null ? null : request.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.success("Logout successful", null));
    }

    @PostMapping("/logout-all")
    @Operation(summary = "Logout all sessions for the current user")
    public ResponseEntity<ApiResponse<Void>> logoutAll(
        @RequestHeader("Authorization") String authorizationHeader
    ) {
        authService.logoutAll(extractAccessToken(authorizationHeader));
        return ResponseEntity.ok(ApiResponse.success("All sessions logged out successfully", null));
    }

    @GetMapping("/me")
    @Operation(summary = "Get current authenticated user")
    public ResponseEntity<ApiResponse<UserResponse>> me() {
        return ResponseEntity.ok(ApiResponse.success("Current user fetched successfully", authService.currentUser()));
    }

    private String extractAccessToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        return authorizationHeader.substring(7);
    }

    private ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }

    private String frontendCallback(String fragment) {
        String frontendUrl = appProperties.getOauth().getGoogle().getFrontendUrl().replaceAll("/+$", "");
        if (!frontendUrl.startsWith("http://") && !frontendUrl.startsWith("https://")) {
            throw new IllegalStateException("FRONTEND_URL must be an absolute HTTP(S) URL");
        }
        return frontendUrl + "/auth/google/callback#" + fragment;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
