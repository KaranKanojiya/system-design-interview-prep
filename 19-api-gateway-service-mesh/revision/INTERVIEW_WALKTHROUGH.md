# API Gateway & Service Mesh -- Staff Engineer Interview Walkthrough

> **Target role:** Staff Engineer | **Time budget:** 35 minutes
> **Comparable systems:** Kong, Envoy, Istio, AWS API Gateway, NGINX, Zuul
> **Codebase reference:** `com.systemdesign.gateway` (Project 19)

---

## TABLE OF CONTENTS

```
Phase 1 : Clarify Requirements .................. 2-3 min  (lines   33-195)
Phase 2 : High-Level Architecture ............... 5-7 min  (lines  197-520)
Phase 3 : Deep Dive -- Gateway Pipeline ......... 8-10 min (lines  522-1005)
Phase 4 : Deep Dive -- Service Mesh ............. 5-7 min  (lines 1007-1400)
Phase 5 : Traffic Management .................... 3-5 min  (lines 1402-1720)
Phase 6 : Scaling & Tradeoffs ................... 3-5 min  (lines 1722-2015)
Phase 7 : Edge Cases ............................ 2-3 min  (lines 2017-2300)
Appendix A : Design Patterns Cheat Sheet ........ (lines 2302-2370)
Appendix B : Complexity Cheat Sheet ............. (lines 2372-2425)
Appendix C : Quick-Fire Q&A Bank ................ (lines 2427-2530)
Appendix D : Kong vs Envoy vs Istio Comparison .. (lines 2532-2600)
Appendix E : Whiteboard Drawing Order ........... (lines 2602-2660)
Appendix F : Anti-Patterns to Avoid ............. (lines 2662-2740)
Appendix G : Interview Timing Cheat Sheet ....... (lines 2742-2780)
```

---
---

## PHASE 1: CLARIFY REQUIREMENTS (2-3 min)

### What this phase signals to the interviewer

```
STAFF SIGNAL: You drive ambiguity instead of waiting for answers.
You ask targeted questions that reveal hidden constraints,
then confirm scope before drawing a single box.
Juniors jump to drawing; Staff engineers anchor first.
```

### Questions to ask the interviewer (pick 6-8)

Ask these in a natural conversational order. Do not read them like a
checklist. Group them into three buckets: scale, architecture model,
and deployment.

#### Bucket 1 -- Scale & Traffic Shape

```
Q1: "What's the expected request throughput -- are we talking
     10K requests/sec or 100K+ requests/sec at the edge?"
     WHY: 10K/s = single gateway cluster suffices.
          100K+ = need multiple gateway instances with L4 load
          balancing (DNS or hardware LB) in front of the fleet.
          This determines whether we need horizontal scaling on
          day one.

Q2: "How many backend microservices are in the mesh -- tens
     or hundreds?"
     WHY: Tens = flat mesh, simple service registry.
          Hundreds = need hierarchical namespacing, sidecar resource
          budgeting, and control plane scalability considerations.
          Also determines service discovery complexity.

Q3: "What's the expected ratio of external (north-south) traffic
     to internal (east-west) service-to-service calls?"
     WHY: If 80% of traffic is east-west, the mesh data plane is
          the bottleneck, not the gateway. If 80% is north-south,
          the gateway pipeline is the critical path.

Q4: "What are the latency SLOs for the gateway hop itself --
     is 5ms overhead acceptable, or do we need sub-1ms?"
     WHY: 5ms = room for JWT validation, rate limiting, and
          circuit breaker checks in the hot path.
          Sub-1ms = we need to push auth to an async pre-check
          or use connection-level TLS termination only.
```

#### Bucket 2 -- Architecture Model

```
Q5: "Are we designing just an API gateway, or a full service
     mesh with sidecar proxies? Or a hybrid?"
     WHY: This is THE architectural fork. Gateway-only = centralized
          edge proxy. Full mesh = sidecar per pod (Envoy/Istio
          model). Hybrid = gateway for north-south, mesh for
          east-west. Each has radically different failure domains.

Q6: "What authentication model -- JWT bearer tokens, API keys,
     mTLS, or OAuth2 with an external IdP?"
     WHY: JWT = stateless validation at the gateway (fast).
          OAuth2 = token introspection call to an IdP (adds latency).
          mTLS = certificate-based identity for service-to-service.
          The choice affects every layer of the pipeline.

Q7: "Do we need advanced traffic management -- canary releases,
     blue-green deployments, A/B testing?"
     WHY: If yes, we need a traffic splitting layer with weighted
          routing and automated rollback. This adds control plane
          complexity (VirtualService-like CRDs in Istio terms).

Q8: "Should rate limiting be per-client, per-route, per-service,
     or some combination?"
     WHY: Per-client = need client identity extraction (API key,
          JWT sub claim). Per-route = simpler, keyed by path pattern.
          Per-service = mesh-level, protecting downstream capacity.
          Combination = need a multi-dimensional rate limit key.
```

#### Bucket 3 -- Deployment & Operations

```
Q9: "Is this deployed on Kubernetes, bare-metal, or a managed
     cloud service?"
     WHY: Kubernetes = sidecar injection via admission webhooks,
          service discovery via kube-dns, pod-level networking.
          Bare-metal = need explicit service registration, DNS-based
          discovery, manual sidecar deployment. This shapes the
          entire control plane design.

Q10: "Do we need centralized observability -- distributed tracing,
      access logging, and metrics from the gateway and mesh?"
      WHY: If yes, every proxy (gateway + sidecars) must emit
           OpenTelemetry spans, structured access logs, and
           RED metrics. This adds overhead but is essential for
           production debugging.
```

### Clarified scope (write on whiteboard/doc)

After hearing answers, summarize aloud:

```
+--------------------------------------+--------------------------------------+
|            IN SCOPE                  |           OUT OF SCOPE               |
+--------------------------------------+--------------------------------------+
| Edge API gateway (north-south)       | CDN / edge caching layer            |
| Service mesh (east-west, sidecar)    | Service implementation / business   |
| JWT + mTLS authentication            | OAuth2 IdP implementation           |
| Token bucket rate limiting           | Billing / usage metering            |
| Circuit breaker per-service          | Full observability platform          |
| Weighted load balancing + consistent | API versioning strategy             |
|   hash                               | Web Application Firewall (WAF)      |
| Canary / blue-green traffic splits   | GraphQL / gRPC protocol handling    |
| Control plane + data plane split     | Multi-cluster federation            |
| Distributed tracing header propagate | Certificate Authority (CA) design   |
+--------------------------------------+--------------------------------------+
```

```
TALKING POINT:
"I'll design a hybrid system: an edge API gateway for north-south
traffic handling auth, rate limiting, and routing; plus a service
mesh with sidecar proxies for east-west traffic handling mTLS,
circuit breaking, and load balancing. I'll target 50K req/s at the
edge, 200K req/s east-west, with 100 microservices in the mesh.
This is comparable to what Kong + Istio provide together, but I'll
design from first principles."
```

### Common follow-up questions for Phase 1

```
Q: "What if the interviewer says 'just design whatever you think
    is right'?"
A: Default to this scope: 50K req/s at the gateway, 100 services,
   JWT auth at the edge, mTLS in the mesh, token bucket rate
   limiting, circuit breakers with 5-failure threshold, consistent
   hash LB for cache-affinity, canary deployments with 10% initial
   traffic split, Kubernetes deployment.

Q: "Should I mention Kong/Envoy/Istio by name?"
A: Yes, briefly: "This is similar to Kong at the edge and
   Istio/Envoy in the mesh, but I'll design from first principles."
   Shows awareness without name-dropping.

Q: "What if they ask about gateway-only vs. full mesh?"
A: "A gateway alone handles north-south well but leaves east-west
   traffic unprotected. A full mesh adds mTLS, per-service circuit
   breakers, and observability for internal calls. The hybrid
   approach gives us defense in depth."
```

---
---

## PHASE 2: HIGH-LEVEL ARCHITECTURE (5-7 min)

### What this phase signals to the interviewer

```
STAFF SIGNAL: You separate the control plane from the data plane,
name the key components, and draw data flow arrows showing both
north-south and east-west paths. You call out which components
are on the critical path and which are async configuration pushes.
```

### The Two Planes

Present the system as two distinct planes. This framing immediately
shows the interviewer you understand the fundamental architecture
of modern service meshes (Istio, Linkerd, Consul Connect).

```
CONTROL PLANE (async, off the critical path)
  - Configuration management (routes, rate limits, circuit breaker thresholds)
  - Service discovery (registry of healthy instances)
  - Certificate management (mTLS cert issuance and rotation)
  - Traffic policy (canary weights, A/B splits, retry policies)
  - Observability aggregation (collect metrics from all proxies)

  "The control plane is the brain. It distributes configuration
   to all data plane proxies. It is NOT on the request hot path.
   If the control plane goes down, proxies continue serving with
   their last-known configuration."

DATA PLANE (synchronous, on the critical path)
  - Edge gateway proxy (north-south: external clients to services)
  - Sidecar proxies (east-west: service-to-service)
  - Each proxy runs the same pipeline: route → auth → rate limit
    → circuit breaker → load balance → forward
  - Proxies cache config locally; they do not call the control
    plane on every request

  "The data plane is the muscle. Every request flows through a
   proxy -- the edge gateway for external traffic, a sidecar for
   internal traffic. Proxies are stateless and horizontally
   scalable."
```

### ASCII Architecture Diagram (draw this)

```
                     API GATEWAY & SERVICE MESH
  =====================================================================

  EXTERNAL CLIENTS (north-south traffic)
         |
         v
  +------+------+      +------+------+      +------+------+
  | L4 Load     |      | L4 Load     |      | L4 Load     |
  | Balancer    |      | Balancer    |      | Balancer    |
  | (DNS/NLB)   |      | (DNS/NLB)   |      | (DNS/NLB)   |
  +------+------+      +------+------+      +------+------+
         |                    |                    |
         +--------------------+--------------------+
                              |
                     L4 ROUND ROBIN
                              |
         +--------------------+--------------------+
         |                    |                    |
         v                    v                    v
  +------+------+      +------+------+      +------+------+
  |   Gateway   |      |   Gateway   |      |   Gateway   |
  |  Instance 1 |      |  Instance 2 |      |  Instance 3 |
  |             |      |             |      |             |
  | Route       |      | Route       |      | Route       |
  | Auth (JWT)  |      | Auth (JWT)  |      | Auth (JWT)  |
  | Rate Limit  |      | Rate Limit  |      | Rate Limit  |
  | Circuit Brk |      | Circuit Brk |      | Circuit Brk |
  | Load Balance|      | Load Balance|      | Load Balance|
  +------+------+      +------+------+      +------+------+
         |                    |                    |
  =======+===================+====================+============
         |         SERVICE MESH (east-west)        |
         v                    v                    v
  +------+---+  +---+------+---+  +---+------+---+  +---+------+
  | Sidecar  |  |   | Sidecar  |  |   | Sidecar  |  |   | Sidecar|
  | Proxy A  |  |   | Proxy B  |  |   | Proxy C  |  |   | Proxy D|
  +----+-----+  |   +----+-----+  |   +----+-----+  |   +----+---+
  | Service  |  |   | Service  |  |   | Service  |  |   | Service|
  |    A     |  |   |    B     |  |   |    C     |  |   |    D   |
  +----------+  |   +----------+  |   +----------+  |   +--------+
                |                  |
                |  east-west calls |
                +---> mTLS --------+
                      Circuit Brk
                      Load Balance
                      Retry
  =====================================================================

  CONTROL PLANE (async config push — NOT on request hot path)
  +------------------------------------------------------------------+
  |                                                                  |
  |  +---------------+  +---------------+  +------------------+      |
  |  | Config Store  |  | Service       |  | Certificate      |      |
  |  | (Routes,      |  | Registry      |  | Authority (CA)   |      |
  |  |  Rate Limits, |  | (Instances,   |  | (mTLS cert       |      |
  |  |  CB Thresholds|  |  Health,      |  |  issuance,       |      |
  |  |  Traffic      |  |  Endpoints)   |  |  rotation)       |      |
  |  |  Splits)      |  |              |  |                  |      |
  |  +-------+-------+  +-------+------+  +--------+---------+      |
  |          |                  |                   |                |
  |          +------------------+-------------------+                |
  |                             |                                    |
  |                    xDS / gRPC push                               |
  |                             |                                    |
  |                 to all gateway instances                         |
  |                 and sidecar proxies                              |
  |                                                                  |
  +------------------------------------------------------------------+
```

