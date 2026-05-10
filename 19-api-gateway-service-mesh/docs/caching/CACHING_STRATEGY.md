# Caching Strategy -- API Gateway & Service Mesh

> Every cache layer in the system, from route table caching and service instance
> discovery caches to rate limit state in Redis, JWT public key caching, response
> caching at the gateway, and circuit breaker state. Interview-ready with Redis
> commands, TTL policies, invalidation strategies, and the full request flow
> through all cache layers.
>
> **Key insight:** Unlike most systems where Redis is "the cache," in an API
> gateway **the route table is THE hot cache**. Every single request hits the
> route table -- it must be in-memory with O(1) or O(log N) lookup. Redis
> handles distributed state (rate limits, circuit breakers), while JWK public
> keys and response caches reduce calls to external systems.

---

## Cache Layer Overview

```
  GET /api/users/123
       |
       v
  +-------------------+
  | Route Table Cache  |  Tier 1: in-memory, 100% hit rate      <-- THE HOT PATH
  | (Radix Tree /      |  Rebuilt on config change (xDS push)
  |  Sorted ArrayList) |  Every request hits this cache
  +-------------------+
       | matched route
       v
  +-------------------+
  | JWT Public Key     |  Tier 2: in-memory, TTL = 1 hour
  | Cache (JWKS)       |  Key: kid -> RSA PublicKey
  +-------------------+  Miss: fetch from /.well-known/jwks.json
       | validated token
       v
  +-------------------+
  | Rate Limit State   |  Tier 3: Redis, per-key token bucket
  | (Redis Hash)       |  Key: rl:{routeId}:{clientId}
  +-------------------+  TTL: 1 hour (auto-expire stale keys)
       | within limit
       v
  +-------------------+
  | Circuit Breaker    |  Tier 4: in-memory (local) + Redis (distributed)
  | State Cache        |  Key: cb:{serviceName}
  +-------------------+  State: CLOSED / OPEN / HALF_OPEN
       | circuit allows
       v
  +-------------------+
  | Service Instance   |  Tier 5: in-memory, refreshed via heartbeats
  | Cache (Registry)   |  Key: serviceName -> List<ServiceInstance>
  +-------------------+  Stale entries evicted after 30s no heartbeat
       | instance selected
       v
  +-------------------+
  | Response Cache     |  Tier 6: optional, for idempotent GETs
  | (Gateway-level)    |  Key: hash(method + path + queryParams + varyHeaders)
  +-------------------+  TTL: per-route (5s to 5min)
       | cache hit? → return cached response
       | cache miss → forward to upstream
       v
  +-------------------+
  | Upstream Service   |
  +-------------------+
```

---

## 1. Route Table Cache -- The Hot Path

**Every single request hits the route table.** This is the most critical cache
in the entire gateway. It must be in-memory, sorted, and fast.

### What We Cache

| Cached Data | Structure | Size | Update Frequency |
|-------------|-----------|------|-----------------|
| Route definitions | Sorted list by priority (our `RequestRouter.routes`) | ~100-1000 routes | On config change (minutes/hours) |
| Path patterns | String patterns with `**` wildcard | O(R * L) R=routes, L=avg path length | Same as routes |
| Target service mapping | Route -> service name | O(R) | Same as routes |
| Method filters | EnumSet per route | O(R) | Same as routes |
| Rate limit config per route | int `rateLimitPerSecond` embedded in Route | O(R) | Same as routes |

### Cache Strategy

```
Route Table Cache Strategy:
  Type:     In-memory (JVM heap)
  Pattern:  Read-through (routes always in memory)
  TTL:      No TTL -- explicit invalidation on config change
  Size:     ~100 KB for 1000 routes (negligible)
  Hit rate: 100% (all routes loaded at startup)
  Update:   Push-based via xDS (Envoy), Admin API (Kong), reload (Nginx)

Our implementation: RequestRouter.routes (ArrayList<Route>)
Production equivalent:
  - Kong: Nginx shared memory + LuaJIT FFI
  - Envoy: RDS (Route Discovery Service) from control plane
  - Nginx: radix tree built at config reload time
```

### Route Matching Performance

```
Our simulation (linear scan):
  routes.stream()
      .filter(Route::isEnabled)
      .filter(route -> route.getMethods().contains(request.getMethod()))
      .filter(route -> matchesPath(route.getPathPattern(), request.getPath()))
      .findFirst();

  Time: O(R) where R = number of routes
  For 100 routes: ~100 comparisons per request (fine for simulation)

Production (radix tree / trie):
  Nginx: ngx_http_core_find_location() — radix tree lookup
    Time: O(L) where L = path length (typically 20-50 chars)
    For 10,000 routes: still only ~30 comparisons per request

  Envoy: prefix tree with longest-match semantics
    Time: O(L) path length
    Falls back to regex matching if no prefix match (slower)

Optimization path:
  ArrayList O(R) → HashMap O(1) exact match → Trie O(L) prefix match → Radix tree O(L) compressed
```

