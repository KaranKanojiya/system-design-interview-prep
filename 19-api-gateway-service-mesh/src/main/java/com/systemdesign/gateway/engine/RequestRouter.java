package com.systemdesign.gateway.engine;

// Wiring: RequestRouter matches incoming HttpRequests to configured Routes.
// Used by GatewayService -> receives request -> finds matching route -> forwards to upstream.
// Routes are sorted by priority (lower value = higher priority).

import com.systemdesign.gateway.model.HttpMethod;
import com.systemdesign.gateway.model.HttpRequest;
import com.systemdesign.gateway.model.Route;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Matches incoming HTTP requests to the best-fit route based on path pattern,
 * HTTP method, and route priority.
 */
public class RequestRouter {

    // Routes sorted by priority — lower value means higher priority
    private final List<Route> routes = new ArrayList<>();

    /** Adds a route and re-sorts by priority (lower = higher priority). */
    public void addRoute(Route route) {
        routes.add(route);
        routes.sort(Comparator.comparingInt(Route::getPriority));
        System.out.println("[ROUTER] Added route: " + route.getId()
                + " pattern=" + route.getPathPattern()
                + " priority=" + route.getPriority());
    }

    /**
     * Finds the first route matching the request.
     * Match criteria: path matches pathPattern (supports ** wildcard),
     * method is in the route's allowed methods, and route is enabled.
     */
    public Optional<Route> match(HttpRequest request) {
        return routes.stream()
                .filter(Route::isEnabled)
                .filter(route -> route.getMethods().contains(request.getMethod()))
                .filter(route -> matchesPath(route.getPathPattern(), request.getPath()))
                .findFirst();
    }

    /** Removes a route by its ID. */
    public void removeRoute(String routeId) {
        routes.removeIf(route -> route.getId().equals(routeId));
        System.out.println("[ROUTER] Removed route: " + routeId);
    }

    /** Returns all configured routes (sorted by priority). */
    public List<Route> getAllRoutes() {
        return List.copyOf(routes);
    }

    // ── Path matching with ** wildcard support ──

    /**
     * Matches a path against a pattern.
     * Supports ** wildcard: "/api/users/**" matches "/api/users/123/orders".
     * Exact match is also supported: "/health" matches "/health".
     */
    private boolean matchesPath(String pattern, String path) {
        // 1. Exact match
        if (pattern.equals(path)) {
            return true;
        }

        // 2. ** wildcard — matches any subpath
        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 3); // strip "/**"
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }

        // 3. Single-segment wildcard: /api/users/* matches /api/users/123
        if (pattern.contains("*") && !pattern.contains("**")) {
            String regex = pattern
                    .replace("*", "[^/]+");
            return path.matches(regex);
        }

        return false;
    }
}
