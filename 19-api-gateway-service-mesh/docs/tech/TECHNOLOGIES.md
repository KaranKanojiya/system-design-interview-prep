# Technologies -- API Gateway & Service Mesh

> Production technology stack for an API gateway + service mesh platform. For
> each tech: why it fits, key operations, data model, complexity analysis, and
> how our Java simulation maps to the production version.
>
> **Domain-specific:** API gateways and service meshes sit at the nexus of
> networking, security, and reliability. This doc covers proxies (Kong, Envoy,
> Nginx, HAProxy), mesh control planes (Istio, Linkerd), service discovery
> (Consul, etcd), rate limiting (Redis token bucket), authentication
> (JWT/OAuth2/OIDC), protocols (gRPC, WebSocket), TLS/mTLS, consistent hashing,
> and canary deployments.

---

## Technology Map

```
                            Internet
                               |
                    +--------------------+
                    |   CDN / Edge Cache  |   CloudFront / Fastly / Akamai
                    |   (edge routing,    |   TLS termination at PoP
                    |    WAF, DDoS)       |
                    +--------------------+
                               |
                    +--------------------+
                    |   API Gateway       |   Kong / AWS API GW / Nginx
                    |   (routing, auth,   |   Rate limiting, JWT validation,
                    |    rate limit,      |   request transformation,
                    |    canary splits)   |   response caching
                    +--------------------+
                               |
          +--------------------+--------------------+
          |                    |                    |
   +------------+       +------------+       +------------+
   | Sidecar    |       | Sidecar    |       | Sidecar    |
   | Proxy      |       | Proxy      |       | Proxy      |  Envoy / Linkerd-proxy
   | (Envoy)    |       | (Envoy)    |       | (Envoy)    |  mTLS, circuit breaker,
   +-----+------+       +-----+------+       +-----+------+  load balancing, retries
         |                     |                     |
   +-----+------+       +-----+------+       +-----+------+
   | User       |       | Order      |       | Payment    |
   | Service    |       | Service    |       | Service    |
   +------------+       +------------+       +------------+
                               |
          +--------------------+--------------------+
          |                    |                    |
   +------------+       +------------+       +------------+
   | Redis      |       | Consul     |       | Jaeger     |
   | Rate Limit |       | / etcd     |       | Tracing    |
   | State      |       | Discovery  |       |            |
   +------------+       +------------+       +------------+
```

---

## 1. Kong -- API Gateway

**THE most popular open-source API gateway.** Used by companies like Stripe,
Salesforce, and Nasdaq. Built on Nginx + OpenResty (LuaJIT), Kong provides
routing, authentication, rate limiting, and plugin-based extensibility.

### Why Kong

| Criterion | Kong Fit |
|-----------|----------|
| Plugin ecosystem | 100+ plugins: JWT, OAuth2, rate limiting, CORS, logging, Prometheus |
| Performance | Nginx core: 100K+ RPS per node, sub-ms added latency |
| Declarative config | YAML/JSON config for CI/CD pipelines (decK) |
| Multi-protocol | HTTP, gRPC, WebSocket, TCP/TLS passthrough |
| Kubernetes native | Kong Ingress Controller (KIC) integrates with k8s Ingress/Gateway API |
| DB-less mode | Run without PostgreSQL -- config stored in-memory from declarative file |

### Key Operations

| Operation | Kong Config | Latency | Our Java Mapping |
|-----------|-------------|---------|------------------|
| Route matching | `routes[].paths: ["/api/users/**"]` | O(N) routes, typically < 100 | `RequestRouter.match()` |
| JWT validation | `plugins[].name: jwt` | O(1) signature verify | `JwtAuthStrategy.authenticate()` |
| Rate limiting | `plugins[].name: rate-limiting` (Redis-backed) | O(1) Redis INCR | `RateLimiterEngine.tryConsume()` |
| Load balancing | `upstreams[].targets[]` with weights | O(1) round-robin / O(log N) consistent hash | `LoadBalancerService.selectInstance()` |
| Request transform | `plugins[].name: request-transformer` | O(1) header/body mutation | N/A (simulated in forwarding) |
| Circuit breaker | `upstreams[].healthchecks.active/passive` | O(1) state check | `CircuitBreakerEngine.allowRequest()` |

### Kong Architecture

```
Client Request
    |
    v
+-------------------------------------------+
|              Kong Gateway Node             |
|                                            |
|  1. Nginx event loop receives connection   |
|  2. LuaJIT runs plugin chain (access phase)|
|     a. JWT plugin: verify token            |
|     b. ACL plugin: check consumer group    |
|     c. Rate-limiting plugin: Redis INCR    |
|     d. Correlation-id: add trace header    |
|  3. Route matching: path + host + headers  |
|  4. Upstream selection: target ring         |
|  5. Nginx proxies to upstream              |
|  6. LuaJIT runs plugin chain (response)    |
|     a. Response-transformer: add headers   |
|     b. Prometheus: record latency metric   |
|  7. Return response to client              |
+-------------------------------------------+
          |                |
          v                v
    +----------+     +----------+
    |  Redis   |     | Postgres |
    |  (rate   |     | (config  |
    |  limits) |     |  store)  |
    +----------+     +----------+
```

### Kong Declarative Configuration

```yaml
# kong.yml -- declarative configuration (DB-less mode)
_format_version: "3.0"

services:
  - name: user-service
    url: http://user-service.default.svc:8080
    routes:
      - name: user-api-route
        paths:
          - /api/users
        methods:
          - GET
          - POST
          - PUT
          - DELETE
        strip_path: false
        plugins:
          - name: jwt
            config:
              claims_to_verify:
                - exp
          - name: rate-limiting
            config:
              second: 100
              policy: redis
              redis_host: redis.default.svc
              redis_port: 6379
          - name: correlation-id
            config:
              header_name: X-Trace-Id
              generator: uuid

  - name: order-service
    url: http://order-service.default.svc:8080
    routes:
      - name: order-api-route
        paths:
          - /api/orders
        plugins:
          - name: jwt
          - name: rate-limiting
            config:
              second: 50
              policy: redis
              redis_host: redis.default.svc

upstreams:
  - name: user-service
    algorithm: round-robin
    healthchecks:
      active:
        healthy:
          interval: 5
          successes: 3
        unhealthy:
          interval: 5
          http_failures: 3
      passive:
        unhealthy:
          http_failures: 5
    targets:
      - target: user-service-1:8080
        weight: 100
      - target: user-service-2:8080
        weight: 100
      - target: user-service-3:8080
        weight: 100
```

### Simulation-to-Production Mapping

| Our Simulation | Production (Kong) |
|----------------|-------------------|
| `RequestRouter` (ArrayList sorted by priority) | Nginx radix tree with prefix matching |
| `JwtAuthStrategy` (in-memory HMAC) | JWT plugin with JWKS endpoint + RSA-256/ES256 |
| `RateLimiterEngine` (ConcurrentHashMap) | Redis-backed sliding window or token bucket |
| `LoadBalancerService` (Strategy pattern) | Nginx upstream module with health checks |
| `CircuitBreakerEngine` (ConcurrentHashMap) | Active/passive health checks with threshold |
| `Route` model (Builder pattern) | Declarative YAML parsed into Nginx config |

### Complexity Analysis

| Operation | Time | Space | Notes |
|-----------|------|-------|-------|
| Route matching (radix tree) | O(L) where L = path length | O(R * L) R = routes | Production uses radix/trie, not linear scan |
| JWT validation | O(1) | O(K) K = cached keys | Signature verify is constant-time crypto |
| Rate limit check (Redis) | O(1) | O(N) N = unique keys | Single Redis INCR + EXPIRE |
| Health check (active) | O(T) T = targets per upstream | O(T) per upstream | Runs in background timer |
| Plugin chain execution | O(P) P = plugins on route | O(P) | Plugins run sequentially in phases |

---

## 2. Envoy Proxy -- Data Plane for Service Mesh

**THE sidecar proxy for modern service meshes.** Created at Lyft, now a CNCF
graduated project. Envoy handles L4/L7 proxying, service discovery, load
balancing, circuit breaking, retries, and observability -- all as a sidecar
next to each service pod.

### Why Envoy

| Criterion | Envoy Fit |
|-----------|-----------|
| L7 aware | Full HTTP/2 and gRPC support, header-based routing |
| Hot restart | Config reloads with zero dropped connections |
| xDS API | Dynamic config via gRPC (RDS, CDS, EDS, LDS, SDS) |
| Observability | Built-in stats, distributed tracing (Zipkin/Jaeger), access logging |
| Extension | WASM filters for custom logic (Rust, C++, Go via proxy-wasm) |
| Battle-tested | Powers Istio, AWS App Mesh, GCP Traffic Director |

### Envoy Architecture

```
                 +------------------------------------+
                 |          Envoy Sidecar             |
                 |                                    |
  Inbound  ----->| Listener (port 15006)              |
  traffic        |   |                                |
                 |   v                                |
                 | Filter Chain:                      |
                 |   1. TLS Inspector (SNI routing)   |
                 |   2. HTTP Connection Manager       |
                 |      a. Router filter              |
                 |      b. Rate limit filter          |
                 |      c. JWT authn filter           |
                 |      d. RBAC authz filter          |
                 |      e. WASM custom filter         |
                 |   3. Route matching (RDS config)   |
                 |      |                             |
                 |      v                             |
                 | Cluster Manager:                   |
                 |   - Selects upstream cluster       |
                 |   - Load balancing (EDS endpoints) |
                 |   - Circuit breaker check          |
                 |   - Retry policy                   |
                 |   - Timeout enforcement            |
                 |      |                             |
                 |      v                             |
  Outbound ----->| Connection Pool                    |
  to upstream    |   - HTTP/2 multiplexing            |
                 |   - TLS handshake (mTLS via SDS)   |
                 |                                    |
                 +------------------------------------+
                          |            |
                          v            v
                    +---------+  +-----------+
                    | Metrics |  |  Tracing  |
                    | (Stats) |  | (Zipkin/  |
                    | Sink    |  |  Jaeger)  |
                    +---------+  +-----------+
```

