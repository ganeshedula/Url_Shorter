package com.url.shortener.util;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ClientInfo {
    private final String browser;
    private final String operatingSystem;
    private final String ipAddress;
    private final String country;
    private final String countryCode;
    private final String region;
    private final String city;
    private final String timezone;
    private final Double latitude;
    private final Double longitude;
    private final String userAgent;
    private final String deviceInfo;
}
