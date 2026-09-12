package com.url.shortener.service;

import com.url.shortener.config.AppProperties;
import com.url.shortener.dtos.ClickEventDto;
import com.url.shortener.dtos.DailyClickDto;
import com.url.shortener.dtos.LocationAnalyticsDto;
import com.url.shortener.dtos.PagedResponse;
import com.url.shortener.dtos.ShortUrlResponse;
import com.url.shortener.dtos.UpdateUrlRequest;
import com.url.shortener.dtos.UrlAnalyticsResponse;
import com.url.shortener.exception.BadRequestException;
import com.url.shortener.exception.UrlNotFoundException;
import com.url.shortener.models.ClickEvent;
import com.url.shortener.models.UrlMapping;
import com.url.shortener.models.User;
import com.url.shortener.repo.ClickEventRepository;
import com.url.shortener.repo.UrlMappingRepository;
import com.url.shortener.util.ClientInfo;
import com.url.shortener.util.ShortCodeGenerator;
import org.springframework.context.ApplicationEventPublisher;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class UrlMappingService {

    private final UrlMappingRepository urlMappingRepository;
    private final ClickEventRepository clickEventRepository;
    private final ShortCodeGenerator shortCodeGenerator;
    private final AppProperties appProperties;
    private final ApplicationEventPublisher applicationEventPublisher;

    public UrlMappingService(
        UrlMappingRepository urlMappingRepository,
        ClickEventRepository clickEventRepository,
        ShortCodeGenerator shortCodeGenerator,
        AppProperties appProperties,
        ApplicationEventPublisher applicationEventPublisher
    ) {
        this.urlMappingRepository = urlMappingRepository;
        this.clickEventRepository = clickEventRepository;
        this.shortCodeGenerator = shortCodeGenerator;
        this.appProperties = appProperties;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Transactional
    public ShortUrlResponse createShortUrl(String originalUrl, OffsetDateTime expirationDate, User user) {
        validateUrl(originalUrl);
        if (expirationDate != null && expirationDate.isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new BadRequestException("Expiration date must be in the future");
        }
        UrlMapping urlMapping = new UrlMapping();
        urlMapping.setOriginalUrl(originalUrl.trim());
        urlMapping.setShortCode(generateUniqueShortCode());
        urlMapping.setExpirationDate(expirationDate);
        urlMapping.setUser(user);
        return toShortUrlResponse(urlMappingRepository.save(urlMapping));
    }

    @Transactional(readOnly = true)
    public PagedResponse<ShortUrlResponse> getUrlsByUser(User user, int page, int size, String sortBy, String direction, String search) {
        Sort sort = Sort.by(Sort.Direction.fromString(direction), resolveSortField(sortBy));
        Pageable pageable = PageRequest.of(page, size, sort);

        Specification<UrlMapping> specification = (root, query, criteriaBuilder) -> {
            Predicate byUser = criteriaBuilder.equal(root.get("user"), user);
            if (search == null || search.isBlank()) {
                return byUser;
            }
            String likeValue = "%" + search.toLowerCase() + "%";
            Predicate byOriginalUrl = criteriaBuilder.like(criteriaBuilder.lower(root.get("originalUrl")), likeValue);
            Predicate byShortCode = criteriaBuilder.like(criteriaBuilder.lower(root.get("shortCode")), likeValue);
            return criteriaBuilder.and(byUser, criteriaBuilder.or(byOriginalUrl, byShortCode));
        };

        Page<UrlMapping> resultPage = urlMappingRepository.findAll(specification, pageable);
        return PagedResponse.<ShortUrlResponse>builder()
            .content(resultPage.getContent().stream().map(this::toShortUrlResponse).toList())
            .page(resultPage.getNumber())
            .size(resultPage.getSize())
            .totalElements(resultPage.getTotalElements())
            .totalPages(resultPage.getTotalPages())
            .first(resultPage.isFirst())
            .last(resultPage.isLast())
            .sort(sortBy + "," + direction)
            .search(search)
            .build();
    }

    @Transactional(readOnly = true)
    public UrlAnalyticsResponse getUrlAnalytics(UUID id, User user) {
        UrlMapping urlMapping = getOwnedUrl(id, user);

        List<Object[]> dailyRows = clickEventRepository.findDailyClickCountsRaw(id);
        List<DailyClickDto> dailyClicks = dailyRows.stream()
            .map(row -> DailyClickDto.builder()
                .date(parseLocalDate(row[0]))
                .count(((Number) row[1]).longValue())
                .build())
            .toList();

        List<Object[]> locationRows = clickEventRepository.findTopLocationsRaw(id, PageRequest.of(0, 10));
        List<LocationAnalyticsDto> topLocations = locationRows.stream()
            .map(row -> LocationAnalyticsDto.builder()
                .country(normalizeLocationValue((String) row[0]))
                .countryCode(normalizeLocationValue((String) row[1]))
                .region(normalizeLocationValue((String) row[2]))
                .city(normalizeLocationValue((String) row[3]))
                .latitude((Double) row[4])
                .longitude((Double) row[5])
                .timezone(normalizeLocationValue((String) row[6]))
                .clicks(((Number) row[7]).longValue())
                .build())
            .toList();

        List<ClickEventDto> recentClicks = clickEventRepository.findTop20ByUrlMapping_IdOrderByAccessedAtDesc(id).stream()
            .map(this::toClickEventDto)
            .toList();

        return UrlAnalyticsResponse.builder()
            .id(urlMapping.getId())
            .originalUrl(urlMapping.getOriginalUrl())
            .shortCode(urlMapping.getShortCode())
            .shortUrl(buildShortUrl(urlMapping.getShortCode()))
            .createdAt(OffsetDateTime.ofInstant(urlMapping.getCreatedAt(), ZoneOffset.UTC))
            .expirationDate(urlMapping.getExpirationDate())
            .lastAccessedAt(urlMapping.getLastAccessedAt())
            .clickCount(urlMapping.getClickCount())
            .dailyClicks(dailyClicks)
            .topLocations(topLocations)
            .recentClicks(recentClicks)
            .build();
    }

    @Transactional
    public ShortUrlResponse updateUrl(UUID id, UpdateUrlRequest request, User user) {
        UrlMapping urlMapping = getOwnedUrl(id, user);
        if (request.getUrl() != null && !request.getUrl().isBlank()) {
            validateUrl(request.getUrl());
            urlMapping.setOriginalUrl(request.getUrl().trim());
        }
        if (request.getExpirationDate() != null) {
            if (request.getExpirationDate().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
                throw new BadRequestException("Expiration date must be in the future");
            }
            urlMapping.setExpirationDate(request.getExpirationDate());
        }
        if (request.getActive() != null) {
            urlMapping.setActive(request.getActive());
        }
        return toShortUrlResponse(urlMappingRepository.save(urlMapping));
    }

    @Transactional
    public void deleteUrl(UUID id, User user) {
        UrlMapping urlMapping = getOwnedUrl(id, user);
        urlMappingRepository.delete(urlMapping);
    }

    @Transactional
    public String resolveShortCode(String shortCode, ClientInfo clientInfo) {
        UrlMapping urlMapping = urlMappingRepository.findByShortCode(shortCode)
            .orElseThrow(() -> new UrlNotFoundException("Short URL not found"));

        if (!urlMapping.isActive()) {
            throw new BadRequestException("Short URL is inactive");
        }
        if (urlMapping.getExpirationDate() != null && urlMapping.getExpirationDate().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new BadRequestException("Short URL has expired");
        }

        ClickEvent event = new ClickEvent();
        event.setUrlMapping(urlMapping);
        event.setAccessedAt(OffsetDateTime.now(ZoneOffset.UTC));
        event.setBrowser(clientInfo.getBrowser());
        event.setOperatingSystem(clientInfo.getOperatingSystem());
        event.setIpAddress(clientInfo.getIpAddress());
        event.setCountry(clientInfo.getCountry());
        event.setCountryCode(clientInfo.getCountryCode());
        event.setRegion(clientInfo.getRegion());
        event.setCity(clientInfo.getCity());
        event.setTimezone(clientInfo.getTimezone());
        event.setLatitude(clientInfo.getLatitude());
        event.setLongitude(clientInfo.getLongitude());
        event.setUserAgent(clientInfo.getUserAgent());

        urlMapping.setClickCount(urlMapping.getClickCount() + 1);
        urlMapping.setLastAccessedAt(event.getAccessedAt());
        clickEventRepository.save(event);
        urlMappingRepository.save(urlMapping);
        applicationEventPublisher.publishEvent(new ClickEventRecorded(event.getId(), clientInfo.getIpAddress()));
        return urlMapping.getOriginalUrl();
    }

    private UrlMapping getOwnedUrl(UUID id, User user) {
        return urlMappingRepository.findById(id)
            .filter(urlMapping -> urlMapping.getUser().getId().equals(user.getId()))
            .orElseThrow(() -> new UrlNotFoundException("URL mapping not found"));
    }

    private ShortUrlResponse toShortUrlResponse(UrlMapping urlMapping) {
        return ShortUrlResponse.builder()
            .id(urlMapping.getId())
            .shortCode(urlMapping.getShortCode())
            .shortUrl(buildShortUrl(urlMapping.getShortCode()))
            .originalUrl(urlMapping.getOriginalUrl())
            .clickCount(urlMapping.getClickCount())
            .createdAt(OffsetDateTime.ofInstant(urlMapping.getCreatedAt(), ZoneOffset.UTC))
            .updatedAt(OffsetDateTime.ofInstant(urlMapping.getUpdatedAt(), ZoneOffset.UTC))
            .expirationDate(urlMapping.getExpirationDate())
            .lastAccessedAt(urlMapping.getLastAccessedAt())
            .active(urlMapping.isActive())
            .build();
    }

    private ClickEventDto toClickEventDto(ClickEvent event) {
        return ClickEventDto.builder()
            .accessedAt(event.getAccessedAt())
            .browser(event.getBrowser())
            .operatingSystem(event.getOperatingSystem())
            .ipAddress(event.getIpAddress())
            .country(event.getCountry())
            .countryCode(event.getCountryCode())
            .region(event.getRegion())
            .city(event.getCity())
            .timezone(event.getTimezone())
            .latitude(event.getLatitude())
            .longitude(event.getLongitude())
            .build();
    }

    private String buildShortUrl(String shortCode) {
        return appProperties.getBaseUrl().replaceAll("/$", "") + "/" + shortCode;
    }

    private String generateUniqueShortCode() {
        String shortCode = shortCodeGenerator.generate();
        while (urlMappingRepository.existsByShortCode(shortCode)) {
            shortCode = shortCodeGenerator.generate();
        }
        return shortCode;
    }

    private static final int MAX_URL_LENGTH = 2048;

    private void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new BadRequestException("URL must be absolute and valid");
        }
        String trimmed = url.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new BadRequestException("URL must not exceed " + MAX_URL_LENGTH + " characters");
        }
        if (trimmed.contains("\r") || trimmed.contains("\n")) {
            throw new BadRequestException("URL contains invalid line break characters");
        }
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new BadRequestException("URL scheme must be http or https");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new BadRequestException("URL must be absolute and valid");
            }
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("URL must be absolute and valid");
        }
    }

    private String resolveSortField(String sortBy) {
        return switch (sortBy) {
            case "originalUrl" -> "originalUrl";
            case "clickCount" -> "clickCount";
            case "updatedAt" -> "updatedAt";
            default -> "createdAt";
        };
    }

    private String normalizeLocationValue(String value) {
        return (value == null || value.isBlank()) ? "Unknown" : value;
    }

    private LocalDate parseLocalDate(Object value) {
        if (value instanceof LocalDate ld) {
            return ld;
        }
        if (value instanceof java.sql.Date sd) {
            return sd.toLocalDate();
        }
        return LocalDate.parse(value.toString());
    }
}