### Envoy xDS APIs -- Dynamic Configuration

```
Control Plane (Istio Pilot / custom)
    |
    |  gRPC streaming
    |
    v
Envoy Sidecar
    |
    +-- LDS (Listener Discovery Service)
    |     - Which ports to listen on
    |     - Filter chains per listener
    |
    +-- RDS (Route Discovery Service)
    |     - Route tables: path -> cluster mapping
    |     - Header matching, prefix/exact/regex
    |     - Traffic splitting (canary weights)
    |
    +-- CDS (Cluster Discovery Service)
    |     - Upstream cluster definitions
    |     - Circuit breaker settings per cluster
    |     - Outlier detection thresholds
    |
    +-- EDS (Endpoint Discovery Service)
    |     - IP:port of healthy endpoints per cluster
    |     - Health status (HEALTHY, DEGRADED, UNHEALTHY)
    |     - Zone-aware routing weights
    |
    +-- SDS (Secret Discovery Service)
          - TLS certificates for mTLS
          - Certificate rotation without restart
          - SPIFFE identity documents
```

### Envoy Configuration (Static Bootstrap Example)

```yaml
# envoy.yaml -- static bootstrap configuration
admin:
  address:
    socket_address: { address: 0.0.0.0, port_value: 9901 }

static_resources:
  listeners:
    - name: inbound
      address:
        socket_address: { address: 0.0.0.0, port_value: 15006 }
      filter_chains:
        - transport_socket:
            name: envoy.transport_sockets.tls
            typed_config:
              "@type": type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext
              require_client_certificate: true
              common_tls_context:
                tls_certificates:
                  - certificate_chain: { filename: /certs/cert.pem }
                    private_key: { filename: /certs/key.pem }
                validation_context:
                  trusted_ca: { filename: /certs/ca.pem }
          filters:
            - name: envoy.filters.network.http_connection_manager
              typed_config:
                "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager
                stat_prefix: inbound_http
                route_config:
                  name: local_route
                  virtual_hosts:
                    - name: user_service
                      domains: ["*"]
                      routes:
                        - match: { prefix: "/api/users" }
                          route:
                            cluster: user_service_cluster
                            retry_policy:
                              retry_on: "5xx,connect-failure,retriable-4xx"
                              num_retries: 3
                            timeout: 5s
                http_filters:
                  - name: envoy.filters.http.jwt_authn
                    typed_config:
                      "@type": type.googleapis.com/envoy.extensions.filters.http.jwt_authn.v3.JwtAuthentication
                      providers:
                        auth0:
                          issuer: "https://my-tenant.auth0.com/"
                          remote_jwks:
                            http_uri:
                              uri: "https://my-tenant.auth0.com/.well-known/jwks.json"
                              cluster: auth0_jwks
                              timeout: 5s
                            cache_duration: 600s
                  - name: envoy.filters.http.router
                    typed_config:
                      "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router

  clusters:
    - name: user_service_cluster
      type: EDS
      eds_cluster_config:
        eds_config:
          api_config_source:
            api_type: GRPC
            grpc_services:
              - envoy_grpc:
                  cluster_name: xds_cluster
      circuit_breakers:
        thresholds:
          - priority: DEFAULT
            max_connections: 1024
            max_pending_requests: 1024
            max_requests: 1024
            max_retries: 3
      outlier_detection:
        consecutive_5xx: 5
        interval: 10s
        base_ejection_time: 30s
        max_ejection_percent: 50

    - name: xds_cluster
      type: STRICT_DNS
      load_assignment:
        cluster_name: xds_cluster
        endpoints:
          - lb_endpoints:
              - endpoint:
                  address:
                    socket_address: { address: istiod.istio-system.svc, port_value: 15010 }
```

### Simulation-to-Production Mapping

| Our Simulation | Production (Envoy) |
|----------------|-------------------|
| `ServiceMeshService.proxyRequest()` | Envoy sidecar HTTP filter chain |
| `TlsEngine.validateConnection()` | mTLS via SDS + SPIFFE identity |
| `CircuitBreakerEngine` (state machine) | Envoy circuit breaker + outlier detection |
| `LoadBalancerService` (Strategy pattern) | Envoy cluster manager (ring hash, round-robin, MAGLEV) |
| `ServiceRegistry` (ConcurrentHashMap) | EDS endpoint list (dynamic via xDS) |
| `ConsistentHashLoadBalancer` (TreeMap ring) | Envoy ring hash / MAGLEV hash |

### Complexity Analysis

| Operation | Time | Space | Notes |
|-----------|------|-------|-------|
| Route matching (trie) | O(L) path length | O(R * L) | Prefix, exact, regex matching |
| Circuit breaker check | O(1) | O(C) clusters | Per-cluster atomic counters |
| Outlier detection | O(1) amortized | O(E) endpoints | Sliding window of success rates |
| Load balancing (ring hash) | O(log V) | O(E * V) V = vnodes | TreeMap ceiling lookup |
| mTLS handshake | O(1) per connection | O(S) certs | TLS 1.3 handshake, cert cached |
| xDS config push | O(C + R + E) | O(total config) | Incremental delta xDS preferred |

---

## 3. Istio -- Service Mesh Control Plane

**THE most widely adopted service mesh control plane.** Istio manages Envoy
sidecars through its control plane (istiod), providing traffic management,
security (mTLS), and observability across the entire mesh.

### Why Istio

| Criterion | Istio Fit |
|-----------|-----------|
| Automatic mTLS | Zero-code mutual TLS between all services |
| Traffic policies | VirtualService + DestinationRule for routing/retries/timeouts |
| Canary deployments | Weighted traffic splitting at the mesh level |
| Authorization | Fine-grained RBAC (AuthorizationPolicy) |
| Observability | Automatic metrics, traces, and access logs from every sidecar |
| Multi-cluster | Mesh federation across multiple Kubernetes clusters |

### Istio Architecture

```
+---------------------------------------------------------------+
|                     Istio Control Plane                       |
|                                                               |
|  +------------------+  +---------------+  +----------------+  |
|  |  Pilot (istiod)  |  |  Citadel      |  |  Galley        |  |
|  |  - xDS server    |  |  - CA for     |  |  - Config      |  |
|  |  - service       |  |    mTLS certs |  |    validation  |  |
|  |    discovery     |  |  - SPIFFE ID  |  |  - k8s CRD     |  |
|  |  - config push   |  |  - cert       |  |    adapter     |  |
|  |  - health agg    |  |    rotation   |  |                |  |
|  +--------+---------+  +-------+-------+  +--------+-------+  |
|           |                    |                    |          |
|           +--------------------+--------------------+          |
|                        | xDS (gRPC) |                         |
+------------------------+-----+------+-------------------------+
                               |
          +--------------------+--------------------+
          |                    |                    |
   +------+-------+    +------+-------+    +------+-------+
   | Pod A        |    | Pod B        |    | Pod C        |
   |  +---------+ |    |  +---------+ |    |  +---------+ |
   |  | Envoy   | |    |  | Envoy   | |    |  | Envoy   | |
   |  | Sidecar | |    |  | Sidecar | |    |  | Sidecar | |
   |  +----+----+ |    |  +----+----+ |    |  +----+----+ |
   |       |      |    |       |      |    |       |      |
   |  +----+----+ |    |  +----+----+ |    |  +----+----+ |
   |  | User    | |    |  | Order   | |    |  | Payment | |
   |  | Service | |    |  | Service | |    |  | Service | |
   |  +---------+ |    |  +---------+ |    |  +---------+ |
   +--------------+    +--------------+    +--------------+
```

### Istio Traffic Management CRDs

```yaml
# VirtualService -- canary traffic splitting (maps to our CanaryTrafficStrategy)
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: user-service
spec:
  hosts:
    - user-service
  http:
    - match:
        - headers:
            x-canary:
              exact: "true"
      route:
        - destination:
            host: user-service
            subset: canary
          weight: 100
    - route:
        - destination:
            host: user-service
            subset: stable
          weight: 90
        - destination:
            host: user-service
            subset: canary
          weight: 10
      retries:
        attempts: 3
        perTryTimeout: 2s
        retryOn: 5xx,connect-failure
      timeout: 10s

---
# DestinationRule -- circuit breaker + load balancing (maps to our CircuitBreakerEngine + LoadBalancerService)
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: user-service
spec:
  host: user-service
  trafficPolicy:
    connectionPool:
      tcp:
        maxConnections: 100
      http:
        h2UpgradePolicy: DEFAULT
        http1MaxPendingRequests: 100
        http2MaxRequests: 1000
    outlierDetection:
      consecutive5xxErrors: 5
      interval: 10s
      baseEjectionTime: 30s
      maxEjectionPercent: 50
    loadBalancer:
      consistentHash:
        httpHeaderName: x-user-id
  subsets:
    - name: stable
      labels:
        version: v1
    - name: canary
      labels:
        version: v2

---
# AuthorizationPolicy -- RBAC (maps to our AuthService.authorize())
apiVersion: security.istio.io/v1beta1
kind: AuthorizationPolicy
metadata:
  name: user-service-policy
spec:
  selector:
    matchLabels:
      app: user-service
  rules:
    - from:
        - source:
            principals: ["cluster.local/ns/default/sa/order-service"]
      to:
        - operation:
            methods: ["GET"]
            paths: ["/api/users/*"]
    - from:
        - source:
            principals: ["cluster.local/ns/default/sa/admin-service"]
      to:
        - operation:
            methods: ["GET", "POST", "PUT", "DELETE"]
            paths: ["/api/users/*"]

---
# PeerAuthentication -- mTLS mode (maps to our TlsEngine)
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: default
  namespace: istio-system
spec:
  mtls:
    mode: STRICT
```