### What to say while drawing

```
"Let me walk through the architecture in two passes -- first the
request path, then the configuration path.

REQUEST PATH (data plane):
 1. External client sends HTTPS request to a DNS name that
    resolves to one of our L4 load balancers (AWS NLB or
    hardware F5). The L4 LB is dumb -- TCP round-robin, no
    HTTP awareness.

 2. The L4 LB forwards to one of N gateway instances. Each
    gateway is stateless and runs the full pipeline: TLS
    termination, route matching, JWT auth, rate limiting,
    circuit breaker check, load balancing, and forwarding.

 3. The gateway selects a backend service instance and forwards
    the request. If the target is inside the mesh, the request
    goes to the service's sidecar proxy first.

 4. For east-west calls (Service A calls Service B), the
    request exits Service A's sidecar, traverses the network
    with mTLS, and enters Service B's sidecar. The sidecar
    applies its own circuit breaker, load balancing, and retry
    logic.

CONFIGURATION PATH (control plane):
 5. The control plane runs a Config Store, Service Registry,
    and Certificate Authority. It pushes configuration to all
    proxies via xDS (Envoy's discovery service API) or gRPC
    streaming.

 6. When an operator changes a route or rate limit, the control
    plane pushes the new config. Proxies apply it without
    restart. If the control plane is down, proxies serve with
    stale config -- they degrade gracefully."
```

### Why control-plane / data-plane separation matters

```
TALKING POINT:
"This separation is the single most important architectural
decision. It means:
  - The data plane has zero runtime dependency on the control
    plane. If the control plane crashes, existing requests
    continue flowing.
  - We can upgrade the control plane independently of the data
    plane (and vice versa).
  - The control plane can be a single leader or a small cluster;
    it does not need to scale with request throughput.
  - Configuration changes propagate asynchronously -- eventual
    consistency is acceptable for route changes, rate limit
    updates, and certificate rotations."
```

### Numbered component summary (say aloud)

```
"Let me enumerate the core components:

 1. L4 Load Balancer — distributes TCP connections across gateway
    instances. No HTTP awareness, no single point of failure if
    deployed as DNS round-robin or active-passive pair.

 2. Gateway Instance — the edge proxy. Runs the Chain of
    Responsibility pipeline (Phase 3 deep dive). Stateless;
    all config comes from the control plane.

 3. Sidecar Proxy — deployed alongside each service (one per
    pod in K8s). Intercepts all inbound and outbound traffic.
    Runs a subset of the gateway pipeline (mTLS, circuit
    breaker, LB, retry). No auth step because identity is
    established by mTLS certificate.

 4. Config Store — source of truth for routes, rate limits,
    circuit breaker thresholds, and traffic split weights.
    Backed by etcd or a relational database.

 5. Service Registry — tracks all service instances, their
    health status, and endpoints. Updated by health checks
    (active) or heartbeats (passive). Similar to Consul or
    Kubernetes endpoints.

 6. Certificate Authority (CA) — issues short-lived X.509
    certificates for mTLS. Each sidecar gets a cert identifying
    its service. The CA rotates certs before expiry (e.g., every
    24 hours). Similar to Istio's Citadel."
```

### Common follow-up questions for Phase 2

```
Q: "Why not put auth in the sidecar too?"
A: "For north-south traffic, auth must happen at the edge before
   the request enters the trusted mesh. For east-west traffic,
   identity is established by the mTLS certificate -- the sidecar
   knows the caller's service identity from the cert's SPIFFE ID.
   We don't need JWT validation inside the mesh."

Q: "Why a separate L4 LB in front of the gateway?"
A: "The gateway instances themselves are the L7 proxies. We need
   something to distribute traffic across them. An L4 LB (NLB,
   DNS round-robin, or BGP/ECMP) is cheap and adds <1ms latency.
   The alternative -- a single gateway -- is a single point of
   failure."

Q: "How does the sidecar intercept traffic?"
A: "On Kubernetes, iptables rules redirect all pod traffic through
   the sidecar. The sidecar listens on a local port, intercepts
   both inbound and outbound connections, applies policies, and
   forwards. This is transparent to the application."

Q: "What if we're not on Kubernetes?"
A: "On bare-metal or VMs, we use a daemon per host that intercepts
   traffic via iptables or a library-based approach (e.g., gRPC
   xDS client). The sidecar model still works -- it's just not
   auto-injected by an admission webhook."
```

---
---

## PHASE 3: DEEP DIVE -- GATEWAY PIPELINE (8-10 min)

### What this phase signals to the interviewer

```
STAFF SIGNAL: You walk through each stage of the request pipeline
with concrete implementation details: data structures, algorithms,
failure modes, and latency impact. You name the Chain of
Responsibility pattern and explain why each stage is ordered the
way it is.
```

### The Pipeline as Chain of Responsibility

The gateway pipeline is a Chain of Responsibility (GoF). Each
filter in the chain can either pass the request to the next filter
or short-circuit with an error response. The ordering is critical.

```
REQUEST PIPELINE (Chain of Responsibility)
═══════════════════════════════════════════════════════════

  HttpRequest
      │
      ▼
  ┌─────────────────┐
  │  1. TLS TERMIN. │  Decrypt HTTPS → plaintext HTTP
  │     (nginx/LB)  │  Certificate validation, SNI routing
  └────────┬────────┘
           │
           ▼
  ┌─────────────────┐
  │  2. ROUTE MATCH │  Path pattern → target service
  │  RoutingService │  Trie or regex matching
  └────────┬────────┘  404 if no match (FAST FAIL)
           │
           ▼
  ┌─────────────────┐
  │  3. AUTH (JWT)  │  Verify signature, check expiry
  │  AuthService    │  Extract principal + roles
  └────────┬────────┘  401 if invalid (FAST FAIL)
           │
           ▼
  ┌─────────────────┐
  │  4. AUTHZ       │  Check principal roles vs route
  │  AuthService    │  required-role metadata
  └────────┬────────┘  403 if forbidden (FAST FAIL)
           │
           ▼
  ┌─────────────────┐
  │  5. RATE LIMIT  │  Token bucket per route/client
  │  RateLimitSvc   │  Check & consume token
  └────────┬────────┘  429 if exhausted (FAST FAIL)
           │
           ▼
  ┌─────────────────┐
  │  6. CIRCUIT BRK │  Check state for target service
  │  CircuitBreaker │  CLOSED=pass, OPEN=reject
  │  Service        │  HALF_OPEN=probe
  └────────┬────────┘  503 if circuit OPEN (FAST FAIL)
           │
           ▼
  ┌─────────────────┐
  │  7. LOAD BAL.   │  Select instance from registry
  │  LoadBalancerSvc│  Strategy: RR, Weighted, ConsHash
  └────────┬────────┘  503 if no healthy instances
           │
           ▼
  ┌─────────────────┐
  │  8. FORWARD     │  HTTP call to selected instance
  │  (HTTP client)  │  Timeout + retry policy
  └────────┬────────┘
           │
           ▼
  ┌─────────────────┐
  │  9. RECORD      │  Update circuit breaker state
  │  CircuitBreaker │  Success → reset failures
  │  Service        │  Failure → increment failures
  └────────┬────────┘
           │
           ▼
  ┌─────────────────┐
  │ 10. RESPOND     │  Add X-Trace-Id header
  │  (build resp)   │  Add X-Gateway-Service header
  └─────────────────┘  Return to client
```

### Why this ordering matters

```
TALKING POINT:
"The stages are ordered by cost and fail-fast priority:

 1. TLS Termination — must happen first; we can't read headers
    without decrypting. Offloaded to the L4 LB or the gateway's
    TLS listener.

 2. Route Matching — cheapest check (O(path length) trie lookup).
    If no route matches, reject immediately. Don't waste CPU on
    auth or rate limiting for a nonexistent endpoint.

 3. Authentication — must happen before authorization. JWT
    signature verification is CPU-intensive (HMAC or RSA), but
    we need the principal identity for subsequent steps.

 4. Authorization — requires the auth result. Simple map lookup:
    does the principal's role set contain the route's required
    role? O(1) with a HashSet.

 5. Rate Limiting — must happen after auth so we can rate-limit
    per-client (using the authenticated principal as the key).
    Token bucket check is O(1).

 6. Circuit Breaker — must happen after rate limiting because
    a rate-limited request should not count against the circuit
    breaker. O(1) state lookup in a ConcurrentHashMap.

 7. Load Balancing — last step before forwarding. Selects a
    healthy instance. Round-robin is O(1), consistent hash is
    O(log N) for the TreeMap ceiling lookup.

 8-10. Forward, Record, Respond — the actual proxy operation."
```

### Stage 2: Route Matching -- Deep Dive

```
WHAT TO SAY:
"Routes are stored in a RequestRouter that matches incoming
requests by HTTP method and path pattern. Path patterns support
prefix matching (e.g., /api/users/** matches /api/users/123).

The matching algorithm:
 1. Filter routes by HTTP method (GET, POST, etc.)
 2. Among method-matched routes, find the longest prefix match
 3. Return the first matching Route object

In our codebase, Route contains:
 - id: unique route identifier (e.g., 'user-service-route')
 - pathPattern: the URL pattern ('/api/users/**')
 - targetService: logical service name ('user-service')
 - httpMethods: allowed HTTP methods
 - rateLimitPerSecond: per-route rate limit
 - metadata: key-value map (e.g., 'required-role' → 'admin')

At scale (1000+ routes), we'd use a radix trie for O(path length)
matching instead of linear scan. Kong uses a radix tree internally;
Envoy uses route tables compiled from xDS config."

CODE REFERENCE: RoutingService.matchRoute() delegates to
RequestRouter.match() which iterates registered routes.
```

### Stage 3: Authentication -- Deep Dive