### Invalidation Strategy

```
Route table invalidation:

  Kong:
    1. Admin API receives route update (POST/PUT/DELETE)
    2. Writes to PostgreSQL (or declarative file reload)
    3. Broadcasts invalidation event to all Kong nodes (cluster events)
    4. Each node rebuilds its in-memory route table
    5. Latency: ~1-5 seconds for propagation

  Envoy/Istio:
    1. Operator applies VirtualService CRD (kubectl apply)
    2. istiod detects CRD change (k8s watch)
    3. istiod pushes new RDS config to all Envoy sidecars (xDS gRPC stream)
    4. Envoy atomically swaps route table (no dropped requests)
    5. Latency: ~1-3 seconds for xDS propagation

  Nginx:
    1. Config file updated on disk
    2. `nginx -s reload` sends HUP signal
    3. New worker processes start with new config
    4. Old workers drain existing connections and exit
    5. Latency: ~100ms for reload (but requires file update first)

Our simulation:
    routingService.registerRoute(route)  → adds to sorted list immediately
    routingService.removeRoute(routeId)  → removes by ID immediately
    No propagation delay (single JVM)
```

---

## 2. Service Instance Cache (Service Discovery)

**The service instance cache maps logical service names to physical endpoints.**
It must be fresh enough to avoid routing to dead instances, but not so
aggressively refreshed that it overwhelms the discovery system.

### What We Cache

| Cached Data | Structure | Size | TTL |
|-------------|-----------|------|-----|
| Service name -> instances list | `ConcurrentHashMap<String, List<ServiceInstance>>` | O(S * I) S=services, I=avg instances | Until heartbeat timeout (30s) |
| Instance health status | `HealthStatus` enum per instance | O(1) per instance | Updated on every health check (5s) |
| Instance weight | int per instance | O(1) per instance | Static (set at registration) |
| Instance zone | String per instance | O(1) per instance | Static (set at registration) |

### Cache Strategy

```
Service Instance Cache Strategy:
  Type:     In-memory (JVM heap)
  Pattern:  Write-through (register/deregister updates immediately)
  TTL:      Implicit -- stale instances evicted after 30s no heartbeat
  Size:     ~1 KB per instance * 1000 instances = ~1 MB
  Hit rate: 100% (all instances loaded in memory)
  Staleness: Up to 30 seconds (heartbeat interval)

Our implementation: ServiceRegistry.instances (ConcurrentHashMap)
Production equivalent:
  - Consul: local agent cache + blocking queries (long-poll)
  - Envoy: EDS endpoint list from control plane (xDS push)
  - Kubernetes: Endpoints/EndpointSlice objects (watch API)
```

### Cache Refresh Patterns

```
Pattern 1: Pull-based (Consul blocking query)
  +---------+      +----------+      +---------+
  | Gateway |----->| Consul   |----->| Consul  |
  | (cache) |      | Agent    |      | Server  |
  |         |<-----| (local)  |<-----| (Raft)  |
  +---------+      +----------+      +---------+

  1. Gateway sends: GET /v1/health/service/user-service?index=42&wait=30s
  2. Consul blocks until index changes or 30s timeout
  3. On change: returns new instance list immediately
  4. Gateway updates local cache
  5. Latency: near-zero for changes (push-via-long-poll)

Pattern 2: Push-based (Envoy EDS)
  +---------+      +-----------+     +---------+
  | Envoy   |<-----| istiod    |<----| k8s     |
  | sidecar |  xDS | (control  |watch| API     |
  | (cache) |gRPC  | plane)    |     | server  |
  +---------+      +-----------+     +---------+

  1. Envoy establishes gRPC stream to istiod
  2. istiod watches k8s Endpoints/EndpointSlice
  3. On pod scale-up/down: k8s updates Endpoints
  4. istiod pushes EDS update to all connected Envoys
  5. Envoy atomically updates endpoint list
  6. Latency: 1-3 seconds from pod change to route update

Pattern 3: Heartbeat-based (our implementation)
  +---------+      +-----------+
  | Gateway |<-----| Service   |
  | Registry|      | Instance  |
  | (cache) |      | heartbeat |
  +---------+      +-----------+

  1. Service instance sends heartbeat every 10s
  2. Registry updates lastHeartbeat timestamp
  3. Background task runs evictStale(Duration.ofSeconds(30)) every 10s
  4. Instances with no heartbeat for 30s are evicted
  5. Staleness: up to 30 seconds for a dead instance
```

