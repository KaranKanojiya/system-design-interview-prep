package com.systemdesign.urlshortener.service;

import com.systemdesign.urlshortener.exception.DuplicateAliasException;
import com.systemdesign.urlshortener.exception.InvalidUrlException;
import com.systemdesign.urlshortener.exception.UrlExpiredException;
import com.systemdesign.urlshortener.exception.UrlNotFoundException;
import com.systemdesign.urlshortener.model.Url;
import com.systemdesign.urlshortener.model.UrlShortenRequest;
import com.systemdesign.urlshortener.model.UrlShortenResponse;
import com.systemdesign.urlshortener.repository.UrlRepository;
import com.systemdesign.urlshortener.strategy.EncodingStrategy;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Core service that orchestrates URL shortening, redirection, and stats.
 *
 * Key design decisions:
 * - AtomicLong counter for thread-safe ID generation (simulates a distributed ID generator)
 * - Strategy pattern for encoding — caller picks the algorithm
 * - Collision retry loop (up to 3 attempts) for robustness
 */
public class UrlShortenerService {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "^(https?://)[\\w\\-]+(\\.[\\w\\-]+)+([\\w.,@?^=%&:/~+#\\-]*[\\w@?^=%&/~+#\\-])?$"
    );
    private static final int MAX_RETRIES = 3;

    private final UrlRepository repository;
    private final EncodingStrategy encodingStrategy;
    private final String baseUrl;
    private final AtomicLong counter = new AtomicLong(100_000); // Start high for realistic codes

    public UrlShortenerService(UrlRepository repository, EncodingStrategy encodingStrategy, String baseUrl) {
        this.repository = repository;
        this.encodingStrategy = encodingStrategy;
        this.baseUrl = baseUrl;
    }

    /**
     * Shorten a URL using the configured encoding strategy.
     */
    public UrlShortenResponse shortenUrl(UrlShortenRequest request) {
        validateUrl(request.getOriginalUrl());

        String shortCode;

        // If a custom alias is provided, use it directly
        if (request.getCustomAlias() != null && !request.getCustomAlias().isBlank()) {
            shortCode = request.getCustomAlias();
            if (repository.existsByShortCode(shortCode)) {
                throw new DuplicateAliasException(shortCode);
            }
        } else {
            shortCode = generateUniqueCode(request.getOriginalUrl());
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = request.getExpiresAt() != null
                ? request.getExpiresAt()
                : now.plusDays(365); // Default TTL

        Url url = Url.builder()
                .id(UUID.randomUUID().toString())
                .shortCode(shortCode)
                .originalUrl(request.getOriginalUrl())
                .createdAt(now)
                .expiresAt(expiresAt)
                .customAlias(request.getCustomAlias())
                .build();

        repository.save(url);

        return new UrlShortenResponse(
                baseUrl + "/" + shortCode,
                shortCode,
                request.getOriginalUrl(),
                now,
                expiresAt
        );
    }

    /**
     * Resolve a short code to the original URL (the "redirect" operation).
     */
    public String redirect(String shortCode) {
        Url url = repository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        if (url.isExpired()) {
            throw new UrlExpiredException(shortCode);
        }

        url.incrementClickCount();
        return url.getOriginalUrl();
    }

    /**
     * Get full URL stats for analytics display.
     */
    public Url getStats(String shortCode) {
        return repository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));
    }

    /**
     * Delete a shortened URL.
     */
    public void deleteUrl(String shortCode) {
        if (!repository.existsByShortCode(shortCode)) {
            throw new UrlNotFoundException(shortCode);
        }
        repository.deleteByShortCode(shortCode);
    }

    public String getStrategyName() {
        return encodingStrategy.name();
    }

    // --- Private helpers ---

    private void validateUrl(String url) {
        if (url == null || !URL_PATTERN.matcher(url).matches()) {
            throw new InvalidUrlException(url);
        }
    }

    /**
     * Generate a unique short code with collision retry.
     * For Base62: pass counter value. For MD5: pass the URL. For Random: input is ignored.
     */
    private String generateUniqueCode(String originalUrl) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            String input = switch (encodingStrategy.name()) {
                case "Base62" -> String.valueOf(counter.incrementAndGet());
                case "MD5"    -> originalUrl + (attempt > 0 ? "#" + attempt : "");
                default       -> originalUrl; // Random strategy ignores input anyway
            };

            String code = encodingStrategy.encode(input);

            if (!repository.existsByShortCode(code)) {
                return code;
            }
            // Collision detected — retry with modified input
        }
        throw new RuntimeException("Failed to generate unique short code after " + MAX_RETRIES + " attempts");
    }
}