```
WHAT TO SAY:
"Authentication uses the Strategy pattern (GoF). The AuthService
holds a pluggable AuthStrategy -- we can swap between JWT and
API key validation at runtime.

JWT validation flow:
 1. Extract the Authorization header (Bearer <token>)
 2. Base64-decode the header and payload
 3. Verify the signature using the public key (RSA) or shared
    secret (HMAC)
 4. Check expiry (exp claim) and not-before (nbf claim)
 5. Extract principal (sub claim) and roles (custom claim)
 6. Return AuthResult with isAuthenticated=true, principal,
    and roles

Performance optimization: Cache the JWKS (JSON Web Key Set) from
the IdP. Fetch new keys only when we encounter an unknown kid
(key ID). This avoids a network call on every request.

Latency impact: JWT validation is ~0.5ms for HMAC, ~2ms for RSA.
This is the most expensive stage in the pipeline."

CODE REFERENCE: AuthService.authenticate() delegates to
JwtAuthStrategy or ApiKeyAuthStrategy. Both implement AuthStrategy.
```

### Stage 5: Rate Limiting -- Deep Dive

```
WHAT TO SAY:
"Rate limiting uses a token bucket algorithm. Each route and
each client gets an independent bucket.

Token bucket mechanics (from our RateLimiterEngine):
 1. Each bucket has maxTokens (capacity) and refillRate (tokens/sec)
 2. Buckets start full
 3. On each request: refill based on elapsed time, then try to
    consume one token
 4. If tokens >= 1: consume and allow (return remaining count)
 5. If tokens < 1: deny and return retryAfterMs

The rate limiter is per-gateway-instance. For distributed rate
limiting across N gateway instances, we have two options:

Option A -- Local rate limiting:
 Set each instance's limit to globalLimit / N.
 Simple but inaccurate when traffic is uneven across instances.
 Example: 1000 req/s global, 3 instances → 333 req/s per instance.
 If one instance gets 50% of traffic, it over-limits.

Option B -- Centralized counter (Redis):
 All instances increment a shared counter in Redis.
 Accurate but adds ~1ms latency per request (Redis RTT).
 Use a Lua script for atomic check-and-decrement.
 This is what Kong and most production gateways use.

I'd default to Option B for production, with a local fallback
if Redis is unavailable (fail-open with local limits)."

CODE REFERENCE: RateLimitService.checkRouteRateLimit() calls
RateLimiterEngine.tryConsume() which implements the token bucket.
```

### Stage 6: Circuit Breaker -- Deep Dive

```
WHAT TO SAY:
"The circuit breaker protects upstream services from cascading
failures. It implements the standard three-state machine:

  CLOSED (normal operation)
    │  Every request passes through
    │  Failures are counted
    │  When failureCount >= failureThreshold (default: 5)
    ▼
  OPEN (service is failing)
    │  All requests are immediately rejected with 503
    │  No load sent to the failing service
    │  After openDurationMs (default: 30s)
    ▼
  HALF_OPEN (probing recovery)
    │  Allow a limited number of probe requests
    │  If successCount >= successThreshold (default: 3) → CLOSED
    │  If any failure → back to OPEN

The circuit breaker is per-service (not per-instance). If Service B
is failing, the circuit opens for ALL instances of Service B.

In our CircuitBreakerEngine:
 - ConcurrentHashMap<String, CircuitBreakerState> stores per-service state
 - allowRequest() checks state and transitions OPEN → HALF_OPEN on timeout
 - recordSuccess() and recordFailure() update counts and trigger transitions
 - shouldAttemptReset() checks if openDurationMs has elapsed

Why per-service and not per-instance?
 If one instance of Service B is failing, it might be a deployment
 issue affecting all instances. Per-service circuit breaking prevents
 the gateway from hammering a service that's globally unhealthy.
 For instance-level isolation, we rely on the load balancer removing
 unhealthy instances from the pool."

DRAW THIS STATE MACHINE:
  ┌────────────────────────────────────────┐
  │                                        │
  │   ┌─────────┐   failures >= 5   ┌─────┴───┐
  │   │ CLOSED  │ ────────────────► │  OPEN   │
  │   │ (allow) │                   │ (reject)│
  │   └────┬────┘ ◄──success >= 3── └────┬────┘
  │        │       ┌───────────┐         │
  │        │       │ HALF_OPEN │         │
  │        │       │  (probe)  │ ◄───────┘
  │        │       └─────┬─────┘   after 30s
  │        │             │
  │        │   failure   │
  │        │   in probe  │
  │        └─────────────┘ (back to OPEN)
  └────────────────────────────────────────┘

CODE REFERENCE: CircuitBreakerEngine.allowRequest(),
CircuitBreakerState.recordSuccess(), CircuitBreakerState.recordFailure()
```

### Stage 7: Load Balancing -- Deep Dive

```
WHAT TO SAY:
"Load balancing uses the Strategy pattern (GoF). The
LoadBalancerService holds a pluggable LoadBalancingStrategy.
We support three strategies:

1. Round Robin (RoundRobinLoadBalancer)
   - Simplest. Cycles through healthy instances using an
     AtomicInteger counter mod instance count.
   - O(1) selection.
   - Good for stateless services with uniform instance capacity.

2. Weighted (WeightedLoadBalancer)
   - Each instance has a weight (e.g., large instance = weight 3,
     small instance = weight 1).
   - Weighted random selection: sum weights, pick random in range,
     walk through instances accumulating weight.
   - O(N) selection but N is small (typically < 20 instances).
   - Good for heterogeneous instance sizes.

3. Consistent Hash (ConsistentHashLoadBalancer)
   - Builds a virtual-node ring (TreeMap) with 150 virtual nodes
     per physical instance.
   - Hashes the request path using FNV-1a.
   - Finds the ceiling entry in the TreeMap (O(log(N * vnodes))).
   - The same path always routes to the same instance (cache
     affinity).
   - When an instance is added/removed, only ~1/N of requests
     are redistributed.
   - Best for services with local caches (e.g., product catalog
     service caching by product ID).

The service registry provides the list of healthy instances.
Health is determined by active health checks (HTTP GET /health)
or passive health checks (tracking 5xx response rates)."

DRAW THIS:
  Consistent Hash Ring:
  ┌──────────────────────────────────────┐
  │            Hash Ring (TreeMap)        │
  │                                      │
  │     ●A-vnode-0                       │
  │   ●C-vnode-47      ●B-vnode-12      │
  │                                      │
  │ ●B-vnode-88    request("/api/user")  │
  │                  hash = 4821         │
  │     ●A-vnode-33  → ceiling = A-33   │
  │                  → route to A        │
  │   ●C-vnode-71                        │
  │                     ●A-vnode-99      │
  │                                      │
  └──────────────────────────────────────┘

CODE REFERENCE: ConsistentHashLoadBalancer.selectInstance()
uses TreeMap.ceilingEntry() for O(log N) lookup.
```

### Full pipeline trace (say aloud)

```
TALKING POINT:
"Let me trace a concrete request through the pipeline:

 1. Client sends: GET /api/orders/123
    Header: Authorization: Bearer eyJhbGciOiJSUzI1NiJ9...
    Client IP: 10.0.1.50

 2. Route match: /api/orders/** → target 'order-service'
    (O(1) trie lookup, ~0.01ms)

 3. JWT auth: decode token, verify RSA signature, extract
    principal='user-42', roles=['customer']
    (~2ms for RSA-256 verification)

 4. Authz: route requires role 'customer', user has it → pass
    (~0.001ms hash set lookup)

 5. Rate limit: key='order-service-route', 100 tokens/sec,
    current=73 → consume one, allow (remaining=72)
    (~0.01ms local, ~1ms if Redis-backed)

 6. Circuit breaker: order-service state=CLOSED → allow
    (~0.001ms ConcurrentHashMap lookup)

 7. Load balance: consistent hash on '/api/orders/123'
    → FNV hash → ceiling entry → instance 10.0.2.15:8080
    (~0.01ms TreeMap lookup)

 8. Forward: HTTP GET to 10.0.2.15:8080/api/orders/123
    with traceId header. Timeout=5s, retry=1.
    (~50ms upstream latency)

 9. Record: response 200 → recordSuccess('order-service')
    → failure count reset to 0

10. Respond: 200 OK with X-Trace-Id and X-Gateway-Service headers

Total gateway overhead: ~3ms (dominated by JWT verification)
Total end-to-end: ~53ms"
```

### Common follow-up questions for Phase 3

```
Q: "What if JWT verification is too slow?"
A: "Three optimizations:
    1. Use HMAC-SHA256 instead of RSA (10x faster, ~0.2ms)
    2. Cache validated tokens in a local LRU cache with TTL
       matching the token's remaining lifetime
    3. For internal services, skip JWT entirely -- use mTLS
       identity from the sidecar"

Q: "What about request transformation -- path rewriting,
    header injection?"
A: "Add a Transform stage between Route Match and Auth. The
   Route object can carry rewrite rules (e.g., strip /api/v1
   prefix before forwarding). Header injection (X-Request-Id,
   X-Forwarded-For) happens in the Forward stage."

Q: "Can the pipeline stages be reordered?"
A: "Some stages have hard dependencies:
    - Auth before Authz (need identity first)
    - Auth before Rate Limit (need client key for per-client limits)
    - Route before everything (need to know the target service)
   But Rate Limit and Circuit Breaker could be swapped. I put
   Rate Limit first because it's cheaper than Circuit Breaker
   (which may involve timestamp comparisons for OPEN → HALF_OPEN
   transitions)."

Q: "How do you handle request/response body transformation?"
A: "The gateway should NOT inspect or transform request bodies
   unless absolutely necessary (e.g., GraphQL query analysis).
   Body transformation adds latency and memory pressure. If
   needed, use a streaming approach -- don't buffer the entire
   body."
```

---
---

## PHASE 4: DEEP DIVE -- SERVICE MESH (5-7 min)

### What this phase signals to the interviewer

```
STAFF SIGNAL: You explain the sidecar proxy model with specifics
on mTLS, per-service circuit breakers, and the difference between
gateway-level and mesh-level concerns. You show you understand
the Envoy/Istio architecture deeply.
```

### Sidecar Proxy Model

```
WHAT TO SAY:
"In the service mesh, every service gets a sidecar proxy deployed
alongside it. On Kubernetes, this is a second container in the
same pod, injected automatically by a mutating admission webhook.

The sidecar intercepts ALL network traffic to and from the
application container using iptables rules. The application does
not know the sidecar exists -- it just makes normal HTTP/gRPC
calls to localhost or to service DNS names.

┌────────────────────────────── Pod ──────────────────────────────┐
│                                                                 │
│  ┌─────────────────┐    iptables     ┌─────────────────────┐   │
│  │  Application    │  ────────────►  │  Sidecar Proxy      │   │
│  │  Container      │                 │  (Envoy)            │   │
│  │                 │  ◄────────────  │                     │   │
│  │  Listens on     │    iptables     │  Intercepts all     │   │
│  │  :8080          │                 │  inbound + outbound │   │
│  │                 │                 │                     │   │
│  │  Makes calls to │                 │  Applies:           │   │
│  │  other services │                 │  - mTLS             │   │
│  │  via normal DNS │                 │  - Circuit breaker  │   │
│  │                 │                 │  - Load balancing   │   │
│  │                 │                 │  - Retry / timeout  │   │
│  │                 │                 │  - Observability    │   │
│  └─────────────────┘                 └─────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘

The sidecar runs a SUBSET of the gateway pipeline:
 - NO route matching (the application already knows the target)
 - NO JWT auth (identity comes from mTLS certificate)
 - NO authz against route metadata
 - YES mTLS validation (verify caller and target certificates)
 - YES circuit breaker (per-service, same state machine)
 - YES load balancing (select instance of target service)
 - YES retry with exponential backoff
 - YES timeout enforcement
 - YES observability (emit spans, metrics, access logs)"
```

