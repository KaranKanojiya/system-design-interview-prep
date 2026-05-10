package com.systemdesign.gateway.config;

// Wiring: AppConfig is the composition root / factory for the entire API Gateway & Service Mesh.
// All concrete class instantiation happens here — lazily initialized, with strategy setters
// that clear dependent objects so the graph is rebuilt on the next access.

import com.systemdesign.gateway.controller.GatewayController;
import com.systemdesign.gateway.display.GatewayStatsDisplay;
import com.systemdesign.gateway.engine.CircuitBreakerEngine;
import com.systemdesign.gateway.engine.RateLimiterEngine;
import com.systemdesign.gateway.engine.RequestRouter;
import com.systemdesign.gateway.engine.ServiceRegistry;
import com.systemdesign.gateway.engine.TlsEngine;
import com.systemdesign.gateway.model.RetryPolicy;
import com.systemdesign.gateway.model.ServiceMeshConfig;
import com.systemdesign.gateway.repository.InMemoryRouteRepository;
import com.systemdesign.gateway.repository.InMemoryServiceInstanceRepository;
import com.systemdesign.gateway.service.AuthService;
import com.systemdesign.gateway.service.CircuitBreakerService;
import com.systemdesign.gateway.service.GatewayService;
import com.systemdesign.gateway.service.LoadBalancerService;
import com.systemdesign.gateway.service.RateLimitService;
import com.systemdesign.gateway.service.RoutingService;
import com.systemdesign.gateway.service.ServiceMeshService;
import com.systemdesign.gateway.strategy.auth.AuthStrategy;
import com.systemdesign.gateway.strategy.auth.JwtAuthStrategy;
import com.systemdesign.gateway.strategy.loadbalancing.LoadBalancingStrategy;
import com.systemdesign.gateway.strategy.loadbalancing.RoundRobinLoadBalancer;
import com.systemdesign.gateway.strategy.routing.PathBasedRoutingStrategy;
import com.systemdesign.gateway.strategy.routing.RoutingStrategy;
import com.systemdesign.gateway.strategy.traffic.CanaryTrafficStrategy;
import com.systemdesign.gateway.strategy.traffic.TrafficStrategy;

import java.util.Map;

/**
 * AppConfig — FACTORY / Composition Root.
 *
 * The ONLY place where "new ConcreteClass()" appears. All fields are lazily initialized.
 * Strategy setters clear dependent objects so the wiring graph rebuilds on next access.
 *
 * Dependency wiring graph:
 *
 *   Repositories: RouteRepository, ServiceInstanceRepository
 *       ↑
 *   Engines: RequestRouter, CircuitBreakerEngine, RateLimiterEngine, ServiceRegistry, TlsEngine
 *       ↑
 *   Strategies: RoutingStrategy, LoadBalancingStrategy, AuthStrategy, TrafficStrategy
 *       ↑
 *   Services: RoutingService, AuthService, RateLimitService, CircuitBreakerService,
 *             LoadBalancerService, ServiceMeshService → GatewayService (FACADE)
 *       ↑
 *   Controller: GatewayController
 *       ↑
 *   Display: GatewayStatsDisplay
 */
public class AppConfig {

    // ── Repositories ────────────────────────────────────────────────────
    private InMemoryRouteRepository routeRepository;
    private InMemoryServiceInstanceRepository serviceInstanceRepository;

    // ── Engines ─────────────────────────────────────────────────────────
    private RequestRouter requestRouter;
    private CircuitBreakerEngine circuitBreakerEngine;
    private RateLimiterEngine rateLimiterEngine;
    private ServiceRegistry serviceRegistry;
    private TlsEngine tlsEngine;

    // ── Strategies (swappable via setters) ───────────────────────────────
    private RoutingStrategy routingStrategy;
    private LoadBalancingStrategy loadBalancingStrategy;
    private AuthStrategy authStrategy;
    private TrafficStrategy trafficStrategy;

    // ── Services ─────────────────────────────────────────────────────────
    private RoutingService routingService;
    private AuthService authService;
    private RateLimitService rateLimitService;
    private CircuitBreakerService circuitBreakerService;
    private LoadBalancerService loadBalancerService;
    private ServiceMeshService serviceMeshService;
    private GatewayService gatewayService;

    // ── Mesh config ──────────────────────────────────────────────────────
    private ServiceMeshConfig serviceMeshConfig;

    // ── Controller & Display ─────────────────────────────────────────────
    private GatewayController gatewayController;
    private GatewayStatsDisplay gatewayStatsDisplay;

    // ══════════════════════════════════════════════════════════════════════
    //  Repository getters (lazy)
    // ══════════════════════════════════════════════════════════════════════

    public InMemoryRouteRepository getRouteRepository() {
        if (routeRepository == null) {
            routeRepository = new InMemoryRouteRepository();
        }
        return routeRepository;
    }