### Staleness vs Availability Tradeoff

```
Aggressive eviction (5s timeout):
  + Dead instances removed quickly (fewer 502 errors)
  - Network blips cause false evictions (healthy instances removed)
  - Thundering herd: all instances briefly evicted during network partition

Conservative eviction (60s timeout):
  + Tolerates network blips (no false evictions)
  - Dead instances receive traffic for up to 60 seconds
  - Circuit breaker compensates (trips after 5 failures)

Our default (30s timeout):
  Balanced: tolerates brief network issues, removes genuinely dead instances
  Circuit breaker acts as secondary protection (trips in ~5 failed requests)

Production recommendation:
  - Active health checks: 5s interval, 3 consecutive failures → mark unhealthy
  - Passive health checks: track actual request failures → outlier detection
  - Combine both: active gives baseline, passive catches application errors
```

---

## 3. Rate Limit State in Redis

**Distributed rate limiting state stored in Redis.** Each gateway instance
must share rate limit counters to enforce global limits. Redis provides
the atomic operations needed for accurate distributed counting.

### What We Cache

| Cached Data | Redis Key Pattern | Type | TTL |
|-------------|-------------------|------|-----|
| Token bucket state | `rl:{routeId}:{clientId}` | Hash (tokens, last_refill) | 1 hour |
| Sliding window entries | `rlw:{routeId}:{clientId}` | Sorted Set (timestamp scores) | window_size + 1s |
| Global route counter | `rl:route:{routeId}` | Hash (tokens, last_refill) | 1 hour |
| Per-client counter | `rl:client:{clientId}` | Hash (tokens, last_refill) | 1 hour |

### Cache Strategy

```
Rate Limit Cache Strategy:
  Type:     Redis (distributed)
  Pattern:  Read-modify-write (atomic Lua script)
  TTL:      1 hour (auto-expire idle keys)
  Size:     ~200 bytes per key * 100K active keys = ~20 MB
  Hit rate: N/A (every check is a read+write)
  Staleness: None (Redis is the source of truth)

Our implementation: RateLimiterEngine.buckets (ConcurrentHashMap<String, TokenBucket>)
  - Single JVM, not distributed
  - synchronized(bucket) for thread safety

Production equivalent: Redis Hash + Lua script
  - Distributed across all gateway instances
  - Atomic via Redis single-threaded execution
  - Lua script: HMGET → compute → HMSET (all in one round trip)
```

### Redis Rate Limit Key Layout

```
Example: Route "user-api" with per-client limits

Key: rl:route:user-api
  Hash fields:
    tokens     = 87.3        # current available tokens
    last_refill = 1700000000  # epoch ms of last refill
  TTL: 3600s (1 hour)

Key: rl:client:192.168.1.100
  Hash fields:
    tokens     = 42.0
    last_refill = 1700000000
  TTL: 3600s (1 hour)

Memory per key: ~200 bytes (hash overhead + 2 fields)
Total for 100K clients: ~20 MB Redis memory

Redis Cluster sharding:
  - Rate limit keys distribute across hash slots
  - rl:client:192.168.1.100 → hash slot 8432 → shard 2
  - rl:client:10.0.0.50     → hash slot 1247 → shard 0
  - Each gateway instance can talk to any shard
```

### Failure Handling: What If Redis Is Down?

```
Option A: Fail OPEN (allow all requests -- our recommendation for most cases)
  if (redisUnavailable) {
      return RateLimitResult.allowed(Integer.MAX_VALUE);
  }
  Rationale: Brief Redis downtime should not block all API traffic.
  Risk: Abuse during Redis outage (mitigated by short outage duration).

Option B: Fail CLOSED (deny all requests)
  if (redisUnavailable) {
      return RateLimitResult.denied(retryAfterMs);
  }
  Rationale: Protect upstream services at all costs.
  Risk: All legitimate traffic blocked during Redis outage.

Option C: Fail to LOCAL (use in-process fallback)
  if (redisUnavailable) {
      return localRateLimiter.tryConsume(key);  // per-instance limit
  }
  Rationale: Best of both worlds -- still rate limiting, but per-instance.
  Risk: N gateway instances → effective rate is N * per-instance limit.
  Implementation: Our RateLimiterEngine IS this local fallback.

Production recommendation:
  Primary: Redis Cluster (3+ nodes, automatic failover)
  Fallback: Local token bucket (RateLimiterEngine)
  Strategy: Fail to local, log warning, alert on Redis health
```

---

## 4. JWT Validation Cache (Public Key Cache)

