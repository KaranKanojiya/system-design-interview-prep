# Design Patterns -- API Gateway & Service Mesh

> Quick reference for system design interviews. Each pattern includes the ugly
> anti-pattern first, then the clean solution, numbered call chain, ASCII diagram,
> and a one-liner you can drop in an interview.
>
> **Domain:** API gateway and service mesh infrastructure where incoming HTTP requests
> flow through a pipeline of route matching, authentication, rate limiting, circuit
> breaking, and load balancing before reaching upstream microservices. Four Strategy
> interfaces (RoutingStrategy, LoadBalancingStrategy, AuthStrategy, TrafficStrategy)
> make this a strategy-heavy project. The circuit breaker state machine and token
> bucket rate limiter are THE core algorithms -- interviewers will ask you to walk
> through the state transitions and token consumption flow.
>
> **Project 19 of the system design series.**

---

## Table of Contents

| # | Pattern | GoF Category | Primary Use |
|---|---------|-------------|-------------|
| 1 | Strategy (x4) | Behavioral | RoutingStrategy (PathBased, HeaderBased), LoadBalancingStrategy (RoundRobin, Weighted, ConsistentHash), AuthStrategy (JWT, ApiKey), TrafficStrategy (Canary, HeaderBased) |
| 2 | Builder | Creational | HttpRequest.Builder, HttpResponse.Builder, Route.Builder, ServiceMeshConfig.Builder |
| 3 | Factory | Creational | AppConfig wires strategies, engines, repos, services -- composition root |
| 4 | Repository (x2) | Structural (enterprise) | RouteRepository, ServiceInstanceRepository with InMemory implementations |
| 5 | Facade | Structural | GatewayService orchestrates route -> auth -> rate limit -> CB -> LB -> forward |
| 6 | State | Behavioral | CircuitBreakerState: CLOSED -> OPEN -> HALF_OPEN state machine |
| 7 | Chain of Responsibility | Behavioral | GatewayFilter interface, 10-step pipeline with short-circuit at any stage |
| 8 | Proxy | Structural | ServiceMeshService as sidecar proxy for service-to-service calls |
| 9 | Singleton | Creational | AppConfig lazy initialization -- single factory creates all objects |

---

## 1. Strategy Pattern (Behavioral) -- THE KEY PATTERN

**Four independent Strategy interfaces** power the core of this system.
This is the pattern interviewers care about most -- it directly answers
"How do you route requests?", "How do you distribute load?",
"How do you authenticate?", and "How do you roll out canary deployments?"

### Strategy Interface A: RoutingStrategy

Determines **how** the best route is selected from candidates that already
matched the request path.

```java
public interface RoutingStrategy {
    /**
     * Selects the best route from pre-filtered candidates.
     *
     * @param request        the incoming HTTP request
     * @param matchingRoutes routes whose path pattern already matched
     * @return the best route, or empty if none qualifies
     */
    Optional<Route> route(HttpRequest request, List<Route> matchingRoutes);

    String getStrategyName();
}
```

Two concrete strategies:

| Strategy | Algorithm | Use Case |
|----------|-----------|----------|
| PathBasedRoutingStrategy | Specificity scoring: exact=100, wildcard=50-N, catch-all=0; tiebreak by path length | Default: most specific path wins (like Nginx location matching) |
| HeaderBasedRoutingStrategy | Match header value (e.g., "X-Version: v2") against route metadata "required-header-value" | API versioning, A/B testing, canary opt-in via header |

### Strategy Interface B: LoadBalancingStrategy

Determines **which** service instance handles a request.

```java
public interface LoadBalancingStrategy {
    /**
     * Selects a service instance from the available pool.
     *
     * @param instances all registered instances for the target service
     * @param request   the incoming request (may influence selection)
     * @return the chosen instance, or empty if no healthy instance available
     */
    Optional<ServiceInstance> selectInstance(List<ServiceInstance> instances, HttpRequest request);

    String getStrategyName();
}
```

Three concrete strategies:

| Strategy | Algorithm | Trade-off |
|----------|-----------|-----------|
| RoundRobinLoadBalancer | AtomicInteger counter % healthy count; lock-free thread-safe | Fair distribution; ignores instance capacity and weight |
| WeightedLoadBalancer | Weighted random: totalWeight, ThreadLocalRandom, accumulate and select | Respects instance capacity; non-deterministic distribution |
| ConsistentHashLoadBalancer | FNV-1a hash on request path, virtual-node TreeMap ring (150 vnodes/instance), ceilingEntry with wrap-around | Cache affinity -- same path hits same instance; adding/removing instances only remaps ~1/N keys |

### Strategy Interface C: AuthStrategy

Determines **how** requests are authenticated.

```java
public interface AuthStrategy {
    /**
     * Authenticates the incoming request.
     *
     * @param request HTTP request containing credentials
     * @return AuthResult: success with principal/roles, or unauthorized with reason
     */
    AuthResult authenticate(HttpRequest request);

    String getStrategyName();
}
```

Two concrete strategies:

| Strategy | How It Works | Trade-off |
|----------|-------------|-----------|
| JwtAuthStrategy | Read "Authorization: Bearer" header, validate 3-part structure, Base64-decode payload, extract sub claim, lookup roles from roleMap | Stateless (no DB lookup), tamper-proof (signature), carries claims; token can't be revoked without blacklist |
| ApiKeyAuthStrategy | Read "X-API-Key" header, lookup in validKeys map (key -> clientName), return API_CLIENT role | Simpler than JWT, good for server-to-server; no expiry, no claims, must be stored hashed |

### Strategy Interface D: TrafficStrategy

Determines **how** traffic is shaped across deployment versions.

```java
public interface TrafficStrategy {
    /**
     * Selects which deployment version should handle the request.
     *
     * @param request the incoming HTTP request
     * @param split   traffic split configuration (version -> weight)
     * @return name of the selected version
     */
    String selectVersion(HttpRequest request, TrafficSplit split);

    String getStrategyName();
}
```

Two concrete strategies:

| Strategy | Algorithm | When Used |
|----------|-----------|-----------|
| CanaryTrafficStrategy | Weighted random selection across version splits -- same algorithm as WeightedLoadBalancer | Gradual rollout: start at 1%, increase to 100% |
| HeaderBasedTrafficStrategy | Check "X-Canary: true" header; present -> lowest-weight (canary); absent -> highest-weight (stable) | QA/internal opt-in to canary; production traffic stays on stable |

### Ugly Anti-Pattern -- Hardcoded Everything

