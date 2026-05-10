package com.systemdesign.gateway.service;

import com.systemdesign.gateway.engine.RateLimiterEngine;
import com.systemdesign.gateway.model.RequestContext;
import com.systemdesign.gateway.model.RateLimitResult;

import java.util.Objects;

/**
 * Rate limiting management — enforces per-route and per-client request quotas.
 *
 * Flow: RequestContext → check route rate limit → check client rate limit → allow or deny
 *
 * Pattern: delegates to RateLimiterEngine which implements token bucket / sliding window internally.
 */
public class RateLimitService {

    private final RateLimiterEngine rateLimiter; // wiring: token-bucket rate limiting engine

    public RateLimitService(RateLimiterEngine rateLimiter) {
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter must not be null");
    }

    /**
     * Configures a rate limit for a specific route.
     *
     * @param routeId              the route identifier
     * @param maxRequestsPerSecond maximum requests allowed per second
     */
    public void configureRoute(String routeId, int maxRequestsPerSecond) {
        rateLimiter.configure(routeId, maxRequestsPerSecond, maxRequestsPerSecond);
        System.out.println("[RATE LIMIT] Configured route '%s' → %d req/s".formatted(
                routeId, maxRequestsPerSecond));
    }

    /**
     * Configures a per-client rate limit.
     *
     * @param clientId             the client identifier (IP or principal)
     * @param maxRequestsPerSecond maximum requests allowed per second
     */
    public void configureClient(String clientId, int maxRequestsPerSecond) {
        rateLimiter.configure(clientId, maxRequestsPerSecond, maxRequestsPerSecond);
        System.out.println("[RATE LIMIT] Configured client '%s' → %d req/s".formatted(
                clientId, maxRequestsPerSecond));
    }

    /**
     * Checks whether a request identified by key is within the rate limit.
     *
     * @param key rate limit key (route id, client id, etc.)
     * @return result indicating allow or deny with remaining quota
     */
    public RateLimitResult checkRateLimit(String key) {
        RateLimitResult result = rateLimiter.tryConsume(key);
        System.out.println("[RATE LIMIT] Check key='%s' → %s (remaining=%d)".formatted(
                key, result.isAllowed() ? "ALLOWED" : "DENIED", result.getRemainingTokens()));
        return result;
    }

    /**
     * Checks rate limit for the route associated with the request context.
     * Uses the route's configured rateLimitPerSecond. If the route has no limit (0), allows.
     *
     * @param ctx the request context containing route information
     * @return result indicating allow or deny
     */
    public RateLimitResult checkRouteRateLimit(RequestContext ctx) {
        String routeId = ctx.getRoute().getId();
        int limit = ctx.getRoute().getRateLimitPerSecond();

        if (limit <= 0) {
            System.out.println("[RATE LIMIT] Route '%s' has no rate limit configured — allowing".formatted(routeId));
            return RateLimitResult.allowed(Integer.MAX_VALUE);
        }

        RateLimitResult result = rateLimiter.tryConsume(routeId);
        System.out.println("[RATE LIMIT] Route '%s' → %s (limit=%d req/s, remaining=%d)".formatted(
                routeId, result.isAllowed() ? "ALLOWED" : "DENIED", limit, result.getRemainingTokens()));
        return result;
    }

    /**
     * Checks rate limit for the client making the request.
     * Uses client IP as the key; falls back to auth principal if available.
     *
     * @param ctx the request context containing client information
     * @return result indicating allow or deny
     */
    public RateLimitResult checkClientRateLimit(RequestContext ctx) {
        // Prefer auth principal as key; fall back to client IP
        String key = ctx.getAuthResult() != null && ctx.getAuthResult().getPrincipal() != null
                ? ctx.getAuthResult().getPrincipal()
                : ctx.getRequest().getClientIp();

        RateLimitResult result = rateLimiter.tryConsume(key);
        System.out.println("[RATE LIMIT] Client '%s' → %s (remaining=%d)".formatted(
                key, result.isAllowed() ? "ALLOWED" : "DENIED", result.getRemainingTokens()));
        return result;
    }
}