### mTLS -- Mutual TLS Deep Dive

```
WHAT TO SAY:
"mTLS is the foundation of service mesh security. Every service
gets an X.509 certificate that identifies it. Both sides of a
connection present and validate certificates.

The flow for Service A calling Service B:

 1. Service A's sidecar initiates a TLS handshake with Service B's
    sidecar.

 2. Service B's sidecar presents its certificate:
    - Subject: spiffe://cluster.local/ns/default/sa/service-b
    - Issuer: the mesh CA (our Certificate Authority)
    - Expiry: 24 hours from issuance

 3. Service A's sidecar validates:
    - Is the certificate signed by our CA? (chain of trust)
    - Is the certificate expired? (time check)
    - Is the SPIFFE ID in the allowed callers list? (authorization)

 4. Service A's sidecar presents its OWN certificate to Service B.
    Service B validates it the same way.

 5. If both validations pass, the TLS session is established and
    all data is encrypted in transit.

Certificate rotation:
 - Certificates are short-lived (24 hours).
 - The sidecar requests a new cert from the CA before the old
   one expires (e.g., at 50% lifetime).
 - Rotation is seamless -- new connections use the new cert,
   existing connections continue with the old cert until they
   close.
 - If the CA is unreachable, the sidecar continues using the
   current cert until expiry. This is a graceful degradation.

In our codebase, TlsEngine.validateConnection() checks that both
the caller and target are in the trusted services set. In a real
system, this would be a full X.509 certificate chain validation."

DRAW THIS:
  mTLS Handshake:

  Service A (sidecar)              Service B (sidecar)
       │                                │
       │──── ClientHello ──────────────►│
       │                                │
       │◄─── ServerHello + CertB ──────│
       │                                │
       │  Validate CertB:              │
       │  - Signed by mesh CA? ✓       │
       │  - Not expired? ✓             │
       │  - SPIFFE ID allowed? ✓       │
       │                                │
       │──── CertA + Finished ─────────►│
       │                                │
       │                    Validate CertA:
       │                    - Signed by mesh CA? ✓
       │                    - Not expired? ✓
       │                    - SPIFFE ID allowed? ✓
       │                                │
       │◄─── Finished ────────────────│
       │                                │
       │◄═══ Encrypted Channel ═══════►│
       │     (all data encrypted)       │
```

### Sidecar Pipeline -- Full Request Flow

```
WHAT TO SAY:
"When Service A calls Service B through the mesh, here is the
full sidecar pipeline:

Step 1: Service A's application makes HTTP call to
        http://service-b.default.svc.cluster.local:8080/api/data

Step 2: iptables intercepts the outbound connection and redirects
        it to Service A's sidecar (port 15001).

Step 3: Service A's OUTBOUND sidecar pipeline:
  a. DNS resolve → Service B's cluster IP
  b. mTLS: initiate handshake, present Service A's certificate
  c. Load balance: select one of Service B's instances
     (using the configured strategy: round-robin, weighted,
     or consistent hash)
  d. Forward the request over the mTLS connection

Step 4: The request arrives at Service B's sidecar (port 15006).
        Service B's INBOUND sidecar pipeline:
  a. mTLS: validate Service A's certificate
  b. Authorization: is Service A allowed to call Service B?
     (checked against the service mesh authorization policy)
  c. Circuit breaker: is Service B healthy? If OPEN → 503
  d. Forward to the local application on :8080

Step 5: The application processes the request and returns a
        response through the reverse path.

Step 6: Service A's sidecar records the result:
  - Success → circuit breaker recordSuccess()
  - Failure → circuit breaker recordFailure()
  - Emit a span to the tracing collector
  - Increment request/error/duration metrics"

CODE REFERENCE: ServiceMeshService.proxyRequest() implements this
pipeline: mTLS validation → circuit breaker → load balance →
forward → record result.
```

### Consistent Hash Load Balancing in the Mesh

```
WHAT TO SAY:
"For services with local caches, we use consistent hash load
balancing in the sidecar. This ensures the same request key
(e.g., user ID, product ID) consistently routes to the same
service instance, maximizing cache hit rates.

The consistent hash implementation uses a virtual-node ring:
 1. Each service instance gets 150 virtual nodes on the ring
    (hash of 'instanceId-vnode-N' using FNV-1a)
 2. The request's hash key (path, header, or query param) is
    hashed
 3. TreeMap.ceilingEntry() finds the next virtual node clockwise
 4. That virtual node maps to a physical instance

Why 150 virtual nodes?
 - With too few virtual nodes (e.g., 1 per instance), the key
   distribution is uneven -- some instances get 3x the load.
 - 150 virtual nodes per instance gives <10% deviation from
   perfect balance with up to 100 instances.
 - More virtual nodes = more memory (150 * N entries in the
   TreeMap) but still trivial for N < 1000.

When an instance is added:
 - 150 new virtual nodes are placed on the ring
 - Only ~1/N of existing keys are remapped
 - Existing cache entries on other instances remain valid

When an instance is removed:
 - Its 150 virtual nodes are removed from the ring
 - Only its ~1/N share of keys are redistributed
 - Much better than round-robin where ALL keys would shift"

CODE REFERENCE: ConsistentHashLoadBalancer builds the ring in
buildRing() and selects via TreeMap.ceilingEntry() in selectInstance().
```

### Circuit Breaker in the Mesh (per-service)

```
WHAT TO SAY:
"The mesh circuit breaker operates identically to the gateway's
circuit breaker but with a different scope:

Gateway circuit breaker:
 - Protects against upstream failures for north-south traffic
 - One breaker per target service, shared across all routes
 - When tripped, returns 503 to external clients

Mesh circuit breaker (per-sidecar):
 - Protects against downstream failures for east-west traffic
 - One breaker per target service, per calling sidecar
 - When tripped, returns 503 to the calling service
 - The calling service can implement its own fallback logic

Important distinction: in the mesh, each sidecar maintains its
OWN circuit breaker state for each target service. Service A's
sidecar might have Service B's circuit OPEN while Service C's
sidecar has it CLOSED. This is intentional -- network partitions
and load imbalances mean different callers may see different
failure rates.

The circuit breaker shares the same state machine:
 - CLOSED: failureThreshold=5, all requests pass
 - OPEN: openDurationMs=30s, all requests rejected
 - HALF_OPEN: successThreshold=3, probe requests only

We combine circuit breaking with retry logic:
 - If a request fails and the circuit is CLOSED, retry once
   (to handle transient failures)
 - If the retry also fails, record failure and potentially trip
 - If the circuit is OPEN, do NOT retry -- fail immediately
 - If the circuit is HALF_OPEN, do NOT retry -- one probe at a time"
```

### Common follow-up questions for Phase 4

```
Q: "What's the latency overhead of the sidecar?"
A: "Measured in production (Istio benchmarks): ~1-3ms per hop
   for mTLS + proxy overhead. For a call chain of 5 services,
   that's ~5-15ms added. Acceptable for most microservice
   architectures where individual service latency is 10-100ms."

Q: "How do you handle services that don't support mTLS?"
A: "The mesh supports PERMISSIVE mode: accept both plaintext and
   mTLS connections. This allows gradual migration. Services
   already in the mesh get mTLS; legacy services get plaintext.
   The sidecar can also handle TLS origination -- the application
   sends plaintext to the sidecar, and the sidecar encrypts it."

Q: "What about gRPC and WebSocket traffic?"
A: "Envoy-based sidecars natively support HTTP/2 (gRPC) and
   WebSocket upgrades. The sidecar detects the protocol from
   the connection and applies the appropriate filter chain.
   gRPC load balancing is per-stream (not per-connection), which
   is important because gRPC uses long-lived HTTP/2 connections."

Q: "How does service discovery work in the mesh?"
A: "On Kubernetes, the control plane watches the Kubernetes API
   server for endpoint changes. When a pod starts/stops, the
   control plane pushes updated endpoint lists to all sidecars
   via xDS (Envoy Discovery Service). The sidecar's load balancer
   always has a fresh list of healthy instances."

Q: "What if the sidecar crashes?"
A: "If the sidecar process crashes, Kubernetes restarts it (since
   it's a container in the pod). During the restart (~1-2 seconds),
   iptables rules still redirect traffic to the dead sidecar port,
   so the application's outbound calls will fail with connection
   refused. The application should have retry logic or the calling
   sidecar will retry. For inbound traffic, the service is
   effectively down until the sidecar restarts."
```

---
---

## PHASE 5: TRAFFIC MANAGEMENT (3-5 min)

### What this phase signals to the interviewer

```
STAFF SIGNAL: You go beyond basic routing to explain production
deployment strategies: canary, blue-green, and A/B testing. You
describe automated rollback conditions and gradual rollout
mechanics. This is what separates infra architects from
application developers.
```

### Canary Deployments

```
WHAT TO SAY:
"A canary deployment gradually shifts traffic from the stable
version to the new version, monitoring error rates and latency
at each step. If the canary shows degradation, we automatically
roll back.

The mechanism uses our TrafficSplit model:

Phase 1: Deploy canary alongside stable
  TrafficSplit: { 'v1-stable': 100, 'v2-canary': 0 }
  v2 is deployed but receives zero traffic.

Phase 2: Initial canary exposure (1-5%)
  TrafficSplit: { 'v1-stable': 95, 'v2-canary': 5 }
  Monitor for 10 minutes:
  - Error rate for v2-canary vs v1-stable
  - p99 latency for v2-canary vs v1-stable
  - Business metrics (conversion rate, etc.)

Phase 3: Gradual increase (5% → 25% → 50% → 100%)
  TrafficSplit: { 'v1-stable': 75, 'v2-canary': 25 }
  Each step: monitor for N minutes, proceed if healthy.

Phase 4: Full rollout
  TrafficSplit: { 'v2-canary': 100 }
  v1-stable instances can be decommissioned.

Automated rollback trigger:
  IF v2_error_rate > v1_error_rate * 1.5
  OR v2_p99_latency > v1_p99_latency * 2.0
  THEN revert to { 'v1-stable': 100, 'v2-canary': 0 }

The CanaryTrafficStrategy in our codebase implements weighted
random selection: sum all version weights, pick a random number,
walk through entries accumulating weight until the random is
exhausted. This gives exact percentage splits."

DRAW THIS:
  Canary Rollout Timeline:

  Traffic %
  100│  ████████████████████████████                    ████████
     │  █  v1-stable  █                               █ v2 new █
   75│  ████████████████                               █ stable █
     │                  ████████                       █████████
   50│                  █ v1    █
     │                  ████████  ████████
   25│                           █ v1    █
     │                           ████████
    5│  ░░░░░░░░░░░░░░░░░░░░░░░░         ████████
     │  ░  v2-canary (monitor) ░         █ v2    █
    0├──────────────────────────────────────────────── time
     t0   t0+10min  t0+20min  t0+30min  t0+40min
         5%        25%        50%       100%

CODE REFERENCE: CanaryTrafficStrategy.selectVersion() and
TrafficSplit.selectVersion() implement weighted random selection.
```

### Blue-Green Deployments

