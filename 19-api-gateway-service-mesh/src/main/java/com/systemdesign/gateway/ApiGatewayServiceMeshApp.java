package com.systemdesign.gateway;

import com.systemdesign.gateway.config.AppConfig;
import com.systemdesign.gateway.display.GatewayStatsDisplay;
import com.systemdesign.gateway.engine.*;
import com.systemdesign.gateway.model.*;
import com.systemdesign.gateway.service.*;
import com.systemdesign.gateway.strategy.auth.ApiKeyAuthStrategy;
import com.systemdesign.gateway.strategy.auth.JwtAuthStrategy;
import com.systemdesign.gateway.strategy.loadbalancing.ConsistentHashLoadBalancer;
import com.systemdesign.gateway.strategy.loadbalancing.WeightedLoadBalancer;
import com.systemdesign.gateway.strategy.traffic.CanaryTrafficStrategy;
import com.systemdesign.gateway.strategy.traffic.HeaderBasedTrafficStrategy;

import java.util.*;

/**
 * API Gateway & Service Mesh — System Design Demo
 *
 * Demonstrates: Request routing (path/header-based), rate limiting (token bucket),
 * authentication (JWT/API key), circuit breaker (CLOSED/OPEN/HALF_OPEN),
 * load balancing (round-robin, weighted, consistent hash), sidecar proxy (mTLS),
 * traffic shaping (canary, blue-green), service discovery, and health checking.
 *
 * 12 demos covering all major components.
 */
public class ApiGatewayServiceMeshApp {

    private static final String SEPARATOR = "=".repeat(70);

    public static void main(String[] args) {
        System.out.println(SEPARATOR);
        System.out.println("   API GATEWAY & SERVICE MESH — System Design Demo");
        System.out.println("   Staff Engineer Interview Prep: Routing, Resilience, Security");
        System.out.println(SEPARATOR);
        System.out.println();

        AppConfig config = new AppConfig();
        setupServices(config);

        demo1_RequestRouting(config);
        demo2_RateLimiting(config);
        demo3_JwtAuthentication(config);
        demo4_ApiKeyAuthentication(config);
        demo5_CircuitBreaker(config);
        demo6_RoundRobinLoadBalancing(config);
        demo7_WeightedLoadBalancing(config);
        demo8_ConsistentHashLoadBalancing(config);
        demo9_CanaryDeployment(config);
        demo10_ServiceMeshSidecar(config);
        demo11_ServiceDiscoveryHealthCheck(config);
        demo12_FullGatewayPipeline(config);

        printDesignSummary();
    }

    private static void setupServices(AppConfig config) {
        ServiceRegistry registry = config.getServiceRegistry();
        RoutingService routingService = config.getRoutingService();
        RateLimiterEngine rateLimiter = config.getRateLimiterEngine();
        TlsEngine tlsEngine = config.getTlsEngine();

        // Register service instances
        registry.register(new ServiceInstance("user-svc-1", "user-service", "10.0.1.10", 8080, 3, "us-east-1a"));
        registry.register(new ServiceInstance("user-svc-2", "user-service", "10.0.1.11", 8080, 2, "us-east-1b"));
        registry.register(new ServiceInstance("user-svc-3", "user-service", "10.0.1.12", 8080, 5, "us-west-2a"));
        registry.register(new ServiceInstance("order-svc-1", "order-service", "10.0.2.10", 8080, 3, "us-east-1a"));
        registry.register(new ServiceInstance("order-svc-2", "order-service", "10.0.2.11", 8080, 3, "us-east-1b"));
        registry.register(new ServiceInstance("payment-svc-1", "payment-service", "10.0.3.10", 8080, 4, "us-east-1a"));

        // Register routes
        routingService.registerRoute(new Route.Builder("/api/users/**", "user-service")
                .methods(Set.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT))
                .rateLimitPerSecond(100)
                .timeoutMs(5000)
                .retryCount(2)
                .priority(10)
                .build());

