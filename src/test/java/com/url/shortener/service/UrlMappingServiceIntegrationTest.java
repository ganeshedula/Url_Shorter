package com.url.shortener.service;

import com.url.shortener.config.AppProperties;
import com.url.shortener.dtos.ClickEventDto;
import com.url.shortener.dtos.PagedResponse;
import com.url.shortener.dtos.ShortUrlResponse;
import com.url.shortener.dtos.UrlAnalyticsResponse;
import com.url.shortener.models.Role;
import com.url.shortener.models.User;
import com.url.shortener.repo.UserRepository;
import com.url.shortener.util.ClientInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;

@SpringBootTest
@ActiveProfiles("test")
class UrlMappingServiceIntegrationTest {

    @Autowired
    private UrlMappingService urlMappingService;

    @Autowired
    private UserRepository userRepository;

    @MockBean
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @MockBean
    private GeoLocationClient geoLocationClient;

    @MockBean
    private ApplicationEventPublisher applicationEventPublisher;

    private User user;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        doNothing().when(applicationEventPublisher).publishEvent(any());
        org.mockito.Mockito.when(geoLocationClient.lookup(any())).thenReturn(Optional.empty());
        user = new User();
        user.setEmail("owner@example.com");
        user.setPassword("secret");
        user.setRole(Role.ROLE_USER);
        user = userRepository.save(user);
    }

    @Test
    void createShortUrlPersistsUrlAndReturnsPublicLink() {
        ShortUrlResponse response = urlMappingService.createShortUrl("https://google.com", OffsetDateTime.now().plusDays(1), user);

        assertThat(response.getId()).isNotNull();
        assertThat(response.getShortCode()).hasSize(8);
        assertThat(response.getShortUrl()).contains("/" + response.getShortCode());
        assertThat(response.getOriginalUrl()).isEqualTo("https://google.com");
    }

    @Test
    void getUrlsByUserReturnsPaginatedResults() {
        urlMappingService.createShortUrl("https://example.com/one", null, user);
        urlMappingService.createShortUrl("https://example.com/two", null, user);

        PagedResponse<ShortUrlResponse> response = urlMappingService.getUrlsByUser(user, 0, 10, "createdAt", "desc", "example");

        assertThat(response.getContent()).hasSize(2);
        assertThat(response.getTotalElements()).isEqualTo(2);
    }

    @Test
    void resolveShortCodeRecordsApproximateLocationFieldsInAnalytics() {
        ShortUrlResponse response = urlMappingService.createShortUrl("https://example.com/location", null, user);
        ClientInfo clientInfo = ClientInfo.builder()
            .browser("Chrome")
            .operatingSystem("macOS")
            .ipAddress("8.8.8.8")
            .country("India")
            .countryCode("IN")
            .region("Tamil Nadu")
            .city("Chennai")
            .timezone("Asia/Kolkata")
            .latitude(13.0827)
            .longitude(80.2707)
            .userAgent("Chrome")
            .deviceInfo("Chrome on macOS")
            .build();

        String resolvedUrl = urlMappingService.resolveShortCode(response.getShortCode(), clientInfo);
        UrlAnalyticsResponse analyticsResponse = urlMappingService.getUrlAnalytics(response.getId(), user);

        assertThat(resolvedUrl).isEqualTo("https://example.com/location");
        assertThat(analyticsResponse.getClickCount()).isEqualTo(1);
        assertThat(analyticsResponse.getTopLocations()).hasSize(1);
        ClickEventDto clickEvent = analyticsResponse.getRecentClicks().getFirst();
        assertThat(clickEvent.getCity()).isEqualTo("Chennai");
        assertThat(clickEvent.getCountryCode()).isEqualTo("IN");
    }
}
