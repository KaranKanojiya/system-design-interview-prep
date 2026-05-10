# API Gateway & Service Mesh (Kong / Zuul / Envoy / Istio)

## Problem Summary

Design an **API Gateway and Service Mesh** (like Kong, Netflix Zuul, Envoy, or Istio) that provides a unified ingress layer and inter-service communication fabric for microservice architectures. The core challenge is the **gateway pipeline** -- a Chain of Responsibility that processes every incoming request through seven sequential stages: **(1) route matching** (path/header-based with glob wildcard patterns, priority-sorted, first-match wins), **(2) authentication** (JWT token validation with Base64-decoded claims extraction, or API key lookup from a trusted keystore), **(3) authorization** (role-based access control per-route via metadata-driven required-role checks), **(4) rate limiting** (token bucket algorithm -- bucket starts full at capacity `C`, each request consumes one token, tokens refill at rate `R` per second via elapsed-time calculation, allows controlled bursts up to `C` then smoothly limits to `R` req/sec), **(5) circuit breaker** (three-state machine: CLOSED allows all requests and counts failures, OPEN rejects all requests for a configurable cooldown period, HALF_OPEN allows probe requests and transitions to CLOSED after `successThreshold` consecutive successes or back to OPEN on any failure), **(6) load balancing** (pluggable strategies: round-robin cycles through healthy instances, weighted distributes proportionally to instance weight, consistent hash uses a TreeMap-based virtual-node ring with FNV-1a hashing for cache-affinity routing where the same path always maps to the same instance), and **(7) forward** to the selected upstream service instance. The **service mesh** layer adds a sidecar proxy pattern for service-to-service communication that handles **mTLS** (mutual TLS -- both caller and target must be in a trusted service set, enforcing zero-trust networking without application code changes), **service discovery** with health checking (registry stores instances with `volatile HealthStatus` and heartbeat timestamps, stale instances are evicted via `evictStale(Duration timeout)`), and **traffic shaping** for canary deployments (weighted random version selection from a `TrafficSplit` map where e.g. `{"v1-stable": 90, "v2-canary": 10}` routes ~10% of traffic to the canary, plus header-based routing where `X-Canary: true` forces canary selection). The system demonstrates that an API gateway is the **single choke point** for cross-cutting concerns -- authentication, authorization, rate limiting, and observability flow through one pipeline instead of being duplicated across every microservice. The design is **AP for routing** (a briefly stale route table is acceptable; the gateway continues serving known routes during a control plane partition) and **CP for auth + rate limiting** (an unauthorized request must never be allowed through, and rate limits must be consistently enforced to prevent abuse).

---

## 1-Minute Interview Revision

**Memorize these bullets. They cover 80% of what interviewers want to hear.**

- **Gateway Pipeline (Chain of Responsibility): Route -> Auth -> AuthZ -> Rate Limit -> Circuit Breaker -> Load Balance -> Forward. Each step can short-circuit (401, 403, 429, 503). TraceId flows through every step for end-to-end observability.** The gateway pipeline processes every incoming request through seven sequential stages. Route matching uses glob patterns (`/api/users/**`) sorted by priority (lower = higher priority, first match wins). Authentication validates JWT tokens (Base64 decode of `header.payload.signature`, extract `sub` claim as principal) or API keys (hash lookup from trusted keystore). Authorization checks the authenticated principal's roles against the route's `required-role` metadata. Rate limiting uses a token bucket (bucket starts full at capacity, each request consumes one token, tokens refill at a configured rate per second). Circuit breaker checks if the target service is healthy (CLOSED = allow, OPEN = reject with 503, HALF_OPEN = allow probe requests). Load balancer selects a specific instance. The request is forwarded and the result recorded in the circuit breaker. Any step can short-circuit the pipeline: 404 (no route), 401 (unauthenticated), 403 (unauthorized), 429 (rate limited), 503 (circuit open or no healthy instances). In production: Kong uses a plugin chain, Envoy uses filter chains, Netflix Zuul uses pre/route/post filter phases.

- **Token Bucket Rate Limiting: Bucket starts full at capacity C, each request consumes 1 token, tokens refill at rate R/sec via elapsed-time calculation. Allows controlled bursts up to C then smoothly limits to R req/sec. Better than fixed window (no boundary burst) and sliding window log (O(1) per request vs O(n)).** The token bucket is implemented with a `TokenBucket` inner class tracking `tokens` (double, current available), `maxTokens` (capacity), `refillRate` (tokens/sec), and `lastRefillTime` (Instant). On `tryConsume()`: first call `refill()` which computes `elapsedSeconds = (now - lastRefillTime) / 1000.0`, adds `elapsedSeconds * refillRate` tokens capped at `maxTokens`, then attempts to consume one token. If `tokens >= 1.0`, consume and return `RateLimitResult.allowed(remaining)`. If insufficient, return `RateLimitResult.denied(retryAfterMs)` where retry-after is `1000 / refillRate` ms. Per-key buckets stored in `ConcurrentHashMap<String, TokenBucket>`. In production: distributed rate limiting via Redis + Lua script (atomic read-check-decrement), or Envoy local rate limiter + global rate limit service. Stripe uses per-merchant rate limits. Cloudflare processes 45M+ requests/sec through their rate limiter.

- **Circuit Breaker State Machine: CLOSED -> (failureThreshold failures) -> OPEN -> (openDurationMs cooldown) -> HALF_OPEN -> (successThreshold consecutive successes) -> CLOSED. Prevents cascading failure by failing fast when downstream is unhealthy.** The `CircuitBreakerState` model tracks: `state` (CLOSED/OPEN/HALF_OPEN), `failureCount` (consecutive in CLOSED), `successCount` (consecutive in HALF_OPEN), `lastStateChange` (Instant for cooldown calculation), `failureThreshold` (default 5), `successThreshold` (default 3), `openDurationMs` (default 30s). `recordSuccess()`: in HALF_OPEN increments successCount, transitions to CLOSED when threshold met; in CLOSED resets failureCount. `recordFailure()`: in CLOSED increments failureCount, trips to OPEN when threshold met; in HALF_OPEN immediately trips back to OPEN. `shouldAttemptReset()`: checks `Duration.between(lastStateChange, now).toMillis() > openDurationMs`. The `CircuitBreakerEngine` manages per-service breakers in a `ConcurrentHashMap` with lazy creation via `computeIfAbsent()`. Netflix Hystrix pioneered this pattern; now Resilience4j and Envoy outlier detection are standard. Key insight: without circuit breakers, one failing service consumes all thread pool threads waiting for timeouts, causing cascading failure across the entire system.

