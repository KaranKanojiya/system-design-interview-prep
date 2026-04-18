package com.systemdesign.urlshortener.model;

import java.time.LocalDateTime;

/**
 * Core domain entity representing a shortened URL.
 * Uses the Builder pattern for flexible, readable object construction.
 */
public class Url {

    private final String id;
    private final String shortCode;
    private final String originalUrl;
    private final LocalDateTime createdAt;
    private final LocalDateTime expiresAt;
    private long clickCount;
    private final String customAlias;
    private final String userId;

    private Url(Builder builder) {
        this.id = builder.id;
        this.shortCode = builder.shortCode;
        this.originalUrl = builder.originalUrl;
        this.createdAt = builder.createdAt;
        this.expiresAt = builder.expiresAt;
        this.clickCount = builder.clickCount;
        this.customAlias = builder.customAlias;
        this.userId = builder.userId;
    }

    // --- Domain logic ---

    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }

    public synchronized void incrementClickCount() {
        this.clickCount++;
    }

    // --- Getters ---

    public String getId() { return id; }
    public String getShortCode() { return shortCode; }
    public String getOriginalUrl() { return originalUrl; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public long getClickCount() { return clickCount; }
    public String getCustomAlias() { return customAlias; }
    public String getUserId() { return userId; }

    @Override
    public String toString() {
        return "Url{" +
                "shortCode='" + shortCode + '\'' +
                ", originalUrl='" + originalUrl + '\'' +
                ", createdAt=" + createdAt +
                ", expiresAt=" + expiresAt +
                ", clickCount=" + clickCount +
                ", customAlias='" + customAlias + '\'' +
                '}';
    }

    // --- Builder ---

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String shortCode;
        private String originalUrl;
        private LocalDateTime createdAt = LocalDateTime.now();
        private LocalDateTime expiresAt;
        private long clickCount = 0;
        private String customAlias;
        private String userId;

        public Builder id(String id) { this.id = id; return this; }
        public Builder shortCode(String shortCode) { this.shortCode = shortCode; return this; }
        public Builder originalUrl(String originalUrl) { this.originalUrl = originalUrl; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder expiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; return this; }
        public Builder clickCount(long clickCount) { this.clickCount = clickCount; return this; }
        public Builder customAlias(String customAlias) { this.customAlias = customAlias; return this; }
        public Builder userId(String userId) { this.userId = userId; return this; }

        public Url build() {
            if (originalUrl == null || originalUrl.isBlank()) {
                throw new IllegalArgumentException("originalUrl is required");
            }
            if (shortCode == null || shortCode.isBlank()) {
                throw new IllegalArgumentException("shortCode is required");
            }
            return new Url(this);
        }
    }
}
