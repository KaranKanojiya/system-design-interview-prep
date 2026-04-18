package com.systemdesign.urlshortener.model;

import java.time.LocalDateTime;

/**
 * Represents a single click/redirect event for analytics tracking.
 */
public class ClickEvent {

    private final String shortCode;
    private final LocalDateTime timestamp;
    private final String ipAddress;
    private final String userAgent;

    public ClickEvent(String shortCode, LocalDateTime timestamp, String ipAddress, String userAgent) {
        this.shortCode = shortCode;
        this.timestamp = timestamp;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }

    public String getShortCode() { return shortCode; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public String getIpAddress() { return ipAddress; }
    public String getUserAgent() { return userAgent; }

    @Override
    public String toString() {
        return "ClickEvent{shortCode='" + shortCode + "', ip='" + ipAddress +
                "', timestamp=" + timestamp + '}';
    }
}