```java
// UGLY: No strategies. All algorithms hardcoded. Adding a new LB algorithm
// means modifying every class that does load balancing. New auth mechanism?
// Giant if-else chain. New routing logic? Copy-paste and pray.

public class UglyGatewayService {

    private int roundRobinCounter = 0;

    public HttpResponse handleRequest(HttpRequest request) {
        // Hardcoded path matching -- no strategy
        String service;
        if (request.getPath().startsWith("/api/users")) {
            service = "user-service";
        } else if (request.getPath().startsWith("/api/orders")) {
            service = "order-service";
        } else {
            return HttpResponse.error(404, "Not found");
        }

        // Hardcoded JWT auth -- want API key? Add another if-else
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return HttpResponse.error(401, "Unauthorized");
        }
        // No role checking, no strategy swap, no extensibility

        // Hardcoded round-robin -- want weighted? Rewrite everything
        List<String> instances = List.of("10.0.1.10", "10.0.1.11", "10.0.1.12");
        String target = instances.get(roundRobinCounter++ % instances.size());
        // No health checking, no consistent hashing option

        // No circuit breaker, no rate limiting, no traffic shaping...
        return HttpResponse.ok("{\"instance\":\"" + target + "\"}");
    }
}
// Problems:
//   1. Adding weighted LB requires modifying handleRequest()
//   2. Adding API key auth requires another if-else branch
//   3. Adding header-based routing requires more if-else
//   4. No way to swap strategies at runtime
//   5. Testing requires the entire monolith
//   6. Violates Open-Closed Principle catastrophically
```

### Clean Solution -- Strategy Pattern

```java
// CLEAN: Four independent strategy interfaces. New algorithm = new class.
// Swap at runtime via setStrategy(). Zero modification to existing code.

public class LoadBalancerService {
    private volatile LoadBalancingStrategy strategy; // pluggable via interface

    public Optional<ServiceInstance> selectInstance(String serviceName, HttpRequest request) {
        List<ServiceInstance> healthy = registry.getInstances(serviceName);
        return strategy.selectInstance(healthy, request); // delegate to strategy
    }

    public void setStrategy(LoadBalancingStrategy strategy) {
        this.strategy = strategy; // hot-swap at runtime
    }
}

public class AuthService {
    private volatile AuthStrategy authStrategy; // pluggable via interface

    public AuthResult authenticate(HttpRequest request) {
        return authStrategy.authenticate(request); // delegate to strategy
    }

    public void setStrategy(AuthStrategy strategy) {
        this.authStrategy = strategy; // hot-swap at runtime
    }
}
```

### Numbered Call Chain -- Load Balancing (RoundRobin)

```
1. GatewayService.handleRequest(request)
2.   --> LoadBalancerService.selectInstance("user-service", request)
3.       --> ServiceRegistry.getInstances("user-service")
4.           returns: [inst-A(healthy), inst-B(healthy), inst-C(healthy)]
5.       --> RoundRobinLoadBalancer.selectInstance(instances, request)
6.           filter to healthy: [A, B, C]
7.           counter.getAndIncrement() = 0  -->  0 % 3 = 0  -->  inst-A
8.           return Optional.of(inst-A)
9.   --> forward request to inst-A at 10.0.1.10:8080
```

### Numbered Call Chain -- Auth (JWT)

```
1. GatewayService.handleRequest(request)
2.   --> AuthService.authenticate(request)
3.       --> JwtAuthStrategy.authenticate(request)
4.           read header: "Authorization: Bearer eyJh...payload...sig"
5.           split on ".": ["eyJh", "eyJzdWIiOiJrYXJhbiJ9", "sig"]  (3 parts, valid)
6.           Base64-decode part[1]: {"sub":"karan"}
7.           extractSubject -> "karan"
8.           roleMap.get("karan") -> Set.of("admin", "user")
9.           return AuthResult.success("karan", {"admin", "user"})
10.  --> AuthService.authorize(authResult, route)
11.      route.metadata("required-role") -> "user"
12.      authResult.getRoles().contains("user") -> true
13.      return true (authorized)
```

### ASCII Diagram -- Strategy Hot-Swap

```
Before swap:                          After swap:
+------------------+                  +------------------+
| LoadBalancerSvc  |                  | LoadBalancerSvc  |
|                  |                  |                  |
| strategy ------->| RoundRobin      | strategy ------->| ConsistentHash
|                  | LB              |                  | LB
+------------------+                 +------------------+

  config.setLoadBalancingStrategy(     // Runtime swap:
      new ConsistentHashLoadBalancer() //   1. Null out loadBalancerService
  );                                   //   2. Null out dependent services
                                       //   3. Lazy re-create with new strategy

  Same for Auth:  setAuthStrategy(new ApiKeyAuthStrategy(...))
  Same for Route: setRoutingStrategy(new HeaderBasedRoutingStrategy("X-Version"))
  Same for Traffic: setTrafficStrategy(new HeaderBasedTrafficStrategy())
```

### Interview Soundbite

> "We use four Strategy interfaces -- RoutingStrategy, LoadBalancingStrategy,
> AuthStrategy, and TrafficStrategy. Each has 2-3 concrete implementations.
> Services depend on the interface, never on a concrete class. AppConfig wires
> them. Hot-swap at runtime via setStrategy() for zero-downtime config changes.
> This is exactly how Envoy's cluster managers and filter chains work."

---

## 2. Builder Pattern (Creational)

Four classes use the Builder pattern for constructing complex objects with
many optional fields and immutable results.

### Ugly Anti-Pattern -- Telescoping Constructors

```java
// UGLY: 10-parameter constructor. Nobody remembers the argument order.
// Adding a new field breaks every caller.

HttpRequest request = new HttpRequest(
    UUID.randomUUID().toString(),  // id
    HttpMethod.GET,                // method
    "/api/users/123",              // path
    new HashMap<>(),               // headers
    new HashMap<>(),               // queryParams
    null,                          // body (nullable for GET)
    "192.168.1.100",               // clientIp
    Instant.now()                  // timestamp
);
// What if you need to add a "protocol" field? Every call site breaks.
// What are args 4 and 5? Both are Map<String,String> -- easy to swap by mistake.
```

### Clean Solution -- Builder Pattern

```java
// CLEAN: Required fields in Builder constructor. Optional fields via fluent setters.
// Immutable result. Private constructor prevents direct instantiation.

HttpRequest request = new HttpRequest.Builder(HttpMethod.GET, "/api/users/123")
    .header("Authorization", "Bearer eyJh...")
    .header("Accept", "application/json")
    .clientIp("192.168.1.100")
    .build();

Route route = new Route.Builder("/api/users/**", "user-service")
    .methods(Set.of(HttpMethod.GET, HttpMethod.POST))
    .rateLimitPerSecond(100)
    .timeoutMs(5000)
    .retryCount(2)
    .priority(10)
    .metadata("required-role", "user")
    .build();

ServiceMeshConfig config = new ServiceMeshConfig.Builder()
    .mtlsEnabled(true)
    .sidecarPort(15001)
    .tracingEnabled(true)
    .retryPolicy(RetryPolicy.defaultPolicy())
    .circuitBreakerEnabled(true)
    .build();
```

### Numbered Call Chain -- HttpRequest Building

```
1. new HttpRequest.Builder(HttpMethod.GET, "/api/users/123")
   --> id = UUID.randomUUID()  (auto-generated)
   --> method = GET  (required)
   --> path = "/api/users/123"  (required)
   --> clientIp = "127.0.0.1"  (default)
   --> timestamp = Instant.now()  (default)

2. .header("Authorization", "Bearer eyJh...")
   --> headers.put("Authorization", "Bearer eyJh...")

3. .clientIp("192.168.1.100")
   --> clientIp = "192.168.1.100"  (overrides default)

4. .build()
   --> new HttpRequest(this)  (private constructor)
   --> Collections.unmodifiableMap(headers)  (immutable)
   --> Collections.unmodifiableMap(queryParams)  (immutable)
   --> return immutable HttpRequest
```

