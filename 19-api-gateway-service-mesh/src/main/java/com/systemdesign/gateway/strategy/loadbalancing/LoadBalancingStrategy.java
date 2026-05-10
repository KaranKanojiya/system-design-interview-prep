package com.systemdesign.gateway.strategy.loadbalancing;

import com.systemdesign.gateway.model.HttpRequest;
import com.systemdesign.gateway.model.ServiceInstance;

import java.util.List;
import java.util.Optional;

/**
 * Strategy Pattern (GoF) — determines which service instance handles a request.
 *
 * Flow: Request routed to a service → LoadBalancingStrategy picks one healthy instance
 *
 * Implementations:
 *   1. RoundRobinLoadBalancer      — cycles through instances sequentially
 *   2. WeightedLoadBalancer        — weighted random selection
 *   3. ConsistentHashLoadBalancer  — hash-ring for cache-affinity routing
 */
public interface LoadBalancingStrategy {

    /**
     * Selects a service instance from the available pool.
     *
     * @param instances all registered instances for the target service
     * @param request   the incoming HTTP request (may influence selection, e.g. for hashing)
     * @return the chosen instance, or empty if no healthy instance is available
     */
    Optional<ServiceInstance> selectInstance(List<ServiceInstance> instances, HttpRequest request);

    /**
     * Human-readable name for logging and metrics.
     */
    String getStrategyName();
}