- **Load Balancing Strategies: Round-robin (simple, fair, ignores capacity), Weighted (proportional to instance weight, supports heterogeneous fleets and gradual rollouts), Consistent Hash (TreeMap virtual-node ring with FNV-1a, cache-affinity routing, only 1/N keys remap on instance change).** Round-robin uses an `AtomicInteger` counter modulo healthy instance count -- simple and fair but ignores instance capacity. Weighted computes `totalWeight = sum(weights)`, generates `random(0, totalWeight)`, iterates instances accumulating weight until `accumulated > random` -- a weight-5 instance gets 2.5x traffic vs weight-2. Consistent hash builds a `TreeMap<Integer, ServiceInstance>` ring where each instance gets `virtualNodeCount` (default 150) positions hashed via FNV-1a (`hash ^= char; hash *= 0x01000193`). Request path is hashed, `ring.ceilingEntry(hash)` finds the target (wrap to `firstEntry()` if null). Same path always routes to same instance = cache affinity. When an instance is removed, only ~1/N of keys remap. Used by: Memcached, DynamoDB, Cassandra, Envoy ring hash load balancer.

- **Service Mesh Sidecar (Proxy Pattern): Envoy/Linkerd sidecar handles mTLS, circuit breaking, load balancing, retries, and observability -- all without changing application code. Control plane (Istio/Linkerd) configures data plane (Envoy sidecars) via xDS protocol.** The `ServiceMeshService` simulates an Envoy-style sidecar proxy. Flow: (1) mTLS validation -- `TlsEngine.validateConnection()` checks both caller and target are in the trusted service set, enforcing zero-trust (deny by default), (2) circuit breaker check on the target service, (3) load balancer instance selection, (4) forward request with simulated latency, (5) record success/failure in circuit breaker, (6) return response with `X-Mesh-Source`, `X-Mesh-Target`, and `X-Mesh-Instance` headers. mTLS means both sides present certificates -- the sidecar terminates/originates TLS so the application speaks plain HTTP to localhost. In Istio: Pilot (config), Citadel (certs), Galley (validation) form the control plane; Envoy sidecars auto-injected into every pod form the data plane. The key architectural benefit is that network concerns (security, resilience, observability) move from application code to infrastructure.

- **Canary Deployments & Traffic Shaping: Weighted random version selection from TrafficSplit (e.g. 90/10 stable/canary). Header-based routing (X-Canary: true) for targeted testing. Start at 1%, monitor error rate, gradually increase to 100%.** The `CanaryTrafficStrategy` reads version weights from a `TrafficSplit` map, computes `totalWeight`, picks `random(0, totalWeight)`, and iterates accumulating weight until `accumulated > random`. The `HeaderBasedTrafficStrategy` checks for `X-Canary: true` header and forces canary selection, falling back to the last version (stable) for normal requests. Canary reduces blast radius: a bug in the new version affects only 10% of traffic, not 100%. In production: Istio `VirtualService` with weighted route rules, AWS ALB weighted target groups, Kubernetes Argo Rollouts with automated analysis. Shopify canaries every deploy -- for Black Friday (peak traffic), they reduce canary percentage to 1% and extend observation windows. Progressive delivery: 1% -> 5% -> 25% -> 50% -> 100%, with automated rollback if error rate exceeds baseline + 2 sigma.

---

## Class Hierarchy

