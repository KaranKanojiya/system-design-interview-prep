package com.systemdesign.gateway.model;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Mutable context object that flows through the entire gateway filter pipeline.
 *
 * Flow: HttpRequest arrives → RequestContext created → filters populate route, auth, rate limit →
 *       load balancer sets selectedInstance → request forwarded → response returned
 */
public class RequestContext {

    private final HttpRequest request;                  // the original inbound request
    private Route route;                                // matched route (set by routing filter)
    private ServiceInstance selectedInstance;            // chosen instance (set by load balancer)
    private AuthResult authResult;                      // authentication result (set by auth filter)
    private RateLimitResult rateLimitResult;             // rate limit result (set by rate limiter)
    private final Instant startTime;                    // when processing began
    private final String traceId;                       // distributed trace ID for observability

    public RequestContext(HttpRequest request) {
        this.request = request;
        this.startTime = Instant.now();
        this.traceId = UUID.randomUUID().toString();
    }

    // ── Getters ──

    public HttpRequest getRequest() { return request; }
    public Route getRoute() { return route; }
    public ServiceInstance getSelectedInstance() { return selectedInstance; }
    public AuthResult getAuthResult() { return authResult; }
    public RateLimitResult getRateLimitResult() { return rateLimitResult; }
    public Instant getStartTime() { return startTime; }
    public String getTraceId() { return traceId; }

    // ── Setters (populated by successive filters in the pipeline) ──

    public void setRoute(Route route) {
        this.route = route;
    }

    public void setSelectedInstance(ServiceInstance selectedInstance) {
        this.selectedInstance = selectedInstance;
    }

    public void setAuthResult(AuthResult authResult) {
        this.authResult = authResult;
    }

    public void setRateLimitResult(RateLimitResult rateLimitResult) {
        this.rateLimitResult = rateLimitResult;
    }

    /** Returns the elapsed time in milliseconds since request processing began. */
    public long getElapsedMs() {
        return Duration.between(startTime, Instant.now()).toMillis();
    }

    @Override
    public String toString() {
        return "RequestContext{traceId='%s', path='%s', route=%s, elapsed=%dms}".formatted(
                traceId, request.getPath(),
                route != null ? route.getTargetService() : "unmatched",
                getElapsedMs());
    }
}