### Simulation-to-Production Mapping

| Our Simulation | Production (Istio) |
|----------------|-------------------|
| `ServiceMeshService` | Envoy sidecar managed by istiod |
| `TlsEngine` (trusted set) | Citadel CA + SPIFFE identity + auto-rotated certs |
| `CanaryTrafficStrategy` (weighted random) | VirtualService weight-based traffic splitting |
| `HeaderBasedTrafficStrategy` | VirtualService header match rules |
| `CircuitBreakerEngine` (threshold + timeout) | DestinationRule outlierDetection |
| `AuthService.authorize()` (role check) | AuthorizationPolicy (RBAC) |

---

## 4. Linkerd -- Lightweight Service Mesh

**The original service mesh.** Linkerd is a CNCF graduated project focused on
simplicity, minimal resource overhead, and operational ease. It uses its own
Rust-based micro-proxy (linkerd2-proxy) instead of Envoy.

### Why Linkerd

| Criterion | Linkerd Fit |
|-----------|-------------|
| Simplicity | 2-command install: `linkerd install \| kubectl apply` |
| Resource efficiency | Rust proxy: 10 MB RAM per pod vs 50 MB+ for Envoy |
| Automatic mTLS | Zero-config mutual TLS with identity from k8s ServiceAccounts |
| Minimal config | Works well with defaults -- fewer knobs = fewer foot-guns |
| Multi-cluster | Cross-cluster service mirroring with gateway |
| Latency | p99 < 1ms added latency per hop |

### Linkerd vs Istio Comparison

| Feature | Linkerd | Istio |
|---------|---------|-------|
| Proxy | linkerd2-proxy (Rust) | Envoy (C++) |
| Memory per sidecar | ~10 MB | ~50-100 MB |
| Install complexity | `linkerd install` (1 CRD set) | Helm chart with 20+ CRDs |
| mTLS | Automatic, always-on | Configurable (STRICT/PERMISSIVE) |
| Traffic splitting | HTTPRoute + TrafficSplit CRD | VirtualService + DestinationRule |
| Circuit breaking | Automatic (no config needed) | DestinationRule outlierDetection |
| WASM extensibility | Not supported | Supported |
| Multi-protocol | HTTP/1.1, HTTP/2, gRPC, TCP | HTTP/1.1, HTTP/2, gRPC, TCP, Mongo, MySQL |
| Complexity | Low | High |

### Linkerd Traffic Split (Canary)

```yaml
# TrafficSplit -- maps to our CanaryTrafficStrategy
apiVersion: split.smi-spec.io/v1alpha4
kind: TrafficSplit
metadata:
  name: user-service
spec:
  service: user-service
  backends:
    - service: user-service-stable
      weight: 900
    - service: user-service-canary
      weight: 100
```

### Linkerd ServiceProfile (Retries + Timeouts)

```yaml
# ServiceProfile -- maps to our Route.retryCount and Route.timeoutMs
apiVersion: linkerd.io/v1alpha2
kind: ServiceProfile
metadata:
  name: user-service.default.svc.cluster.local
spec:
  routes:
    - name: GET /api/users/{id}
      condition:
        method: GET
        pathRegex: /api/users/[^/]+
      timeout: 5s
      isRetryable: true
    - name: POST /api/users
      condition:
        method: POST
        pathRegex: /api/users
      timeout: 10s
      isRetryable: false
```

---

## 5. AWS API Gateway -- Managed API Gateway

**THE managed API gateway for AWS-native architectures.** Supports REST APIs
(API Gateway v1), HTTP APIs (API Gateway v2, cheaper, faster), and WebSocket
APIs. Integrated with Lambda, Cognito, WAF, and CloudWatch.

### Why AWS API Gateway

| Criterion | AWS API GW Fit |
|-----------|----------------|
| Serverless | Direct Lambda integration, no server management |
| Authentication | Cognito user pool authorizer, Lambda authorizer, IAM auth |
| Rate limiting | Usage plans with throttling (token bucket) and quota |
| Caching | Built-in response caching (ElastiCache under the hood) |
| WAF integration | AWS WAF rules for IP filtering, SQL injection, XSS |
| Cost model | Pay per request ($3.50/million for REST, $1.00/million for HTTP API) |

### AWS API Gateway Architecture

```
Client
  |
  v
+------------------------------------------+
|           AWS API Gateway                |
|                                          |
|  1. Custom domain (Route 53 + ACM cert)  |
|  2. WAF rules (IP, rate, SQL injection)  |
|  3. API key validation (usage plan)      |
|  4. Cognito authorizer (JWT validation)  |
|  5. Request validation (JSON schema)     |
|  6. Request transformation (VTL)         |
|  7. Integration:                         |
|     - Lambda (most common)               |
|     - HTTP proxy (ALB, NLB, any URL)     |
|     - AWS service proxy (DynamoDB, SQS)  |
|  8. Response transformation              |
|  9. Caching (optional, 0.5-237 GB)       |
| 10. CloudWatch metrics + access logs     |
+------------------------------------------+
     |              |              |
     v              v              v
+----------+  +----------+  +----------+
| Lambda   |  | ALB/ECS  |  | DynamoDB |
| Function |  | Service  |  | Direct   |
+----------+  +----------+  +----------+
```

### Usage Plan (Rate Limiting)

```json
{
  "name": "premium-tier",
  "description": "Premium API consumers",
  "throttle": {
    "burstLimit": 500,
    "rateLimit": 100
  },
  "quota": {
    "limit": 100000,
    "period": "MONTH"
  },
  "apiStages": [
    {
      "apiId": "abc123",
      "stage": "prod",
      "throttle": {
        "/api/users/GET": {
          "burstLimit": 200,
          "rateLimit": 50
        }
      }
    }
  ]
}
```

### Simulation-to-Production Mapping

| Our Simulation | Production (AWS API GW) |
|----------------|-------------------------|
| `Route` model | API resource + method + integration |
| `JwtAuthStrategy` | Cognito user pool authorizer |
| `ApiKeyAuthStrategy` | API key + usage plan |
| `RateLimiterEngine` (token bucket) | Usage plan throttle (token bucket) |
| `GatewayService.handleRequest()` | API Gateway execution pipeline |
| `RequestRouter.match()` | Resource tree path matching |

---

## 6. Nginx -- Reverse Proxy and Load Balancer

**THE high-performance reverse proxy.** Nginx powers ~34% of the web (2024).
Used as the foundation of Kong and as a standalone API gateway, load balancer,
and TLS terminator.

### Why Nginx

| Criterion | Nginx Fit |
|-----------|-----------|
| Performance | Event-driven, non-blocking I/O: 50K+ concurrent connections per worker |
| Mature | 20+ years in production, battle-tested at every scale |
| TLS termination | OpenSSL integration, TLS 1.3, OCSP stapling |
| Upstream health | Active and passive health checks (Nginx Plus) |
| Config hot-reload | `nginx -s reload` -- zero-downtime configuration updates |
| Module system | C modules, Lua (OpenResty), njs (JavaScript) |

### Nginx Configuration (API Gateway Pattern)

```nginx
# nginx.conf -- API gateway pattern
# Maps to our GatewayService pipeline

http {
    # Rate limiting zone -- maps to our RateLimiterEngine
    limit_req_zone $binary_remote_addr zone=api_limit:10m rate=100r/s;
    limit_req_zone $http_x_api_key zone=apikey_limit:10m rate=50r/s;

    # JWT validation (requires njs module) -- maps to our JwtAuthStrategy
    js_import auth from /etc/nginx/auth.js;

    # Upstream backends -- maps to our ServiceRegistry
    upstream user_service {
        zone user_service 64k;
        # Consistent hash -- maps to our ConsistentHashLoadBalancer
        hash $request_uri consistent;

        server user-1.internal:8080 weight=5;
        server user-2.internal:8080 weight=5;
        server user-3.internal:8080 weight=3;

        # Health checks (Nginx Plus) -- maps to our health check service
        health_check interval=5s fails=3 passes=2;
    }

    upstream order_service {
        zone order_service 64k;
        least_conn;  # Least-connections -- maps to our LeastConnectionsLoadBalancer

        server order-1.internal:8080;
        server order-2.internal:8080;
    }

    server {
        listen 443 ssl http2;
        server_name api.example.com;

        # TLS 1.3 -- maps to our TlsEngine
        ssl_certificate /etc/nginx/certs/server.crt;
        ssl_certificate_key /etc/nginx/certs/server.key;
        ssl_protocols TLSv1.3;
        ssl_prefer_server_ciphers on;

        # API routing -- maps to our RequestRouter
        location /api/users/ {
            limit_req zone=api_limit burst=20 nodelay;

            # JWT validation
            js_content auth.validateJwt;

            proxy_pass http://user_service;
            proxy_set_header X-Trace-Id $request_id;
            proxy_set_header X-Real-IP $remote_addr;

            # Timeouts -- maps to our Route.timeoutMs
            proxy_connect_timeout 5s;
            proxy_read_timeout 10s;

            # Circuit breaker (via max_fails) -- maps to our CircuitBreakerEngine
            proxy_next_upstream error timeout http_502 http_503;
            proxy_next_upstream_tries 3;
        }

        location /api/orders/ {
            limit_req zone=apikey_limit burst=10 nodelay;
            proxy_pass http://order_service;
        }

        # Health check endpoint
        location /health {
            return 200 '{"status":"healthy"}';
            add_header Content-Type application/json;
        }
    }
}
```

