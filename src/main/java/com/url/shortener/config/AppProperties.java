package com.url.shortener.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    @NotBlank
    private String baseUrl;

    private Cors cors = new Cors();
    private Jwt jwt = new Jwt();
    private ClientIp clientIp = new ClientIp();
    private GeoIp geoIp = new GeoIp();

    @Getter
    @Setter
    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:3000", "http://localhost:8080");
    }

    @Getter
    @Setter
    public static class Jwt {
        @NotBlank
        private String secret;
        private Duration accessTokenExpiration = Duration.ofMinutes(15);
        private Duration refreshTokenExpiration = Duration.ofDays(7);
    }

    @Getter
    @Setter
    public static class ClientIp {
        private boolean trustForwardHeaders = false;
        private List<String> trustedProxies = List.of("127.0.0.1", "::1");
    }

    @Getter
    @Setter
    public static class GeoIp {
        private boolean enabled = true;
        private String endpointTemplate = "https://ipapi.co/%s/json/";
        private Duration connectTimeout = Duration.ofMillis(500);
        private Duration readTimeout = Duration.ofMillis(1200);
        private Duration cacheTtl = Duration.ofHours(6);
    }
}