```
WHAT TO SAY:
"Blue-green is simpler than canary: we run two identical
environments (blue and green). At any time, one is live and
the other is idle.

The flow:
 1. Blue is currently live (receiving 100% of traffic)
 2. Deploy the new version to Green
 3. Run smoke tests and health checks against Green
 4. Flip the traffic split: { 'green': 100, 'blue': 0 }
 5. If problems arise, flip back: { 'blue': 100, 'green': 0 }

The flip is atomic -- there's no gradual transition. This is
implemented as a TrafficSplit update pushed from the control
plane to all gateway instances and sidecars.

Advantages over canary:
 - Instant rollback (just flip the split)
 - Simple to reason about (two known states)
 - No mixed-version traffic

Disadvantages:
 - Double the infrastructure cost (two full environments)
 - No gradual validation -- you're either 0% or 100%
 - Database schema changes are hard (both versions must be
   compatible with the same schema)

When to use blue-green vs canary:
 - Blue-green: for critical services where you need instant
   rollback and can afford the infrastructure cost.
 - Canary: for high-traffic services where you want to validate
   with real production traffic before full rollout."
```

### A/B Testing (Header-Based Routing)

```
WHAT TO SAY:
"A/B testing routes requests based on request attributes (headers,
cookies, query parameters) rather than random weights.

Example: route users with header 'X-Feature-Group: beta' to v2,
all others to v1.

The HeaderBasedTrafficStrategy checks the request for a specific
header and routes accordingly:

 IF request.header('X-Feature-Group') == 'beta'
   THEN route to v2
   ELSE route to v1

This is different from canary because:
 - Canary: random N% of ALL users get the new version
 - A/B: SPECIFIC users (identified by header/cookie) get v2
 - A/B gives deterministic results -- the same user always sees
   the same version

Implementation in the gateway pipeline:
 1. Route matching identifies the target service
 2. Before load balancing, check if a traffic policy exists
 3. If header-based: read the routing header, select version
 4. If canary: use weighted random selection
 5. The version maps to a specific set of instances (e.g.,
    v2 instances are tagged in the service registry)

The control plane manages the traffic policy and pushes it to
all proxies. Operators create policies via API:
  POST /traffic-policies
  {
    'service': 'checkout-service',
    'strategy': 'header-based',
    'header': 'X-Feature-Group',
    'mappings': { 'beta': 'v2', 'default': 'v1' }
  }"
```

### Automated Rollback Pipeline

```
WHAT TO SAY:
"For production safety, canary deployments need automated
rollback. Here's the pipeline I'd build:

┌─────────────┐     ┌──────────────┐     ┌─────────────────┐
│  Deploy     │     │  Traffic     │     │  Monitor        │
│  Canary     │────►│  Shift 5%   │────►│  (10 min window)│
│  (v2)       │     │              │     │                 │
└─────────────┘     └──────────────┘     └────────┬────────┘
                                                   │
                                          ┌────────┴────────┐
                                          │  Analyze:       │
                                          │  error_rate?    │
                                          │  p99_latency?   │
                                          │  biz_metrics?   │
                                          └────────┬────────┘
                                                   │
                                        ┌──────────┴──────────┐
                                        │                     │
                                     HEALTHY              DEGRADED
                                        │                     │
                                        ▼                     ▼
                                  ┌───────────┐       ┌──────────────┐
                                  │ Increase  │       │  Rollback    │
                                  │ to 25%    │       │  to 0%       │
                                  │ (next     │       │  Alert oncall│
                                  │  phase)   │       │              │
                                  └───────────┘       └──────────────┘

Rollback conditions (any one triggers rollback):
 1. Error rate > baseline * 1.5 (50% increase)
 2. p99 latency > baseline * 2.0 (doubled)
 3. Canary pod restarts > 0 (crash loop)
 4. Canary health check failures > 0
 5. Business metric drop > 5% (e.g., checkout completion rate)

The monitoring system (Prometheus + custom rules) evaluates these
conditions every 30 seconds during the canary window. If any
condition is violated, the control plane automatically reverts the
TrafficSplit and pages the on-call engineer."
```

### Common follow-up questions for Phase 5

```
Q: "How do you handle database migrations during canary?"
A: "Use the expand-and-contract pattern:
    1. Expand: add new columns/tables (backward compatible)
    2. Deploy canary writing to both old and new schema
    3. Migrate: backfill new schema from old data
    4. Contract: remove old columns after full rollout
   Both v1 and v2 must be able to read/write the database at
   every step."

Q: "What about sticky sessions during canary?"
A: "Use consistent hash routing on a session identifier (cookie
   or user ID). Once a user is assigned to v2, they stay on v2
   for the duration of the canary. This prevents users from
   flipping between versions mid-session."

Q: "How does this work with WebSocket connections?"
A: "WebSocket connections are long-lived. A traffic split change
   only affects NEW connections. Existing WebSocket connections
   continue on their current version until they close. This means
   canary rollout for WebSocket services is slower -- you may
   need to actively drain connections from v1."

Q: "What's the minimum canary window?"
A: "Depends on traffic volume. You need enough requests to be
   statistically significant. At 1000 req/s with 5% canary,
   that's 50 req/s to the canary. In 10 minutes, you get 30K
   requests -- enough to detect a 1% error rate increase with
   high confidence. For low-traffic services, you may need
   longer windows or higher canary percentages."
```

---
---

## PHASE 6: SCALING & TRADEOFFS (3-5 min)

### What this phase signals to the interviewer

```
STAFF SIGNAL: You reason about scaling dimensions, articulate
tradeoffs with concrete numbers, and name the failure modes of
each architectural choice. You don't just say "scale horizontally"
-- you explain what breaks and how to fix it.
```

### Horizontal Scaling of the Gateway

```
WHAT TO SAY:
"The gateway scales horizontally by adding more instances behind
the L4 load balancer. Each instance is stateless -- all
configuration comes from the control plane, all rate limit state
is in Redis.

Scaling dimensions:
 1. CPU-bound: JWT verification is CPU-intensive (~2ms RSA per
    request). At 50K req/s, that's 100 CPU-seconds/s. With 8-core
    instances, we need ~13 instances just for JWT.

 2. Connection-bound: each gateway instance can handle ~10K
    concurrent connections (with epoll/kqueue). At 50K req/s with
    100ms average request duration, we have ~5K concurrent
    connections. Comfortable on 2-3 instances.

 3. Memory-bound: route tables, rate limit buckets, and circuit
    breaker state are all lightweight. 1000 routes + 10K rate
    limit buckets + 100 circuit breakers < 50MB.

The bottleneck is usually CPU (JWT verification) or the external
rate limit store (Redis). Solutions:
 - For CPU: use HMAC-SHA256 instead of RSA, or offload JWT to a
   dedicated auth service.
 - For Redis: use Redis Cluster with 3-6 shards, or accept local
   rate limiting with eventual consistency."
```

### Edge vs. Centralized Gateway

```
WHAT TO SAY:
"There are two deployment models for the gateway:

Edge Gateway (our design):
 ┌─────────┐     ┌─────────┐     ┌─────────┐
 │ Gateway │     │ Gateway │     │ Gateway │
 │ (AZ-1)  │     │ (AZ-2)  │     │ (AZ-3)  │
 └────┬────┘     └────┬────┘     └────┬────┘
      │               │               │
      └───────── Service Mesh ────────┘

 - Gateway instances deployed across availability zones
 - Each AZ has independent gateway instances
 - L4 LB distributes across AZs
 - If an AZ fails, DNS health checks route away from it

Centralized Gateway (alternative):
 ┌─────────────────────────┐
 │   Single Gateway Cluster│
 │   (all traffic through  │
 │    one location)        │
 └────────────┬────────────┘
              │
              └──── Service Mesh ────

 - All traffic goes through one gateway cluster
 - Simpler to manage and debug
 - Single point of failure (SPOF)
 - Higher latency for geographically distributed clients

Tradeoff matrix:
  ┌────────────────┬─────────────────────┬───────────────────┐
  │  Dimension     │  Edge (multi-AZ)    │  Centralized      │
  ├────────────────┼─────────────────────┼───────────────────┤
  │  Availability  │  ★★★ (AZ-resilient) │  ★ (SPOF)         │
  │  Latency       │  ★★★ (close to user)│  ★★ (one hop)     │
  │  Complexity    │  ★★ (more ops)      │  ★★★ (simple)     │
  │  Rate limiting │  ★★ (distributed)   │  ★★★ (centralized)│
  │  Cost          │  ★★ (3x infra)      │  ★★★ (1x infra)   │
  │  Debugging     │  ★★ (multi-region)  │  ★★★ (one place)  │
  └────────────────┴─────────────────────┴───────────────────┘

I'd choose edge deployment for production because availability
and latency outweigh operational complexity."
```

### Fail-Open vs. Fail-Closed Rate Limiting

```
WHAT TO SAY:
"When the centralized rate limit store (Redis) is unavailable,
we face a critical decision:

Fail-OPEN (allow all requests):
 - Pro: no downtime for legitimate users
 - Con: unprotected against abuse during Redis outage
 - Con: could overwhelm backend services
 - When to use: for user-facing APIs where availability trumps
   protection. A Redis outage is minutes; DDoS protection can
   come from the L4 LB / CDN.

Fail-CLOSED (reject all requests):
 - Pro: guaranteed rate limit enforcement
 - Con: legitimate users get 503 during Redis outage
 - Con: a Redis failure cascades to a full outage
 - When to use: for billing APIs, payment endpoints, or any
   endpoint where over-serving is worse than under-serving.

Hybrid approach (my recommendation):
 - Primary: centralized Redis rate limiting
 - Fallback: local per-instance rate limiting (globalLimit / N)
 - If Redis is unreachable, switch to local limits within 1 second
 - Local limits are less accurate but prevent total protection loss

In code, the rate limiter would:
  try {
      result = redis.checkAndDecrement(key, limit);
  } catch (RedisUnavailableException e) {
      result = localRateLimiter.checkAndDecrement(key, localLimit);
      alerting.fire('rate-limit-redis-fallback');
  }

This is what I'd recommend for production."
```

### Sidecar Resource Overhead at Scale

```
WHAT TO SAY:
"Each sidecar proxy (Envoy) consumes resources:
 - CPU: ~50-100 millicores per sidecar at moderate load
 - Memory: ~50-100MB per sidecar (route tables, connection pools)
 - Network: ~1-3ms latency added per hop

With 100 services and 3 instances each = 300 sidecars:
 - Total CPU overhead: 15-30 cores dedicated to sidecars
 - Total memory overhead: 15-30 GB across the cluster
 - This is ~5-10% of cluster resources for a typical deployment

Mitigation strategies:
 1. Right-size sidecar resource limits (not all sidecars need
    the same allocation -- high-traffic services get more)
 2. Use Envoy's connection pooling to reduce per-connection
    overhead (HTTP/2 multiplexing over fewer TCP connections)
 3. For ultra-low-latency services, consider ambient mesh
    (Istio's model where a per-node proxy replaces per-pod
    sidecars, reducing the proxy count from N pods to N nodes)

When NOT to use a sidecar mesh:
 - Fewer than 10 services (use a gateway only -- the mesh
   overhead is not justified)
 - Extremely latency-sensitive workloads (<1ms budgets)
 - Batch processing jobs that make few network calls"
```

### Control Plane Scalability

