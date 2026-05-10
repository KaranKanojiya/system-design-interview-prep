package com.systemdesign.gateway.strategy.routing;

import com.systemdesign.gateway.model.HttpRequest;
import com.systemdesign.gateway.model.Route;

import java.util.List;
import java.util.Optional;

/**
 * Strategy Pattern (GoF) — determines how requests are routed to a matching route definition.
 *
 * Flow: HttpRequest arrives → RoutingStrategy evaluates matching routes → best Route selected
 *
 * Implementations:
 *   1. PathBasedRoutingStrategy  — selects the most specific path match
 *   2. HeaderBasedRoutingStrategy — routes based on a designated header value
 */
public interface RoutingStrategy {

    /**
     * Selects the best route from the list of candidates that already matched the request path.
     *
     * @param request        the incoming HTTP request
     * @param matchingRoutes pre-filtered routes whose path pattern matched the request
     * @return the best route, or empty if none qualifies under this strategy
     */
    Optional<Route> route(HttpRequest request, List<Route> matchingRoutes);

    /**
     * Human-readable name for logging and metrics.
     */
    String getStrategyName();
}