        routingService.registerRoute(new Route.Builder("/api/orders/**", "order-service")
                .methods(Set.of(HttpMethod.GET, HttpMethod.POST))
                .rateLimitPerSecond(50)
                .timeoutMs(10000)
                .retryCount(3)
                .priority(10)
                .metadata(Map.of("required-role", "user"))
                .build());

        routingService.registerRoute(new Route.Builder("/api/payments/**", "payment-service")
                .methods(Set.of(HttpMethod.POST))
                .rateLimitPerSecond(20)
                .timeoutMs(15000)
                .retryCount(1)
                .priority(5)
                .metadata(Map.of("required-role", "admin"))
                .build());

        routingService.registerRoute(new Route.Builder("/health", "health-check")
                .methods(Set.of(HttpMethod.GET))
                .rateLimitPerSecond(1000)
                .priority(1)
                .build());

        // Configure rate limits
        rateLimiter.configure("global", 200, 200);

        // Trust services for mTLS
        tlsEngine.trustService("api-gateway");
        tlsEngine.trustService("user-service");
        tlsEngine.trustService("order-service");
        tlsEngine.trustService("payment-service");

        System.out.println("[SETUP] Registered 6 service instances, 4 routes, mTLS configured");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 1: Request Routing (Path-Based & Priority)
    // ─────────────────────────────────────────────────────────────────
    private static void demo1_RequestRouting(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 1: Request Routing (Path-Based Matching)");
        System.out.println(SEPARATOR);

        RoutingService routingService = config.getRoutingService();

        HttpRequest req1 = new HttpRequest.Builder(HttpMethod.GET, "/api/users/123").build();
        HttpRequest req2 = new HttpRequest.Builder(HttpMethod.POST, "/api/orders/new").build();
        HttpRequest req3 = new HttpRequest.Builder(HttpMethod.GET, "/health").build();
        HttpRequest req4 = new HttpRequest.Builder(HttpMethod.DELETE, "/api/unknown").build();

        System.out.println("[DEMO] Route matching:");
        routingService.matchRoute(req1).ifPresentOrElse(
                r -> System.out.println("  GET /api/users/123  → " + r.getTargetService()),
                () -> System.out.println("  GET /api/users/123  → NO MATCH"));
        routingService.matchRoute(req2).ifPresentOrElse(
                r -> System.out.println("  POST /api/orders/new → " + r.getTargetService()),
                () -> System.out.println("  POST /api/orders/new → NO MATCH"));
        routingService.matchRoute(req3).ifPresentOrElse(
                r -> System.out.println("  GET /health          → " + r.getTargetService()),
                () -> System.out.println("  GET /health          → NO MATCH"));
        routingService.matchRoute(req4).ifPresentOrElse(
                r -> System.out.println("  DELETE /api/unknown   → " + r.getTargetService()),
                () -> System.out.println("  DELETE /api/unknown   → NO MATCH (404)"));

        System.out.println();
        System.out.println("  KEY INSIGHT: Path-based routing with ** wildcard matches any suffix.");
        System.out.println("  Routes sorted by priority (lower=higher). First match wins.");
        System.out.println("  In production: Kong uses radix tree, Envoy uses route table with");
        System.out.println("  prefix/exact/regex matching. Netflix Zuul uses filter chains.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 2: Rate Limiting (Token Bucket)
    // ─────────────────────────────────────────────────────────────────
    private static void demo2_RateLimiting(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 2: Rate Limiting (Token Bucket Algorithm)");
        System.out.println(SEPARATOR);

        RateLimiterEngine rateLimiter = config.getRateLimiterEngine();
        rateLimiter.configure("demo-client", 5, 5); // 5 requests/sec

        System.out.println("[DEMO] Token bucket: capacity=5, refill=5/sec");
        System.out.println("[DEMO] Sending 8 rapid requests:");

        for (int i = 1; i <= 8; i++) {
            RateLimitResult result = rateLimiter.tryConsume("demo-client");
            System.out.printf("  Request %d: %s (remaining: %d)%n",
                    i, result.isAllowed() ? "ALLOWED" : "DENIED (429)", result.getRemainingTokens());
        }

        System.out.println();
        System.out.println("  KEY INSIGHT: Token bucket allows bursts (up to bucket capacity)");
        System.out.println("  then rate-limits to refill rate. Better than fixed window which");
        System.out.println("  allows 2x burst at window boundary. In production: Redis + Lua");
        System.out.println("  script for distributed rate limiting (Stripe, Cloudflare pattern).");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 3: JWT Authentication
    // ─────────────────────────────────────────────────────────────────
    private static void demo3_JwtAuthentication(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 3: JWT Authentication");
        System.out.println(SEPARATOR);

        // Configure JWT auth with role mappings
        Map<String, Set<String>> roleMap = new HashMap<>();
        roleMap.put("karan", Set.of("admin", "user"));
        roleMap.put("guest", Set.of("user"));
        config.setAuthStrategy(new JwtAuthStrategy(roleMap));
        AuthService authService = config.getAuthService();

        // Valid JWT (simulated: header.payload.signature format)
        String validJwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJrYXJhbiJ9.signature";
        HttpRequest authReq = new HttpRequest.Builder(HttpMethod.GET, "/api/users/me")
                .header("Authorization", "Bearer " + validJwt)
                .build();
        AuthResult result1 = authService.authenticate(authReq);
        System.out.println("[DEMO] Valid JWT: authenticated=" + result1.isAuthenticated()
                + ", principal=" + result1.getPrincipal() + ", roles=" + result1.getRoles());

        // Missing Authorization header
        HttpRequest noAuthReq = new HttpRequest.Builder(HttpMethod.GET, "/api/users/me").build();
        AuthResult result2 = authService.authenticate(noAuthReq);
        System.out.println("[DEMO] No token: authenticated=" + result2.isAuthenticated()
                + ", error=" + result2.getErrorMessage());

        // Invalid JWT format
        HttpRequest badReq = new HttpRequest.Builder(HttpMethod.GET, "/api/users/me")
                .header("Authorization", "Bearer invalid-token")
                .build();
        AuthResult result3 = authService.authenticate(badReq);
        System.out.println("[DEMO] Invalid JWT: authenticated=" + result3.isAuthenticated()
                + ", error=" + result3.getErrorMessage());

        System.out.println();
        System.out.println("  KEY INSIGHT: Gateway validates JWT signature and extracts claims.");
        System.out.println("  Services never see raw tokens — gateway passes X-User-Id header.");
        System.out.println("  In production: RS256 asymmetric signing (gateway has public key only).");
        System.out.println("  OAuth2: client → auth server → JWT → gateway validates → backend.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 4: API Key Authentication
    // ─────────────────────────────────────────────────────────────────
    private static void demo4_ApiKeyAuthentication(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 4: API Key Authentication");
        System.out.println(SEPARATOR);

        Map<String, String> validKeys = Map.of(
                "sk_live_abc123", "stripe-integration",
                "pk_test_xyz789", "mobile-app"
        );
        config.setAuthStrategy(new ApiKeyAuthStrategy(validKeys));
        AuthService authService = config.getAuthService();

        HttpRequest validKey = new HttpRequest.Builder(HttpMethod.POST, "/api/payments/charge")
                .header("X-API-Key", "sk_live_abc123")
                .build();
        AuthResult r1 = authService.authenticate(validKey);
        System.out.println("[DEMO] Valid key: principal=" + r1.getPrincipal());

        HttpRequest invalidKey = new HttpRequest.Builder(HttpMethod.POST, "/api/payments/charge")
                .header("X-API-Key", "invalid_key_000")
                .build();
        AuthResult r2 = authService.authenticate(invalidKey);
        System.out.println("[DEMO] Invalid key: " + r2.getErrorMessage());

        System.out.println();
        System.out.println("  KEY INSIGHT: API keys are simpler than JWT but less secure (no");
        System.out.println("  expiry, no claims, must be stored hashed). Best for server-to-server.");
        System.out.println("  Stripe uses API keys with prefix (sk_live_, pk_test_) for environment");
        System.out.println("  identification. Rate limits are per-key for abuse prevention.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 5: Circuit Breaker (CLOSED → OPEN → HALF_OPEN → CLOSED)
    // ─────────────────────────────────────────────────────────────────
    private static void demo5_CircuitBreaker(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 5: Circuit Breaker State Machine");
        System.out.println(SEPARATOR);

        CircuitBreakerService cbService = config.getCircuitBreakerService();
        String service = "flaky-service";

        System.out.println("[DEMO] Initial state: " + cbService.getState(service));

        // Simulate 5 failures to trip the circuit
        System.out.println("[DEMO] Simulating 5 consecutive failures...");
        for (int i = 1; i <= 5; i++) {
            cbService.recordFailure(service);
            System.out.println("  Failure " + i + ": state=" + cbService.getState(service));
        }

        // Circuit should be OPEN now
        System.out.println("[DEMO] Circuit is OPEN — requests rejected:");
        System.out.println("  allowRequest: " + cbService.allowRequest(service));

        // Force to half-open for demo (in production, wait for cooldown)
        CircuitBreakerEngine engine = config.getCircuitBreakerEngine();
        engine.getOrCreate(service).halfOpen();
        System.out.println("[DEMO] Manually moved to HALF_OPEN (simulating cooldown expiry)");
        System.out.println("  allowRequest: " + cbService.allowRequest(service));

        // Simulate 3 successes to close the circuit
        System.out.println("[DEMO] Simulating 3 consecutive successes...");
        for (int i = 1; i <= 3; i++) {
            cbService.recordSuccess(service);
            System.out.println("  Success " + i + ": state=" + cbService.getState(service));
        }

        System.out.println();
        System.out.println("  KEY INSIGHT: Circuit breaker prevents cascading failure.");
        System.out.println("  CLOSED → (5 failures) → OPEN → (30s cooldown) → HALF_OPEN →");
        System.out.println("  (3 successes) → CLOSED. Netflix Hystrix pioneered this; now");
        System.out.println("  Resilience4j and Envoy circuit breakers are standard.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 6: Round-Robin Load Balancing
    // ─────────────────────────────────────────────────────────────────
    private static void demo6_RoundRobinLoadBalancing(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 6: Round-Robin Load Balancing");
        System.out.println(SEPARATOR);

        LoadBalancerService lbService = config.getLoadBalancerService();
        HttpRequest request = new HttpRequest.Builder(HttpMethod.GET, "/api/users/1").build();

        System.out.println("[DEMO] 6 requests to user-service (3 instances):");
        for (int i = 1; i <= 6; i++) {
            lbService.selectInstance("user-service", request).ifPresent(inst ->
                    System.out.println("  → " + inst.getHost() + ":" + inst.getPort()
                            + " (" + inst.getZone() + ", weight=" + inst.getWeight() + ")"));
        }

        System.out.println();
        System.out.println("  KEY INSIGHT: Round-robin is simple and fair but ignores instance");
        System.out.println("  capacity/health weight. Good when instances are homogeneous.");
        System.out.println("  Nginx default, Kubernetes kube-proxy, AWS ALB all support this.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 7: Weighted Load Balancing
    // ─────────────────────────────────────────────────────────────────
    private static void demo7_WeightedLoadBalancing(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 7: Weighted Load Balancing");
        System.out.println(SEPARATOR);

        config.setLoadBalancingStrategy(new WeightedLoadBalancer());
        LoadBalancerService lbService = config.getLoadBalancerService();
        HttpRequest request = new HttpRequest.Builder(HttpMethod.GET, "/api/users/1").build();

        // Track distribution
        Map<String, Integer> distribution = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            lbService.selectInstance("user-service", request).ifPresent(inst -> {
                String key = inst.getHost() + " (w=" + inst.getWeight() + ")";
                distribution.merge(key, 1, Integer::sum);
            });
        }

        System.out.println("[DEMO] 100 requests weighted distribution:");
        distribution.forEach((k, v) ->
                System.out.printf("  %-30s : %d requests (%.0f%%)%n", k, v, v * 100.0 / 100));

        System.out.println();
        System.out.println("  KEY INSIGHT: Weighted balancing sends more traffic to stronger");
        System.out.println("  instances. weight=5 gets ~2.5x traffic vs weight=2. Used for");
        System.out.println("  heterogeneous fleets (bigger instances get more) and gradual");
        System.out.println("  rollouts (new version starts with weight=1).");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 8: Consistent Hash Load Balancing
    // ─────────────────────────────────────────────────────────────────
    private static void demo8_ConsistentHashLoadBalancing(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 8: Consistent Hash Load Balancing");
        System.out.println(SEPARATOR);

        config.setLoadBalancingStrategy(new ConsistentHashLoadBalancer(150));
        LoadBalancerService lbService = config.getLoadBalancerService();

        String[] paths = {"/api/users/100", "/api/users/200", "/api/users/100",
                "/api/users/300", "/api/users/200", "/api/users/100"};

        System.out.println("[DEMO] Same path always routes to same instance (cache affinity):");
        for (String path : paths) {
            HttpRequest req = new HttpRequest.Builder(HttpMethod.GET, path).build();
            lbService.selectInstance("user-service", req).ifPresent(inst ->
                    System.out.println("  " + path + " → " + inst.getHost() + ":" + inst.getPort()));
        }

        System.out.println();
        System.out.println("  KEY INSIGHT: Consistent hashing provides cache affinity — same");
        System.out.println("  request always hits same instance, maximizing local cache hit rate.");
        System.out.println("  Virtual nodes (150/instance) ensure even distribution. When an");
        System.out.println("  instance is removed, only 1/N of keys remap (minimal disruption).");
        System.out.println("  Used by: Memcached, DynamoDB, Cassandra, Envoy.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 9: Canary Deployment (Traffic Splitting)
    // ─────────────────────────────────────────────────────────────────
    private static void demo9_CanaryDeployment(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 9: Canary Deployment (Traffic Splitting)");
        System.out.println(SEPARATOR);

        TrafficSplit split = new TrafficSplit("order-service-deploy",
                Map.of("v1-stable", 90, "v2-canary", 10));

        // Weighted canary
        CanaryTrafficStrategy canaryStrategy = new CanaryTrafficStrategy();
        Map<String, Integer> canaryDist = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            HttpRequest req = new HttpRequest.Builder(HttpMethod.GET, "/api/orders").build();
            String version = canaryStrategy.selectVersion(req, split);
            canaryDist.merge(version, 1, Integer::sum);
        }

        System.out.println("[DEMO] Canary split (90/10) over 100 requests:");
        canaryDist.forEach((v, count) ->
                System.out.printf("  %-15s : %d requests%n", v, count));

        // Header-based routing
        System.out.println();
        HeaderBasedTrafficStrategy headerStrategy = new HeaderBasedTrafficStrategy();
        HttpRequest normalReq = new HttpRequest.Builder(HttpMethod.GET, "/api/orders").build();
        HttpRequest canaryReq = new HttpRequest.Builder(HttpMethod.GET, "/api/orders")
                .header("X-Canary", "true").build();

        System.out.println("[DEMO] Header-based routing:");
        System.out.println("  Normal request  → " + headerStrategy.selectVersion(normalReq, split));
        System.out.println("  X-Canary: true  → " + headerStrategy.selectVersion(canaryReq, split));

        System.out.println();
        System.out.println("  KEY INSIGHT: Canary deployments reduce blast radius. Start at 1%,");
        System.out.println("  monitor error rate, gradually increase to 100%. Istio VirtualService");
        System.out.println("  does this declaratively. AWS ALB weighted target groups. Shopify");
        System.out.println("  canaries every deploy to protect Black Friday traffic.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 10: Service Mesh Sidecar Proxy (mTLS + Circuit Breaker)
    // ─────────────────────────────────────────────────────────────────
    private static void demo10_ServiceMeshSidecar(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 10: Service Mesh Sidecar Proxy (mTLS)");
        System.out.println(SEPARATOR);

        ServiceMeshService meshService = config.getServiceMeshService();

        // Trusted service-to-service call
        HttpRequest req = new HttpRequest.Builder(HttpMethod.GET, "/internal/user/123").build();
        System.out.println("[DEMO] Sidecar proxy: order-service → user-service (mTLS)");
        HttpResponse resp = meshService.proxyRequest("order-service", "user-service", req);
        System.out.println("  Response: " + resp.getStatusCode() + " (latency=" + resp.getLatencyMs() + "ms)");

        // Untrusted service call
        System.out.println();
        System.out.println("[DEMO] Sidecar proxy: unknown-service → user-service (untrusted)");
        HttpResponse resp2 = meshService.proxyRequest("unknown-service", "user-service", req);
        System.out.println("  Response: " + resp2.getStatusCode() + " — " + resp2.getBody());

        System.out.println();
        System.out.println("  KEY INSIGHT: Service mesh sidecar (Envoy/Linkerd) handles:");
        System.out.println("  mTLS (zero-trust), circuit breaking, load balancing, retries,");
        System.out.println("  and observability — all without changing application code.");
        System.out.println("  Istio: control plane configures Envoy sidecars via xDS protocol.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 11: Service Discovery & Health Checking
    // ─────────────────────────────────────────────────────────────────
    private static void demo11_ServiceDiscoveryHealthCheck(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 11: Service Discovery & Health Checking");
        System.out.println(SEPARATOR);

        ServiceRegistry registry = config.getServiceRegistry();
        GatewayStatsDisplay display = config.getStatsDisplay();

        display.printServiceRegistry();

        // Mark an instance unhealthy
        System.out.println("[DEMO] Marking user-svc-2 as UNHEALTHY...");
        registry.markUnhealthy("user-svc-2");

        System.out.println("[DEMO] Healthy instances for user-service:");
        registry.getInstances("user-service").forEach(inst ->
                System.out.println("  " + inst.getId() + " @ " + inst.getAddress()
                        + " [" + inst.getHealthStatus() + "]"));

        // Recover
        System.out.println("[DEMO] Recovering user-svc-2...");
        registry.markHealthy("user-svc-2");

        System.out.println();
        System.out.println("  KEY INSIGHT: Service discovery is the backbone of microservices.");
        System.out.println("  Pull-based (Consul, etcd) vs Push-based (Eureka, K8s endpoints).");
        System.out.println("  Health checks: HTTP /health endpoint, TCP, gRPC HealthCheck service.");
        System.out.println("  Stale instance eviction prevents routing to dead instances.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 12: Full Gateway Pipeline (End-to-End)
    // ─────────────────────────────────────────────────────────────────
    private static void demo12_FullGatewayPipeline(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 12: Full Gateway Pipeline (End-to-End)");
        System.out.println(SEPARATOR);

        // Restore JWT auth with roles
        Map<String, Set<String>> roleMap = new HashMap<>();
        roleMap.put("karan", Set.of("admin", "user"));
        config.setAuthStrategy(new JwtAuthStrategy(roleMap));

        GatewayService gateway = config.getGatewayService();
        String validJwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJrYXJhbiJ9.signature";

        // Successful request through full pipeline
        System.out.println("[DEMO] Full pipeline — authenticated user request:");
        HttpRequest goodReq = new HttpRequest.Builder(HttpMethod.GET, "/api/users/123")
                .header("Authorization", "Bearer " + validJwt)
                .clientIp("192.168.1.100")
                .build();
        HttpResponse goodResp = gateway.handleRequest(goodReq);
        System.out.println("  → Response: " + goodResp.getStatusCode());

        // Unauthenticated request
        System.out.println();
        System.out.println("[DEMO] Full pipeline — unauthenticated request:");
        HttpRequest noAuthReq = new HttpRequest.Builder(HttpMethod.GET, "/api/users/123")
                .clientIp("192.168.1.101")
                .build();
        HttpResponse noAuthResp = gateway.handleRequest(noAuthReq);
        System.out.println("  → Response: " + noAuthResp.getStatusCode() + " — " + noAuthResp.getBody());

        // Route not found
        System.out.println();
        System.out.println("[DEMO] Full pipeline — unknown path:");
        HttpRequest unknownReq = new HttpRequest.Builder(HttpMethod.GET, "/api/xyz")
                .header("Authorization", "Bearer " + validJwt)
                .build();
        HttpResponse unknownResp = gateway.handleRequest(unknownReq);
        System.out.println("  → Response: " + unknownResp.getStatusCode() + " — " + unknownResp.getBody());

        System.out.println();
        config.getStatsDisplay().printStats();

        System.out.println();
        System.out.println("  KEY INSIGHT: The gateway pipeline is a Chain of Responsibility:");
        System.out.println("  Route → Auth → AuthZ → Rate Limit → Circuit Breaker → LB → Forward.");
        System.out.println("  Each step can short-circuit (401, 403, 429, 503). TraceId flows");
        System.out.println("  through every step for end-to-end observability.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Design Summary
    // ─────────────────────────────────────────────────────────────────
    private static void printDesignSummary() {
        System.out.println(SEPARATOR);
        System.out.println("  DESIGN SUMMARY — API Gateway & Service Mesh");
        System.out.println(SEPARATOR);
        System.out.println();
        System.out.println("  Gateway Pipeline (Chain of Responsibility):");
        System.out.println("    1. Route Matching (path/header-based, priority sorted)");
        System.out.println("    2. Authentication (JWT / API Key)");
        System.out.println("    3. Authorization (role-based, per-route)");
        System.out.println("    4. Rate Limiting (token bucket, per-client/per-route)");
        System.out.println("    5. Circuit Breaker (CLOSED/OPEN/HALF_OPEN state machine)");
        System.out.println("    6. Load Balancing (round-robin/weighted/consistent hash)");
        System.out.println("    7. Forward to Service Instance");
        System.out.println();
        System.out.println("  Service Mesh (Sidecar Proxy):");
        System.out.println("    • mTLS — zero-trust service-to-service encryption");
        System.out.println("    • Circuit Breaker — per-service failure isolation");
        System.out.println("    • Load Balancing — instance selection within mesh");
        System.out.println("    • Traffic Shaping — canary, blue-green, A/B routing");
        System.out.println();
        System.out.println("  Design Patterns (GoF):");
        System.out.println("    • Strategy — routing, load balancing, auth, traffic shaping");
        System.out.println("    • Builder — HttpRequest, Route, ServiceMeshConfig");
        System.out.println("    • Factory — AppConfig composition root");
        System.out.println("    • Repository — data access (RouteRepo, InstanceRepo)");
        System.out.println("    • Facade — GatewayService orchestrates full pipeline");
        System.out.println("    • State — CircuitBreaker (CLOSED/OPEN/HALF_OPEN)");
        System.out.println("    • Chain of Responsibility — gateway filter pipeline");
        System.out.println("    • Proxy — sidecar proxy pattern (ServiceMeshService)");
        System.out.println("    • Singleton — AppConfig lazy initialization");
        System.out.println();
        System.out.println("  Staff-Level Topics Covered:");
        System.out.println("    • API Gateway as cross-cutting infrastructure");
        System.out.println("    • Service mesh (Istio/Envoy architecture)");
        System.out.println("    • Zero-trust security (mTLS)");
        System.out.println("    • Canary deployments & traffic shaping");
        System.out.println("    • Consistent hash load balancing (cache affinity)");
        System.out.println("    • Token bucket rate limiting");
        System.out.println("    • Circuit breaker for cascading failure prevention");
        System.out.println();
        System.out.println(SEPARATOR);
        System.out.println("  End of API Gateway & Service Mesh Demo");
        System.out.println(SEPARATOR);
    }
}