```
HttpRequest (incoming request, Builder)             HttpResponse (outgoing response, Builder)
  |-- id (UUID)                                       |-- statusCode (int)
  |-- method: GET | POST | PUT | DELETE | PATCH       |-- body (String)
  |-- path ("/api/users/123")                         |-- headers: Map<String,String>
  |-- headers: Map<String,String>                     |-- latencyMs (long)
  |-- queryParams: Map<String,String>                 |-- serviceName (String)
  |-- body (String, nullable for GET)
  |-- clientIp ("192.168.1.100")
  |-- timestamp (Instant)

Route (route definition, Builder)                   ServiceInstance (registered instance)
  |-- id (UUID)                                       |-- id (String, e.g. "user-svc-1")
  |-- pathPattern ("/api/users/**")                   |-- serviceName ("user-service")
  |-- targetService ("user-service")                  |-- host ("10.0.1.10")
  |-- methods: Set<HttpMethod>                        |-- port (8080)
  |-- priority (int, lower = higher)                  |-- healthStatus: HEALTHY | UNHEALTHY | UNKNOWN
  |-- enabled (boolean)                               |-- weight (int, for weighted LB)
  |-- rateLimitPerSecond (int)                        |-- zone ("us-east-1a")
  |-- timeoutMs (long)                                |-- registeredAt (Instant)
  |-- retryCount (int)                                |-- lastHeartbeat (Instant, volatile)
  |-- metadata: Map<String,String>                    |-- metadata: Map<String,String>
  |-- matches(path, method) -> glob matching          |-- isHealthy() -> healthStatus.isUp()
                                                      |-- getAddress() -> "host:port"

CircuitBreakerState (mutable state machine)         ServiceMeshConfig (mesh config, Builder)
  |-- serviceName                                     |-- mtlsEnabled (boolean)
  |-- state: CLOSED | OPEN | HALF_OPEN                |-- sidecarPort (15001)
  |-- failureCount (int, consecutive)                 |-- tracingEnabled (boolean)
  |-- successCount (int, consecutive in HALF_OPEN)    |-- retryPolicy (RetryPolicy)
  |-- lastFailureTime (Instant)                       |-- circuitBreakerEnabled (boolean)
  |-- lastStateChange (Instant)
  |-- failureThreshold (default 5)                  TrafficSplit (deployment versions)
  |-- successThreshold (default 3)                    |-- deploymentId ("order-service-deploy")
  |-- openDurationMs (default 30_000)                 |-- splits: Map<String,Integer>
  |-- recordSuccess() / recordFailure()               |    e.g. {"v1-stable": 90, "v2-canary": 10}
  |-- shouldTrip() / shouldAttemptReset()
  |-- trip() / halfOpen() / reset()

AuthResult (authentication result)                  RateLimitResult (rate limit check result)
  |-- authenticated (boolean)                         |-- allowed (boolean)
  |-- principal (String, e.g. "karan")                |-- remainingTokens (int)
  |-- roles: Set<String>                              |-- retryAfterMs (long)
  |-- errorMessage (String)                           |-- allowed(remaining) / denied(retryAfter)

AuthToken (parsed JWT/API-key)                      RequestContext (per-request state)
  |-- tokenValue                                      |-- request: HttpRequest
  |-- tokenType (JWT / API_KEY)                       |-- route: Route (set after matching)
  |-- principal                                       |-- authResult: AuthResult (set after auth)
  |-- roles: Set<String>

RetryPolicy (mesh retry config)                     GatewayFilter (extensible filter)
  |-- maxRetries (int)                                |-- name (String)
  |-- retryDelayMs (long)                             |-- order (int)
  |-- retryableStatusCodes: Set<Integer>              |-- enabled (boolean)
  |-- defaultPolicy() -> static factory

HealthStatus (enum)                                 HttpMethod (enum)
  |-- HEALTHY, UNHEALTHY, UNKNOWN                     |-- GET, POST, PUT, DELETE, PATCH
  |-- isUp() -> HEALTHY only

CircuitState (enum)
  |-- CLOSED, OPEN, HALF_OPEN

RoutingStrategy (Strategy interface)                LoadBalancingStrategy (Strategy interface)
  |-- PathBasedRoutingStrategy                        |-- RoundRobinLoadBalancer
  |     glob match with "/**" suffix                  |     AtomicInteger counter % instances.size()
  |-- HeaderBasedRoutingStrategy                      |-- WeightedLoadBalancer
  |     match on specific header values               |     weighted random: totalWeight, accumulated
                                                      |-- ConsistentHashLoadBalancer
AuthStrategy (Strategy interface)                   |     TreeMap ring, 150 virtual nodes, FNV-1a
  |-- JwtAuthStrategy
  |     Base64 decode payload, extract "sub" claim  TrafficStrategy (Strategy interface)
  |     Role mapping: Map<principal, Set<roles>>      |-- CanaryTrafficStrategy
  |-- ApiKeyAuthStrategy                              |     weighted random from TrafficSplit
  |     keyStore: Map<apiKey, principalName>           |-- HeaderBasedTrafficStrategy
                                                      |     X-Canary header -> force canary

ServiceRegistry (service discovery engine)          CircuitBreakerEngine (per-service breakers)
  |-- instances: ConcurrentHashMap<svcName,           |-- breakers: ConcurrentHashMap<svcName,
  |    List<ServiceInstance>>                          |    CircuitBreakerState>
  |-- register(instance) / deregister(instanceId)     |-- getOrCreate(svcName) -> computeIfAbsent
  |-- getInstances(svcName) -> healthy only            |-- allowRequest(svcName) -> state check
  |-- heartbeat(instanceId) / markHealthy/Unhealthy   |-- recordSuccess/Failure(svcName)
  |-- evictStale(timeout) -> remove old instances      |-- getState(svcName) -> CircuitState

RateLimiterEngine (token bucket per key)            TlsEngine (mTLS simulation)
  |-- buckets: ConcurrentHashMap<key, TokenBucket>    |-- trustedServices: Set<String>
  |-- configure(key, maxTokens, refillRate)           |-- mtlsEnabled: boolean
  |-- tryConsume(key) -> RateLimitResult              |-- trustService(name) / revokeService(name)
  |-- TokenBucket (inner class):                      |-- validateConnection(caller, target)
  |    tokens, maxTokens, refillRate, lastRefillTime  |     -> both must be trusted + mTLS enabled
  |    refill() -> elapsed * rate, cap at max

RequestRouter (route table engine)                  GatewayService (FACADE -- full pipeline)
  |-- routes: List<Route>                             |-- routingService, authService
  |-- addRoute(route)                                 |-- rateLimitService, circuitBreakerService
  |-- matchRoute(request) -> priority-sorted match    |-- loadBalancerService, serviceMeshService
                                                      |-- handleRequest(request) -> 10-step pipeline
RoutingService (route management)                   |    with traceId propagation
  |-- requestRouter, routingStrategy
  |-- registerRoute(route) / getAllRoutes()          ServiceMeshService (sidecar proxy)
  |-- matchRoute(request) -> Optional<Route>          |-- tlsEngine, circuitBreakerService
                                                      |-- loadBalancerService, meshConfig
AuthService (auth facade)                             |-- proxyRequest(caller, target, request)
  |-- authStrategy (swappable)                        |    -> mTLS + CB + LB + forward
  |-- authenticate(request) -> AuthResult
  |-- authorize(authResult, route) -> boolean        GatewayController (REST facade)
                                                      |-- POST /gateway/request -> handleRequest
RateLimitService (rate limit facade)                  |-- POST /gateway/routes -> registerRoute
  |-- rateLimiterEngine                               |-- POST /gateway/services -> registerInstance
  |-- checkRouteRateLimit(ctx) -> RateLimitResult     |-- GET /gateway/circuit-breakers
                                                      |-- GET /gateway/status
CircuitBreakerService (CB facade)
  |-- circuitBreakerEngine                          GatewayStatsDisplay (console output)
  |-- allowRequest(svcName) -> boolean                |-- printRouteTable()
  |-- recordSuccess/Failure(svcName)                  |-- printServiceInstances(svcName)
  |-- getState(svcName) -> CircuitState               |-- printCircuitBreakerStatus()
  |-- getCircuitSummary() -> Map<svc, state>          |-- printServiceRegistry()
                                                      |-- printStats()
LoadBalancerService (LB facade)
  |-- serviceRegistry, loadBalancingStrategy        RouteRepository / ServiceInstanceRepository
  |-- selectInstance(svcName, req)                    |-- InMemoryRouteRepository
  |    -> Optional<ServiceInstance>                   |-- InMemoryServiceInstanceRepository
  |-- registerInstance(instance)

AppConfig (Composition Root / Factory / Singleton)
  |-- creates repositories (2 InMemory impls: Route, ServiceInstance)
  |-- creates engines (RequestRouter, CircuitBreakerEngine, RateLimiterEngine, ServiceRegistry, TlsEngine)
  |-- creates strategies (RoutingStrategy, LoadBalancingStrategy, AuthStrategy, TrafficStrategy) -- swappable
  |-- creates services (Routing, Auth, RateLimit, CircuitBreaker, LoadBalancer, ServiceMesh, Gateway)
  |-- creates mesh config (ServiceMeshConfig via Builder)
  |-- creates controller + display
  |-- setRoutingStrategy() / setLoadBalancingStrategy() / setAuthStrategy() / setTrafficStrategy()
  |    -> invalidate dependents, rebuild lazily
```

