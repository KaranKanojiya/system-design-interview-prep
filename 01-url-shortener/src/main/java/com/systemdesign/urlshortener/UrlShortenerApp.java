package com.systemdesign.urlshortener;

import com.systemdesign.urlshortener.config.AppConfig;
import com.systemdesign.urlshortener.controller.UrlShortenerController;
import com.systemdesign.urlshortener.exception.UrlNotFoundException;
import com.systemdesign.urlshortener.model.Url;
import com.systemdesign.urlshortener.model.UrlShortenRequest;
import com.systemdesign.urlshortener.model.UrlShortenResponse;
import com.systemdesign.urlshortener.service.UrlShortenerService;
import com.systemdesign.urlshortener.strategy.Base62EncodingStrategy;
import com.systemdesign.urlshortener.strategy.EncodingStrategy;
import com.systemdesign.urlshortener.strategy.Md5EncodingStrategy;
import com.systemdesign.urlshortener.strategy.RandomEncodingStrategy;

import java.time.LocalDateTime;
import java.util.List;

/**
 * URL Shortener — System Design Interview Demo
 *
 * Demonstrates:
 * - Strategy Pattern (encoding algorithms)
 * - Builder Pattern (Url entity)
 * - Repository Pattern (data access abstraction)
 * - Factory Method (AppConfig wiring)
 * - Clean separation of concerns (model / service / controller / repository)
 */
public class UrlShortenerApp {

    public static void main(String[] args) {
        System.out.println("=== URL Shortener — System Design Demo ===");
        System.out.println();

        // --- 1. Create controller via factory ---
        UrlShortenerController controller = AppConfig.createController();

        // --- 2. Shorten some URLs ---
        printSection("1. SHORTEN URLs");

        UrlShortenResponse googleResp = controller.handleShortenRequest(
                new UrlShortenRequest("https://www.google.com")
        );
        System.out.println("   Result: " + googleResp);
        System.out.println();

        UrlShortenResponse githubResp = controller.handleShortenRequest(
                new UrlShortenRequest("https://github.com/explore")
        );
        System.out.println("   Result: " + githubResp);
        System.out.println();

        // --- 3. Custom alias ---
        printSection("2. CUSTOM ALIAS");

        UrlShortenResponse customResp = controller.handleShortenRequest(
                new UrlShortenRequest("https://docs.oracle.com/en/java/", "java-docs",
                        LocalDateTime.now().plusDays(30))
        );
        System.out.println("   Result: " + customResp);
        System.out.println();

        // --- 4. Redirect (simulate clicking a short link) ---
        printSection("3. REDIRECT (simulate click)");

        String googleCode = googleResp.getShortCode();
        String originalUrl = controller.handleRedirect(googleCode);
        System.out.println("   Resolved: " + originalUrl);
        System.out.println();

        // Click a few more times to build up stats
        controller.handleRedirect(googleCode);
        controller.handleRedirect(googleCode);

        // --- 5. Get stats ---
        printSection("4. URL STATS");

        Url stats = controller.handleGetStats(googleCode);
        System.out.println("   Full stats: " + stats);
        System.out.println();

        // --- 6. Access non-existent code ---
        printSection("5. ERROR HANDLING — Not Found");

        try {
            controller.handleRedirect("INVALID");
        } catch (UrlNotFoundException e) {
            System.out.println("   Caught expected error: " + e.getMessage());
        }
        System.out.println();

        // --- 7. Delete a URL ---
        printSection("6. DELETE URL");

        controller.handleDelete(githubResp.getShortCode());
        System.out.println();

        // Verify deletion
        try {
            controller.handleRedirect(githubResp.getShortCode());
        } catch (UrlNotFoundException e) {
            System.out.println("   Verified: " + e.getMessage());
        }
        System.out.println();

        // --- 8. Encoding strategy comparison ---
        printSection("7. ENCODING STRATEGY COMPARISON");

        String testUrl = "https://www.example.com/very/long/path/to/resource";
        List<EncodingStrategy> strategies = List.of(
                new Base62EncodingStrategy(),
                new Md5EncodingStrategy(),
                new RandomEncodingStrategy()
        );

        for (EncodingStrategy strategy : strategies) {
            UrlShortenerService service = AppConfig.createServiceWithStrategy(strategy);
            UrlShortenResponse resp = service.shortenUrl(new UrlShortenRequest(testUrl));
            System.out.printf("   %-8s -> %s  (code: %s)%n",
                    strategy.name(), resp.getShortUrl(), resp.getShortCode());
        }
        System.out.println();

        // --- 9. Summary ---
        printSection("8. DESIGN SUMMARY");

        System.out.println("   Patterns Used:");
        System.out.println("     - Strategy Pattern  : Swappable encoding (Base62, MD5, Random)");
        System.out.println("     - Builder Pattern   : Url entity construction");
        System.out.println("     - Repository Pattern: Data access abstraction (InMemory / DB swap)");
        System.out.println("     - Factory Method    : AppConfig wires dependencies");
        System.out.println();
        System.out.println("   Scalability Considerations:");
        System.out.println("     - Base62 + distributed counter (Snowflake/ZooKeeper) for uniqueness");
        System.out.println("     - Read-heavy: cache layer (Redis) in front of DB");
        System.out.println("     - Analytics: async event stream (Kafka) to avoid write-path latency");
        System.out.println("     - DB: NoSQL (DynamoDB) for key-value lookups, or PostgreSQL with index");
        System.out.println("     - Rate limiting per user/IP to prevent abuse");
        System.out.println();
        System.out.println("=== Demo Complete ===");
    }

    private static void printSection(String title) {
        System.out.println("------------------------------------------------------");
        System.out.println("  " + title);
        System.out.println("------------------------------------------------------");
    }
}