### Nginx vs Kong vs Envoy

| Feature | Nginx | Kong | Envoy |
|---------|-------|------|-------|
| Primary role | Reverse proxy, LB | API gateway | Service mesh data plane |
| Config format | Static file (nginx.conf) | Declarative YAML + Admin API | xDS (dynamic gRPC) |
| Auth plugins | njs module (manual) | 10+ auth plugins built-in | JWT filter, ext_authz |
| Rate limiting | `limit_req` module | Redis-backed plugin | Rate limit service (external) |
| Service discovery | DNS / static config | DNS / Consul / k8s | xDS EDS (dynamic) |
| Circuit breaking | `max_fails` + `fail_timeout` | Active/passive health checks | Outlier detection + thresholds |
| mTLS | Manual cert config | Plugin | SDS automatic rotation |
| gRPC | Supported (since 1.13) | Supported | Native, first-class |

---

## 7. HAProxy -- High-Performance TCP/HTTP Load Balancer

**THE load balancer for extreme throughput.** HAProxy powers high-traffic
sites (GitHub, Stack Overflow, Reddit). Known for predictable sub-millisecond
latency at millions of concurrent connections.

### Why HAProxy

| Criterion | HAProxy Fit |
|-----------|-------------|
| Throughput | 2M+ HTTP requests/second on a single instance |
| Connection handling | 500K+ concurrent connections |
| Health checking | Rich health check DSL (HTTP, TCP, SSL, scripted) |
| Stick tables | In-memory tracking tables for rate limiting, session affinity |
| Zero downtime | Seamless reloads with socket passing |
| ACL system | Powerful ACL-based routing (header, path, cookie, src IP) |

### HAProxy Configuration

```
# haproxy.cfg -- API gateway pattern
# Maps to our GatewayService pipeline

global
    maxconn 500000
    ssl-default-bind-ciphers TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256
    ssl-default-bind-options ssl-min-ver TLSv1.3

defaults
    mode http
    timeout connect 5s
    timeout client 30s
    timeout server 30s
    option httplog
    option forwardfor

# Rate limiting stick table -- maps to our RateLimiterEngine
backend rate_limit_tracker
    stick-table type ip size 100k expire 60s store http_req_rate(10s)

# Frontend -- maps to our GatewayService
frontend api_gateway
    bind *:443 ssl crt /etc/haproxy/certs/api.pem alpn h2,http/1.1

    # Add trace ID -- maps to our traceId generation
    http-request set-header X-Trace-Id %[uuid()]

    # Rate limiting check
    http-request track-sc0 src table rate_limit_tracker
    http-request deny deny_status 429 if { sc_http_req_rate(0) gt 100 }

    # JWT validation via Lua script
    http-request lua.validate_jwt

    # Routing ACLs -- maps to our RequestRouter
    acl is_user_api path_beg /api/users
    acl is_order_api path_beg /api/orders
    acl is_payment_api path_beg /api/payments

    use_backend user_service if is_user_api
    use_backend order_service if is_order_api
    use_backend payment_service if is_payment_api

    default_backend fallback_404

# Backend with health checks -- maps to our ServiceRegistry + LoadBalancerService
backend user_service
    balance roundrobin
    option httpchk GET /health
    http-check expect status 200

    server user-1 10.0.1.10:8080 check inter 5s fall 3 rise 2 weight 100
    server user-2 10.0.1.11:8080 check inter 5s fall 3 rise 2 weight 100
    server user-3 10.0.1.12:8080 check inter 5s fall 3 rise 2 weight 50

    # Circuit breaker -- maps to our CircuitBreakerEngine
    # fall 3 = 3 consecutive failures -> mark DOWN (OPEN)
    # rise 2 = 2 consecutive successes -> mark UP (CLOSED)
    # inter 5s = check interval

backend order_service
    balance leastconn
    option httpchk GET /health
    server order-1 10.0.2.10:8080 check
    server order-2 10.0.2.11:8080 check

backend fallback_404
    http-request deny deny_status 404
```

---

## 8. Consul -- Service Discovery and Configuration

**THE service discovery platform for dynamic infrastructure.** HashiCorp Consul
provides service registration, health checking, key-value store, and Connect
(built-in service mesh with mTLS).

### Why Consul

| Criterion | Consul Fit |
|-----------|-----------|
| Multi-datacenter | Built-in WAN gossip for cross-DC service discovery |
| Health checks | HTTP, TCP, gRPC, script, and TTL-based health checks |
| Key-value store | Distributed KV for configuration and feature flags |
| Service mesh (Connect) | Built-in mTLS + intentions (authorization) |
| DNS interface | Services queryable via DNS: `user-service.service.consul` |
| Platform agnostic | VM, bare metal, Kubernetes, Nomad |

### Consul Architecture

```
+---------------------------------------------------------------+
|                    Consul Cluster (Raft)                       |
|                                                               |
|  +--------+    +--------+    +--------+                       |
|  | Server |    | Server |    | Server |  3 or 5 server nodes  |
|  | (Leader)    | (Follower)  | (Follower)  Raft consensus    |
|  +----+---+    +----+---+    +----+---+                       |
|       |             |             |                            |
|       +------+------+------+------+                           |
|              | Gossip (Serf) |                                 |
+--------------+------+--------+--------------------------------+
                      |
         +------------+------------+
         |            |            |
   +-----+----+ +----+-----+ +----+-----+
   | Consul   | | Consul   | | Consul   |
   | Agent    | | Agent    | | Agent    |  Agent on every node
   | (Client) | | (Client) | | (Client) |  Forwards to servers
   +-----+----+ +----+-----+ +----+-----+
         |            |            |
   +-----+----+ +----+-----+ +----+-----+
   | User     | | Order    | | Payment  |
   | Service  | | Service  | | Service  |
   | :8080    | | :8081    | | :8082    |
   +----------+ +----------+ +----------+
```

### Consul Service Registration

```json
{
  "service": {
    "id": "user-service-1",
    "name": "user-service",
    "tags": ["v1", "primary"],
    "address": "10.0.1.10",
    "port": 8080,
    "meta": {
      "version": "1.2.3",
      "zone": "us-east-1a"
    },
    "check": {
      "http": "http://10.0.1.10:8080/health",
      "interval": "10s",
      "timeout": "2s",
      "deregister_critical_service_after": "60s"
    },
    "weights": {
      "passing": 10,
      "warning": 1
    }
  }
}
```

### Consul DNS Service Discovery

```bash
# DNS query for healthy instances of user-service
dig @127.0.0.1 -p 8600 user-service.service.consul SRV

# Response:
# user-service.service.consul. 0 IN SRV 1 1 8080 user-1.node.dc1.consul.
# user-service.service.consul. 0 IN SRV 1 1 8080 user-2.node.dc1.consul.
# user-service.service.consul. 0 IN SRV 1 1 8080 user-3.node.dc1.consul.

# Tag-based query (only v2 instances)
dig @127.0.0.1 -p 8600 v2.user-service.service.consul SRV

# HTTP API query
curl http://localhost:8500/v1/health/service/user-service?passing=true
```

### Simulation-to-Production Mapping

| Our Simulation | Production (Consul) |
|----------------|---------------------|
| `ServiceRegistry` (ConcurrentHashMap) | Consul catalog + health checks |
| `ServiceRegistry.register()` | Consul agent service registration |
| `ServiceRegistry.getInstances()` | Consul health endpoint (passing only) |
| `ServiceRegistry.heartbeat()` | Consul TTL check `PUT /v1/agent/check/pass/:id` |
| `ServiceRegistry.evictStale()` | Consul `deregister_critical_service_after` |
| `ServiceInstance.zone` | Consul datacenter + meta tags |

---

## 9. etcd -- Distributed Key-Value Store

**THE backing store for Kubernetes.** etcd is a strongly consistent (Raft)
key-value store used for service discovery, configuration, and distributed
coordination. Kubernetes stores all cluster state in etcd.

### Why etcd

| Criterion | etcd Fit |
|-----------|----------|
| Strong consistency | Raft consensus: linearizable reads and writes |
| Watch API | Real-time notifications on key changes (push model) |
| Kubernetes native | All k8s state (Services, Endpoints, ConfigMaps) stored in etcd |
| Lease mechanism | TTL-based leases for ephemeral keys (service registrations) |
| MVCC | Multi-version concurrency control for safe concurrent access |
| Compact | Small binary, low resource usage for metadata workloads |

### etcd Service Discovery Pattern

```bash
# Register a service instance with a lease (TTL)
# 1. Create a lease (30-second TTL)
LEASE_ID=$(etcdctl lease grant 30 | awk '{print $2}')

# 2. Register instance with the lease
etcdctl put /services/user-service/instances/user-1 \
  '{"host":"10.0.1.10","port":8080,"zone":"us-east-1a","weight":100}' \
  --lease=$LEASE_ID

# 3. Keep-alive (heartbeat) -- renews the lease
etcdctl lease keep-alive $LEASE_ID &

# 4. Discover instances (list all under prefix)
etcdctl get /services/user-service/instances/ --prefix

# 5. Watch for changes (real-time push)
etcdctl watch /services/user-service/instances/ --prefix
# Output when instance added/removed:
# PUT /services/user-service/instances/user-4 {"host":"10.0.1.13"...}
# DELETE /services/user-service/instances/user-2
```