---

## Key Components

| Component | Role |
|-----------|------|
| `HttpRequest` | Incoming HTTP request flowing through the gateway. Created via Builder pattern with required `method` and `path`. Carries headers (for auth tokens, canary flags), query params, body, clientIp, and timestamp. Immutable after construction -- headers and queryParams wrapped in `Collections.unmodifiableMap()`. |
| `HttpResponse` | Outgoing HTTP response from the gateway. Created via Builder with required `statusCode`. Carries body, headers (X-Trace-Id, X-Gateway-Service, X-Gateway-Instance), latencyMs, and serviceName. Error responses generated by `GatewayService.buildErrorResponse()` include JSON error body and traceId header. |
| `Route` | API route definition mapping a path pattern to a target service. Created via Builder with required `pathPattern` and `targetService`. `matches(path, method)` supports exact match and `/**` wildcard suffix matching. Routes are priority-sorted (lower = higher priority). Carries per-route rate limit, timeout, retry count, and metadata (e.g. `required-role`). |
| `ServiceInstance` | A running service instance in the registry. Tracks host, port, weight (for weighted LB), zone (for locality-aware routing), and health status (`volatile HealthStatus` for thread-safe reads). `isHealthy()` delegates to `HealthStatus.isUp()`. `updateHeartbeat()` stamps `lastHeartbeat` and sets status to HEALTHY. |
| `CircuitBreakerState` | Mutable state machine for a single service's circuit breaker. Three states: CLOSED (normal), OPEN (all requests rejected), HALF_OPEN (probe requests allowed). `recordSuccess()` in HALF_OPEN increments successCount, transitions to CLOSED at threshold. `recordFailure()` in CLOSED increments failureCount, trips to OPEN at threshold; in HALF_OPEN, immediately trips back to OPEN. `shouldAttemptReset()` checks if cooldown elapsed since `lastStateChange`. |
| `ServiceMeshConfig` | Configuration for the sidecar proxy. Built via Builder with defaults: mTLS enabled, sidecar port 15001, tracing enabled, default retry policy, circuit breaker enabled. Immutable after construction. Simulates Istio's control plane configuration that is pushed to Envoy sidecars via xDS protocol. |
| `TrafficSplit` | Deployment version weights for canary routing. Maps version names to integer weights (e.g. `{"v1-stable": 90, "v2-canary": 10}`). Used by `CanaryTrafficStrategy` for weighted random selection and by `HeaderBasedTrafficStrategy` for override routing. |
| `RateLimiterEngine` | Token bucket rate limiter managing per-key buckets in a `ConcurrentHashMap`. `configure(key, maxTokens, refillRate)` creates a bucket. `tryConsume(key)` refills based on elapsed time, then attempts to consume one token. `TokenBucket` inner class tracks `tokens` (double), `maxTokens`, `refillRate`, and `lastRefillTime`. Thread-safe via `synchronized(bucket)` blocks. |
| `CircuitBreakerEngine` | Manages per-service circuit breakers in a `ConcurrentHashMap`. `getOrCreate(serviceName)` uses `computeIfAbsent()` with defaults (5 failure threshold, 3 success threshold, 30s cooldown). `allowRequest()` checks state: CLOSED always allows, OPEN checks cooldown and transitions to HALF_OPEN if elapsed, HALF_OPEN allows. `recordSuccess/Failure()` delegate to the `CircuitBreakerState` state machine. |
| `ServiceRegistry` | In-memory service discovery registry. `ConcurrentHashMap<serviceName, List<ServiceInstance>>`. `register()` adds instances, `getInstances()` returns only HEALTHY instances, `heartbeat()` updates timestamps, `markHealthy/Unhealthy()` toggles status, `evictStale(Duration)` removes instances whose `lastHeartbeat` is older than the cutoff. |
| `TlsEngine` | Simulates mutual TLS for zero-trust service-to-service communication. Maintains a `Set<String>` of trusted service names and a global `mtlsEnabled` toggle. `validateConnection(caller, target)` requires both services to be trusted AND mTLS to be enabled. Untrusted callers receive 403 Forbidden. |
| `RequestRouter` | Route table engine that stores routes and matches incoming requests. `matchRoute()` iterates priority-sorted routes, calling `route.matches(path, method)` for each. First match wins. Returns `Optional<Route>`. |
| `GatewayService` | **Facade Pattern (GoF)** -- single entry point orchestrating the full 10-step pipeline. `handleRequest()` creates a RequestContext with traceId, then sequentially: route match, authenticate, authorize, rate limit, circuit breaker check, load balance, forward (simulated with random latency and 5% failure rate), record result in circuit breaker, and build response with traceId header. Any step can short-circuit with an error response (404, 401, 403, 429, 503). |
| `ServiceMeshService` | **Proxy Pattern (GoF)** -- sidecar proxy for service-to-service communication. `proxyRequest(caller, target, request)` runs the mesh pipeline: mTLS validation, circuit breaker check, load balancer instance selection, forward with simulated latency, record success/failure, and return response with mesh headers (X-Mesh-Source, X-Mesh-Target, X-Mesh-Instance). |
| `RoutingService` | Route management service wrapping `RequestRouter` and `RoutingStrategy`. `registerRoute()` adds routes. `matchRoute()` delegates to the router. `getAllRoutes()` returns the full route table. |
| `AuthService` | Authentication and authorization service. `authenticate(request)` delegates to the pluggable `AuthStrategy` (JWT or API key). `authorize(authResult, route)` checks if the authenticated principal has the `required-role` specified in route metadata. Returns true if no role required or if principal's roles contain the required role. |
| `LoadBalancerService` | Load balancing service wrapping `ServiceRegistry` and `LoadBalancingStrategy`. `selectInstance(serviceName, request)` fetches healthy instances from the registry, then delegates to the strategy (round-robin, weighted, or consistent hash). |
| `GatewayController` | REST-like controller dispatching simulated HTTP requests to the gateway pipeline. Endpoints: `POST /gateway/request` (full pipeline), `POST /gateway/routes` (register route), `POST /gateway/services` (register instance), `GET /gateway/circuit-breakers` (breaker status), `GET /gateway/status` (operational summary). |
| `GatewayStatsDisplay` | Console output helper printing formatted tables for route table, service instances, circuit breaker status, service registry, and gateway summary statistics. Uses column-aligned `printf` formatting with unicode separators. |
| `AppConfig` | **Factory Pattern + Composition Root + Singleton** -- lazily creates and wires all 25+ objects (repositories, engines, strategies, services, mesh config, controller, display). Strategy setters (`setLoadBalancingStrategy()`, etc.) invalidate dependent objects for automatic re-creation on next access. The wiring graph flows: Repositories -> Engines -> Strategies -> Services -> GatewayService (Facade) -> Controller -> Display. |

