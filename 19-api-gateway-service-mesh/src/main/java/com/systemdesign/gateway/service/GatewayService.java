package com.systemdesign.gateway.service;

import com.systemdesign.gateway.model.CircuitState;
import com.systemdesign.gateway.model.HttpRequest;
import com.systemdesign.gateway.model.HttpResponse;
import com.systemdesign.gateway.model.RequestContext;
import com.systemdesign.gateway.model.Route;
import com.systemdesign.gateway.model.ServiceInstance;
import com.systemdesign.gateway.model.AuthResult;
import com.systemdesign.gateway.model.RateLimitResult;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Facade (GoF) — single entry point orchestrating the full API gateway pipeline.
 *
 * Flow: HttpRequest → (1) route → (2) auth → (3) authz → (4) rate limit → (5) circuit breaker
 *       → (6) load balance → (7) forward → (8) record → (9) respond
 *
 * Pattern: Facade — hides the complexity of six sub-services behind one handleRequest() method.
 */
public class GatewayService {

    // Facade Pattern — single entry point for the API gateway
    private final RoutingService routingService;               // wiring: route matching and management
    private final AuthService authService;                     // wiring: authentication and authorization
    private final RateLimitService rateLimitService;           // wiring: per-route and per-client rate limiting
    private final CircuitBreakerService circuitBreakerService; // wiring: circuit breaker for upstream services
    private final LoadBalancerService loadBalancerService;     // wiring: load balancing across instances
    private final ServiceMeshService serviceMeshService;       // wiring: service mesh sidecar proxy

    public GatewayService(RoutingService routingService,
                          AuthService authService,
                          RateLimitService rateLimitService,
                          CircuitBreakerService circuitBreakerService,
                          LoadBalancerService loadBalancerService,
                          ServiceMeshService serviceMeshService) {
        this.routingService = Objects.requireNonNull(routingService, "routingService must not be null");
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.rateLimitService = Objects.requireNonNull(rateLimitService, "rateLimitService must not be null");
        this.circuitBreakerService = Objects.requireNonNull(circuitBreakerService, "circuitBreakerService must not be null");
        this.loadBalancerService = Objects.requireNonNull(loadBalancerService, "loadBalancerService must not be null");
        this.serviceMeshService = Objects.requireNonNull(serviceMeshService, "serviceMeshService must not be null");
    }

    /**
     * Handles an incoming HTTP request through the full gateway pipeline:
     *   1. Create RequestContext with traceId
     *   2. Route matching
     *   3. Authentication
     *   4. Authorization
     *   5. Rate limiting
     *   6. Circuit breaker check
     *   7. Load balancer instance selection
     *   8. Forward to upstream service (simulated)
     *   9. Record success/failure in circuit breaker
     *  10. Return response with traceId header
     *
     * @param request the incoming HTTP request
     * @return the HTTP response from the upstream service or a gateway error
     */
    public HttpResponse handleRequest(HttpRequest request) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        System.out.println("[GATEWAY] ════════════════════════════════════════════════════════");
        System.out.println("[GATEWAY] Processing request: %s %s (traceId=%s)".formatted(
                request.getMethod(), request.getPath(), traceId));

        // Step 1: Create request context
        RequestContext ctx = new RequestContext(request);
        System.out.println("[GATEWAY] Step 1: Created RequestContext — traceId=%s, clientIp=%s".formatted(
                traceId, request.getClientIp()));

        // Step 2: Route matching
        Optional<Route> matchedRoute = routingService.matchRoute(request);
        if (matchedRoute.isEmpty()) {
            System.out.println("[GATEWAY] Step 2: No matching route → 404 Not Found");
            return buildErrorResponse(404, "No route found for %s %s".formatted(
                    request.getMethod(), request.getPath()), traceId);
        }
        Route route = matchedRoute.get();
        ctx.setRoute(route);
        System.out.println("[GATEWAY] Step 2: Route matched — '%s' → target '%s'".formatted(
                route.getId(), route.getTargetService()));

        // Step 3: Authentication
        AuthResult authResult = authService.authenticate(request);
        if (!authResult.isAuthenticated()) {
            System.out.println("[GATEWAY] Step 3: Authentication failed → 401 Unauthorized");
            return buildErrorResponse(401, "Authentication failed: %s".formatted(
                    authResult.getErrorMessage()), traceId);
        }
        ctx.setAuthResult(authResult);
        System.out.println("[GATEWAY] Step 3: Authentication passed — principal='%s'".formatted(
                authResult.getPrincipal()));

        // Step 4: Authorization
        boolean authorized = authService.authorize(authResult, route);
        if (!authorized) {
            System.out.println("[GATEWAY] Step 4: Authorization denied → 403 Forbidden");
            return buildErrorResponse(403, "Access denied for route '%s'".formatted(
                    route.getId()), traceId);
        }
        System.out.println("[GATEWAY] Step 4: Authorization granted");

