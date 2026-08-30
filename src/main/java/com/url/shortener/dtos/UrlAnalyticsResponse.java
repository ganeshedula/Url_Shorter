package com.url.shortener.dtos;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
public class UrlAnalyticsResponse {
    private final UUID id;
    private final String originalUrl;
    private final String shortCode;
    private final String shortUrl;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime expirationDate;
    private final OffsetDateTime lastAccessedAt;
    private final long clickCount;
    private final List<DailyClickDto> dailyClicks;
    private final List<LocationAnalyticsDto> topLocations;
    private final List<ClickEventDto> recentClicks;
}
