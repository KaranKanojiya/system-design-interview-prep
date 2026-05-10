package com.systemdesign.gateway.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Traffic split configuration for canary and blue-green deployments.
 *
 * Example: {"v1": 90, "v2": 10} sends 90% of traffic to v1 and 10% to v2.
 *
 * Flow: Route resolved → TrafficSplit.selectVersion() → pick target version → forward
 */
public class TrafficSplit {

    private final String name;                      // deployment name, e.g. "user-service-canary"
    private final Map<String, Integer> splits;      // version → weight mapping

    public TrafficSplit(String name, Map<String, Integer> splits) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.splits = Collections.unmodifiableMap(Objects.requireNonNull(splits, "splits must not be null"));
        if (splits.isEmpty()) {
            throw new IllegalArgumentException("splits must not be empty");
        }
    }

    // ── Getters ──

    public String getName() { return name; }
    public Map<String, Integer> getSplits() { return splits; }

    /**
     * Selects a version using weighted random selection.
     *
     * Algorithm:
     *   1. Sum all weights
     *   2. Pick a random number in [0, totalWeight)
     *   3. Walk through entries, subtracting each weight until the random value is exhausted
     */
    public String selectVersion() {
        int totalWeight = splits.values().stream().mapToInt(Integer::intValue).sum();
        int random = ThreadLocalRandom.current().nextInt(totalWeight);

        for (Map.Entry<String, Integer> entry : splits.entrySet()) {
            random -= entry.getValue();
            if (random < 0) {
                return entry.getKey();
            }
        }

        // fallback — should not reach here with valid weights
        return splits.keySet().iterator().next();
    }

    @Override
    public String toString() {
        return "TrafficSplit{name='%s', splits=%s}".formatted(name, splits);
    }
}