**JWKS (JSON Web Key Set) public keys cached to avoid HTTP fetch on every
request.** JWT tokens are verified on every API call. Fetching the public key
from the auth provider on every request would add ~50-200ms latency. Caching
the JWKS response reduces this to zero for cached keys.

### What We Cache

| Cached Data | Cache Key | Type | TTL |
|-------------|-----------|------|-----|
| JWKS response (all keys) | Issuer URL | In-memory map | 1 hour |
| Individual public key | `kid` (Key ID) | In-memory map | 1 hour |
| Parsed RSA/EC public key object | `kid` | Java PublicKey object | 1 hour |

### Cache Strategy

```
JWT Public Key Cache Strategy:
  Type:     In-memory (JVM heap)
  Pattern:  Cache-aside with lazy load
  TTL:      1 hour (configurable per issuer)
  Size:     ~2 KB per key * 5 keys = ~10 KB (negligible)
  Hit rate: 99.99% (key rotation happens every 24-90 days)
  Staleness: Up to 1 hour (but keys overlap during rotation)

Our implementation: JwtAuthStrategy validates token in-memory (HMAC-SHA256)
  - Simulates the validation flow, but uses symmetric key (HMAC)
  - Production uses asymmetric keys (RSA/EC) from JWKS endpoint

Production equivalent:
  - Envoy: jwt_authn filter with cache_duration: 600s
  - Kong: JWT plugin with JWKS URL + automatic refresh
  - Custom: Caffeine/Guava cache with kid -> PublicKey mapping
```

### JWKS Cache Flow

```
Request #1 (cold cache):
  1. Extract JWT header: { "kid": "key-2024-01", "alg": "RS256" }
  2. Look up kid "key-2024-01" in cache → MISS
  3. Fetch: GET https://auth.example.com/.well-known/jwks.json
     Response: { "keys": [{ "kid": "key-2024-01", "n": "...", "e": "AQAB" }] }
     Latency: 50-200ms (external HTTP call)
  4. Parse all keys from JWKS response into PublicKey objects
  5. Store in cache: { "key-2024-01" -> RSAPublicKey, "key-2024-02" -> RSAPublicKey }
  6. Verify JWT signature with cached key
  Total latency: 50-200ms (JWKS fetch dominates)

Request #2-1,000,000 (warm cache):
  1. Extract JWT header: { "kid": "key-2024-01", "alg": "RS256" }
  2. Look up kid "key-2024-01" in cache → HIT
  3. Verify JWT signature with cached key
  Total latency: < 0.1ms (crypto verification only)

Key rotation:
  1. Auth provider adds new key "key-2024-02" to JWKS endpoint
  2. New tokens signed with "key-2024-02"
  3. Gateway receives token with "kid": "key-2024-02"
  4. Cache miss for "key-2024-02" → fetch JWKS
  5. Cache now has both keys (old tokens still validate)
  6. After 90 days: auth provider removes "key-2024-01" from JWKS
  7. Old tokens with "key-2024-01" fail validation (expired anyway)
```

### Cache Invalidation Triggers

```
1. TTL expiry (primary):
   Cache entry expires after 1 hour → next request fetches fresh JWKS

2. Unknown kid (reactive):
   Token presents kid not in cache → fetch JWKS immediately
   Prevents: new key not yet cached from causing auth failures
   Guard: rate-limit JWKS fetches to 1 per minute (prevent abuse)

3. Verification failure (proactive):
   Signature verification fails → might be a rotated key
   Fetch JWKS once → retry verification
   If still fails → genuinely invalid token (401)

4. Forced refresh (operational):
   Admin endpoint to flush JWKS cache (emergency key rotation)
   POST /admin/cache/jwks/flush
```

### Security Considerations

```
MUST cache:
  - Public keys (JWKS) — safe to cache, they are public
  - Issuer configuration (/.well-known/openid-configuration)

MUST NOT cache:
  - JWT tokens themselves (each has unique claims and expiry)
  - Token validation RESULTS (token could be revoked)
  - Private keys (never leaves the auth server)

Exception -- short-lived validation result cache:
  Some gateways cache "token X is valid" for 30-60 seconds
  Risk: revoked token accepted for up to 60 seconds
  Mitigated: short TTL + token blacklist check

Our AuthResult:
  We do NOT cache AuthResult — every request runs full validation
  This matches the recommended production approach
```

---

## 5. Response Cache at the Gateway

**Optional gateway-level response caching for idempotent GET requests.**
Caching API responses at the gateway avoids forwarding to upstream services,
reducing latency and upstream load.

### What We Cache