### etcd vs Consul Comparison

| Feature | etcd | Consul |
|---------|------|--------|
| Consistency | Strong (Raft) | Strong (Raft) for servers, eventual for agents |
| Service discovery | Manual (key patterns) | First-class (catalog + DNS) |
| Health checks | Lease TTL (implicit) | HTTP, TCP, gRPC, script (explicit) |
| DNS interface | None (key-value only) | Built-in DNS server |
| Multi-datacenter | Single cluster (federation via gRPC proxy) | Native WAN gossip |
| Watch mechanism | gRPC watch stream | Blocking queries (long-poll) |
| Typical use | Kubernetes backing store | Standalone service discovery |

---

## 10. Redis -- Rate Limiting and State Store

**THE in-memory data store for rate limiting, caching, and session state.**
Redis provides sub-millisecond operations, atomic counters, and Lua scripting
-- perfect for distributed rate limiting and circuit breaker state.

### Why Redis for Rate Limiting

| Criterion | Redis Fit |
|-----------|----------|
| Performance | 100K+ ops/sec per node, sub-ms latency |
| Atomic operations | INCR, EXPIRE, Lua scripts for atomic multi-step operations |
| Data structures | Strings (counters), Sorted Sets (sliding window), Hashes (state) |
| Cluster mode | Redis Cluster for horizontal scaling across rate limit partitions |
| Pub/Sub | Real-time event propagation (circuit breaker state changes) |
| Persistence | RDB/AOF for state recovery (rate limit counters survive restart) |

### Token Bucket Rate Limiting in Redis (Lua Script)

```lua
-- token_bucket.lua -- maps to our RateLimiterEngine.tryConsume()
-- KEYS[1] = rate limit key (e.g., "rl:user-service:client-123")
-- ARGV[1] = max_tokens (bucket capacity)
-- ARGV[2] = refill_rate (tokens per second)
-- ARGV[3] = current_timestamp_ms
-- ARGV[4] = tokens_to_consume (usually 1)

local key = KEYS[1]
local max_tokens = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])

-- Get current state or initialize
local state = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens = tonumber(state[1])
local last_refill = tonumber(state[2])

if tokens == nil then
    -- First request: initialize bucket to full
    tokens = max_tokens
    last_refill = now
end

-- Refill tokens based on elapsed time
local elapsed_ms = now - last_refill
local tokens_to_add = (elapsed_ms / 1000.0) * refill_rate
tokens = math.min(max_tokens, tokens + tokens_to_add)

-- Try to consume
local allowed = 0
local remaining = tokens

if tokens >= requested then
    tokens = tokens - requested
    allowed = 1
    remaining = tokens
end

-- Update state
redis.call('HMSET', key, 'tokens', tokens, 'last_refill', now)
redis.call('EXPIRE', key, 3600)  -- TTL: 1 hour cleanup

-- Return: [allowed (0/1), remaining_tokens, retry_after_ms]
local retry_after = 0
if allowed == 0 then
    retry_after = math.ceil((requested - tokens) / refill_rate * 1000)
end

return {allowed, math.floor(remaining), retry_after}
```

### Sliding Window Rate Limiting in Redis

```lua
-- sliding_window.lua -- alternative to token bucket
-- Uses sorted set: member = request_id, score = timestamp
-- KEYS[1] = rate limit key
-- ARGV[1] = window_size_ms (e.g., 60000 for 1 minute)
-- ARGV[2] = max_requests (e.g., 100)
-- ARGV[3] = current_timestamp_ms
-- ARGV[4] = request_id (unique)

local key = KEYS[1]
local window_ms = tonumber(ARGV[1])
local max_requests = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local request_id = ARGV[4]

-- Remove expired entries (outside the window)
local window_start = now - window_ms
redis.call('ZREMRANGEBYSCORE', key, '-inf', window_start)

-- Count current requests in window
local current_count = redis.call('ZCARD', key)

if current_count < max_requests then
    -- Allowed: add this request to the window
    redis.call('ZADD', key, now, request_id)
    redis.call('EXPIRE', key, math.ceil(window_ms / 1000) + 1)
    return {1, max_requests - current_count - 1, 0}
else
    -- Denied: return retry-after based on oldest entry
    local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
    local retry_after = 0
    if #oldest > 0 then
        retry_after = tonumber(oldest[2]) + window_ms - now
    end
    return {0, 0, retry_after}
end
```

### Circuit Breaker State in Redis

```lua
-- circuit_breaker.lua -- maps to our CircuitBreakerEngine
-- Distributed circuit breaker state shared across all gateway instances
-- KEYS[1] = circuit breaker key (e.g., "cb:user-service")
-- ARGV[1] = event_type ("success" or "failure")
-- ARGV[2] = failure_threshold
-- ARGV[3] = success_threshold
-- ARGV[4] = open_duration_ms
-- ARGV[5] = current_timestamp_ms

local key = KEYS[1]
local event = ARGV[1]
local fail_threshold = tonumber(ARGV[2])
local success_threshold = tonumber(ARGV[3])
local open_duration_ms = tonumber(ARGV[4])
local now = tonumber(ARGV[5])

-- Get current state
local state = redis.call('HGETALL', key)
local current_state = "CLOSED"
local failure_count = 0
local success_count = 0
local opened_at = 0

-- Parse state (HGETALL returns flat array)
for i = 1, #state, 2 do
    if state[i] == "state" then current_state = state[i+1]
    elseif state[i] == "failures" then failure_count = tonumber(state[i+1])
    elseif state[i] == "successes" then success_count = tonumber(state[i+1])
    elseif state[i] == "opened_at" then opened_at = tonumber(state[i+1])
    end
end

-- State machine
if current_state == "OPEN" then
    -- Check if cooldown has elapsed
    if (now - opened_at) >= open_duration_ms then
        current_state = "HALF_OPEN"
        success_count = 0
        failure_count = 0
    end
end

if event == "success" then
    if current_state == "HALF_OPEN" then
        success_count = success_count + 1
        if success_count >= success_threshold then
            current_state = "CLOSED"
            failure_count = 0
            success_count = 0
        end
    elseif current_state == "CLOSED" then
        failure_count = math.max(0, failure_count - 1)  -- decay on success
    end
elseif event == "failure" then
    if current_state == "HALF_OPEN" then
        current_state = "OPEN"
        opened_at = now
    elseif current_state == "CLOSED" then
        failure_count = failure_count + 1
        if failure_count >= fail_threshold then
            current_state = "OPEN"
            opened_at = now
        end
    end
end

-- Save state
redis.call('HMSET', key,
    'state', current_state,
    'failures', failure_count,
    'successes', success_count,
    'opened_at', opened_at)
redis.call('EXPIRE', key, 3600)

-- Return: state, allowed (1=yes, 0=no)
local allowed = (current_state ~= "OPEN") and 1 or 0
return {current_state, allowed, failure_count, success_count}
```

### Simulation-to-Production Mapping

| Our Simulation | Production (Redis) |
|----------------|-------------------|
| `RateLimiterEngine.TokenBucket` (in-memory) | Redis Lua script token bucket (distributed) |
| `ConcurrentHashMap` per-key buckets | Redis key per rate-limit key |
| `synchronized(bucket)` atomicity | Redis single-threaded + Lua script atomicity |
| `bucket.refill()` elapsed-time calc | Lua `now - last_refill` calculation |
| `CircuitBreakerEngine` (per-service map) | Redis Hash per service circuit breaker |

### Complexity Analysis

| Operation | Time | Space | Notes |
|-----------|------|-------|-------|
| Token bucket check (Lua) | O(1) | O(1) per key | Single HMGET + HMSET |
| Sliding window check (Lua) | O(log N + M) | O(N) per key | ZREMRANGEBYSCORE + ZADD, N = window entries |
| Circuit breaker update | O(1) | O(1) per service | Single Hash operation |
| Key expiration | O(1) amortized | O(1) | Lazy + active expiration |

---

## 11. JWT / OAuth 2.0 / OIDC -- Authentication

**THE authentication stack for API gateways.** JWT provides stateless token
verification, OAuth 2.0 provides the authorization framework, and OpenID
Connect adds identity on top. Together they secure every API request.

### Why JWT + OAuth2 + OIDC

| Criterion | Fit |
|-----------|-----|
| Stateless | JWT can be verified locally (no database lookup per request) |
| Scalable | Any gateway instance can validate without shared session state |
| Standard | RFC 7519 (JWT), RFC 6749 (OAuth2), OIDC Core 1.0 |
| Claims-based | Roles, permissions, tenantId embedded in the token |
| Key rotation | JWKS endpoint for automatic public key rotation |
| Identity | OIDC provides `id_token` with user profile claims |

### JWT Verification Flow at the Gateway

