package com.url.shortener.service;

import com.url.shortener.config.AppProperties;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GeoLocationService {

    private final GeoLocationClient geoLocationClient;
    private final Duration cacheTtl;
    private final ConcurrentHashMap<String, CachedGeoLocation> cache = new ConcurrentHashMap<>();

    public GeoLocationService(GeoLocationClient geoLocationClient, AppProperties appProperties) {
        this.geoLocationClient = geoLocationClient;
        this.cacheTtl = appProperties.getGeoIp().getCacheTtl();
    }

    public Optional<GeoLocation> lookup(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank() || isNonPublicAddress(ipAddress)) {
            return Optional.empty();
        }

        CachedGeoLocation cachedGeoLocation = cache.get(ipAddress);
        if (cachedGeoLocation != null && cachedGeoLocation.expiresAt().isAfter(Instant.now())) {
            return Optional.of(cachedGeoLocation.geoLocation());
        }

        Optional<GeoLocation> resolvedLocation = geoLocationClient.lookup(ipAddress);
        resolvedLocation.ifPresent(location -> cache.put(ipAddress, new CachedGeoLocation(location, Instant.now().plus(cacheTtl))));
        return resolvedLocation;
    }

    private boolean isNonPublicAddress(String ipAddress) {
        try {
            InetAddress address = InetAddress.getByName(ipAddress);
            return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress();
        } catch (UnknownHostException exception) {
            return true;
        }
    }

    private record CachedGeoLocation(GeoLocation geoLocation, Instant expiresAt) {
    }
}