```
WHAT TO SAY:
"The control plane must push configuration to all proxies. At
scale, this becomes a fan-out problem:

 - 300 sidecars + 9 gateway instances = 309 proxies
 - Each proxy needs route tables, endpoint lists, rate limit
   configs, circuit breaker thresholds, and TLS certificates
 - When a deployment happens, endpoint lists change and all
   309 proxies need an update within seconds

Scaling the control plane:
 1. Incremental xDS: don't push the full config on every change.
    Push only the delta (Envoy's incremental ADS protocol).

 2. Sharded control plane: partition proxies across control plane
    instances. Each control plane instance manages a subset of
    proxies. Consistent hashing on proxy ID determines the
    assignment.

 3. Configuration caching: proxies cache config locally and only
    request updates when the control plane pushes a version bump.
    If the control plane restarts, proxies continue with cached
    config.

 4. Separate concerns: route changes are rare (config push).
    Endpoint changes are frequent (pod scaling). Use different
    update channels with different frequencies:
    - Routes: push on admin API change (rare)
    - Endpoints: push on Kubernetes watch event (frequent)
    - Certificates: push on rotation schedule (every 12-24h)"
```

### Common follow-up questions for Phase 6

```
Q: "What's the gateway's blast radius if it has a bug?"
A: "The gateway is on the critical path for ALL north-south
   traffic. A bug (memory leak, infinite loop) affects every
   external user. Mitigations:
    1. Canary the gateway itself (deploy new gateway version to
       1 of 9 instances first)
    2. Health checks with auto-rollback (if error rate spikes,
       L4 LB removes the bad instance)
    3. Rate limit the gateway's own resource usage (connection
       limits, request body size limits)"

Q: "How do you handle TLS certificate expiry at scale?"
A: "Short-lived certificates (24h) with automated rotation at
   50% lifetime (12h). The CA issues new certs proactively. If
   rotation fails, alerts fire at 75% lifetime (18h). At 90%
   (21.6h), the on-call engineer is paged. This gives a 2.4h
   window to manually fix before expiry."

Q: "What about multi-cluster / multi-region?"
A: "Each cluster runs its own control plane and data plane. For
   cross-cluster traffic, use a federated service registry that
   aggregates endpoints from multiple clusters. The gateway in
   cluster A can route to services in cluster B by resolving
   their endpoints through the federation layer. Latency between
   clusters is higher, so use locality-aware routing (prefer
   local instances, fall back to remote)."
```

---
---

## PHASE 7: EDGE CASES (2-3 min)

### What this phase signals to the interviewer

```
STAFF SIGNAL: You proactively name failure modes that most
candidates miss. Each edge case comes with a concrete solution,
not just a problem statement. You show production experience.
```

### Edge Case 1: Gateway as Single Point of Failure

```
WHAT TO SAY:
"The gateway is NOT a single point of failure because:

 1. Multiple instances behind an L4 LB. If one instance crashes,
    the LB routes around it within the health check interval
    (typically 5-10 seconds).

 2. Multi-AZ deployment. Each AZ has independent gateway instances.
    An AZ failure (network partition, power) does not affect other
    AZs.

 3. The L4 LB itself is the potential SPOF. Solutions:
    a. DNS-based LB (multiple A records, client retries) -- no
       single LB device
    b. AWS NLB / GCP LB -- managed, highly available
    c. BGP/ECMP -- traffic is load-balanced at the network layer
       across multiple LB nodes

 4. Graceful degradation: if ALL gateway instances fail (extremely
    rare), the DNS TTL determines how long clients continue
    sending to the dead endpoints. Set DNS TTL to 60 seconds so
    clients discover the failure quickly.

The real SPOF risk is the control plane -- but since the data
plane operates independently with cached config, a control plane
outage only prevents configuration CHANGES, not traffic flow."
```

### Edge Case 2: Distributed Rate Limiting Across Gateway Instances

```
WHAT TO SAY:
"With N gateway instances and centralized rate limiting in Redis,
there are three failure modes:

Problem 1: Race conditions
 Two gateway instances check Redis simultaneously. Both see 1
 token remaining. Both decrement. The counter goes to -1.
 Solution: use Redis Lua script for atomic check-and-decrement:
   local tokens = redis.call('GET', key)
   if tonumber(tokens) > 0 then
     redis.call('DECR', key)
     return 1  -- allowed
   else
     return 0  -- denied
   end

Problem 2: Redis latency under load
 At 50K req/s, each rate limit check adds ~1ms Redis RTT.
 That's 50K Redis operations per second -- within Redis's
 capacity (100K+ ops/s for single-key operations).
 But under burst traffic, Redis latency may spike.
 Solution: use Redis Pipeline to batch multiple rate limit
 checks in one round trip, or use local caching with periodic
 sync.

Problem 3: Token bucket refill accuracy
 With N instances sharing a bucket, the refill rate must be
 applied once (not N times). The Lua script handles this:
   local lastRefill = redis.call('GET', key .. ':lastRefill')
   local elapsed = now - lastRefill
   local tokensToAdd = elapsed * refillRate
   redis.call('INCRBY', key, tokensToAdd)
   redis.call('SET', key .. ':lastRefill', now)

Problem 4: Redis failure during rate limiting
 As discussed in Phase 6: fall back to local rate limiting
 with limit = globalLimit / N. Accept the inaccuracy until
 Redis recovers."
```

### Edge Case 3: Circuit Breaker Synchronization

```
WHAT TO SAY:
"In the gateway, each instance has its own circuit breaker state.
This means different instances may have different views of a
service's health.

Scenario: Service B is failing intermittently.
 - Gateway Instance 1 has seen 5 failures → circuit OPEN
 - Gateway Instance 2 has seen 2 failures → circuit CLOSED
 - Gateway Instance 3 has seen 4 failures → circuit CLOSED

This is actually DESIRABLE in most cases:
 - If Service B is partially failing (some instances unhealthy),
   different gateways may see different failure rates depending
   on which backend instance they selected.
 - A globally synchronized circuit breaker would be overly
   aggressive -- one gateway's bad luck shouldn't trip the
   circuit for all gateways.

However, if you NEED synchronized circuit breakers:

Option A -- Shared state in Redis:
 All gateways read/write circuit breaker state to Redis.
 Pro: globally consistent.
 Con: adds Redis RTT to every request + every success/failure
 recording. Not recommended for hot path.

Option B -- Gossip protocol:
 Gateways share circuit breaker state via a gossip protocol
 (e.g., Serf/Memberlist). When a gateway trips a circuit, it
 broadcasts to peers. Eventual consistency (seconds lag).
 Pro: no centralized dependency.
 Con: complex to implement, eventual consistency.

Option C -- Independent with shared metrics (recommended):
 Keep circuit breakers independent per instance. Feed all
 gateway metrics to a centralized monitoring system. If the
 monitoring system detects a global failure pattern, it pushes
 a control plane override to all gateways: 'force-open
 circuit for service B'. This separates the fast path (local
 circuit breaker) from the global view (monitoring + control
 plane).

I'd use Option C for production because it keeps the hot path
fast and pushes global coordination to the async control plane."
```

### Edge Case 4: mTLS Certificate Rotation

```
WHAT TO SAY:
"Certificate rotation in a large mesh is a coordination problem.
300 sidecars need new certificates without any connection dropping.

The rotation protocol:
 1. CA issues a new certificate for each sidecar 12 hours before
    the old cert expires (at 50% lifetime for 24h certs).

 2. The sidecar receives the new cert via xDS SDS (Secret
    Discovery Service) from the control plane.

 3. The sidecar starts using the new cert for NEW connections.
    EXISTING connections continue using the old cert (they already
    completed the TLS handshake).

 4. Both the old and new certificates are valid simultaneously
    (the CA doesn't revoke the old cert until it naturally expires).

 5. Over the next 12 hours, all connections eventually close and
    reconnect, picking up the new cert.

Edge cases during rotation:
 a. Control plane is down when rotation is due:
    The sidecar continues using the current cert. If it expires
    before the control plane recovers, mTLS connections will fail.
    Mitigation: set cert lifetime to 48h with rotation at 24h,
    giving a 24h buffer.

 b. Clock skew between services:
    If Service A's clock is 5 minutes ahead, it may reject
    Service B's cert as 'not yet valid'. Mitigation: use NTP
    on all nodes and add a 5-minute grace period to cert
    validity checks.

 c. Certificate revocation (compromised key):
    Can't wait for natural expiry. Push a CRL (Certificate
    Revocation List) to all sidecars via xDS. Sidecars check
    the CRL on every new connection. For immediate revocation,
    use short-lived certs (1h lifetime) so the window of
    compromise is small.

 d. CA key compromise:
    If the mesh CA's signing key is compromised, all certs are
    suspect. Rotate the CA key, re-issue all certs, push to all
    sidecars. This is a cluster-wide event -- plan for it in
    disaster recovery runbooks."
```

### Edge Case 5: Retry Storms and Cascading Failures

```
WHAT TO SAY:
"Retries at multiple layers can create amplification:

Without protection:
 - Client retries 3 times
 - Gateway retries 2 times per attempt
 - Sidecar retries 2 times per attempt
 - Total: 3 * 2 * 2 = 12 requests for one user action

This is a RETRY STORM. When a service is slow, retries multiply
the load and make it slower, creating a death spiral.

Solution -- Retry budget:
 1. Set a global retry budget per service: max 20% of traffic
    can be retries. If the retry rate exceeds 20%, stop retrying
    and return errors immediately.

 2. Use exponential backoff with jitter:
    delay = min(baseDelay * 2^attempt + random(0, jitter), maxDelay)
    This spreads retries over time instead of thundering herd.

 3. Retry only on idempotent operations (GET, PUT, DELETE).
    Never auto-retry POST unless the API is explicitly idempotent
    (with an idempotency key).

 4. Set decreasing retry limits at each layer:
    - Client: 2 retries (outermost)
    - Gateway: 1 retry
    - Sidecar: 0 retries (innermost)
    Total worst case: 2 * 1 * 1 = 2 requests. Manageable.

 5. Circuit breaker is the safety net: even if retries create
    extra load, the circuit breaker trips at 5 failures and
    stops ALL traffic to the failing service."
```

### Edge Case 6: Hot-Path Latency Spike Debugging

```
WHAT TO SAY:
"When gateway latency spikes, we need to isolate which pipeline
stage is the bottleneck. The gateway emits per-stage timing:

 - route_match_ms: time for path trie lookup
 - auth_ms: time for JWT verification
 - rate_limit_ms: time for token bucket check (or Redis call)
 - circuit_breaker_ms: time for state lookup
 - load_balance_ms: time for instance selection
 - upstream_ms: time waiting for the backend response
 - total_ms: end-to-end gateway latency

These are emitted as structured access log fields AND as
histogram metrics. A dashboard shows p50/p95/p99 per stage.

Common spike causes:
 1. auth_ms spike → JWKS cache miss, fetching keys from IdP
 2. rate_limit_ms spike → Redis latency (network or overload)
 3. upstream_ms spike → backend service degradation
 4. load_balance_ms spike → service registry returning stale
    data, many unhealthy instances

The X-Trace-Id header (set in step 10 of the pipeline) correlates
gateway logs with upstream service logs, enabling end-to-end
request tracing."
```

### Common follow-up questions for Phase 7

