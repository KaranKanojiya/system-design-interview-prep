package com.systemdesign.gateway.strategy.traffic;

import com.systemdesign.gateway.model.HttpRequest;
import com.systemdesign.gateway.model.TrafficSplit;

/**
 * Strategy Pattern (GoF) — determines how traffic is shaped (canary, blue-green, A/B).
 *
 * Flow: HttpRequest → TrafficStrategy evaluates TrafficSplit config → selected version name
 *
 * Implementations:
 *   1. CanaryTrafficStrategy      — weighted random selection across version splits
 *   2. HeaderBasedTrafficStrategy — routes to canary/stable based on a header flag
 */
public interface TrafficStrategy {

    /**
     * Selects which deployment version should handle the request.
     *
     * @param request the incoming HTTP request
     * @param split   the traffic split configuration defining versions and their weights
     * @return the name of the selected version (e.g. "v1-stable", "v2-canary")
     */
    String selectVersion(HttpRequest request, TrafficSplit split);

    /**
     * Human-readable name for logging and metrics.
     */
    String getStrategyName();
}
