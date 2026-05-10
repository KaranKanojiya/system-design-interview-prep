package com.systemdesign.gateway.strategy.routing;

import com.systemdesign.gateway.model.HttpRequest;
import com.systemdesign.gateway.model.Route;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Routes requests based on a designated header value matched against route metadata.
 *
 * Flow:
 *   1. Read the configured header (e.g. "X-Version") from the incoming request
 *   2. Find a route whose metadata key "required-header-value" matches the header value
 *   3. Fall back to the first matching route if no header-specific match exists
 *
 * // wiring: plugged into RoutingEngine when header-based routing is enabled
 */
public class HeaderBasedRoutingStrategy implements RoutingStrategy {

    private final String headerName;  // e.g. "X-Version"

    /**
     * @param headerName the HTTP header name to inspect for routing decisions
     */
    public HeaderBasedRoutingStrategy(String headerName) {
        this.headerName = Objects.requireNonNull(headerName, "headerName must not be null");
    }

    @Override
    public Optional<Route> route(HttpRequest request, List<Route> matchingRoutes) {
        if (matchingRoutes == null || matchingRoutes.isEmpty()) {
            return Optional.empty();
        }

        String headerValue = request.getHeader(headerName);

        // Step 1: if the header is present, look for a route whose metadata matches
        if (headerValue != null && !headerValue.isBlank()) {
            Optional<Route> headerMatch = matchingRoutes.stream()
                    .filter(r -> headerValue.equals(r.getMetadata().get("required-header-value")))
                    .findFirst();

            if (headerMatch.isPresent()) {
                System.out.printf("[ROUTING] Header '%s=%s' matched route %s%n",
                        headerName, headerValue, headerMatch.get().getId());
                return headerMatch;
            }
        }

        // Step 2: fallback — return first matching route
        Route fallback = matchingRoutes.getFirst();
        System.out.printf("[ROUTING] No header match for '%s', falling back to route %s%n",
                headerName, fallback.getId());
        return Optional.of(fallback);
    }

    @Override
    public String getStrategyName() {
        return "HEADER_BASED(" + headerName + ")";
    }
}