```
Q: "What happens if a gateway instance has a memory leak?"
A: "The L4 LB's health check detects the instance becoming
   unresponsive (health endpoint times out). It removes the
   instance from the pool. Kubernetes restarts the pod. For
   gradual leaks, set memory limits on the container so the
   OOM killer terminates it before it affects other pods on
   the same node."

Q: "How do you handle a thundering herd after a gateway restart?"
A: "When a gateway restarts, its local rate limit buckets are
   empty (full capacity) and its circuit breakers are all CLOSED.
   For rate limits, the centralized Redis store prevents over-
   serving. For circuit breakers, the fresh instance will quickly
   detect unhealthy services (within 5 failures). To be extra
   safe, pre-warm the gateway by loading the latest circuit
   breaker state from the control plane on startup."

Q: "What about request deduplication?"
A: "The gateway can detect duplicate requests using an idempotency
   key (header or request body field). Store recent idempotency
   keys in a Bloom filter or Redis SET with TTL. If a duplicate
   is detected, return the cached response instead of forwarding
   to the backend. This is essential for payment and order APIs."
```

---
---

## APPENDIX A: DESIGN PATTERNS CHEAT SHEET

```
┌────────────────────────┬────────────────────────┬─────────────────────┐
│  Pattern               │  Where Used            │  Why                │
├────────────────────────┼────────────────────────┼─────────────────────┤
│  Facade (GoF)          │  GatewayService        │  Single entry point │
│                        │                        │  hides 6 sub-services│
├────────────────────────┼────────────────────────┼─────────────────────┤
│  Chain of              │  Gateway pipeline      │  Sequential filters │
│  Responsibility (GoF)  │  (10-step pipeline)    │  with short-circuit │
├────────────────────────┼────────────────────────┼─────────────────────┤
│  Strategy (GoF)        │  AuthService,          │  Swap auth, LB,     │
│                        │  LoadBalancerService,  │  routing, and traffic│
│                        │  RoutingService,       │  algorithms at       │
│                        │  TrafficStrategy       │  runtime             │
├────────────────────────┼────────────────────────┼─────────────────────┤
│  State Machine         │  CircuitBreakerState   │  CLOSED→OPEN→       │
│                        │                        │  HALF_OPEN→CLOSED   │
├────────────────────────┼────────────────────────┼─────────────────────┤
│  Token Bucket          │  RateLimiterEngine     │  Rate limiting with │
│                        │                        │  burst allowance    │
├────────────────────────┼────────────────────────┼─────────────────────┤
│  Consistent Hashing    │  ConsistentHashLB      │  Cache-affinity LB  │
│                        │                        │  with minimal rehash│
├────────────────────────┼────────────────────────┼─────────────────────┤
│  Sidecar (Cloud)       │  ServiceMeshService    │  Transparent proxy  │
│                        │                        │  per service pod    │
├────────────────────────┼────────────────────────┼─────────────────────┤
│  Control Plane /       │  Config Store + xDS    │  Separate config    │
│  Data Plane            │  vs. Gateway + Sidecar │  from traffic path  │
├────────────────────────┼────────────────────────┼─────────────────────┤
│  Weighted Random       │  CanaryTrafficStrategy │  Gradual traffic    │
│  Selection             │  TrafficSplit          │  shifting for canary│
├────────────────────────┼────────────────────────┼─────────────────────┤
│  Proxy (GoF)           │  Sidecar Proxy         │  Intercept and      │
│                        │                        │  augment traffic    │
├────────────────────────┼────────────────────────┼─────────────────────┤
│  Observer (GoF)        │  xDS config push       │  Control plane      │
│                        │                        │  notifies proxies   │
├────────────────────────┼────────────────────────┼─────────────────────┤
│  Registry (Enterprise) │  ServiceRegistry       │  Centralized service│
│                        │                        │  instance tracking  │
└────────────────────────┴────────────────────────┴─────────────────────┘
```

---

## APPENDIX B: COMPLEXITY CHEAT SHEET

```
┌──────────────────────────┬───────────────┬──────────────────────────┐
│  Operation               │  Complexity   │  Notes                   │
├──────────────────────────┼───────────────┼──────────────────────────┤
│  Route match (trie)      │  O(path len)  │  Radix trie lookup       │
│  Route match (linear)    │  O(N routes)  │  Our codebase (simple)   │
│  JWT HMAC verification   │  O(1)         │  ~0.2ms constant time    │
│  JWT RSA verification    │  O(1)         │  ~2ms constant time      │
│  Rate limit (local)      │  O(1)         │  Token bucket check      │
│  Rate limit (Redis)      │  O(1) + RTT   │  ~1ms network round trip │
│  Circuit breaker check   │  O(1)         │  ConcurrentHashMap get   │
│  Round-robin LB          │  O(1)         │  AtomicInteger mod N     │
│  Weighted LB             │  O(N inst.)   │  Walk through weights    │
│  Consistent hash LB      │  O(log(N*V))  │  TreeMap ceiling, V=150  │
│  Ring build (cons. hash) │  O(N * V)     │  N instances * V vnodes  │
│  mTLS handshake          │  O(1) + RTT   │  Certificate exchange    │
│  xDS config push         │  O(P proxies) │  Fan-out to all proxies  │
│  Canary weight selection │  O(V versions)│  Walk through weights    │
│  Service registry lookup │  O(1)         │  HashMap by service name │
│  Health check (active)   │  O(N inst.)   │  HTTP GET per instance   │
└──────────────────────────┴───────────────┴──────────────────────────┘
```

---

## APPENDIX C: QUICK-FIRE Q&A BANK

```
Q: "What's the difference between an API gateway and a reverse proxy?"
A: "A reverse proxy (NGINX) forwards requests to backend servers
   based on URL path -- pure L7 routing. An API gateway adds
   cross-cutting concerns: auth, rate limiting, circuit breaking,
   request transformation, observability. Every API gateway IS a
   reverse proxy, but not every reverse proxy is an API gateway."

Q: "What's the difference between a service mesh and an API gateway?"
A: "API gateway handles north-south traffic (external to internal).
   Service mesh handles east-west traffic (internal to internal).
   The gateway is centralized at the edge. The mesh is distributed
   (sidecar per service). They solve the same problems (auth, LB,
   circuit breaking) but at different boundaries."

Q: "Why Envoy instead of NGINX for the sidecar?"
A: "Envoy was purpose-built for service meshes:
   1. xDS API for dynamic config (NGINX requires config reload)
   2. Native L7 protocol support (gRPC, HTTP/2, MongoDB, Redis)
   3. Hot restart without dropping connections
   4. Built-in observability (stats, tracing, access logging)
   5. Battle-tested in Istio, Consul Connect, and AWS App Mesh"

Q: "What is SPIFFE?"
A: "Secure Production Identity Framework For Everyone. A standard
   for service identity in distributed systems. Each service gets
   a SPIFFE ID (URI like spiffe://cluster/ns/sa/service-name)
   encoded in its X.509 certificate. mTLS uses SPIFFE IDs for
   authorization: 'is this caller's SPIFFE ID allowed to call me?'"

Q: "What's the xDS protocol?"
A: "Envoy's discovery service API. xDS is a family of APIs:
   - LDS (Listener): what ports to listen on
   - RDS (Route): routing rules
   - CDS (Cluster): upstream cluster definitions
   - EDS (Endpoint): individual instance addresses
   - SDS (Secret): TLS certificates
   The control plane implements these APIs. Envoy subscribes to
   updates. Changes are pushed via gRPC streaming."

Q: "How does Istio inject sidecars?"
A: "Kubernetes mutating admission webhook. When a pod is created
   in a namespace with the istio-injection=enabled label, the
   webhook intercepts the pod spec and adds the Envoy sidecar
   container + init container (for iptables rules). The
   application developer never touches the sidecar config."

Q: "What's ambient mesh?"
A: "Istio's sidecarless mode (introduced 2022). Instead of a
   sidecar per pod, a shared ztunnel daemon runs per node,
   handling L4 mTLS. An optional waypoint proxy handles L7
   policies. Reduces resource overhead from O(pods) to O(nodes).
   Tradeoff: less isolation between pods on the same node."

Q: "Token bucket vs. sliding window rate limiter?"
A: "Token bucket allows bursts (up to bucket capacity) and refills
   at a steady rate. Good for APIs that tolerate short bursts.
   Sliding window counts requests in a rolling time window. No
   burst allowance but more predictable rate. Our codebase uses
   token bucket (RateLimiterEngine) because burst tolerance is
   desirable for API gateways."

Q: "What's the difference between L4 and L7 load balancing?"
A: "L4 (transport layer): routes TCP connections based on IP and
   port. Cannot inspect HTTP headers, URLs, or cookies. Very fast
   (~0.01ms overhead). Used for the LB in front of gateway instances.
   L7 (application layer): routes HTTP requests based on URL path,
   headers, cookies. Can do content-based routing, header
   manipulation, SSL termination. Our gateway is an L7 proxy."

Q: "What's a VirtualService in Istio?"
A: "A CRD (Custom Resource Definition) that configures L7 routing
   rules for the mesh. It specifies: match conditions (URI,
   headers), route destinations (service + version), traffic
   policy (timeout, retry, fault injection). Our Route + TrafficSplit
   models are equivalent to a VirtualService."

Q: "How do you test the gateway pipeline?"
A: "1. Unit tests: each pipeline stage in isolation (mock dependencies)
   2. Integration tests: full pipeline with in-memory dependencies
   3. Contract tests: verify the gateway forwards correct headers
   4. Chaos tests: inject failures (Redis down, backend slow, cert
      expired) and verify graceful degradation
   5. Load tests: verify throughput and latency SLOs under peak load"

Q: "What about WebAssembly (Wasm) filters in Envoy?"
A: "Envoy supports Wasm plugins for custom filter logic. Instead of
   recompiling Envoy, you compile your filter to Wasm and load it
   at runtime. Use cases: custom auth logic, request transformation,
   protocol translation. The filter runs in a sandboxed Wasm VM
   with ~10% overhead compared to native C++ filters."

Q: "How do you handle API versioning at the gateway?"
A: "Three approaches:
   1. URL path: /api/v1/users, /api/v2/users (separate routes)
   2. Header: Accept: application/vnd.api.v2+json (header-based routing)
   3. Query param: /api/users?version=2 (least common)
   I prefer URL path for simplicity. The gateway routes /api/v1/**
   to the v1 backend and /api/v2/** to the v2 backend. The traffic
   split mechanism can route based on version for canary deployments."
```

---

## APPENDIX D: KONG VS ENVOY VS ISTIO COMPARISON

