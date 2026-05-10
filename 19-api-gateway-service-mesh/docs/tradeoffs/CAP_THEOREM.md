# CAP Theorem -- API Gateway & Service Mesh

> Interview-ready analysis of consistency, availability, and partition tolerance
> tradeoffs for an API gateway and service mesh platform. Covers the unique CAP
> challenges of gateway infrastructure: authentication decisions (CP), routing
> table consistency (AP), rate limiting accuracy (AP), circuit breaker state
> propagation, sidecar vs library tradeoffs, and edge vs centralized gateway
> architectures.
>
> **Key insight:** An API gateway makes different CAP tradeoffs for different
> subsystems. Authentication MUST be CP (reject if unsure), but routing CAN be
> AP (stale routes are briefly acceptable). Rate limiting should fail open (AP)
> to avoid blocking all traffic when Redis is down.

---

## CAP Recap

| Letter | Property | Meaning |
|--------|----------|---------|
| **C** | Consistency | Every read receives the most recent write |
| **A** | Availability | Every request receives a response (no timeouts) |
| **P** | Partition Tolerance | System continues operating despite network partitions |

In a distributed system, network partitions **will** happen. You must choose:

```
           C
          / \
         /   \
        /     \
      CP       CA  <-- not possible in distributed systems
      /         \
     /           \
    P ----------- A
          AP
```

**You can have CP or AP, never CA in a real distributed system.**

---

## API Gateway = Mixed CP/AP System (Overall)

### Why Mixed?

Unlike a single database or cache, an API gateway is a composite system with
multiple subsystems, each with different consistency needs:

```
+---------------------------------------------------------------------+
|                        API Gateway Pipeline                         |
|                                                                     |
|  [ROUTE TABLE]  →  [AUTH]  →  [RATE LIMIT]  →  [CIRCUIT BREAKER]   |
|     AP               CP          AP               CP/AP             |
|                                                                     |
|  Stale routes     Reject if    Allow if         Local: AP           |
|  OK briefly       unsure       Redis down       Distributed: CP     |
+---------------------------------------------------------------------+
```

### The Core Argument

```
An API gateway sits in the critical path of EVERY request.
If the gateway is unavailable, NO requests reach ANY service.

Therefore: the gateway as a whole MUST be highly available (A).
But individual subsystems choose differently:

  Auth:           CP — a wrong "allow" is catastrophic (security breach)
  Routing:        AP — a briefly stale route causes a retry, not a breach
  Rate limiting:  AP — allowing a few extra requests is better than blocking all
  Circuit breaker: AP (local) — per-instance decisions are good enough
```

**Interview answer:** "An API gateway is a mixed CP/AP system. Authentication
is CP because incorrectly allowing an unauthenticated request is a security
breach -- we must reject if unsure. Routing is AP because a briefly stale
route table causes a 404 that the client retries, which is better than the
gateway being unavailable. Rate limiting is AP because we fail open when Redis
is down -- allowing some excess requests is better than blocking all
legitimate traffic."

---

## Consistency Spectrum by Subsystem

| Subsystem | CAP Choice | Staleness Tolerance | Failure Mode | Why |
|-----------|-----------|--------------------|--------------|----|
| Route table | AP | ~5 seconds | Serve stale routes | Client retries on 404; unavailable gateway blocks ALL traffic |
| Authentication (JWT) | CP | 0 seconds | Reject request (401) | Wrong "allow" = security breach; wrong "deny" = user retries |
| Authorization (RBAC) | CP | 0 seconds | Reject request (403) | Unauthorized access to protected resources |
| Rate limiting (Redis) | AP | ~30 seconds | Fail open (allow) | Excess requests < total outage; circuit breaker backs up |
| Circuit breaker (local) | AP | Per-instance | Independent per node | If service is truly down, all nodes detect independently |
| Circuit breaker (distributed) | CP | ~1 second | Block requests | Global failure budget strictly enforced |
| Service discovery | AP | ~30 seconds | Serve stale instance list | Dead instance → 502 → circuit breaker trips → retry |
| TLS/mTLS certificates | CP | 0 seconds | Reject connection | Invalid cert = potential MITM attack |
| Canary traffic split | AP | ~5 seconds | Wrong version percentage | 5% canary sees 7% briefly -- acceptable |
| Response cache | AP | TTL-based | Serve stale response | Stale data for a few seconds is acceptable |
| Distributed tracing | AP | ~seconds | Drop trace spans | Missing traces is better than blocked requests |

---

## 1. Authentication: CP (Reject If Unsure)

### Why CP for Auth

