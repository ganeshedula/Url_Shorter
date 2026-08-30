package com.url.shortener.service;

import com.url.shortener.models.ClickEvent;
import com.url.shortener.repo.ClickEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ClickAnalyticsEnrichmentListener {

    private static final Logger log = LoggerFactory.getLogger(ClickAnalyticsEnrichmentListener.class);

    private final GeoLocationService geoLocationService;
    private final ClickEventRepository clickEventRepository;

    public ClickAnalyticsEnrichmentListener(GeoLocationService geoLocationService, ClickEventRepository clickEventRepository) {
        this.geoLocationService = geoLocationService;
        this.clickEventRepository = clickEventRepository;
    }

    @Async("clickAnalyticsTaskExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(ClickEventRecorded event) {
        try {
            geoLocationService.lookup(event.ipAddress())
                .ifPresent(location -> clickEventRepository.findById(event.clickEventId()).ifPresent(clickEvent -> updateClickEvent(clickEvent, location)));
        } catch (RuntimeException exception) {
            log.debug("Click analytics enrichment failed for event {}: {}", event.clickEventId(), exception.getMessage());
        }
    }

    private void updateClickEvent(ClickEvent clickEvent, GeoLocation location) {
        clickEvent.setCountry(location.getCountry());
        clickEvent.setCountryCode(location.getCountryCode());
        clickEvent.setRegion(location.getRegion());
        clickEvent.setCity(location.getCity());
        clickEvent.setTimezone(location.getTimezone());
        clickEvent.setLatitude(location.getLatitude());
        clickEvent.setLongitude(location.getLongitude());
        clickEventRepository.save(clickEvent);
    }
}
