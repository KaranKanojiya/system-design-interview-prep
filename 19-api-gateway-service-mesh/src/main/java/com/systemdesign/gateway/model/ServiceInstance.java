package com.systemdesign.gateway.model;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A running service instance registered in the service mesh.
 *
 * Flow: Service registers → ServiceInstance stored in registry → load balancer picks instance → request forwarded
 */
public class ServiceInstance {

    private final String id;                    // unique instance identifier
    private final String serviceName;           // logical service name, e.g. "user-service"
    private final String host;                  // hostname or IP
    private final int port;                     // listening port
    private volatile HealthStatus healthStatus; // current health (mutable — updated by health checks)
    private final int weight;                   // weight for weighted load balancing (higher = more traffic)
    private final String zone;                  // availability zone, e.g. "us-east-1a"
    private final Instant registeredAt;         // when the instance registered
    private volatile Instant lastHeartbeat;     // last successful heartbeat (mutable)
    private final Map<String, String> metadata; // extensible key-value metadata

    public ServiceInstance(String serviceName, String host, int port) {
        this(serviceName, host, port, 1, "default");
    }

    public ServiceInstance(String serviceName, String host, int port, int weight, String zone) {
        this.id = UUID.randomUUID().toString();
        this.serviceName = Objects.requireNonNull(serviceName, "serviceName must not be null");
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.port = port;
        this.healthStatus = HealthStatus.UNKNOWN;
        this.weight = weight;
        this.zone = zone;
        this.registeredAt = Instant.now();
        this.lastHeartbeat = Instant.now();
        this.metadata = new HashMap<>();
    }

    /** Constructor with explicit id — used when the caller supplies a custom instance identifier. */
    public ServiceInstance(String id, String serviceName, String host, int port, int weight, String zone) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.serviceName = Objects.requireNonNull(serviceName, "serviceName must not be null");
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.port = port;
        this.healthStatus = HealthStatus.HEALTHY;
        this.weight = weight;
        this.zone = zone;
        this.registeredAt = Instant.now();
        this.lastHeartbeat = Instant.now();
        this.metadata = new HashMap<>();
    }

    // ── Getters ──

    public String getId() { return id; }
    public String getServiceName() { return serviceName; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public HealthStatus getHealthStatus() { return healthStatus; }
    public int getWeight() { return weight; }
    public String getZone() { return zone; }
    public Instant getRegisteredAt() { return registeredAt; }
    public Instant getLastHeartbeat() { return lastHeartbeat; }
    public Map<String, String> getMetadata() { return Collections.unmodifiableMap(metadata); }

    // ── Methods ──

    /** Returns the network address as "host:port". */
    public String getAddress() {
        return host + ":" + port;
    }

    /** Records a successful heartbeat, updating the timestamp. */
    public void updateHeartbeat() {
        this.lastHeartbeat = Instant.now();
        this.healthStatus = HealthStatus.HEALTHY;
    }

    /** Returns true if the instance is considered healthy enough to receive traffic. */
    public boolean isHealthy() {
        return healthStatus.isUp();
    }

    public void setHealthStatus(HealthStatus healthStatus) {
        this.healthStatus = healthStatus;
    }

    public void addMetadata(String key, String value) {
        this.metadata.put(key, value);
    }

    @Override
    public String toString() {
        return "ServiceInstance{id='%s', service='%s', address='%s', status=%s, weight=%d, zone='%s'}".formatted(
                id, serviceName, getAddress(), healthStatus, weight, zone);
    }
}