| Cached Data | Cache Key | Type | TTL |
|-------------|-----------|------|-----|
| Full HTTP response (status + headers + body) | `hash(method + path + queryParams + varyHeaders)` | In-memory or Redis | Per-route (5s to 5min) |
| Response metadata | Content-Type, Content-Length, ETag | Part of cached response | Same as response |
| Cache-Control directives | Parsed from upstream response | Per-response | Embedded in response |

### Cache Strategy

```
Response Cache Strategy:
  Type:     In-memory (L1, per-instance) + Redis (L2, shared)
  Pattern:  Cache-aside with HTTP cache semantics
  TTL:      Per-route configuration (default: no caching)
  Size:     L1: 100 MB per gateway instance, L2: 1 GB Redis
  Hit rate: 30-80% depending on API pattern (high for catalog, low for user-specific)
  Staleness: Controlled by Cache-Control headers

Our implementation: Not explicitly implemented (responses not cached)
Production equivalent:
  - Kong: proxy-cache plugin (memory or Redis)
  - AWS API Gateway: built-in response caching (0.5-237 GB)
  - Nginx: proxy_cache with shared memory zone
  - Envoy: HTTP cache filter (experimental)
```

### Response Cache Decision Flow

```
Request arrives at gateway:
  |
  v
Is it a GET or HEAD request?
  | No → BYPASS cache (POST, PUT, DELETE, PATCH are not cached)
  | Yes
  v
Does the route have caching enabled?
  | No → BYPASS cache
  | Yes
  v
Does the request have Cache-Control: no-cache?
  | Yes → BYPASS cache (client demands fresh)
  | No
  v
Does the request have an Authorization header?
  | Yes → Is route marked as "cache-with-auth"?
  |   | No → BYPASS cache (personalized response)
  |   | Yes → Continue (shared cache for authorized users)
  | No → Continue
  v
Compute cache key:
  key = SHA256(method + path + sortedQueryParams + Vary headers)
  Example: SHA256("GET:/api/products/123:?fields=name,price:Accept:application/json")
  |
  v
Look up key in L1 (in-memory):
  | HIT and not expired → return cached response (< 0.1ms)
  | MISS → check L2 (Redis)
  v
Look up key in L2 (Redis):
  | HIT and not expired → return cached response, populate L1 (~1ms)
  | MISS → forward to upstream
  v
Forward to upstream service:
  |
  v
Check upstream response:
  Is status 200, 301, or 404? (cacheable statuses)
  Does response have Cache-Control: no-store?
    | Yes → return response, do NOT cache
    | No → cache response with TTL from route config or Cache-Control max-age
  Store in L1 and L2
  Return response to client
```

### Cache Key Design

```
Bad cache key (too broad):
  key = "/api/users/123"
  Problem: different Accept headers (JSON vs XML) serve same cached response
  Problem: different API versions get mixed responses

Good cache key (includes Vary dimensions):
  key = SHA256(
    method    = "GET"
    path      = "/api/users/123"
    query     = "fields=name,email"  (sorted)
    Accept    = "application/json"
    X-Api-Version = "v2"
    Authorization = ""  (empty if not caching per-user)
  )
  Result: "a3f2b8c1d4e5..." (unique per request variant)

Vary header handling:
  Upstream responds with: Vary: Accept, Accept-Encoding
  Gateway includes Accept and Accept-Encoding values in cache key
  Same path with Accept: application/json → different cache entry than Accept: text/xml
```

### Response Cache Configuration (per route)

```
Kong proxy-cache plugin:
  routes:
    - name: product-catalog
      paths: ["/api/products/**"]
      plugins:
        - name: proxy-cache
          config:
            strategy: memory           # or "redis"
            content_type:
              - application/json
            cache_ttl: 300             # 5 minutes
            cache_control: true        # respect Cache-Control headers
            memory:
              dictionary_name: response_cache
            vary_headers:
              - Accept
            response_code:
              - 200
              - 301

Nginx proxy_cache:
  proxy_cache_path /tmp/nginx_cache levels=1:2 keys_zone=api_cache:10m
                   max_size=1g inactive=60m;
  
  location /api/products/ {
      proxy_cache api_cache;
      proxy_cache_valid 200 5m;
      proxy_cache_valid 404 1m;
      proxy_cache_key "$request_method$request_uri$http_accept";
      proxy_cache_use_stale error timeout updating;
      add_header X-Cache-Status $upstream_cache_status;
  }
```

---

## 6. Circuit Breaker State Cache

**Circuit breaker state determines whether requests to a service are allowed.**
The state machine (CLOSED -> OPEN -> HALF_OPEN -> CLOSED) must be consistent
enough to prevent cascading failures, but fast enough to not add latency.

### What We Cache