```
Scenario: Network partition between gateway and auth provider

Option A: CP (reject if unsure) ← CORRECT
  Gateway cannot validate JWT → 401 Unauthorized
  Impact: legitimate users retry after partition heals
  Risk: brief unavailability for authenticated endpoints
  Duration: typically < 30 seconds (partition + recovery)

Option B: AP (allow if unsure) ← DANGEROUS
  Gateway cannot validate JWT → allow request through
  Impact: unauthenticated requests reach protected services
  Risk: SECURITY BREACH — attacker sends requests during partition
  Duration: entire partition duration (could be minutes)
```

### JWT Validation: Mostly Offline (Mitigation)

```
Key insight: JWT validation can be mostly CP WITHOUT external dependencies.

JWT signature verification flow:
  1. Extract JWT from header
  2. Decode header → get "kid" (key ID)
  3. Look up public key in LOCAL cache (in-memory)
  4. Verify signature locally (RSA/ECDSA — pure CPU, no network)
  5. Validate claims (exp, iss, aud — pure CPU, no network)

External dependency: JWKS endpoint (only on cache miss)
  - Public keys cached for 1 hour
  - Key rotation happens every 24-90 days
  - Cache miss during partition: reject request (CP)
  - But cache miss is extremely rare (keys cached, long rotation interval)

Effective availability: 99.999%+ (almost always cached)
Actual partition vulnerability: only during:
  1. Gateway cold start (no cached keys) + JWKS endpoint unreachable
  2. Key rotation event + JWKS endpoint unreachable simultaneously
  Probability: < 0.001% of requests

Our implementation:
  JwtAuthStrategy validates tokens in-memory (HMAC-SHA256)
  - No external dependency (fully offline)
  - Production would use RSA-256 with cached JWKS (mostly offline)
```

### Token Revocation: The Hard CP Problem

```
Challenge: User revoked, but their JWT is still valid (not expired).

Option 1: Short token TTL (5-15 minutes)
  - Tokens expire quickly, limiting the revocation window
  - Tradeoff: more token refresh requests
  - CP stance: "consistency within 5-15 minutes"

Option 2: Token blacklist (Redis set)
  POST /revoke → ADD token_id to Redis set "blacklisted_tokens"
  Gateway checks: IS token_id IN "blacklisted_tokens"?
  
  If Redis down during partition:
    CP: reject ALL tokens (too aggressive, blocks everyone)
    AP: skip blacklist check (revoked token accepted for up to TTL)
    Hybrid: local blacklist cache (eventual consistency, ~5s lag)

Option 3: Token introspection (OAuth 2.0 RFC 7662)
  Gateway calls auth server: POST /introspect { "token": "..." }
  Auth server returns: { "active": true/false }
  
  If auth server unreachable:
    CP: reject request (401) ← our recommendation
    AP: fall back to local JWT validation (skip introspection)

Our simulation: JwtAuthStrategy always validates (no revocation mechanism)
  Production: Short TTL (15 min) + Redis blacklist + introspection for sensitive ops
```

### Auth Decision Matrix

```
| JWT Signature | JWT Expired? | Blacklisted? | Redis Up? | Decision |
|---------------|-------------|-------------|----------|----------|
| Valid         | No          | No          | Yes      | ALLOW    |
| Valid         | No          | Yes         | Yes      | DENY     |
| Valid         | No          | Unknown     | No       | CP: DENY, AP: ALLOW |
| Valid         | Yes         | N/A         | N/A      | DENY     |
| Invalid       | N/A         | N/A         | N/A      | DENY     |
| Missing       | N/A         | N/A         | N/A      | DENY     |

Our recommendation: CP for auth decisions
  - Invalid/expired/missing → always DENY
  - Valid + Redis down → DENY (conservative) or ALLOW with short-TTL tokens
  - The 15-minute token TTL limits the blast radius of "ALLOW when unsure"
```

---

## 2. Routing: AP (Stale Routes Acceptable Briefly)

### Why AP for Routing

```
Scenario: Route configuration updated, but not all gateway instances have it

Old config: /api/users/** → user-service-v1
New config: /api/users/** → user-service-v2

During propagation (~1-5 seconds):
  Instance A: has new config → routes to v2
  Instance B: has old config → routes to v1

Option A: AP (serve stale routes) ← CORRECT
  Instance B routes to v1 for ~1-5 seconds
  Impact: some requests hit old version briefly
  Risk: minimal — both versions are running (that is the whole point of config update)

Option B: CP (block until consistent) ← WRONG
  Instance B blocks ALL requests until it gets new config
  Impact: 50% of gateway capacity unavailable for 1-5 seconds
  Risk: cascading failure — remaining instances overloaded
```

### Route Table Propagation

