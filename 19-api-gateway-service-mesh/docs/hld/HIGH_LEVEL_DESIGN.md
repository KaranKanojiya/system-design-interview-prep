# API Gateway & Service Mesh -- High-Level Design

## Interview Guide

**Target Duration**: 30-45 minutes
**Difficulty**: Staff Engineer / L6+
**Format**: Structured walkthrough, whiteboard-friendly

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Scope](#2-scope)
3. [Requirements](#3-requirements)
4. [API Design](#4-api-design)
5. [Data Model](#5-data-model)
6. [High-Level Architecture](#6-high-level-architecture)
7. [Gateway Pipeline Deep Dive](#7-gateway-pipeline-deep-dive)
8. [Service Mesh Architecture](#8-service-mesh-architecture)
9. [Circuit Breaker State Machine](#9-circuit-breaker-state-machine)
10. [Rate Limiting Algorithms](#10-rate-limiting-algorithms)
11. [Load Balancing Strategies](#11-load-balancing-strategies)
12. [mTLS and Zero-Trust Security](#12-mtls-and-zero-trust-security)
13. [Canary Deployments & Traffic Shaping](#13-canary-deployments--traffic-shaping)
14. [Scaling Strategy](#14-scaling-strategy)
15. [Database Choices](#15-database-choices)
16. [CAP Analysis](#16-cap-analysis)
17. [Cloud Mapping](#17-cloud-mapping)
18. [Failure Scenarios](#18-failure-scenarios)
19. [Interview Walkthrough Script](#19-interview-walkthrough-script)

---

## 1. Problem Statement

Design an **API Gateway and Service Mesh** (like Kong, Netflix Zuul, AWS API Gateway, Envoy, or Istio) that provides a unified ingress layer for external traffic and a secure, resilient communication fabric for inter-service traffic in a microservice architecture.

### Why This Is Hard

1. **Scale**: A mid-size platform generates 100K+ external requests/sec and 10x that in inter-service RPCs (1M+ internal RPCs/sec). The gateway must add <1ms overhead per request at this scale.
2. **Cross-Cutting Concerns**: Authentication, authorization, rate limiting, circuit breaking, observability, and encryption must be applied consistently across 100s of services without duplicating logic in each service.
3. **Resilience**: A single failing service must not cascade to the entire platform. Circuit breakers, retries with exponential backoff, and bulkhead isolation must work together to contain blast radius.
4. **Security**: Zero-trust networking requires every service-to-service call to be authenticated and encrypted (mTLS), with certificate rotation happening automatically across thousands of instances.
5. **Deployment Safety**: Deploying new code to production without downtime requires canary deployments with automated rollback, which means the routing layer must support traffic splitting at fine granularity.
6. **Multi-Tenancy**: Different API consumers (mobile apps, partner integrations, internal services) have different rate limits, authentication schemes, and SLA requirements, all flowing through the same gateway.

### Real-World Scale

| Metric | Mid-Size Platform | Large Platform (Netflix/Uber) |
|--------|-------------------|-------------------------------|
| External requests/sec | 100K | 2M+ |
| Internal RPCs/sec | 1M | 100M+ |
| Microservices | 100-500 | 1000-5000 |
| Service instances | 1,000-5,000 | 50,000-200,000 |
| Routes | 200-1,000 | 5,000-50,000 |
| Certificate rotations/day | 1,000 | 200,000+ |
| Deploy frequency | 50/day | 1,000+/day |
| P99 gateway latency target | <5ms | <1ms |

---

## 2. Scope

### In Scope

| Feature | Details |
|---------|---------|
| Request Routing | Path-based and header-based routing with glob wildcard patterns, priority sorting |
| Authentication | JWT token validation (decode + claim extraction) and API key authentication |
| Authorization | Role-based access control per-route via metadata-driven required-role checks |
| Rate Limiting | Token bucket algorithm with per-client and per-route limits |
| Circuit Breaker | Three-state machine (CLOSED/OPEN/HALF_OPEN) with configurable thresholds |
| Load Balancing | Round-robin, weighted, and consistent hash strategies |
| Service Mesh | Sidecar proxy with mTLS, circuit breaking, and load balancing |
| Service Discovery | Instance registration, health checking, and stale instance eviction |
| Traffic Shaping | Canary deployments with weighted and header-based version selection |
| Observability | TraceId propagation through the full pipeline, mesh headers for debugging |

### Out of Scope

| Feature | Why |
|---------|-----|
| Request/response transformation | Adds complexity without teaching core gateway concepts |
| WebSocket/gRPC proxying | Protocol-specific concerns, not architectural patterns |
| API versioning management | Business logic, not infrastructure design |
| OAuth2 full flow | External identity provider concern; gateway only validates tokens |
| Multi-cluster mesh federation | Advanced mesh topic, beyond single-cluster fundamentals |
| Billing/metering | Downstream business system |

---

## 3. Requirements

### 3.1 Functional Requirements (FR)

```
FR-1:  Route incoming requests to target services based on path pattern and HTTP method
FR-2:  Support glob wildcard routing (/api/users/** matches /api/users/123/profile)
FR-3:  Authenticate requests via JWT (decode payload, extract sub claim, map to roles)
FR-4:  Authenticate requests via API key (lookup from trusted keystore, map to principal)
FR-5:  Authorize requests based on route-level required roles
FR-6:  Rate limit requests using token bucket algorithm (per-client and per-route)
FR-7:  Implement circuit breaker (CLOSED/OPEN/HALF_OPEN) to prevent cascading failure
FR-8:  Load balance across healthy service instances (round-robin, weighted, consistent hash)
FR-9:  Register and deregister service instances with health status tracking
FR-10: Evict stale instances based on heartbeat timeout
FR-11: Enforce mutual TLS (mTLS) for service-to-service communication
FR-12: Support canary deployments with configurable traffic split percentages
FR-13: Support header-based routing for targeted canary testing (X-Canary: true)
FR-14: Propagate trace IDs through the full gateway pipeline for observability
FR-15: Provide gateway status endpoint with route table, circuit breaker states, and instance counts
```

### 3.2 Non-Functional Requirements (NFR)

```
NFR-1:  Gateway pipeline latency < 1ms overhead (excluding upstream response time)
NFR-2:  Support 100K+ requests/second per gateway instance
NFR-3:  99.99% gateway availability (52.6 minutes downtime/year)
NFR-4:  Route table updates propagate within 5 seconds (eventual consistency)
NFR-5:  Circuit breaker state transitions happen within 1 request of threshold
NFR-6:  Rate limiter accuracy within 1% of configured rate (no significant over/under-limiting)
NFR-7:  mTLS certificate validation < 0.1ms per request (cached certificate chains)
NFR-8:  Health check detection of failed instances within 10 seconds
NFR-9:  Canary rollback within 60 seconds of anomaly detection
NFR-10: Zero downtime during gateway deployments (rolling restart)
```

### 3.3 Capacity Estimation

```
External requests:         100,000 req/sec
Internal RPCs (10x fan-out): 1,000,000 req/sec
Average request size:       2 KB
Average response size:      10 KB

Bandwidth:
  External ingress:  100K * 2KB  = 200 MB/sec = 1.6 Gbps
  External egress:   100K * 10KB = 1 GB/sec   = 8 Gbps
  Internal mesh:     1M * 5KB    = 5 GB/sec   = 40 Gbps

Route table size:     1,000 routes * 1KB = 1 MB (fits in memory)
Service instances:    5,000 instances * 500B = 2.5 MB (fits in memory)
Rate limit buckets:   100K clients * 100B = 10 MB (fits in memory)
Circuit breaker state: 500 services * 200B = 100 KB (fits in memory)

Gateway instances needed: 100K RPS / 50K RPS per instance = 2-4 instances
                         (with 2x headroom for failover)
```

---

## 4. API Design

### 4.1 Gateway External API (North-South Traffic)

```
POST /gateway/request
  Description: Process an incoming HTTP request through the full gateway pipeline
  Headers:
    Authorization: Bearer <jwt-token>   (or)
    X-API-Key: <api-key>
  Body: The original HTTP request (method, path, headers, body)
  Response:
    200 OK              -- upstream service responded successfully
    401 Unauthorized    -- authentication failed (missing/invalid token)
    403 Forbidden       -- authorization failed (insufficient roles)
    404 Not Found       -- no matching route
    429 Too Many Requests -- rate limit exceeded (Retry-After header included)
    502 Bad Gateway     -- upstream service returned an error
    503 Service Unavailable -- circuit breaker OPEN or no healthy instances
  Response Headers:
    X-Trace-Id: <trace-id>           -- end-to-end request tracing
    X-Gateway-Service: <service>     -- which service handled the request
    X-Gateway-Instance: <host:port>  -- which instance handled the request
    X-RateLimit-Remaining: <n>       -- remaining rate limit tokens
    Retry-After: <seconds>           -- when to retry (on 429)

POST /gateway/routes
  Description: Register a new route in the gateway's routing table
  Body:
    {
      "pathPattern": "/api/users/**",
      "targetService": "user-service",
      "methods": ["GET", "POST", "PUT"],
      "rateLimitPerSecond": 100,
      "timeoutMs": 5000,
      "retryCount": 2,
      "priority": 10,
      "metadata": { "required-role": "user" }
    }
  Response: 201 Created

POST /gateway/services
  Description: Register a service instance in the load balancer registry
  Body:
    {
      "id": "user-svc-1",
      "serviceName": "user-service",
      "host": "10.0.1.10",
      "port": 8080,
      "weight": 3,
      "zone": "us-east-1a"
    }
  Response: 201 Created

GET /gateway/circuit-breakers
  Description: Get circuit breaker states for all tracked services
  Response:
    {
      "user-service": "CLOSED",
      "order-service": "HALF_OPEN",
      "payment-service": "OPEN"
    }

GET /gateway/status
  Description: Get gateway operational summary
  Response:
    {
      "routes": 4,
      "services": 3,
      "totalInstances": 6,
      "healthyInstances": 5,
      "circuitBreakers": { "open": 1, "halfOpen": 1, "closed": 1 }
    }
```

### 4.2 Service Mesh API (East-West Traffic)

```
Sidecar Proxy (intercepted by Envoy on localhost):

  All outbound traffic from a service is transparently intercepted by the sidecar.
  Application code makes a normal HTTP call to http://user-service:8080/api/users/123
  The sidecar intercepts and:
    1. Validates mTLS (caller certificate → trusted service set)
    2. Checks circuit breaker for the target service
    3. Selects a healthy instance via load balancer
    4. Forwards with mTLS encryption
    5. Records success/failure in circuit breaker
    6. Returns response with mesh headers:
       X-Mesh-Source: order-service
       X-Mesh-Target: user-service
       X-Mesh-Instance: 10.0.1.10:8080

Control Plane API (Istio-style):

  POST /mesh/config
    Description: Push service mesh configuration to all sidecars
    Body: ServiceMeshConfig (mTLS enabled, retry policy, circuit breaker config)

  POST /mesh/traffic-split
    Description: Configure canary traffic split for a service
    Body:
      {
        "deploymentId": "order-service-deploy",
        "splits": { "v1-stable": 90, "v2-canary": 10 }
      }

  POST /mesh/trust
    Description: Add a service to the mTLS trusted set
    Body: { "serviceName": "new-service" }

  DELETE /mesh/trust/{serviceName}
    Description: Revoke mTLS trust for a service
```

---

## 5. Data Model

### 5.1 Core Entities

```
┌─────────────────────────────────────────────────────────────────┐
│                        HttpRequest                              │
│  ┌──────────┬─────────────────────────────────────────────────┐ │
│  │ id       │ UUID -- unique request identifier               │ │
│  │ method   │ HttpMethod (GET/POST/PUT/DELETE/PATCH)          │ │
│  │ path     │ String ("/api/users/123")                       │ │
│  │ headers  │ Map<String,String> -- immutable after build     │ │
│  │ queryParams│ Map<String,String> -- immutable after build   │ │
│  │ body     │ String (nullable for GET/HEAD)                  │ │
│  │ clientIp │ String ("192.168.1.100")                        │ │
│  │ timestamp│ Instant (when request was received)             │ │
│  └──────────┴─────────────────────────────────────────────────┘ │
│  Builder: required(method, path), optional(headers, body, ip)   │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                           Route                                 │
│  ┌──────────────────┬─────────────────────────────────────────┐ │
│  │ id               │ UUID -- unique route identifier         │ │
│  │ pathPattern      │ String ("/api/users/**") -- glob match  │ │
│  │ targetService    │ String ("user-service")                 │ │
│  │ methods          │ Set<HttpMethod> -- allowed verbs        │ │
│  │ priority         │ int -- lower = higher priority          │ │
│  │ enabled          │ boolean -- active/inactive toggle       │ │
│  │ rateLimitPerSecond│ int -- per-route rate limit (0=none)  │ │
│  │ timeoutMs        │ long -- request timeout                 │ │
│  │ retryCount       │ int -- retries on failure               │ │
│  │ metadata         │ Map<String,String> -- e.g. required-role│ │
│  └──────────────────┴─────────────────────────────────────────┘ │
│  matches(path, method):                                         │
│    1. Check enabled && methods.contains(method)                 │
│    2. Exact match: pathPattern.equals(path)                     │
│    3. Wildcard: pathPattern ends with "/**" → prefix match      │
│  Builder: required(pathPattern, targetService)                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                       ServiceInstance                           │
│  ┌──────────────────┬─────────────────────────────────────────┐ │
│  │ id               │ String ("user-svc-1") -- instance ID    │ │
│  │ serviceName      │ String ("user-service") -- logical name │ │
│  │ host             │ String ("10.0.1.10") -- IP address      │ │
│  │ port             │ int (8080)                              │ │
│  │ healthStatus     │ HealthStatus (volatile) -- thread-safe  │ │
│  │ weight           │ int -- for weighted load balancing      │ │
│  │ zone             │ String ("us-east-1a") -- AZ placement   │ │
│  │ registeredAt     │ Instant -- registration timestamp       │ │
│  │ lastHeartbeat    │ Instant (volatile) -- last health check │ │
│  │ metadata         │ Map<String,String> -- extensible tags   │ │
│  └──────────────────┴─────────────────────────────────────────┘ │
│  isHealthy(): healthStatus.isUp() → only HEALTHY returns true   │
│  getAddress(): host + ":" + port                                │
│  updateHeartbeat(): stamps lastHeartbeat, sets HEALTHY          │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                    CircuitBreakerState                          │
│  ┌──────────────────┬─────────────────────────────────────────┐ │
│  │ serviceName      │ String -- service this breaker protects │ │
│  │ state            │ CircuitState (CLOSED/OPEN/HALF_OPEN)    │ │
│  │ failureCount     │ int -- consecutive failures in CLOSED   │ │
│  │ successCount     │ int -- consecutive successes in HALF_OPEN│ │
│  │ lastFailureTime  │ Instant -- most recent failure          │ │
│  │ lastStateChange  │ Instant -- last state transition        │ │
│  │ failureThreshold │ int (default 5) -- trips to OPEN       │ │
│  │ successThreshold │ int (default 3) -- recovers to CLOSED  │ │
│  │ openDurationMs   │ long (default 30_000) -- cooldown       │ │
│  └──────────────────┴─────────────────────────────────────────┘ │
│  State Machine:                                                 │
│    CLOSED ---(failureCount >= failureThreshold)---> OPEN        │
│    OPEN   ---(elapsed > openDurationMs)-----------> HALF_OPEN   │
│    HALF_OPEN -(successCount >= successThreshold)--> CLOSED      │
│    HALF_OPEN -(any failure)-----------------------> OPEN        │
└─────────────────────────────────────────────────────────────────┘
```

### 5.2 Configuration Entities

```
┌─────────────────────────────────────────────────────────────────┐
│                     ServiceMeshConfig                           │
│  ┌──────────────────┬─────────────────────────────────────────┐ │
│  │ mtlsEnabled      │ boolean (true) -- mutual TLS toggle     │ │
│  │ sidecarPort      │ int (15001) -- sidecar listen port      │ │
│  │ tracingEnabled    │ boolean (true) -- distributed tracing   │ │
│  │ retryPolicy      │ RetryPolicy -- mesh-wide retry config   │ │
│  │ circuitBreakerEnabled│ boolean (true) -- CB toggle         │ │
│  └──────────────────┴─────────────────────────────────────────┘ │
│  Builder with sensible defaults for all fields                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                       TrafficSplit                               │
│  ┌──────────────────┬─────────────────────────────────────────┐ │
│  │ deploymentId     │ String ("order-service-deploy")         │ │
│  │ splits           │ Map<String,Integer>                     │ │
│  │                  │ {"v1-stable": 90, "v2-canary": 10}     │ │
│  └──────────────────┴─────────────────────────────────────────┘ │
│  Used by CanaryTrafficStrategy for weighted random selection     │
│  Used by HeaderBasedTrafficStrategy for override routing         │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                        RetryPolicy                              │
│  ┌──────────────────┬─────────────────────────────────────────┐ │
│  │ maxRetries       │ int -- maximum retry attempts           │ │
│  │ retryDelayMs     │ long -- delay between retries           │ │
│  │ retryableStatusCodes│ Set<Integer> -- 502, 503, 504       │ │
│  └──────────────────┴─────────────────────────────────────────┘ │
│  defaultPolicy(): maxRetries=3, delay=100ms, codes={502,503,504}│
└─────────────────────────────────────────────────────────────────┘
```

### 5.3 Token Bucket Internal State

```
┌─────────────────────────────────────────────────────────────────┐
│                    TokenBucket (per-key)                         │
│  ┌──────────────────┬─────────────────────────────────────────┐ │
│  │ tokens           │ double -- current available tokens      │ │
│  │ maxTokens        │ int -- bucket capacity (burst limit)    │ │
│  │ refillRate       │ double -- tokens added per second       │ │
│  │ lastRefillTime   │ Instant -- last refill timestamp        │ │
│  └──────────────────┴─────────────────────────────────────────┘ │
│  refill():                                                      │
│    elapsed = (now - lastRefillTime) / 1000.0                    │
│    tokens = min(maxTokens, tokens + elapsed * refillRate)       │
│    lastRefillTime = now                                         │
│  tryConsume():                                                  │
│    refill() → if tokens >= 1.0: tokens -= 1; return allowed    │
│              else: return denied(retryAfter = 1000/refillRate)  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 6. High-Level Architecture

### 6.1 System Overview

```
                          ┌──────────────────────────────┐
                          │        Load Balancer          │
                          │   (AWS ALB / Nginx / HAProxy) │
                          └──────────────┬───────────────┘
                                         │
                    ┌────────────────────┬┴────────────────────┐
                    ▼                    ▼                     ▼
            ┌──────────────┐   ┌──────────────┐      ┌──────────────┐
            │  API Gateway  │   │  API Gateway  │      │  API Gateway  │
            │  Instance 1   │   │  Instance 2   │      │  Instance N   │
            │               │   │               │      │               │
            │ ┌───────────┐ │   │ ┌───────────┐ │      │ ┌───────────┐ │
            │ │ Route Table│ │   │ │ Route Table│ │      │ │ Route Table│ │
            │ ├───────────┤ │   │ ├───────────┤ │      │ ├───────────┤ │
            │ │ Auth Engine│ │   │ │ Auth Engine│ │      │ │ Auth Engine│ │
            │ ├───────────┤ │   │ ├───────────┤ │      │ ├───────────┤ │
            │ │Rate Limiter│ │   │ │Rate Limiter│ │      │ │Rate Limiter│ │
            │ ├───────────┤ │   │ ├───────────┤ │      │ ├───────────┤ │
            │ │Circuit Brkr│ │   │ │Circuit Brkr│ │      │ │Circuit Brkr│ │
            │ ├───────────┤ │   │ ├───────────┤ │      │ ├───────────┤ │
            │ │Load Balancr│ │   │ │Load Balancr│ │      │ │Load Balancr│ │
            │ └───────────┘ │   │ └───────────┘ │      │ └───────────┘ │
            └───────┬──────┘   └───────┬──────┘      └───────┬──────┘
                    │                  │                      │
            ┌───────┴──────────────────┴──────────────────────┴──────┐
            │                    SERVICE MESH                         │
            │           (East-West Traffic with Sidecars)             │
            │                                                        │
            │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐   │
            │  │ user-service│  │order-service │  │payment-svc  │   │
            │  │ ┌─────────┐ │  │ ┌─────────┐ │  │ ┌─────────┐ │   │
            │  │ │  App    │ │  │ │  App    │ │  │ │  App    │ │   │
            │  │ │ (Java)  │ │  │ │ (Go)   │ │  │ │ (Python)│ │   │
            │  │ └────┬────┘ │  │ └────┬────┘ │  │ └────┬────┘ │   │
            │  │      │      │  │      │      │  │      │      │   │
            │  │ ┌────▼────┐ │  │ ┌────▼────┐ │  │ ┌────▼────┐ │   │
            │  │ │ Sidecar │ │  │ │ Sidecar │ │  │ │ Sidecar │ │   │
            │  │ │ (Envoy) │ │  │ │ (Envoy) │ │  │ │ (Envoy) │ │   │
            │  │ │ mTLS    │◄├──├►│ mTLS    │◄├──├►│ mTLS    │ │   │
            │  │ │ CB      │ │  │ │ CB      │ │  │ │ CB      │ │   │
            │  │ │ LB      │ │  │ │ LB      │ │  │ │ LB      │ │   │
            │  │ └─────────┘ │  │ └─────────┘ │  │ └─────────┘ │   │
            │  └─────────────┘  └─────────────┘  └─────────────┘   │
            │                                                        │
            │  Control Plane (Istio Pilot / Linkerd Control Plane)   │
            │  ┌────────────────────────────────────────────────┐   │
            │  │ Config Distribution (xDS) │ Cert Mgmt (Citadel)│   │
            │  │ Service Discovery         │ Policy Enforcement  │   │
            │  └────────────────────────────────────────────────┘   │
            └────────────────────────────────────────────────────────┘
```

### 6.2 Request Flow (North-South)

```
Client → Load Balancer → API Gateway Pipeline:
  ┌─────────────────────────────────────────────────────────────┐
  │ Step 1: Route Matching                                      │
  │   path="/api/users/123", method=GET                         │
  │   → scan priority-sorted routes                             │
  │   → match "/api/users/**" → target="user-service"          │
  │   → NO MATCH? → 404 Not Found (short-circuit)              │
  ├─────────────────────────────────────────────────────────────┤
  │ Step 2: Authentication                                      │
  │   → extract Authorization header → JWT or API key           │
  │   → JWT: decode Base64 payload, extract "sub" claim         │
  │   → API key: lookup in trusted keystore                     │
  │   → FAIL? → 401 Unauthorized (short-circuit)               │
  ├─────────────────────────────────────────────────────────────┤
  │ Step 3: Authorization                                       │
  │   → check route metadata "required-role"                    │
  │   → compare with authenticated principal's roles            │
  │   → FAIL? → 403 Forbidden (short-circuit)                  │
  ├─────────────────────────────────────────────────────────────┤
  │ Step 4: Rate Limiting                                       │
  │   → token bucket for route key                              │
  │   → tryConsume() → refill + consume                         │
  │   → DENIED? → 429 Too Many Requests (short-circuit)        │
  ├─────────────────────────────────────────────────────────────┤
  │ Step 5: Circuit Breaker                                     │
  │   → check target service circuit state                      │
  │   → OPEN? → 503 Service Unavailable (short-circuit)        │
  │   → HALF_OPEN? → allow probe request                       │
  ├─────────────────────────────────────────────────────────────┤
  │ Step 6: Load Balancing                                      │
  │   → get healthy instances from registry                     │
  │   → strategy.selectInstance(instances, request)             │
  │   → NO INSTANCES? → 503 (short-circuit)                    │
  ├─────────────────────────────────────────────────────────────┤
  │ Step 7: Forward to Upstream                                 │
  │   → HTTP call to selected instance                          │
  │   → timeout after route.timeoutMs                           │
  │   → SUCCESS? → record in circuit breaker → return 200      │
  │   → FAILURE? → record in circuit breaker → return 502      │
  │   → add X-Trace-Id, X-Gateway-Service headers              │
  └─────────────────────────────────────────────────────────────┘
```

### 6.3 Request Flow (East-West / Service Mesh)

```
Service A → Sidecar Proxy (localhost) → Network → Sidecar Proxy → Service B:
  ┌─────────────────────────────────────────────────────────────┐
  │ Service A makes HTTP call to service-b:8080/internal/api    │
  │                                                             │
  │ Sidecar A (outbound):                                      │
  │   1. Intercept outbound traffic (iptables REDIRECT)         │
  │   2. mTLS: present Service A's certificate                  │
  │   3. Circuit breaker: check Service B's state               │
  │   4. Load balance: select Service B instance                │
  │   5. Encrypt with mTLS and forward                          │
  │                                                             │
  │ Sidecar B (inbound):                                        │
  │   1. Intercept inbound traffic                              │
  │   2. mTLS: validate Service A's certificate against trust   │
  │   3. Rate limit: check per-service limits                   │
  │   4. Forward to Service B on localhost                      │
  │   5. Return response through reverse path                   │
  │                                                             │
  │ Headers added:                                              │
  │   X-Mesh-Source: service-a                                  │
  │   X-Mesh-Target: service-b                                  │
  │   X-Mesh-Instance: 10.0.2.10:8080                          │
  └─────────────────────────────────────────────────────────────┘
```

---

## 7. Gateway Pipeline Deep Dive

### 7.1 Chain of Responsibility Pattern

The gateway pipeline implements the Chain of Responsibility pattern. Each step in the pipeline is a handler that either:
- **Passes** the request to the next handler (success)
- **Short-circuits** with an error response (failure)

```
┌───────────┐   ┌───────────┐   ┌───────────┐   ┌───────────┐   ┌───────────┐   ┌───────────┐   ┌───────────┐
│   Route   │──>│   Auth    │──>│   AuthZ   │──>│   Rate    │──>│  Circuit  │──>│   Load    │──>│  Forward  │
│  Matching │   │           │   │           │   │  Limiting │   │  Breaker  │   │  Balance  │   │           │
│           │   │           │   │           │   │           │   │           │   │           │   │           │
│ 404 if    │   │ 401 if    │   │ 403 if    │   │ 429 if    │   │ 503 if    │   │ 503 if no │   │ 200 or    │
│ no match  │   │ no token  │   │ no role   │   │ exceeded  │   │ OPEN      │   │ instances │   │ 502       │
└───────────┘   └───────────┘   └───────────┘   └───────────┘   └───────────┘   └───────────┘   └───────────┘
```

### 7.2 Implementation Detail

The `GatewayService.handleRequest()` method orchestrates 10 numbered steps:

```
Step 1:  Create RequestContext with UUID traceId (8-char prefix for readability)
Step 2:  routingService.matchRoute(request) → Optional<Route>
           If empty → 404 "No route found for GET /api/xyz"
Step 3:  authService.authenticate(request) → AuthResult
           If !authenticated → 401 "Authentication failed: <reason>"
Step 4:  authService.authorize(authResult, route) → boolean
           If !authorized → 403 "Access denied for route '<id>'"
Step 5:  rateLimitService.checkRouteRateLimit(ctx) → RateLimitResult
           If !allowed → 429 "Rate limit exceeded for route '<id>'"
Step 6:  circuitBreakerService.allowRequest(targetService) → boolean
           If !allowed → 503 "Service '<name>' circuit breaker is OPEN"
Step 7:  loadBalancerService.selectInstance(targetService, request) → Optional<ServiceInstance>
           If empty → 503 "No healthy instances for service '<name>'"
Step 8:  Forward to upstream (simulated: random latency 10-100ms, 5% failure rate)
Step 9:  Record success/failure in circuit breaker
           If failed → 502 "Upstream service '<name>' returned an error"
Step 10: Build HttpResponse with 200, body, and headers:
           X-Trace-Id, X-Gateway-Service, X-Gateway-Instance
```

### 7.3 Route Matching Algorithm

```
Input:  HttpRequest (method=GET, path="/api/users/123")
Routes: sorted by priority (ascending, lower = higher priority)

For each route in priority order:
  1. Check route.enabled == true
  2. Check route.methods.contains(request.method)
  3. Try exact match: route.pathPattern.equals(request.path)
  4. Try wildcard: if pathPattern ends with "/**"
     → extract prefix = pathPattern.substring(0, length - 3)
     → check request.path.startsWith(prefix)
  5. First match wins → return Optional.of(route)

No match → return Optional.empty() → 404

Example route table (sorted by priority):
  Priority  Pattern              Target          Methods
  ────────  ──────────────────── ──────────────  ──────────
  1         /health              health-check    [GET]
  5         /api/payments/**     payment-service [POST]
  10        /api/users/**        user-service    [GET,POST,PUT]
  10        /api/orders/**       order-service   [GET,POST]

Resolution:
  GET  /api/users/123     → user-service    (wildcard match, priority 10)
  POST /api/orders/new    → order-service   (wildcard match, priority 10)
  GET  /health            → health-check    (exact match, priority 1)
  DELETE /api/unknown     → 404 Not Found   (no match)

In production:
  Kong:    radix tree for O(log n) prefix matching
  Envoy:   route table with prefix/exact/regex matching, virtual hosts
  Nginx:   location blocks with prefix matching, regex, exact match
  Zuul:    filter chain with configurable route predicates
```

### 7.4 Authentication Deep Dive

```
JWT Authentication Strategy:
  ┌─────────────────────────────────────────────────────────────┐
  │ 1. Extract "Authorization" header from request              │
  │    Missing? → AuthResult.failed("Missing Authorization")    │
  │                                                             │
  │ 2. Verify format: must start with "Bearer "                 │
  │    Invalid? → AuthResult.failed("Invalid format")           │
  │                                                             │
  │ 3. Parse JWT: header.payload.signature (3 parts split by .) │
  │    Not 3 parts? → AuthResult.failed("Invalid JWT format")   │
  │                                                             │
  │ 4. Base64-decode payload, extract "sub" claim               │
  │    payload = Base64.decode(parts[1])                         │
  │    parse JSON → {"sub": "karan"} → principal = "karan"      │
  │                                                             │
  │ 5. Map principal to roles via roleMap                        │
  │    roleMap.get("karan") → Set.of("admin", "user")          │
  │                                                             │
  │ 6. Return AuthResult.success(principal="karan",             │
  │                               roles={"admin","user"})       │
  └─────────────────────────────────────────────────────────────┘

API Key Authentication Strategy:
  ┌─────────────────────────────────────────────────────────────┐
  │ 1. Extract "X-API-Key" header from request                  │
  │    Missing? → AuthResult.failed("Missing API key")          │
  │                                                             │
  │ 2. Lookup key in trusted keystore                           │
  │    keyStore.get("sk_live_abc123") → "stripe-integration"    │
  │    Not found? → AuthResult.failed("Invalid API key")        │
  │                                                             │
  │ 3. Return AuthResult.success(principal="stripe-integration")│
  └─────────────────────────────────────────────────────────────┘

Authorization (role check):
  Route metadata: {"required-role": "admin"}
  AuthResult roles: {"admin", "user"}
  → roles.contains("admin") → true → AUTHORIZED

  Route metadata: {} (no required-role)
  → any authenticated user → AUTHORIZED

In production:
  JWT: RS256 asymmetric signing (gateway has public key only, auth server has private key)
  Certificate pinning, JWKS endpoint for key rotation
  Short-lived access tokens (15 min) + long-lived refresh tokens (7 days)
  Gateway extracts claims and passes X-User-Id, X-User-Roles headers to upstream
  Upstream services NEVER see raw JWT tokens
```

---

## 8. Service Mesh Architecture

### 8.1 Control Plane vs Data Plane

```
┌──────────────────────────────────────────────────────────────┐
│                      CONTROL PLANE                           │
│  (Istio Pilot / Linkerd Control Plane / Consul Connect)      │
│                                                              │
│  ┌─────────────────┐  ┌──────────────┐  ┌───────────────┐  │
│  │ Config Server    │  │ Certificate  │  │ Service       │  │
│  │ (Pilot/Galley)   │  │ Authority    │  │ Discovery     │  │
│  │                  │  │ (Citadel)    │  │ (Registry)    │  │
│  │ • Route configs  │  │              │  │               │  │
│  │ • Retry policies │  │ • Issue certs│  │ • Instance    │  │
│  │ • CB thresholds  │  │ • Auto-rotate│  │   registration│  │
│  │ • Rate limits    │  │ • mTLS config│  │ • Health check│  │
│  │ • Traffic splits │  │ • Trust roots│  │ • Eviction    │  │
│  └────────┬─────────┘  └──────┬───────┘  └───────┬───────┘  │
│           │  xDS Protocol      │  Cert push        │          │
│           │  (LDS/RDS/CDS/EDS) │                   │          │
└───────────┼────────────────────┼───────────────────┼──────────┘
            ▼                    ▼                   ▼
┌──────────────────────────────────────────────────────────────┐
│                       DATA PLANE                             │
│           (Envoy Sidecars / Linkerd Proxy)                   │
│                                                              │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐   │
│  │   Pod A       │    │   Pod B       │    │   Pod C       │  │
│  │ ┌──────────┐  │    │ ┌──────────┐  │    │ ┌──────────┐  │  │
│  │ │ App      │  │    │ │ App      │  │    │ │ App      │  │  │
│  │ │ Container│  │    │ │ Container│  │    │ │ Container│  │  │
│  │ └────┬─────┘  │    │ └────┬─────┘  │    │ └────┬─────┘  │  │
│  │      │ localhost    │      │ localhost    │      │ localhost │
│  │ ┌────▼─────┐  │    │ ┌────▼─────┐  │    │ ┌────▼─────┐  │  │
│  │ │ Envoy    │  │    │ │ Envoy    │  │    │ │ Envoy    │  │  │
│  │ │ Sidecar  │  │    │ │ Sidecar  │  │    │ │ Sidecar  │  │  │
│  │ │ :15001   │  │    │ │ :15001   │  │    │ │ :15001   │  │  │
│  │ │ mTLS ────┼──┼────┼─┤ mTLS ────┼──┼────┼─┤ mTLS     │  │  │
│  │ │ CB       │  │    │ │ CB       │  │    │ │ CB       │  │  │
│  │ │ LB       │  │    │ │ LB       │  │    │ │ LB       │  │  │
│  │ │ Retry    │  │    │ │ Retry    │  │    │ │ Retry    │  │  │
│  │ │ Metrics  │  │    │ │ Metrics  │  │    │ │ Metrics  │  │  │
│  │ └──────────┘  │    │ └──────────┘  │    │ └──────────┘  │  │
│  └──────────────┘    └──────────────┘    └──────────────┘   │
└──────────────────────────────────────────────────────────────┘
```

### 8.2 Sidecar Proxy Pipeline

The `ServiceMeshService.proxyRequest()` method implements the sidecar proxy pipeline:

```
Input: callerService="order-service", targetService="user-service", request

Step 1: mTLS Validation (TlsEngine)
  → Check meshConfig.isMtlsEnabled()
  → If enabled: tlsEngine.validateConnection(caller, target)
    → Both caller AND target must be in trustedServices set
    → Caller not trusted? → 403 "mTLS validation failed"
    → Target not trusted? → 403 "mTLS validation failed"
  → If disabled: skip validation (not recommended in production)

Step 2: Circuit Breaker Check
  → circuitBreakerService.allowRequest(targetService)
  → If OPEN and cooldown not elapsed → 503 "Circuit breaker OPEN"
  → If OPEN and cooldown elapsed → transition to HALF_OPEN, allow
  → If CLOSED or HALF_OPEN → allow

Step 3: Load Balancer Instance Selection
  → loadBalancerService.selectInstance(targetService, request)
  → Registry filters to healthy instances only
  → Strategy selects one (round-robin / weighted / consistent hash)
  → No healthy instances? → record failure in CB → 503

Step 4: Forward Request
  → HTTP call to selected instance (simulated: 5-50ms latency, 5% failure)
  → Timeout handling based on mesh retry policy

Step 5: Record Result
  → Success → circuitBreakerService.recordSuccess(targetService)
  → Failure → circuitBreakerService.recordFailure(targetService)
    → May trip circuit to OPEN

Step 6: Return Response
  → Success → 200 with X-Mesh-Source, X-Mesh-Target, X-Mesh-Instance headers
  → Failure → 502 "Service returned an error"
```

### 8.3 xDS Protocol (Envoy Configuration)

In production, the control plane pushes configuration to Envoy sidecars via the xDS API:

```
xDS Protocol Family:
  ┌────────┬─────────────────────────────────────────────────────┐
  │ LDS    │ Listener Discovery Service                          │
  │        │ What ports to listen on, which filter chains to use │
  │        │ Maps to: sidecarPort = 15001 in ServiceMeshConfig   │
  ├────────┼─────────────────────────────────────────────────────┤
  │ RDS    │ Route Discovery Service                             │
  │        │ Route matching rules, path patterns, header matches │
  │        │ Maps to: Route table in RoutingService              │
  ├────────┼─────────────────────────────────────────────────────┤
  │ CDS    │ Cluster Discovery Service                           │
  │        │ Upstream cluster definitions (service names)        │
  │        │ Maps to: service names in ServiceRegistry           │
  ├────────┼─────────────────────────────────────────────────────┤
  │ EDS    │ Endpoint Discovery Service                          │
  │        │ Individual instance endpoints for each cluster      │
  │        │ Maps to: ServiceInstance list per service            │
  ├────────┼─────────────────────────────────────────────────────┤
  │ SDS    │ Secret Discovery Service                            │
  │        │ TLS certificates for mTLS                           │
  │        │ Maps to: TlsEngine trusted service certificates     │
  └────────┴─────────────────────────────────────────────────────┘

Configuration push model:
  Control Plane → gRPC stream → Envoy sidecar
  • Envoy subscribes to xDS streams on startup
  • Control plane pushes updates when config changes
  • Envoy applies updates with zero downtime (hot restart)
  • Version tracking prevents stale config application
```

---

## 9. Circuit Breaker State Machine

### 9.1 State Transition Diagram

```
                    ┌──────────────────────┐
                    │                      │
                    │       CLOSED         │
                    │                      │
                    │  • All requests pass  │
                    │  • Count failures     │
                    │  • Reset on success   │
                    │                      │
                    └──────────┬───────────┘
                               │
                    failureCount >= failureThreshold (5)
                               │
                               ▼
                    ┌──────────────────────┐
                    │                      │
              ┌────>│        OPEN          │
              │     │                      │
              │     │  • ALL requests      │
              │     │    rejected (503)    │
              │     │  • Wait for cooldown │
              │     │    (30 seconds)      │
              │     │                      │
              │     └──────────┬───────────┘
              │                │
              │     elapsed > openDurationMs (30s)
              │                │
              │                ▼
              │     ┌──────────────────────┐
              │     │                      │
              │     │     HALF_OPEN        │
              │     │                      │
              │     │  • Allow probe       │
              │     │    requests          │
              │     │  • Count successes   │
              │     │                      │
              │     └─────┬──────────┬─────┘
              │           │          │
              │     Any failure    successCount >= successThreshold (3)
              │           │          │
              └───────────┘          ▼
                          ┌──────────────────────┐
                          │                      │
                          │    CLOSED (reset)    │
                          │                      │
                          │  • failureCount = 0  │
                          │  • successCount = 0  │
                          │  • Normal operation  │
                          │                      │
                          └──────────────────────┘
```

### 9.2 Implementation Details

```java
// CircuitBreakerState state transitions (simplified):

recordSuccess():
  switch (state):
    HALF_OPEN:
      successCount++
      if successCount >= successThreshold:  // default 3
        reset()  // → CLOSED, clear counters
    CLOSED:
      failureCount = 0  // reset on success
    OPEN:
      // ignored while open

recordFailure():
  lastFailureTime = Instant.now()
  switch (state):
    CLOSED:
      failureCount++
      if failureCount >= failureThreshold:  // default 5
        trip()  // → OPEN
    HALF_OPEN:
      trip()  // immediately back to OPEN
    OPEN:
      // already open

shouldAttemptReset():
  if state != OPEN: return false
  elapsed = Duration.between(lastStateChange, now).toMillis()
  return elapsed > openDurationMs  // default 30_000ms

// CircuitBreakerEngine.allowRequest():
allowRequest(serviceName):
  breaker = getOrCreate(serviceName)  // computeIfAbsent
  switch (breaker.state):
    CLOSED:    return true
    OPEN:
      if breaker.shouldAttemptReset():
        breaker.halfOpen()  // transition to HALF_OPEN
        return true         // allow probe
      return false          // reject
    HALF_OPEN: return true  // allow probe
```

### 9.3 Configuration Trade-offs

```
Parameter              Low Value              High Value              Recommendation
─────────────────────  ──────────────────     ──────────────────      ──────────────────
failureThreshold       2 (very sensitive)     20 (very tolerant)      5 (balance speed vs noise)
successThreshold       1 (fast recovery)      10 (cautious recovery)  3 (confirm stability)
openDurationMs         5s (aggressive probe)  120s (long isolation)   30s (reasonable cooldown)

Production considerations:
  • Use failure RATE, not count (10 failures out of 1000 = 1% ≠ 10 out of 10 = 100%)
  • Envoy: outlier detection with configurable ejection percentage
  • Resilience4j: sliding window (count-based or time-based) for rate calculation
  • Separate thresholds for different error types (5xx vs timeout vs connection refused)
  • Health check circuit breakers (separate from request circuit breakers)
```

---

## 10. Rate Limiting Algorithms

### 10.1 Token Bucket (Implemented)

```
Token Bucket Algorithm:
  ┌───────────────────────────────────────────────────┐
  │  Bucket: maxTokens=5, refillRate=5/sec            │
  │                                                   │
  │  Time 0.0s: [#####] 5 tokens (full)               │
  │  Request 1:  [####.] 4 tokens → ALLOWED            │
  │  Request 2:  [###..] 3 tokens → ALLOWED            │
  │  Request 3:  [##...] 2 tokens → ALLOWED            │
  │  Request 4:  [#....] 1 token  → ALLOWED            │
  │  Request 5:  [.....] 0 tokens → ALLOWED            │
  │  Request 6:  [.....] 0 tokens → DENIED (429)       │
  │  Request 7:  [.....] 0 tokens → DENIED (429)       │
  │                                                   │
  │  Time 0.5s: [##...] ~2.5 tokens refilled           │
  │  Request 8:  [#....] 1.5 tokens → ALLOWED          │
  │                                                   │
  │  Steady state: ~5 requests/sec average             │
  │  Burst: up to 5 requests instantly                 │
  └───────────────────────────────────────────────────┘

Implementation:
  class TokenBucket {
    double tokens;           // current tokens (fractional for precision)
    int maxTokens;           // bucket capacity (burst limit)
    double refillRate;       // tokens per second
    Instant lastRefillTime;  // for elapsed calculation

    refill():
      elapsed = (now - lastRefillTime).toMillis() / 1000.0
      tokensToAdd = elapsed * refillRate
      tokens = min(maxTokens, tokens + tokensToAdd)
      lastRefillTime = now

    tryConsume() -> RateLimitResult:
      synchronized(this):
        refill()
        if tokens >= 1.0:
          tokens -= 1.0
          return RateLimitResult.allowed(remaining=(int)tokens)
        else:
          retryAfterMs = (long)(1000.0 / refillRate)
          return RateLimitResult.denied(retryAfterMs)
  }

Advantages:
  ✓ Allows controlled bursts (important for bursty real-world traffic)
  ✓ O(1) per request (no sliding window log to maintain)
  ✓ Smooth rate limiting after burst (refills at constant rate)
  ✓ Elegant: only two operations (refill + consume)

Disadvantages:
  ✗ Single-node: does not work across distributed gateway instances
  ✗ Solution: Redis + Lua script for atomic distributed token bucket
```

### 10.2 Algorithm Comparison

```
Algorithm           Burst Handling    Memory      Accuracy    Distributed   Use Case
──────────────────  ────────────────  ──────────  ──────────  ────────────  ──────────────────────
Fixed Window        Bad (2x at       O(1)        Approximate Easy (atomic  Simple APIs where
                    boundary)                                  counter)     approximate is OK

Sliding Window Log  Perfect          O(n) per    Exact       Hard (need    High-accuracy needs
                                     client                   sorted set)  (banking APIs)

Sliding Window      Good             O(1)        Approximate Easy          Good balance of
Counter                                                       (2 counters) accuracy and simplicity

Token Bucket        Configurable     O(1)        Good        Medium        Most APIs (allows
(THIS DESIGN)       burst via                                 (Redis+Lua)  burst, smooth limit)

Leaky Bucket        None (constant   O(1)        Exact       Medium        Strict constant-rate
                    output rate)                              (queue)      processing

Distributed rate limiting in production:
  Redis + Lua Script (atomic read-check-decrement):
    local tokens = redis.call("get", KEYS[1])
    local refillAmount = (now - lastRefill) * refillRate
    tokens = math.min(maxTokens, tokens + refillAmount)
    if tokens >= 1 then
      tokens = tokens - 1
      redis.call("set", KEYS[1], tokens)
      return 1  -- allowed
    else
      return 0  -- denied
    end

  Why Lua? Single-threaded Redis executes the script atomically.
  No race condition between read and decrement.
  Stripe, Cloudflare, GitHub all use this pattern.
```

### 10.3 Multi-Level Rate Limiting

```
In production, rate limits are enforced at multiple levels:

Level 1: Global Gateway Rate Limit
  → Protects the entire platform from DDoS
  → Example: 200 req/sec aggregate across all clients
  → Implemented as a single "global" bucket key

Level 2: Per-Route Rate Limit
  → Protects individual service endpoints
  → Example: /api/payments/** at 20 req/sec (expensive operation)
  → Route.rateLimitPerSecond = 20

Level 3: Per-Client Rate Limit
  → Fair usage enforcement per API consumer
  → Example: each API key gets 100 req/sec
  → Bucket key = API key or client IP

Level 4: Per-Tenant Rate Limit (SaaS platforms)
  → Different plans get different limits
  → Example: Free=10 req/min, Pro=100 req/sec, Enterprise=1000 req/sec
  → Bucket key = tenant ID, limits from billing system

This design implements Levels 1-3 via RateLimiterEngine with configurable per-key buckets.
```

---

## 11. Load Balancing Strategies

### 11.1 Round-Robin

```
Round-Robin Load Balancer:
  ┌──────────────────────────────────────────────────┐
  │ Instances: [A, B, C] (all healthy)               │
  │ Counter:   AtomicInteger (starts at 0)           │
  │                                                  │
  │ Request 1: counter=0, 0 % 3 = 0 → Instance A    │
  │ Request 2: counter=1, 1 % 3 = 1 → Instance B    │
  │ Request 3: counter=2, 2 % 3 = 2 → Instance C    │
  │ Request 4: counter=3, 3 % 3 = 0 → Instance A    │
  │ Request 5: counter=4, 4 % 3 = 1 → Instance B    │
  │ Request 6: counter=5, 5 % 3 = 2 → Instance C    │
  │                                                  │
  │ Distribution: A=33%, B=33%, C=33% (perfectly even)│
  └──────────────────────────────────────────────────┘

When to use:
  ✓ Homogeneous instances (same CPU, memory, capacity)
  ✓ Stateless services (no session affinity needed)
  ✓ Simple and predictable

When NOT to use:
  ✗ Heterogeneous instances (different sizes/capacities)
  ✗ Services with expensive requests (one slow request blocks instance)
  ✗ When cache affinity matters (each request goes to different instance)

Production examples: Nginx default, Kubernetes kube-proxy, AWS ALB
```

### 11.2 Weighted Load Balancing

```
Weighted Load Balancer:
  ┌──────────────────────────────────────────────────┐
  │ Instances with weights:                          │
  │   A (weight=3, us-east-1a)                       │
  │   B (weight=2, us-east-1b)                       │
  │   C (weight=5, us-west-2a)                       │
  │                                                  │
  │ totalWeight = 3 + 2 + 5 = 10                     │
  │                                                  │
  │ Algorithm (weighted random):                     │
  │   1. random = ThreadLocalRandom(0, 10)           │
  │   2. Iterate instances, accumulating weight:     │
  │      A: accumulated=3, if random < 3 → A (30%)  │
  │      B: accumulated=5, if random < 5 → B (20%)  │
  │      C: accumulated=10, if random < 10 → C (50%)│
  │                                                  │
  │ Expected distribution over 100 requests:         │
  │   A (w=3): ~30 requests (30%)                    │
  │   B (w=2): ~20 requests (20%)                    │
  │   C (w=5): ~50 requests (50%)                    │
  └──────────────────────────────────────────────────┘

Use cases:
  1. Heterogeneous fleet: larger instances get higher weight
  2. Canary deployment: new version starts at weight=1, stable at weight=10
  3. AZ-aware routing: prefer instances in the same AZ (higher weight)
  4. Gradual rollout: increase weight of new version over time

Production: Envoy weighted cluster routing, Nginx upstream weight, ALB target group weight
```

### 11.3 Consistent Hash Load Balancing

```
Consistent Hash Load Balancer:
  ┌───────────────────────────────────────────────────────────────┐
  │ Hash Ring (TreeMap<Integer, ServiceInstance>):                 │
  │                                                               │
  │ Physical instances: A, B, C                                   │
  │ Virtual nodes per instance: 150 (total ring size: 450 entries)│
  │                                                               │
  │              0                                                │
  │            .─────.                                            │
  │          ╱    A-v2  ╲     ← virtual node of A at hash position│
  │        ╱              ╲                                       │
  │      B-v0              C-v1                                   │
  │     │                    │                                    │
  │     │   Hash Ring        │                                    │
  │     │   (TreeMap)        │                                    │
  │     │                    │                                    │
  │      A-v1              B-v2                                   │
  │        ╲              ╱                                       │
  │          ╲    C-v0  ╱                                         │
  │            '─────'                                            │
  │           MAX_INT                                             │
  │                                                               │
  │ Lookup:                                                       │
  │   1. hash(request.path) using FNV-1a                          │
  │   2. ring.ceilingEntry(hash) → nearest clockwise virtual node │
  │   3. If null (past max) → ring.firstEntry() (wrap around)    │
  │                                                               │
  │ Example:                                                      │
  │   hash("/api/users/100") = 42857                              │
  │   ring.ceilingEntry(42857) → A-v47 → Instance A              │
  │                                                               │
  │   hash("/api/users/200") = 91234                              │
  │   ring.ceilingEntry(91234) → C-v12 → Instance C              │
  │                                                               │
  │ Same path ALWAYS routes to same instance (cache affinity!)    │
  └───────────────────────────────────────────────────────────────┘

FNV-1a Hash Function:
  int hash = 0x811c9dc5;   // FNV offset basis
  for each char in key:
    hash ^= char;
    hash *= 0x01000193;    // FNV prime
  return hash & 0x7FFFFFFF; // ensure non-negative

Why virtual nodes?
  Without: 3 instances → 3 points on ring → very uneven distribution
  With 150 virtual nodes: 3 instances → 450 points → nearly uniform distribution

  Virtual node key format: "instanceId-vnode-N" (e.g., "user-svc-1-vnode-42")

Instance removal impact:
  Traditional hash (hash % N): adding/removing 1 instance remaps ~100% of keys
  Consistent hash: removing 1 instance remaps only ~1/N of keys (~33% for 3 instances)

Production: Memcached client, DynamoDB partition routing, Cassandra token ring,
            Envoy ring hash load balancer, Nginx upstream consistent hash
```

---

## 12. mTLS and Zero-Trust Security

### 12.1 Zero-Trust Architecture

```
Traditional perimeter security:
  ┌─────────────────────────────────────┐
  │ Firewall / VPN                      │
  │  "Everything inside is trusted"     │  ← WRONG
  │  Service A ──plaintext──> Service B │
  │  (no authentication, no encryption) │
  └─────────────────────────────────────┘

Zero-trust security (service mesh):
  ┌─────────────────────────────────────────────────────┐
  │ "Never trust, always verify"                        │
  │                                                     │
  │ Service A                    Service B              │
  │ ┌───────┐   mTLS            ┌───────┐              │
  │ │ App   │──>│Sidecar│─encrypted─>│Sidecar│──>│ App  ││
  │ └───────┘   │       │           │       │   └──────┘│
  │             │ Cert A │           │ Cert B │          │
  │             │ Trust? │           │ Trust? │          │
  │             └────────┘           └────────┘          │
  │                                                     │
  │ Both sides authenticate:                            │
  │   A presents certificate → B verifies A is trusted  │
  │   B presents certificate → A verifies B is trusted  │
  │   → Encrypted channel established                   │
  └─────────────────────────────────────────────────────┘
```

### 12.2 mTLS in This Design

```
TlsEngine implementation:
  trustedServices: HashSet<String>
    → Contains service names with valid certificates
    → e.g., {"api-gateway", "user-service", "order-service", "payment-service"}

  mtlsEnabled: boolean
    → Global toggle for mTLS enforcement

  validateConnection(callerService, targetService):
    1. if !mtlsEnabled → return false (skip validation)
    2. callerTrusted = trustedServices.contains(callerService)
    3. targetTrusted = trustedServices.contains(targetService)
    4. allowed = callerTrusted AND targetTrusted
    5. If !allowed → log denial with details
    6. Return allowed

  Scenarios:
    order-service → user-service:   BOTH trusted → ALLOWED
    order-service → unknown-service: target NOT trusted → DENIED (403)
    rogue-service → user-service:    caller NOT trusted → DENIED (403)
    unknown → unknown:               BOTH NOT trusted → DENIED (403)

In production (Istio Citadel):
  1. Pod starts → Istio injects Envoy sidecar + init container
  2. Init container configures iptables to redirect all traffic through sidecar
  3. Citadel issues SPIFFE identity certificate to sidecar:
     spiffe://cluster.local/ns/default/sa/order-service
  4. Certificates auto-rotate every 24 hours (no application involvement)
  5. Authorization policies define who can talk to whom:
     apiVersion: security.istio.io/v1beta1
     kind: AuthorizationPolicy
     spec:
       rules:
       - from:
         - source:
             principals: ["cluster.local/ns/default/sa/order-service"]
         to:
         - operation:
             methods: ["GET"]
             paths: ["/api/users/*"]
```

### 12.3 Certificate Lifecycle

```
Certificate Management in Service Mesh:

  ┌──────────┐   1. Request cert    ┌──────────────┐
  │  Envoy   │ ────────────────────> │   Citadel    │
  │  Sidecar │                       │ (Cert Auth)  │
  │          │ <──────────────────── │              │
  │          │   2. Issue SPIFFE     │ Signs with   │
  │          │      identity cert    │ root CA      │
  └──────────┘                       └──────────────┘

  Certificate properties:
    Subject: spiffe://cluster.local/ns/{namespace}/sa/{service-account}
    Validity: 24 hours (auto-renewed before expiry)
    Key type: RSA 2048 or ECDSA P-256
    Trust chain: Root CA → Intermediate CA → Workload certificate

  Rotation flow:
    1. Certificate issued at pod startup (24h validity)
    2. Envoy requests renewal at 50% lifetime (12h mark)
    3. Citadel issues new certificate with fresh validity period
    4. Envoy hot-swaps certificate (zero downtime, no connection drop)
    5. Old connections continue with old cert until naturally closed

  Scale: Uber rotates ~200K certificates per day across their mesh.
         Google BeyondProd rotates millions of certificates daily.
```

---

## 13. Canary Deployments & Traffic Shaping

### 13.1 Canary Deployment Flow

```
Progressive Delivery Lifecycle:

  Phase 1: Deploy Canary (1% traffic)
  ┌────────────────────────────────────────────────────────┐
  │ TrafficSplit: {"v1-stable": 99, "v2-canary": 1}       │
  │                                                        │
  │ 99% traffic ──> v1-stable (10 instances, weight=10)    │
  │  1% traffic ──> v2-canary (1 instance, weight=1)       │
  │                                                        │
  │ Monitor for 5 minutes:                                 │
  │   ✓ Error rate: canary 0.1% vs baseline 0.1% → OK     │
  │   ✓ P99 latency: canary 120ms vs baseline 115ms → OK  │
  │   ✓ No circuit breaker trips on canary                 │
  └────────────────────────────────────────────────────────┘

  Phase 2: Increase to 10%
  ┌────────────────────────────────────────────────────────┐
  │ TrafficSplit: {"v1-stable": 90, "v2-canary": 10}       │
  │                                                        │
  │ Monitor for 10 minutes:                                │
  │   ✓ Error rate within tolerance                        │
  │   ✓ Latency within tolerance                           │
  └────────────────────────────────────────────────────────┘

  Phase 3: Increase to 50%
  ┌────────────────────────────────────────────────────────┐
  │ TrafficSplit: {"v1-stable": 50, "v2-canary": 50}       │
  │                                                        │
  │ Monitor for 15 minutes:                                │
  │   ✓ All metrics healthy                                │
  └────────────────────────────────────────────────────────┘

  Phase 4: Full rollout (100%)
  ┌────────────────────────────────────────────────────────┐
  │ TrafficSplit: {"v2-canary": 100}                        │
  │ (rename v2-canary → v2-stable, decommission v1)        │
  └────────────────────────────────────────────────────────┘

  Automated Rollback (if any phase fails):
  ┌────────────────────────────────────────────────────────┐
  │ Trigger: error rate > baseline + 2σ                    │
  │     OR:  P99 latency > baseline + 10%                  │
  │     OR:  circuit breaker trips on canary               │
  │                                                        │
  │ Action: TrafficSplit → {"v1-stable": 100}               │
  │ Time: < 60 seconds from anomaly detection              │
  └────────────────────────────────────────────────────────┘
```

### 13.2 Traffic Shaping Strategies

```
Strategy 1: Canary (Weighted Random)
  ┌───────────────────────────────────────────────────┐
  │ CanaryTrafficStrategy.selectVersion(req, split):  │
  │                                                   │
  │   splits = {"v1-stable": 90, "v2-canary": 10}    │
  │   totalWeight = 90 + 10 = 100                     │
  │   random = ThreadLocalRandom.nextInt(100)         │
  │                                                   │
  │   Iterate:                                        │
  │     v1-stable: accumulated=90                     │
  │       if random < 90 → return "v1-stable"         │
  │     v2-canary: accumulated=100                    │
  │       if random < 100 → return "v2-canary"        │
  │                                                   │
  │   ~90% of requests → v1-stable                    │
  │   ~10% of requests → v2-canary                    │
  └───────────────────────────────────────────────────┘

Strategy 2: Header-Based (Targeted Testing)
  ┌───────────────────────────────────────────────────┐
  │ HeaderBasedTrafficStrategy.selectVersion(req, sp):│
  │                                                   │
  │   if request.getHeader("X-Canary") == "true":     │
  │     → return last version from split (canary)     │
  │   else:                                           │
  │     → return first version from split (stable)    │
  │                                                   │
  │   Use cases:                                      │
  │     QA team adds X-Canary: true → always canary   │
  │     Normal users → always stable                  │
  │     Dogfooding: internal employees → canary       │
  └───────────────────────────────────────────────────┘

Strategy 3: A/B Testing (not implemented, extension point)
  ┌───────────────────────────────────────────────────┐
  │ Route based on user attributes:                   │
  │   user.country == "US" → version A                │
  │   user.country == "EU" → version B                │
  │   user.plan == "enterprise" → early access        │
  └───────────────────────────────────────────────────┘

Production tools:
  Istio VirtualService:
    apiVersion: networking.istio.io/v1beta1
    kind: VirtualService
    spec:
      http:
      - match:
        - headers:
            x-canary:
              exact: "true"
        route:
        - destination:
            host: order-service
            subset: canary
      - route:
        - destination:
            host: order-service
            subset: stable
          weight: 90
        - destination:
            host: order-service
            subset: canary
          weight: 10

  AWS ALB: weighted target groups
  Kubernetes: Argo Rollouts with AnalysisRun
  Nginx: split_clients directive
```

---

## 14. Scaling Strategy

### 14.1 Gateway Scaling

```
Horizontal Scaling:
  ┌─────────────────────────────────────────────────────────────┐
  │ Gateway instances are STATELESS → horizontal scaling is     │
  │ straightforward. Add more instances behind the load balancer│
  │                                                             │
  │ What scales linearly:                                       │
  │   ✓ Request processing (CPU-bound: auth, routing)           │
  │   ✓ Connection handling (each instance handles its own)     │
  │   ✓ Circuit breaker state (per-instance, eventual sync)     │
  │                                                             │
  │ What needs coordination:                                    │
  │   ✗ Rate limiting (per-client limits across instances)      │
  │     → Redis centralized counter                             │
  │   ✗ Route table updates (must propagate to all instances)   │
  │     → Config server push or periodic pull                   │
  │   ✗ Circuit breaker state (ideally consistent across gw)    │
  │     → Per-instance local state is acceptable (N instances   │
  │       each see 1/N of the failures, so trip at N*threshold) │
  └─────────────────────────────────────────────────────────────┘

Capacity Planning:
  ┌──────────────────────────────────────────────────────────┐
  │ Single gateway instance capacity:                        │
  │   Kong:      50,000-100,000 RPS (Nginx-based, C/Lua)    │
  │   Envoy:     50,000-80,000 RPS (C++, async I/O)         │
  │   Zuul 2:    20,000-50,000 RPS (Netty-based, async)     │
  │   Spring GW: 10,000-30,000 RPS (Reactor/Netty)          │
  │                                                          │
  │ For 100K RPS target:                                     │
  │   Option A: 2-3 Kong instances (with 2x headroom = 6)   │
  │   Option B: 2-4 Envoy instances (with 2x headroom = 8)  │
  │                                                          │
  │ Resources per instance:                                  │
  │   CPU:    4-8 cores (SSL termination is CPU-intensive)   │
  │   Memory: 2-4 GB (route table + rate limit buckets)      │
  │   Network: 10 Gbps NIC (for high throughput)             │
  └──────────────────────────────────────────────────────────┘
```

### 14.2 Service Mesh Scaling

```
Data Plane Scaling:
  ┌───────────────────────────────────────────────────────────┐
  │ Envoy sidecar resource overhead per pod:                  │
  │   CPU:    50-100m (0.05-0.1 core)                         │
  │   Memory: 50-100 MB                                       │
  │   Latency: 0.5-1ms per hop (inbound + outbound)          │
  │                                                           │
  │ At 5000 pods: 5000 * 100m = 500 cores overhead            │
  │ At 5000 pods: 5000 * 100MB = 500 GB memory overhead       │
  │                                                           │
  │ Optimization:                                             │
  │   • Ambient mesh (Istio 1.15+): no sidecar per pod        │
  │     → shared ztunnel per node, reduces overhead 10x       │
  │   • Resource limits: set CPU/memory limits on sidecars    │
  │   • Protocol detection: avoid full L7 processing for      │
  │     simple TCP connections                                │
  └───────────────────────────────────────────────────────────┘

Control Plane Scaling:
  ┌───────────────────────────────────────────────────────────┐
  │ Istio Pilot (istiod) scalability:                         │
  │   • 1 Pilot instance handles ~1000 sidecars               │
  │   • 5 Pilot replicas for 5000 sidecars                    │
  │   • xDS push latency: <1s for config updates              │
  │   • Memory: ~1 GB per 1000 endpoints                      │
  │                                                           │
  │ Bottleneck: large service meshes (10K+ services) hit      │
  │ Pilot memory limits due to endpoint tracking. Sharding    │
  │ by namespace or cluster reduces per-instance load.        │
  └───────────────────────────────────────────────────────────┘
```

### 14.3 Rate Limiting at Scale

```
Distributed Rate Limiting:
  ┌───────────────────────────────────────────────────────────┐
  │ Problem: 4 gateway instances, per-client limit of 100/sec │
  │                                                           │
  │ Naive approach: each instance allows 25/sec               │
  │   → WRONG: uneven distribution means some instances get   │
  │     more traffic, hitting local limit while others idle   │
  │                                                           │
  │ Solution: Centralized Redis counter                       │
  │                                                           │
  │   Gateway 1 ──┐                                           │
  │   Gateway 2 ──┤──> Redis (atomic Lua script) ──> allow/deny│
  │   Gateway 3 ──┤    EVAL token_bucket.lua 1 "client:abc"  │
  │   Gateway 4 ──┘                                           │
  │                                                           │
  │ Hybrid approach (Envoy pattern):                          │
  │   Local rate limiter: fast, per-instance (first line)     │
  │   + Global rate limiter: Redis-backed (second line)       │
  │   → Local catches obvious abuse (<1ms)                    │
  │   → Global enforces accurate per-client limits (~1ms)     │
  └───────────────────────────────────────────────────────────┘
```

---

## 15. Database Choices

### 15.1 Storage Components

```
Component               Storage Choice          Rationale
──────────────────────  ──────────────────      ──────────────────────────────────────
Route Table             In-memory (HashMap)     ~1000 routes, <1 MB, read-heavy, must be
                                                sub-millisecond. Backed by config server
                                                (etcd/Consul) for persistence + distribution.

Service Registry        In-memory (ConcurrentHashMap)  ~5000 instances, <3 MB, frequent
                                                       updates (heartbeats every 10s).
                                                       Backed by etcd/Consul/ZooKeeper.

Rate Limit State        Redis                   Distributed token buckets need atomic
                                                operations across gateway instances.
                                                Redis + Lua script for atomicity.
                                                TTL for automatic bucket expiry.

Circuit Breaker State   In-memory (local)       Per-gateway-instance state. Eventual
                                                consistency is acceptable (each instance
                                                observes its own failure rate).

mTLS Certificates       Secrets store           HashiCorp Vault, AWS Secrets Manager,
                                                or Kubernetes Secrets. Istio Citadel
                                                manages cert lifecycle automatically.

Traffic Split Config    Config store            etcd, Consul KV, or Kubernetes CRDs
                                                (Istio VirtualService). Pushed to
                                                gateways/sidecars via xDS.

Audit Logs              Append-only log         Kafka for real-time streaming →
                                                Elasticsearch for search →
                                                S3 for long-term archive.

Metrics & Observability Time-series DB          Prometheus for metrics collection,
                                                Jaeger/Zipkin for distributed tracing,
                                                Grafana for dashboards.
```

### 15.2 Why Not a Traditional Database?

```
Q: "Why not PostgreSQL for route and service instance storage?"

A: Route table and service registry are:
   1. Small (< 5 MB total) → fits entirely in memory
   2. Read-heavy (matched on every request) → must be sub-millisecond
   3. Updated infrequently (seconds to minutes, not per-request)

   PostgreSQL round-trip: 1-5ms per query
   In-memory HashMap: 0.001ms per lookup (1000x faster)

   At 100K RPS, even 1ms overhead = 100K ms = 100 seconds of CPU per second
   → you need more gateway instances just for database I/O

   The pattern: in-memory cache with durable backing store
   → Local: ConcurrentHashMap (hot path, every request)
   → Remote: etcd/Consul (durable, distributed, config source of truth)
   → Sync: periodic pull (every 5s) or push notification (watch/subscribe)

Q: "Why Redis for rate limiting instead of in-memory?"

A: In-memory rate limiting only works for single-instance gateways.
   With 4 gateway instances behind a load balancer:
   → Client sends 100 requests, load balancer distributes 25 to each instance
   → Each instance's local bucket sees 25 requests (under 100 limit)
   → Client actually made 100 requests successfully (limit violated!)

   Redis provides a shared counter that all gateway instances read/write atomically.
   Redis + Lua script latency: ~0.5ms (acceptable for rate limiting).
```

---

## 16. CAP Analysis

### 16.1 Gateway Components

```
Component              CAP Choice    Rationale
─────────────────────  ────────────  ──────────────────────────────────────────────
Route Table            AP            A stale route table is acceptable for seconds.
                                     Gateway continues serving known routes during
                                     a config server partition. Better to route to a
                                     slightly stale target than to reject all requests.
                                     Eventual consistency via periodic config pull.

Authentication (JWT)   CP            An unauthorized request must NEVER be allowed.
                                     If the JWT validation key is unavailable, reject
                                     the request (503) rather than allow it through.
                                     Fail closed, not open. Security is a correctness
                                     property, not a performance property.

Rate Limiting          CP            Rate limits must be enforced accurately across
                                     all gateway instances. Under-limiting allows abuse
                                     (DDoS, cost explosion). Over-limiting is annoying
                                     but safe. Use Redis with strong consistency
                                     guarantees (single-master, replicated).

Circuit Breaker State  AP            Per-instance circuit breaker state is acceptable.
                                     If 4 gateways each track failures independently,
                                     each sees 1/N of the failures. Slightly delayed
                                     trip is acceptable vs. rejecting valid requests
                                     due to stale shared state.

Service Discovery      AP            An eventually-consistent instance list is better
                                     than no list at all. During a partition with the
                                     registry, gateway continues using its cached
                                     instance list. Stale entries may route to dead
                                     instances (handled by circuit breaker + retry).

mTLS Trust Store       CP            The trusted service set must be consistent. An
                                     untrusted service must NEVER be allowed to
                                     communicate. If the trust store is unavailable,
                                     deny the connection (fail closed). Security
                                     correctness overrides availability.

Traffic Split Config   AP            A stale traffic split (still 90/10 when it should
                                     be 80/20) is safe — the canary just gets slightly
                                     different traffic than intended for a few seconds.
                                     Better than rejecting all requests during a
                                     config push failure.
```

### 16.2 Overall System

```
The API Gateway is predominantly AP (Availability + Partition Tolerance):
  → The gateway must always be available (it is the single entry point)
  → Brief staleness in config is acceptable
  → Circuit breakers and retries handle transient failures

EXCEPT for security-critical paths (CP):
  → Authentication: fail closed (reject if can't verify)
  → mTLS: fail closed (deny if can't verify trust)
  → Rate limiting: fail accurate (enforce limits precisely)

This is the correct trade-off for an API gateway:
  A gateway that rejects all requests during a config update is worse
  than one that serves slightly stale routes for 5 seconds.
  But a gateway that allows unauthorized requests is a security breach.
```

---

## 17. Cloud Mapping

### 17.1 AWS Implementation

```
Component                This Design             AWS Service
─────────────────────    ──────────────────      ──────────────────────────
API Gateway              GatewayService           Amazon API Gateway (REST/HTTP)
                                                  or AWS App Mesh (Envoy-based)

Load Balancer            LoadBalancerService       Application Load Balancer (ALB)
                                                   Network Load Balancer (NLB)

Service Discovery        ServiceRegistry           AWS Cloud Map
                                                   ECS Service Discovery
                                                   EKS with CoreDNS

Rate Limiting            RateLimiterEngine          API Gateway throttling
                                                   WAF rate rules
                                                   ElastiCache (Redis) for custom

Circuit Breaker          CircuitBreakerEngine      AWS App Mesh circuit breaker
                                                   (Envoy outlier detection)

mTLS                     TlsEngine                 AWS App Mesh mTLS
                                                   AWS Certificate Manager (ACM)
                                                   Private CA for service certs

Canary Deployment        TrafficStrategy           CodeDeploy (Lambda/ECS canary)
                                                   ALB weighted target groups
                                                   App Mesh virtual router weights

Authentication           AuthStrategy              Cognito User Pools
                                                   IAM authorizer
                                                   Lambda authorizer

Config Distribution      AppConfig                 AWS AppConfig
                                                   Parameter Store
                                                   App Mesh control plane

Observability            GatewayStatsDisplay       CloudWatch Metrics + Logs
                                                   X-Ray (distributed tracing)
                                                   Container Insights
```

### 17.2 GCP Implementation

```
Component                GCP Service
─────────────────────    ──────────────────────────────
API Gateway              Apigee, Cloud Endpoints, Traffic Director
Load Balancer            Cloud Load Balancing (global, anycast)
Service Discovery        Cloud Service Directory, GKE Service Mesh
Rate Limiting            Apigee quotas, Cloud Armor rate limiting
Circuit Breaker          Traffic Director (Envoy), Anthos Service Mesh
mTLS                     Anthos Service Mesh (Istio-based), Certificate Authority Service
Canary Deployment        Cloud Deploy, GKE Gateway API traffic splitting
Authentication           Identity-Aware Proxy (IAP), Firebase Auth
Config Distribution      Traffic Director xDS, GKE ConfigMaps
Observability            Cloud Monitoring, Cloud Trace, Cloud Logging
```

### 17.3 Kubernetes-Native Implementation

```
Component                Kubernetes / CNCF
─────────────────────    ──────────────────────────────
API Gateway              Kubernetes Gateway API, Nginx Ingress, Traefik, Kong Ingress
Load Balancer            kube-proxy (iptables/IPVS), Envoy, MetalLB
Service Discovery        CoreDNS, Kubernetes Service + Endpoints
Rate Limiting            Envoy local rate limiter, external rate limit service
Circuit Breaker          Istio DestinationRule (outlier detection), Envoy
mTLS                     Istio (Citadel), Linkerd (identity), cert-manager
Canary Deployment        Argo Rollouts, Flagger, Istio VirtualService
Authentication           OPA/Gatekeeper, Istio AuthorizationPolicy, dex
Config Distribution      Istio Pilot (xDS), Kubernetes ConfigMaps
Observability            Prometheus + Grafana, Jaeger/Tempo, Loki/Fluentd
```

---

## 18. Failure Scenarios

### 18.1 Gateway Instance Failure

```
Scenario: One of 4 gateway instances crashes mid-request.

Impact:
  → In-flight requests on the crashed instance fail (connection reset)
  → Clients see connection timeout or 502 from load balancer
  → ~25% of traffic affected for 1-10 seconds

Detection:
  → Health check fails (ALB checks /health endpoint every 10 seconds)
  → ALB marks instance unhealthy after 2 consecutive failures (20s)

Recovery:
  → ALB stops routing to failed instance within 20-30 seconds
  → Remaining 3 instances absorb 33% more traffic each
  → Auto-scaling group launches replacement instance (2-5 minutes)
  → New instance pulls config from etcd, registers with ALB

Mitigation:
  → Client-side retry with exponential backoff (1s, 2s, 4s)
  → Connection draining: ALB stops new requests but lets in-flight complete
  → N+1 redundancy: run 5 instances for 4-instance capacity
```

### 18.2 Upstream Service Degradation

```
Scenario: Payment service latency increases from 100ms to 5000ms.

Without circuit breaker:
  → Gateway threads block waiting for payment responses
  → Thread pool exhaustion → gateway cannot serve ANY requests
  → Cascading failure: user-service and order-service also fail
  → ENTIRE PLATFORM DOWN because ONE service is slow

With circuit breaker (this design):
  Step 1: Payment service slows down (5000ms responses)
  Step 2: Requests to payment-service start timing out
  Step 3: circuitBreakerEngine records failures for "payment-service"
  Step 4: After 5 consecutive failures → state transitions to OPEN
  Step 5: All subsequent payment requests immediately get 503
           (no thread waiting, no resource consumption)
  Step 6: After 30-second cooldown → transition to HALF_OPEN
  Step 7: Allow 1 probe request to payment-service
  Step 8a: If probe succeeds (3 times) → transition to CLOSED (recovered)
  Step 8b: If probe fails → back to OPEN (still degraded)

Impact with circuit breaker:
  → Payment requests fail fast (503 in <1ms instead of 5000ms timeout)
  → User-service and order-service continue working normally
  → Gateway resources are not exhausted
  → Blast radius: ONLY payment-related requests affected
```

### 18.3 Rate Limiter Redis Failure

```
Scenario: Redis cluster used for distributed rate limiting becomes unavailable.

Option A: Fail OPEN (allow all requests)
  → Temporarily disables rate limiting
  → Risk: abuse, DDoS, cost explosion
  → Acceptable for: low-risk APIs, internal services

Option B: Fail CLOSED (deny all requests)
  → Temporarily blocks all requests
  → Risk: complete service outage
  → Acceptable for: never (this is worse than no rate limiting)

Option C: Fall back to LOCAL rate limiting (this design's approach)
  → Each gateway instance uses its in-memory token bucket
  → Per-instance limit = global limit / N instances
  → Imperfect but functional
  → Risk: uneven distribution means limits are approximate
  → Acceptable for: most production systems

Recovery:
  → Redis recovers → gateway reconnects → global limits restored
  → No data loss needed (rate limit state is ephemeral)
  → Bucket state rebuilds naturally on first request per client
```

### 18.4 Service Discovery Failure

```
Scenario: etcd/Consul (service registry backing store) becomes unavailable.

Impact:
  → New instances cannot register
  → Dead instances cannot be deregistered
  → Gateway continues using CACHED instance list

This design's behavior:
  → ServiceRegistry stores instances in ConcurrentHashMap (in-memory)
  → If backing store fails, cached list remains valid
  → evictStale(Duration) removes instances that stop heartbeating
  → Load balancer routes to cached healthy instances
  → Circuit breaker handles individual instance failures

Recovery:
  → Registry recovers → instances re-register → cache updated
  → Any instances that started during outage register on recovery
  → Brief gap: new instances not routable during registry outage

Mitigation:
  → DNS-based fallback: if service discovery fails, fall back to DNS SRV records
  → Long cache TTL: cache instance list for 5 minutes (outlasts brief outages)
  → Stale-while-revalidate: serve cached data while attempting to refresh
```

### 18.5 mTLS Certificate Expiry

```
Scenario: Citadel (certificate authority) is unavailable, certificates expire.

Impact:
  → Sidecars cannot renew certificates
  → After certificate expiry, mTLS handshakes fail
  → ALL service-to-service communication fails
  → Complete internal communication outage

Mitigation:
  → Certificate validity: 24 hours (Istio default)
  → Renewal at 50% lifetime: 12 hours before expiry
  → Grace period: certificate accepted up to 1 hour past expiry (configurable)
  → Citadel HA: 3 replicas with leader election
  → Fallback: allow plaintext fallback (PERMISSIVE mode) during recovery
    → Security trade-off: temporary unencrypted traffic vs total outage

Production incident (real):
  → Istio has experienced certificate rotation bugs in versions 1.1-1.3
  → Lesson: monitor certificate expiry as a critical metric
  → Alert: "Certificate expires in < 2 hours" → page on-call immediately
```

### 18.6 Canary Deployment Gone Wrong

```
Scenario: Canary version has a bug that causes 50% error rate on /api/orders.

Timeline:
  T+0:    Deploy v2-canary with 10% traffic split
  T+30s:  Monitoring detects error rate spike on canary (50% vs 0.1% baseline)
  T+45s:  Automated analysis: error_rate_canary >> error_rate_baseline + 2σ
  T+60s:  AUTOMATED ROLLBACK: TrafficSplit → {"v1-stable": 100}
          All traffic routes to stable version

Impact:
  → 10% of traffic affected for 60 seconds
  → At 100K RPS: ~6000 requests failed (10% * 100K * 60s * 0.5 error rate)
  → Without canary: 100% of traffic affected = ~3M requests failed

Circuit breaker interaction:
  → If canary instance triggers circuit breaker for "order-service":
    → Gateway fast-fails ALL order requests (including stable!)
  → Solution: separate circuit breakers per version (canary vs stable)
  → Or: canary traffic routed to separate service instances with separate CB

This design's approach:
  → TrafficSplit routes to version labels, not directly to instances
  → Circuit breaker is per-service-name, not per-version
  → In production, use separate Kubernetes Deployments per version
    → Each deployment has its own circuit breaker via Envoy
```

---

## 19. Interview Walkthrough Script

### 19.1 Opening (2 minutes)

```
"I'm going to design an API Gateway and Service Mesh -- the infrastructure layer
that handles all incoming traffic routing, authentication, rate limiting, and
inter-service communication for a microservice platform.

The core insight is that cross-cutting concerns like auth, rate limiting, and
observability should be handled ONCE at the infrastructure layer, not duplicated
in every microservice.

I'll cover two main components:
1. API Gateway (north-south traffic): external requests → route → auth → rate limit →
   circuit breaker → load balance → forward to service
2. Service Mesh (east-west traffic): service-to-service with mTLS, circuit breakers,
   and traffic shaping -- all via sidecar proxies without changing application code

Let me start with requirements and work through the architecture."
```

### 19.2 Requirements (3 minutes)

```
"Functional: route matching (path/header), JWT + API key auth, per-route rate limiting
with token bucket, circuit breaker (CLOSED/OPEN/HALF_OPEN), round-robin + weighted +
consistent hash load balancing, mTLS for zero-trust, canary deployments with traffic
splitting.

Non-functional: <1ms gateway overhead, 100K+ RPS per instance, 99.99% availability,
health check detection within 10 seconds, canary rollback within 60 seconds.

Scale: 100K external RPS, 1M internal RPCs/sec, 5000 service instances."
```

### 19.3 API Design (3 minutes)

```
"Gateway API:
  POST /gateway/request -- full pipeline (route → auth → rate limit → CB → LB → forward)
  Response: 200 OK, or 401/403/404/429/502/503 with X-Trace-Id header

  Each error code maps to a specific pipeline step failure:
    404 = no route match
    401 = authentication failed
    403 = authorization failed
    429 = rate limit exceeded (with Retry-After header)
    503 = circuit breaker OPEN or no healthy instances
    502 = upstream service error

Service Mesh:
  Transparent sidecar proxy -- application makes normal HTTP calls.
  Sidecar intercepts, applies mTLS + CB + LB, forwards to target.
  Zero application code changes."
```

### 19.4 Architecture Deep Dive (15 minutes)

```
"The gateway pipeline is a Chain of Responsibility with 7 stages that can each
short-circuit:

[Draw the pipeline diagram from Section 6.2]

Key implementation details:

1. ROUTING: glob patterns (/api/users/**), priority-sorted, first-match wins.
   In production, Kong uses radix tree for O(log n) matching.

2. RATE LIMITING: token bucket algorithm. Bucket capacity C = burst limit.
   Refill rate R = steady-state limit. Tokens = min(C, tokens + elapsed * R).
   Consume one per request. In production, Redis + Lua for distributed counting.

3. CIRCUIT BREAKER: three-state machine.
   CLOSED: count failures. At threshold → OPEN.
   OPEN: reject all. After cooldown → HALF_OPEN.
   HALF_OPEN: allow probes. Success threshold → CLOSED. Any failure → OPEN.

   [Draw the state machine from Section 9.1]

4. LOAD BALANCING: three strategies via Strategy pattern.
   Round-robin: fair but ignores capacity.
   Weighted: proportional to instance weight, great for canary.
   Consistent hash: TreeMap ring with virtual nodes, cache affinity.

   [Draw the hash ring from Section 11.3]

5. SERVICE MESH: sidecar proxy pattern.
   Control plane (Istio Pilot) configures data plane (Envoy sidecars) via xDS.
   mTLS: both caller and target authenticate. Zero-trust by default.
   Language-agnostic: sidecar handles it all, app speaks plain HTTP to localhost."
```

### 19.5 Scaling & Trade-offs (5 minutes)

```
"Gateway scaling: stateless instances behind ALB. Add more for more throughput.
Challenge: distributed rate limiting (solved with Redis + Lua).

Service mesh scaling: Envoy sidecar per pod. ~100m CPU, 100MB RAM overhead each.
At 5000 pods: 500 cores + 500GB overhead. Ambient mesh reduces this 10x.

CAP trade-offs:
  Routing, service discovery, circuit breaker: AP (availability over consistency)
  Auth, rate limiting, mTLS: CP (correctness over availability, fail closed)

The key insight: the gateway must always be available (it's the single entry point),
but security-critical paths must fail closed, never open."
```

### 19.6 Failure Scenarios (5 minutes)

```
"Five key failure scenarios:

1. Gateway crash: ALB health check detects in 20s, routes to remaining instances.
   N+1 redundancy ensures capacity.

2. Service degradation: Circuit breaker trips after 5 failures → 503 fast-fail.
   Prevents cascading failure. 30s cooldown → HALF_OPEN → probe recovery.

3. Redis failure (rate limiter): Fall back to local per-instance token buckets.
   Approximate limits but functional. Never fail closed on rate limiter failure.

4. Registry failure: Gateway uses cached instance list. evictStale() removes dead
   instances. Circuit breaker handles individual instance failures.

5. Bad canary: Automated rollback within 60s when error rate exceeds baseline + 2σ.
   Only 10% of traffic affected during the observation window."
```

### 19.7 Closing (2 minutes)

```
"Design patterns: Strategy (routing, LB, auth, traffic x4), Builder (HttpRequest,
Route, ServiceMeshConfig), Factory (AppConfig), Facade (GatewayService), State
(CircuitBreaker), Chain of Responsibility (pipeline), Proxy (sidecar), Singleton
(AppConfig), Repository (2 data access interfaces).

Real-world: Netflix Zuul handles 2B+ requests/day. Stripe uses per-merchant rate
limiting with API key authentication. Uber runs 4000+ microservices with Envoy
sidecars for mTLS. Shopify canaries every deploy, reducing canary to 1% on Black
Friday. Cloudflare processes 45M+ requests/sec with edge rate limiting.

The system handles the full lifecycle: ingress routing, security, resilience,
observability, and safe deployments -- all at the infrastructure layer."
```

---

## Appendix A: Design Patterns Summary

```
Pattern                    Class                         Why This Pattern
─────────────────────────  ──────────────────────────── ──────────────────────────────────────
Strategy (x4)              RoutingStrategy               Swap routing algorithm at runtime
                           LoadBalancingStrategy          Swap LB algorithm at runtime
                           AuthStrategy                   Swap auth mechanism at runtime
                           TrafficStrategy                Swap traffic shaping at runtime

Builder (x3)               HttpRequest.Builder            6+ fields, required + optional
                           Route.Builder                  10 fields with defaults
                           ServiceMeshConfig.Builder      5 fields with sensible defaults

Factory                    AppConfig                      Composition root, lazy initialization,
                                                          strategy setters clear dependents

Repository (x2)            RouteRepository                Abstract data access for routes
                           ServiceInstanceRepository      Abstract data access for instances

Facade                     GatewayService                 Single entry point for 10-step pipeline,
                                                          hides 6 sub-service interactions

State                      CircuitBreakerState            Three-state machine with distinct behavior
                                                          per state (CLOSED/OPEN/HALF_OPEN)

Chain of Responsibility    Gateway Pipeline               7 stages, each can short-circuit with
                                                          error response (404/401/403/429/503)

Proxy                      ServiceMeshService             Sidecar proxy for service-to-service,
                                                          transparently adds mTLS/CB/LB

Singleton                  AppConfig                      Lazy initialization, cached instances,
                                                          single wiring graph
```

---

## Appendix B: Comparison with Production Systems

```
Feature              This Design          Kong             Envoy/Istio        AWS API Gateway
──────────────────── ──────────────────── ──────────────── ────────────────── ──────────────────
Routing              Glob patterns        Radix tree       Route table (xDS)  Resource policies
Authentication       JWT + API key        Plugins (20+)    AuthZ policy       IAM/Cognito/Lambda
Rate Limiting        Token bucket         Redis plugin     Local + global     Throttling settings
Circuit Breaker      State machine        Plugin           Outlier detection  N/A (use App Mesh)
Load Balancing       RR/Weighted/Hash     RR/Hash/Least    RR/Random/Hash     ALB algorithms
Service Discovery    In-memory registry   DNS/Consul       EDS (xDS)          Cloud Map
mTLS                 TlsEngine            Kong Mesh        Citadel/SDS        App Mesh mTLS
Traffic Shaping      Canary/Header        Canary plugin    VirtualService     CodeDeploy
Config               AppConfig            Kong Admin API   Pilot (xDS push)   CloudFormation
Observability        TraceId headers      Plugin system    Envoy access logs  CloudWatch/X-Ray
```

---

## Appendix C: Performance Benchmarks (Industry Reference)

```
System                 Throughput           Latency (P99)      Notes
─────────────────────  ──────────────────── ──────────────     ──────────────────────────
Kong (OSS)             50K-100K RPS         <2ms               Nginx + Lua, plugin overhead
Kong (Enterprise)      100K-200K RPS        <1ms               Worker processes
Envoy                  50K-80K RPS/worker   <1ms               C++, async I/O, per-worker
Netflix Zuul 2         20K-50K RPS          <5ms               Netty, async, filter chain
AWS API Gateway        10K RPS default      <10ms              Managed, scales to 100K+
Cloudflare Workers     Millions RPS         <1ms               Edge, V8 isolates
Spring Cloud Gateway   10K-30K RPS          <10ms              Reactor/Netty
Traefik                30K-50K RPS          <2ms               Go, dynamic config
HAProxy                100K-1M RPS          <0.5ms             C, L4/L7, minimal overhead

Note: these are per-instance numbers. Horizontal scaling multiplies.
```