| Cached Data | Location | Type | Staleness |
|-------------|----------|------|-----------|
| Circuit state (CLOSED/OPEN/HALF_OPEN) | In-memory (local) | Enum | None (local decisions) |
| Failure count | In-memory (local) | int | Per-instance (not global) |
| Success count (half-open) | In-memory (local) | int | Per-instance (not global) |
| Last state change timestamp | In-memory (local) | long (epoch ms) | Per-instance |
| Distributed state (optional) | Redis Hash | Hash | ~1 second (Redis latency) |

### Cache Strategy: Local vs Distributed

```
Strategy 1: LOCAL ONLY (our implementation -- recommended for most cases)
  +---------------+     +---------------+
  | Gateway #1    |     | Gateway #2    |
  | CB: user-svc  |     | CB: user-svc  |
  | state: OPEN   |     | state: CLOSED |  ← different state is OK!
  | failures: 7   |     | failures: 2   |
  +---------------+     +---------------+

  Pros:
    - Zero additional latency (in-memory check)
    - No Redis dependency for circuit breaking
    - Each instance detects failures independently
  Cons:
    - Instance #1 might be OPEN while #2 is CLOSED
    - Total failure threshold = N * per-instance threshold
  
  Why it works:
    If a service is truly down, ALL gateway instances will independently
    detect the failures and trip their circuits. The delay is at most
    (threshold * inter-request-time) per instance.

Strategy 2: DISTRIBUTED (Redis-backed)
  +---------------+     +---------------+
  | Gateway #1    |     | Gateway #2    |
  | CB: (Redis)   |<--->| CB: (Redis)   |
  +-------+-------+     +-------+-------+
          |                     |
          v                     v
    +----------------------------+
    | Redis                      |
    | cb:user-svc                |
    |   state: OPEN              |  ← single source of truth
    |   failures: 7              |
    |   opened_at: 1700000000   |
    +----------------------------+

  Pros:
    - Consistent state across all gateway instances
    - Faster detection (failures aggregated globally)
    - Trip threshold applies globally (5 failures total, not 5 per instance)
  Cons:
    - Redis latency added to every request (~1ms)
    - Redis failure = circuit breaker failure
    - More complex implementation

Strategy 3: HYBRID (recommended for production)
  Local check first (0ms latency):
    if (localState == OPEN && !shouldAttemptReset()) → REJECT immediately
    if (localState == CLOSED) → ALLOW, record result locally

  Periodic sync to Redis (every 1-5 seconds):
    Push local failure/success counts to Redis
    Pull global state from Redis
    Update local state if Redis says OPEN

  Benefit: near-zero latency + eventual global consistency
```

### Our Implementation (Local Circuit Breaker)

```
CircuitBreakerEngine (in-memory):
  ConcurrentHashMap<String, CircuitBreakerState> breakers

  Per-service state machine:
    CLOSED:
      - failureCount tracks consecutive failures
      - failureCount >= FAILURE_THRESHOLD (5) → transition to OPEN
      - successCount not tracked (reset failureCount on success)

    OPEN:
      - All requests rejected immediately (503 Service Unavailable)
      - openedAt timestamp recorded
      - After OPEN_DURATION_MS (30s) → transition to HALF_OPEN

    HALF_OPEN:
      - Allow limited requests through (testing recovery)
      - successCount tracks consecutive successes
      - successCount >= SUCCESS_THRESHOLD (3) → transition to CLOSED
      - Any failure → transition back to OPEN

  Complexity:
    allowRequest():  O(1)
    recordSuccess(): O(1)
    recordFailure(): O(1)
    Space: O(S) where S = number of services
```

### When to Use Distributed vs Local

```
Use LOCAL circuit breaker when:
  - Gateway instances: < 10
  - Request rate per instance: > 100 RPS (each instance quickly detects failures)
  - Redis is not available or adds unacceptable latency
  - Services fail completely (all instances see failures)

Use DISTRIBUTED circuit breaker when:
  - Gateway instances: > 50 (per-instance thresholds too lenient)
  - Intermittent failures (only some requests fail, not all)
  - Strict failure budget (exactly 5 failures, not 5 * N)
  - Multi-region deployments sharing circuit state

Our simulation: LOCAL (CircuitBreakerEngine with ConcurrentHashMap)
  - Perfect for demonstrating the state machine pattern
  - Maps to Envoy's per-sidecar outlier detection (also local)
```

---

## 7. What NOT to Cache

**Some data should never be cached at the gateway, or has caching constraints
that make it dangerous.**

### Never Cache These

