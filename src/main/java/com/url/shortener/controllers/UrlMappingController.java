package com.url.shortener.controllers;

import com.url.shortener.dtos.ApiResponse;
import com.url.shortener.dtos.CreateShortUrlRequest;
import com.url.shortener.dtos.PagedResponse;
import com.url.shortener.dtos.ShortUrlResponse;
import com.url.shortener.dtos.UpdateUrlRequest;
import com.url.shortener.dtos.UrlAnalyticsResponse;
import com.url.shortener.models.User;
import com.url.shortener.service.UrlMappingService;
import com.url.shortener.service.UserService;
import com.url.shortener.util.ClientInfoExtractor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping
@Tag(name = "URL Shortener")
public class UrlMappingController {

    private final UrlMappingService urlMappingService;
    private final UserService userService;
    private final ClientInfoExtractor clientInfoExtractor;

    public UrlMappingController(
        UrlMappingService urlMappingService,
        UserService userService,
        ClientInfoExtractor clientInfoExtractor
    ) {
        this.urlMappingService = urlMappingService;
        this.userService = userService;
        this.clientInfoExtractor = clientInfoExtractor;
    }

    @PostMapping("/api/url")
    @Operation(summary = "Create a short URL")
    public ResponseEntity<ApiResponse<ShortUrlResponse>> createShortUrl(
        @Valid @RequestBody CreateShortUrlRequest request,
        Principal principal
    ) {
        User user = resolveUser(principal);
        ShortUrlResponse response = urlMappingService.createShortUrl(request.getUrl(), request.getExpirationDate(), user);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Short URL created successfully", response));
    }

    @GetMapping("/{shortCode}")
    @Operation(summary = "Redirect short code to original URL")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode, HttpServletRequest request) {
        String targetUrl = urlMappingService.resolveShortCode(shortCode, clientInfoExtractor.extract(request));
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, targetUrl)
            .build();
    }

    @GetMapping("/api/url/{id}")
    @Operation(summary = "Get URL analytics by ID")
    public ResponseEntity<ApiResponse<UrlAnalyticsResponse>> getUrlById(@PathVariable UUID id, Principal principal) {
        User user = resolveUser(principal);
        return ResponseEntity.ok(ApiResponse.success("URL analytics fetched successfully", urlMappingService.getUrlAnalytics(id, user)));
    }

    @PutMapping("/api/url/{id}")
    @Operation(summary = "Update an existing short URL")
    public ResponseEntity<ApiResponse<ShortUrlResponse>> updateUrl(
        @PathVariable UUID id,
        @Valid @RequestBody UpdateUrlRequest request,
        Principal principal
    ) {
        User user = resolveUser(principal);
        return ResponseEntity.ok(ApiResponse.success("Short URL updated successfully", urlMappingService.updateUrl(id, request, user)));
    }

    @DeleteMapping("/api/url/{id}")
    @Operation(summary = "Delete a short URL")
    public ResponseEntity<ApiResponse<Void>> deleteUrl(@PathVariable UUID id, Principal principal) {
        User user = resolveUser(principal);
        urlMappingService.deleteUrl(id, user);
        return ResponseEntity.ok(ApiResponse.success("Short URL deleted successfully", null));
    }

    @GetMapping("/api/url/my")
    @Operation(summary = "List the current user's short URLs")
    public ResponseEntity<ApiResponse<PagedResponse<ShortUrlResponse>>> getMyUrls(
        Principal principal,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(defaultValue = "createdAt") String sortBy,
        @RequestParam(defaultValue = "desc") String direction,
        @RequestParam(required = false) String search
    ) {
        User user = resolveUser(principal);
        return ResponseEntity.ok(ApiResponse.success(
            "Short URLs fetched successfully",
            urlMappingService.getUrlsByUser(user, page, size, sortBy, direction, search)
        ));
    }

    private User resolveUser(Principal principal) {
        if (principal instanceof org.springframework.security.core.Authentication authentication
            && authentication.getPrincipal() instanceof com.url.shortener.service.UserDetailsImpl userDetails
            && userDetails.getUser() != null) {
            return userDetails.getUser();
        }
        return userService.findByEmail(principal.getName());
    }
}
