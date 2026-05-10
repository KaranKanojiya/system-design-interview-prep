package com.systemdesign.gateway.model;

/**
 * Configuration for the service mesh sidecar proxy.
 *
 * Controls mTLS, tracing, retries, and circuit-breaking behavior across the mesh.
 */
public class ServiceMeshConfig {

    private final boolean mtlsEnabled;              // mutual TLS between services
    private final int sidecarPort;                  // port the sidecar proxy listens on
    private final boolean tracingEnabled;            // distributed tracing (e.g., Zipkin/Jaeger)
    private final RetryPolicy retryPolicy;          // mesh-wide retry policy
    private final boolean circuitBreakerEnabled;    // enable circuit breakers on service calls

    // ── private constructor wired from Builder ──
    private ServiceMeshConfig(Builder builder) {
        this.mtlsEnabled = builder.mtlsEnabled;
        this.sidecarPort = builder.sidecarPort;
        this.tracingEnabled = builder.tracingEnabled;
        this.retryPolicy = builder.retryPolicy;
        this.circuitBreakerEnabled = builder.circuitBreakerEnabled;
    }

    // ── Getters ──

    public boolean isMtlsEnabled() { return mtlsEnabled; }
    public int getSidecarPort() { return sidecarPort; }
    public boolean isTracingEnabled() { return tracingEnabled; }
    public RetryPolicy getRetryPolicy() { return retryPolicy; }
    public boolean isCircuitBreakerEnabled() { return circuitBreakerEnabled; }

    @Override
    public String toString() {
        return "ServiceMeshConfig{mtls=%s, sidecarPort=%d, tracing=%s, circuitBreaker=%s}".formatted(
                mtlsEnabled, sidecarPort, tracingEnabled, circuitBreakerEnabled);
    }

    // ── Builder with sensible defaults ──

    public static class Builder {
        private boolean mtlsEnabled = true;
        private int sidecarPort = 15001;
        private boolean tracingEnabled = true;
        private RetryPolicy retryPolicy = RetryPolicy.defaultPolicy();
        private boolean circuitBreakerEnabled = true;

        public Builder() {
            // all defaults set above
        }

        public Builder mtlsEnabled(boolean mtlsEnabled) { this.mtlsEnabled = mtlsEnabled; return this; }
        public Builder sidecarPort(int sidecarPort) { this.sidecarPort = sidecarPort; return this; }
        public Builder tracingEnabled(boolean tracingEnabled) { this.tracingEnabled = tracingEnabled; return this; }
        public Builder retryPolicy(RetryPolicy retryPolicy) { this.retryPolicy = retryPolicy; return this; }
        public Builder circuitBreakerEnabled(boolean enabled) { this.circuitBreakerEnabled = enabled; return this; }

        public ServiceMeshConfig build() {
            return new ServiceMeshConfig(this);
        }
    }
}