| Data | Why Not Cache | Risk |
|------|--------------|------|
| POST/PUT/DELETE responses | Not idempotent -- caching could replay side effects | Duplicate orders, double payments |
| Responses with `Set-Cookie` | Session cookies must reach the client fresh | Session fixation, auth bypass |
| Responses with `Cache-Control: no-store` | Upstream explicitly said "do not cache" | Compliance violation, stale data |
| Private/personalized responses | User-specific data cached under shared key | Data leak between users |
| Streaming responses (chunked/SSE) | Partial data cached as complete response | Corrupt data served to clients |
| Error responses (500, 502, 503) | Transient errors cached as permanent state | Service appears down after recovery |
| JWT tokens | Each token is unique with specific claims | Token reuse, privilege escalation |
| Rate limit decisions | Must be computed per-request for accuracy | Rate limit bypass |
| Write-path circuit breaker state | Stale state = wrong routing decisions | Traffic to failing services |

### Dangerous Cache Patterns (Anti-Patterns)

```
Anti-pattern 1: Caching auth decisions
  +---------+    +-------+
  | Gateway |    | Cache |
  | auth()  |--->| "user-123 → valid" (TTL: 60s) |
  +---------+    +-------+

  Problem: User revoked at second 5, but cache says "valid" until second 60.
  Fix: Never cache auth decisions. Always validate JWT (signature check is fast).
  Exception: JWKS public keys ARE safe to cache (they are public).

Anti-pattern 2: Caching POST responses
  POST /api/orders
  Response: { "orderId": "order-789", "status": "created" }
  Cached!  ← WRONG

  Next POST /api/orders (different user):
  Returns cached: { "orderId": "order-789" }  ← WRONG ORDER!
  Fix: Never cache POST/PUT/DELETE. Check method before caching.

Anti-pattern 3: Shared cache key without Vary
  GET /api/users/me  (Authorization: Bearer token-alice)
  Response: { "name": "Alice", "email": "alice@..." }
  Cache key: "/api/users/me"  ← missing user dimension!

  GET /api/users/me  (Authorization: Bearer token-bob)
  Cache HIT → returns Alice's data to Bob!  ← DATA LEAK
  Fix: Include Authorization (or user ID) in cache key, or do not cache /me endpoints.

Anti-pattern 4: Caching 503 errors
  user-service is temporarily down → 503 response
  Gateway caches 503 for 5 minutes
  user-service recovers after 30 seconds
  All requests see cached 503 for remaining 4.5 minutes!
  Fix: Never cache 5xx responses, or use very short TTL (1-5 seconds).
```

### Cache Bypass Rules

```
Bypass cache when:
  1. Request method is not GET or HEAD
  2. Request has Authorization header (unless route opts in)
  3. Request has Cache-Control: no-cache or Pragma: no-cache
  4. Route does not have caching enabled
  5. Response status is not in cacheable set (200, 301, 404)
  6. Response has Cache-Control: no-store or private
  7. Response has Set-Cookie header
  8. Response body > max cacheable size (e.g., 10 MB)
  9. Response is streaming (Transfer-Encoding: chunked without Content-Length)

These rules applied in order — first match = bypass.
```

---

## 8. Cache Warming and Preloading

### Route Table Warming

```
Gateway startup sequence:
  1. Load route configuration (file, database, or xDS)
  2. Build route table in memory (sort by priority)
  3. Register service instances (or wait for first EDS push)
  4. Pre-fetch JWKS public keys from all configured issuers
  5. Initialize rate limit buckets (or lazy-init on first request)
  6. Initialize circuit breakers (all CLOSED)
  7. Start accepting traffic

Cold start risk:
  - First few requests may be slow (JWKS fetch, no warm caches)
  - Service instances not yet discovered (empty registry)

Mitigation:
  - Health check endpoint returns 503 until fully warmed
  - Kubernetes readiness probe gates traffic:
    readinessProbe:
      httpGet:
        path: /health/ready
        port: 8080
      initialDelaySeconds: 5
      periodSeconds: 5
  - Load balancer only sends traffic to "ready" instances
```

### JWKS Pre-warming

```
On gateway startup:
  for each configured issuer:
    fetch GET {issuer}/.well-known/jwks.json
    parse all keys into PublicKey objects
    store in cache with 1-hour TTL
    log: "Pre-warmed JWKS for issuer {issuer}: {N} keys cached"

  If fetch fails:
    log warning, but start gateway anyway
    first JWT request will trigger on-demand fetch
    risk: first few requests may have +50-200ms latency
```

---

## 9. Cache Metrics and Monitoring

### Key Metrics to Track

