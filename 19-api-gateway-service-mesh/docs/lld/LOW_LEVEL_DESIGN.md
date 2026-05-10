# Low-Level Design: API Gateway & Service Mesh

> **Difficulty**: HARD | **Target**: Senior Java Developer (7+ years) | **Focus**: Request Routing, Rate Limiting, Circuit Breaking, Load Balancing, mTLS, Traffic Shaping, Service Discovery
> This is the infrastructure-layer interview question. It tests your understanding of API
> gateway pipelines, service mesh sidecar proxies, token bucket rate limiting, circuit
> breaker state machines, consistent hash load balancing, mutual TLS, canary deployments,
> and service discovery -- all with concurrency-safe design and pluggable strategy interfaces.

---

## Table of Contents

1. [Core Modules Overview](#1-core-modules-overview)
2. [Package Structure](#2-package-structure)
3. [Class Diagram](#3-class-diagram)
4. [Entity Design](#4-entity-design)
5. [Interface Contracts](#5-interface-contracts)
6. [Strategy Implementations](#6-strategy-implementations)
7. [Engine Design](#7-engine-design)
8. [Service Layer Design](#8-service-layer-design)
9. [Concurrency Considerations](#9-concurrency-considerations)
10. [SOLID Principles Applied](#10-solid-principles-applied)
11. [Sample Workflows](#11-sample-workflows)
12. [Design Patterns Used](#12-design-patterns-used)
13. [Extensibility Points](#13-extensibility-points)

---

## 1. Core Modules Overview

| Module | Package | Responsibility |
|--------|---------|----------------|
| **Model** | `model/` | Domain entities: HttpRequest (Builder, id, method, path, headers, queryParams, body, clientIp, timestamp), HttpResponse (Builder, statusCode, headers, body, latencyMs, serviceName, static factories ok/error/redirect), HttpMethod (enum: GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS), Route (Builder, id, pathPattern, targetService, methods, priority, enabled, rateLimitPerSecond, timeoutMs, retryCount, metadata, glob matching), ServiceInstance (id, serviceName, host, port, healthStatus volatile, weight, zone, registeredAt, lastHeartbeat volatile, metadata), RequestContext (mutable pipeline context: request, route, selectedInstance, authResult, rateLimitResult, startTime, traceId), AuthToken (tokenId/jti, subject/sub, roles, issuedAt, expiresAt, claims, expiry check), AuthResult (static factories: success/unauthorized/forbidden, authenticated, authorized, principal, roles, errorMessage), CircuitBreakerState (mutable state machine: serviceName, state, failureCount, successCount, thresholds, openDurationMs, recordSuccess/recordFailure/trip/reset/halfOpen), CircuitState (enum: CLOSED, OPEN, HALF_OPEN), HealthStatus (enum: HEALTHY, UNHEALTHY, DEGRADED, UNKNOWN with isUp()), RateLimitResult (static factories: allowed/denied, remainingTokens, retryAfterMs), RetryPolicy (maxRetries, initialDelayMs, maxDelayMs, retryableStatusCodes, exponential backoff, defaultPolicy), ServiceMeshConfig (Builder, mtlsEnabled, sidecarPort, tracingEnabled, retryPolicy, circuitBreakerEnabled), TrafficSplit (name, version-to-weight map, weighted random selectVersion()), GatewayFilter (functional interface: filter(RequestContext) returns Optional<HttpResponse> for short-circuit). |
| **Strategy (Routing)** | `strategy/routing/` | Pluggable route selection: RoutingStrategy interface with PathBasedRoutingStrategy (specificity scoring: exact=100, wildcard=50-N, catch-all=0; tiebreak by path length) and HeaderBasedRoutingStrategy (match header value against route metadata "required-header-value", fallback to first route). Strategy pattern -- swap routing algorithm without touching services. |
| **Strategy (Load Balancing)** | `strategy/loadbalancing/` | Pluggable instance selection: LoadBalancingStrategy interface with RoundRobinLoadBalancer (AtomicInteger counter mod healthy count -- thread-safe), WeightedLoadBalancer (weighted random: totalWeight, accumulate, ThreadLocalRandom), ConsistentHashLoadBalancer (virtual-node TreeMap ring, FNV-1a hash, 150 vnodes/instance, ceiling entry with wrap-around). |
| **Strategy (Auth)** | `strategy/auth/` | Pluggable authentication: AuthStrategy interface with JwtAuthStrategy (Bearer token validation, 3-part structure check, Base64 payload decode, subject extraction, role lookup from configurable roleMap) and ApiKeyAuthStrategy (X-API-Key header validation against known key-to-client map, returns API_CLIENT role). |
| **Strategy (Traffic)** | `strategy/traffic/` | Pluggable traffic shaping: TrafficStrategy interface with CanaryTrafficStrategy (weighted random selection across version splits, same algorithm as WeightedLoadBalancer) and HeaderBasedTrafficStrategy (X-Canary header opt-in: true routes to lowest-weight/canary version, absent routes to highest-weight/stable version). |
| **Engine** | `engine/` | Core infrastructure engines: RequestRouter (path matching with ** and * wildcards, priority-sorted route list, exact/prefix/regex matching), CircuitBreakerEngine (per-service ConcurrentHashMap of CircuitBreakerState, lazy creation with defaults, allowRequest/recordSuccess/recordFailure state transitions), RateLimiterEngine (per-key token bucket with inner TokenBucket class, synchronized refill based on elapsed time, configurable maxTokens and refillRate), ServiceRegistry (in-memory service discovery with ConcurrentHashMap, register/deregister/heartbeat/markHealthy/markUnhealthy/evictStale), TlsEngine (mutual TLS simulation with trusted service set, enableMtls/disableMtls toggle, validateConnection checks both caller and target trust). |
| **Service** | `service/` | Business logic: GatewayService (Facade -- 10-step pipeline: context, route, auth, authz, rate limit, circuit breaker, load balance, forward, record, respond), RoutingService (delegates to RequestRouter + RoutingStrategy), AuthService (delegates to AuthStrategy, runtime strategy swap, role-based authorization against route metadata), RateLimitService (per-route and per-client rate limiting via RateLimiterEngine), CircuitBreakerService (delegates to CircuitBreakerEngine, manual forceOpen/forceClose, summary reporting), LoadBalancerService (delegates to ServiceRegistry + LoadBalancingStrategy, runtime strategy swap), ServiceMeshService (sidecar proxy simulation: mTLS validation, circuit breaker, load balance, forward with 5% simulated failure). |
| **Repository** | `repository/` | Data access layer: RouteRepository interface (save, findById, findByPathPattern, findAll, deleteById) with InMemoryRouteRepository (ConcurrentHashMap-backed), ServiceInstanceRepository interface (save, findById, findByServiceName, findHealthy, findAll, deleteById) with InMemoryServiceInstanceRepository (ConcurrentHashMap-backed, health-aware filtering). |
| **Controller** | `controller/` | REST-like entry point: GatewayController maps simulated HTTP endpoints (POST /gateway/request, POST /gateway/routes, POST /gateway/services, GET /gateway/circuit-breakers, GET /gateway/status) to GatewayService, LoadBalancerService, and CircuitBreakerService. |
| **Config** | `config/` | Factory wiring: AppConfig creates all objects and injects dependencies. Lazy initialization with strategy setters that null-out dependent objects for automatic rebuild. No framework -- pure constructor injection. Composition root pattern. |
| **Display** | `display/` | GatewayStatsDisplay: formatted tables for route table (path, target, methods, rate limit, priority), service instances (id, host:port, health, weight, zone), circuit breaker status (service, state, failures, successes), service registry (name, instance counts), summary stats. |
| **Exception** | `exception/` | Domain exceptions: GatewayException (base RuntimeException), RouteNotFoundException (404 -- unmatched path), ServiceUnavailableException (503 -- circuit open, no healthy instances), RateLimitExceededException (429 -- key, retryAfterMs). |

### Why API Gateway & Service Mesh Is a Top-Tier Interview Question

```
Interviewer's checklist when evaluating your answer:

  1. Is the gateway a single entry point for all microservices?            --> Facade Pattern
  2. Can you swap routing, auth, LB strategies without code changes?       --> Strategy Pattern
  3. How does rate limiting work? Token bucket vs sliding window?          --> Token Bucket
  4. What happens when a downstream service fails repeatedly?              --> Circuit Breaker
  5. How do you distribute traffic across instances?                       --> Load Balancing
  6. How do you secure service-to-service communication?                   --> mTLS
  7. How do you safely roll out new service versions?                      --> Canary Deployment
  8. Is the pipeline extensible with new filters/middleware?               --> Chain of Responsibility
  9. How do you do service discovery and health checking?                  --> Service Registry
  10. Is the design thread-safe for concurrent gateway requests?            --> Concurrency
```

---

## 2. Package Structure

```
com.systemdesign.gateway
|
+-- model/
|   +-- HttpRequest.java          -- Builder, id (UUID), method, path, headers, queryParams, body, clientIp, timestamp
|   +-- HttpResponse.java         -- Builder, statusCode, headers, body, latencyMs, serviceName, static ok/error/redirect
|   +-- HttpMethod.java           -- enum: GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS
|   +-- Route.java                -- Builder, id, pathPattern, targetService, methods, priority, enabled, rateLimitPerSecond, timeoutMs, retryCount, metadata
|   +-- ServiceInstance.java      -- id, serviceName, host, port, healthStatus (volatile), weight, zone, registeredAt, lastHeartbeat (volatile), metadata
|   +-- RequestContext.java       -- mutable: request, route, selectedInstance, authResult, rateLimitResult, startTime, traceId
|   +-- AuthToken.java            -- tokenId, subject, roles, issuedAt, expiresAt, claims, isExpired(), hasRole()
|   +-- AuthResult.java           -- static factories: success/unauthorized/forbidden, isAllowed() = authenticated && authorized
|   +-- CircuitBreakerState.java  -- mutable state machine: serviceName, state, failureCount, successCount, recordSuccess/recordFailure/trip/reset/halfOpen
|   +-- CircuitState.java         -- enum: CLOSED, OPEN, HALF_OPEN
|   +-- HealthStatus.java         -- enum: HEALTHY, UNHEALTHY, DEGRADED, UNKNOWN with isUp()
|   +-- RateLimitResult.java      -- static factories: allowed(remaining)/denied(retryAfterMs)
|   +-- RetryPolicy.java          -- maxRetries, initialDelayMs, maxDelayMs, retryableStatusCodes, exponential backoff
|   +-- ServiceMeshConfig.java    -- Builder, mtlsEnabled, sidecarPort, tracingEnabled, retryPolicy, circuitBreakerEnabled
|   +-- TrafficSplit.java         -- name, splits (version -> weight), weighted random selectVersion()
|   +-- GatewayFilter.java        -- @FunctionalInterface: filter(RequestContext) -> Optional<HttpResponse>
|
+-- strategy/
|   +-- routing/
|   |   +-- RoutingStrategy.java            -- interface: route(request, matchingRoutes) -> Optional<Route>
|   |   +-- PathBasedRoutingStrategy.java   -- specificity scoring (exact=100, wildcard=50-N, catch-all=0)
|   |   +-- HeaderBasedRoutingStrategy.java -- match header against route metadata, fallback to first
|   |
|   +-- loadbalancing/
|   |   +-- LoadBalancingStrategy.java       -- interface: selectInstance(instances, request) -> Optional<ServiceInstance>
|   |   +-- RoundRobinLoadBalancer.java      -- AtomicInteger counter % healthy count (thread-safe)
|   |   +-- WeightedLoadBalancer.java        -- weighted random with ThreadLocalRandom
|   |   +-- ConsistentHashLoadBalancer.java  -- virtual-node TreeMap ring, FNV-1a hash, 150 vnodes/instance
|   |
|   +-- auth/
|   |   +-- AuthStrategy.java     -- interface: authenticate(request) -> AuthResult
|   |   +-- JwtAuthStrategy.java  -- Bearer token, Base64 payload decode, subject + role lookup
|   |   +-- ApiKeyAuthStrategy.java -- X-API-Key header, key-to-client map
|   |
|   +-- traffic/
|       +-- TrafficStrategy.java            -- interface: selectVersion(request, split) -> String
|       +-- CanaryTrafficStrategy.java      -- weighted random across version splits
|       +-- HeaderBasedTrafficStrategy.java -- X-Canary header opt-in to canary version
|
+-- engine/
|   +-- RequestRouter.java        -- path matching (**, *), priority-sorted ArrayList
|   +-- CircuitBreakerEngine.java -- per-service ConcurrentHashMap<String, CircuitBreakerState>
|   +-- RateLimiterEngine.java    -- per-key token bucket with inner TokenBucket class
|   +-- ServiceRegistry.java      -- in-memory ConcurrentHashMap<String, List<ServiceInstance>>
|   +-- TlsEngine.java           -- mutual TLS with trusted service set (HashSet)
|
+-- service/
|   +-- GatewayService.java         -- FACADE: 10-step pipeline (route, auth, authz, rate limit, CB, LB, forward, record, respond)
|   +-- RoutingService.java         -- route matching + strategy-based selection
|   +-- AuthService.java            -- auth + authz with runtime strategy swap
|   +-- RateLimitService.java       -- per-route and per-client rate limiting
|   +-- CircuitBreakerService.java  -- circuit state management + manual overrides
|   +-- LoadBalancerService.java    -- instance selection with runtime strategy swap
|   +-- ServiceMeshService.java     -- sidecar proxy (mTLS, CB, LB, forward)
|
+-- repository/
|   +-- RouteRepository.java, InMemoryRouteRepository.java
|   +-- ServiceInstanceRepository.java, InMemoryServiceInstanceRepository.java
|
+-- controller/
|   +-- GatewayController.java    -- REST-like: POST /gateway/request, routes, services; GET circuit-breakers, status
|
+-- config/
|   +-- AppConfig.java            -- factory/composition root, lazy init, strategy setters null dependents
|
+-- display/
|   +-- GatewayStatsDisplay.java  -- formatted tables: routes, instances, circuit breakers, registry, summary
|
+-- exception/
|   +-- GatewayException.java              -- base RuntimeException
|   +-- RouteNotFoundException.java        -- 404: unmatched path
|   +-- ServiceUnavailableException.java   -- 503: circuit open / no instances
|   +-- RateLimitExceededException.java    -- 429: key + retryAfterMs
|
+-- ApiGatewayServiceMeshApp.java  -- Main demo: 12 demos covering routing, rate limiting, auth, CB, LB, canary, mesh, discovery
```

---

## 3. Class Diagram

```
+=====================================================================+
|     THE CORE PROBLEM: API GATEWAY AS INFRASTRUCTURE ENTRY POINT      |
+=====================================================================+

  Client (Browser/Mobile)       API Gateway            Microservices
      |                             |                       |
      |--- GET /api/users/123 ---->|                       |
      |                             |                       |
      |    Gateway Pipeline:        |                       |
      |    1. Route Match           |                       |
      |    2. Authenticate (JWT)    |                       |
      |    3. Authorize (RBAC)      |                       |
      |    4. Rate Limit (bucket)   |                       |
      |    5. Circuit Breaker       |                       |
      |    6. Load Balance          |                       |
      |    7. Forward ------------>| --- user-svc:8080 --->|
      |                             |    (10.0.1.10)         |
      |                             |                       |
      |<--- 200 OK + response -----|<--- response ---------|
      |    + X-Trace-Id header      |                       |
      |    + latency metrics        |                       |


+=====================================================================+
|               GATEWAY SERVICE -- FACADE PATTERN                      |
+=====================================================================+

  GatewayController (REST-like entry point)
      |
      v
  GatewayService ---- FACADE orchestrating 6 sub-services:
      |
      +-- RoutingService -------- RequestRouter (engine)
      |                           RoutingStrategy (strategy: path/header)
      |
      +-- AuthService ----------- AuthStrategy (strategy: JWT/API key)
      |
      +-- RateLimitService ------ RateLimiterEngine (token bucket)
      |
      +-- CircuitBreakerService - CircuitBreakerEngine (state machine)
      |
      +-- LoadBalancerService --- ServiceRegistry (engine)
      |                           LoadBalancingStrategy (strategy: RR/weighted/hash)
      |
      +-- ServiceMeshService ---- TlsEngine (mTLS)
                                  CircuitBreakerService (shared)
                                  LoadBalancerService (shared)


+=====================================================================+
|                 SERVICE MESH -- SIDECAR PROXY                        |
+=====================================================================+

  order-service                  sidecar (Envoy)              user-service
      |                              |                              |
      |--- call user-service ------>|                              |
      |                              |                              |
      |    Sidecar Pipeline:         |                              |
      |    1. Validate mTLS          |                              |
      |    2. Check circuit breaker  |                              |
      |    3. Select instance (LB)   |                              |
      |    4. Forward request ------>|-------- 10.0.1.10:8080 ---->|
      |    5. Record CB result       |                              |
      |    6. Return response        |                              |
      |                              |                              |
      |<--- response + headers ------|<--- 200 OK -----------------|
      |    X-Mesh-Source             |                              |
      |    X-Mesh-Target             |                              |
      |    X-Mesh-Instance           |                              |


+=====================================================================+
|               DETAILED CLASS RELATIONSHIPS                           |
+=====================================================================+

                        +---------------------+
                        | GatewayController   |
                        | (REST facade)       |
                        +---+-------+---------+
                            |       |
             +--------------+       +-----------+
             v                                  v
  +---------------------+         +----------------------------+
  | GatewayService      |         | CircuitBreakerService      |
  | (Facade -- 10 steps)|         | (state management)         |
  +---+-+-+-+-+-+-------+         +----+-----------------------+
      | | | | | |                      |
      | | | | | +-- ServiceMeshService |
      | | | | |     +-- TlsEngine     |
      | | | | |     +-- CB Service <---+
      | | | | |     +-- LB Service ----+---+
      | | | | |                        |   |
      | | | | +---- LoadBalancerService|   |
      | | | |       +-- ServiceRegistry|   |
      | | | |       +-- LB Strategy    |   |
      | | | |                          |   |
      | | | +------ CircuitBreakerSvc -+   |
      | | |         +-- CB Engine          |
      | | |                                |
      | | +-------- RateLimitService       |
      | |           +-- RateLimiterEngine  |
      | |                                  |
      | +---------- AuthService            |
      |             +-- AuthStrategy       |
      |                                    |
      +------------ RoutingService         |
                    +-- RequestRouter      |
                    +-- RoutingStrategy    |


+=====================================================================+
|           STRATEGY INTERFACES -- 4 PLUGGABLE FAMILIES                |
+=====================================================================+

  <<interface>>             <<interface>>              <<interface>>
  RoutingStrategy           LoadBalancingStrategy      AuthStrategy
      |                         |                          |
      +-- PathBased             +-- RoundRobin             +-- JwtAuth
      +-- HeaderBased           +-- Weighted               +-- ApiKeyAuth
                                +-- ConsistentHash

  <<interface>>
  TrafficStrategy
      |
      +-- Canary
      +-- HeaderBasedTraffic


+=====================================================================+
|          CIRCUIT BREAKER STATE MACHINE                                |
+=====================================================================+

      +----------+   failures >= threshold    +--------+
      |          |  ========================>  |        |
      |  CLOSED  |                             |  OPEN  |
      |          |  <========================  |        |
      +----------+   cooldown expired          +--------+
           ^          (-> HALF_OPEN first)          |
           |                                        |
           |  success count                         | cooldown
           |  >= threshold                          | expires
           |                                        v
           +----------- +-----------+ <-------------+
                        | HALF_OPEN |
                        +-----------+
                             |
                             | failure
                             v
                        +--------+
                        |  OPEN  |  (trips back immediately)
                        +--------+


+=====================================================================+
|          TOKEN BUCKET RATE LIMITER                                    |
+=====================================================================+

  Bucket: [maxTokens=5, refillRate=5/sec]

  Time 0.0s: [TTTTT]  5 tokens  -- bucket starts full
  Request 1: [TTTT.]  4 tokens  -- consume 1
  Request 2: [TTT..]  3 tokens  -- consume 1
  Request 3: [TT...]  2 tokens  -- consume 1
  Request 4: [T....]  1 token   -- consume 1
  Request 5: [.....]  0 tokens  -- consume 1
  Request 6: [.....]  DENIED    -- no tokens, return retryAfterMs
  Time 0.2s: [T....]  1 token   -- refill (0.2s * 5/s = 1 token)
  Request 7: [.....]  0 tokens  -- consume 1, allowed


+=====================================================================+
|          CONSISTENT HASH RING (LOAD BALANCER)                        |
+=====================================================================+

  Hash Ring: 0 --------- 2^31 (wrap-around)

  vnodes: A0  A1  B0  B1  C0  C1  A2  B2  C2 ...  (150 per instance)
          |   |   |   |   |   |   |   |   |
  Ring:   0---+---+---+---+---+---+---+---+---> 2^31
                      ^
                      |
          hash("/api/users/123") = 42850
          ceiling(42850) -> B0 -> Instance B

  Add Instance D:  only ~1/N keys remap (minimal disruption)
  Remove Instance B: only B's keys remap to next node on ring
```

---

## 4. Entity Design

### 4.1 HttpRequest (Builder Pattern)

```java
public class HttpRequest {

    private final String id;                    // UUID -- unique request identifier
    private final HttpMethod method;            // HTTP verb (GET, POST, PUT, etc.)
    private final String path;                  // request path, e.g. "/api/users/123"
    private final Map<String, String> headers;  // HTTP headers (unmodifiable)
    private final Map<String, String> queryParams; // query string parameters (unmodifiable)
    private final String body;                  // request body (nullable for GET/HEAD)
    private final String clientIp;              // originating client IP (default "127.0.0.1")
    private final Instant timestamp;            // when the request was received (default now)

    // Builder requires (method, path); everything else has sensible defaults
    // private constructor takes Builder -- immutable after creation
    // Collections.unmodifiableMap wraps headers and queryParams
}
```

**Design decisions:**
- Builder pattern: method and path are required in the Builder constructor; all other fields have defaults (UUID id, "127.0.0.1" clientIp, Instant.now() timestamp).
- Immutability: all maps wrapped in `Collections.unmodifiableMap`, all fields are `final`.
- Why `String id`? Not `long` -- distributed systems use UUIDs to avoid coordination. Gateway generates the id on receipt.

### 4.2 HttpResponse (Builder + Static Factories)

```java
public class HttpResponse {

    private final int statusCode;               // HTTP status code (200, 401, 429, 503, etc.)
    private final Map<String, String> headers;  // response headers (unmodifiable)
    private final String body;                  // response body (JSON string)
    private final long latencyMs;               // round-trip latency in milliseconds
    private final String serviceName;           // which upstream service produced the response

    // Static factories for common responses:
    //   HttpResponse.ok(body)              -> 200 with body
    //   HttpResponse.error(status, message) -> {status} with JSON error body
    //   HttpResponse.redirect(location)    -> 302 with Location header

    public boolean isSuccess()  { return statusCode >= 200 && statusCode < 300; }
    public boolean isError()    { return statusCode >= 400; }
}
```

**Design decisions:**
- Static factories (ok, error, redirect) reduce boilerplate for the most common response types.
- `latencyMs` and `serviceName` provide observability data that the gateway attaches to every response.
- `isSuccess()` and `isError()` follow HTTP semantics (2xx = success, 4xx/5xx = error).

### 4.3 Route (Builder Pattern -- Core Routing Entity)

```java
public class Route {

    private final String id;                    // UUID -- unique route identifier
    private final String pathPattern;           // glob: "/api/users/**"
    private final String targetService;         // service name in the registry
    private final Set<HttpMethod> methods;      // allowed HTTP methods (EnumSet, unmodifiable)
    private final int priority;                 // lower = higher priority (default 100)
    private final boolean enabled;              // active/inactive toggle (default true)
    private final int rateLimitPerSecond;       // max req/s (0 = unlimited)
    private final long timeoutMs;               // request timeout (default 5000ms)
    private final int retryCount;               // retries on failure (default 0)
    private final Map<String, String> metadata; // extensible: "required-role", "required-header-value"

    // matches(path, method):
    //   1. Exact match: "/health" == "/health"
    //   2. Wildcard suffix: "/api/users/**" matches "/api/users/123"
    //   3. Disabled routes and method mismatches return false
}
```

**Design decisions:**
- `EnumSet.copyOf` for methods: O(1) contains check, cache-friendly bit-set.
- Priority-based ordering: lower value = higher priority. Routes sorted on add in RequestRouter.
- `metadata` map is the extensibility mechanism: authorization roles, header routing values, custom tags. Avoids class explosion when adding new per-route config.

### 4.4 ServiceInstance (Mutable Health State)

```java
public class ServiceInstance {

    private final String id;                    // unique instance identifier
    private final String serviceName;           // logical service name, e.g. "user-service"
    private final String host;                  // hostname or IP
    private final int port;                     // listening port
    private volatile HealthStatus healthStatus; // HEALTHY/UNHEALTHY/DEGRADED/UNKNOWN (mutable)
    private final int weight;                   // weight for weighted LB (higher = more traffic)
    private final String zone;                  // availability zone, e.g. "us-east-1a"
    private final Instant registeredAt;         // when the instance registered
    private volatile Instant lastHeartbeat;     // last heartbeat timestamp (mutable)
    private final Map<String, String> metadata; // extensible key-value metadata

    public String getAddress()      { return host + ":" + port; }
    public void updateHeartbeat()   { lastHeartbeat = Instant.now(); healthStatus = HEALTHY; }
    public boolean isHealthy()      { return healthStatus.isUp(); } // HEALTHY or DEGRADED
}
```

**Design decisions:**
- `volatile` on `healthStatus` and `lastHeartbeat`: these are mutated by health check threads and read by load balancer threads. Volatile ensures visibility without full synchronization.
- `weight` for weighted load balancing: weight=5 gets ~2.5x traffic vs weight=2.
- `zone` for zone-aware routing: prefer same-zone instances to reduce cross-AZ latency.
- Three constructors: minimal (name, host, port), full (+ weight, zone), and explicit-id (for testing with known IDs).

### 4.5 RequestContext (Mutable Pipeline Context)

```java
public class RequestContext {

    private final HttpRequest request;              // original inbound request (immutable)
    private Route route;                            // set by routing filter
    private ServiceInstance selectedInstance;        // set by load balancer
    private AuthResult authResult;                  // set by auth filter
    private RateLimitResult rateLimitResult;         // set by rate limiter
    private final Instant startTime;                // when processing began
    private final String traceId;                   // UUID for distributed tracing

    public long getElapsedMs() {
        return Duration.between(startTime, Instant.now()).toMillis();
    }
}
```

**Design decisions:**
- Mutable by design: each stage of the pipeline populates its result. This is the "context object" pattern used by servlet filters, Netty channel handlers, and Zuul filters.
- `traceId` generated on creation: flows through every log line for end-to-end correlation.
- `getElapsedMs()`: computed on demand, not stored, to avoid stale values.

### 4.6 AuthToken (Parsed JWT Claims)

```java
public class AuthToken {

    private final String tokenId;       // jti claim
    private final String subject;       // sub claim (user or service)
    private final Set<String> roles;    // granted authorities (unmodifiable)
    private final Instant issuedAt;     // iat claim
    private final Instant expiresAt;    // exp claim
    private final Map<String, String> claims; // additional custom claims (unmodifiable)

    public boolean isExpired()          { return Instant.now().isAfter(expiresAt); }
    public boolean hasRole(String role) { return roles.contains(role); }
}
```

### 4.7 AuthResult (Static Factory Pattern)

```java
public class AuthResult {

    private final boolean authenticated;    // identity verified
    private final boolean authorized;       // permission granted
    private final String principal;         // user/client name (null if unauthenticated)
    private final Set<String> roles;        // associated roles
    private final String errorMessage;      // failure reason (null on success)

    // Static factories -- the ONLY way to create instances:
    public static AuthResult success(String principal, Set<String> roles);   // 200
    public static AuthResult unauthorized(String reason);                    // 401
    public static AuthResult forbidden(String reason);                       // 403

    public boolean isAllowed() { return authenticated && authorized; }
}
```

**Design decisions:**
- Private constructor with static factories: prevents invalid states (e.g., authenticated=true but principal=null).
- Separate `authenticated` and `authorized`: authentication (identity) and authorization (permission) are distinct concerns. A user can be authenticated but forbidden from a specific route.

### 4.8 CircuitBreakerState (State Machine)

```java
public class CircuitBreakerState {

    private final String serviceName;       // service this breaker protects
    private CircuitState state;             // CLOSED / OPEN / HALF_OPEN
    private int failureCount;               // consecutive failures in CLOSED
    private int successCount;               // consecutive successes in HALF_OPEN
    private Instant lastFailureTime;        // timestamp of most recent failure
    private Instant lastStateChange;        // timestamp of last transition
    private final int failureThreshold;     // failures to trip (default 5)
    private final int successThreshold;     // successes to recover (default 3)
    private final long openDurationMs;      // cooldown before probe (default 30s)

    // State transitions:
    //   recordSuccess():
    //     HALF_OPEN: successCount++; if >= threshold -> reset() -> CLOSED
    //     CLOSED:    failureCount = 0
    //     OPEN:      ignored
    //
    //   recordFailure():
    //     CLOSED:    failureCount++; if >= threshold -> trip() -> OPEN
    //     HALF_OPEN: trip() -> OPEN immediately
    //     OPEN:      ignored
    //
    //   shouldAttemptReset():
    //     OPEN && elapsed > openDurationMs -> true (transition to HALF_OPEN)
}
```

### 4.9 Supporting Enums and Value Objects

```java
// CircuitState
public enum CircuitState {
    CLOSED("Normal operation -- requests flow through"),
    OPEN("Circuit tripped -- requests rejected immediately"),
    HALF_OPEN("Testing recovery -- limited requests allowed through");
}

// HealthStatus
public enum HealthStatus {
    HEALTHY, UNHEALTHY, DEGRADED, UNKNOWN;
    public boolean isUp() { return this == HEALTHY || this == DEGRADED; }
}

// HttpMethod
public enum HttpMethod { GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS }

// RateLimitResult (static factories)
public class RateLimitResult {
    public static RateLimitResult allowed(int remaining);
    public static RateLimitResult denied(long retryAfterMs);
}

// RetryPolicy (exponential backoff)
public class RetryPolicy {
    // getDelay(attempt): min(initialDelayMs * 2^(attempt-1), maxDelayMs)
    // shouldRetry(statusCode, attempt): attempt <= max && code in retryableSet
    // defaultPolicy(): 3 retries, 100ms initial, 5s max, retry on 502/503/504
}

// TrafficSplit (weighted random version selection)
public class TrafficSplit {
    // selectVersion(): sum weights, random in [0, total), walk entries
    // Example: {"v1-stable": 90, "v2-canary": 10} -> 10% canary
}

// ServiceMeshConfig (Builder)
public class ServiceMeshConfig {
    // Builder with defaults: mtls=true, sidecarPort=15001, tracing=true,
    // retryPolicy=default, circuitBreaker=true
}

// GatewayFilter (functional interface)
@FunctionalInterface
public interface GatewayFilter {
    Optional<HttpResponse> filter(RequestContext ctx);
    // Return empty -> continue chain
    // Return response -> short-circuit (401, 429, etc.)
}
```

---

## 5. Interface Contracts

### 5.1 RoutingStrategy

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

**Contract rules:**
- Input `matchingRoutes` is pre-filtered by RequestRouter (path and method already match).
- Strategy picks the "best" from candidates. PathBased uses specificity scoring. HeaderBased matches metadata.
- Return `Optional.empty()` if no candidate qualifies (e.g., header routing with no match and no fallback).

### 5.2 LoadBalancingStrategy

```java
public interface LoadBalancingStrategy {
    /**
     * Selects a service instance from the available pool.
     *
     * @param instances all registered instances for the target service
     * @param request   the incoming request (may influence selection, e.g., for hashing)
     * @return the chosen instance, or empty if no healthy instance available
     */
    Optional<ServiceInstance> selectInstance(List<ServiceInstance> instances, HttpRequest request);

    String getStrategyName();
}
```

**Contract rules:**
- Each strategy filters to healthy instances internally (isHealthy() check).
- `request` parameter enables request-aware strategies (ConsistentHash uses path for hash key).
- All strategies return empty when no healthy instances exist.

### 5.3 AuthStrategy

```java
public interface AuthStrategy {
    /**
     * Authenticates the incoming request.
     *
     * @param request HTTP request containing credentials (header-based)
     * @return AuthResult: success with principal/roles, or unauthorized with reason
     */
    AuthResult authenticate(HttpRequest request);

    String getStrategyName();
}
```

**Contract rules:**
- JWT reads `Authorization: Bearer <token>`. API Key reads `X-API-Key: <key>`.
- Never throws -- always returns AuthResult (success or unauthorized).
- Authorization is handled separately by AuthService.authorize(authResult, route).

### 5.4 TrafficStrategy

```java
public interface TrafficStrategy {
    /**
     * Selects which deployment version should handle the request.
     *
     * @param request the incoming HTTP request
     * @param split   traffic split configuration (version -> weight)
     * @return name of the selected version (e.g., "v1-stable", "v2-canary")
     */
    String selectVersion(HttpRequest request, TrafficSplit split);

    String getStrategyName();
}
```

**Contract rules:**
- Canary uses weighted random (same algorithm as WeightedLoadBalancer).
- HeaderBased checks X-Canary header: true -> lowest weight (canary), absent -> highest weight (stable).
- Throws IllegalArgumentException if splits map is empty.

### 5.5 RouteRepository

```java
public interface RouteRepository {
    void save(Route route);
    Optional<Route> findById(String id);
    List<Route> findByPathPattern(String pattern);
    List<Route> findAll();
    void deleteById(String id);
}
```

### 5.6 ServiceInstanceRepository

```java
public interface ServiceInstanceRepository {
    void save(ServiceInstance instance);
    Optional<ServiceInstance> findById(String id);
    List<ServiceInstance> findByServiceName(String serviceName);
    List<ServiceInstance> findHealthy(String serviceName);
    List<ServiceInstance> findAll();
    void deleteById(String id);
}
```

### 5.7 GatewayFilter (Functional Interface)

```java
@FunctionalInterface
public interface GatewayFilter {
    /**
     * Processes the request context.
     *
     * @param ctx the request context flowing through the pipeline
     * @return empty to continue the chain, or a response to short-circuit
     */
    Optional<HttpResponse> filter(RequestContext ctx);
}
```

**Contract rules:**
- Return `Optional.empty()` to pass to the next filter.
- Return `Optional.of(response)` to short-circuit the chain (e.g., 401 Unauthorized, 429 Too Many Requests).
- Filters mutate RequestContext (set route, authResult, rateLimitResult, etc.).

---

## 6. Strategy Implementations

### 6.1 Routing Strategies

#### PathBasedRoutingStrategy

```java
public class PathBasedRoutingStrategy implements RoutingStrategy {

    // Specificity scoring:
    //   Exact path (no wildcards)   -> 100  (highest priority)
    //   Contains wildcards          -> 50 - wildcardCount
    //   Catch-all ("/**" or "**")   -> 0   (lowest priority)
    //
    // Tiebreaker: longer path patterns are more specific
    //
    // Example rankings:
    //   "/api/users/123"       -> 100 (exact)
    //   "/api/users/*"         -> 49  (one wildcard)
    //   "/api/users/**"        -> 48  (two wildcard chars)
    //   "/**"                  -> 0   (catch-all)

    @Override
    public Optional<Route> route(HttpRequest request, List<Route> matchingRoutes) {
        return matchingRoutes.stream()
                .sorted(Comparator.comparingInt(this::specificityScore).reversed()
                        .thenComparing(r -> r.getPathPattern().length(), Comparator.reverseOrder()))
                .findFirst();
    }
}
```

#### HeaderBasedRoutingStrategy

```java
public class HeaderBasedRoutingStrategy implements RoutingStrategy {

    private final String headerName;  // e.g. "X-Version"

    // Flow:
    //   1. Read headerName from request (e.g., "X-Version: v2")
    //   2. Find route with metadata "required-header-value" == "v2"
    //   3. If no match, fallback to first matching route
    //
    // Use case: API versioning via header (X-Version), A/B testing (X-Experiment)
}
```

### 6.2 Load Balancing Strategies

#### RoundRobinLoadBalancer

```java
public class RoundRobinLoadBalancer implements LoadBalancingStrategy {

    private final AtomicInteger counter = new AtomicInteger(0);

    // Flow:
    //   1. Filter to healthy instances
    //   2. index = |counter.getAndIncrement()| % healthy.size()
    //   3. Return healthy.get(index)
    //
    // Thread-safety: AtomicInteger ensures correct rotation under concurrent access.
    // Math.abs handles integer overflow (counter wraps to negative).
    //
    // When to use: homogeneous instances with equal capacity.
    // Real-world: Nginx default, K8s kube-proxy, AWS ALB.
}
```

#### WeightedLoadBalancer

```java
public class WeightedLoadBalancer implements LoadBalancingStrategy {

    // Flow:
    //   1. Filter to healthy instances
    //   2. totalWeight = sum of all instance weights
    //   3. random = ThreadLocalRandom.nextInt(totalWeight)
    //   4. Walk instances, accumulate weight, select when accumulated > random
    //
    // Example: A(w=3), B(w=1) -> A gets ~75% traffic, B gets ~25%
    //
    // When to use: heterogeneous instances (bigger VM = higher weight),
    //   gradual rollouts (new version starts with weight=1).
    // Real-world: Envoy weighted clusters, AWS ALB weighted target groups.
}
```

#### ConsistentHashLoadBalancer

```java
public class ConsistentHashLoadBalancer implements LoadBalancingStrategy {

    private static final int DEFAULT_VIRTUAL_NODE_COUNT = 150;

    // Flow:
    //   1. Filter to healthy instances
    //   2. Build TreeMap ring: each instance gets 150 virtual nodes
    //      Key = hash("instanceId-vnode-N"), Value = ServiceInstance
    //   3. Hash request path using FNV-1a
    //   4. ring.ceilingEntry(requestHash) -> nearest node clockwise
    //   5. If null (past last node), wrap to ring.firstEntry()
    //
    // FNV-1a hash:
    //   int hash = 0x811c9dc5;  // offset basis
    //   for each char: hash ^= char; hash *= 0x01000193;  // FNV prime
    //   return hash & 0x7FFFFFFF;  // ensure non-negative
    //
    // Why 150 virtual nodes?
    //   Fewer -> uneven distribution (some instances get 2-3x traffic)
    //   More -> more memory but better uniformity
    //   150 is the industry standard (Ketama, Envoy)
    //
    // Key property: same path -> same instance (cache affinity)
    // Add/remove instance: only ~1/N keys remap (minimal disruption)
    //
    // When to use: caching services (cache affinity), session affinity.
    // Real-world: Memcached, DynamoDB, Cassandra, Envoy.
}
```

### 6.3 Auth Strategies

#### JwtAuthStrategy

```java
public class JwtAuthStrategy implements AuthStrategy {

    private final Map<String, Set<String>> roleMap;  // subject -> roles

    // Flow:
    //   1. Read "Authorization" header
    //   2. Verify "Bearer " prefix
    //   3. Split token on "." -- expect 3 parts (header.payload.signature)
    //   4. Base64URL-decode payload (middle part)
    //   5. Extract "sub" field (subject / username)
    //   6. Lookup roles from roleMap
    //   7. Return AuthResult.success(subject, roles)
    //
    // Failure modes:
    //   - Missing Authorization header -> unauthorized("Missing or invalid Authorization header")
    //   - Not 3 parts -> unauthorized("Invalid JWT format")
    //   - Cannot decode -> unauthorized("Could not extract subject")
    //
    // Production notes:
    //   - Real JWT validation: RS256 signature verification with public key
    //   - Check exp claim for expiry
    //   - Validate iss (issuer) and aud (audience)
    //   - Use library (Nimbus JOSE, Auth0 java-jwt)
}
```

#### ApiKeyAuthStrategy

```java
public class ApiKeyAuthStrategy implements AuthStrategy {

    private final Map<String, String> validKeys;  // apiKey -> clientName

    // Flow:
    //   1. Read "X-API-Key" header
    //   2. Lookup key in validKeys map
    //   3. If found -> AuthResult.success(clientName, Set.of("API_CLIENT"))
    //   4. If missing/invalid -> AuthResult.unauthorized
    //
    // Production notes:
    //   - Store keys hashed (bcrypt/argon2), not plaintext
    //   - Per-key rate limits for abuse prevention
    //   - Key rotation: support multiple active keys per client
    //   - Prefix convention: sk_live_, pk_test_ (Stripe pattern)
}
```

### 6.4 Traffic Strategies

#### CanaryTrafficStrategy

```java
public class CanaryTrafficStrategy implements TrafficStrategy {

    // Flow:
    //   1. Read version weights from TrafficSplit (e.g., {"v1": 90, "v2": 10})
    //   2. totalWeight = sum of all weights
    //   3. random = ThreadLocalRandom.nextInt(totalWeight)
    //   4. Walk entries, accumulate, select when accumulated > random
    //
    // Same algorithm as WeightedLoadBalancer but applied to versions, not instances.
    //
    // Deployment workflow:
    //   v2 starts at 1% -> monitor error rate -> 5% -> 25% -> 50% -> 100%
    //   Rollback: set v2 weight to 0
}
```

#### HeaderBasedTrafficStrategy

```java
public class HeaderBasedTrafficStrategy implements TrafficStrategy {

    private static final String CANARY_HEADER = "X-Canary";

    // Flow:
    //   1. Check "X-Canary: true" header
    //   2. If present -> select lowest-weight version (the canary)
    //   3. If absent  -> select highest-weight version (the stable)
    //
    // Use case: QA/internal users set header to opt in to canary.
    // Everyone else gets stable production.
    //
    // Real-world: Istio VirtualService with header-based match rules,
    //   AWS ALB with custom header routing.
}
```

---

## 7. Engine Design

### 7.1 RequestRouter (Path Matching)

```java
public class RequestRouter {

    private final List<Route> routes = new ArrayList<>();  // sorted by priority

    // addRoute(route):
    //   1. Add route to list
    //   2. Re-sort by priority (Comparator.comparingInt(Route::getPriority))
    //   Lower priority value = higher priority = matched first

    // match(request):
    //   1. Stream all routes
    //   2. Filter: enabled, method matches, path matches
    //   3. findFirst() -- first match wins (priority-sorted)

    // matchesPath(pattern, path):
    //   1. Exact match: "/health" == "/health"
    //   2. ** wildcard: "/api/users/**" -> strip "/**", check startsWith(prefix + "/")
    //   3. * wildcard: "/api/users/*" -> replace * with "[^/]+" regex
    //
    // Why ArrayList not HashMap?
    //   Routes need ordered iteration by priority. A HashMap can't provide
    //   ordered matching. In production (Kong, Envoy), radix trees provide
    //   O(path length) lookups -- our linear scan is fine for demo scale.
}
```

**Matching algorithm in detail:**

```
Request: GET /api/users/123/orders

Route Table (priority-sorted):
  Priority 1:  /health                  -> health-check
  Priority 5:  /api/payments/**         -> payment-service
  Priority 10: /api/users/**            -> user-service        <-- MATCH
  Priority 10: /api/orders/**           -> order-service

Step 1: /health -- exact match? No. ** wildcard? No.                Skip.
Step 2: /api/payments/** -- prefix "/api/payments" -- startsWith? No. Skip.
Step 3: /api/users/** -- prefix "/api/users" -- startsWith? YES.    MATCH.
Return: Route(target=user-service)
```

### 7.2 CircuitBreakerEngine (Per-Service State Machine)

```java
public class CircuitBreakerEngine {

    private final Map<String, CircuitBreakerState> breakers = new ConcurrentHashMap<>();

    private static final int DEFAULT_FAILURE_THRESHOLD = 5;
    private static final int DEFAULT_SUCCESS_THRESHOLD = 3;
    private static final long DEFAULT_OPEN_DURATION_MS = 30_000;

    // getOrCreate(serviceName):
    //   Lazily creates a breaker with default thresholds.
    //   ConcurrentHashMap.computeIfAbsent is atomic.

    // allowRequest(serviceName):
    //   CLOSED    -> true (always allow)
    //   OPEN      -> if cooldown expired: halfOpen(), return true
    //                else: return false (reject)
    //   HALF_OPEN -> true (probe request allowed)

    // recordSuccess(serviceName):
    //   Delegates to CircuitBreakerState.recordSuccess()
    //   HALF_OPEN: if successCount >= threshold -> CLOSED (recovered)
    //   CLOSED: resets failureCount to 0
    //   Logs state transitions

    // recordFailure(serviceName):
    //   Delegates to CircuitBreakerState.recordFailure()
    //   CLOSED: if failureCount >= threshold -> OPEN (tripped)
    //   HALF_OPEN: immediately -> OPEN (recovery failed)
    //   Logs state transitions
}
```

**State transition trace:**

```
t=0s    service-A: CLOSED (failureCount=0)
t=1s    recordFailure -> failureCount=1, state=CLOSED
t=2s    recordFailure -> failureCount=2, state=CLOSED
t=3s    recordFailure -> failureCount=3, state=CLOSED
t=4s    recordFailure -> failureCount=4, state=CLOSED
t=5s    recordFailure -> failureCount=5, TRIP! state=OPEN
t=6s    allowRequest -> OPEN, cooldown not expired -> REJECT (false)
t=35s   allowRequest -> OPEN, cooldown expired (30s) -> halfOpen() -> HALF_OPEN, ALLOW
t=36s   recordSuccess -> successCount=1, state=HALF_OPEN
t=37s   recordSuccess -> successCount=2, state=HALF_OPEN
t=38s   recordSuccess -> successCount=3, RECOVERED! reset() -> state=CLOSED
```

### 7.3 RateLimiterEngine (Token Bucket)

```java
public class RateLimiterEngine {

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    // configure(key, maxTokens, refillRate):
    //   Creates a new TokenBucket for the key.
    //   Bucket starts at full capacity (tokens = maxTokens).

    // tryConsume(key):
    //   1. Get bucket for key (null -> allow, no limit configured)
    //   2. synchronized(bucket):
    //      a. refill() -- add tokens based on elapsed time
    //      b. if tokens >= 1.0: consume 1, return allowed(remaining)
    //      c. else: return denied(retryAfterMs = 1000 / refillRate)

    // Inner class: TokenBucket
    //   double tokens;           // current tokens (double for fractional refill)
    //   int maxTokens;           // bucket capacity
    //   double refillRate;       // tokens per second
    //   Instant lastRefillTime;  // for elapsed-time calculation
    //
    //   refill():
    //     elapsedSeconds = (now - lastRefillTime) / 1000.0
    //     tokensToAdd = elapsedSeconds * refillRate
    //     tokens = min(maxTokens, tokens + tokensToAdd)
    //     lastRefillTime = now
}
```

**Token bucket algorithm walkthrough:**

```
Config: maxTokens=5, refillRate=5.0/sec

t=0.00s  bucket=[5.0]  tryConsume -> 4.0 remaining (allowed)
t=0.01s  bucket=[4.0]  tryConsume -> 3.0 remaining (allowed)
t=0.02s  bucket=[3.0]  tryConsume -> 2.0 remaining (allowed)
t=0.03s  bucket=[2.0]  tryConsume -> 1.0 remaining (allowed)
t=0.04s  bucket=[1.0]  tryConsume -> 0.0 remaining (allowed)
t=0.05s  bucket=[0.0]  tryConsume -> DENIED (retryAfterMs=200)
         refill: 0.05s * 5/s = 0.25 tokens added -> bucket=[0.25]
         0.25 < 1.0 -> still denied
t=0.25s  refill: 0.2s * 5/s = 1.0 token added -> bucket=[1.25]
         tryConsume -> 0.25 remaining (allowed)

Key insight: Token bucket allows bursts (up to capacity) then enforces
steady-state rate. Better than fixed window which allows 2x burst at
window boundaries.
```

### 7.4 ServiceRegistry (In-Memory Service Discovery)

```java
public class ServiceRegistry {

    private final Map<String, List<ServiceInstance>> instances = new ConcurrentHashMap<>();

    // register(instance):
    //   computeIfAbsent(serviceName, k -> new ArrayList<>()).add(instance)

    // deregister(instanceId):
    //   Walk all lists, removeIf(inst -> inst.getId() == instanceId)

    // getInstances(serviceName):
    //   Return only HEALTHY instances (healthStatus == HEALTHY)

    // getAllInstances(serviceName):
    //   Return all instances including unhealthy (for admin/display)

    // heartbeat(instanceId):
    //   Find instance across all services, call updateHeartbeat()
    //   (sets lastHeartbeat = now, healthStatus = HEALTHY)

    // markUnhealthy(instanceId) / markHealthy(instanceId):
    //   Find instance, set health status

    // evictStale(timeout):
    //   Remove instances whose lastHeartbeat is older than cutoff
    //   Return count of evicted instances
    //
    //   Production equivalent: Consul deregisters after 90s without heartbeat.
    //   Eureka evicts after 3 missed renewals (90s default).
}
```

### 7.5 TlsEngine (Mutual TLS Simulation)

```java
public class TlsEngine {

    private final Set<String> trustedServices = new HashSet<>();
    private boolean mtlsEnabled = false;

    // enableMtls() / disableMtls():
    //   Global toggle for mTLS enforcement

    // trustService(serviceName) / revokeService(serviceName):
    //   Add/remove from trusted set

    // validateConnection(callerService, targetService):
    //   1. If mTLS disabled -> return false (skip validation)
    //   2. Check callerService in trustedServices
    //   3. Check targetService in trustedServices
    //   4. Both must be trusted -> return true
    //   5. Either untrusted -> return false
    //
    // Real-world: Istio generates and rotates X.509 certificates via
    //   its CA (Citadel). Envoy sidecars present certs on every connection.
    //   No application code changes required.
}
```

---

## 8. Service Layer Design

### 8.1 GatewayService (Facade -- THE Core Class)

```java
public class GatewayService {

    // Facade Pattern -- hides 6 sub-services behind handleRequest()
    private final RoutingService routingService;
    private final AuthService authService;
    private final RateLimitService rateLimitService;
    private final CircuitBreakerService circuitBreakerService;
    private final LoadBalancerService loadBalancerService;
    private final ServiceMeshService serviceMeshService;

    /**
     * Full gateway pipeline -- 10 steps:
     *
     *   1. Create RequestContext (traceId, startTime)
     *   2. Route matching (path + method -> Route)
     *   3. Authentication (JWT/API Key -> AuthResult)
     *   4. Authorization (role check against route metadata)
     *   5. Rate limiting (token bucket check for route)
     *   6. Circuit breaker (is target service healthy?)
     *   7. Load balance (select instance from healthy pool)
     *   8. Forward to upstream (simulated: random latency 10-100ms, 5% failure)
     *   9. Record result (success/failure in circuit breaker)
     *  10. Build response (200 OK or error, attach traceId header)
     *
     * Any step can short-circuit:
     *   Step 2 fail -> 404 Not Found
     *   Step 3 fail -> 401 Unauthorized
     *   Step 4 fail -> 403 Forbidden
     *   Step 5 fail -> 429 Too Many Requests
     *   Step 6 fail -> 503 Service Unavailable (circuit open)
     *   Step 7 fail -> 503 Service Unavailable (no instances)
     *   Step 8 fail -> 502 Bad Gateway (upstream error)
     */
    public HttpResponse handleRequest(HttpRequest request) { ... }
}
```

**Pipeline flow diagram:**

```
Client Request
    |
    v
+---[1. Create RequestContext]---+
|   traceId = UUID               |
|   startTime = Instant.now()    |
+--------------------------------+
    |
    v
+---[2. Route Matching]---------+
|   routingService.matchRoute()  |
|   NO MATCH -> 404 Not Found   |
+--------------------------------+
    |
    v
+---[3. Authentication]---------+
|   authService.authenticate()   |
|   FAIL -> 401 Unauthorized     |
+--------------------------------+
    |
    v
+---[4. Authorization]----------+
|   authService.authorize()      |
|   DENIED -> 403 Forbidden      |
+--------------------------------+
    |
    v
+---[5. Rate Limiting]----------+
|   rateLimitService.check()     |
|   DENIED -> 429 Too Many Req   |
+--------------------------------+
    |
    v
+---[6. Circuit Breaker]--------+
|   cbService.allowRequest()     |
|   OPEN -> 503 Svc Unavailable  |
+--------------------------------+
    |
    v
+---[7. Load Balance]-----------+
|   lbService.selectInstance()   |
|   EMPTY -> 503 No Instances    |
+--------------------------------+
    |
    v
+---[8. Forward to Upstream]----+
|   simulate: latency 10-100ms  |
|   5% random failure chance     |
+--------------------------------+
    |
    v
+---[9. Record CB Result]-------+
|   success -> recordSuccess()   |
|   failure -> recordFailure()   |
|   FAIL -> 502 Bad Gateway      |
+--------------------------------+
    |
    v
+---[10. Build Response]---------+
|   200 OK + JSON body            |
|   X-Trace-Id header             |
|   X-Gateway-Service header      |
|   X-Gateway-Instance header     |
+---------------------------------+
```

### 8.2 RoutingService

```java
public class RoutingService {

    private final RequestRouter router;            // path-pattern matching engine
    private final RoutingStrategy routingStrategy;  // strategy for selecting best route

    // registerRoute(route):
    //   Delegates to router.addRoute() -- adds and re-sorts by priority

    // matchRoute(request):
    //   Delegates to router.match() -- first priority-sorted match wins

    // routeRequest(request, routes):
    //   Delegates to routingStrategy.route() -- strategy-based selection from candidates

    // removeRoute(routeId):
    //   Delegates to router.removeRoute() -- removes by ID
}
```

### 8.3 AuthService

```java
public class AuthService {

    private volatile AuthStrategy authStrategy;  // volatile for thread-safe swap

    // authenticate(request):
    //   1. Delegate to authStrategy.authenticate(request)
    //   2. Log result (principal + roles on success, error on failure)
    //   3. Return AuthResult

    // authorize(authResult, route):
    //   1. Read "required-role" from route metadata
    //   2. No required-role -> public route, always authorized
    //   3. Check if authResult.getRoles().contains(requiredRole)
    //   4. Return boolean

    // setStrategy(strategy):
    //   Hot-swap authentication mechanism at runtime (Strategy Pattern)
    //   volatile field ensures visibility across threads
}
```

### 8.4 RateLimitService

```java
public class RateLimitService {

    private final RateLimiterEngine rateLimiter;

    // configureRoute(routeId, maxRequestsPerSecond):
    //   Create token bucket for route (key = routeId)

    // configureClient(clientId, maxRequestsPerSecond):
    //   Create token bucket for client (key = clientId or IP)

    // checkRouteRateLimit(ctx):
    //   1. Get route from context
    //   2. If rateLimitPerSecond <= 0 -> allow (no limit)
    //   3. rateLimiter.tryConsume(routeId) -> allowed/denied

    // checkClientRateLimit(ctx):
    //   Key = auth principal (if authenticated) or clientIp (fallback)
    //   rateLimiter.tryConsume(key) -> allowed/denied
}
```

### 8.5 CircuitBreakerService

```java
public class CircuitBreakerService {

    private final CircuitBreakerEngine engine;

    // allowRequest(serviceName):
    //   Delegates to engine.allowRequest() -- checks state
    //   Logs service name, state, and allowed/denied

    // recordSuccess(serviceName) / recordFailure(serviceName):
    //   Delegates to engine -- may trigger state transitions
    //   Logs current state after recording

    // forceOpen(serviceName):
    //   engine.getOrCreate(serviceName).trip() -- manual trip
    //   Use case: admin wants to manually isolate a service

    // forceClose(serviceName):
    //   engine.getOrCreate(serviceName).reset() -- manual reset
    //   Use case: admin confirms service is healthy, bypass cooldown

    // getCircuitSummary():
    //   Returns Map<String, CircuitState> for all tracked services
}
```

### 8.6 LoadBalancerService

```java
public class LoadBalancerService {

    private final ServiceRegistry registry;
    private volatile LoadBalancingStrategy strategy;  // volatile for hot-swap

    // selectInstance(serviceName, request):
    //   1. getHealthyInstances(serviceName) from registry
    //   2. If empty -> log, return empty
    //   3. strategy.selectInstance(healthy, request)
    //   4. Log selected instance address

    // registerInstance(instance) / deregisterInstance(instanceId):
    //   Delegates to registry

    // setStrategy(strategy):
    //   Hot-swap load balancing algorithm at runtime
    //   volatile field ensures visibility
}
```

### 8.7 ServiceMeshService (Sidecar Proxy)

```java
public class ServiceMeshService {

    private final TlsEngine tlsEngine;
    private final CircuitBreakerService circuitBreakerService;
    private final LoadBalancerService loadBalancerService;
    private final ServiceMeshConfig meshConfig;

    /**
     * Sidecar proxy pipeline -- 6 steps:
     *
     *   1. mTLS validation (if enabled)
     *      FAIL -> 403 mTLS validation failed
     *
     *   2. Circuit breaker check
     *      OPEN -> 503 Circuit breaker OPEN
     *
     *   3. Load balance (select instance)
     *      EMPTY -> 503 No healthy instances + recordFailure
     *
     *   4. Forward request (simulated: 5-50ms latency, 5% failure)
     *
     *   5. Record result in circuit breaker
     *      FAIL -> 502 Service error
     *
     *   6. Build response (200 OK with mesh headers)
     *      X-Mesh-Source, X-Mesh-Target, X-Mesh-Instance
     */
    public HttpResponse proxyRequest(String callerService, String targetService, HttpRequest request) { ... }
}
```

---

## 9. Concurrency Considerations

### 9.1 ConcurrentHashMap Usage

| Component | Map | Why ConcurrentHashMap |
|-----------|-----|----------------------|
| CircuitBreakerEngine | `breakers: Map<String, CircuitBreakerState>` | Multiple gateway threads check/update different service breakers concurrently |
| RateLimiterEngine | `buckets: Map<String, TokenBucket>` | Different clients/routes rate-limited concurrently |
| ServiceRegistry | `instances: Map<String, List<ServiceInstance>>` | Registration, deregistration, health checks happen on different threads |
| InMemoryRouteRepository | `store: Map<String, Route>` | Route CRUD and route matching can be concurrent |
| InMemoryServiceInstanceRepository | `store: Map<String, ServiceInstance>` | Same as above for instance data |

### 9.2 AtomicInteger (RoundRobinLoadBalancer)

```java
private final AtomicInteger counter = new AtomicInteger(0);
int index = Math.abs(counter.getAndIncrement() % healthy.size());
```

- `getAndIncrement()` is atomic -- no two threads get the same counter value.
- `Math.abs()` handles integer overflow when counter wraps past `Integer.MAX_VALUE`.
- No synchronization needed -- lock-free and fast.

### 9.3 Volatile Fields

```java
// AuthService
private volatile AuthStrategy authStrategy;

// LoadBalancerService
private volatile LoadBalancingStrategy strategy;

// ServiceInstance
private volatile HealthStatus healthStatus;
private volatile Instant lastHeartbeat;
```

- `volatile` guarantees happens-before: a write by thread A is visible to a subsequent read by thread B.
- Used for hot-swappable strategies (set on admin thread, read on request threads).
- Used for health status (set by health check thread, read by load balancer threads).

### 9.4 Synchronized Block (Token Bucket)

```java
synchronized (bucket) {
    bucket.refill();
    if (bucket.tokens >= 1.0) {
        bucket.tokens -= 1.0;
        return RateLimitResult.allowed((int) bucket.tokens);
    }
}
```

- refill + consume must be atomic per bucket -- otherwise two threads could both see tokens=1, both consume, resulting in tokens=-1.
- Synchronized on the individual bucket object -- no global lock, different keys are independent.
- In production: Redis + Lua script for distributed token bucket (atomic execution).

### 9.5 Thread-Safety Summary

```
Component                 Mechanism               Contention Level
---------------------------------------------------------------------
CircuitBreakerEngine      ConcurrentHashMap        Low (per-service key)
RateLimiterEngine         CHM + synchronized       Medium (per-key lock)
RoundRobinLoadBalancer    AtomicInteger            Very Low (CAS)
ServiceRegistry           ConcurrentHashMap        Low (per-service key)
AuthService               volatile strategy        None (read-mostly)
LoadBalancerService       volatile strategy        None (read-mostly)
ServiceInstance           volatile fields          None (single writer)
```

---

## 10. SOLID Principles Applied

### S - Single Responsibility

| Class | Single Responsibility |
|-------|----------------------|
| `RequestRouter` | Path matching only -- does not authenticate or rate limit |
| `CircuitBreakerEngine` | State machine management only -- does not forward requests |
| `RateLimiterEngine` | Token bucket management only -- does not route |
| `AuthService` | Authentication + authorization only -- uses strategy for mechanism |
| `GatewayController` | HTTP endpoint mapping only -- delegates all logic to services |
| `GatewayStatsDisplay` | Console formatting only -- does not modify state |

### O - Open/Closed

```
Open for extension, closed for modification:

  RoutingStrategy          -- add WeightedRoutingStrategy without touching RequestRouter
  LoadBalancingStrategy    -- add LeastConnectionsLoadBalancer without changing LoadBalancerService
  AuthStrategy             -- add OAuth2Strategy without modifying AuthService
  TrafficStrategy          -- add BlueGreenTrafficStrategy without changing TrafficSplit
  RouteRepository          -- add JdbcRouteRepository without modifying RoutingService
  ServiceInstanceRepository -- add ConsulServiceInstanceRepository without changing registry
  GatewayFilter            -- add LoggingFilter, MetricsFilter without changing pipeline

  The Strategy interfaces are the primary extension mechanism.
  New strategies register at runtime via setStrategy() -- zero recompilation.
```

### L - Liskov Substitution

```
All strategy implementations are substitutable:

  RoundRobinLoadBalancer, WeightedLoadBalancer, ConsistentHashLoadBalancer
    -> all implement LoadBalancingStrategy
    -> all return Optional<ServiceInstance> from selectInstance()
    -> LoadBalancerService works with any of them

  JwtAuthStrategy, ApiKeyAuthStrategy
    -> both implement AuthStrategy
    -> both return AuthResult from authenticate()
    -> AuthService works with either

  Test: swap strategy at runtime via setStrategy() -- no behavioral change
  in the calling service.
```

### I - Interface Segregation

```
Four narrow strategy interfaces instead of one fat "GatewayStrategy":

  RoutingStrategy:       route(request, routes) -> Optional<Route>
  LoadBalancingStrategy: selectInstance(instances, request) -> Optional<ServiceInstance>
  AuthStrategy:          authenticate(request) -> AuthResult
  TrafficStrategy:       selectVersion(request, split) -> String

Each interface has ONE method (plus getStrategyName for logging).
Implementations only need to know their own concern.

Similarly, two repository interfaces (RouteRepository, ServiceInstanceRepository)
instead of one generic Repository that knows about both routes and instances.
```

### D - Dependency Inversion

```
High-level modules depend on abstractions:

  GatewayService -------> RoutingService (not RequestRouter directly)
  LoadBalancerService --> LoadBalancingStrategy (interface, not RoundRobin)
  AuthService ----------> AuthStrategy (interface, not JwtAuthStrategy)
  RoutingService -------> RoutingStrategy (interface, not PathBasedRoutingStrategy)

  AppConfig is the only class that knows about concrete implementations.
  Services receive interfaces via constructor injection.
  Strategy swap at runtime via setStrategy() -- pure interface-level change.

  Dependency flow:
    Controller -> Service -> Strategy (interface)
                          -> Engine
                          -> Repository (interface)
```

---

## 11. Sample Workflows

### 11.1 Successful Request Through Full Pipeline

```
1. Client sends:       GET /api/users/123
                        Authorization: Bearer eyJh...(JWT)
                        Client-IP: 192.168.1.100

2. GatewayService.handleRequest(request):
   a. Create RequestContext (traceId=abc12345, startTime=now)

3. Route Matching:
   a. RequestRouter.match(GET, "/api/users/123")
   b. Iterate priority-sorted routes:
      - /health (priority=1)       -> path mismatch, skip
      - /api/payments/** (pri=5)   -> prefix "/api/payments", no match, skip
      - /api/users/** (pri=10)     -> prefix "/api/users", match! target=user-service
   c. ctx.setRoute(route)

4. Authentication:
   a. AuthService.authenticate(request) -> JwtAuthStrategy
   b. Read "Authorization: Bearer eyJh..."
   c. Split on "." -> 3 parts (valid JWT structure)
   d. Base64-decode payload -> extract sub="karan"
   e. roleMap.get("karan") -> Set.of("admin", "user")
   f. Return AuthResult.success("karan", {"admin", "user"})

5. Authorization:
   a. AuthService.authorize(authResult, route)
   b. route.getMetadata("required-role") -> null (no required role)
   c. Public route -> authorized (return true)

6. Rate Limiting:
   a. RateLimitService.checkRouteRateLimit(ctx)
   b. Route rate limit = 100 req/s
   c. rateLimiter.tryConsume(routeId) -> allowed (99 remaining)

7. Circuit Breaker:
   a. CircuitBreakerService.allowRequest("user-service")
   b. State = CLOSED -> allowed

8. Load Balancing:
   a. LoadBalancerService.selectInstance("user-service", request)
   b. Registry returns 3 healthy instances:
      - user-svc-1 @ 10.0.1.10:8080 (w=3, us-east-1a)
      - user-svc-2 @ 10.0.1.11:8080 (w=2, us-east-1b)
      - user-svc-3 @ 10.0.1.12:8080 (w=5, us-west-2a)
   c. RoundRobin: counter=0 -> index=0 -> user-svc-1

9. Forward:
   a. Simulate: latency=42ms, success=true (95% chance)

10. Record + Respond:
    a. circuitBreakerService.recordSuccess("user-service")
    b. Return HttpResponse(200, body={"service":"user-service","instance":"10.0.1.10:8080"})
    c. Headers: X-Trace-Id=abc12345, X-Gateway-Service=user-service
```

### 11.2 Circuit Breaker Trip and Recovery

```
1. Initial state: user-service circuit = CLOSED (failureCount=0)

2. Five consecutive failures (upstream errors):
   a. recordFailure -> failureCount=1, state=CLOSED
   b. recordFailure -> failureCount=2, state=CLOSED
   c. recordFailure -> failureCount=3, state=CLOSED
   d. recordFailure -> failureCount=4, state=CLOSED
   e. recordFailure -> failureCount=5 >= threshold(5) -> trip() -> state=OPEN

3. Next request to user-service:
   a. CircuitBreakerService.allowRequest("user-service")
   b. state=OPEN, elapsed=2s < openDuration=30s -> REJECT
   c. Return 503 "Circuit breaker is OPEN"

4. After 30 seconds (cooldown expires):
   a. allowRequest("user-service")
   b. state=OPEN, elapsed=31s > openDuration=30s -> halfOpen()
   c. state=HALF_OPEN -> ALLOW (probe request)

5. Three consecutive successes (recovery):
   a. recordSuccess -> successCount=1, state=HALF_OPEN
   b. recordSuccess -> successCount=2, state=HALF_OPEN
   c. recordSuccess -> successCount=3 >= threshold(3) -> reset() -> state=CLOSED

6. Service recovered: all requests flow through normally.
```

### 11.3 Rate Limit Exceeded

```
1. Route configured: /api/payments/** -> rateLimitPerSecond=20

2. Client sends 25 rapid requests:
   a. Requests 1-20: token bucket has 20 tokens -> ALLOWED (remaining: 19, 18, ..., 0)
   b. Request 21: bucket empty, refill insufficient (0.05s * 20/s = 1 token) -> DENIED
   c. Return 429 Too Many Requests
      Body: {"error":"Rate limit exceeded for route 'payment-route'"}
      Retry-After: 50ms (1000 / 20 = 50ms per token)

3. After waiting 1 second: 20 new tokens refilled -> next burst of 20 allowed.
```

### 11.4 Service Mesh mTLS Rejection

```
1. Caller: "unknown-service" wants to call "user-service"

2. ServiceMeshService.proxyRequest("unknown-service", "user-service", request):
   a. Step 1: mTLS validation
   b. meshConfig.isMtlsEnabled() -> true
   c. tlsEngine.validateConnection("unknown-service", "user-service")
   d. trustedServices = {"api-gateway", "user-service", "order-service", "payment-service"}
   e. "unknown-service" NOT in trustedServices -> callerTrusted=false
   f. Return false -> CONNECTION DENIED

3. Return HttpResponse(403)
   Body: {"error":"mTLS validation failed between unknown-service and user-service"}

4. Contrast with trusted call:
   proxyRequest("order-service", "user-service", request)
   -> both in trustedServices -> mTLS PASSED -> proceed to step 2
```

### 11.5 Canary Deployment (Traffic Splitting)

```
1. TrafficSplit: {"v1-stable": 90, "v2-canary": 10}

2. CanaryTrafficStrategy.selectVersion(request, split):
   a. totalWeight = 90 + 10 = 100
   b. random = ThreadLocalRandom.nextInt(100) -> e.g., 42
   c. Walk entries:
      - "v1-stable": accumulated=90, 90 > 42 -> SELECT "v1-stable"
   d. If random were 95:
      - "v1-stable": accumulated=90, 90 < 95 -> continue
      - "v2-canary": accumulated=100, 100 > 95 -> SELECT "v2-canary"

3. Over 1000 requests: ~900 to v1-stable, ~100 to v2-canary

4. Gradually increase canary:
   {"v1-stable": 75, "v2-canary": 25} -> 25% canary
   {"v1-stable": 50, "v2-canary": 50} -> 50% canary
   {"v2-canary": 100}                  -> 100% cutover
```

### 11.6 Consistent Hash Load Balancing (Cache Affinity)

```
1. user-service has 3 instances: A(10.0.1.10), B(10.0.1.11), C(10.0.1.12)
   Each gets 150 virtual nodes on the hash ring.

2. Request: GET /api/users/100
   hash("/api/users/100") = 428501234
   ring.ceilingEntry(428501234) -> vnode B-42 -> Instance B
   -> Routes to 10.0.1.11

3. Request: GET /api/users/200
   hash("/api/users/200") = 891003456
   ring.ceilingEntry(891003456) -> vnode C-108 -> Instance C
   -> Routes to 10.0.1.12

4. Request: GET /api/users/100 (same path, 2nd time)
   hash("/api/users/100") = 428501234 (same hash!)
   ring.ceilingEntry(428501234) -> vnode B-42 -> Instance B (same!)
   -> Cache hit on Instance B's local cache

5. Instance B goes down:
   Only B's vnodes removed from ring.
   Requests that went to B now go to the NEXT node clockwise.
   Instances A and C keep their existing traffic (minimal disruption).
   Only ~1/3 of total keys remap (not 100%).
```

---

## 12. Design Patterns Used

| # | Pattern | GoF Category | Where Used |
|---|---------|-------------|------------|
| 1 | **Strategy (x4)** | Behavioral | RoutingStrategy (PathBased, HeaderBased), LoadBalancingStrategy (RoundRobin, Weighted, ConsistentHash), AuthStrategy (JWT, ApiKey), TrafficStrategy (Canary, HeaderBased) |
| 2 | **Builder** | Creational | HttpRequest.Builder, HttpResponse.Builder, Route.Builder, ServiceMeshConfig.Builder |
| 3 | **Factory** | Creational | AppConfig (composition root, lazy initialization, dependency wiring) |
| 4 | **Repository** | Structural (enterprise) | RouteRepository + InMemoryRouteRepository, ServiceInstanceRepository + InMemoryServiceInstanceRepository |
| 5 | **Facade** | Structural | GatewayService orchestrates 6 sub-services behind handleRequest() |
| 6 | **State** | Behavioral | CircuitBreakerState (CLOSED/OPEN/HALF_OPEN state machine with transition methods) |
| 7 | **Chain of Responsibility** | Behavioral | GatewayFilter functional interface, GatewayService pipeline (each step can short-circuit) |
| 8 | **Proxy** | Structural | ServiceMeshService as sidecar proxy (intercepts and wraps service-to-service calls) |
| 9 | **Singleton** | Creational | AppConfig lazy initialization (single factory instance creates all objects) |

---

## 13. Extensibility Points

### 13.1 New Load Balancing Strategy

```java
// Add LeastConnectionsLoadBalancer without modifying LoadBalancerService:

public class LeastConnectionsLoadBalancer implements LoadBalancingStrategy {
    private final Map<String, AtomicInteger> connectionCounts = new ConcurrentHashMap<>();

    @Override
    public Optional<ServiceInstance> selectInstance(List<ServiceInstance> instances, HttpRequest request) {
        return instances.stream()
                .filter(ServiceInstance::isHealthy)
                .min(Comparator.comparingInt(inst ->
                        connectionCounts.getOrDefault(inst.getId(), new AtomicInteger(0)).get()));
    }

    @Override
    public String getStrategyName() { return "LEAST_CONNECTIONS"; }
}

// Swap at runtime:
config.setLoadBalancingStrategy(new LeastConnectionsLoadBalancer());
```

### 13.2 New Auth Strategy

```java
// Add OAuth2Strategy without modifying AuthService:

public class OAuth2Strategy implements AuthStrategy {
    @Override
    public AuthResult authenticate(HttpRequest request) {
        String token = request.getHeader("Authorization"); // Bearer <access_token>
        // Introspect token with OAuth2 provider
        // Return AuthResult.success or .unauthorized
    }

    @Override
    public String getStrategyName() { return "OAUTH2"; }
}

// Swap at runtime:
config.setAuthStrategy(new OAuth2Strategy());
```

### 13.3 New Repository Backend

```java
// Replace InMemoryRouteRepository with Redis-backed:

public class RedisRouteRepository implements RouteRepository {
    private final RedisTemplate<String, Route> redis;

    @Override
    public void save(Route route) { redis.opsForValue().set(route.getId(), route); }

    @Override
    public Optional<Route> findById(String id) {
        return Optional.ofNullable(redis.opsForValue().get(id));
    }
    // ... other methods
}
```

### 13.4 New Gateway Filters

```java
// Add logging, metrics, or CORS filters via GatewayFilter interface:

GatewayFilter loggingFilter = ctx -> {
    System.out.println("[LOG] " + ctx.getRequest().getMethod() + " " + ctx.getRequest().getPath());
    return Optional.empty(); // continue chain
};

GatewayFilter corsFilter = ctx -> {
    // Add CORS headers to response
    return Optional.empty(); // continue chain
};

// These plug into a List<GatewayFilter> filter chain without modifying GatewayService.
```

### 13.5 New Traffic Strategy

```java
// Add BlueGreenTrafficStrategy:

public class BlueGreenTrafficStrategy implements TrafficStrategy {
    private volatile String activeVersion = "blue";

    @Override
    public String selectVersion(HttpRequest request, TrafficSplit split) {
        return activeVersion; // all traffic to one version
    }

    public void switchTo(String version) { this.activeVersion = version; }

    @Override
    public String getStrategyName() { return "BLUE_GREEN"; }
}
```

### 13.6 Extensibility Summary

```
Extension Point              Interface                    How to Extend
-------------------------------------------------------------------------
Routing algorithm            RoutingStrategy              Implement + setRoutingStrategy()
Load balancing algorithm     LoadBalancingStrategy        Implement + setLoadBalancingStrategy()
Authentication mechanism     AuthStrategy                 Implement + setAuthStrategy()
Traffic shaping              TrafficStrategy              Implement + setTrafficStrategy()
Route persistence            RouteRepository              Implement + inject into AppConfig
Instance persistence         ServiceInstanceRepository    Implement + inject into AppConfig
Request filter               GatewayFilter                Implement + add to filter chain
```

**Key principle:** Every extension point is an interface. New implementations register via constructor injection (AppConfig) or runtime swap (setStrategy). Zero modification to existing code.

---

## 14. AppConfig Wiring -- Composition Root Deep Dive

### 14.1 Full Dependency Graph

```
Layer 0 (Leaf nodes -- no dependencies):
  InMemoryRouteRepository
  InMemoryServiceInstanceRepository
  RequestRouter
  CircuitBreakerEngine
  RateLimiterEngine
  ServiceRegistry
  TlsEngine
  PathBasedRoutingStrategy (default)
  RoundRobinLoadBalancer (default)
  JwtAuthStrategy (default)
  CanaryTrafficStrategy (default)
  ServiceMeshConfig (Builder, defaults)

Layer 1 (Depend on Layer 0):
  RoutingService(RequestRouter, RoutingStrategy)
  AuthService(AuthStrategy)
  RateLimitService(RateLimiterEngine)
  CircuitBreakerService(CircuitBreakerEngine)
  LoadBalancerService(ServiceRegistry, LoadBalancingStrategy)

Layer 2 (Depend on Layer 1):
  ServiceMeshService(TlsEngine, CircuitBreakerService, LoadBalancerService, ServiceMeshConfig)

Layer 3 (Depend on Layer 1 + Layer 2):
  GatewayService(RoutingService, AuthService, RateLimitService,
                 CircuitBreakerService, LoadBalancerService, ServiceMeshService)

Layer 4 (Depend on Layer 3):
  GatewayController(GatewayService, LoadBalancerService, CircuitBreakerService)

Layer 5 (Depend on Layer 3 + Layer 1 + Layer 0):
  GatewayStatsDisplay(GatewayService, LoadBalancerService,
                      CircuitBreakerService, ServiceRegistry)
```

### 14.2 Strategy Setter Invalidation Chains

```
setRoutingStrategy(newStrategy):
  nullify: routingStrategy
  nullify: routingService        (depends on routingStrategy)
  nullify: gatewayService        (depends on routingService)
  nullify: gatewayController     (depends on gatewayService)
  Next getController() call rebuilds the entire chain with new strategy.

setLoadBalancingStrategy(newStrategy):
  nullify: loadBalancingStrategy
  nullify: loadBalancerService   (depends on loadBalancingStrategy)
  nullify: serviceMeshService    (depends on loadBalancerService)
  nullify: gatewayService        (depends on loadBalancerService + serviceMeshService)
  nullify: gatewayController     (depends on gatewayService)
  Widest invalidation -- LB strategy affects both gateway and mesh paths.

setAuthStrategy(newStrategy):
  nullify: authStrategy
  nullify: authService           (depends on authStrategy)
  nullify: gatewayService        (depends on authService)
  nullify: gatewayController     (depends on gatewayService)

setTrafficStrategy(newStrategy):
  nullify: trafficStrategy
  nullify: gatewayController     (depends on trafficStrategy indirectly)
  Narrowest invalidation -- traffic strategy is only used in demos, not core pipeline.
```

### 14.3 Lazy Initialization Pattern

```java
// Every getter follows the same pattern:
public SomeService getSomeService() {
    if (someService == null) {                    // 1. Check cache
        someService = new SomeService(            // 2. Create on first access
            getDependencyA(),                     // 3. Recursively resolve deps
            getDependencyB()                      //    (also lazy)
        );
    }
    return someService;                           // 4. Return cached instance
}

// Benefits:
//   - Objects created only when needed (startup cost = 0)
//   - Dependencies resolved in correct order automatically
//   - Strategy swap triggers rebuild of affected subgraph only
//   - No circular dependency risk (DAG structure enforced by construction)
```

---

## 15. Exception Hierarchy

### 15.1 Class Hierarchy

```
RuntimeException
  |
  +-- GatewayException (base)
        |
        +-- RouteNotFoundException (404)
        |     - path: String (the unmatched request path)
        |     - getMessage(): "No route found for path: /api/xyz"
        |
        +-- ServiceUnavailableException (503)
        |     - serviceName: String (the unreachable service)
        |     - getMessage(): "Service unavailable: user-service"
        |
        +-- RateLimitExceededException (429)
              - key: String (rate-limit key: client IP or API key)
              - retryAfterMs: long (when to retry)
              - getMessage(): "Rate limit exceeded for key: 192.168.1.100 (retry after 200ms)"
```

### 15.2 Exception-to-HTTP-Status Mapping

```
Exception                         HTTP Status    Response Body
------------------------------------------------------------------
RouteNotFoundException            404            {"error":"No route found for path: /api/xyz"}
AuthResult.unauthorized()         401            {"error":"Authentication failed: Missing Authorization header"}
AuthResult.forbidden()            403            {"error":"Access denied for route 'payment-route'"}
RateLimitExceededException        429            {"error":"Rate limit exceeded for route 'user-route'"}
                                                 Retry-After: 200ms
ServiceUnavailableException       503            {"error":"Circuit breaker is OPEN for 'user-service'"}
(circuit breaker open)
ServiceUnavailableException       503            {"error":"No healthy instances for service 'user-service'"}
(no healthy instances)
Upstream failure                  502            {"error":"Upstream service 'user-service' returned an error"}
```

### 15.3 Design Decisions

- **RuntimeException base:** Gateway exceptions are unchecked. Callers are not forced to catch -- the gateway pipeline handles all errors by returning appropriate HTTP responses. No checked exceptions in the pipeline.
- **Per-exception context:** Each exception carries domain-specific context (path, serviceName, key, retryAfterMs) that the error handler uses to build informative responses.
- **No exception throwing in normal pipeline:** The current GatewayService uses return-early with HttpResponse.error() instead of throwing exceptions. The exception classes exist for use by extensions and downstream consumers.

---

## 16. Controller Layer Design

### 16.1 GatewayController Endpoints

```
+-----------+----------------------------+-------------------------------+
| Method    | Endpoint                   | Handler                       |
+-----------+----------------------------+-------------------------------+
| POST      | /gateway/request           | handleRequest(HttpRequest)    |
|           |                            | -> HttpResponse               |
+-----------+----------------------------+-------------------------------+
| POST      | /gateway/routes            | registerRoute(Route)          |
|           |                            | -> void                       |
+-----------+----------------------------+-------------------------------+
| POST      | /gateway/services          | registerServiceInstance(inst) |
|           |                            | -> void                       |
+-----------+----------------------------+-------------------------------+
| GET       | /gateway/circuit-breakers  | getCircuitBreakerStatus()     |
|           |                            | -> Map<String, CircuitState>  |
+-----------+----------------------------+-------------------------------+
| GET       | /gateway/status            | getServiceStatus()            |
|           |                            | -> String (formatted)         |
+-----------+----------------------------+-------------------------------+
```

### 16.2 Controller Dependencies

```java
public class GatewayController {
    private final GatewayService gatewayService;             // for request handling + status
    private final LoadBalancerService loadBalancerService;   // for service instance registration
    private final CircuitBreakerService circuitBreakerService; // for circuit breaker queries

    // The controller is deliberately thin:
    //   1. Log the simulated HTTP method + path
    //   2. Delegate to the appropriate service
    //   3. Return the result
    //
    // No business logic in the controller.
    // No direct engine/repository access.
    // Three-dependency limit keeps it focused.
}
```

---

## 17. Display Layer Design

### 17.1 GatewayStatsDisplay Tables

```
ROUTE TABLE
================================================================================
  PATH PATTERN                   TARGET SERVICE       METHODS              RATE LIMIT   PRIORITY
  ────────────────────────────── ──────────────────── ──────────────────── ──────────── ────────
  /health                        health-check         [GET]                1000 req/s   1
  /api/payments/**               payment-service      [POST]               20 req/s     5
  /api/users/**                  user-service         [GET, POST, PUT]     100 req/s    10
  /api/orders/**                 order-service        [GET, POST]          50 req/s     10


SERVICE INSTANCES: user-service
================================================================================
  ID         HOST:PORT                 HEALTH       WEIGHT   ZONE
  ────────── ───────────────────────── ──────────── ──────── ───────────────
  user-svc-  10.0.1.10:8080            HEALTHY      3        us-east-1a
  user-svc-  10.0.1.11:8080            HEALTHY      2        us-east-1b
  user-svc-  10.0.1.12:8080            HEALTHY      5        us-west-2a


CIRCUIT BREAKER STATUS
================================================================================
  SERVICE                   STATE        FAILURES   SUCCESSES
  ───────────────────────── ──────────── ────────── ──────────
  user-service              CLOSED       0          0
  order-service             CLOSED       0          0
  flaky-service             OPEN         5          0


GATEWAY SUMMARY
================================================================================
  Routes configured:     4
  Services registered:   3
  Total instances:       6 (6 healthy)
  Circuit breakers:      3 (1 open)
```

---

## 18. Production Considerations

### 18.1 What This Design Covers vs Production Additions

```
Feature                  This Project              Production System
------------------------------------------------------------------------
Route matching           ArrayList + linear scan    Radix tree (O(path length))
Rate limiting            In-memory token bucket     Redis + Lua script (distributed)
Circuit breaker          In-memory per-JVM          Distributed CB (Resilience4j + Redis)
Service discovery        In-memory registry         Consul / etcd / K8s endpoints API
Load balancing           3 strategies               + Least Connections, Locality-Aware
mTLS                     Simulated trust set        X.509 certs, auto-rotation (Istio CA)
Auth                     Simulated JWT              RS256 with JWKS endpoint, token revocation
Config                   AppConfig factory          Spring Cloud Config / Consul KV / K8s ConfigMap
Observability            Console logging            Prometheus metrics, Jaeger tracing, ELK logging
Persistence              InMemory repositories      PostgreSQL / DynamoDB / Redis
Deployment               Single JVM                 K8s Deployment + Envoy sidecar injection
```

### 18.2 Scaling the Gateway

```
Single Gateway (this project):
  Client -> [Gateway JVM] -> Upstream

Horizontally Scaled (production):
  Client -> [Load Balancer (ALB/NLB)]
              |           |           |
              v           v           v
         [Gateway-1] [Gateway-2] [Gateway-3]
              |           |           |
              v           v           v
         [Upstream Pool: user-svc-1, user-svc-2, ...]

Key considerations:
  1. Rate limiting must be distributed (Redis) -- per-JVM buckets don't share state
  2. Circuit breaker state should be shared (or per-gateway is acceptable for most cases)
  3. Service registry is external (Consul/etcd) -- all gateways see same instances
  4. Route config is centralized (DB/ConfigMap) -- route changes propagate to all gateways
  5. Session affinity: consistent hash ensures same client hits same gateway (if needed)
```

### 18.3 Real-World Gateway Comparisons

```
Feature            Kong              Envoy (Istio)     AWS API Gateway    This Project
-------------------------------------------------------------------------------------
Routing            Radix tree        Route table        Resource/Method    ArrayList + glob
Rate Limiting      Redis plugin      Local/global       Per-stage          Token bucket
Circuit Breaker    Plugin            Built-in (outlier) N/A                State machine
Load Balancing     RR, Least Conn    RR, W, CH, LC      ALB/NLB            RR, W, CH
Auth               Plugin (JWT,      External authz     Cognito/Lambda     JWT, API Key
                   OAuth2, HMAC)     (ext_authz filter)
mTLS               Kong Mesh         Istio CA (SPIFFE)  ACM                Simulated
Traffic Shaping    Canary plugin     VirtualService     Canary (stage)     Canary, Header
Service Discovery  DNS / Consul      K8s endpoints      CloudMap           In-memory
Config Format      YAML/Admin API    xDS protocol       OpenAPI/SAM        Java AppConfig
```

### 18.4 Interview Deep-Dive Topics

```
Topic                           Key Points to Mention
--------------------------------------------------------------------
Why API Gateway?                Single entry point, cross-cutting concerns,
                                reduce duplication across microservices

Gateway vs Service Mesh?        Gateway = north-south (client to service)
                                Mesh = east-west (service to service)
                                Both handle: LB, CB, auth, observability

Token Bucket vs Sliding Window? Bucket: allows bursts, simple, O(1)
                                Sliding: precise, no burst, more memory

Circuit Breaker thresholds?     Failure threshold: 5 (too low = false trips)
                                Cooldown: 30s (too short = thrashing)
                                Success threshold: 3 (proves recovery)

Consistent Hash ring size?      150 virtual nodes per instance is standard
                                Fewer = uneven distribution
                                More = better uniformity, more memory

mTLS rotation?                  Istio CA issues short-lived certs (24h)
                                Auto-rotation via SDS (Secret Discovery Service)
                                SPIFFE identity framework for cross-cluster

Canary deployment rollback?     Monitor error rate and latency P99
                                Automated rollback if error rate > threshold
                                Istio VirtualService weight update = instant
```

---

## 19. File Count Summary

```
Package              Files   Classes/Interfaces
------------------------------------------------
model/               16      HttpRequest, HttpResponse, HttpMethod, Route,
                             ServiceInstance, RequestContext, AuthToken,
                             AuthResult, CircuitBreakerState, CircuitState,
                             HealthStatus, RateLimitResult, RetryPolicy,
                             ServiceMeshConfig, TrafficSplit, GatewayFilter

engine/              5       RequestRouter, CircuitBreakerEngine,
                             RateLimiterEngine, ServiceRegistry, TlsEngine

repository/          4       RouteRepository (I), InMemoryRouteRepository,
                             ServiceInstanceRepository (I),
                             InMemoryServiceInstanceRepository

strategy/routing/    3       RoutingStrategy (I), PathBasedRoutingStrategy,
                             HeaderBasedRoutingStrategy

strategy/lb/         4       LoadBalancingStrategy (I), RoundRobinLoadBalancer,
                             WeightedLoadBalancer, ConsistentHashLoadBalancer

strategy/auth/       3       AuthStrategy (I), JwtAuthStrategy,
                             ApiKeyAuthStrategy

strategy/traffic/    3       TrafficStrategy (I), CanaryTrafficStrategy,
                             HeaderBasedTrafficStrategy

service/             7       GatewayService (Facade), RoutingService,
                             AuthService, RateLimitService,
                             CircuitBreakerService, LoadBalancerService,
                             ServiceMeshService

controller/          1       GatewayController
config/              1       AppConfig
display/             1       GatewayStatsDisplay
exception/           4       GatewayException, RouteNotFoundException,
                             ServiceUnavailableException,
                             RateLimitExceededException
main/                1       ApiGatewayServiceMeshApp (12 demos)
------------------------------------------------
TOTAL               53      ~55 classes/interfaces/enums
```

---

## 20. Algorithm Deep Dives

### 20.1 Token Bucket Rate Limiting -- Full Algorithm

```
Data Structure:
  class TokenBucket {
      double tokens;            // fractional: 3.7 tokens is valid
      int maxTokens;            // bucket capacity (hard ceiling)
      double refillRate;        // tokens added per second
      Instant lastRefillTime;   // for elapsed time calculation
  }

refill():
  1. now = Instant.now()
  2. elapsedSeconds = (now.toEpochMilli() - lastRefillTime.toEpochMilli()) / 1000.0
  3. tokensToAdd = elapsedSeconds * refillRate
  4. if tokensToAdd > 0:
       tokens = min(maxTokens, tokens + tokensToAdd)
       lastRefillTime = now
  // Note: min() prevents exceeding capacity. Tokens don't accumulate
  // beyond maxTokens even if no requests come for a long time.

tryConsume(key):
  1. bucket = buckets.get(key)
  2. if bucket is null -> return allowed(-1)  // no limit configured
  3. synchronized(bucket):
       a. bucket.refill()     // add tokens based on elapsed time
       b. if bucket.tokens >= 1.0:
            bucket.tokens -= 1.0
            return allowed(floor(bucket.tokens))
       c. else:
            retryAfterMs = ceil(1000.0 / bucket.refillRate)
            return denied(retryAfterMs)

Complexity:
  Time:  O(1) per tryConsume() -- refill is constant-time arithmetic
  Space: O(K) where K = number of unique rate-limit keys

Comparison with other algorithms:
  +-------------------+--------+-----------+--------+---------------------+
  | Algorithm         | Burst  | Precision | Memory | Distributed-Friendly|
  +-------------------+--------+-----------+--------+---------------------+
  | Token Bucket      | Yes    | Good      | O(K)   | Yes (Redis+Lua)     |
  | Leaky Bucket      | No     | Excellent | O(K)   | Yes                 |
  | Fixed Window      | 2x     | Poor      | O(K)   | Yes                 |
  | Sliding Window Log| No     | Excellent | O(K*N) | Yes but expensive   |
  | Sliding Window Cnt| Moderate| Good     | O(K)   | Yes                 |
  +-------------------+--------+-----------+--------+---------------------+
  We chose token bucket: allows bursts (good UX), O(1) per check,
  and naturally maps to Redis + Lua for distributed rate limiting.
```

### 20.2 Consistent Hash Ring -- Full Algorithm

```
Data Structure:
  TreeMap<Integer, ServiceInstance> ring;  // sorted map = hash ring
  int virtualNodeCount = 150;             // vnodes per physical instance

buildRing(instances):
  for each instance in instances:
    for i in 0..149:
      key = instance.getId() + "-vnode-" + i
      hash = fnv1a(key)
      ring.put(hash, instance)
  // Result: ~150 * N entries in the TreeMap
  // 3 instances = 450 ring positions

selectInstance(instances, request):
  1. healthy = filter instances where isHealthy()
  2. ring = buildRing(healthy)
  3. requestHash = fnv1a(request.getPath())
  4. entry = ring.ceilingEntry(requestHash)  // first key >= requestHash
  5. if entry is null:
       entry = ring.firstEntry()  // wrap around (ring is circular)
  6. return entry.getValue()

fnv1a(key):
  hash = 0x811c9dc5         // FNV offset basis (32-bit)
  for each char c in key:
    hash = hash XOR c       // XOR with byte
    hash = hash * 0x01000193 // multiply by FNV prime
  return hash AND 0x7FFFFFFF // mask to non-negative

Why FNV-1a?
  - Fast: no crypto overhead (unlike MD5/SHA)
  - Good distribution: low collision rate for string keys
  - Deterministic: same input always produces same output
  - 32-bit is sufficient for hash ring (2^31 positions)

Why 150 virtual nodes?
  With N=3 physical instances and 150 vnodes each:
    Ring has 450 positions
    Each instance "covers" ~150/450 = 33% of the ring
    Standard deviation of load: ~2-3% (excellent uniformity)

  With only 1 vnode per instance:
    Ring has 3 positions
    Load distribution can be 10%/20%/70% (terrible)

  Industry standard: Ketama (memcached) uses 160 vnodes,
  Envoy uses 150, DynamoDB uses 100-200.

Key redistribution on instance failure:
  Before: A=150, B=150, C=150 vnodes (450 total)
  Instance B fails: remove B's 150 vnodes
  After: A=150, C=150 vnodes (300 total)
  Only B's keys remap to next clockwise node (A or C)
  A and C keep their existing traffic
  ~1/3 of total keys remap (not 100%)
```

### 20.3 Circuit Breaker State Machine -- Formal Specification

```
States: {CLOSED, OPEN, HALF_OPEN}
Initial state: CLOSED

Transitions:
  CLOSED --[failureCount >= failureThreshold]--> OPEN
    Action: trip() -> set state=OPEN, successCount=0, lastStateChange=now

  OPEN --[elapsed > openDurationMs]--> HALF_OPEN
    Action: halfOpen() -> set state=HALF_OPEN, successCount=0, failureCount=0

  HALF_OPEN --[successCount >= successThreshold]--> CLOSED
    Action: reset() -> set state=CLOSED, failureCount=0, successCount=0

  HALF_OPEN --[any failure]--> OPEN
    Action: trip() -> set state=OPEN, successCount=0

Events:
  recordSuccess():
    CLOSED:    failureCount = 0 (reset on success)
    HALF_OPEN: successCount++ (progress toward recovery)
    OPEN:      no-op (requests should not reach this state)

  recordFailure():
    CLOSED:    failureCount++ (may trigger trip)
    HALF_OPEN: trip() immediately (probe failed)
    OPEN:      no-op (already open)

  allowRequest():
    CLOSED:    return true
    OPEN:      if shouldAttemptReset() -> halfOpen(), return true
               else -> return false
    HALF_OPEN: return true (probe request)

Configuration:
  failureThreshold  = 5      (trips after 5 consecutive failures)
  successThreshold  = 3      (recovers after 3 consecutive successes)
  openDurationMs    = 30,000 (30 seconds cooldown before probing)

Production tuning guidelines:
  - High-traffic service: failureThreshold=10, openDuration=60s
    (fewer false trips, longer recovery window)
  - Low-traffic service: failureThreshold=3, openDuration=15s
    (faster detection, faster recovery attempt)
  - Critical path: successThreshold=5, openDuration=120s
    (more confidence before closing, longer safety window)
```

### 20.4 Route Matching -- Priority and Specificity

```
Route Table (stored in priority-sorted ArrayList):

  Priority 1:  /health                  -> health-check       (exact)
  Priority 5:  /api/payments/**         -> payment-service    (wildcard)
  Priority 10: /api/users/**            -> user-service       (wildcard)
  Priority 10: /api/orders/**           -> order-service      (wildcard)
  Priority 100: /**                     -> fallback-service   (catch-all)

Matching algorithm:

  match(request):
    for each route in prioritySortedList:
      if !route.isEnabled() -> skip
      if !route.getMethods().contains(request.getMethod()) -> skip
      if matchesPath(route.getPathPattern(), request.getPath()) -> return route
    return empty

  matchesPath(pattern, path):
    Case 1: Exact match
      "/health" == "/health" -> true
      "/health" == "/health/" -> false (strict)

    Case 2: ** wildcard (multi-segment)
      "/api/users/**" -> strip "/**" -> prefix = "/api/users"
      Check: path.equals(prefix) OR path.startsWith(prefix + "/")
      "/api/users" -> true (equals prefix)
      "/api/users/123" -> true (startsWith "/api/users/")
      "/api/users/123/orders" -> true (startsWith "/api/users/")
      "/api/usersx" -> false (startsWith "/api/users/" fails)

    Case 3: * wildcard (single-segment)
      "/api/users/*" -> regex: "/api/users/[^/]+"
      "/api/users/123" -> matches (one segment)
      "/api/users/123/orders" -> no match (two segments)

  Resolution order:
    1. Priority value (lower = checked first)
    2. First match wins (within same priority, insertion order)

  Example: GET /api/users/123
    Check /health (priority 1)      -> path mismatch      -> skip
    Check /api/payments/** (pri 5)  -> prefix mismatch    -> skip
    Check /api/users/** (pri 10)    -> prefix match       -> RETURN
    (never reaches /api/orders/** or /**)

  Example: DELETE /api/users/123
    Check /api/users/** (pri 10)    -> methods={GET,POST,PUT}
                                    -> DELETE not in set  -> skip
    Check /** (pri 100)             -> catch-all matches  -> RETURN fallback
```

### 20.5 Weighted Random Selection -- Shared Algorithm

```
Used by: WeightedLoadBalancer, CanaryTrafficStrategy, TrafficSplit.selectVersion()

Algorithm:
  1. entries = [("A", weight=3), ("B", weight=1), ("C", weight=6)]
  2. totalWeight = 3 + 1 + 6 = 10
  3. random = ThreadLocalRandom.nextInt(10)  // [0, 10)
  4. accumulated = 0
  5. Walk entries:
       accumulated += 3 = 3   -> if 3 > random -> select A  (random in [0,2])
       accumulated += 1 = 4   -> if 4 > random -> select B  (random = 3)
       accumulated += 6 = 10  -> if 10 > random -> select C (random in [4,9])

  Distribution over 10000 calls:
    A: ~3000 (30%)
    B: ~1000 (10%)
    C: ~6000 (60%)

  Proof of correctness:
    P(A) = weight(A) / totalWeight = 3/10 = 30%
    P(B) = weight(B) / totalWeight = 1/10 = 10%
    P(C) = weight(C) / totalWeight = 6/10 = 60%

  Complexity: O(N) per selection where N = number of entries
  For small N (3-10 instances or versions), this is negligible.
  For large N, a binary search on prefix sums gives O(log N).

  ThreadLocalRandom is used (not Random) because:
    - No contention between threads (each thread has its own)
    - Faster than synchronized Random
    - Sufficient randomness for load balancing
```

---

## 21. Interview Deep-Dive Questions and Answers

### Q: Why separate Gateway (north-south) from Service Mesh (east-west)?

```
Gateway handles client-to-service traffic:
  - Authentication (JWT, API key)
  - Rate limiting (per-client)
  - API versioning and routing
  - Request/response transformation
  - Public-facing security (WAF, DDoS protection)

Service Mesh handles service-to-service traffic:
  - mTLS (zero-trust between services)
  - Circuit breaking (failure isolation)
  - Load balancing (instance selection)
  - Distributed tracing (propagate traceId)
  - No authentication (services are already trusted inside the mesh)

In our design:
  GatewayService = north-south (handles external requests)
  ServiceMeshService = east-west (handles internal requests)
  Both share CircuitBreakerService and LoadBalancerService.
```

### Q: How would you make rate limiting distributed?

```
Current: In-memory token bucket (per-JVM)
  Problem: 3 gateway instances each allow 100 req/s = 300 req/s total

Solution: Redis + Lua script
  1. Store token bucket state in Redis (key, tokens, lastRefill)
  2. Lua script executes atomically:
     - Read current tokens
     - Calculate refill based on elapsed time
     - Consume token if available
     - Return result
  3. All gateway instances share the same Redis bucket
  4. Total rate limit is enforced globally (100 req/s across all gateways)

Lua script ensures atomicity:
  Redis executes Lua scripts in a single thread.
  No race conditions between gateway instances.
  Latency: ~1ms per rate limit check (Redis round-trip).

This is the Stripe/Cloudflare pattern for distributed rate limiting.
```

### Q: What if the circuit breaker is too aggressive (false trips)?

```
Tuning strategies:
  1. Increase failureThreshold: 5 -> 10 (need more failures to trip)
  2. Use failure percentage instead of count: trip at 50% failure rate
  3. Use sliding window: count failures in last 60s, not all-time consecutive
  4. Exclude expected errors: 400s are client errors, not service failures
  5. Health check probes: separate health endpoint, not relying on real traffic
  6. Per-endpoint breakers: /api/users/search might fail but /api/users/:id is fine

Our implementation uses consecutive failure count (simplest model).
Production systems (Resilience4j, Envoy) use sliding window + percentage.
```

### Q: How does consistent hash handle hot keys?

```
Problem: /api/users/popular-user-123 gets 1000x more traffic than average.
Consistent hash routes it to the same instance every time -> overload.

Solutions:
  1. Key prefix hashing: hash on /api/users/* (remove specific ID)
     Spreads user requests across instances but loses cache affinity.

  2. Replicated hot keys: detect hot keys, replicate their cache to all
     instances. Route hot keys with round-robin instead of hash.

  3. Jittered hashing: add random suffix to hot key to distribute.
     Trades cache affinity for load balance.

  4. Two-tier: consistent hash for normal traffic, round-robin fallback
     when an instance exceeds load threshold.

Our implementation hashes on request.getPath() which is reasonable
for most API traffic. Hot key detection would be an extension.
```
