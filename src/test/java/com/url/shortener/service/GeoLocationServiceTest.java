package com.url.shortener.service;

import com.url.shortener.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeoLocationServiceTest {

    @Test
    void skipsLookupForPrivateAddresses() {
        GeoLocationClient geoLocationClient = mock(GeoLocationClient.class);
        GeoLocationService geoLocationService = new GeoLocationService(geoLocationClient, new AppProperties());

        assertThat(geoLocationService.lookup("127.0.0.1")).isEmpty();
        verify(geoLocationClient, times(0)).lookup("127.0.0.1");
    }

    @Test
    void cachesSuccessfulLookups() {
        GeoLocationClient geoLocationClient = mock(GeoLocationClient.class);
        GeoLocation geoLocation = GeoLocation.builder().country("India").city("Chennai").build();
        when(geoLocationClient.lookup("8.8.8.8")).thenReturn(Optional.of(geoLocation));
        GeoLocationService geoLocationService = new GeoLocationService(geoLocationClient, new AppProperties());

        Optional<GeoLocation> firstLookup = geoLocationService.lookup("8.8.8.8");
        Optional<GeoLocation> secondLookup = geoLocationService.lookup("8.8.8.8");

        assertThat(firstLookup).contains(geoLocation);
        assertThat(secondLookup).contains(geoLocation);
        verify(geoLocationClient).lookup("8.8.8.8");
    }

    @Test
    void returnsEmptyWhenProviderFails() {
        GeoLocationClient geoLocationClient = mock(GeoLocationClient.class);
        when(geoLocationClient.lookup("1.1.1.1")).thenReturn(Optional.empty());
        GeoLocationService geoLocationService = new GeoLocationService(geoLocationClient, new AppProperties());

        assertThat(geoLocationService.lookup("1.1.1.1")).isEmpty();
        verify(geoLocationClient).lookup("1.1.1.1");
    }
}
