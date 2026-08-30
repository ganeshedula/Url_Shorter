package com.url.shortener.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.url.shortener.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

@Component
public class IpApiGeoLocationClient implements GeoLocationClient {

    private static final Logger log = LoggerFactory.getLogger(IpApiGeoLocationClient.class);

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public IpApiGeoLocationClient(AppProperties appProperties, ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(appProperties.getGeoIp().getConnectTimeout())
            .build();
    }

    @Override
    public Optional<GeoLocation> lookup(String ipAddress) {
        if (!appProperties.getGeoIp().isEnabled()) {
            return Optional.empty();
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(buildLookupUri(ipAddress))
                .timeout(appProperties.getGeoIp().getReadTimeout())
                .header("Accept", "application/json")
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.debug("Geo IP lookup returned status {} for {}", response.statusCode(), ipAddress);
                return Optional.empty();
            }

            ProviderResponse providerResponse = objectMapper.readValue(response.body(), ProviderResponse.class);
            return Optional.of(GeoLocation.builder()
                .country(defaultValue(providerResponse.countryName))
                .countryCode(defaultValue(providerResponse.countryCode))
                .region(defaultValue(providerResponse.region))
                .city(defaultValue(providerResponse.city))
                .timezone(defaultValue(providerResponse.timezone))
                .latitude(providerResponse.latitude)
                .longitude(providerResponse.longitude)
                .build());
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("Geo IP lookup failed for {}: {}", ipAddress, exception.getMessage());
            return Optional.empty();
        }
    }

    private URI buildLookupUri(String ipAddress) {
        String encodedIpAddress = URLEncoder.encode(ipAddress, StandardCharsets.UTF_8);
        return URI.create(appProperties.getGeoIp().getEndpointTemplate().formatted(encodedIpAddress));
    }

    private String defaultValue(String value) {
        return value == null || value.isBlank() ? "Unknown" : value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ProviderResponse {
        @JsonProperty("country_name")
        private String countryName;

        @JsonProperty("country_code")
        private String countryCode;

        @JsonProperty("region")
        private String region;

        @JsonProperty("city")
        private String city;

        @JsonProperty("timezone")
        private String timezone;

        @JsonProperty("latitude")
        private Double latitude;

        @JsonProperty("longitude")
        private Double longitude;
    }
}
