package com.systemdesign.urlshortener.model;

import java.time.LocalDateTime;

/**
 * Response DTO returned after successfully shortening a URL.
 */
public class UrlShortenResponse {

    private final String shortUrl;
    private final String shortCode;
    private final String originalUrl;
    private final LocalDateTime createdAt;
    private final LocalDateTime expiresAt;

    public UrlShortenResponse(String shortUrl, String shortCode, String originalUrl,
                              LocalDateTime createdAt, LocalDateTime expiresAt) {
        this.shortUrl = shortUrl;
        this.shortCode = shortCode;
        this.originalUrl = originalUrl;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getShortUrl() { return shortUrl; }
    public String getShortCode() { return shortCode; }
    public String getOriginalUrl() { return originalUrl; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }

    @Override
    public String toString() {
        return "UrlShortenResponse{" +
                "shortUrl='" + shortUrl + '\'' +
                ", originalUrl='" + originalUrl + '\'' +
                ", expiresAt=" + expiresAt +
                '}';
    }
}