```
Kong (cluster events, ~1-5 seconds):
  Admin API → PostgreSQL → cluster invalidation event → each node rebuilds
  During propagation:
    Requests may hit old routes
    Old routes still point to running services (safe)
    New routes not yet active (client gets 404, retries)

Envoy/Istio (xDS push, ~1-3 seconds):
  kubectl apply VirtualService → istiod detects → xDS push to all sidecars
  During propagation:
    Some sidecars have new config, some have old
    Traffic split may be temporarily uneven
    Both versions are healthy (safe)

Nginx (reload, ~100ms):
  Config file update → nginx -s reload → new workers start, old drain
  During reload:
    Old workers serve old config until connections drain
    New workers serve new config
    Overlap period: ~100ms-5s

Our implementation:
  routingService.registerRoute(route) → immediately visible (single JVM)
  No propagation delay — but production WOULD have delay
  Our simulation is effectively "instant AP" (always consistent, single node)
```

### Route Table Conflict Resolution

```
What if two operators update routes simultaneously?

Scenario:
  Operator A: change /api/users → user-service-v2
  Operator B: change /api/users → user-service-v3
  Both applied at nearly the same time

Resolution strategies:
  1. Last-writer-wins (most gateways):
     Whichever write reaches the config store last wins
     Risk: Operator A's change silently overwritten
     Mitigation: version field + optimistic locking (If-Match: etag)

  2. Raft consensus (etcd-backed):
     Both writes serialized through Raft leader
     Order is deterministic, but may not match operator intent
     Mitigation: CAS (Compare-And-Swap) on config version

  3. GitOps (recommended):
     Both changes go through Git PR
     Merge conflict forces manual resolution
     Only merged config is applied to gateway
     Maps to: declarative config (Kong decK, Istio CRDs in Git)
```

---

## 3. Rate Limiting: AP (Fail Open vs Fail Closed)

### Why AP for Rate Limiting

```
Scenario: Redis (rate limit store) is unreachable during network partition

Option A: Fail OPEN (allow requests) ← RECOMMENDED
  Gateway cannot check Redis → allow request through
  Impact: rate limits not enforced for duration of partition
  Risk: upstream services get more traffic than expected
  Duration: typically < 30 seconds (Redis failover)
  Mitigation: circuit breaker protects upstream from overload

Option B: Fail CLOSED (deny all requests) ← DANGEROUS
  Gateway cannot check Redis → deny ALL requests with 429
  Impact: ENTIRE API is unavailable (not just rate-limited)
  Risk: self-inflicted outage worse than any abuse
  Duration: entire partition duration
  Mitigation: none — you are down

Option C: Fail to LOCAL (per-instance fallback) ← BEST
  Gateway cannot check Redis → use in-process token bucket
  Impact: rate limits enforced per-instance (not globally)
  Risk: effective limit = N * per-instance limit (N = gateway instances)
  Duration: transparent to clients
  Mitigation: set per-instance limit to (global limit / N)
```

### The Math of Fail-Open vs Fail-Closed

```
Setup:
  10 gateway instances
  Global rate limit: 1000 req/s (per client)
  Redis down for 30 seconds

Fail OPEN:
  Abuse potential: unlimited for 30 seconds
  Legitimate impact: none (all requests succeed)
  Worst case: 30s * (some abusive rate) excess requests
  Circuit breaker compensation: trips after ~5 failed upstream requests

Fail CLOSED:
  Abuse potential: zero (nobody can access API)
  Legitimate impact: 100% of traffic denied (429)
  Worst case: 30s * 1000 req/s * num_clients = ALL legitimate traffic blocked
  This is a self-inflicted outage

Fail to LOCAL:
  Per-instance limit: 1000 / 10 = 100 req/s per instance
  Abuse potential: 10 * 100 = 1000 req/s (same as global — perfect!)
  Caveat: only works if traffic is evenly distributed across instances
  Worst case: some instances get more traffic → effective limit slightly higher
  Our RateLimiterEngine IS this local fallback

Production recommendation:
  Primary: Redis Cluster (fail-open on partition)
  Secondary: Local token bucket (RateLimiterEngine) as fallback
  Alert: if local fallback active for > 1 minute, page on-call
```

### Distributed Rate Limiting Consistency

```
Challenge: 10 gateway instances, each talking to Redis

Scenario 1: All instances hit Redis (strongly consistent)
  Instance A: EVAL token_bucket.lua → tokens = 87
  Instance B: EVAL token_bucket.lua → tokens = 86
  Instance C: EVAL token_bucket.lua → tokens = 85
  
  Redis single-threaded: operations are serialized
  Result: globally accurate rate limiting
  Latency: +1ms per request (Redis round-trip)

Scenario 2: Redis Cluster with partitioning
  Rate limit key: rl:client:192.168.1.100
  Hash slot: 8432 → Shard 2
  
  If Shard 2 is partitioned:
    Instances connected to Shard 2 leader: rate limiting works
    Instances connected to Shard 2 replica: may get stale data
    Instances losing connection to Shard 2: fall back to local

  Redis Cluster CAP: CP for writes (only leader accepts writes)
  Impact: during failover (~5-15s), some rate limit checks fail
  Our response: fall to local token bucket during Redis Cluster failover

Scenario 3: Multi-region rate limiting
  US gateway → US Redis
  EU gateway → EU Redis
  
  Client from US: rate limited by US Redis (accurate)
  Client moves to EU: rate limited by EU Redis (starts fresh!)
  
  Solution A: Global Redis (CRDTs, eventual consistency)
    Active-Active Redis Enterprise with CRDT counters
    Counters converge within ~1 second
    Effective staleness: ~1 second (AP)

  Solution B: Accept per-region limits
    US limit: 500 req/s, EU limit: 500 req/s
    Client in both regions: effective limit = 1000 req/s (2x)
    Acceptable if limit is set conservatively per-region
```