```
Route table:
  - route_table.size: number of active routes
  - route_table.reload_count: number of config reloads
  - route_table.reload_latency_ms: time to rebuild route table

Service instance cache:
  - service_registry.instance_count{service="user-service"}: active instances
  - service_registry.eviction_count: stale instances evicted
  - service_registry.heartbeat_age_ms: staleness of oldest heartbeat

Rate limit (Redis):
  - rate_limit.redis_latency_ms: p50/p95/p99 of Redis calls
  - rate_limit.redis_errors: Redis connection failures
  - rate_limit.fallback_local: times local fallback was used
  - rate_limit.denied_count{route="user-api"}: requests denied

JWT cache:
  - jwt_cache.hit_count: JWKS key found in cache
  - jwt_cache.miss_count: JWKS fetch triggered
  - jwt_cache.fetch_latency_ms: time to fetch from issuer
  - jwt_cache.key_count: number of cached public keys

Response cache:
  - response_cache.hit_count: cached response served
  - response_cache.miss_count: forwarded to upstream
  - response_cache.hit_rate: hit / (hit + miss)
  - response_cache.eviction_count: entries evicted (LRU/TTL)
  - response_cache.size_bytes: current cache memory usage

Circuit breaker:
  - circuit_breaker.state{service="user-service"}: CLOSED/OPEN/HALF_OPEN
  - circuit_breaker.trip_count: times circuit opened
  - circuit_breaker.recovery_count: times circuit closed from half-open
```

---

## 10. Cache Sizing Guidelines

### Per-Component Sizing

| Cache | Per-Instance Memory | Keys | TTL | Eviction |
|-------|-------------------|------|-----|----------|
| Route table | < 1 MB | ~100-1000 routes | None (explicit rebuild) | N/A |
| Service instances | ~1 MB | ~1000 instances | 30s heartbeat timeout | Stale eviction |
| JWKS public keys | < 10 KB | ~5-10 keys | 1 hour | TTL + on-demand refresh |
| Rate limit (Redis) | 20 MB (Redis) | ~100K active keys | 1 hour | TTL expiry |
| Response cache (L1) | 100-500 MB | ~10K-50K entries | Per-route (5s-5min) | LRU + TTL |
| Response cache (L2) | 1-5 GB (Redis) | ~100K-500K entries | Per-route (5s-5min) | LRU + TTL |
| Circuit breaker state | < 100 KB | ~100 services | None (in-memory) | N/A |
| **Total per instance** | **~100-500 MB** | | | |

### When to Scale Cache Layers

```
Route table > 10,000 routes:
  Action: Switch from sorted list to trie/radix tree
  Impact: O(R) → O(L) lookup (R = routes, L = path length)

Service instances > 10,000:
  Action: Shard registry by service name across multiple nodes
  Impact: Reduced memory per node, faster per-service lookups

Rate limit keys > 1M:
  Action: Redis Cluster (multiple shards)
  Impact: Distributed across N shards, each handling 1M/N keys

Response cache > 1 GB per instance:
  Action: Move to shared Redis cache (L2 only, drop L1)
  Impact: Slight latency increase (~1ms), but shared across instances

Circuit breaker > 1000 services:
  Action: Usually fine (each is O(1) state), but consider distributed mode
  Impact: Redis Hash per service, global state
```

---

## Interview Quick Reference

### "How do you cache in an API gateway?"

```
Layer 1 (Route table):
  - In-memory, rebuilt on config change
  - 100% hit rate, sub-microsecond lookup
  - Maps to our RequestRouter.routes

Layer 2 (JWT public keys):
  - In-memory, 1-hour TTL
  - 99.99% hit rate (keys rotate rarely)
  - Avoids 50-200ms JWKS fetch per request

Layer 3 (Rate limit state):
  - Redis, distributed across gateway instances
  - Token bucket: O(1) per check
  - Fail-open on Redis failure (or local fallback)

Layer 4 (Circuit breaker state):
  - In-memory per instance (local decisions)
  - O(1) check, no Redis dependency
  - Eventual consistency across instances is acceptable

Layer 5 (Service instances):
  - In-memory, refreshed via heartbeats or xDS push
  - 30s staleness tolerance, circuit breaker compensates
  
Layer 6 (Response cache, optional):
  - In-memory L1 + Redis L2
  - Only for GET/HEAD, only for cacheable routes
  - Cache key includes Vary dimensions
```

### "What should you NOT cache?"

```
1. POST/PUT/DELETE responses (not idempotent)
2. Responses with Set-Cookie (session integrity)
3. Responses with Cache-Control: no-store (explicit opt-out)
4. Personalized responses without user-scoped cache key
5. JWT tokens (unique per user, security risk)
6. Auth validation results (revocation window risk)
7. 5xx error responses (transient state cached as permanent)
8. Streaming responses (incomplete data)
```