### ASCII Diagram -- Builder Classes

```
+------------------+     +-----------------------+
| HttpRequest      |     | HttpRequest.Builder   |
|                  |     |                       |
| - id (final)     |<----| + id(String)          |
| - method (final) |     | + method (required)   |
| - path (final)   |     | + path (required)     |
| - headers (final)|     | + header(k, v)        |
| - queryParams    |     | + queryParam(k, v)    |
| - body (final)   |     | + body(String)        |
| - clientIp       |     | + clientIp(String)    |
| - timestamp      |     | + timestamp(Instant)  |
|                  |     | + build() --> Request  |
| (private ctor)   |     +-----------------------+
+------------------+

Same pattern for: HttpResponse, Route, ServiceMeshConfig
```

### Interview Soundbite

> "HttpRequest, Route, and ServiceMeshConfig all use the Builder pattern.
> Required parameters go in the Builder constructor; everything else has
> sensible defaults. The built object is immutable -- maps wrapped in
> unmodifiableMap, all fields are final. This is the standard pattern for
> configuration objects in any gateway (Kong, Envoy, Spring Cloud Gateway)."

---

## 3. Factory Pattern (Creational)

### Ugly Anti-Pattern -- Scattered Instantiation

```java
// UGLY: Every class creates its own dependencies. Changing a strategy
// requires editing 10 different files. No central wiring. Testing is impossible.

public class UglyGatewayService {
    // Hardcoded dependencies created inline -- no injection
    private final RequestRouter router = new RequestRouter();
    private final JwtAuthStrategy auth = new JwtAuthStrategy(Map.of()); // hardcoded JWT
    private final RoundRobinLoadBalancer lb = new RoundRobinLoadBalancer(); // hardcoded RR
    private final RateLimiterEngine rateLimiter = new RateLimiterEngine();
    private final CircuitBreakerEngine cb = new CircuitBreakerEngine();
    // Want to use WeightedLoadBalancer? Edit this class.
    // Want to use ApiKeyAuth? Edit this class.
    // Want to test with a mock? Can't -- all concrete.
}
```

### Clean Solution -- AppConfig Factory (Composition Root)

```java
// CLEAN: AppConfig is the ONLY class that calls "new ConcreteClass()".
// All fields lazily initialized. Strategy setters null out dependents
// so the graph rebuilds automatically on next access.

public class AppConfig {

    // Lazy initialization -- created on first access
    private RoutingStrategy routingStrategy;
    private LoadBalancingStrategy loadBalancingStrategy;
    private AuthStrategy authStrategy;
    private GatewayService gatewayService;

    public LoadBalancingStrategy getLoadBalancingStrategy() {
        if (loadBalancingStrategy == null) {
            loadBalancingStrategy = new RoundRobinLoadBalancer(); // default
        }
        return loadBalancingStrategy;
    }

    // Strategy setters null out dependents for lazy rebuild:
    public void setLoadBalancingStrategy(LoadBalancingStrategy strategy) {
        this.loadBalancingStrategy = strategy;
        this.loadBalancerService = null;    // depends on LB strategy
        this.serviceMeshService = null;     // depends on LB service
        this.gatewayService = null;         // depends on everything
        this.gatewayController = null;      // depends on gateway service
    }

    public GatewayService getGatewayService() {
        if (gatewayService == null) {
            gatewayService = new GatewayService(
                getRoutingService(),         // auto-creates if null
                getAuthService(),            // auto-creates if null
                getRateLimitService(),       // auto-creates if null
                getCircuitBreakerService(),  // auto-creates if null
                getLoadBalancerService(),    // auto-creates if null
                getServiceMeshService()      // auto-creates if null
            );
        }
        return gatewayService;
    }
}
```

### Numbered Call Chain -- AppConfig Wiring

```
1. AppConfig.getController()
2.   gatewayController == null? YES
3.   --> AppConfig.getGatewayService()
4.       gatewayService == null? YES
5.       --> AppConfig.getRoutingService()
6.           --> new RoutingService(getRequestRouter(), getRoutingStrategy())
7.               --> getRequestRouter() -> new RequestRouter()
8.               --> getRoutingStrategy() -> new PathBasedRoutingStrategy()
9.       --> AppConfig.getAuthService()
10.          --> new AuthService(getAuthStrategy())
11.              --> getAuthStrategy() -> new JwtAuthStrategy(Map.of())
12.      --> AppConfig.getRateLimitService()
13.          --> new RateLimitService(getRateLimiterEngine())
14.              --> getRateLimiterEngine() -> new RateLimiterEngine()
15.      --> AppConfig.getCircuitBreakerService()
16.          --> new CircuitBreakerService(getCircuitBreakerEngine())
17.              --> getCircuitBreakerEngine() -> new CircuitBreakerEngine()
18.      --> AppConfig.getLoadBalancerService()
19.          --> new LoadBalancerService(getServiceRegistry(), getLoadBalancingStrategy())
20.              --> getServiceRegistry() -> new ServiceRegistry()
21.              --> getLoadBalancingStrategy() -> new RoundRobinLoadBalancer()
22.      --> AppConfig.getServiceMeshService()
23.          --> new ServiceMeshService(getTlsEngine(), getCBService(), getLBService(), getMeshConfig())
24.      --> new GatewayService(routing, auth, rateLimit, cb, lb, mesh)
25.  --> new GatewayController(gateway, lb, cb)
```

### ASCII Diagram -- Dependency Wiring Graph

```
                    AppConfig (Composition Root)
                    /    |     |     |     \     \
                   v     v     v     v      v     v
              Route   CB     Rate   Auth   LB    Mesh
              Repo   Engine  Engine Strat  Strat  Config
                |      |      |     |       |      |
                v      v      v     v       v      v
             Routing  CB    Rate   Auth    LB    Mesh
             Service  Svc   Svc    Svc     Svc    Svc
                 \     |     |     /       /      |
                  \    |     |    /       /       |
                   v   v     v   v       v        v
                   +---------------------+--------+
                   |   GatewayService    |
                   |   (Facade)          |
                   +---------------------+
                            |
                            v
                   +---------------------+
                   | GatewayController   |
                   +---------------------+
                            |
                            v
                   +---------------------+
                   | GatewayStatsDisplay |
                   +---------------------+
```

### Interview Soundbite

> "AppConfig is our composition root -- the only place where `new ConcreteClass()`
> appears. Lazy initialization means objects are created on demand. Strategy setters
> null out downstream dependents so the graph self-heals. This mirrors Spring's
> application context but with explicit wiring -- great for understanding the
> dependency graph without DI magic."

---

## 4. Repository Pattern (Structural -- Enterprise)

### Ugly Anti-Pattern -- Data Access Mixed with Business Logic

```java
// UGLY: Service directly manages ConcurrentHashMap. Data access code
// is tangled with routing logic. Can't swap to Redis/Consul without
// rewriting the entire service.

public class UglyRoutingService {
    private final Map<String, Route> routeStore = new ConcurrentHashMap<>(); // data access

    public Optional<Route> matchRoute(HttpRequest request) {
        // Business logic AND data access in the same method
        for (Route route : routeStore.values()) {
            if (route.matches(request.getPath(), request.getMethod())) {
                return Optional.of(route);
            }
        }
        return Optional.empty();
    }

    public void save(Route route) {
        routeStore.put(route.getId(), route); // direct map access
    }
}
```