### Rate Limit Decision Matrix

```
| Redis Status | Rate Limit Key Exists | Tokens Available | Decision |
|-------------|----------------------|-----------------|----------|
| Healthy     | Yes                  | >= 1            | ALLOW    |
| Healthy     | Yes                  | 0               | DENY (429) |
| Healthy     | No (new key)         | N/A (init full) | ALLOW (init bucket) |
| Unreachable | N/A                  | N/A             | ALLOW (fail open) |
| Unreachable | N/A                  | N/A             | LOCAL CHECK (fail to local) |
| Cluster failover | N/A            | N/A             | ALLOW or LOCAL (5-15s window) |

Our simulation: always local (RateLimiterEngine in ConcurrentHashMap)
  - Effectively "fail to local" at all times (single JVM)
  - No Redis dependency in simulation
```

---

## 4. Circuit Breaker: CP (State) vs AP (Decision)

### Why Circuit Breaker Is Nuanced

```
The circuit breaker has TWO distinct concerns:

1. State tracking (failure/success counts):
   - CP: globally accurate failure count
   - AP: per-instance failure count (each node tracks independently)

2. Allow/deny decision:
   - Must be fast (O(1), no network call)
   - Must be available (circuit check cannot itself fail)
   - Therefore: local decision is required (AP)

Production approach: LOCAL state + OPTIONAL distributed sync
  Each instance: tracks failures locally, makes decisions locally
  Periodically: syncs state to Redis for global awareness
  On sync: updates local state if global says OPEN
```

### Local Circuit Breaker: AP

```
+------------------+     +------------------+
| Gateway #1       |     | Gateway #2       |
| CB: user-svc     |     | CB: user-svc     |
| state: CLOSED    |     | state: CLOSED    |
| failures: 3      |     | failures: 1      |
+------------------+     +------------------+

user-service is degraded (50% of requests fail):

After 10 more requests:
+------------------+     +------------------+
| Gateway #1       |     | Gateway #2       |
| CB: OPEN         |     | CB: CLOSED       |  ← different states!
| failures: 5      |     | failures: 4      |
+------------------+     +------------------+

Gateway #1 tripped first (it happened to see more failures).
Gateway #2 will trip after 1 more failure.
Delay: at most (threshold / failure_rate) seconds per instance.

Is this acceptable? YES, because:
  - If the service is truly failing, ALL instances will trip eventually
  - The delay between first and last trip is typically < 5 seconds
  - During the delay, the failing requests go through but get 502 errors
    (the client already sees errors — circuit breaker just prevents more)
```

### Distributed Circuit Breaker: CP

```
+------------------+     +------------------+
| Gateway #1       |     | Gateway #2       |
| CB: (via Redis)  |     | CB: (via Redis)  |
+--------+---------+     +--------+---------+
         |                        |
         v                        v
   +-----------------------------------+
   | Redis                              |
   | cb:user-svc                        |
   |   state: OPEN                      |  ← single source of truth
   |   failures: 5                      |
   |   opened_at: 1700000000           |
   +-----------------------------------+

All failures aggregated globally.
5 total failures (across all instances) → circuit opens.
ALL instances immediately reject requests to user-svc.

Benefit: faster detection (failures from all instances count)
Cost: +1ms Redis latency per request for circuit check
Risk: Redis down → circuit breaker down → fail open (allow all)
```

### Circuit Breaker: Open vs Half-Open Race Condition

```
Distributed half-open race:

Time T=0: Circuit is OPEN, cooldown = 30s
Time T=30: Cooldown elapsed, transition to HALF_OPEN

Instance A: checks Redis, sees HALF_OPEN → sends probe request
Instance B: checks Redis, sees HALF_OPEN → sends probe request
Instance C: checks Redis, sees HALF_OPEN → sends probe request

Problem: 3 simultaneous probe requests to a failing service!
  If the service is recovering, this is fine.
  If the service is still failing, 3 failures are recorded.

Solution 1: Distributed lock (Redis SETNX)
  Only one instance gets the "probe lock"
  Other instances continue to reject
  Lock expires after probe timeout

Solution 2: Probabilistic probing
  Each instance has P(probe) = 1/N probability of probing
  Expected: ~1 probe request per cooldown period
  Our simulation: single JVM, no race condition

Solution 3: Accept multiple probes (simple, usually fine)
  3 probes to a recovering service is acceptable
  If service handles 1 request, it can handle 3
  Only problematic if probing itself causes harm (rare)
```