```
┌────────────────────┬──────────────────┬──────────────────┬──────────────────┐
│  Dimension         │  Kong            │  Envoy           │  Istio           │
├────────────────────┼──────────────────┼──────────────────┼──────────────────┤
│  Role              │  API Gateway     │  L7 Proxy        │  Service Mesh    │
│                    │  (edge proxy)    │  (data plane)    │  (ctrl + data)   │
├────────────────────┼──────────────────┼──────────────────┼──────────────────┤
│  Deployment        │  Centralized     │  Sidecar or edge │  Sidecar per pod │
│                    │  edge cluster    │  (flexible)      │  + control plane │
├────────────────────┼──────────────────┼──────────────────┼──────────────────┤
│  Config model      │  Admin API +     │  xDS (dynamic)   │  K8s CRDs +      │
│                    │  declarative     │  or static YAML  │  Galley/istiod   │
│                    │  (YAML / DB)     │                  │                  │
├────────────────────┼──────────────────┼──────────────────┼──────────────────┤
│  Auth              │  JWT, OAuth,     │  ext_authz       │  RequestAuth +   │
│                    │  API key plugins │  filter (delegate)│  AuthorizationPol│
├────────────────────┼──────────────────┼──────────────────┼──────────────────┤
│  Rate limiting     │  Built-in plugin │  ext_ratelimit   │  Envoy filter +  │
│                    │  (Redis-backed)  │  (external svc)  │  Mixer (legacy)  │
├────────────────────┼──────────────────┼──────────────────┼──────────────────┤
│  Circuit breaker   │  Plugin          │  Outlier detect. │  DestinationRule │
│                    │                  │  (built-in)      │  outlier config  │
├────────────────────┼──────────────────┼──────────────────┼──────────────────┤
│  Load balancing    │  Round-robin,    │  RR, weighted,   │  Same as Envoy   │
│                    │  consistent hash,│  consistent hash,│  (configured via │
│                    │  least-conn      │  least-request,  │  DestinationRule)│
│                    │                  │  ring hash       │                  │
├────────────────────┼──────────────────┼──────────────────┼──────────────────┤
│  mTLS              │  Plugin (manual) │  Built-in SDS    │  Auto mTLS       │
│                    │                  │                  │  (Citadel CA)    │
├────────────────────┼──────────────────┼──────────────────┼──────────────────┤
│  Traffic mgmt      │  Canary plugin   │  Weighted routes │  VirtualService  │
│                    │                  │                  │  + DestinationRule│
├────────────────────┼──────────────────┼──────────────────┼──────────────────┤
│  Observability     │  Plugins (Prom,  │  Built-in stats, │  Envoy stats +   │
│                    │  Zipkin, etc.)   │  tracing, access │  Kiali, Jaeger,  │
│                    │                  │  log             │  Grafana          │
├────────────────────┼──────────────────┼──────────────────┼──────────────────┤
│  Extensibility     │  Lua / Go        │  Wasm, C++       │  Wasm (Envoy) +  │
│                    │  plugins         │  filters         │  K8s webhooks    │
├────────────────────┼──────────────────┼──────────────────┼──────────────────┤
│  Best for          │  API management  │  High-perf proxy │  Full mesh with  │
│                    │  with developer  │  building block   │  automated mTLS, │
│                    │  portal          │  for custom infra│  traffic, observ. │
└────────────────────┴──────────────────┴──────────────────┴──────────────────┘

TALKING POINT:
"In a typical production setup, you'd use Kong (or AWS API Gateway)
at the edge for north-south traffic, with Istio + Envoy for the
service mesh. Kong handles external auth, rate limiting, and API
management. Istio handles mTLS, east-west traffic policy, and
canary deployments. They complement each other."
```

---

## APPENDIX E: WHITEBOARD DRAWING ORDER

```
Draw the architecture in this order for maximum clarity during
the interview. Each step builds on the previous one.

Step 1: Two horizontal lanes (2 min)
  Draw two lanes labeled "CONTROL PLANE" and "DATA PLANE".
  Say: "Everything below is on the critical request path.
  Everything above is async configuration."

Step 2: External flow (1 min)
  In the data plane lane, draw:
  [Client] → [L4 LB] → [Gateway] → [Service]
  Say: "North-south traffic enters through an L4 LB, hits the
  gateway, and reaches the backend service."

Step 3: Gateway pipeline (2 min)
  Expand the gateway box into the 10-stage pipeline:
  Route → Auth → Rate Limit → Circuit Breaker → LB → Forward
  Say: "Each stage is a filter in a Chain of Responsibility.
  Any stage can short-circuit with an error."

Step 4: Service mesh (2 min)
  Add sidecar proxies next to services:
  [Sidecar A] ← mTLS → [Sidecar B]
  Say: "East-west traffic goes through sidecar proxies. They
  handle mTLS, circuit breaking, and load balancing."

Step 5: Control plane components (1 min)
  In the control plane lane, draw:
  [Config Store] [Service Registry] [CA]
  Draw dotted arrows down to gateway and sidecars.
  Say: "The control plane pushes config via xDS. If it's down,
  proxies serve with cached config."

Step 6: Traffic splitting (1 min)
  Add version labels to services:
  [Service v1 (90%)] [Service v2 (10%)]
  Say: "Canary deployments use weighted traffic splitting.
  The control plane updates the weights."

Step 7: Circuit breaker state machine (1 min)
  Draw the three states: CLOSED → OPEN → HALF_OPEN → CLOSED
  Say: "After 5 failures, the circuit opens. After 30 seconds,
  we probe. After 3 successful probes, we close."
```

---

## APPENDIX F: ANTI-PATTERNS TO AVOID

```
Anti-Pattern 1: "Put all logic in the gateway"
  DON'T: business logic, data validation, complex transformation
  DO: cross-cutting concerns only (auth, rate limit, routing)
  WHY: the gateway is shared by all services. Business logic
  belongs in the services. A bug in gateway business logic
  affects ALL services.

Anti-Pattern 2: "Skip the mesh, just use the gateway for everything"
  DON'T: route east-west traffic through the gateway
  DO: use sidecars for service-to-service calls
  WHY: routing internal traffic through the gateway adds a
  needless hop, creates a bottleneck, and means the gateway must
  know about internal APIs. East-west traffic volume is often 5-10x
  north-south.

Anti-Pattern 3: "Retry everything"
  DON'T: retry non-idempotent operations (POST to create order)
  DO: retry only GET, PUT, DELETE with idempotency guarantees
  WHY: retrying a non-idempotent POST can create duplicate orders,
  double-charge customers, or corrupt state.

Anti-Pattern 4: "Synchronous control plane calls on the hot path"
  DON'T: call the control plane to fetch config on every request
  DO: cache config locally, receive updates via xDS push
  WHY: a synchronous call to the control plane on every request
  adds latency AND makes the control plane a SPOF for traffic.

Anti-Pattern 5: "Global circuit breaker across all instances"
  DON'T: trip one circuit breaker that affects all gateway instances
  DO: per-instance circuit breaker with monitoring-driven overrides
  WHY: a global trip can cause a cascading outage. Per-instance
  breakers allow partial degradation.

Anti-Pattern 6: "Long-lived mTLS certificates"
  DON'T: issue certificates with 1-year expiry
  DO: short-lived certs (24h) with automated rotation
  WHY: long-lived certs are hard to revoke. If a cert is compromised,
  you're exposed until manual revocation. Short-lived certs expire
  naturally, limiting the blast radius.

Anti-Pattern 7: "Rate limit only at the gateway"
  DON'T: assume the gateway protects all services
  DO: add per-service rate limits in the mesh sidecars
  WHY: internal services can call each other directly (east-west).
  A runaway internal service can overwhelm a downstream service
  without ever touching the gateway.

Anti-Pattern 8: "Ignore connection pooling"
  DON'T: create a new TCP connection for every proxied request
  DO: maintain connection pools to upstream services
  WHY: TCP handshake + TLS handshake = ~10ms per new connection.
  Connection pooling amortizes this across many requests. Envoy
  does this automatically; custom gateways must implement it.

Anti-Pattern 9: "Skip health checks"
  DON'T: blindly forward to instances without checking health
  DO: active health checks (HTTP GET /health) + passive (track 5xx)
  WHY: forwarding to an unhealthy instance wastes a request and
  triggers unnecessary circuit breaker increments. Health checks
  remove bad instances from the pool proactively.

Anti-Pattern 10: "Buffer entire request/response bodies"
  DON'T: load the full request body into memory for transformation
  DO: stream bodies through the proxy
  WHY: a 100MB file upload through a gateway that buffers = 100MB
  memory per concurrent upload. At 1000 concurrent uploads, that's
  100GB of memory. Stream-through proxying uses constant memory.
```

---

## APPENDIX G: INTERVIEW TIMING CHEAT SHEET

```
┌─────────────────────────────────────────────────────────────────┐
│                    35-MINUTE TIMELINE                           │
├──────────┬──────────────────────────────────────────────────────┤
│  0:00    │  Phase 1: Clarify requirements                      │
│          │  - Ask 6-8 targeted questions (3 buckets)            │
│          │  - Write scope table on whiteboard                   │
│          │  - State assumptions aloud                           │
│  2:30    │  TRANSITION: "Let me draw the high-level architecture"│
├──────────┼──────────────────────────────────────────────────────┤
│  2:30    │  Phase 2: High-Level Architecture                   │
│          │  - Draw control plane / data plane lanes             │
│          │  - Draw external flow: Client → L4 LB → Gateway     │
│          │  - Draw mesh: Sidecar ↔ Sidecar (mTLS)              │
│          │  - Name all 6 core components                        │
│  9:00    │  TRANSITION: "Let me dive into the gateway pipeline" │
├──────────┼──────────────────────────────────────────────────────┤
│  9:00    │  Phase 3: Deep Dive -- Gateway Pipeline              │
│          │  - Draw 10-stage pipeline diagram                    │
│          │  - Explain ordering rationale                        │
│          │  - Deep dive on auth, rate limiting, circuit breaker │
│          │  - Trace a concrete request end-to-end               │
│ 19:00    │  TRANSITION: "Now the service mesh data plane"       │
├──────────┼──────────────────────────────────────────────────────┤
│ 19:00    │  Phase 4: Deep Dive -- Service Mesh                  │
│          │  - Sidecar proxy model (iptables interception)       │
│          │  - mTLS handshake diagram                            │
│          │  - Consistent hash LB (virtual node ring)            │
│          │  - Per-sidecar circuit breaker (vs. gateway's)       │
│ 25:00    │  TRANSITION: "Let me cover traffic management"       │
├──────────┼──────────────────────────────────────────────────────┤
│ 25:00    │  Phase 5: Traffic Management                         │
│          │  - Canary: weighted random, gradual rollout          │
│          │  - Blue-green: atomic flip                           │
│          │  - A/B: header-based routing                         │
│          │  - Automated rollback pipeline                       │
│ 29:00    │  TRANSITION: "A few scaling considerations"          │
├──────────┼──────────────────────────────────────────────────────┤
│ 29:00    │  Phase 6: Scaling & Tradeoffs                        │
│          │  - Horizontal gateway scaling (CPU/conn/mem)         │
│          │  - Edge vs centralized (tradeoff table)              │
│          │  - Fail-open vs fail-closed rate limiting            │
│          │  - Sidecar resource overhead at 300 pods             │
│ 33:00    │  TRANSITION: "Let me name some edge cases"           │
├──────────┼──────────────────────────────────────────────────────┤
│ 33:00    │  Phase 7: Edge Cases                                 │
│          │  - Gateway SPOF mitigation (multi-AZ)               │
│          │  - Distributed rate limiting (Redis Lua script)      │
│          │  - Circuit breaker sync (Option C: async metrics)    │
│          │  - mTLS cert rotation (short-lived + auto-renew)     │
│          │  - Retry storms (budget + exponential backoff)       │
│ 35:00    │  END                                                 │
└──────────┴──────────────────────────────────────────────────────┘

PACING TIPS:
 - If Phase 3 is running long, abbreviate the per-stage deep dives.
   Cover route + auth + circuit breaker; skip rate limit details.
 - If the interviewer asks many follow-ups in Phase 2, compress
   Phases 5 and 6 to 2 minutes each.
 - Always leave 2 minutes for edge cases -- they are high-signal
   for Staff-level evaluation.
 - If you finish early, offer: "I can also discuss multi-cluster
   federation, gRPC load balancing, or Wasm extensibility."
```
