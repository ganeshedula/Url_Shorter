package com.url.shortener.dtos;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LocationAnalyticsDto {
    private final String country;
    private final String countryCode;
    private final String region;
    private final String city;
    private final Double latitude;
    private final Double longitude;
    private final String timezone;
    private final long clicks;
}