### Our Implementation Mapping

```
CircuitBreakerEngine (local, AP):
  - ConcurrentHashMap<String, CircuitBreakerState>
  - Each CircuitBreakerState: state, failureCount, successCount, openedAt
  - allowRequest(): O(1), no network call
  - recordSuccess()/recordFailure(): O(1), local state update

  Maps to: Envoy's per-sidecar outlier detection
    - Each Envoy tracks failures independently
    - No centralized circuit breaker state
    - Envoy ejects unhealthy endpoints from its own endpoint list

  Parameters:
    DEFAULT_FAILURE_THRESHOLD = 5    (consecutive failures to trip)
    DEFAULT_SUCCESS_THRESHOLD = 3    (successes in HALF_OPEN to recover)
    DEFAULT_OPEN_DURATION_MS = 30000 (cooldown before HALF_OPEN)
```

---

## 5. Service Discovery: AP (Stale Instance List)

### Why AP for Discovery

```
Scenario: Service instance dies, but discovery cache is stale

With AP (stale cache, 30s TTL):
  T=0:    Instance dies
  T=0-30: Some requests routed to dead instance → 502 error
  T=0:    Circuit breaker starts counting failures
  T=5:    Circuit breaker trips (5 failures) → stops routing to service
  T=30:   Discovery cache refreshes → dead instance removed
  
  Impact: 5 failed requests over ~1 second, then circuit breaker protects
  Recovery: automatic (no manual intervention)

With CP (block until consistent):
  T=0:    Instance dies
  T=0:    Discovery must confirm death before routing any requests
  T=0-10: ALL requests to this service blocked while confirming
  
  Impact: 10 seconds of zero availability for the service
  Risk: if discovery system is slow/partitioned, longer outage

AP is clearly better for service discovery.
The circuit breaker provides a secondary safety net.
```

### Discovery Staleness Scenarios

```
Scenario 1: Scale-up (new instance added)
  Discovery lag: 1-5 seconds (push) or up to 30 seconds (poll)
  Impact: new instance not receiving traffic yet (no harm)
  Traffic: distributed among existing instances (slightly more load)
  Resolution: automatic when discovery updates

Scenario 2: Scale-down (instance removed gracefully)
  Sequence: drain → deregister → terminate
  Discovery lag: near-zero (deregister happens before terminate)
  Impact: none (instance stops receiving new requests during drain)
  Best practice: connection draining timeout > discovery propagation time

Scenario 3: Instance crash (ungraceful)
  Discovery lag: health check interval + failure threshold
  Example: 10s interval * 3 failures = 30 seconds
  Impact: requests routed to dead instance for up to 30 seconds
  Mitigation: circuit breaker trips in ~5 failed requests (~1 second)
  Net impact: ~5 failed requests, then automatic protection

Scenario 4: Network partition (instance healthy but unreachable)
  From gateway's perspective: instance appears dead
  Health checks fail → mark unhealthy → remove from routing
  Circuit breaker: trips on connection timeouts
  When partition heals: health checks pass → re-add to routing
  Risk: healthy instance not receiving traffic during partition
```

### Our Implementation

```
ServiceRegistry (AP):
  - ConcurrentHashMap<String, List<ServiceInstance>>
  - getInstances(): returns HEALTHY instances only
  - evictStale(Duration.ofSeconds(30)): removes instances with no heartbeat
  - heartbeat(): updates lastHeartbeat timestamp

  Staleness: up to 30 seconds for dead instance
  Mitigation: CircuitBreakerEngine trips after 5 failures (~1s)
  Net effect: ~5 failed requests before automatic protection
```

---

## 6. Sidecar vs Library: Deployment Tradeoff

### The Core Tradeoff

