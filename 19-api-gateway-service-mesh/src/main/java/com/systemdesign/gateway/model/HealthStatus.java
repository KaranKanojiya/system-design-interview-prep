package com.systemdesign.gateway.model;

/**
 * Health status of a service instance in the service mesh.
 *
 * Used by: ServiceInstance, health-check probes, load balancer instance filtering.
 */
public enum HealthStatus {

    HEALTHY,
    UNHEALTHY,
    DEGRADED,
    UNKNOWN;

    /**
     * Returns true if the instance is considered "up" and eligible for traffic.
     * HEALTHY and DEGRADED instances can still receive requests.
     */
    public boolean isUp() {
        return this == HEALTHY || this == DEGRADED;
    }
}