### Clean Solution -- Repository Interface + InMemory Implementation

```java
// CLEAN: Interface defines the contract. InMemory implements it.
// Swap to Redis/Consul by implementing the same interface.

public interface RouteRepository {
    void save(Route route);
    Optional<Route> findById(String id);
    List<Route> findByPathPattern(String pattern);
    List<Route> findAll();
    void deleteById(String id);
}

public class InMemoryRouteRepository implements RouteRepository {
    private final Map<String, Route> store = new ConcurrentHashMap<>();

    @Override
    public void save(Route route) { store.put(route.getId(), route); }

    @Override
    public Optional<Route> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }
    // ... other methods delegate to ConcurrentHashMap
}

public interface ServiceInstanceRepository {
    void save(ServiceInstance instance);
    Optional<ServiceInstance> findById(String id);
    List<ServiceInstance> findByServiceName(String serviceName);
    List<ServiceInstance> findHealthy(String serviceName);  // health-aware query
    List<ServiceInstance> findAll();
    void deleteById(String id);
}
```

### Numbered Call Chain -- Route Lookup

```
1. GatewayController.handleRequest(request)
2.   --> GatewayService.handleRequest(request)
3.       --> RoutingService.matchRoute(request)
4.           --> RequestRouter.match(request)
5.               iterate priority-sorted List<Route>
6.               route.matches(path, method) -> true
7.               return Optional.of(route)
8.       // Route found -- continue pipeline
```

### ASCII Diagram -- Repository Abstraction

```
+----------------------+         +-----------------------------+
| RouteRepository      |<--------|  InMemoryRouteRepository    |
| <<interface>>        |         |  (ConcurrentHashMap)        |
|                      |         +-----------------------------+
| + save(Route)        |
| + findById(id)       |         +-----------------------------+
| + findByPathPattern()|<--------|  RedisRouteRepository       |
| + findAll()          |         |  (future extension)         |
| + deleteById(id)     |         +-----------------------------+
+----------------------+

+----------------------+         +-----------------------------+
| ServiceInstanceRepo  |<--------|  InMemoryServiceInstanceRepo|
| <<interface>>        |         |  (ConcurrentHashMap)        |
|                      |         +-----------------------------+
| + save(instance)     |
| + findById(id)       |         +-----------------------------+
| + findByServiceName()|<--------|  ConsulInstanceRepository   |
| + findHealthy(name)  |         |  (future extension)         |
| + findAll()          |         +-----------------------------+
| + deleteById(id)     |
+----------------------+
```

### Interview Soundbite

> "RouteRepository and ServiceInstanceRepository are interfaces with InMemory
> implementations backed by ConcurrentHashMap. In production, you'd swap to
> ConsulServiceInstanceRepository or RedisRouteRepository -- same interface,
> different backend. The service layer never knows how data is stored."

---

## 5. Facade Pattern (Structural) -- THE ORCHESTRATOR

### Ugly Anti-Pattern -- Client Calls 6 Services Directly

```java
// UGLY: The controller must know about every sub-service and call them
// in the right order. Miss a step? Security vulnerability. Wrong order?
// Rate limiting after auth fails means 401s don't count against rate limits.

public class UglyGatewayController {
    private final RoutingService routing;
    private final AuthService auth;
    private final RateLimitService rateLimit;
    private final CircuitBreakerService cb;
    private final LoadBalancerService lb;
    private final ServiceMeshService mesh;

    public HttpResponse handleRequest(HttpRequest request) {
        // Controller has to know the exact order
        Optional<Route> route = routing.matchRoute(request);
        if (route.isEmpty()) return HttpResponse.error(404, "Not found");
        // Did we forget auth? Oops, unauthenticated requests hit rate limiter
        RateLimitResult rl = rateLimit.checkRouteRateLimit(/* ... */);
        // Auth after rate limiting? Wrong order!
        AuthResult ar = auth.authenticate(request);
        // 6 more steps... every controller must repeat this
    }
}
```

### Clean Solution -- GatewayService Facade

```java
// CLEAN: GatewayService is the ONLY class that knows the pipeline order.
// Controller calls one method: handleRequest(). The 10-step pipeline is
// encapsulated inside the facade. Cannot skip steps. Cannot reorder.

public class GatewayService {
    // Facade -- hides 6 sub-services behind one method
    private final RoutingService routingService;
    private final AuthService authService;
    private final RateLimitService rateLimitService;
    private final CircuitBreakerService circuitBreakerService;
    private final LoadBalancerService loadBalancerService;
    private final ServiceMeshService serviceMeshService;

    public HttpResponse handleRequest(HttpRequest request) {
        // 1. Create context
        // 2. Route match        -> 404 if no match
        // 3. Authenticate       -> 401 if invalid
        // 4. Authorize          -> 403 if denied
        // 5. Rate limit         -> 429 if exceeded
        // 6. Circuit breaker    -> 503 if open
        // 7. Load balance       -> 503 if no instances
        // 8. Forward            -> simulated upstream call
        // 9. Record CB result   -> 502 if upstream failed
        // 10. Return response   -> 200 OK with traceId
    }
}

// Controller is now trivial:
public class GatewayController {
    private final GatewayService gatewayService;

    public HttpResponse handleRequest(HttpRequest request) {
        return gatewayService.handleRequest(request); // one call
    }
}
```

### Numbered Call Chain -- Full Pipeline

```
1.  GatewayController.handleRequest(request)
2.    --> GatewayService.handleRequest(request)
3.        --> new RequestContext(request)                    // traceId, startTime
4.        --> routingService.matchRoute(request)             // Step 2: route
5.            --> RequestRouter.match(request)               //   path + method matching
6.        --> authService.authenticate(request)              // Step 3: auth
7.            --> JwtAuthStrategy.authenticate(request)      //   JWT decode + roles
8.        --> authService.authorize(authResult, route)       // Step 4: authz
9.            --> route.metadata("required-role")            //   role check
10.       --> rateLimitService.checkRouteRateLimit(ctx)      // Step 5: rate limit
11.           --> rateLimiter.tryConsume(routeId)            //   token bucket
12.       --> circuitBreakerService.allowRequest(target)     // Step 6: circuit breaker
13.           --> engine.allowRequest(serviceName)           //   state check
14.       --> loadBalancerService.selectInstance(target, req)// Step 7: load balance
15.           --> registry.getInstances(serviceName)         //   healthy instances
16.           --> strategy.selectInstance(healthy, request)  //   RR/weighted/hash
17.       --> Thread.sleep(latencyMs)                        // Step 8: simulated forward
18.       --> circuitBreakerService.recordSuccess(target)    // Step 9: record
19.       --> new HttpResponse.Builder(200)                  // Step 10: build response
20.           .header("X-Trace-Id", traceId)
21.           .build()
```

### ASCII Diagram -- Facade Hides Complexity