---

## Key Tradeoffs

| Decision | Option A | Option B | This Design |
|----------|----------|----------|-------------|
| Gateway architecture | **Centralized gateway (single entry point)** -- all external traffic flows through one cluster of gateway instances | **Distributed gateways** -- each service exposes its own API with its own auth/rate-limiting | **Centralized gateway** -- single enforcement point for cross-cutting concerns (auth, rate limiting, observability). Avoids duplicating security logic across 50+ services. Tradeoff: the gateway is a single point of failure and a potential bottleneck. Mitigate with horizontal scaling (Kong/Envoy scale to millions of RPS) and health-checked load balancer in front. Netflix Zuul handles 2B+ requests/day as a centralized gateway. AWS API Gateway is inherently centralized per-region. |
| Rate limiting algorithm | **Fixed window** (simple, but allows 2x burst at window boundary) | **Token bucket** (allows controlled bursts, smooth rate limiting) | **Token bucket** -- allows bursts up to bucket capacity (accommodating legitimate traffic spikes) then smoothly limits to refill rate. Fixed window counter allows double the rate at the boundary (99 requests at second 59, 100 more at second 60 = 199 in 2 seconds). Sliding window log is accurate but O(n) memory per client. Token bucket is O(1) per request with elegant burst handling. In production: Redis + Lua atomic script for distributed token bucket (Stripe pattern). Envoy uses both local and global rate limiting. Cloudflare's edge rate limiter processes 45M+ requests/sec using a distributed token bucket. |
| Circuit breaker implementation | **Thread pool isolation** (Hystrix-style, each service gets its own thread pool, bulkhead pattern) | **State machine with failure counting** (simpler, lower overhead) | **State machine** -- three states (CLOSED/OPEN/HALF_OPEN) with configurable thresholds. Thread pool isolation adds significant overhead (thread context switching, pool sizing complexity) and is harder to reason about. The state machine approach counts consecutive failures and uses a cooldown timer. In production: Resilience4j uses this model with configurable failure rate calculation (count-based or time-based sliding window). Envoy's outlier detection ejects unhealthy hosts from the load balancer pool. Tradeoff: state machine does not provide bulkhead isolation -- combine with separate thread pools or semaphore bulkheads for defense in depth. |
| Load balancing strategy | **Round-robin** (simple, fair, ignores instance capacity) | **Consistent hashing** (cache-affinity, deterministic, complex) | **All three via Strategy pattern** -- round-robin for homogeneous fleets, weighted for heterogeneous instances and gradual rollouts, consistent hash for cache-affinity workloads. Round-robin is the default (Nginx, kube-proxy, ALB) but wastes capacity when instances are unequal. Weighted is essential for canary deployments (new version starts at weight=1). Consistent hash maximizes cache hit rates by routing the same key to the same instance, but requires virtual nodes (150/instance) for even distribution. Strategy pattern allows runtime switching without code changes. |
| Authentication model | **JWT (stateless, self-contained, no backend lookup)** | **API key (simple, requires backend lookup/cache)** | **Both via Strategy pattern** -- JWT for user-facing requests (stateless verification with RS256 public key, claims carry user identity and roles, short-lived with refresh tokens), API keys for server-to-server (simpler integration, hashed storage, per-key rate limits). JWT advantages: no session store, horizontal scaling without sticky sessions. API key advantages: simpler for partners/integrations, revocable per-key. In production: gateway validates JWT signature and extracts claims, then passes `X-User-Id` header to upstream services (services never see raw tokens). Stripe uses API keys with environment-identifying prefixes (`sk_live_`, `pk_test_`). |
| Service mesh adoption | **Library-based (Hystrix, Resilience4j)** -- embed resilience in application code | **Sidecar proxy (Envoy/Linkerd)** -- infrastructure handles cross-cutting concerns | **Sidecar proxy** -- moves networking concerns (mTLS, circuit breaking, retries, observability) from application code to infrastructure. Application speaks plain HTTP to localhost; the sidecar handles TLS termination, retry logic, and metrics collection. Library approach requires every service in every language to import and configure the library (Java has Resilience4j, but what about Python, Go, Node?). Sidecar is language-agnostic. Tradeoff: sidecar adds ~1ms latency per hop and increases resource consumption (each pod runs an extra container). Uber runs 4000+ microservices with Envoy sidecars. |
| Canary deployment strategy | **Blue-green** (instant 100% traffic switch, simple rollback) | **Canary (gradual traffic shifting with monitoring)** | **Canary with both weighted and header-based routing** -- gradual rollout reduces blast radius (1% -> 5% -> 25% -> 100%). Header-based routing (`X-Canary: true`) enables targeted testing by QA and dogfooding. Blue-green is simpler but has 100% blast radius on failure. Canary allows automated rollback based on error rate metrics. In production: Istio VirtualService with weighted routing, Argo Rollouts with automated analysis, AWS ALB weighted target groups. Shopify canaries every deploy to protect Black Friday traffic -- they reduce canary percentage to 1% during peak and extend observation windows. |

---

