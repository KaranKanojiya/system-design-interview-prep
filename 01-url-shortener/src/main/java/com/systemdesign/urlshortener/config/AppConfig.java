package com.systemdesign.urlshortener.config;

import com.systemdesign.urlshortener.controller.UrlShortenerController;
import com.systemdesign.urlshortener.repository.InMemoryUrlRepository;
import com.systemdesign.urlshortener.repository.UrlRepository;
import com.systemdesign.urlshortener.service.AnalyticsService;
import com.systemdesign.urlshortener.service.UrlShortenerService;
import com.systemdesign.urlshortener.strategy.Base62EncodingStrategy;
import com.systemdesign.urlshortener.strategy.EncodingStrategy;

/**
 * Application configuration and wiring.
 * Acts as a simple dependency injection container (manual IoC).
 * In production, Spring's @Configuration / @Bean would handle this.
 */
public class AppConfig {

    // --- Constants ---
    public static final String BASE_URL = "https://short.url";
    public static final int DEFAULT_TTL_DAYS = 365;
    public static final int MAX_CUSTOM_ALIAS_LENGTH = 16;
    public static final int SHORT_CODE_LENGTH = 7;

    /**
     * Factory method: creates a UrlShortenerService with default wiring.
     */
    public static UrlShortenerService createDefaultService() {
        UrlRepository repository = new InMemoryUrlRepository();
        EncodingStrategy strategy = new Base62EncodingStrategy();
        return new UrlShortenerService(repository, strategy, BASE_URL);
    }

    /**
     * Factory method: creates a service with a specific encoding strategy.
     */
    public static UrlShortenerService createServiceWithStrategy(EncodingStrategy strategy) {
        UrlRepository repository = new InMemoryUrlRepository();
        return new UrlShortenerService(repository, strategy, BASE_URL);
    }

    /**
     * Factory method: wires up the full controller with all dependencies.
     */
    public static UrlShortenerController createController() {
        UrlShortenerService service = createDefaultService();
        AnalyticsService analyticsService = new AnalyticsService();
        return new UrlShortenerController(service, analyticsService);
    }
}
