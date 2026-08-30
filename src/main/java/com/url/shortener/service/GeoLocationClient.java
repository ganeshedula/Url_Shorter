package com.url.shortener.service;

import java.util.Optional;

public interface GeoLocationClient {
    Optional<GeoLocation> lookup(String ipAddress);
}