```
Client
  |
  | Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyLTEyMyJ9...
  |
  v
+----------------------------------------------------------+
|                    API Gateway JWT Flow                   |
|                                                          |
|  1. Extract token from Authorization header              |
|     token = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyLTEyMyJ9..." |
|                                                          |
|  2. Decode header (base64url, NO verification yet)       |
|     { "alg": "RS256", "kid": "key-2024-01" }            |
|                                                          |
|  3. Fetch public key from JWKS cache (or JWKS endpoint)  |
|     Cache: kid -> RSA PublicKey (TTL: 1 hour)            |
|     Miss: GET https://auth.example.com/.well-known/jwks.json |
|                                                          |
|  4. Verify signature (RS256: RSA-PKCS1-v1.5 + SHA-256)  |
|     RSA.verify(publicKey, signature, headerBase64 + "."  |
|                + payloadBase64)                           |
|                                                          |
|  5. Validate claims:                                     |
|     a. exp > now  (not expired)                          |
|     b. iss == "https://auth.example.com/" (trusted issuer)|
|     c. aud contains "https://api.example.com" (audience) |
|     d. nbf <= now  (not before)                          |
|                                                          |
|  6. Extract identity + roles:                            |
|     sub = "user-123"                                     |
|     roles = ["admin", "user"]                            |
|     tenantId = "tenant-456"                              |
|                                                          |
|  7. Set headers for upstream:                            |
|     X-User-Id: user-123                                  |
|     X-User-Roles: admin,user                             |
|     X-Tenant-Id: tenant-456                              |
+----------------------------------------------------------+
         |
         v
    Upstream Service (trusts gateway-injected headers)
```

### OAuth 2.0 Token Flows

```
FLOW 1: Authorization Code + PKCE (Web/Mobile apps)
=========================================================
User -> Browser -> Authorization Server -> code -> Backend -> token

  1. Client generates code_verifier (random 43-128 chars)
  2. Client computes code_challenge = BASE64URL(SHA256(code_verifier))
  3. Redirect to: /authorize?response_type=code&client_id=X
                  &redirect_uri=Y&code_challenge=Z
                  &code_challenge_method=S256&scope=openid+profile
  4. User authenticates + consents
  5. Auth server redirects: /callback?code=AUTH_CODE
  6. Backend exchanges code for tokens:
     POST /token
       grant_type=authorization_code
       code=AUTH_CODE
       code_verifier=ORIGINAL_VERIFIER
       client_id=X
       redirect_uri=Y
  7. Response: { access_token, id_token, refresh_token, expires_in }


FLOW 2: Client Credentials (Service-to-Service)
=========================================================
Service A -> Authorization Server -> token -> Service B

  1. Service A requests token:
     POST /token
       grant_type=client_credentials
       client_id=service-a
       client_secret=SECRET
       scope=user-service.read
  2. Response: { access_token, token_type: "Bearer", expires_in: 3600 }
  3. Service A calls Service B:
     GET /api/users/123
     Authorization: Bearer ACCESS_TOKEN
  4. Service B validates token at gateway (or locally)


FLOW 3: Resource Owner Password (Legacy -- avoid in new systems)
=========================================================
  POST /token
    grant_type=password
    username=user@example.com
    password=hunter2
    client_id=X
    scope=openid+profile
```

### JWKS (JSON Web Key Set) Endpoint

```json
// GET https://auth.example.com/.well-known/jwks.json
// Gateway caches this for 1 hour (maps to JWT validation cache)
{
  "keys": [
    {
      "kty": "RSA",
      "kid": "key-2024-01",
      "use": "sig",
      "alg": "RS256",
      "n": "0vx7agoebGcQSuuPiLJXZptN9nndrQmbXEps2aiAFbWhM78LhWx4cbbfAAtVT86zwu1RK7aPFFxuhDR1L6tSoc_BJECPebWKRXjBZCiFV4n3oknjhMstn64tZ_2W-5JsGY4Hc5n9yBXArwl93lqt7_RN5w6Cf0h4QyQ5v-65YGjQR0_FDW2QvzqY368QQMicAtaSqzs8KJZgnYb9c7d0zgdAZHzu6qMQvRL5hajrn1n91CbOpbISD08qNLyrdkt-bFTWhAI4vMQFh6WeZu0fM4lFd2NcRwr3XPksINHaQ-G_xBniIqbw0Ls1jF44-csFCur-kEgU8awapJzKnqDKgw",
      "e": "AQAB"
    },
    {
      "kty": "RSA",
      "kid": "key-2024-02",
      "use": "sig",
      "alg": "RS256",
      "n": "...",
      "e": "AQAB"
    }
  ]
}
```

### Simulation-to-Production Mapping

| Our Simulation | Production (JWT/OAuth2/OIDC) |
|----------------|------------------------------|
| `JwtAuthStrategy` (HMAC-SHA256 in-memory) | RS256/ES256 with JWKS endpoint |
| `ApiKeyAuthStrategy` (Map lookup) | API key validation + usage tracking |
| `AuthService.authenticate()` | JWT decode + signature verify + claims validation |
| `AuthService.authorize()` (role check) | RBAC from JWT claims or external policy engine (OPA) |
| `AuthResult` (principal + roles) | JWT claims: sub, roles, scope, tenantId |
| `Route.metadata("required-role")` | AuthorizationPolicy or OPA/Rego policy |

---

## 12. gRPC -- High-Performance RPC Framework

**THE inter-service communication protocol for microservices.** gRPC uses
HTTP/2, Protocol Buffers, and bidirectional streaming. API gateways must
handle gRPC-to-JSON transcoding for browser clients and native gRPC pass-through
for service-to-service calls.

### Why gRPC

| Criterion | gRPC Fit |
|-----------|---------|
| Performance | Binary Protobuf: 5-10x smaller than JSON, 10x faster serialization |
| HTTP/2 | Multiplexing, header compression, server push |
| Streaming | Unary, server-streaming, client-streaming, bidirectional |
| Code generation | .proto files generate client/server stubs in 10+ languages |
| Deadlines | Built-in deadline propagation (timeout budget) |
| Load balancing | Client-side LB via xDS (proxyless gRPC) or proxy-side (Envoy) |

### gRPC at the Gateway

```
                    Browser (JSON)         Service (gRPC)
                         |                      |
                         v                      v
                  +------+------+        +------+------+
                  | gRPC-JSON   |        | gRPC Pass   |
                  | Transcoding |        | Through     |
                  | (grpc-web   |        | (HTTP/2     |
                  |  or Envoy   |        |  proxy)     |
                  |  transcoder)|        |             |
                  +------+------+        +------+------+
                         |                      |
                         +----------+-----------+
                                    |
                              +-----+-----+
                              | API       |
                              | Gateway   |
                              | (Envoy)   |
                              +-----+-----+
                                    |
                              +-----+-----+
                              | User      |
                              | Service   |
                              | (gRPC)    |
                              +-----------+

gRPC-JSON transcoding example:
  Browser:  POST /v1/users/123  {"name": "Alice"}
  Envoy transcodes to gRPC:
    service UserService { rpc UpdateUser(UpdateUserRequest) returns (User); }
    message UpdateUserRequest { string user_id = 1; string name = 2; }
```

### Proto Definition (Gateway Routing)

```protobuf
syntax = "proto3";

package gateway.v1;

service GatewayService {
  rpc RouteRequest(GatewayRequest) returns (GatewayResponse);
  rpc StreamEvents(stream EventRequest) returns (stream EventResponse);
  rpc HealthCheck(HealthCheckRequest) returns (HealthCheckResponse);
}

message GatewayRequest {
  string path = 1;
  string method = 2;
  map<string, string> headers = 3;
  bytes body = 4;
  string client_ip = 5;
  string trace_id = 6;
}

message GatewayResponse {
  int32 status_code = 1;
  map<string, string> headers = 2;
  bytes body = 3;
  string trace_id = 4;
  int64 latency_ms = 5;
  string upstream_service = 6;
}

message HealthCheckRequest {
  string service = 1;
}

message HealthCheckResponse {
  enum ServingStatus {
    UNKNOWN = 0;
    SERVING = 1;
    NOT_SERVING = 2;
    SERVICE_UNKNOWN = 3;
  }
  ServingStatus status = 1;
}
```

---

## 13. WebSocket -- Full-Duplex Communication

**THE protocol for real-time bidirectional communication.** API gateways must
handle WebSocket upgrades, connection affinity, and timeout management
differently from standard HTTP request-response.

### WebSocket at the Gateway

```
Client (Browser)                 API Gateway                    Backend (WS Server)
      |                              |                               |
      |  GET /ws/chat HTTP/1.1       |                               |
      |  Upgrade: websocket          |                               |
      |  Connection: Upgrade         |                               |
      |  Sec-WebSocket-Key: dGhl...  |                               |
      |----------------------------->|                               |
      |                              |  Route match: /ws/chat -> chat-service  |
      |                              |  Auth: validate JWT from query param    |
      |                              |  Load balance: MUST use sticky session  |
      |                              |      (WebSocket requires affinity)      |
      |                              |------------------------------>|
      |                              |                               |
      |  HTTP/1.1 101 Switching      |                               |
      |  Upgrade: websocket          |  101 Switching Protocols      |
      |  Connection: Upgrade         |<------------------------------|
      |<-----------------------------|                               |
      |                              |                               |
      |  [WebSocket frame: text]     |  [proxy frame]                |
      |  {"type":"msg","text":"hi"}  |                               |
      |----------------------------->|------------------------------>|
      |                              |                               |
      |                              |  [proxy frame back]           |
      |  {"type":"msg","from":"Bob"} |                               |
      |<-----------------------------|<------------------------------|
      |                              |                               |
      |  [ping]                      |  [proxy ping]                 |
      |----------------------------->|------------------------------>|
      |  [pong]                      |  [proxy pong]                 |
      |<-----------------------------|<------------------------------|

Gateway challenges for WebSocket:
  1. Connection affinity: same backend for the entire session
  2. Idle timeout: separate from HTTP request timeout (often 30-60 min)
  3. Auth: token in query param or first message (no headers after upgrade)
  4. Rate limiting: per-message not per-request
  5. Circuit breaking: connection-level, not request-level
```

### Nginx WebSocket Configuration