```
WITHOUT Facade:                    WITH Facade:

Controller must know               Controller calls ONE method:
all 6 services and order:
                                    +------------------+
+------------------+                | GatewayController|
| GatewayController|                |                  |
|                  |                | handleRequest()  |
| route()          |                +--------+---------+
| auth()           |                         |
| authz()          |                         v
| rateLimit()      |                +------------------+
| circuitBreaker() |                | GatewayService   |
| loadBalance()    |                | (FACADE)         |
| forward()        |                |                  |
| record()         |                | 10-step pipeline |
| respond()        |                | fully encapsulated|
+------------------+                +------------------+
                                    /  |  |  |  |  \
9 method calls to                  v   v  v  v  v   v
coordinate correctly            Route Auth RL CB LB Mesh
```

### Interview Soundbite

> "GatewayService is a Facade that orchestrates the full 10-step pipeline:
> route, auth, authz, rate limit, circuit breaker, load balance, forward,
> record, respond. The controller makes one call. Any step can short-circuit
> (401, 429, 503). This is exactly how Kong and Envoy structure their filter
> chains -- a single entry point that hides the complexity of the pipeline."

---

## 6. State Pattern (Behavioral) -- Circuit Breaker

### Ugly Anti-Pattern -- Boolean Flags Instead of State Machine

```java
// UGLY: Boolean flags instead of explicit states. No state transitions.
// Impossible to know if you're in half-open. Recovery logic is ad-hoc.

public class UglyCircuitBreaker {
    private boolean isOpen = false;
    private int failures = 0;

    public boolean allowRequest() {
        if (isOpen) return false; // No half-open, no recovery testing
        return true;
    }

    public void recordFailure() {
        failures++;
        if (failures > 5) isOpen = true; // No cooldown, no probe
    }

    public void reset() {
        isOpen = false; // How do we know it's safe to reset?
        failures = 0;   // No gradual recovery testing
    }
}
```

### Clean Solution -- CircuitBreakerState with Explicit States

```java
// CLEAN: Three explicit states with well-defined transitions.
// Each state has clear entry/exit conditions and behavior.

public enum CircuitState {
    CLOSED("Normal operation -- requests flow through"),
    OPEN("Circuit tripped -- requests rejected immediately"),
    HALF_OPEN("Testing recovery -- limited requests allowed through");
}

public class CircuitBreakerState {
    private CircuitState state = CircuitState.CLOSED;
    private int failureCount = 0;
    private int successCount = 0;
    private final int failureThreshold = 5;     // CLOSED -> OPEN after 5 failures
    private final int successThreshold = 3;     // HALF_OPEN -> CLOSED after 3 successes
    private final long openDurationMs = 30_000; // stay OPEN for 30s before probing

    public void recordSuccess() {
        switch (state) {
            case HALF_OPEN -> { successCount++; if (successCount >= successThreshold) reset(); }
            case CLOSED -> failureCount = 0;
            case OPEN -> { /* ignored */ }
        }
    }

    public void recordFailure() {
        lastFailureTime = Instant.now();
        switch (state) {
            case CLOSED -> { failureCount++; if (failureCount >= failureThreshold) trip(); }
            case HALF_OPEN -> trip(); // immediately back to OPEN
            case OPEN -> { /* already open */ }
        }
    }
}
```

### Numbered Call Chain -- Circuit Breaker Lifecycle

```
HEALTHY OPERATION:
1. CircuitBreakerEngine.allowRequest("user-service")
2.   state = CLOSED -> return true (allow)
3. Forward request to user-service -> success
4. CircuitBreakerEngine.recordSuccess("user-service")
5.   state = CLOSED -> failureCount = 0

DEGRADATION (5 consecutive failures):
1. recordFailure -> failureCount=1, state=CLOSED
2. recordFailure -> failureCount=2, state=CLOSED
3. recordFailure -> failureCount=3, state=CLOSED
4. recordFailure -> failureCount=4, state=CLOSED
5. recordFailure -> failureCount=5 >= threshold(5)
6.   trip() -> state=OPEN, successCount=0, lastStateChange=now

REJECTION (circuit open):
1. CircuitBreakerEngine.allowRequest("user-service")
2.   state = OPEN
3.   shouldAttemptReset()? elapsed=2s < openDuration=30s -> NO
4.   return false (REJECT -> 503 Service Unavailable)

RECOVERY (after 30s cooldown):
1. CircuitBreakerEngine.allowRequest("user-service")
2.   state = OPEN
3.   shouldAttemptReset()? elapsed=31s > openDuration=30s -> YES
4.   halfOpen() -> state=HALF_OPEN, successCount=0, failureCount=0
5.   return true (allow probe request)
6. Forward probe request -> success
7. recordSuccess -> successCount=1, state=HALF_OPEN
8. Another success -> successCount=2
9. Another success -> successCount=3 >= threshold(3)
10.  reset() -> state=CLOSED, failureCount=0, successCount=0
```

### ASCII Diagram -- State Machine

```
                    +===========+
                    |           |
          +------->|  CLOSED   |<----------+
          |        |           |           |
          |        | requests  |           |
          |        | flow      |           |
          |        +-----+-----+           |
          |              |                 |
          |     failureCount >= 5          |
          |              |                 |
          |              v           successCount >= 3
          |        +===========+           |
          |        |           |           |
          |        |   OPEN    |           |
          |        |           |           |
          |        | requests  |           |
          |        | REJECTED  |           |
          |        +-----+-----+           |
          |              |                 |
          |     30s cooldown expires       |
          |              |                 |
          |              v                 |
          |        +===========+           |
          |        |           |-----------+
          +--------|  HALF     |  success * 3
        failure    |  OPEN     |
        (any)      |           |
                   | limited   |
                   | probes    |
                   +===========+

  CLOSED:    Normal. Count failures. failureCount >= 5 -> trip to OPEN.
  OPEN:      Reject all. After 30s -> transition to HALF_OPEN.
  HALF_OPEN: Allow limited probes. 3 successes -> CLOSED. Any failure -> OPEN.
```

### Interview Soundbite

> "The circuit breaker uses a three-state machine: CLOSED for normal operation,
> OPEN to reject all requests after the failure threshold (5 failures), and
> HALF_OPEN to test recovery with probe requests after a 30-second cooldown.
> Three successes in HALF_OPEN close the circuit. Any failure in HALF_OPEN
> immediately re-opens it. This is Netflix Hystrix / Resilience4j / Envoy's
> exact model."

---

## 7. Chain of Responsibility Pattern (Behavioral)

### Ugly Anti-Pattern -- Monolithic If-Else Pipeline

```java
// UGLY: One giant method with sequential if-else checks.
// Adding a new filter (logging, CORS, compression) means modifying
// the method body. Cannot reorder filters. Cannot conditionally skip.

public HttpResponse handleRequest(HttpRequest request) {
    // 50 lines of route matching
    // 30 lines of auth
    // 20 lines of rate limiting
    // 15 lines of circuit breaker
    // 25 lines of load balancing
    // ... 200+ line method, all in one place
}
```

### Clean Solution -- GatewayFilter Interface + Pipeline

