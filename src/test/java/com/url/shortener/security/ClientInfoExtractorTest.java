package com.url.shortener.security;

import com.url.shortener.config.AppProperties;
import com.url.shortener.util.ClientInfoExtractor;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientInfoExtractorTest {

    @Test
    void usesRemoteAddressWhenForwardHeadersAreNotTrusted() {
        AppProperties appProperties = new AppProperties();
        ClientInfoExtractor extractor = new ClientInfoExtractor(appProperties);
        HttpServletRequest request = mock(HttpServletRequest.class);

        when(request.getRemoteAddr()).thenReturn("10.0.0.10");
        when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.7");

        assertThat(extractor.extract(request).getIpAddress()).isEqualTo("10.0.0.10");
    }

    @Test
    void usesTrustedForwardedHeaderWhenProxyIsAllowed() {
        AppProperties appProperties = new AppProperties();
        appProperties.getClientIp().setTrustForwardHeaders(true);
        appProperties.getClientIp().setTrustedProxies(java.util.List.of("10.0.0.10"));
        ClientInfoExtractor extractor = new ClientInfoExtractor(appProperties);
        HttpServletRequest request = mock(HttpServletRequest.class);

        when(request.getRemoteAddr()).thenReturn("10.0.0.10");
        when(request.getHeader("Forwarded")).thenReturn("for=\"[2001:db8::17]:4711\"");

        assertThat(extractor.extract(request).getIpAddress()).isEqualTo("2001:db8::17");
    }

    @Test
    void fallsBackToRemoteAddressWhenTrustedHeaderIsBlank() {
        AppProperties appProperties = new AppProperties();
        appProperties.getClientIp().setTrustForwardHeaders(true);
        appProperties.getClientIp().setTrustedProxies(java.util.List.of("10.0.0.10"));
        ClientInfoExtractor extractor = new ClientInfoExtractor(appProperties);
        HttpServletRequest request = mock(HttpServletRequest.class);

        when(request.getRemoteAddr()).thenReturn("10.0.0.10");
        when(request.getHeader("Forwarded")).thenReturn("for=unknown");
        when(request.getHeader("X-Forwarded-For")).thenReturn("");

        assertThat(extractor.extract(request).getIpAddress()).isEqualTo("10.0.0.10");
    }
}