## Design Patterns

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** | `RoutingStrategy`: PathBased vs HeaderBased | Swap request routing algorithm. Path-based matches glob patterns (`/api/users/**`), header-based routes on specific header values. Both implement `matchRoute(List<Route>, HttpRequest)`. Enables runtime routing strategy changes without modifying the pipeline. |
| **Strategy** | `LoadBalancingStrategy`: RoundRobin vs Weighted vs ConsistentHash | Swap load balancing algorithm. Round-robin for homogeneous fleets, weighted for heterogeneous/canary, consistent hash for cache-affinity. All implement `selectInstance(List<ServiceInstance>, HttpRequest)`. Switch at runtime via `config.setLoadBalancingStrategy()`. |
| **Strategy** | `AuthStrategy`: JWT vs ApiKey | Swap authentication mechanism. JWT decodes Base64 payload and extracts `sub` claim with role mapping. API key does hash lookup from trusted keystore. Both implement `authenticate(HttpRequest)`. Enables multi-auth support (JWT for users, API key for partners). |
| **Strategy** | `TrafficStrategy`: Canary vs HeaderBased | Swap traffic shaping algorithm. Canary uses weighted random selection from TrafficSplit. Header-based checks `X-Canary: true` for forced routing. Both implement `selectVersion(HttpRequest, TrafficSplit)`. |
| **Builder** | `HttpRequest.Builder`, `Route.Builder`, `ServiceMeshConfig.Builder` | Complex objects with 6-10 fields. Builder avoids telescoping constructors, enforces required fields via constructor (method+path for request, pathPattern+targetService for route), defaults optional ones (clientIp = "127.0.0.1", priority = 100). Fluent API: `new Route.Builder("/api/users/**", "user-service").rateLimitPerSecond(100).priority(10).build()`. |
| **Factory** | `AppConfig` as Composition Root | Lazily creates and wires all 25+ dependencies. Strategy setters invalidate dependent objects for re-creation on next access (e.g. `setLoadBalancingStrategy()` nulls out `loadBalancerService`, `serviceMeshService`, `gatewayService`, `gatewayController`). Single entry point for demo and tests. No DI framework needed. |
| **Repository** | `RouteRepository`, `ServiceInstanceRepository` (2 interfaces + 2 InMemory impls) | Abstract data access behind interfaces. Swap InMemory for Redis/PostgreSQL without touching service logic. Each interface has standard CRUD plus domain-specific queries. |
| **Facade** | `GatewayService` orchestrates 6 sub-services | Single unified API. The 10-step pipeline (`route -> auth -> authz -> rate limit -> circuit breaker -> load balance -> forward -> record -> respond`) is hidden behind one `handleRequest(HttpRequest)` method. Controllers and demos interact with one class instead of six. |
| **State** | `CircuitBreakerState` (CLOSED/OPEN/HALF_OPEN) | The circuit breaker is a textbook GoF State pattern. Each state has different behavior: CLOSED counts failures, OPEN rejects requests, HALF_OPEN allows probes. State transitions are triggered by `recordSuccess()`, `recordFailure()`, and cooldown timeout. The state machine prevents cascading failure by failing fast. |
| **Chain of Responsibility** | Gateway pipeline (route -> auth -> rate limit -> CB -> LB -> forward) | Each step in the pipeline can short-circuit with an error response (404, 401, 403, 429, 503). The request flows through successive handlers until one rejects it or it reaches the forwarding step. In production, Kong and Envoy implement this as plugin/filter chains where each filter can modify or reject the request. |
| **Proxy** | `ServiceMeshService` as sidecar proxy | The Proxy pattern (GoF) applied to service-to-service communication. The sidecar proxy interposes on every inter-service call, transparently adding mTLS, circuit breaking, load balancing, and observability. The calling service thinks it is talking directly to the target, but the proxy handles all cross-cutting concerns. This is the fundamental pattern behind Envoy, Linkerd, and every service mesh. |
| **Singleton** | `AppConfig` lazy initialization | Each getter creates the instance once and caches it. Subsequent calls return the cached instance. Strategy setters clear dependents to force re-creation. Thread-unsafe by design (single-threaded demo). In production, use DI container (Spring) for lifecycle management. |

---

## Real-World Use Cases & Industry Applications

### 1. Netflix Zuul / Envoy -- Routing 2B+ Requests/Day
**Problem:** Netflix operates 1000+ microservices serving 230M+ subscribers across 190 countries. Every API request (play a movie, browse catalog, rate a title) must be routed to the correct backend service with authentication, rate limiting, and observability -- all at massive scale (2B+ requests/day peak).
**How this system solves it:** The gateway pipeline handles this exact flow. Route matching uses path-based glob patterns (`/api/v1/titles/**` -> title-service, `/api/v1/profiles/**` -> profile-service) with priority sorting so versioned routes (`/api/v2/titles/**`) take precedence. Rate limiting prevents any single client or partner from overwhelming the system. Circuit breakers isolate failures -- if the recommendation service is slow, the gateway fast-fails those requests while user-service and title-service continue working. Load balancing distributes across instances in multiple availability zones. Netflix migrated from Zuul 1 (blocking, servlet-based) to Zuul 2 (async, Netty-based) and now increasingly uses Envoy for gRPC and HTTP/2 support. The key architectural insight is that the gateway is a choke point where cross-cutting concerns are enforced once rather than duplicated in every service.
**Production numbers:** Netflix's gateway processes 2B+ requests/day. Zuul 2 handles 100K+ RPS per instance. Envoy at Lyft (its origin) handles 3M+ RPS across 1000+ services. At this scale, the gateway pipeline adds <1ms overhead per request.

### 2. Stripe API Gateway -- Versioned API, Idempotency, Per-Merchant Rate Limiting
**Problem:** Stripe processes billions of dollars in payments. Their API must handle: API versioning (merchants pin to a specific API version), idempotency (a retry must not double-charge a customer), per-merchant rate limiting (a misbehaving integration should not affect other merchants), and authentication (every request carries an API key with environment identification: `sk_live_`, `sk_test_`).
**How this system solves it:** The API key authentication strategy (`ApiKeyAuthStrategy`) maps keys to principals (merchants). Rate limiting uses per-key token buckets -- each merchant gets an independent bucket configured with their plan's limits (e.g., 100 req/sec for standard, 1000 req/sec for enterprise). The route metadata system carries versioning information: requests include an `API-Version` header, and the gateway routes to the correct version handler. Circuit breakers protect downstream payment processors (banks, card networks) -- if Visa's API is degraded, the circuit opens and the gateway returns 503 with a retry-after header instead of queuing thousands of hanging connections. Idempotency is implemented at the route level: `POST /v1/charges` with `Idempotency-Key` header deduplicates within a 24-hour window.
**Production numbers:** Stripe handles 100s of millions of API calls/day. Their per-merchant rate limiting prevents a single buggy integration from consuming all capacity. Stripe's API versioning supports 100+ versions simultaneously. Key prefixes (`sk_live_`, `pk_test_`) immediately identify the environment, preventing accidental live charges during testing.

