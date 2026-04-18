package com.systemdesign.urlshortener.controller;

import com.systemdesign.urlshortener.model.Url;
import com.systemdesign.urlshortener.model.UrlShortenRequest;
import com.systemdesign.urlshortener.model.UrlShortenResponse;
import com.systemdesign.urlshortener.service.AnalyticsService;
import com.systemdesign.urlshortener.service.UrlShortenerService;

/**
 * Simulated REST controller (no framework dependencies).
 * Each method mirrors what a real Spring @RestController endpoint would do.
 *
 * In production:
 *   POST /api/shorten       -> handleShortenRequest
 *   GET  /{shortCode}       -> handleRedirect (returns 302)
 *   GET  /api/stats/{code}  -> handleGetStats
 *   DELETE /api/{shortCode} -> handleDelete
 */
public class UrlShortenerController {

    private final UrlShortenerService shortenerService;
    private final AnalyticsService analyticsService;

    public UrlShortenerController(UrlShortenerService shortenerService, AnalyticsService analyticsService) {
        this.shortenerService = shortenerService;
        this.analyticsService = analyticsService;
    }

    /**
     * POST /api/shorten — Create a shortened URL.
     */
    public UrlShortenResponse handleShortenRequest(UrlShortenRequest request) {
        System.out.println("[POST /api/shorten] Shortening: " + request.getOriginalUrl());
        UrlShortenResponse response = shortenerService.shortenUrl(request);
        System.out.println("[201 Created] " + response.getShortUrl());
        return response;
    }

    /**
     * GET /{shortCode} — Redirect to original URL.
     * In production, this returns HTTP 302 with Location header.
     */
    public String handleRedirect(String shortCode) {
        System.out.println("[GET /" + shortCode + "] Redirecting...");
        String originalUrl = shortenerService.redirect(shortCode);

        // Record the click event (simulating request metadata)
        analyticsService.recordClick(shortCode, "192.168.1.1", "Mozilla/5.0");

        System.out.println("[302 Redirect] -> " + originalUrl);
        return originalUrl;
    }

    /**
     * GET /api/stats/{shortCode} — Get analytics for a shortened URL.
     */
    public Url handleGetStats(String shortCode) {
        System.out.println("[GET /api/stats/" + shortCode + "] Fetching stats...");
        Url stats = shortenerService.getStats(shortCode);
        long analyticsClicks = analyticsService.getClickCount(shortCode);
        System.out.println("[200 OK] Clicks: " + stats.getClickCount() +
                " (analytics service: " + analyticsClicks + ")");
        return stats;
    }

    /**
     * DELETE /api/{shortCode} — Delete a shortened URL.
     */
    public void handleDelete(String shortCode) {
        System.out.println("[DELETE /api/" + shortCode + "] Deleting...");
        shortenerService.deleteUrl(shortCode);
        System.out.println("[204 No Content] Deleted successfully.");
    }
}