        // Step 5: Rate limiting
        RateLimitResult rateLimitResult = rateLimitService.checkRouteRateLimit(ctx);
        if (!rateLimitResult.isAllowed()) {
            System.out.println("[GATEWAY] Step 5: Rate limit exceeded → 429 Too Many Requests");
            return buildErrorResponse(429, "Rate limit exceeded for route '%s'".formatted(
                    route.getId()), traceId);
        }
        System.out.println("[GATEWAY] Step 5: Rate limit check passed (remaining=%d)".formatted(
                rateLimitResult.getRemainingTokens()));

        // Step 6: Circuit breaker check
        String targetService = route.getTargetService();
        boolean circuitAllowed = circuitBreakerService.allowRequest(targetService);
        if (!circuitAllowed) {
            System.out.println("[GATEWAY] Step 6: Circuit breaker OPEN for '%s' → 503 Service Unavailable".formatted(
                    targetService));
            return buildErrorResponse(503, "Service '%s' circuit breaker is OPEN".formatted(
                    targetService), traceId);
        }
        System.out.println("[GATEWAY] Step 6: Circuit breaker allows request to '%s'".formatted(targetService));

        // Step 7: Load balancer — select instance
        Optional<ServiceInstance> instance = loadBalancerService.selectInstance(targetService, request);
        if (instance.isEmpty()) {
            System.out.println("[GATEWAY] Step 7: No healthy instances for '%s' → 503 Service Unavailable".formatted(
                    targetService));
            return buildErrorResponse(503, "No healthy instances for service '%s'".formatted(
                    targetService), traceId);
        }
        System.out.println("[GATEWAY] Step 7: Selected instance %s for '%s'".formatted(
                instance.get().getAddress(), targetService));

        // Step 8: Forward to upstream service (simulated)
        long latencyMs = ThreadLocalRandom.current().nextLong(10, 101);
        boolean requestFailed = ThreadLocalRandom.current().nextDouble() < 0.05;

        try {
            Thread.sleep(latencyMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("[GATEWAY] Step 8: Forwarded to %s — latency=%dms, success=%s".formatted(
                instance.get().getAddress(), latencyMs, !requestFailed));

        // Step 9: Record success/failure in circuit breaker
        if (requestFailed) {
            circuitBreakerService.recordFailure(targetService);
            System.out.println("[GATEWAY] Step 9: Recorded FAILURE for '%s'".formatted(targetService));
            return buildErrorResponse(502, "Upstream service '%s' returned an error".formatted(
                    targetService), traceId);
        }
        circuitBreakerService.recordSuccess(targetService);
        System.out.println("[GATEWAY] Step 9: Recorded SUCCESS for '%s'".formatted(targetService));

        // Step 10: Build and return response with traceId header
        HttpResponse response = new HttpResponse.Builder(200)
                .body("{\"service\":\"%s\",\"instance\":\"%s\",\"message\":\"OK\"}".formatted(
                        targetService, instance.get().getAddress()))
                .header("X-Trace-Id", traceId)
                .header("X-Gateway-Service", targetService)
                .header("X-Gateway-Instance", instance.get().getAddress())
                .latencyMs(latencyMs)
                .serviceName(targetService)
                .build();

        System.out.println("[GATEWAY] Step 10: Response — status=200, latency=%dms, traceId=%s".formatted(
                latencyMs, traceId));
        System.out.println("[GATEWAY] ════════════════════════════════════════════════════════");
        return response;
    }

    /**
     * Delegates route registration to the routing service.
     *
     * @param route the route definition to register
     */
    public void registerRoute(Route route) {
        routingService.registerRoute(route);
    }

    /**
     * Returns all registered routes.
     */
    public java.util.List<Route> getAllRoutes() {
        return routingService.getAllRoutes();
    }

    /**
     * Returns a formatted summary of the gateway's current service status.
     */
    public String getServiceStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════╗\n");
        sb.append("║        API Gateway Service Status        ║\n");
        sb.append("╠══════════════════════════════════════════╣\n");

        // Routes
        var routes = routingService.getAllRoutes();
        sb.append("║ Routes: %d registered                    \n".formatted(routes.size()));
        for (Route r : routes) {
            sb.append("║   %s → %s\n".formatted(r.getPathPattern(), r.getTargetService()));
        }

        // Circuit breakers
        Map<String, CircuitState> circuits = circuitBreakerService.getCircuitSummary();
        sb.append("║ Circuit Breakers: %d services             \n".formatted(circuits.size()));
        circuits.forEach((service, state) ->
                sb.append("║   %s → %s\n".formatted(service, state)));

        sb.append("╚══════════════════════════════════════════╝");
        return sb.toString();
    }

    // ── Private helpers ──

    private HttpResponse buildErrorResponse(int statusCode, String message, String traceId) {
        return new HttpResponse.Builder(statusCode)
                .body("{\"error\":\"%s\"}".formatted(message))
                .header("Content-Type", "application/json")
                .header("X-Trace-Id", traceId)
                .build();
    }
}