```
Sidecar (Envoy / Linkerd-proxy):
  +--------+     +--------+
  | Service| <-> | Envoy  |   Same pod, localhost communication
  | (Java) |     | Sidecar|   Envoy handles: mTLS, LB, CB, retries, tracing
  +--------+     +--------+

  Pros:
    - Language-agnostic (Java, Go, Python, Rust — all get same features)
    - Independent updates (update Envoy without redeploying service)
    - Consistent behavior (all services use same proxy version)
    - No code changes (zero instrumentation in application code)
    
  Cons:
    - +1 ms latency per hop (localhost proxy)
    - +50-100 MB memory per pod (Envoy sidecar)
    - Operational complexity (sidecar injection, lifecycle management)
    - Debugging harder (request goes through proxy before reaching service)

Library (Resilience4j / Hystrix / Polly):
  +---------------------------+
  | Service (Java)            |
  |   Resilience4j circuit    |
  |   breaker, retry, rate    |
  |   limit — all in-process  |
  +---------------------------+

  Pros:
    - Zero additional latency (in-process calls)
    - Zero additional memory (library is part of application)
    - Simpler debugging (everything in one process)
    - Direct control (developer configures per-call behavior)
    
  Cons:
    - Language-specific (Resilience4j = Java only, Polly = .NET only)
    - Tight coupling (library updates require redeploying every service)
    - Inconsistent behavior (different services may use different versions)
    - No mTLS (must implement TLS separately)
    - No traffic splitting (library cannot intercept incoming requests)
```

### When to Use Each

```
Use SIDECAR when:
  - Polyglot microservices (Java + Go + Python + Node.js)
  - You need mTLS between all services (zero-trust network)
  - You need centralized traffic management (canary, A/B, traffic shifting)
  - You want consistent observability (metrics/traces from every service)
  - Team count > 5 (central platform team manages mesh)

Use LIBRARY when:
  - Single language (all services in Java → Resilience4j)
  - Latency-critical services (every microsecond matters)
  - Small team (< 5 engineers, mesh is too much operational overhead)
  - Simple architecture (< 10 services)
  - Edge cases where sidecar doesn't work (serverless functions, batch jobs)

Use BOTH (hybrid, common in practice):
  - Sidecar for mTLS, service discovery, basic routing
  - Library for application-specific retry logic, custom circuit breakers
  - Sidecar handles infrastructure concerns, library handles business logic

Our simulation:
  - Models BOTH patterns:
    GatewayService: API gateway (centralized, like Kong)
    ServiceMeshService: sidecar proxy (like Envoy)
    CircuitBreakerEngine/RateLimiterEngine: in-process (like library approach)
```

### Resource Comparison

| Aspect | Sidecar (Envoy) | Library (Resilience4j) |
|--------|-----------------|----------------------|
| Memory per service | +50-100 MB | +5-10 MB |
| CPU per service | +5-10% | +1-2% |
| Latency per hop | +1-2 ms | +0.01 ms |
| Languages supported | All | Java only |
| mTLS support | Built-in | Manual (or separate library) |
| Traffic splitting | Built-in | Not available |
| Config update | xDS push (no restart) | Restart or hot-reload |
| Circuit breaker | Per-endpoint outlier detection | Per-call, configurable |
| Debugging | Proxy logs + application logs | Application logs only |

---

## 7. Edge Gateway vs Centralized Gateway

### Architecture Comparison

```
Edge Gateway (CDN edge functions):
  +--------+    +--------+    +--------+
  | Edge   |    | Edge   |    | Edge   |   Edge PoPs (200+)
  | PoP 1  |    | PoP 2  |    | PoP 3  |   Run auth, rate limit, routing
  | (NYC)  |    | (LON)  |    | (TKY)  |   at the nearest edge location
  +---+----+    +---+----+    +---+----+
      |             |             |
      +------+------+------+-----+
             |             |
       +-----+----+  +----+-----+
       | Origin   |  | Origin   |
       | Region 1 |  | Region 2 |
       | (US)     |  | (EU)     |
       +----------+  +----------+

  Examples: CloudFront Functions, Lambda@Edge, Cloudflare Workers
  Latency: ~1-10ms (user to edge PoP)
  Compute: limited (128 MB, 5ms CPU for CloudFront Functions)

Centralized Gateway (single region):
  +--------+    +--------+    +--------+
  | Client |    | Client |    | Client |
  | (NYC)  |    | (LON)  |    | (TKY)  |
  +---+----+    +---+----+    +---+----+
      |             |             |
      +------+------+------+-----+
             |
       +-----+-----+
       | Centralized|
       | API Gateway|
       | (US-EAST)  |
       +-----+------+
             |
       +-----+-----+
       | Services   |
       +------------+

  Examples: Kong cluster, API Gateway (regional), Nginx cluster
  Latency: ~10-200ms (user to gateway region, cross-ocean for far users)
  Compute: full (any language, any complexity)
```

### Tradeoff Analysis

| Aspect | Edge Gateway | Centralized Gateway |
|--------|-------------|-------------------|
| User-facing latency | 1-10 ms (nearest PoP) | 10-200 ms (single region) |
| Auth validation | Limited (JWT verify, API key) | Full (OAuth flow, DB lookup) |
| Rate limiting | Per-PoP (not global) | Global (Redis-backed) |
| Complex routing | Limited (simple rules) | Full (regex, header, body) |
| Request transformation | Limited | Full (VTL, Lua, JS) |
| State management | Stateless or limited KV | Full (Redis, DB, sessions) |
| DDoS protection | Excellent (absorb at edge) | Good (WAF at gateway) |
| Deployment | 200+ PoPs automatically | 1-3 regions manually |
| Cost | Per-request at edge pricing | Per-instance + compute |
| Debugging | Harder (distributed logs) | Easier (centralized logs) |