```java
// CLEAN: Each concern is a separate GatewayFilter.
// Filters return Optional.empty() to continue, or Optional.of(response) to short-circuit.

@FunctionalInterface
public interface GatewayFilter {
    Optional<HttpResponse> filter(RequestContext ctx);
}

// Each step in the pipeline can short-circuit independently:
// Route filter:  no match     -> Optional.of(404)
// Auth filter:   invalid JWT  -> Optional.of(401)
// AuthZ filter:  missing role -> Optional.of(403)
// Rate filter:   over limit   -> Optional.of(429)
// CB filter:     circuit open -> Optional.of(503)

// GatewayService implements this as a procedural pipeline (10 steps),
// but the GatewayFilter interface enables extension to a dynamic
// filter chain (List<GatewayFilter>) without modifying the core.
```

### Numbered Call Chain -- Short-Circuit at Auth

```
1. GatewayService.handleRequest(request)
2.   Step 1: RequestContext created
3.   Step 2: RoutingService.matchRoute(request)
4.     -> route found: /api/users/** -> user-service
5.   Step 3: AuthService.authenticate(request)
6.     -> Authorization header missing
7.     -> AuthResult.unauthorized("Missing or invalid Authorization header")
8.     -> SHORT-CIRCUIT: return 401 Unauthorized
9.     // Steps 4-10 NEVER execute
10.    // Rate limiter not consumed (good -- don't penalize failed auth)
11.    // Circuit breaker not checked (good -- no upstream call)
```

### ASCII Diagram -- Filter Chain with Short-Circuit

```
Request -->  [Route]  -->  [Auth]  -->  [AuthZ]  -->  [RateLimit]
               |             |            |              |
               | 404         | 401        | 403          | 429
               v             v            v              v
            Response      Response     Response       Response
                             ^
                             |
                    SHORT-CIRCUIT HERE
                    (steps 4-10 skipped)

     --> [CircuitBreaker]  -->  [LoadBalance]  -->  [Forward]  -->  [Record]  -->  [Respond]
              |                     |                  |               |              |
              | 503                 | 503              | 502           |              |
              v                     v                  v              v              v
           Response              Response           Response       Update CB      200 OK
```

### Interview Soundbite

> "The gateway pipeline is a Chain of Responsibility. Each step can short-circuit
> with an error response (404, 401, 403, 429, 503). The GatewayFilter interface
> returns Optional<HttpResponse> -- empty means continue, present means stop.
> This is exactly how Servlet Filters, Spring Cloud Gateway Filters, and Envoy
> HTTP filter chains work."

---

## 8. Proxy Pattern (Structural) -- Service Mesh Sidecar

### Ugly Anti-Pattern -- Direct Service-to-Service Calls

```java
// UGLY: Services call each other directly. No mTLS, no circuit breaking,
// no load balancing, no observability. Every service must implement
// retry logic, health checking, and security on its own.

public class UglyOrderService {
    public String callUserService(String userId) {
        // Direct HTTP call -- no sidecar, no proxy
        String url = "http://10.0.1.10:8080/users/" + userId;
        // No mTLS (plaintext)
        // No circuit breaker (cascade if user-service is down)
        // No load balancing (always hits same instance)
        // No retry logic
        // No distributed tracing
        return httpClient.get(url);
    }
}
```

### Clean Solution -- ServiceMeshService as Sidecar Proxy

```java
// CLEAN: ServiceMeshService acts as a transparent sidecar proxy.
// All cross-cutting concerns handled without application code changes.

public class ServiceMeshService {
    private final TlsEngine tlsEngine;                    // mTLS
    private final CircuitBreakerService circuitBreakerService; // resilience
    private final LoadBalancerService loadBalancerService;     // distribution
    private final ServiceMeshConfig meshConfig;               // config

    public HttpResponse proxyRequest(String caller, String target, HttpRequest request) {
        // Step 1: mTLS validation (zero-trust security)
        // Step 2: Circuit breaker check (failure isolation)
        // Step 3: Load balance (instance selection)
        // Step 4: Forward (with latency simulation)
        // Step 5: Record CB result (feedback loop)
        // Step 6: Return response (with mesh headers)
    }
}

// Application code stays simple:
meshService.proxyRequest("order-service", "user-service", request);
// The sidecar handles: mTLS, CB, LB, retry, tracing -- transparently.
```

### Numbered Call Chain -- Sidecar Proxy

```
1. ServiceMeshService.proxyRequest("order-service", "user-service", request)
2.   Step 1: mTLS validation
3.     tlsEngine.validateConnection("order-service", "user-service")
4.       trustedServices contains both? -> YES -> PASSED
5.   Step 2: Circuit breaker
6.     circuitBreakerService.allowRequest("user-service")
7.       state = CLOSED -> allowed
8.   Step 3: Load balance
9.     loadBalancerService.selectInstance("user-service", request)
10.      registry.getInstances("user-service") -> [inst-1, inst-2, inst-3]
11.      strategy.selectInstance(instances, request) -> inst-1
12.  Step 4: Forward to inst-1 (10.0.1.10:8080)
13.      latency = 23ms, success = true
14.  Step 5: Record success
15.      circuitBreakerService.recordSuccess("user-service")
16.  Step 6: Build response
17.      200 OK with headers: X-Mesh-Source=order-service,
18.      X-Mesh-Target=user-service, X-Mesh-Instance=10.0.1.10:8080
```

### ASCII Diagram -- Sidecar Proxy

```
WITHOUT Mesh:                      WITH Mesh (Sidecar Proxy):

order-service ----> user-service    order-service --> [SIDECAR] --> user-service
(direct call)                                            |
(no mTLS)                           The sidecar handles:
(no CB)                              1. mTLS (encrypt + verify identity)
(no LB)                              2. Circuit Breaker (fail fast)
(no retry)                           3. Load Balancing (pick instance)
(no tracing)                         4. Retry (with exponential backoff)
                                     5. Tracing (propagate traceId)
                                     6. Metrics (latency, error rate)

  +-------------+     +--------+     +--------+     +-------------+
  | order-svc   |---->| sidecar|---->| sidecar|---->| user-svc    |
  | (app code)  |     | proxy  |     | proxy  |     | (app code)  |
  +-------------+     +--------+     +--------+     +-------------+
                       (Envoy)        (Envoy)
                       outbound       inbound

  Real-world: Istio deploys Envoy sidecars via K8s admission webhook.
  Control plane (Istiod) pushes config via xDS protocol.
  Data plane (Envoy sidecars) handles all traffic.
```

### Interview Soundbite

> "ServiceMeshService simulates an Envoy sidecar proxy. Service-to-service
> calls go through the sidecar which handles mTLS (zero-trust), circuit
> breaking, load balancing, and observability -- all without application
> code changes. In production, Istio's control plane configures Envoy
> sidecars via the xDS protocol. The Proxy pattern makes cross-cutting
> concerns transparent."

---

## 9. Singleton Pattern (Creational)

### Ugly Anti-Pattern -- Multiple Config Instances

```java
// UGLY: Every class creates its own AppConfig. Different configs create
// different instances of the same services. Circuit breaker state is
// split across multiple CircuitBreakerEngine instances. Chaos.

public class UglyApp {
    public static void main(String[] args) {
        AppConfig config1 = new AppConfig();
        AppConfig config2 = new AppConfig();

        // config1 and config2 have DIFFERENT CircuitBreakerEngines!
        // Failures recorded in config1's engine don't trip config2's breaker.
        // Two separate ServiceRegistries with different instance lists.
        // Two separate RateLimiterEngines with independent token buckets.
    }
}
```

