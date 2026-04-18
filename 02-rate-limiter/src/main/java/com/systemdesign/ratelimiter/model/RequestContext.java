package com.systemdesign.ratelimiter.model;

/**
 * Encapsulates metadata about an incoming request for rate limiting decisions.
 * The composite key (clientId:endpoint) allows per-user, per-endpoint granularity.
 */
public class RequestContext {

    private final String clientId;
    private final String ipAddress;
    private final String endpoint;
    private final long timestamp;

    public RequestContext(String clientId, String ipAddress, String endpoint, long timestamp) {
        this.clientId = clientId;
        this.ipAddress = ipAddress;
        this.endpoint = endpoint;
        this.timestamp = timestamp;
    }

    /** Convenience constructor — uses current system time. */
    public RequestContext(String clientId, String ipAddress, String endpoint) {
        this(clientId, ipAddress, endpoint, System.currentTimeMillis());
    }

    // --- Getters ---

    public String getClientId() { return clientId; }
    public String getIpAddress() { return ipAddress; }
    public String getEndpoint() { return endpoint; }
    public long getTimestamp() { return timestamp; }

    /**
     * Default composite key for rate limiting: "clientId:endpoint".
     * In production, this could be configurable (IP-based, API-key-based, etc.).
     */
    public String getRateLimitKey() {
        return clientId + ":" + endpoint;
    }

    @Override
    public String toString() {
        return "RequestContext{clientId='%s', ip='%s', endpoint='%s', timestamp=%d}"
                .formatted(clientId, ipAddress, endpoint, timestamp);
    }
}