### When to Use Each

```
Use EDGE gateway when:
  - Global user base (latency matters)
  - Simple auth (JWT, API key — no database needed)
  - Static rate limiting (per-IP, not per-user global)
  - DDoS protection is critical
  - Geo-routing needed (route to nearest region)
  - A/B testing at the edge (cookie-based)

Use CENTRALIZED gateway when:
  - Complex auth (OAuth flows, LDAP, custom logic)
  - Global rate limiting (shared Redis state)
  - Complex routing (body inspection, request transformation)
  - Service mesh integration (mTLS, circuit breaking)
  - Compliance (all requests must go through specific region)

Use BOTH (common production pattern):
  Edge: TLS termination, DDoS protection, simple auth, caching
  Centralized: complex auth, rate limiting, routing, mesh integration

Our simulation models CENTRALIZED:
  GatewayService handles all concerns in one pipeline
  Production: edge + centralized in two tiers
```

### Edge Rate Limiting: The Consistency Problem

```
Edge rate limiting with 200+ PoPs:

Global limit: 1000 req/s per API key
PoPs: 200 worldwide
Per-PoP limit: 1000 / 200 = 5 req/s per PoP ← too low!

Problem: most users hit only 1-3 PoPs
  User in NYC always hits NYC PoP
  NYC PoP limit: 5 req/s (but user's real limit should be 1000)
  
Solutions:
  A. Proportional allocation:
     Allocate limits based on traffic distribution per PoP
     NYC (10% traffic) → 100 req/s
     LON (5% traffic) → 50 req/s
     Dynamic rebalancing based on observed traffic

  B. Token bucket with global sync:
     Each PoP has a local token bucket
     Periodically sync consumed tokens to central counter
     Rebalance allocations based on actual consumption
     Latency: eventual consistency (~5-10 seconds)

  C. Centralized rate limiting only:
     Edge does DDoS/IP-level limiting (simple)
     Centralized gateway does per-user/per-key rate limiting
     This is the most common production approach

  D. Regional clusters:
     3-5 regional clusters (US, EU, APAC) instead of 200 PoPs
     Each cluster has its own Redis for rate limiting
     Per-region limit: 1000 / 3 = 333 req/s (reasonable)
```

---

## 8. mTLS Certificate Distribution: CP

### Why CP for Certificates

```
Certificate validity is non-negotiable:
  - Expired cert → connection refused (CP: reject)
  - Revoked cert → connection refused (CP: reject)
  - Unknown CA → connection refused (CP: reject)
  - Valid cert → connection allowed

There is no "stale certificate" — it is either valid or not.
mTLS certificate distribution must be CP.

Certificate rotation flow (Istio Citadel):
  1. Citadel issues cert with 24-hour TTL
  2. At 50% lifetime (12 hours): Envoy requests new cert
  3. Citadel issues new cert → Envoy hot-swaps
  4. Old cert still valid until expiry (overlap period)
  5. If Citadel is unreachable at renewal time:
     Envoy retries exponentially
     Old cert still valid for up to 12 more hours
     If old cert expires and Citadel still unreachable: connection fails (CP)

Our implementation:
  TlsEngine.validateConnection() → checks both caller and target in trusted set
  - Simple boolean check (in-memory)
  - Production: full X.509 chain validation + CRL/OCSP check
  - Both are CP: reject if cert is invalid
```

---

## 9. Canary Traffic Split: AP

### Why AP for Canary

```
Desired split: 90% stable, 10% canary

During config propagation (~1-5 seconds):
  Instance A (new config): 90/10 split
  Instance B (old config): 100/0 split (no canary)
  Instance C (new config): 90/10 split

Effective split during propagation:
  66% of instances route 10% to canary = ~6.6% canary traffic
  (instead of 10%)

Impact: canary gets slightly less traffic for a few seconds.
Risk: none — canary just ramps up a few seconds slower.

This is clearly AP-safe. No reason to block traffic during propagation.

Our implementation:
  CanaryTrafficStrategy.selectVersion() → weighted random
  - TrafficSplit.splits: {"v1-stable": 90, "v2-canary": 10}
  - ThreadLocalRandom for per-request selection
  - No distributed state needed (each instance independently
    applies the same weights — statistical guarantee of correct split)
```

---

## 10. Summary: CAP Decisions by Subsystem

