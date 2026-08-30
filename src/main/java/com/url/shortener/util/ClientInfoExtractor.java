package com.url.shortener.util;

import com.url.shortener.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

@Component
public class ClientInfoExtractor {

    private final AppProperties appProperties;

    public ClientInfoExtractor(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public ClientInfo extract(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        return ClientInfo.builder()
            .browser(parseBrowser(userAgent))
            .operatingSystem(parseOperatingSystem(userAgent))
            .ipAddress(resolveIpAddress(request))
            .country("Unknown")
            .countryCode("Unknown")
            .region("Unknown")
            .city("Unknown")
            .timezone("Unknown")
            .userAgent(userAgent)
            .deviceInfo(userAgent == null ? "Unknown Device" : userAgent)
            .build();
    }

    private String resolveIpAddress(HttpServletRequest request) {
        String remoteAddress = normalizeIpCandidate(request.getRemoteAddr());
        if (!appProperties.getClientIp().isTrustForwardHeaders() || !isTrustedProxy(remoteAddress)) {
            return remoteAddress;
        }

        String forwardedHeader = request.getHeader("Forwarded");
        String forwardedIp = extractForwardedIp(forwardedHeader);
        if (forwardedIp != null) {
            return forwardedIp;
        }

        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            String[] forwardedAddresses = xForwardedFor.split(",");
            for (String forwardedAddress : forwardedAddresses) {
                String normalized = normalizeIpCandidate(forwardedAddress);
                if (normalized != null) {
                    return normalized;
                }
            }
        }

        return remoteAddress;
    }

    private String extractForwardedIp(String forwardedHeader) {
        if (forwardedHeader == null || forwardedHeader.isBlank()) {
            return null;
        }
        String[] segments = forwardedHeader.split(",");
        for (String segment : segments) {
            String[] directives = segment.split(";");
            for (String directive : directives) {
                String trimmed = directive.trim();
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("for=")) {
                    return normalizeIpCandidate(trimmed.substring(4));
                }
            }
        }
        return null;
    }

    private boolean isTrustedProxy(String remoteAddress) {
        if (remoteAddress == null) {
            return false;
        }
        List<String> trustedProxies = appProperties.getClientIp().getTrustedProxies();
        return trustedProxies.stream().anyMatch(proxy -> ipMatches(proxy, remoteAddress));
    }

    private boolean ipMatches(String configuredValue, String remoteAddress) {
        if (configuredValue == null || configuredValue.isBlank()) {
            return false;
        }
        if (configuredValue.equals(remoteAddress)) {
            return true;
        }
        if (!configuredValue.contains("/")) {
            return false;
        }

        try {
            String[] parts = configuredValue.split("/", 2);
            byte[] expectedBytes = InetAddress.getByName(parts[0]).getAddress();
            byte[] candidateBytes = InetAddress.getByName(remoteAddress).getAddress();
            int prefixLength = Integer.parseInt(parts[1]);
            if (expectedBytes.length != candidateBytes.length) {
                return false;
            }

            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            for (int index = 0; index < fullBytes; index++) {
                if (expectedBytes[index] != candidateBytes[index]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = (-1) << (8 - remainingBits);
            return (expectedBytes[fullBytes] & mask) == (candidateBytes[fullBytes] & mask);
        } catch (UnknownHostException | NumberFormatException exception) {
            return false;
        }
    }

    private String normalizeIpCandidate(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        String normalized = candidate.trim();
        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() > 1) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.startsWith("[")) {
            int closingBracketIndex = normalized.indexOf(']');
            if (closingBracketIndex > 0) {
                normalized = normalized.substring(1, closingBracketIndex);
            }
        } else if (normalized.indexOf(':') != normalized.lastIndexOf(':') && !normalized.contains(".")) {
            return normalized;
        } else if (normalized.chars().filter(character -> character == ':').count() == 1 && normalized.contains(".")) {
            normalized = normalized.substring(0, normalized.indexOf(':'));
        }
        return normalized.isBlank() || "unknown".equalsIgnoreCase(normalized) ? null : normalized;
    }

    private String parseBrowser(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Unknown";
        }
        if (userAgent.contains("Edg/")) {
            return "Edge";
        }
        if (userAgent.contains("Chrome/")) {
            return "Chrome";
        }
        if (userAgent.contains("Firefox/")) {
            return "Firefox";
        }
        if (userAgent.contains("Safari/") && !userAgent.contains("Chrome/")) {
            return "Safari";
        }
        return "Unknown";
    }

    private String parseOperatingSystem(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Unknown";
        }
        if (userAgent.contains("Windows")) {
            return "Windows";
        }
        if (userAgent.contains("Mac OS X")) {
            return "macOS";
        }
        if (userAgent.contains("Linux")) {
            return "Linux";
        }
        if (userAgent.contains("Android")) {
            return "Android";
        }
        if (userAgent.contains("iPhone") || userAgent.contains("iPad")) {
            return "iOS";
        }
        return "Unknown";
    }
}
