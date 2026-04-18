package com.systemdesign.urlshortener.model;

import java.time.LocalDateTime;

/**
 * Request DTO for creating a shortened URL.
 * customAlias and expiresAt are optional.
 */
public class UrlShortenRequest {

    private final String originalUrl;
    private final String customAlias;
    private final LocalDateTime expiresAt;

    public UrlShortenRequest(String originalUrl) {
        this(originalUrl, null, null);
    }

    public UrlShortenRequest(String originalUrl, String customAlias, LocalDateTime expiresAt) {
        this.originalUrl = originalUrl;
        this.customAlias = customAlias;
        this.expiresAt = expiresAt;
    }

    public String getOriginalUrl() { return originalUrl; }
    public String getCustomAlias() { return customAlias; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
}