    public InMemoryServiceInstanceRepository getServiceInstanceRepository() {
        if (serviceInstanceRepository == null) {
            serviceInstanceRepository = new InMemoryServiceInstanceRepository();
        }
        return serviceInstanceRepository;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Engine getters (lazy)
    // ══════════════════════════════════════════════════════════════════════

    public RequestRouter getRequestRouter() {
        if (requestRouter == null) {
            requestRouter = new RequestRouter();
        }
        return requestRouter;
    }

    public CircuitBreakerEngine getCircuitBreakerEngine() {
        if (circuitBreakerEngine == null) {
            circuitBreakerEngine = new CircuitBreakerEngine();
        }
        return circuitBreakerEngine;
    }

    public RateLimiterEngine getRateLimiterEngine() {
        if (rateLimiterEngine == null) {
            rateLimiterEngine = new RateLimiterEngine();
        }
        return rateLimiterEngine;
    }

    public ServiceRegistry getServiceRegistry() {
        if (serviceRegistry == null) {
            serviceRegistry = new ServiceRegistry();
        }
        return serviceRegistry;
    }

    public TlsEngine getTlsEngine() {
        if (tlsEngine == null) {
            tlsEngine = new TlsEngine();
        }
        return tlsEngine;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Strategy getters (lazy, with defaults)
    // ══════════════════════════════════════════════════════════════════════

    public RoutingStrategy getRoutingStrategy() {
        if (routingStrategy == null) {
            routingStrategy = new PathBasedRoutingStrategy();
        }
        return routingStrategy;
    }

    public LoadBalancingStrategy getLoadBalancingStrategy() {
        if (loadBalancingStrategy == null) {
            loadBalancingStrategy = new RoundRobinLoadBalancer();
        }
        return loadBalancingStrategy;
    }

    public AuthStrategy getAuthStrategy() {
        if (authStrategy == null) {
            // empty roleMap → all authenticated users are authorized
            authStrategy = new JwtAuthStrategy(Map.of());
        }
        return authStrategy;
    }

    public TrafficStrategy getTrafficStrategy() {
        if (trafficStrategy == null) {
            trafficStrategy = new CanaryTrafficStrategy();
        }
        return trafficStrategy;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Strategy setters — clear dependents so graph rebuilds lazily
    // ══════════════════════════════════════════════════════════════════════

    /** Swap the routing strategy; clears routing service → gateway service → controller. */
    public void setRoutingStrategy(RoutingStrategy routingStrategy) {
        this.routingStrategy = routingStrategy;
        this.routingService = null;
        this.gatewayService = null;
        this.gatewayController = null;
    }

    /** Swap the load-balancing strategy; clears LB service → mesh service → gateway service → controller. */
    public void setLoadBalancingStrategy(LoadBalancingStrategy loadBalancingStrategy) {
        this.loadBalancingStrategy = loadBalancingStrategy;
        this.loadBalancerService = null;
        this.serviceMeshService = null;
        this.gatewayService = null;
        this.gatewayController = null;
    }

    /** Swap the auth strategy; clears auth service → gateway service → controller. */
    public void setAuthStrategy(AuthStrategy authStrategy) {
        this.authStrategy = authStrategy;
        this.authService = null;
        this.gatewayService = null;
        this.gatewayController = null;
    }

    /** Swap the traffic strategy; clears controller. */
    public void setTrafficStrategy(TrafficStrategy trafficStrategy) {
        this.trafficStrategy = trafficStrategy;
        this.gatewayController = null;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Service getters (lazy)
    // ══════════════════════════════════════════════════════════════════════

    public RoutingService getRoutingService() {
        if (routingService == null) {
            routingService = new RoutingService(getRequestRouter(), getRoutingStrategy());
        }
        return routingService;
    }

    public AuthService getAuthService() {
        if (authService == null) {
            authService = new AuthService(getAuthStrategy());
        }
        return authService;
    }

    public RateLimitService getRateLimitService() {
        if (rateLimitService == null) {
            rateLimitService = new RateLimitService(getRateLimiterEngine());
        }
        return rateLimitService;
    }

    public CircuitBreakerService getCircuitBreakerService() {
        if (circuitBreakerService == null) {
            circuitBreakerService = new CircuitBreakerService(getCircuitBreakerEngine());
        }
        return circuitBreakerService;
    }

    public LoadBalancerService getLoadBalancerService() {
        if (loadBalancerService == null) {
            loadBalancerService = new LoadBalancerService(getServiceRegistry(), getLoadBalancingStrategy());
        }
        return loadBalancerService;
    }

    public ServiceMeshConfig getServiceMeshConfig() {
        if (serviceMeshConfig == null) {
            // defaults: mTLS enabled, sidecar on port 15001, tracing on, default retry, circuit breaker on
            serviceMeshConfig = new ServiceMeshConfig.Builder()
                    .mtlsEnabled(true)
                    .sidecarPort(15001)
                    .tracingEnabled(true)
                    .retryPolicy(RetryPolicy.defaultPolicy())
                    .circuitBreakerEnabled(true)
                    .build();
        }
        return serviceMeshConfig;
    }

    public ServiceMeshService getServiceMeshService() {
        if (serviceMeshService == null) {
            serviceMeshService = new ServiceMeshService(
                    getTlsEngine(), getCircuitBreakerService(),
                    getLoadBalancerService(), getServiceMeshConfig());
        }
        return serviceMeshService;
    }

    public GatewayService getGatewayService() {
        if (gatewayService == null) {
            gatewayService = new GatewayService(
                    getRoutingService(), getAuthService(), getRateLimitService(),
                    getCircuitBreakerService(), getLoadBalancerService(), getServiceMeshService());
        }
        return gatewayService;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Controller & Display getters (lazy)
    // ══════════════════════════════════════════════════════════════════════

    public GatewayController getController() {
        if (gatewayController == null) {
            gatewayController = new GatewayController(
                    getGatewayService(), getLoadBalancerService(), getCircuitBreakerService());
        }
        return gatewayController;
    }

    public GatewayStatsDisplay getStatsDisplay() {
        if (gatewayStatsDisplay == null) {
            gatewayStatsDisplay = new GatewayStatsDisplay(
                    getGatewayService(), getLoadBalancerService(),
                    getCircuitBreakerService(), getServiceRegistry());
        }
        return gatewayStatsDisplay;
    }
}