```nginx
# WebSocket proxy -- requires Connection and Upgrade header pass-through
location /ws/ {
    proxy_pass http://websocket_backend;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;

    # WebSocket-specific timeouts
    proxy_read_timeout 3600s;   # 1 hour (long-lived connection)
    proxy_send_timeout 3600s;

    # Sticky session (IP hash for WebSocket affinity)
    # Alternatively use cookie-based stickiness
}

upstream websocket_backend {
    ip_hash;  # Session affinity for WebSocket
    server ws-1.internal:8080;
    server ws-2.internal:8080;
}
```

---

## 14. TLS 1.3 and Mutual TLS (mTLS)

**THE security layer for all gateway and mesh communication.** TLS 1.3
provides encrypted transport with a 1-RTT handshake. mTLS extends this
by requiring both client and server to present certificates, enabling
zero-trust service-to-service authentication.

### Why TLS 1.3 + mTLS

| Criterion | Fit |
|-----------|-----|
| 1-RTT handshake | TLS 1.3: ~100ms faster than TLS 1.2 (0-RTT for resumption) |
| Forward secrecy | All TLS 1.3 cipher suites use ephemeral Diffie-Hellman |
| Zero trust | mTLS: every service proves identity via certificate |
| No shared secrets | mTLS replaces API keys/tokens for service-to-service auth |
| Auto-rotation | Istio/Linkerd rotate mTLS certs automatically (24h default) |
| SPIFFE identity | Standard workload identity: `spiffe://cluster/ns/default/sa/user-svc` |

### TLS 1.3 Handshake

```
Client                                              Server
  |                                                    |
  |  ClientHello                                       |
  |  - TLS 1.3                                         |
  |  - Supported cipher suites:                        |
  |    TLS_AES_256_GCM_SHA384                          |
  |    TLS_CHACHA20_POLY1305_SHA256                    |
  |  - Key shares (ECDHE P-256, X25519)                |
  |  - SNI: api.example.com                            |
  |--------------------------------------------------->|
  |                                                    |
  |                          ServerHello               |
  |                          - Selected cipher         |
  |                          - Server key share         |
  |                          EncryptedExtensions        |
  |                          Certificate                |
  |                          CertificateVerify          |
  |                          Finished                   |
  |<---------------------------------------------------|
  |                                                    |
  |  [Verify server certificate chain]                 |
  |  [Derive shared secret from ECDHE]                 |
  |                                                    |
  |  Finished                                          |
  |--------------------------------------------------->|
  |                                                    |
  |  === Application Data (encrypted) ===              |
  |<=================================================>|
  |                                                    |
  Total: 1 round trip (vs 2 RTT for TLS 1.2)
```

### mTLS Handshake (Service Mesh)

```
Sidecar A (caller)                              Sidecar B (target)
  |                                                    |
  |  ClientHello                                       |
  |  + client certificate request indicator            |
  |--------------------------------------------------->|
  |                                                    |
  |                  ServerHello                        |
  |                  + CertificateRequest               |
  |                  + Server Certificate:              |
  |                    CN=order-service                 |
  |                    SAN=spiffe://cluster/ns/default/ |
  |                        sa/order-service             |
  |<---------------------------------------------------|
  |                                                    |
  |  Client Certificate:                               |
  |    CN=user-service                                 |
  |    SAN=spiffe://cluster/ns/default/                |
  |        sa/user-service                             |
  |  CertificateVerify (signed with client private key)|
  |  Finished                                          |
  |--------------------------------------------------->|
  |                                                    |
  |  [Server validates client cert against mesh CA]    |
  |  [Server checks AuthorizationPolicy:               |
  |   user-service is allowed to call order-service]   |
  |                                                    |
  |                  Finished                          |
  |<---------------------------------------------------|
  |                                                    |
  |  === Mutually authenticated + encrypted ===        |
  |<=================================================>|
```

### Certificate Rotation (Istio Citadel)

```
+-------------------------------+
|  Istio Citadel (Mesh CA)      |
|  - Root CA key (HSM-backed)   |
|  - Signs workload certs       |
|  - Default lifetime: 24 hours |
+---------------+---------------+
                |
                | SDS (Secret Discovery Service)
                |
   +------------+-----------+
   |                        |
+--+--+                  +--+--+
|Envoy|                  |Envoy|
|proxy|                  |proxy|
+--+--+                  +--+--+

Certificate lifecycle:
  1. Pod starts → Envoy sidecar injected (istio-init + istio-proxy)
  2. Envoy sends CSR to Citadel via SDS gRPC
  3. Citadel validates pod identity (k8s ServiceAccount token)
  4. Citadel signs certificate (SPIFFE SVID, 24h TTL)
  5. Envoy receives cert + key via SDS (in-memory, never on disk)
  6. 12 hours later: Envoy proactively requests new cert (50% lifetime)
  7. Citadel issues new cert; old cert still valid until expiry
  8. Envoy hot-swaps cert — zero downtime, zero dropped connections
```

### Simulation-to-Production Mapping

| Our Simulation | Production (TLS 1.3 / mTLS) |
|----------------|------------------------------|
| `TlsEngine.validateConnection()` | mTLS handshake + cert chain validation |
| `TlsEngine.trustedServices` (HashSet) | Mesh CA trust anchor + AuthorizationPolicy |
| `TlsEngine.enableMtls()` | PeerAuthentication mode: STRICT |
| `TlsEngine.trustService()` | ServiceAccount → SPIFFE identity → cert issuance |
| Boolean validation result | Full X.509 chain validation + CRL/OCSP check |

---

## 15. Consistent Hashing -- Cache-Affinity Load Balancing

**THE algorithm for stateful load balancing.** Consistent hashing ensures
that the same request key routes to the same backend instance, maximizing
cache hit rates. When instances are added or removed, only ~1/N of the keys
are remapped.

### Why Consistent Hashing at the Gateway

| Criterion | Fit |
|-----------|-----|
| Cache affinity | Same user/session always hits same instance → warm cache |
| Minimal disruption | Adding/removing nodes only moves ~1/N keys |
| Stateful services | Session data, local caches, connection pools |
| Virtual nodes | Even distribution despite heterogeneous instance capacities |
| Used by | Envoy (ring hash), Nginx (consistent hash), Kong (consistent hash) |

### Consistent Hash Ring

```
Hash ring: [0, 2^31 - 1]

                           hash("inst-A-vnode-0") = 100
                                    |
          hash("inst-C-vnode-2")    |     hash("inst-B-vnode-0") = 350
                  = 950             |              |
                    |               |              |
     +--------------+---+--------+-+--+-----------+------+
     |                  |        |    |                   |
     0            ------+--------+    +----------         2^31-1
                                                  
     Request path "/api/users/123" hashes to 200
       → clockwise → next node is inst-B-vnode-0 at 350
       → route to instance B

     If instance B is removed:
       → requests at hash 200 now route to next clockwise node
       → only requests between A-vnode-0 (100) and B-vnode-0 (350) are affected
       → other requests unchanged (~1/N disruption)
```

### Virtual Nodes for Even Distribution

```
Without virtual nodes (3 instances):
  Instance A: 1 position on ring → might get 50% of key space
  Instance B: 1 position on ring → might get 10% of key space
  Instance C: 1 position on ring → might get 40% of key space
  Problem: severely uneven distribution

With 150 virtual nodes per instance (our implementation):
  Instance A: 150 positions → ~33.3% of key space
  Instance B: 150 positions → ~33.3% of key space
  Instance C: 150 positions → ~33.3% of key space
  
  Total ring positions: 450
  Standard deviation of load: < 5% (vs ~50% without vnodes)

Weighted virtual nodes:
  Instance A (weight=5): 150 * 5 = 750 vnodes → ~50% traffic
  Instance B (weight=3): 150 * 3 = 450 vnodes → ~30% traffic
  Instance C (weight=2): 150 * 2 = 300 vnodes → ~20% traffic
```

### Simulation-to-Production Mapping

| Our Simulation | Production |
|----------------|-----------|
| `ConsistentHashLoadBalancer` (TreeMap) | Envoy ring hash, Nginx `hash $uri consistent` |
| `hash()` FNV-1a | Envoy: xxHash64, Nginx: CRC32 |
| `virtualNodeCount = 150` | Envoy: `minimum_ring_size: 1024` (default) |
| `TreeMap.ceilingEntry()` | Binary search on sorted ring array |
| `buildRing()` per request | Ring rebuilt on EDS endpoint change (cached between) |

### Complexity Analysis

| Operation | Time | Space | Notes |
|-----------|------|-------|-------|
| Build ring | O(N * V) | O(N * V) | N = instances, V = vnodes |
| Lookup | O(log(N * V)) | O(1) | TreeMap ceiling entry |
| Add instance | O(V * log(N * V)) | O(V) | Insert V virtual nodes |
| Remove instance | O(V * log(N * V)) | -O(V) | Remove V virtual nodes |
| Key redistribution | ~1/N of keys | O(1) | Only affected range moves |

---

## 16. Token Bucket Algorithm -- Rate Limiting

**THE rate limiting algorithm for API gateways.** Token bucket provides
smooth rate limiting with burst support. Tokens accumulate at a steady rate
and are consumed by each request. When the bucket is empty, requests are
rejected.

### Why Token Bucket

| Criterion | Fit |
|-----------|-----|
| Burst handling | Allows short bursts up to bucket capacity |
| Smooth limiting | Sustained rate never exceeds refill rate |
| Simple implementation | Counter + timestamp (our `TokenBucket` inner class) |
| Redis-friendly | Atomic Lua script with HMSET |
| Industry standard | Kong, AWS API Gateway, Envoy all use token bucket |

