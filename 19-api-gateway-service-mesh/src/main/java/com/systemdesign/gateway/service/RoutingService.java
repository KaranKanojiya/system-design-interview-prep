package com.systemdesign.gateway.service;

import com.systemdesign.gateway.engine.RequestRouter;
import com.systemdesign.gateway.model.HttpRequest;
import com.systemdesign.gateway.model.Route;
import com.systemdesign.gateway.strategy.routing.RoutingStrategy;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Request routing management — resolves incoming requests to target service routes.
 *
 * Flow: HttpRequest → matchRoute() finds candidates → routeRequest() applies strategy → best Route returned
 *
 * Pattern: delegates path matching to RequestRouter and route selection to RoutingStrategy (GoF Strategy).
 */
public class RoutingService {

    private final RequestRouter router;             // wiring: path-pattern matching engine
    private final RoutingStrategy routingStrategy;  // wiring: strategy for selecting best route from candidates

    public RoutingService(RequestRouter router, RoutingStrategy routingStrategy) {
        this.router = Objects.requireNonNull(router, "router must not be null");
        this.routingStrategy = Objects.requireNonNull(routingStrategy, "routingStrategy must not be null");
    }

    /**
     * Registers a new route in the routing table.
     *
     * @param route the route definition to add
     */
    public void registerRoute(Route route) {
        Objects.requireNonNull(route, "route must not be null");
        router.addRoute(route);
        System.out.println("[ROUTING] Registered route: id=%s, pattern=%s → target=%s".formatted(
                route.getId(), route.getPathPattern(), route.getTargetService()));
    }

    /**
     * Matches the incoming request to the first qualifying route via the router.
     *
     * @param request the incoming HTTP request
     * @return matching route, or empty if no route matches
     */
    public Optional<Route> matchRoute(HttpRequest request) {
        Optional<Route> matched = router.match(request);
        if (matched.isPresent()) {
            System.out.println("[ROUTING] Matched request %s %s → route '%s' (target: %s)".formatted(
                    request.getMethod(), request.getPath(),
                    matched.get().getId(), matched.get().getTargetService()));
        } else {
            System.out.println("[ROUTING] No route matched for %s %s".formatted(
                    request.getMethod(), request.getPath()));
        }
        return matched;
    }

    /**
     * Selects the best route from a list of candidates using the configured routing strategy.
     *
     * @param request the incoming HTTP request
     * @param routes  candidate routes that matched the request path
     * @return the best route chosen by the strategy, or empty
     */
    public Optional<Route> routeRequest(HttpRequest request, List<Route> routes) {
        Optional<Route> selected = routingStrategy.route(request, routes);
        if (selected.isPresent()) {
            System.out.println("[ROUTING] Strategy '%s' selected route '%s' from %d candidates".formatted(
                    routingStrategy.getStrategyName(), selected.get().getId(), routes.size()));
        } else {
            System.out.println("[ROUTING] Strategy '%s' found no suitable route among %d candidates".formatted(
                    routingStrategy.getStrategyName(), routes.size()));
        }
        return selected;
    }

    /**
     * Removes a route from the routing table by its identifier.
     *
     * @param routeId the route to remove
     */
    public void removeRoute(String routeId) {
        router.removeRoute(routeId);
        System.out.println("[ROUTING] Removed route: id=%s".formatted(routeId));
    }

    /**
     * Returns all registered routes.
     */
    public List<Route> getAllRoutes() {
        return router.getAllRoutes();
    }
}
