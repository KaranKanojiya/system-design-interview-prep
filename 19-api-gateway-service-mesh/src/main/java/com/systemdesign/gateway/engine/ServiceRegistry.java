package com.systemdesign.gateway.engine;

// Wiring: ServiceRegistry provides in-memory service discovery.
// Used by LoadBalancerService -> looks up healthy instances -> routes to one.
// Also used by HealthCheckService -> heartbeat + eviction of stale instances.

import com.systemdesign.gateway.model.HealthStatus;
import com.systemdesign.gateway.model.ServiceInstance;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory service discovery registry. Services register instances on startup,
 * send periodic heartbeats, and are evicted when stale.
 */
public class ServiceRegistry {

    // serviceName -> list of registered instances
    private final Map<String, List<ServiceInstance>> instances = new ConcurrentHashMap<>();

    /** Registers a service instance. */
    public void register(ServiceInstance instance) {
        instances.computeIfAbsent(instance.getServiceName(), k -> new ArrayList<>())
                .add(instance);
        System.out.println("[REGISTRY] Registered: " + instance.getId()
                + " for service=" + instance.getServiceName()
                + " at " + instance.getHost() + ":" + instance.getPort());
    }

    /** Deregisters an instance by its ID. */
    public void deregister(String instanceId) {
        instances.values().forEach(list ->
                list.removeIf(inst -> inst.getId().equals(instanceId)));
        System.out.println("[REGISTRY] Deregistered: " + instanceId);
    }

    /** Returns only HEALTHY instances for the given service. */
    public List<ServiceInstance> getInstances(String serviceName) {
        return getAllInstances(serviceName).stream()
                .filter(inst -> inst.getHealthStatus() == HealthStatus.HEALTHY)
                .collect(Collectors.toList());
    }

    /** Returns all instances (including unhealthy) for the given service. */
    public List<ServiceInstance> getAllInstances(String serviceName) {
        return List.copyOf(instances.getOrDefault(serviceName, List.of()));
    }

    /** Returns the set of all registered service names. */
    public Set<String> getServiceNames() {
        return Set.copyOf(instances.keySet());
    }

    /** Returns a snapshot of all services and their instances. */
    public Map<String, List<ServiceInstance>> getAllServices() {
        Map<String, List<ServiceInstance>> snapshot = new java.util.HashMap<>();
        instances.forEach((name, list) -> snapshot.put(name, List.copyOf(list)));
        return snapshot;
    }

    /** Updates the heartbeat timestamp for a specific instance. */
    public void heartbeat(String instanceId) {
        findInstance(instanceId).ifPresent(inst -> {
            inst.updateHeartbeat();
        });
    }

    /** Marks an instance as UNHEALTHY. */
    public void markUnhealthy(String instanceId) {
        findInstance(instanceId).ifPresent(inst -> {
            inst.setHealthStatus(HealthStatus.UNHEALTHY);
            System.out.println("[REGISTRY] Marked UNHEALTHY: " + instanceId);
        });
    }

    /** Marks an instance as HEALTHY. */
    public void markHealthy(String instanceId) {
        findInstance(instanceId).ifPresent(inst -> {
            inst.setHealthStatus(HealthStatus.HEALTHY);
            System.out.println("[REGISTRY] Marked HEALTHY: " + instanceId);
        });
    }

    /**
     * Evicts instances whose last heartbeat is older than the given timeout.
     * Returns the count of evicted instances.
     */
    public int evictStale(Duration timeout) {
        Instant cutoff = Instant.now().minus(timeout);
        int evicted = 0;

        for (List<ServiceInstance> list : instances.values()) {
            int before = list.size();
            list.removeIf(inst -> inst.getLastHeartbeat().isBefore(cutoff));
            evicted += (before - list.size());
        }

        if (evicted > 0) {
            System.out.println("[REGISTRY] Evicted " + evicted + " stale instance(s)");
        }
        return evicted;
    }

    // ── Internal helper ──

    /** Finds an instance by ID across all services. */
    private Optional<ServiceInstance> findInstance(String instanceId) {
        return instances.values().stream()
                .flatMap(List::stream)
                .filter(inst -> inst.getId().equals(instanceId))
                .findFirst();
    }
}
