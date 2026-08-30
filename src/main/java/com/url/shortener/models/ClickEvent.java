package com.url.shortener.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(
    name = "click_events",
    indexes = {
        @Index(name = "idx_click_event_url_accessed_at", columnList = "url_mapping_id,accessed_at"),
        @Index(name = "idx_click_event_ip", columnList = "ip_address")
    }
)
public class ClickEvent {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "accessed_at", nullable = false)
    private OffsetDateTime accessedAt;

    @Column(name = "browser", length = 100)
    private String browser;

    @Column(name = "operating_system", length = 100)
    private String operatingSystem;

    @Column(name = "ip_address", length = 100)
    private String ipAddress;

    @Column(name = "country", length = 100)
    private String country;

    @Column(name = "country_code", length = 10)
    private String countryCode;

    @Column(name = "region", length = 120)
    private String region;

    @Column(name = "city", length = 120)
    private String city;

    @Column(name = "timezone", length = 120)
    private String timezone;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "url_mapping_id", nullable = false)
    private UrlMapping urlMapping;
}
