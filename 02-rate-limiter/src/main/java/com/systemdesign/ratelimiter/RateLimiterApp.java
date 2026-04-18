package com.systemdesign.ratelimiter;

import com.systemdesign.ratelimiter.config.AppConfig;
import com.systemdesign.ratelimiter.controller.RateLimiterController;
import com.systemdesign.ratelimiter.model.*;
import com.systemdesign.ratelimiter.service.RateLimiterService;

/**
 * Main demo application — showcases all 5 rate limiting algorithms
 * with realistic scenarios an interviewer would expect to see.
 *
 * Run with: java -cp . com.systemdesign.ratelimiter.RateLimiterApp
 */
public class RateLimiterApp {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║     Rate Limiter — System Design Demo               ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();

        RateLimiterController controller = AppConfig.createController();
        RateLimiterService service = controller.getService();

        demoTokenBucket(controller, service);
        demoFixedWindowBoundary(controller, service);
        demoSlidingWindowCounter(controller, service);
        demoAlgorithmComparison(service);
        demoMultipleClients(controller, service);
        printDesignSummary();
    }

    // ─── Demo 1: Token Bucket ──────────────────────────────────────────

    private static void demoTokenBucket(RateLimiterController controller, RateLimiterService service)
            throws InterruptedException {
        printHeader("Demo 1: Token Bucket — Burst + Refill");

        String ruleKey = "alice:/api/orders";
        RateLimitRule rule = RateLimitRule.builder(ruleKey, 5, 10_000) // 5 req per 10 sec
                .algorithm(Algorithm.TOKEN_BUCKET)
                .build();
        service.getRuleRepository().save(rule);

        System.out.println("Rule: 5 requests per 10 seconds (Token Bucket)");
        System.out.println("Sending 7 rapid requests...\n");

        int allowed = 0, denied = 0;
        for (int i = 1; i <= 7; i++) {
            System.out.printf("--- Request %d ---%n", i);
            RequestContext ctx = new RequestContext("alice", "192.168.1.1", "/api/orders");
            RateLimitResult result = controller.handleRequest(ctx);
            if (result.isAllowed()) allowed++;
            else denied++;
            System.out.println();
        }
        System.out.printf("Result: %d allowed, %d denied%n%n", allowed, denied);

        // Wait for tokens to refill
        System.out.println("Waiting 2 seconds for token refill...");
        Thread.sleep(2000);

        System.out.println("\n--- Request after refill ---");
        RequestContext ctx = new RequestContext("alice", "192.168.1.1", "/api/orders");
        RateLimitResult result = controller.handleRequest(ctx);
        System.out.printf("After refill: %s%n%n", result.isAllowed() ? "ALLOWED (tokens refilled!)" : "STILL DENIED");

        // Clean up
        service.resetLimit(ruleKey);
    }

    // ─── Demo 2: Fixed Window Boundary Problem ─────────────────────────

    private static void demoFixedWindowBoundary(RateLimiterController controller, RateLimiterService service)
            throws InterruptedException {
        printHeader("Demo 2: Fixed Window — Boundary Burst Problem");

        String ruleKey = "bob:/api/search";
        RateLimitRule rule = RateLimitRule.builder(ruleKey, 5, 2_000) // 5 req per 2 sec
                .algorithm(Algorithm.FIXED_WINDOW)
                .build();
        service.getRuleRepository().save(rule);

        System.out.println("Rule: 5 requests per 2-second window (Fixed Window)");
        System.out.println("Sending 5 requests, then waiting for window boundary, then 5 more...\n");

        // Fill up the current window
        int firstBatch = 0;
        for (int i = 1; i <= 5; i++) {
            RequestContext ctx = new RequestContext("bob", "10.0.0.1", "/api/search");
            RateLimitResult result = controller.handleRequest(ctx);
            if (result.isAllowed()) firstBatch++;
        }
        System.out.printf("\nFirst batch: %d/5 allowed%n", firstBatch);

        // Wait for the next window
        System.out.println("Waiting for next window boundary...");
        Thread.sleep(2100);

        // Immediately fire 5 more — these all succeed in the new window
        int secondBatch = 0;
        for (int i = 1; i <= 5; i++) {
            RequestContext ctx = new RequestContext("bob", "10.0.0.1", "/api/search");
            RateLimitResult result = controller.handleRequest(ctx);
            if (result.isAllowed()) secondBatch++;
        }
        System.out.printf("\nSecond batch (new window): %d/5 allowed%n", secondBatch);
        System.out.printf("Total: %d requests in ~2 seconds — boundary burst! (limit was 5)%n%n",
                firstBatch + secondBatch);

        service.resetLimit(ruleKey);
    }

    // ─── Demo 3: Sliding Window Counter ────────────────────────────────

    private static void demoSlidingWindowCounter(RateLimiterController controller, RateLimiterService service) {
        printHeader("Demo 3: Sliding Window Counter — Smoother Limiting");

        String ruleKey = "carol:/api/search";
        RateLimitRule rule = RateLimitRule.builder(ruleKey, 5, 2_000) // 5 req per 2 sec
                .algorithm(Algorithm.SLIDING_WINDOW_COUNTER)
                .build();
        service.getRuleRepository().save(rule);

        System.out.println("Rule: 5 requests per 2-second window (Sliding Window Counter)");
        System.out.println("Same scenario — sliding window weights previous window's count.\n");

        int allowed = 0;
        for (int i = 1; i <= 8; i++) {
            RequestContext ctx = new RequestContext("carol", "10.0.0.2", "/api/search");
            RateLimitResult result = controller.handleRequest(ctx);
            if (result.isAllowed()) allowed++;
        }
        System.out.printf("\nResult: %d/8 allowed (weighted approximation prevents boundary burst)%n%n", allowed);

        service.resetLimit(ruleKey);
    }

    // ─── Demo 4: Algorithm Comparison ──────────────────────────────────

    private static void demoAlgorithmComparison(RateLimiterService service) {
        printHeader("Demo 4: Algorithm Comparison — Same Rule, 5 Algorithms");

        int maxReq = 5;
        long windowMs = 5_000;

        System.out.printf("Rule: %d requests per %dms. Sending 10 rapid requests to each.%n%n", maxReq, windowMs);
        System.out.println("+--------------------------+----------+----------+");
        System.out.println("| Algorithm                | Allowed  | Denied   |");
        System.out.println("+--------------------------+----------+----------+");

        for (Algorithm algo : Algorithm.values()) {
            // Fresh service per algorithm to avoid shared state
            RateLimiterService freshService = RateLimiterService.createDefault();
            String key = "test:" + algo.name();
            RateLimitRule rule = RateLimitRule.builder(key, maxReq, windowMs)
                    .algorithm(algo)
                    .build();
            freshService.getRuleRepository().save(rule);

            int allowed = 0, denied = 0;
            for (int i = 0; i < 10; i++) {
                RequestContext ctx = new RequestContext("test", "127.0.0.1", algo.name());
                RateLimitResult result = freshService.checkRateLimit(ctx);
                if (result.isAllowed()) allowed++;
                else denied++;
            }

            System.out.printf("| %-24s | %8d | %8d |%n", algo.name(), allowed, denied);
        }
        System.out.println("+--------------------------+----------+----------+");
        System.out.println();
    }

    // ─── Demo 5: Multiple Clients ──────────────────────────────────────

    private static void demoMultipleClients(RateLimiterController controller, RateLimiterService service) {
        printHeader("Demo 5: Multiple Clients — Per-User Isolation");

        // Different limits for different users
        service.getRuleRepository().save(
                RateLimitRule.builder("premium:/api/data", 10, 10_000)
                        .algorithm(Algorithm.TOKEN_BUCKET).build());
        service.getRuleRepository().save(
                RateLimitRule.builder("free:/api/data", 3, 10_000)
                        .algorithm(Algorithm.TOKEN_BUCKET).build());

        System.out.println("Premium user: 10 req/10s | Free user: 3 req/10s");
        System.out.println("Both sending 5 requests to /api/data\n");

        System.out.println("--- Premium User ---");
        int premiumAllowed = 0;
        for (int i = 0; i < 5; i++) {
            RequestContext ctx = new RequestContext("premium", "10.0.0.10", "/api/data");
            RateLimitResult result = controller.handleRequest(ctx);
            if (result.isAllowed()) premiumAllowed++;
        }

        System.out.println("\n--- Free User ---");
        int freeAllowed = 0;
        for (int i = 0; i < 5; i++) {
            RequestContext ctx = new RequestContext("free", "10.0.0.20", "/api/data");
            RateLimitResult result = controller.handleRequest(ctx);
            if (result.isAllowed()) freeAllowed++;
        }

        System.out.printf("%nPremium: %d/5 allowed | Free: %d/5 allowed%n%n", premiumAllowed, freeAllowed);
    }

    // ─── Design Summary ────────────────────────────────────────────────

    private static void printDesignSummary() {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║                      Design Summary                             ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════╣");
        System.out.println("║  Patterns:                                                      ║");
        System.out.println("║    - Strategy     : Swappable algorithms at runtime             ║");
        System.out.println("║    - Builder      : Clean, validated rule construction          ║");
        System.out.println("║    - Factory      : Centralized wiring in AppConfig             ║");
        System.out.println("║    - Repository   : Decoupled storage (swap to Redis/DB)       ║");
        System.out.println("║                                                                 ║");
        System.out.println("║  Scalability Notes:                                             ║");
        System.out.println("║    - Single node  : ConcurrentHashMap + per-key locks           ║");
        System.out.println("║    - Distributed  : Replace in-memory stores with Redis         ║");
        System.out.println("║                     (INCR + EXPIRE for fixed window,            ║");
        System.out.println("║                      Lua scripts for atomic token bucket)       ║");
        System.out.println("║    - API Gateway  : Deploy as middleware (Nginx, Kong, Envoy)   ║");
        System.out.println("║    - Consistency  : Sticky sessions or centralized Redis        ║");
        System.out.println("║                                                                 ║");
        System.out.println("║  Trade-offs:                                                    ║");
        System.out.println("║    Token Bucket     — best for bursty APIs (most common)        ║");
        System.out.println("║    Leaky Bucket     — best for steady-rate processing           ║");
        System.out.println("║    Fixed Window     — simplest, but boundary burst problem      ║");
        System.out.println("║    Sliding Log      — most accurate, O(n) memory per key        ║");
        System.out.println("║    Sliding Counter  — best balance (Cloudflare's choice)        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
    }

    private static void printHeader(String title) {
        System.out.println("━".repeat(60));
        System.out.println("  " + title);
        System.out.println("━".repeat(60));
    }
}
