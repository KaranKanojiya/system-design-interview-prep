package com.systemdesign.gateway.strategy.routing;

import com.systemdesign.gateway.model.HttpRequest;
import com.systemdesign.gateway.model.Route;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Selects the most specific matching route based on path pattern analysis.
 *
 * Specificity order (highest → lowest):
 *   1. Exact path match (no wildcards)
 *   2. Path with fewer wildcard segments
 *   3. Longer path patterns (more segments = more specific)
 *   4. Catch-all patterns ("/**")
 *
 * Flow: matchingRoutes → sort by specificity descending → return first (most specific)
 *
 * // wiring: plugged into RoutingEngine as the default RoutingStrategy
 */
public class PathBasedRoutingStrategy implements RoutingStrategy {

    @Override
    public Optional<Route> route(HttpRequest request, List<Route> matchingRoutes) {
        if (matchingRoutes == null || matchingRoutes.isEmpty()) {
            return Optional.empty();
        }

        return matchingRoutes.stream()
                .sorted(Comparator.comparingInt(this::specificityScore).reversed()
                        .thenComparing(Comparator.comparingInt(
                                (Route r) -> r.getPathPattern().length()).reversed()))
                .findFirst();
    }

    @Override
    public String getStrategyName() {
        return "PATH_BASED";
    }

    // ── Private helpers ──

    /**
     * Scores a route by path specificity.
     *   - Exact path (no wildcards)  → 100
     *   - Contains wildcards         → 50 minus number of wildcard segments
     *   - Catch-all ("/**")          → 0
     */
    private int specificityScore(Route route) {
        String pattern = route.getPathPattern();

        // Catch-all is least specific
        if (pattern.equals("/**") || pattern.equals("**")) {
            return 0;
        }

        boolean hasWildcard = pattern.contains("*");
        if (!hasWildcard) {
            // Exact match — highest specificity
            return 100;
        }

        // Count wildcard segments — fewer wildcards = more specific
        long wildcardCount = pattern.chars()
                .filter(ch -> ch == '*')
                .count();

        return (int) (50 - wildcardCount);
    }
}