### 3. Uber's Service Mesh -- 4000+ Microservices with mTLS and Circuit Breakers
**Problem:** Uber's platform runs 4000+ microservices handling 100M+ trips/day. Every service-to-service call must be authenticated (zero-trust -- no service trusts any other by default), encrypted (mTLS), and protected against cascading failure (circuit breakers). Doing this in application code across 4000 services in multiple languages (Go, Java, Python, Node) is impossible to maintain.
**How this system solves it:** The service mesh sidecar (`ServiceMeshService`) handles all of this at the infrastructure layer. mTLS validation (`TlsEngine.validateConnection()`) ensures both caller and target are in the trusted set -- an untrusted service gets 403 Forbidden without the target ever seeing the request. Circuit breakers per-service prevent cascading failure: if the pricing service is slow, the circuit opens and ride-matching falls back to cached prices instead of blocking. Load balancing across instances within the mesh ensures no single instance is overwhelmed. The critical insight is language-agnosticism: the sidecar runs as a separate process (Envoy) that intercepts all network traffic, so Go services, Java services, and Python services all get the same security and resilience guarantees without any library imports.
**Production numbers:** Uber runs 4000+ microservices. Their service mesh handles billions of inter-service RPCs per day. mTLS certificate rotation happens automatically via the control plane (similar to Istio's Citadel). Circuit breaker configuration is centralized -- the platform team sets thresholds, and all 4000 services inherit them via the sidecar.

### 4. Shopify Edge Routing -- Canary Deploys Protecting Black Friday Traffic
**Problem:** Shopify processes $7.5B+ in Black Friday/Cyber Monday sales. A single bad deployment during peak traffic could take down checkout for millions of merchants. Shopify deploys hundreds of times per day during normal operations -- how do you maintain deployment velocity while protecting peak traffic?
**How this system solves it:** Canary traffic strategy (`CanaryTrafficStrategy`) with weighted random version selection. During normal operations: 95/5 stable/canary split with 5-minute observation window. During Black Friday: reduce canary to 1% with 30-minute observation windows. Header-based routing (`HeaderBasedTrafficStrategy`) enables QA to test the canary version directly via `X-Canary: true` header without affecting real traffic. The circuit breaker monitors error rates for both versions independently -- if the canary version trips its circuit breaker, all traffic automatically routes to the stable version (instant rollback). Load balancing with weighted instances allows gradual scale-up: canary instances start with weight=1 while stable instances have weight=10, giving the canary proportionally less traffic.
**Production numbers:** Shopify processes 10,000+ requests/second during Black Friday peaks. Their canary deployment system monitors: error rate (must be within 0.1% of baseline), latency P99 (must be within 10% of baseline), and checkout conversion rate (must not drop). Automated rollback triggers within 60 seconds of threshold violation. During BFCM 2023, Shopify served $9.3B+ in sales with zero deployment-related outages.

### 5. AWS API Gateway -- Multi-Tenant Throttling, Auth, Request Transformation
**Problem:** AWS API Gateway serves millions of AWS customers, each with their own APIs, authentication schemes, rate limits, and request/response transformations. A single misbehaving tenant must not affect others. The system must handle authentication (IAM, Cognito, Lambda authorizers), per-customer throttling, request validation, and protocol translation (REST to Lambda, HTTP to SQS).
**How this system solves it:** Per-route rate limiting matches AWS's per-API, per-stage, per-method throttling model. The route metadata system carries transformation rules. Authentication via strategy pattern maps to AWS's pluggable authorizer model (IAM, Cognito User Pools, Lambda authorizers). Circuit breakers protect Lambda functions and backend HTTP integrations from overload. The multi-tenant architecture uses independent rate limit buckets per customer (API key maps to customer, customer maps to throttle configuration). Request context carries tenant identity through the entire pipeline for observability and billing.
**Production numbers:** AWS API Gateway handles millions of APIs across millions of accounts. Default throttle: 10,000 requests/second per account (burst) with 5,000 steady-state. Per-API and per-stage overrides available. WebSocket support handles 500K concurrent connections. The gateway adds <5ms latency for REST API requests. AWS uses this internally for service-to-service communication across their own platform.

### 6. Cloudflare -- Global Rate Limiting, DDoS Protection, Edge Routing
**Problem:** Cloudflare sits in front of 20%+ of the internet's web traffic. They must perform rate limiting, DDoS mitigation, authentication (WAF rules), and edge routing at global scale -- 45M+ HTTP requests per second across 300+ points of presence. A rate limiting decision must be made in <1ms at the edge, not via a round-trip to a central data store.
**How this system solves it:** Token bucket rate limiting (`RateLimiterEngine`) at the edge -- each PoP maintains local token buckets for per-IP and per-API-key limiting. The circuit breaker pattern protects origin servers: if Cloudflare detects that an origin is unhealthy (5xx responses exceed threshold), it serves cached content or custom error pages instead of forwarding requests that will fail. Route matching happens at the edge: DNS resolution points to the nearest Cloudflare PoP, which routes requests to the correct origin based on hostname and path patterns. Rate limiting at Cloudflare scale requires distributed counting -- they use a combination of local per-PoP counters (fast, eventually consistent) and global counters synchronized via their distributed key-value store (strong consistency for abuse prevention).
**Production numbers:** Cloudflare processes 45M+ HTTP requests/second globally. Their rate limiting system blocks 136B+ threats per day. Edge latency: <1ms for cached responses, <5ms for rate limit decisions. Their DDoS mitigation has handled 71M request/second attacks (largest on record). Rate limiting uses a sliding window algorithm with sub-second precision across 300+ PoPs.

---

## How to Run

```bash
cd /Users/kanojik/Documents/Karan/Autodesk_AI_Development/systemDesign
./gradlew :19-api-gateway-service-mesh:run
```

---

## Demo Output Preview

```
======================================================================
   API GATEWAY & SERVICE MESH -- System Design Demo
   Staff Engineer Interview Prep: Routing, Resilience, Security
======================================================================

[SETUP] Registered 6 service instances, 4 routes, mTLS configured

======================================================================
  DEMO 1: Request Routing (Path-Based Matching)
======================================================================
[DEMO] Route matching:
  GET /api/users/123  -> user-service
  POST /api/orders/new -> order-service
  GET /health          -> health-check
  DELETE /api/unknown   -> NO MATCH (404)

  KEY INSIGHT: Path-based routing with ** wildcard matches any suffix.
  Routes sorted by priority (lower=higher). First match wins.
  In production: Kong uses radix tree, Envoy uses route table with
  prefix/exact/regex matching. Netflix Zuul uses filter chains.

======================================================================
  DEMO 2: Rate Limiting (Token Bucket Algorithm)
======================================================================
[DEMO] Token bucket: capacity=5, refill=5/sec
[DEMO] Sending 8 rapid requests:
  Request 1: ALLOWED (remaining: 4)
  Request 2: ALLOWED (remaining: 3)
  Request 3: ALLOWED (remaining: 2)
  Request 4: ALLOWED (remaining: 1)
  Request 5: ALLOWED (remaining: 0)
  Request 6: DENIED (429) (remaining: 0)
  Request 7: DENIED (429) (remaining: 0)
  Request 8: DENIED (429) (remaining: 0)

  KEY INSIGHT: Token bucket allows bursts (up to bucket capacity)
  then rate-limits to refill rate. Better than fixed window which
  allows 2x burst at window boundary. In production: Redis + Lua
  script for distributed rate limiting (Stripe, Cloudflare pattern).

======================================================================
  DEMO 3: JWT Authentication
======================================================================
[DEMO] Valid JWT: authenticated=true, principal=karan, roles=[admin, user]
[DEMO] No token: authenticated=false, error=Missing Authorization header
[DEMO] Invalid JWT: authenticated=false, error=Invalid JWT format

  KEY INSIGHT: Gateway validates JWT signature and extracts claims.
  Services never see raw tokens -- gateway passes X-User-Id header.
  In production: RS256 asymmetric signing (gateway has public key only).

======================================================================
  DEMO 5: Circuit Breaker State Machine
======================================================================
[DEMO] Initial state: CLOSED
[DEMO] Simulating 5 consecutive failures...
  Failure 1: state=CLOSED
  Failure 2: state=CLOSED
  Failure 3: state=CLOSED
  Failure 4: state=CLOSED
  Failure 5: state=OPEN
[DEMO] Circuit is OPEN -- requests rejected:
  allowRequest: false
[DEMO] Manually moved to HALF_OPEN (simulating cooldown expiry)
  allowRequest: true
[DEMO] Simulating 3 consecutive successes...
  Success 1: state=HALF_OPEN
  Success 2: state=HALF_OPEN
  Success 3: state=CLOSED

  KEY INSIGHT: Circuit breaker prevents cascading failure.
  CLOSED -> (5 failures) -> OPEN -> (30s cooldown) -> HALF_OPEN ->
  (3 successes) -> CLOSED.

======================================================================
  DEMO 8: Consistent Hash Load Balancing
======================================================================
[DEMO] Same path always routes to same instance (cache affinity):
  /api/users/100 -> 10.0.1.10:8080
  /api/users/200 -> 10.0.1.12:8080
  /api/users/100 -> 10.0.1.10:8080
  /api/users/300 -> 10.0.1.11:8080
  /api/users/200 -> 10.0.1.12:8080
  /api/users/100 -> 10.0.1.10:8080

  KEY INSIGHT: Consistent hashing provides cache affinity -- same
  request always hits same instance, maximizing local cache hit rate.
  Virtual nodes (150/instance) ensure even distribution.

======================================================================
  DEMO 9: Canary Deployment (Traffic Splitting)
======================================================================
[DEMO] Canary split (90/10) over 100 requests:
  v1-stable       : 91 requests
  v2-canary       : 9 requests

[DEMO] Header-based routing:
  Normal request  -> v1-stable
  X-Canary: true  -> v2-canary

  KEY INSIGHT: Canary deployments reduce blast radius. Start at 1%,
  monitor error rate, gradually increase to 100%.

======================================================================
  DEMO 10: Service Mesh Sidecar Proxy (mTLS)
======================================================================
[DEMO] Sidecar proxy: order-service -> user-service (mTLS)
  Response: 200 (latency=23ms)

[DEMO] Sidecar proxy: unknown-service -> user-service (untrusted)
  Response: 403 -- mTLS validation failed

  KEY INSIGHT: Service mesh sidecar (Envoy/Linkerd) handles:
  mTLS (zero-trust), circuit breaking, load balancing, retries,
  and observability -- all without changing application code.

======================================================================
  DEMO 12: Full Gateway Pipeline (End-to-End)
======================================================================
[GATEWAY] Processing request: GET /api/users/123 (traceId=a1b2c3d4)
[GATEWAY] Step 1: Created RequestContext
[GATEWAY] Step 2: Route matched -- '/api/users/**' -> 'user-service'
[GATEWAY] Step 3: Authentication passed -- principal='karan'
[GATEWAY] Step 4: Authorization granted
[GATEWAY] Step 5: Rate limit check passed (remaining=99)
[GATEWAY] Step 6: Circuit breaker allows request to 'user-service'
[GATEWAY] Step 7: Selected instance 10.0.1.10:8080
[GATEWAY] Step 8: Forwarded -- latency=42ms, success=true
[GATEWAY] Step 9: Recorded SUCCESS for 'user-service'
[GATEWAY] Step 10: Response -- status=200, latency=42ms

  KEY INSIGHT: The gateway pipeline is a Chain of Responsibility:
  Route -> Auth -> AuthZ -> Rate Limit -> Circuit Breaker -> LB -> Forward.
  Each step can short-circuit (401, 403, 429, 503). TraceId flows
  through every step for end-to-end observability.

======================================================================
  DESIGN SUMMARY -- API Gateway & Service Mesh
======================================================================

  Gateway Pipeline (Chain of Responsibility):
    1. Route Matching (path/header-based, priority sorted)
    2. Authentication (JWT / API Key)
    3. Authorization (role-based, per-route)
    4. Rate Limiting (token bucket, per-client/per-route)
    5. Circuit Breaker (CLOSED/OPEN/HALF_OPEN state machine)
    6. Load Balancing (round-robin/weighted/consistent hash)
    7. Forward to Service Instance

  Service Mesh (Sidecar Proxy):
    * mTLS -- zero-trust service-to-service encryption
    * Circuit Breaker -- per-service failure isolation
    * Load Balancing -- instance selection within mesh
    * Traffic Shaping -- canary, blue-green, A/B routing

  Design Patterns (GoF):
    * Strategy -- routing, load balancing, auth, traffic shaping
    * Builder -- HttpRequest, Route, ServiceMeshConfig
    * Factory -- AppConfig composition root
    * Repository -- data access (RouteRepo, InstanceRepo)
    * Facade -- GatewayService orchestrates full pipeline
    * State -- CircuitBreaker (CLOSED/OPEN/HALF_OPEN)
    * Chain of Responsibility -- gateway filter pipeline
    * Proxy -- sidecar proxy pattern (ServiceMeshService)
    * Singleton -- AppConfig lazy initialization
```
