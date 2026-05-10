package com.systemdesign.gateway.strategy.traffic;

import com.systemdesign.gateway.model.HttpRequest;
import com.systemdesign.gateway.model.TrafficSplit;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Canary traffic strategy — weighted random selection across deployment versions.
 *
 * Flow:
 *   1. Read version weights from TrafficSplit (e.g. {"v1-stable": 90, "v2-canary": 10})
 *   2. Compute totalWeight = sum of all version weights
 *   3. Pick a random number in [0, totalWeight)
 *   4. Iterate versions accumulating weight — select when accumulated > random
 *
 * Example: v1-stable(90) + v2-canary(10) → canary gets ~10% of traffic.
 *
 * // wiring: used by TrafficShapingFilter for gradual canary rollouts
 */
public class CanaryTrafficStrategy implements TrafficStrategy {

    @Override
    public String selectVersion(HttpRequest request, TrafficSplit split) {
        Map<String, Integer> weights = split.getSplits();

        if (weights.isEmpty()) {
            throw new IllegalArgumentException("TrafficSplit has no version weights configured");
        }

        // Step 1: compute total weight
        int totalWeight = weights.values().stream()
                .mapToInt(Integer::intValue)
                .sum();

        if (totalWeight <= 0) {
            // Fallback: return first version
            String fallback = weights.keySet().iterator().next();
            System.out.printf("[CANARY] All weights zero, falling back to '%s'%n", fallback);
            return fallback;
        }

        // Step 2: weighted random selection
        int random = ThreadLocalRandom.current().nextInt(totalWeight);
        int accumulated = 0;

        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            accumulated += entry.getValue();
            if (accumulated > random) {
                System.out.printf("[CANARY] Selected version '%s' (weight=%d/%d)%n",
                        entry.getKey(), entry.getValue(), totalWeight);
                return entry.getKey();
            }
        }

        // Should not reach here, but safe fallback
        String last = weights.keySet().stream().reduce((a, b) -> b).orElseThrow();
        System.out.printf("[CANARY] Fallback to last version '%s'%n", last);
        return last;
    }

    @Override
    public String getStrategyName() {
        return "CANARY";
    }
}
