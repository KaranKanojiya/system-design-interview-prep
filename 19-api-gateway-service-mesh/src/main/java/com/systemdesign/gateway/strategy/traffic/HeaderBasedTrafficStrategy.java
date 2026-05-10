package com.systemdesign.gateway.strategy.traffic;

import com.systemdesign.gateway.model.HttpRequest;
import com.systemdesign.gateway.model.TrafficSplit;

import java.util.Comparator;
import java.util.Map;

/**
 * Header-based traffic strategy — routes to canary or stable based on a request header.
 *
 * Flow:
 *   1. Check for "X-Canary: true" header on the request
 *   2. If present → route to the version with the lowest weight (the canary)
 *   3. If absent  → route to the version with the highest weight (the stable)
 *
 * Use case: QA or internal users set "X-Canary: true" to opt in to the canary version
 * while all other traffic goes to the stable production version.
 *
 * // wiring: used by TrafficShapingFilter for header-driven canary opt-in
 */
public class HeaderBasedTrafficStrategy implements TrafficStrategy {

    private static final String CANARY_HEADER = "X-Canary";

    @Override
    public String selectVersion(HttpRequest request, TrafficSplit split) {
        Map<String, Integer> weights = split.getSplits();

        if (weights.isEmpty()) {
            throw new IllegalArgumentException("TrafficSplit has no version weights configured");
        }

        boolean isCanaryRequest = "true".equalsIgnoreCase(request.getHeader(CANARY_HEADER));

        String selectedVersion;
        if (isCanaryRequest) {
            // Route to the version with the lowest weight (canary)
            selectedVersion = weights.entrySet().stream()
                    .min(Comparator.comparingInt(Map.Entry::getValue))
                    .map(Map.Entry::getKey)
                    .orElseThrow();

            System.out.printf("[TRAFFIC] X-Canary header detected → routing to canary version '%s'%n",
                    selectedVersion);
        } else {
            // Route to the version with the highest weight (stable)
            selectedVersion = weights.entrySet().stream()
                    .max(Comparator.comparingInt(Map.Entry::getValue))
                    .map(Map.Entry::getKey)
                    .orElseThrow();

            System.out.printf("[TRAFFIC] No canary header → routing to stable version '%s'%n",
                    selectedVersion);
        }

        return selectedVersion;
    }

    @Override
    public String getStrategyName() {
        return "HEADER_BASED_TRAFFIC";
    }
}
