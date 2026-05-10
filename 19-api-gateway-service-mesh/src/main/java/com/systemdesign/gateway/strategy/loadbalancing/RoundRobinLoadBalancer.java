package com.systemdesign.gateway.strategy.loadbalancing;

import com.systemdesign.gateway.model.HttpRequest;
import com.systemdesign.gateway.model.ServiceInstance;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Round-robin load balancer that cycles through healthy instances sequentially.
 *
 * Flow:
 *   1. Filter to healthy instances only
 *   2. Increment atomic counter
 *   3. Select instance at (counter % healthyCount)
 *
 * Thread-safe via AtomicInteger — suitable for concurrent gateway threads.
 *
 * // wiring: default LoadBalancingStrategy in the gateway engine
 */
public class RoundRobinLoadBalancer implements LoadBalancingStrategy {

    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public Optional<ServiceInstance> selectInstance(List<ServiceInstance> instances, HttpRequest request) {
        if (instances == null || instances.isEmpty()) {
            return Optional.empty();
        }

        // Step 1: filter to healthy instances
        List<ServiceInstance> healthy = instances.stream()
                .filter(ServiceInstance::isHealthy)
                .toList();

        if (healthy.isEmpty()) {
            return Optional.empty();
        }

        // Step 2: round-robin selection
        int index = Math.abs(counter.getAndIncrement() % healthy.size());
        ServiceInstance selected = healthy.get(index);

        System.out.printf("[ROUND_ROBIN] Selected instance %s (index=%d of %d healthy)%n",
                selected.getId(), index, healthy.size());

        return Optional.of(selected);
    }

    @Override
    public String getStrategyName() {
        return "ROUND_ROBIN";
    }
}