### Clean Solution -- Single AppConfig with Lazy Initialization

```java
// CLEAN: One AppConfig instance created in main(). All objects are
// created lazily and cached. Strategy setters invalidate dependents
// for automatic rebuild on next access.

public class ApiGatewayServiceMeshApp {
    public static void main(String[] args) {
        AppConfig config = new AppConfig(); // single instance

        // All services share the same engines:
        //   - One CircuitBreakerEngine (shared state across all services)
        //   - One ServiceRegistry (single source of truth for instances)
        //   - One RateLimiterEngine (unified rate limiting)

        GatewayService gateway = config.getGatewayService();   // lazily creates everything
        GatewayController controller = config.getController();  // reuses existing services
    }
}

// Lazy initialization in AppConfig:
public GatewayService getGatewayService() {
    if (gatewayService == null) {            // first access creates it
        gatewayService = new GatewayService( // constructor injection
            getRoutingService(),             // lazily creates if null
            getAuthService(),                // lazily creates if null
            getRateLimitService(),           // lazily creates if null
            getCircuitBreakerService(),      // lazily creates if null
            getLoadBalancerService(),        // lazily creates if null
            getServiceMeshService()          // lazily creates if null
        );
    }
    return gatewayService;                   // subsequent calls return cached
}
```

### ASCII Diagram -- Singleton Config

```
                     +============+
                     |  AppConfig |  <-- Single Instance
                     |  (Singleton|
                     |  Factory)  |
                     +============+
                    /   |   |   |  \
                   v    v   v   v   v
          +------+ +--+ +--+ +--+ +----+
          |Router| |CB| |RL| |SR| |TLS |  <-- One of each engine
          +------+ |Eng| |Eng| |  | |Eng|     (shared state)
                   +--+ +--+ +--+ +----+
                    |    |    |     |
                   v    v    v     v
               +----+ +--+ +--+ +----+
               |Route| |CB | |RL| |LB  |  <-- One of each service
               |Svc  | |Svc| |Svc| |Svc|     (shares engines)
               +----+ +--+ +--+ +----+
                    \   |   |   /
                     v  v   v  v
                  +===============+
                  | GatewayService|  <-- Single Facade
                  +===============+
                         |
                         v
                  +===============+
                  |GatewayController| <-- Single Controller
                  +===============+

All objects share the same AppConfig instance.
Circuit breaker state is unified.
Service registry is the single source of truth.
Rate limiter has one set of token buckets.
```

### Interview Soundbite

> "AppConfig acts as a singleton composition root with lazy initialization.
> One instance, created in main(), with all objects cached after first access.
> Strategy setters null out dependents so the graph self-heals. This ensures
> shared state -- one CircuitBreakerEngine, one ServiceRegistry, one
> RateLimiterEngine -- just like Spring's application context but explicit."

---

## Pattern Interaction Map

```
+=====================================================================+
|              HOW THE 9 PATTERNS WORK TOGETHER                        |
+=====================================================================+

1. FACTORY (AppConfig) creates everything:
   |
   +--> Creates STRATEGY instances (4 families)
   +--> Creates BUILDER-configured models (HttpRequest, Route, Config)
   +--> Creates REPOSITORY implementations (InMemory)
   +--> Creates ENGINES and SERVICES
   +--> Creates the FACADE (GatewayService)
   +--> SINGLETON ensures one config instance

2. Client sends request to FACADE (GatewayService):
   |
   +--> CHAIN OF RESPONSIBILITY: 10-step pipeline
        |
        +--> Step 2: STRATEGY (RoutingStrategy) selects route
        +--> Step 3: STRATEGY (AuthStrategy) authenticates
        +--> Step 5: Token bucket rate limiting
        +--> Step 6: STATE machine (CircuitBreaker) checks health
        +--> Step 7: STRATEGY (LoadBalancingStrategy) picks instance
        +--> Step 8: Forward (or PROXY for mesh calls)

3. Service-to-service calls go through PROXY (ServiceMeshService):
   |
   +--> mTLS validation
   +--> STATE machine check (circuit breaker)
   +--> STRATEGY (LoadBalancingStrategy) picks instance
   +--> Forward and record result

4. Data persisted via REPOSITORY:
   |
   +--> RouteRepository stores route definitions
   +--> ServiceInstanceRepository stores instance registrations

5. Traffic shaping uses STRATEGY (TrafficStrategy):
   |
   +--> Canary: weighted random across versions
   +--> Header: X-Canary opt-in for internal testing
```

---

## Pattern Combinations in Real Scenarios

### Scenario 1: New Client Sends First Request

```
Patterns activated:

1. CLIENT sends: GET /api/users/123 with Bearer JWT

2. SINGLETON (AppConfig):
   First access -> lazy-create entire object graph

3. BUILDER (HttpRequest):
   new HttpRequest.Builder(GET, "/api/users/123")
       .header("Authorization", "Bearer eyJh...").build()

4. FACADE (GatewayService.handleRequest):
   Entry point into the 10-step pipeline

5. CHAIN OF RESPONSIBILITY (pipeline steps):
   Step 2: STRATEGY (PathBasedRoutingStrategy) -> route found
   Step 3: STRATEGY (JwtAuthStrategy) -> authenticated
   Step 4: Role check against route metadata -> authorized
   Step 5: Token bucket check -> allowed
   Step 6: STATE (CircuitBreakerState) -> CLOSED, allow
   Step 7: STRATEGY (RoundRobinLoadBalancer) -> inst-A selected

6. BUILDER (HttpResponse):
   new HttpResponse.Builder(200).body(...).header("X-Trace-Id", ...).build()

Count: 6 patterns in a single request flow.
```

### Scenario 2: Upstream Service Degrades

```
Patterns activated:

1. FACADE receives request -> pipeline proceeds to Step 8 (forward)

2. Forward fails (5% simulated failure)

3. STATE (CircuitBreakerState):
   recordFailure() -> failureCount++
   After 5th failure: trip() -> CLOSED to OPEN
   State machine transition logged

4. CHAIN OF RESPONSIBILITY (next request):
   Step 6: Circuit breaker check -> OPEN -> SHORT-CIRCUIT
   Return 503 without hitting Steps 7-10
   (Upstream is protected from further load)

5. After 30s cooldown:
   STATE: OPEN -> HALF_OPEN (probe allowed)
   3 successes -> HALF_OPEN -> CLOSED (recovered)

6. PROXY (ServiceMeshService):
   If this was a mesh call, the sidecar proxy
   also checks its circuit breaker independently

Patterns: Facade, State, Chain of Responsibility, Proxy
```

### Scenario 3: Hot-Swap Load Balancer at Runtime

```
Patterns activated:

1. FACTORY (AppConfig):
   config.setLoadBalancingStrategy(new ConsistentHashLoadBalancer())
   -> nullify: loadBalancerService, serviceMeshService,
              gatewayService, gatewayController

2. SINGLETON:
   Next access triggers lazy rebuild of affected subgraph only.
   Engines (CB, RL, Registry) are NOT rebuilt -- shared state preserved.

3. STRATEGY (ConsistentHashLoadBalancer):
   Next request uses consistent hash instead of round-robin.
   Same path always hits same instance (cache affinity).
   No code changes, no restart, no recompilation.

4. FACADE (GatewayService):
   New GatewayService created with new LoadBalancerService.
   Pipeline order unchanged. Same 10 steps.

Patterns: Factory, Singleton, Strategy, Facade
```

