package com.systemdesign.gateway.service;

import com.systemdesign.gateway.model.HttpRequest;
import com.systemdesign.gateway.model.ServiceInstance;
import com.systemdesign.gateway.engine.ServiceRegistry;
import com.systemdesign.gateway.strategy.loadbalancing.LoadBalancingStrategy;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Load balancing across service instances — distributes requests to healthy backends.
 *
 * Flow: serviceName → registry lookup (healthy instances) → LoadBalancingStrategy selects one → return instance
 *
 * Pattern: Strategy (GoF) — the balancing algorithm (round-robin, weighted, least-connections) is swappable.
 */
public class LoadBalancerService {

    private final ServiceRegistry registry;         // wiring: service instance registry
    private volatile LoadBalancingStrategy strategy; // wiring: pluggable load balancing algorithm

    public LoadBalancerService(ServiceRegistry registry, LoadBalancingStrategy strategy) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.strategy = Objects.requireNonNull(strategy, "strategy must not be null");
    }

    /**
     * Selects a healthy service instance for the given service name using the active strategy.
     *
     * @param serviceName the logical service name
     * @param request     the incoming HTTP request (strategies may use request data for affinity)
     * @return a selected instance, or empty if no healthy instances are available
     */
    public Optional<ServiceInstance> selectInstance(String serviceName, HttpRequest request) {
        List<ServiceInstance> healthy = getHealthyInstances(serviceName);

        if (healthy.isEmpty()) {
            System.out.println("[LOAD BALANCER] No healthy instances for service '%s'".formatted(serviceName));
            return Optional.empty();
        }

        ServiceInstance selected = strategy.selectInstance(healthy, request).orElse(null);
        if (selected == null) {
            System.out.println("[LOAD BALANCER] Strategy returned no instance for service '%s'".formatted(serviceName));
            return Optional.empty();
        }
        System.out.println("[LOAD BALANCER] Strategy '%s' selected instance '%s' (%s) for service '%s' from %d healthy instances".formatted(
                strategy.getStrategyName(), selected.getId(), selected.getAddress(),
                serviceName, healthy.size()));
        return Optional.of(selected);
    }

    /**
     * Registers a new service instance in the service registry.
     *
     * @param instance the service instance to register
     */
    public void registerInstance(ServiceInstance instance) {
        Objects.requireNonNull(instance, "instance must not be null");
        registry.register(instance);
        System.out.println("[LOAD BALANCER] Registered instance: service='%s', address=%s".formatted(
                instance.getServiceName(), instance.getAddress()));
    }

    /**
     * Deregisters a service instance from the service registry.
     *
     * @param instanceId the instance identifier to remove
     */
    public void deregisterInstance(String instanceId) {
        registry.deregister(instanceId);
        System.out.println("[LOAD BALANCER] Deregistered instance: id=%s".formatted(instanceId));
    }

    /**
     * Returns all healthy instances for a given service name.
     *
     * @param serviceName the logical service name
     * @return list of healthy instances (HEALTHY or DEGRADED status)
     */
    public List<ServiceInstance> getHealthyInstances(String serviceName) {
        List<ServiceInstance> healthy = registry.getInstances(serviceName);
        System.out.println("[LOAD BALANCER] Service '%s' has %d healthy instance(s)".formatted(
                serviceName, healthy.size()));
        return healthy;
    }

    /**
     * Swaps the load balancing strategy at runtime (Strategy Pattern hot-swap).
     *
     * @param strategy the new load balancing strategy
     */
    public void setStrategy(LoadBalancingStrategy strategy) {
        this.strategy = Objects.requireNonNull(strategy, "strategy must not be null");
        System.out.println("[LOAD BALANCER] Strategy swapped to: %s".formatted(strategy.getStrategyName()));
    }
}
