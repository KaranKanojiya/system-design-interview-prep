package com.systemdesign.gateway.controller;

// Wiring: GatewayController is the REST-like facade that dispatches simulated HTTP requests
// to the GatewayService pipeline. Each method logs [CONTROLLER] with a simulated HTTP method+path.

import com.systemdesign.gateway.model.CircuitState;
import com.systemdesign.gateway.model.HttpRequest;
import com.systemdesign.gateway.model.HttpResponse;
import com.systemdesign.gateway.model.Route;
import com.systemdesign.gateway.model.ServiceInstance;
import com.systemdesign.gateway.service.CircuitBreakerService;
import com.systemdesign.gateway.service.GatewayService;
import com.systemdesign.gateway.service.LoadBalancerService;

import java.util.Map;

/**
 * REST-like controller for the API Gateway.
 *
 * Simulates HTTP endpoints:
 *   POST /gateway/request          — handleRequest
 *   POST /gateway/routes           — registerRoute
 *   POST /gateway/services         — registerServiceInstance
 *   GET  /gateway/circuit-breakers — getCircuitBreakerStatus
 *   GET  /gateway/status           — getServiceStatus
 */
public class GatewayController {

    private final GatewayService gatewayService;             // facade for the full gateway pipeline
    private final LoadBalancerService loadBalancerService;   // for service instance registration
    private final CircuitBreakerService circuitBreakerService; // for circuit breaker status queries

    public GatewayController(GatewayService gatewayService,
                             LoadBalancerService loadBalancerService,
                             CircuitBreakerService circuitBreakerService) {
        this.gatewayService = gatewayService;
        this.loadBalancerService = loadBalancerService;
        this.circuitBreakerService = circuitBreakerService;
    }

    // ── POST /gateway/request ───────────────────────────────────────────

    /**
     * Handles an incoming HTTP request through the full gateway pipeline:
     * auth → rate limit → route → circuit breaker → load balance → forward.
     */
    public HttpResponse handleRequest(HttpRequest request) {
        System.out.println("[CONTROLLER] POST /gateway/request — " + request.getMethod() + " " + request.getPath());
        return gatewayService.handleRequest(request);
    }

    // ── POST /gateway/routes ────────────────────────────────────────────

    /**
     * Registers a new route in the gateway's routing table.
     */
    public void registerRoute(Route route) {
        System.out.println("[CONTROLLER] POST /gateway/routes — pattern=" + route.getPathPattern()
                + " target=" + route.getTargetService());
        gatewayService.registerRoute(route);
    }

    // ── POST /gateway/services ──────────────────────────────────────────

    /**
     * Registers a service instance in the load balancer's service registry.
     */
    public void registerServiceInstance(ServiceInstance instance) {
        System.out.println("[CONTROLLER] POST /gateway/services — service=" + instance.getServiceName()
                + " address=" + instance.getAddress());
        loadBalancerService.registerInstance(instance);
    }

    // ── GET /gateway/circuit-breakers ───────────────────────────────────

    /**
     * Returns the current circuit breaker state for all tracked services.
     */
    public Map<String, CircuitState> getCircuitBreakerStatus() {
        System.out.println("[CONTROLLER] GET /gateway/circuit-breakers");
        return circuitBreakerService.getCircuitSummary();
    }

    // ── GET /gateway/status ─────────────────────────────────────────────

    /**
     * Returns a summary of the gateway's operational status.
     */
    public String getServiceStatus() {
        System.out.println("[CONTROLLER] GET /gateway/status");
        return gatewayService.getServiceStatus();
    }
}