### Scenario 4: Service-to-Service Call via Mesh

```
Patterns activated:

1. PROXY (ServiceMeshService):
   order-service wants to call user-service.
   meshService.proxyRequest("order-service", "user-service", request)

2. PROXY pipeline:
   Step 1: TlsEngine.validateConnection() -> mTLS check
   Step 2: STATE (CircuitBreakerState) -> allow/deny
   Step 3: STRATEGY (LoadBalancingStrategy) -> pick instance
   Step 4: Forward (simulated)
   Step 5: STATE -> recordSuccess/recordFailure
   Step 6: BUILDER (HttpResponse) -> add mesh headers

3. REPOSITORY:
   ServiceRegistry (acts as repo) provides instance list
   for the load balancer strategy.

Patterns: Proxy, State, Strategy, Builder, Repository
```

---

## Anti-Pattern Summary

| # | Anti-Pattern | Problem | Clean Pattern | Fix |
|---|-------------|---------|---------------|-----|
| 1 | Hardcoded algorithms | if-else chains for routing, auth, LB | Strategy (x4) | Interface + concrete implementations + setStrategy() |
| 2 | Telescoping constructors | 10-param constructors, wrong argument order | Builder | Required params in Builder ctor, optional via fluent API |
| 3 | Scattered instantiation | Every class creates its own deps | Factory | AppConfig composition root, one place for all `new` |
| 4 | Mixed data access + logic | ConcurrentHashMap in service class | Repository | Interface + InMemory implementation, swap to Redis |
| 5 | Client calls N services | Controller orchestrates 6 services | Facade | GatewayService.handleRequest() hides pipeline |
| 6 | Boolean flags for state | isOpen/isClosed booleans | State | CircuitBreakerState with enum + transition methods |
| 7 | Monolithic handler | 200-line method with all pipeline steps | Chain of Resp | GatewayFilter interface, each step can short-circuit |
| 8 | Direct service calls | No mTLS, no CB, no LB between services | Proxy | ServiceMeshService sidecar wraps all cross-cutting |
| 9 | Multiple config instances | Split state across JVMs | Singleton | One AppConfig, lazy init, shared engines |

---

## Interview Cheat Sheet

### When the interviewer asks about API Gateway design:

```
"The gateway is a Facade that orchestrates a Chain of Responsibility pipeline:
route matching, JWT authentication, RBAC authorization, token bucket rate
limiting, circuit breaker check, and load balancing. Each step can short-circuit
with an appropriate HTTP error (401, 403, 429, 503). Four Strategy interfaces
make routing, LB, auth, and traffic shaping pluggable and hot-swappable."
```

### When they ask about circuit breaker:

```
"Three-state machine: CLOSED (normal, count failures), OPEN (reject all,
30s cooldown), HALF_OPEN (probe with limited requests). Five consecutive
failures trip CLOSED to OPEN. Three successes in HALF_OPEN close the circuit.
Any failure in HALF_OPEN re-opens immediately. This prevents cascading
failures across microservices."
```

### When they ask about load balancing:

```
"Three strategies behind LoadBalancingStrategy interface: round-robin
(AtomicInteger, O(1), fair), weighted (proportional to instance capacity),
and consistent hash (FNV-1a, 150 virtual nodes, TreeMap ring, cache affinity).
Consistent hash is key -- same request path always hits the same instance,
maximizing local cache hit rates. Adding/removing instances only remaps ~1/N keys."
```

### When they ask about service mesh:

```
"ServiceMeshService simulates an Envoy sidecar proxy for east-west traffic.
The sidecar handles mTLS (zero-trust security), circuit breaking, load
balancing, and observability transparently. Application code just calls
meshService.proxyRequest() -- no security or resilience logic in the app.
Istio's control plane pushes configuration to Envoy sidecars via xDS protocol."
```

### When they ask about rate limiting:

```
"Token bucket algorithm: each client/route gets a bucket with configurable
capacity and refill rate. Tokens replenish based on elapsed time. Each request
consumes one token. Bucket empty -> 429 with Retry-After header. Allows bursts
up to bucket capacity, then enforces steady-state rate. Better than fixed
window which allows 2x burst at window boundaries. In production: Redis + Lua
script for distributed rate limiting across multiple gateway instances."
```

---

## Quick Reference -- All Patterns at a Glance

```
Pattern                 GoF         Where                           One-liner
-----------------------+-----------+-------------------------------+----------------------------------
Strategy (x4)          Behavioral  Routing, LB, Auth, Traffic     "Swap algorithms at runtime via interface"
Builder                Creational  HttpRequest, Route, Config     "Complex objects with fluent API, immutable result"
Factory                Creational  AppConfig composition root     "One place creates everything, lazy init"
Repository (x2)        Enterprise  RouteRepo, InstanceRepo        "Data access behind interface, swap backend"
Facade                 Structural  GatewayService                 "One method hides 10-step pipeline"
State                  Behavioral  CircuitBreakerState            "CLOSED/OPEN/HALF_OPEN state machine"
Chain of Responsibility Behavioral GatewayFilter pipeline         "Each step can short-circuit (401/429/503)"
Proxy                  Structural  ServiceMeshService sidecar     "Transparent interception of service calls"
Singleton              Creational  AppConfig lazy init             "One factory, shared state, self-healing graph"
```

---

## Real-World Pattern Usage in Production Gateways

```
Pattern              Kong                 Envoy (Istio)         AWS API Gateway
------------------------------------------------------------------------------------
Strategy             Plugin system        Filter chain          Lambda authorizers
                     (auth, rate limit    (each filter is a     (custom auth via
                     are plugins)         strategy impl)        Lambda function)

Builder              Kong config YAML     xDS protobuf          OpenAPI/SAM
                     declarative routes   messages build         template builds
                                          route config           API resources

Factory              Kong init            Istio control plane   CloudFormation
                     (plugin loader)      (Istiod creates       creates all
                                          Envoy config)         API resources

Repository           PostgreSQL           etcd (K8s API         DynamoDB
                     (route/plugin        server stores          (internal route
                     config store)        VirtualService)        storage)

Facade               Kong proxy handler   Envoy HTTP            API Gateway
                     (single entry for    connection manager    integration
                     all requests)        (single pipeline)     request handler

State                N/A (plugin-based)   Outlier detection     N/A (managed)
                                          (circuit breaker
                                          state machine)

Chain of Resp        Plugin chain         HTTP filter chain     Request pipeline
                     (pre/post filters)   (12+ filters per      (auth, throttle,
                                          request)              transform, route)

Proxy                Kong Mesh            Envoy sidecar         VPC Link
                     (sidecar proxy)      (transparent proxy    (proxy to VPC
                                          for all traffic)      resources)

Singleton            Kong shared state    Envoy shared memory   Managed by AWS
                     (worker processes    (hot restart with      (single control
                     share config)        shared memory)         plane per region)
```
