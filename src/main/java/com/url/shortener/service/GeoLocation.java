package com.url.shortener.service;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GeoLocation {
    private final String country;
    private final String countryCode;
    private final String region;
    private final String city;
    private final String timezone;
    private final Double latitude;
    private final Double longitude;

    public static GeoLocation unavailable() {
        return GeoLocation.builder()
            .country("Unknown")
            .countryCode("Unknown")
            .region("Unknown")
            .city("Unknown")
            .timezone("Unknown")
            .build();
    }
}
