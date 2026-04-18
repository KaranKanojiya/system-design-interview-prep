package com.systemdesign.ratelimiter.controller;

import com.systemdesign.ratelimiter.model.RateLimitResult;
import com.systemdesign.ratelimiter.model.RequestContext;
import com.systemdesign.ratelimiter.service.RateLimiterService;

import java.util.Map;

/**
 * Simulated middleware / controller layer.
 * In production, this would be a servlet filter, Spring interceptor, or API gateway plugin.
 * Here it prints request/response info to demonstrate the flow.
 */
public class RateLimiterController {

    private final RateLimiterService rateLimiterService;

    public RateLimiterController(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    /**
     * Simulates handling an incoming HTTP request through the rate limiter.
     */
    public RateLimitResult handleRequest(RequestContext context) {
        System.out.printf("  [REQUEST] clientId=%s endpoint=%s%n", context.getClientId(), context.getEndpoint());

        RateLimitResult result = rateLimiterService.checkRateLimit(context);

        // Print response headers
        Map<String, String> headers = result.getHeaders();
        headers.forEach((name, value) -> System.out.printf("    %s: %s%n", name, value));

        if (result.isAllowed()) {
            System.out.printf("    [200 OK] Request processed. Remaining: %d%n", result.getRemaining());
        } else {
            System.out.printf("    [429 TOO MANY REQUESTS] Retry after: %dms%n", result.getRetryAfterMs());
        }

        return result;
    }

    public RateLimiterService getService() {
        return rateLimiterService;
    }
}
