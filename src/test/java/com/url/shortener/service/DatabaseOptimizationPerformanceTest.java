package com.url.shortener.service;

import com.url.shortener.dtos.DailyClickDto;
import com.url.shortener.dtos.LocationAnalyticsDto;
import com.url.shortener.dtos.UrlAnalyticsResponse;
import com.url.shortener.models.ClickEvent;
import com.url.shortener.models.Role;
import com.url.shortener.models.UrlMapping;
import com.url.shortener.models.User;
import com.url.shortener.repo.ClickEventRepository;
import com.url.shortener.repo.UrlMappingRepository;
import com.url.shortener.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class DatabaseOptimizationPerformanceTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UrlMappingRepository urlMappingRepository;

    @Autowired
    private ClickEventRepository clickEventRepository;

    @Autowired
    private UrlMappingService urlMappingService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private GeoLocationClient geoLocationClient;

    @MockBean
    private RedisSessionService redisSessionService;

    @MockBean
    private EmailService emailService;

    @MockBean
    private StringRedisTemplate redisTemplate;

    private User testUser;
    private UrlMapping testMapping;

    @BeforeEach
    void setUp() {
        clickEventRepository.deleteAll();
        urlMappingRepository.deleteAll();
        userRepository.deleteAll();

        String uniqueEmail = "db-perf-" + java.util.UUID.randomUUID() + "@example.com";
        testUser = new User();
        testUser.setEmail(uniqueEmail);
        testUser.setUsername("dbperf");
        testUser.setPassword(passwordEncoder.encode("password123"));
        testUser.setRole(Role.ROLE_USER);
        testUser.setEmailVerified(true);
        testUser = userRepository.save(testUser);

        testMapping = new UrlMapping();
        testMapping.setOriginalUrl("https://example.com/analytics-target");
        testMapping.setShortCode("dbperf" + System.currentTimeMillis() % 10000);
        testMapping.setUser(testUser);
        testMapping.setClickCount(0);
        testMapping = urlMappingRepository.save(testMapping);
    }

    @Test
    @DisplayName("Verify ClickEventRepository aggregation queries group by date and location at the database level")
    void aggregationQueries_performGroupingInDatabase() {
        populateClickEvents(300);

        // 1. Raw daily click counts aggregation query
        List<Object[]> dailyRows = clickEventRepository.findDailyClickCountsRaw(testMapping.getId());
        assertThat(dailyRows).isNotEmpty();
        long dailyTotal = dailyRows.stream()
            .mapToLong(row -> ((Number) row[1]).longValue())
            .sum();
        assertThat(dailyTotal).isEqualTo(300);

        // 2. Raw top locations aggregation query with limit
        List<Object[]> topLocationRows = clickEventRepository.findTopLocationsRaw(testMapping.getId(), PageRequest.of(0, 5));
        assertThat(topLocationRows).hasSize(5);

        // First location should have the highest click count (US: 120 clicks)
        String topCountry = (String) topLocationRows.get(0)[0];
        long topCountryClicks = ((Number) topLocationRows.get(0)[7]).longValue();
        assertThat(topCountry).isEqualTo("United States");
        assertThat(topCountryClicks).isEqualTo(120);

        // Second location (GB: 80 clicks)
        String secondCountry = (String) topLocationRows.get(1)[0];
        long secondCountryClicks = ((Number) topLocationRows.get(1)[7]).longValue();
        assertThat(secondCountry).isEqualTo("United Kingdom");
        assertThat(secondCountryClicks).isEqualTo(80);
    }

    @Test
    @DisplayName("Verify getUrlAnalytics computes metrics efficiently without loading all event rows into JVM memory")
    void getUrlAnalytics_highVolumeAggregationSucceeds() {
        populateClickEvents(500);

        long startNanos = System.nanoTime();
        UrlAnalyticsResponse response = urlMappingService.getUrlAnalytics(testMapping.getId(), testUser);
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(response).isNotNull();
        assertThat(response.getClickCount()).isEqualTo(500);
        assertThat(response.getDailyClicks()).isNotEmpty();

        long dailyClicksSum = response.getDailyClicks().stream()
            .mapToLong(DailyClickDto::getCount)
            .sum();
        assertThat(dailyClicksSum).isEqualTo(500);

        assertThat(response.getTopLocations()).isNotEmpty();
        LocationAnalyticsDto topLocation = response.getTopLocations().get(0);
        assertThat(topLocation.getCountry()).isEqualTo("United States");

        // Recent clicks is capped at 20 rows, preventing memory explosion
        assertThat(response.getRecentClicks()).hasSize(20);

        // Ensure calculation finished swiftly in database (under 1000ms in test environment)
        assertThat(durationMs).isLessThan(1000);
    }

    private void populateClickEvents(int totalClicks) {
        List<ClickEvent> batch = new ArrayList<>(totalClicks);
        OffsetDateTime baseTime = OffsetDateTime.now(ZoneOffset.UTC);

        // Distribute clicks across 5 days and 5 countries:
        // US: 40%, GB: 26.6%, IN: 20%, DE: 10%, CA: 3.4%
        for (int i = 0; i < totalClicks; i++) {
            ClickEvent event = new ClickEvent();
            event.setUrlMapping(testMapping);
            event.setAccessedAt(baseTime.minusDays(i % 5).minusHours(i % 12));
            event.setBrowser("Chrome");
            event.setOperatingSystem("macOS");
            event.setIpAddress("192.168.1." + (i % 250));

            if (i < totalClicks * 0.40) {
                event.setCountry("United States");
                event.setCountryCode("US");
                event.setRegion("California");
                event.setCity("San Francisco");
            } else if (i < totalClicks * 0.666) {
                event.setCountry("United Kingdom");
                event.setCountryCode("GB");
                event.setRegion("England");
                event.setCity("London");
            } else if (i < totalClicks * 0.866) {
                event.setCountry("India");
                event.setCountryCode("IN");
                event.setRegion("Karnataka");
                event.setCity("Bengaluru");
            } else if (i < totalClicks * 0.966) {
                event.setCountry("Germany");
                event.setCountryCode("DE");
                event.setRegion("Bavaria");
                event.setCity("Munich");
            } else {
                event.setCountry("Canada");
                event.setCountryCode("CA");
                event.setRegion("Ontario");
                event.setCity("Toronto");
            }

            batch.add(event);
        }

        clickEventRepository.saveAll(batch);
        testMapping.setClickCount(totalClicks);
        urlMappingRepository.save(testMapping);
    }
}
