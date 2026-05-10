package com.systemdesign.gateway.strategy.loadbalancing;

import com.systemdesign.gateway.model.HttpRequest;
import com.systemdesign.gateway.model.ServiceInstance;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Weighted random load balancer — instances with higher weight receive proportionally more traffic.
 *
 * Flow:
 *   1. Filter to healthy instances
 *   2. Compute totalWeight = sum of all healthy instance weights
 *   3. Pick a random number in [0, totalWeight)
 *   4. Iterate instances, accumulating weight — select when accumulated >= random
 *
 * Example: instances A(weight=3), B(weight=1) → A gets ~75% of traffic, B gets ~25%.
 *
 * // wiring: used when weighted traffic distribution is configured for a service
 */
public class WeightedLoadBalancer implements LoadBalancingStrategy {

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

        // Step 2: compute total weight
        int totalWeight = healthy.stream()
                .mapToInt(ServiceInstance::getWeight)
                .sum();

        if (totalWeight <= 0) {
            // Fallback: if all weights are zero, pick first healthy
            return Optional.of(healthy.getFirst());
        }

        // Step 3: weighted random selection
        int random = ThreadLocalRandom.current().nextInt(totalWeight);
        int accumulated = 0;

        for (ServiceInstance instance : healthy) {
            accumulated += instance.getWeight();
            if (accumulated > random) {
                System.out.printf("[WEIGHTED] Selected instance %s (weight=%d, totalWeight=%d)%n",
                        instance.getId(), instance.getWeight(), totalWeight);
                return Optional.of(instance);
            }
        }

        // Should not reach here, but safe fallback
        return Optional.of(healthy.getLast());
    }

    @Override
    public String getStrategyName() {
        return "WEIGHTED";
    }
}
