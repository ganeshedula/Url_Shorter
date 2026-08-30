package com.url.shortener.service;

import java.util.UUID;

public record ClickEventRecorded(UUID clickEventId, String ipAddress) {
}