### Token Bucket Visualization

```
Bucket capacity: 10 tokens
Refill rate: 5 tokens/second

Time 0s:  [##########]  10/10 tokens (full)
           Request → consume 1 → [#########-]  9/10

Time 0.1s: [#########-]  9.5/10 tokens (refilled 0.5)
           Request → consume 1 → [########--]  8.5/10

Time 0.2s: burst of 8 requests
           [########--] → consume 8 → [----------] 0.5/10
           9th request → DENIED (429 Too Many Requests)
           Retry-After: 200ms (1 token / 5 per sec = 0.2s)

Time 0.4s: [#---------] 1.5/10 tokens (refilled 1.0 over 0.2s)
           Request → consume 1 → [----------] 0.5/10

Time 2.0s: [##########] 10/10 tokens (capped at max, refilled over 1.6s)
```

### Token Bucket vs Other Algorithms

| Algorithm | Burst | Precision | Memory | Best For |
|-----------|-------|-----------|--------|----------|
| Token bucket | Allows burst up to capacity | Per-request | O(1) per key | General API rate limiting |
| Leaky bucket | No burst (constant drain) | Per-request | O(1) per key | Smooth output rate |
| Fixed window | Burst at window boundary | Per-window (1s/1m) | O(1) per key | Simple counting |
| Sliding window log | No boundary burst | Per-request | O(N) per key | Precise but memory-heavy |
| Sliding window counter | Weighted boundary | Per-window | O(1) per key | Good precision, low memory |

### Our Implementation vs Production

| Our `RateLimiterEngine` | Production (Redis) |
|-------------------------|-------------------|
| `ConcurrentHashMap<String, TokenBucket>` | Redis Hash per key |
| `synchronized(bucket)` | Redis single-threaded + Lua atomicity |
| `bucket.refill()` in-process | Lua `elapsed * refillRate` |
| Single JVM (not distributed) | Redis Cluster (distributed, consistent) |
| `bucket.tokens -= 1.0` | `redis.call('HMSET', key, 'tokens', tokens - 1)` |
| `RateLimitResult.denied(retryAfterMs)` | Return `retry_after` from Lua script |

---

## 17. Canary Deployments -- Progressive Delivery

**THE deployment strategy for safe rollouts.** Canary deployments route a
small percentage of traffic to a new version, monitor for errors, and
gradually increase the percentage. The gateway/mesh is the control point
for traffic splitting.

### Why Canary at the Gateway/Mesh

| Criterion | Fit |
|-----------|-----|
| Risk reduction | Only 1-10% of users see the new version initially |
| Automatic rollback | If error rate > threshold, shift all traffic back to stable |
| No downtime | Users always have a healthy version to hit |
| A/B testing | Header-based routing for targeted canary (internal users first) |
| Gradual rollout | 1% → 5% → 25% → 50% → 100% over hours/days |
| Service mesh native | Istio VirtualService, Linkerd TrafficSplit |

### Canary Deployment Flow

```
Step 1: Deploy canary (weight=5%)
  +-----------+
  | Stable v1 |  95% traffic     ← 10 pods
  +-----------+
  +-----------+
  | Canary v2 |   5% traffic     ← 1 pod
  +-----------+
  Monitor: error rate, latency p99, CPU, memory
  Duration: 30 minutes

Step 2: Increase canary (weight=25%)
  +-----------+
  | Stable v1 |  75% traffic     ← 8 pods
  +-----------+
  +-----------+
  | Canary v2 |  25% traffic     ← 3 pods
  +-----------+
  Monitor: same metrics
  Duration: 1 hour

Step 3: Canary looks healthy (weight=50%)
  +-----------+
  | Stable v1 |  50% traffic     ← 5 pods
  +-----------+
  +-----------+
  | Canary v2 |  50% traffic     ← 5 pods
  +-----------+
  Duration: 2 hours

Step 4: Full rollout (weight=100%)
  +-----------+
  | v2 (new)  | 100% traffic     ← 10 pods
  +-----------+
  Old v1 pods scaled down and terminated

ROLLBACK (automatic):
  If error rate > 1% during any step:
    → Immediately shift 100% back to stable v1
    → Alert on-call engineer
    → Preserve canary pod for debugging (but no traffic)
```

### Simulation-to-Production Mapping

| Our Simulation | Production |
|----------------|-----------|
| `CanaryTrafficStrategy.selectVersion()` | Istio VirtualService weight-based routing |
| `TrafficSplit` model (version -> weight) | Istio subset weights / Linkerd TrafficSplit CRD |
| `HeaderBasedTrafficStrategy` | Istio VirtualService header match |
| `ThreadLocalRandom.nextInt(totalWeight)` | Envoy weighted cluster routing |
| Manual weight changes | Flagger / Argo Rollouts (automated canary promotion) |

---

## Technology Comparison Matrix

| Tech | Role | Protocol | Config | Scalability | Our Mapping |
|------|------|----------|--------|-------------|-------------|
| Kong | API Gateway | HTTP, gRPC, WS | YAML + Admin API | Horizontal (stateless nodes + Redis) | `GatewayService` |
| Envoy | Sidecar proxy | HTTP/1.1, HTTP/2, gRPC, TCP | xDS (dynamic gRPC) | Per-pod sidecar | `ServiceMeshService` |
| Istio | Mesh control plane | gRPC (xDS) | Kubernetes CRDs | Control plane scales with cluster | Mesh config + policies |
| Linkerd | Mesh (lightweight) | HTTP/1.1, HTTP/2, gRPC | Kubernetes CRDs | Rust proxy, low overhead | Alternative to Istio |
| AWS API GW | Managed gateway | HTTP, WS | CloudFormation/CDK | Auto-scales (managed) | `GatewayService` (managed) |
| Nginx | Reverse proxy / LB | HTTP, TCP, UDP | Static conf file | Vertical (event-loop) | `RequestRouter` + LB |
| HAProxy | TCP/HTTP LB | HTTP, TCP | Static conf file | Vertical (millions of conns) | LB + health checks |
| Consul | Service discovery | HTTP, DNS, gRPC | JSON/HCL | Multi-DC (Raft + gossip) | `ServiceRegistry` |
| etcd | KV store | gRPC | Key-value API | Raft cluster (3-5 nodes) | `ServiceRegistry` backing |
| Redis | Rate limit / cache | RESP protocol | Commands + Lua | Cluster (hash slots) | `RateLimiterEngine` |
| JWT/OAuth2 | Authentication | HTTP (token in header) | JWKS endpoint | Stateless validation | `AuthService` |
| gRPC | RPC framework | HTTP/2 + Protobuf | .proto files | Multiplexed connections | Service-to-service calls |
| TLS 1.3/mTLS | Transport security | TLS record protocol | Certificates (PEM) | Per-connection | `TlsEngine` |
| Consistent hash | Load balancing | N/A (algorithm) | Virtual node count | O(log N) lookup | `ConsistentHashLoadBalancer` |
| Token bucket | Rate limiting | N/A (algorithm) | Capacity + refill rate | O(1) per check | `RateLimiterEngine` |
| Canary deploy | Traffic splitting | N/A (strategy) | Weight percentages | Mesh-level routing | `CanaryTrafficStrategy` |

---

## Interview Quick Reference

### "What technology would you use for X?"

| Requirement | Technology | Why |
|-------------|-----------|-----|
| API gateway (open-source) | Kong | Richest plugin ecosystem, Nginx performance |
| API gateway (AWS-native) | AWS API Gateway + WAF | Managed, Lambda integration, usage plans |
| Service mesh | Istio + Envoy | Industry standard, full feature set |
| Service mesh (lightweight) | Linkerd | Simpler, less resource overhead |
| Service discovery | Consul or Kubernetes Services | Consul for multi-platform, k8s for k8s-only |
| Rate limiting (distributed) | Redis (token bucket Lua script) | Sub-ms, atomic, distributed |
| Authentication | JWT (RS256) + OAuth 2.0 + OIDC | Stateless, scalable, standard |
| TLS termination | Nginx or Envoy | High-performance TLS offloading |
| mTLS (service mesh) | Istio Citadel / Linkerd identity | Auto cert rotation, SPIFFE identity |
| Load balancing (stateful) | Consistent hash (Envoy/Nginx) | Cache affinity, minimal disruption |
| Load balancing (stateless) | Round-robin or least-connections | Simple, even distribution |
| Canary deployments | Istio VirtualService + Flagger | Automated progressive delivery |
| gRPC gateway | Envoy (grpc-json transcoding) | Native gRPC support, HTTP/2 |
| WebSocket gateway | Nginx or Envoy | Upgrade handling, connection affinity |

### "Walk me through a request hitting the gateway"

```
1. Client sends HTTPS request to api.example.com
2. DNS (Route 53) resolves to nearest CDN edge / gateway IP
3. TLS 1.3 handshake terminates at the gateway (Nginx/Kong/Envoy)
4. Gateway extracts JWT from Authorization header
5. Gateway verifies JWT signature against cached JWKS public key
6. Gateway checks route table: /api/users/** → user-service
7. Gateway checks rate limit in Redis (token bucket, O(1))
8. Gateway checks circuit breaker state for user-service
9. Gateway selects instance via load balancer (round-robin or consistent hash)
10. Gateway forwards request to selected instance (with X-Trace-Id header)
11. Sidecar proxy at the instance validates mTLS (Envoy)
12. Service processes request and responds
13. Gateway records success in circuit breaker
14. Gateway returns response to client with trace headers

Our simulation: GatewayService.handleRequest() mirrors steps 4-13 exactly.
```