```
+-------------------+------+----------------------------------------------+
| Subsystem         | CAP  | Rationale                                    |
+-------------------+------+----------------------------------------------+
| Route table       | AP   | Stale routes → 404 (client retries)          |
|                   |      | Unavailable gateway → total outage           |
+-------------------+------+----------------------------------------------+
| Authentication    | CP   | Wrong "allow" → security breach              |
|                   |      | JWT is mostly offline (cached JWKS)          |
+-------------------+------+----------------------------------------------+
| Authorization     | CP   | Unauthorized access to protected resources   |
|                   |      | RBAC check is local (JWT claims)             |
+-------------------+------+----------------------------------------------+
| Rate limiting     | AP   | Fail open: allow excess > block all          |
|                   |      | Fail to local: per-instance fallback         |
+-------------------+------+----------------------------------------------+
| Circuit breaker   | AP   | Local decisions, independent per instance    |
| (local)           |      | All instances trip eventually if service down|
+-------------------+------+----------------------------------------------+
| Circuit breaker   | CP   | Global failure budget strictly enforced      |
| (distributed)     |      | +1ms latency for Redis check                |
+-------------------+------+----------------------------------------------+
| Service discovery | AP   | Stale instance → 502 → circuit breaker trips |
|                   |      | Unavailable discovery → no routing at all    |
+-------------------+------+----------------------------------------------+
| mTLS certificates | CP   | Invalid cert → potential MITM attack         |
|                   |      | No "stale certificate" concept               |
+-------------------+------+----------------------------------------------+
| Canary split      | AP   | Briefly wrong percentage is harmless         |
|                   |      | Canary ramps up seconds slower               |
+-------------------+------+----------------------------------------------+
| Response cache    | AP   | Stale response for seconds is acceptable     |
|                   |      | Cache-Control headers govern TTL             |
+-------------------+------+----------------------------------------------+
| Tracing/logging   | AP   | Missing spans are better than blocked reqs   |
|                   |      | Observability is never on the critical path  |
+-------------------+------+----------------------------------------------+
```

---

## Interview Quick Reference

### "How does CAP apply to an API gateway?"

```
"An API gateway is not a single-CAP system — it is a composite of subsystems
with different consistency needs.

Authentication is CP: if I cannot validate a JWT, I reject the request.
A security breach from a false positive is worse than a brief outage.
In practice, JWT validation is mostly offline (cached JWKS public keys),
so the availability impact is minimal.

Rate limiting is AP: if Redis is unreachable, I fail open and allow requests.
Blocking all legitimate traffic is worse than allowing some excess requests
during a brief Redis outage. The circuit breaker provides a secondary safety
net for upstream services.

Routing is AP: a briefly stale route table means some requests hit an old
version for a few seconds. This is far better than the gateway being
unavailable during a config propagation.

Circuit breaking is AP per-instance: each gateway instance tracks failures
independently. If a service is truly down, all instances will trip their
circuits within seconds. Distributed circuit breaking via Redis adds global
consistency but also adds latency and a Redis dependency."
```

### "Should rate limiting fail open or fail closed?"

```
"Fail open in almost all cases. Here is the math:

If I fail closed when Redis is down for 30 seconds:
  - 100% of legitimate traffic is blocked
  - Self-inflicted outage worse than any abuse

If I fail open when Redis is down for 30 seconds:
  - Rate limits not enforced for 30 seconds
  - Some excess requests reach upstream
  - Circuit breaker protects upstream from overload
  - No legitimate traffic blocked

The best approach is 'fail to local': use an in-process token bucket
as a fallback. This gives per-instance rate limiting during Redis outages.
Our RateLimiterEngine implementation IS this local fallback."
```

### "Sidecar or library for resilience?"

```
"Sidecar (Envoy) for infrastructure concerns: mTLS, service discovery,
traffic splitting, basic circuit breaking, observability. These are
cross-cutting concerns that should be consistent across all services
regardless of language.

Library (Resilience4j) for application-specific concerns: custom retry
logic, business-aware circuit breakers, request-specific timeout tuning.
These require domain knowledge that a generic proxy cannot provide.

In practice, most production systems use BOTH: sidecar for network-level
resilience and library for application-level resilience. Our simulation
models both: ServiceMeshService (sidecar) and CircuitBreakerEngine/
RateLimiterEngine (library)."
```

### "Edge or centralized gateway?"

```
"Both, in a two-tier architecture:

Tier 1 (Edge): CloudFront/Cloudflare for TLS termination, DDoS protection,
response caching, simple auth (JWT verify), and geo-routing. Runs at
200+ PoPs with sub-10ms latency to users.

Tier 2 (Centralized): Kong/Envoy for complex routing, global rate limiting
(Redis-backed), request transformation, service mesh integration, and
full auth flows. Runs in 1-3 regions.

Edge handles the fast, stateless work. Centralized handles the stateful,
complex work. Rate limiting is particularly tricky at the edge because
200+ PoPs cannot efficiently share global counters — so global rate
limiting stays centralized."
```
