package com.systemdesign.gateway.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * API route definition that maps an incoming path pattern to a target service.
 *
 * Flow: HttpRequest → Route matching (by pathPattern + method) → forward to targetService
 */
public class Route {

    private final String id;                    // unique route identifier
    private final String pathPattern;           // glob pattern, e.g. "/api/users/**"
    private final String targetService;         // service name in the registry
    private final Set<HttpMethod> methods;      // allowed HTTP methods for this route
    private final int priority;                 // lower = higher priority for matching order
    private final boolean enabled;              // whether the route is active
    private final int rateLimitPerSecond;       // max requests/second (0 = unlimited)
    private final long timeoutMs;               // request timeout in milliseconds
    private final int retryCount;               // number of retries on failure
    private final Map<String, String> metadata; // extensible key-value metadata

    // ── private constructor wired from Builder ──
    private Route(Builder builder) {
        this.id = builder.id;
        this.pathPattern = builder.pathPattern;
        this.targetService = builder.targetService;
        this.methods = Collections.unmodifiableSet(EnumSet.copyOf(builder.methods));
        this.priority = builder.priority;
        this.enabled = builder.enabled;
        this.rateLimitPerSecond = builder.rateLimitPerSecond;
        this.timeoutMs = builder.timeoutMs;
        this.retryCount = builder.retryCount;
        this.metadata = Collections.unmodifiableMap(new HashMap<>(builder.metadata));
    }

    // ── Getters ──

    public String getId() { return id; }
    public String getPathPattern() { return pathPattern; }
    public String getTargetService() { return targetService; }
    public Set<HttpMethod> getMethods() { return methods; }
    public int getPriority() { return priority; }
    public boolean isEnabled() { return enabled; }
    public int getRateLimitPerSecond() { return rateLimitPerSecond; }
    public long getTimeoutMs() { return timeoutMs; }
    public int getRetryCount() { return retryCount; }
    public Map<String, String> getMetadata() { return metadata; }

    /**
     * Checks if this route matches the given path using simple glob matching.
     * Supports "**" as a wildcard suffix.
     */
    public boolean matches(String path, HttpMethod method) {
        if (!enabled || !methods.contains(method)) {
            return false;
        }
        // 1) exact match
        if (pathPattern.equals(path)) {
            return true;
        }
        // 2) wildcard suffix match: "/api/users/**" matches "/api/users/123"
        if (pathPattern.endsWith("/**")) {
            String prefix = pathPattern.substring(0, pathPattern.length() - 3);
            return path.startsWith(prefix);
        }
        return false;
    }

    @Override
    public String toString() {
        return "Route{id='%s', pattern='%s', target='%s', priority=%d}".formatted(
                id, pathPattern, targetService, priority);
    }

    // ── Builder ──

    public static class Builder {
        private String id = UUID.randomUUID().toString();
        private final String pathPattern;
        private final String targetService;
        private Set<HttpMethod> methods = EnumSet.allOf(HttpMethod.class);
        private int priority = 100;
        private boolean enabled = true;
        private int rateLimitPerSecond = 0;
        private long timeoutMs = 5000;
        private int retryCount = 0;
        private Map<String, String> metadata = new HashMap<>();

        /** Primary constructor — pathPattern and targetService are required. */
        public Builder(String pathPattern, String targetService) {
            this.pathPattern = Objects.requireNonNull(pathPattern, "pathPattern must not be null");
            this.targetService = Objects.requireNonNull(targetService, "targetService must not be null");
        }

        public Builder id(String id) { this.id = id; return this; }
        public Builder methods(Set<HttpMethod> methods) { this.methods = EnumSet.copyOf(methods); return this; }
        public Builder priority(int priority) { this.priority = priority; return this; }
        public Builder enabled(boolean enabled) { this.enabled = enabled; return this; }
        public Builder rateLimitPerSecond(int rps) { this.rateLimitPerSecond = rps; return this; }
        public Builder timeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; return this; }
        public Builder retryCount(int retryCount) { this.retryCount = retryCount; return this; }
        public Builder metadata(String key, String value) { this.metadata.put(key, value); return this; }
        public Builder metadata(Map<String, String> metadata) { this.metadata.putAll(metadata); return this; }

        public Route build() {
            return new Route(this);
        }
    }
}
