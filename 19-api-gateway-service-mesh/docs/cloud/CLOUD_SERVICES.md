# API Gateway & Service Mesh -- Cloud Service Mapping

> Production cloud services for an API gateway and service mesh platform.
> Covers AWS, Azure, and GCP equivalents for every component in our Java
> simulation: routing, authentication, rate limiting, circuit breaking,
> load balancing, service discovery, mTLS, canary deployments, and
> observability.

---

## Component-to-Service Mapping

| Our Component | AWS | Azure | GCP | Notes |
|---------------|-----|-------|-----|-------|
| **API Gateway** | API Gateway (REST/HTTP) | API Management (APIM) | Cloud Endpoints / Apigee | TLS termination, auth, rate limiting, request routing |
| **Load Balancer (L7)** | ALB (Application Load Balancer) | Application Gateway | Cloud Load Balancing (HTTP(S)) | Path-based routing, WebSocket, gRPC |
| **Load Balancer (L4)** | NLB (Network Load Balancer) | Azure Load Balancer | Cloud Load Balancing (TCP/UDP) | Ultra-low latency, static IP, TLS passthrough |
| **Service Mesh** | App Mesh (Envoy-based) | Open Service Mesh (OSM) / Istio on AKS | Anthos Service Mesh (Istio-based) / Traffic Director | mTLS, traffic splitting, observability |
| **WAF** | AWS WAF | Azure WAF (on Front Door/App GW) | Cloud Armor | IP filtering, rate limiting, SQL injection, XSS protection |
| **CDN + Edge Routing** | CloudFront | Azure Front Door / Azure CDN | Cloud CDN | Edge TLS, caching, geo-routing, edge functions |
| **Service Discovery** | Cloud Map | Azure Service Fabric / k8s Services | Cloud Service Directory | DNS-based or API-based service discovery |
| **DNS** | Route 53 | Azure DNS / Traffic Manager | Cloud DNS | Latency-based, failover, weighted routing |
| **Auth (Identity)** | Cognito | Azure AD B2C | Identity Platform (Firebase Auth) | User pools, JWT issuance, social login, MFA |
| **Auth (Service)** | IAM Roles + STS | Managed Identities (Azure AD) | Workload Identity (GCP IAM) | Service-to-service auth, no static credentials |
| **Rate Limiting** | API Gateway usage plans + WAF rate rules | APIM rate-limit policy + WAF | Apigee quota/spike arrest + Cloud Armor rate limiting | Token bucket or sliding window |
| **Cache (Distributed)** | ElastiCache Redis | Azure Cache for Redis | Memorystore (Redis) | Rate limit state, response cache, session state |
| **Secret Management** | Secrets Manager / Parameter Store | Key Vault | Secret Manager | API keys, TLS certs, OAuth client secrets |
| **Certificate Management** | ACM (Certificate Manager) | App Service Certificates / Key Vault | Certificate Manager | Auto-renewal, ALB/CloudFront integration |
| **Container Orchestration** | EKS (Kubernetes) / ECS | AKS (Kubernetes) | GKE (Kubernetes) | Pod-level sidecar injection for mesh |
| **Observability (Metrics)** | CloudWatch | Azure Monitor | Cloud Monitoring | Latency, error rate, request count |
| **Observability (Tracing)** | X-Ray | Application Insights | Cloud Trace | Distributed tracing across gateway + services |
| **Observability (Logging)** | CloudWatch Logs | Log Analytics | Cloud Logging | Access logs, error logs, audit logs |

---

## AWS Architecture (Numbered Flow)

```
User sends: GET https://api.example.com/api/users/123
    |
    1. DNS Resolution (Route 53):
       api.example.com → latency-based routing
       → resolves to nearest CloudFront distribution
    |
    v
    2. CDN Edge (CloudFront):
       - TLS 1.3 termination at edge PoP
       - Check response cache (for GET requests with caching enabled)
       - Cache HIT → return immediately (< 10ms)
       - Cache MISS → forward to origin (API Gateway)
       - Lambda@Edge can run custom auth/routing logic at the edge
    |
    v
    3. WAF (AWS WAF):
       - IP reputation list check
       - Rate limiting: 10,000 requests per IP per 5 minutes
       - SQL injection / XSS pattern matching
       - Geo-blocking (if required)
       - Blocked → return 403 (never reaches API Gateway)
    |
    v
    4. API Gateway (REST API):
       a. Custom domain mapping: api.example.com → API Gateway stage
       b. Request validation: JSON schema check on body/params
       c. Authorizer (Cognito JWT):
          - Extract JWT from Authorization header
          - Validate against Cognito user pool
          - Extract claims: sub, cognito:groups, custom:tenantId
       d. Usage plan check:
          - API key from x-api-key header
          - Throttle: 100 req/s burst, 50 req/s sustained
          - Quota: 100,000 requests/month
       e. Request transformation (VTL):
          - Map path /users/123 → integration /internal/v2/users/123
          - Add header: X-Tenant-Id from JWT claims
       f. Integration: HTTP proxy to ALB
    |
    v
    5. Application Load Balancer (ALB):
       - Target group: user-service ECS tasks (port 8080)
       - Health check: GET /health every 30s
       - Routing rules:
         /api/users/* → user-service target group
         /api/orders/* → order-service target group
       - Sticky sessions (optional): cookie-based affinity
    |
    v
    6. Service Mesh (AWS App Mesh):
       - Envoy sidecar proxy (injected into ECS task / EKS pod)
       - mTLS between services (ACM Private CA certificates)
       - Virtual service: user-service.local
       - Virtual router: route to virtual nodes
       - Traffic splitting: 90% v1-stable, 10% v2-canary
       - Retry policy: 3 retries on 5xx, 2s timeout per attempt
       - Circuit breaker: outlier detection (5 consecutive 5xx → eject for 30s)
    |
    v
    7. ECS Fargate Task (user-service):
       - Runs in private subnet (no public IP)
       - IAM task role for accessing AWS services
       - Reads secrets from Secrets Manager (DB password, API keys)
       - Calls other services via App Mesh virtual services
       - Sends traces to X-Ray daemon (sidecar)
    |
    v
    8. Observability:
       - CloudWatch Metrics: request count, latency p50/p95/p99, 4xx/5xx rate
       - X-Ray Traces: gateway → ALB → service → DB (end-to-end)
       - CloudWatch Logs: API Gateway access logs, application logs
       - CloudWatch Alarms: 5xx rate > 1% → SNS → PagerDuty
```

### AWS Service Configuration Details

#### Route 53 (DNS + Service Discovery)

```
Route 53 Hosted Zone: api.example.com
  |
  +-- A record (alias): api.example.com → CloudFront distribution
  |     Routing policy: latency-based (closest edge)
  |
  +-- CNAME: auth.example.com → Cognito user pool domain
  |
  +-- Private hosted zone (VPC):
      +-- SRV: user-service.internal → ECS service discovery
      +-- SRV: order-service.internal → ECS service discovery

Cloud Map (Service Discovery):
  Namespace: example.local (DNS, private)
    |
    +-- Service: user-service
    |     Instances:
    |       - 10.0.1.10:8080 (healthy)
    |       - 10.0.1.11:8080 (healthy)
    |       - 10.0.1.12:8080 (draining)
    |     Health check: HTTP /health, 10s interval
    |
    +-- Service: order-service
          Instances:
            - 10.0.2.10:8080 (healthy)
```

#### Cognito (Authentication)

```
Cognito User Pool: api-users
  |
  +-- User pool settings:
  |     Password policy: min 12 chars, uppercase, number, symbol
  |     MFA: optional (TOTP/SMS)
  |     Token validity: access=1h, id=1h, refresh=30d
  |
  +-- App client: web-app
  |     Flows: Authorization code + PKCE
  |     Scopes: openid, profile, email, api/read, api/write
  |     Callback URL: https://app.example.com/callback
  |
  +-- App client: service-client
  |     Flows: Client credentials
  |     Scopes: api/admin
  |
  +-- Resource server: api
  |     Identifier: https://api.example.com
  |     Scopes: read, write, admin
  |
  +-- JWT claims (after authentication):
        {
          "sub": "user-123",
          "cognito:groups": ["admin", "users"],
          "custom:tenantId": "tenant-456",
          "iss": "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_ABC123",
          "aud": "web-app-client-id",
          "token_use": "access",
          "exp": 1700003600
        }

API Gateway Cognito Authorizer:
  Type: COGNITO_USER_POOLS
  User pool ARN: arn:aws:cognito-idp:us-east-1:123456789:userpool/us-east-1_ABC123
  Token source: Authorization header
  Token validation: "aud" must match app client ID
```

#### API Gateway + WAF Configuration

```json
{
  "Type": "AWS::ApiGateway::RestApi",
  "Properties": {
    "Name": "gateway-api",
    "EndpointConfiguration": {
      "Types": ["REGIONAL"]
    }
  }
}

// WAF WebACL for API Gateway
{
  "Type": "AWS::WAFv2::WebACL",
  "Properties": {
    "Name": "api-gateway-waf",
    "DefaultAction": { "Allow": {} },
    "Rules": [
      {
        "Name": "rate-limit-per-ip",
        "Priority": 1,
        "Statement": {
          "RateBasedStatement": {
            "Limit": 2000,
            "AggregateKeyType": "IP"
          }
        },
        "Action": { "Block": {} }
      },
      {
        "Name": "aws-managed-sql-injection",
        "Priority": 2,
        "Statement": {
          "ManagedRuleGroupStatement": {
            "VendorName": "AWS",
            "Name": "AWSManagedRulesSQLiRuleSet"
          }
        },
        "OverrideAction": { "None": {} }
      },
      {
        "Name": "geo-block",
        "Priority": 3,
        "Statement": {
          "GeoMatchStatement": {
            "CountryCodes": ["RU", "CN", "KP"]
          }
        },
        "Action": { "Block": {} }
      }
    ]
  }
}
```

#### App Mesh (Service Mesh)

```yaml
# App Mesh Virtual Service -- maps to our ServiceMeshService
apiVersion: appmesh.k8s.aws/v1beta2
kind: VirtualService
metadata:
  name: user-service
spec:
  awsName: user-service.example.local
  provider:
    virtualRouter:
      virtualRouterRef:
        name: user-service-router

---
# Virtual Router with canary split -- maps to our CanaryTrafficStrategy
apiVersion: appmesh.k8s.aws/v1beta2
kind: VirtualRouter
metadata:
  name: user-service-router
spec:
  listeners:
    - portMapping:
        port: 8080
        protocol: http
  routes:
    - name: user-service-route
      httpRoute:
        match:
          prefix: /
        action:
          weightedTargets:
            - virtualNodeRef:
                name: user-service-v1
              weight: 90
            - virtualNodeRef:
                name: user-service-v2
              weight: 10
        retryPolicy:
          maxRetries: 3
          perRetryTimeout:
            value: 2
            unit: s
          httpRetryEvents:
            - server-error
            - gateway-error
        timeout:
          perRequest:
            value: 10
            unit: s

---
# Virtual Node -- maps to our ServiceInstance
apiVersion: appmesh.k8s.aws/v1beta2
kind: VirtualNode
metadata:
  name: user-service-v1
spec:
  podSelector:
    matchLabels:
      app: user-service
      version: v1
  listeners:
    - portMapping:
        port: 8080
        protocol: http
      healthCheck:
        protocol: http
        path: /health
        healthyThreshold: 2
        unhealthyThreshold: 3
        timeoutMillis: 2000
        intervalMillis: 5000
      outlierDetection:
        maxServerErrors: 5
        maxEjectionPercent: 50
        interval:
          value: 10
          unit: s
        baseEjectionDuration:
          value: 30
          unit: s
  serviceDiscovery:
    awsCloudMap:
      namespaceName: example.local
      serviceName: user-service
  backendDefaults:
    clientPolicy:
      tls:
        enforce: true
        validation:
          trust:
            acm:
              certificateAuthorityArns:
                - arn:aws:acm-pca:us-east-1:123456:certificate-authority/abc
```

### AWS Cost Estimate (Production Scale)

| Service | Spec | Monthly Cost (est.) |
|---------|------|-------------------|
| API Gateway (REST) | 100M requests/month | $350 |
| API Gateway (HTTP) | 100M requests/month | $100 |
| CloudFront | 10 TB transfer, 500M requests | $1,200 |
| ALB | 2 ALBs, 1000 LCU-hours | $200 |
| WAF | 1 WebACL, 5 rules, 100M requests | $200 |
| App Mesh | Free (Envoy sidecar resources only) | $0 (compute cost in ECS/EKS) |
| Cognito | 100K MAU (free tier: 50K) | $275 |
| ElastiCache Redis | r6g.large, 3-node cluster | $450 |
| Cloud Map | 100 instances, 1B DNS queries | $50 |
| Route 53 | 1 hosted zone, 10M queries | $10 |
| ACM | Free (public certs) | $0 |
| X-Ray | 10M traces sampled | $50 |
| CloudWatch | Metrics + logs + alarms | $150 |
| **Total** | | **~$3,000/month** |

---

## Azure Architecture (Numbered Flow)

```
User sends: GET https://api.example.com/api/users/123
    |
    1. DNS Resolution (Azure DNS / Traffic Manager):
       api.example.com → performance-based routing
       → resolves to Azure Front Door endpoint
    |
    v
    2. Azure Front Door (CDN + Edge Routing + WAF):
       - Global load balancing (anycast)
       - TLS 1.3 termination at edge
       - WAF: IP filtering, rate limiting, OWASP rules
       - Response caching (for cacheable GET requests)
       - URL rewrite and redirect rules
       - Cache MISS → route to nearest backend
    |
    v
    3. API Management (APIM):
       a. Subscription key check (x-api-key or Ocp-Apim-Subscription-Key)
       b. JWT validation policy:
          <validate-jwt header-name="Authorization"
                        failed-validation-httpcode="401">
            <openid-config url="https://login.microsoftonline.com/{tenant}/v2.0/.well-known/openid-configuration" />
            <required-claims>
              <claim name="aud" match="all">
                <value>api://gateway-api</value>
              </claim>
            </required-claims>
          </validate-jwt>
       c. Rate limiting policy:
          <rate-limit-by-key calls="100" renewal-period="60"
                            counter-key="@(context.Request.IpAddress)" />
       d. Quota policy:
          <quota-by-key calls="10000" renewal-period="86400"
                        counter-key="@(context.Subscription.Id)" />
       e. Request transformation:
          <set-header name="X-Tenant-Id" exists-action="override">
            <value>@(context.Request.Headers.GetValueOrDefault("Authorization","")
                    .AsJwt()?.Claims["tenantId"]?.FirstOrDefault())</value>
          </set-header>
       f. Backend routing:
          /api/users/* → user-service backend pool
    |
    v
    4. Application Gateway (L7 Load Balancer):
       - Backend pool: user-service AKS pods
       - Health probe: GET /health every 30s
       - Path-based routing: /api/users/* → user-service pool
       - Session affinity: cookie-based (optional)
       - Auto-scaling: scale based on request count
    |
    v
    5. Service Mesh (Istio on AKS / Open Service Mesh):
       - Envoy sidecar injected via admission controller
       - mTLS between all services (automatic with Istio)
       - Traffic splitting: VirtualService with weighted destinations
       - Retry policies and circuit breaking via DestinationRule
    |
    v
    6. AKS Pod (user-service):
       - Workload Identity (Azure AD Managed Identity)
       - Secrets from Azure Key Vault (CSI driver)
       - Calls other services via mesh virtual services
    |
    v
    7. Observability:
       - Azure Monitor: metrics, dashboards
       - Application Insights: distributed tracing, dependency map
       - Log Analytics: centralized logging with KQL queries
       - Alert rules: 5xx rate > 1% → Action Group → email/SMS/PagerDuty
```

### Azure APIM Policies (Key Examples)

```xml
<!-- API Management inbound policy pipeline -->
<!-- Maps to our GatewayService.handleRequest() steps 3-5 -->
<policies>
    <inbound>
        <!-- Step 1: CORS -->
        <cors>
            <allowed-origins>
                <origin>https://app.example.com</origin>
            </allowed-origins>
        </cors>

        <!-- Step 2: JWT Validation (maps to our AuthService) -->
        <validate-jwt header-name="Authorization"
                      failed-validation-httpcode="401"
                      failed-validation-error-message="Authentication failed">
            <openid-config url="https://login.microsoftonline.com/{tenant-id}/v2.0/.well-known/openid-configuration" />
            <audiences>
                <audience>api://gateway-api</audience>
            </audiences>
            <required-claims>
                <claim name="roles" match="any">
                    <value>API.Read</value>
                    <value>API.Write</value>
                </claim>
            </required-claims>
        </validate-jwt>

        <!-- Step 3: Rate Limiting (maps to our RateLimitService) -->
        <rate-limit-by-key calls="100"
                           renewal-period="60"
                           counter-key="@(context.Request.IpAddress)"
                           increment-condition="@(context.Response.StatusCode >= 200
                                                  && context.Response.StatusCode < 300)" />

        <!-- Step 4: Quota (maps to our per-client rate limit) -->
        <quota-by-key calls="10000"
                      renewal-period="86400"
                      counter-key="@(context.Subscription.Id)" />

        <!-- Step 5: Request transformation -->
        <set-header name="X-Trace-Id" exists-action="override">
            <value>@(Guid.NewGuid().ToString())</value>
        </set-header>
        <set-header name="X-User-Id" exists-action="override">
            <value>@(context.Request.Headers.GetValueOrDefault("Authorization","")
                    .AsJwt()?.Subject)</value>
        </set-header>

        <!-- Step 6: Circuit breaker (via backend selection) -->
        <set-backend-service base-url="https://user-service.internal" />
    </inbound>

    <backend>
        <!-- Retry policy (maps to our Route.retryCount) -->
        <retry condition="@(context.Response.StatusCode >= 500)"
               count="3"
               interval="1"
               delta="1"
               max-interval="10"
               first-fast-retry="true" />
    </backend>

    <outbound>
        <!-- Response caching (maps to our response cache) -->
        <cache-store duration="300" />

        <!-- Add cache status header -->
        <set-header name="X-Cache-Status" exists-action="override">
            <value>@(context.Response.Headers.GetValueOrDefault("X-Cache-Lookup-Result","MISS"))</value>
        </set-header>
    </outbound>

    <on-error>
        <set-status code="500" reason="Internal Server Error" />
        <set-body>@{
            return new JObject(
                new JProperty("error", context.LastError.Message),
                new JProperty("traceId", context.RequestId)
            ).ToString();
        }</set-body>
    </on-error>
</policies>
```

### Azure Cost Estimate (Production Scale)

| Service | Spec | Monthly Cost (est.) |
|---------|------|-------------------|
| APIM (Standard v2) | 100M requests/month | $700 |
| Front Door (Standard) | 10 TB transfer, 500M requests | $800 |
| Application Gateway (v2) | 2 instances, WAF enabled | $400 |
| AKS | 6 x D4s v3 nodes | $1,000 |
| Azure Cache for Redis | P1 Premium, 6 GB | $500 |
| Azure AD B2C | 100K MAU (first 50K free) | $150 |
| Key Vault | 100K operations/month | $5 |
| Application Insights | 10 GB logs/month | $25 |
| Azure Monitor | Metrics + alerts | $100 |
| **Total** | | **~$3,700/month** |

---

## GCP Architecture (Numbered Flow)

```
User sends: GET https://api.example.com/api/users/123
    |
    1. DNS Resolution (Cloud DNS):
       api.example.com → resolves to Cloud Load Balancing global IP
       (GCP uses anycast for global load balancing -- no separate CDN step)
    |
    v
    2. Cloud Load Balancing (Global HTTP(S) LB):
       - TLS 1.3 termination at Google's edge (200+ PoPs)
       - Cloud CDN: check response cache
       - Cloud Armor: WAF rules (rate limiting, IP blocking, OWASP)
       - URL map: path-based routing to backend services
         /api/users/* → user-service NEG (Network Endpoint Group)
         /api/orders/* → order-service NEG
       - Health checks: HTTP /health every 10s
    |
    v
    3. Apigee (API Management) or Cloud Endpoints:
       Option A -- Apigee (full-featured, enterprise):
         a. API key validation
         b. OAuth 2.0 token verification
         c. Spike arrest: 100 per second
         d. Quota: 10,000 per day per developer
         e. Request/response transformation
         f. Analytics and developer portal
       Option B -- Cloud Endpoints (lighter-weight):
         a. OpenAPI spec-based routing
         b. Firebase Auth / Google Auth token validation
         c. API key check
         d. Logging to Cloud Logging
    |
    v
    4. Service Mesh (Anthos Service Mesh / Traffic Director):
       Option A -- Anthos Service Mesh (Istio-based, managed):
         - Managed Istio control plane (Google-hosted istiod)
         - Envoy sidecar injection via MutatingWebhook
         - mTLS: automatic with mesh CA
         - Traffic splitting via Istio VirtualService
         - Telemetry: automatic export to Cloud Monitoring + Cloud Trace
       Option B -- Traffic Director (proxyless gRPC or Envoy):
         - xDS-compatible control plane
         - Works with Envoy sidecars or proxyless gRPC clients
         - Service routing, load balancing, traffic splitting
         - Health checking at the mesh level
    |
    v
    5. GKE Pod (user-service):
       - Workload Identity: GCP IAM bound to k8s ServiceAccount
       - Secrets from Secret Manager (mounted via CSI driver)
       - Service-to-service calls via mesh
    |
    v
    6. Observability:
       - Cloud Monitoring: latency, error rate, saturation
       - Cloud Trace: distributed tracing (OpenTelemetry)
       - Cloud Logging: structured logs with trace correlation
       - Cloud Error Reporting: automatic error grouping
       - SLO Monitoring: burn rate alerts
```

### GCP Cloud Armor Configuration (WAF + Rate Limiting)

```yaml
# Cloud Armor security policy
# Maps to our rate limiting + WAF equivalent
resource "google_compute_security_policy" "api_policy" {
  name = "api-gateway-policy"

  # Rule 1: Rate limiting per IP (maps to our RateLimiterEngine)
  rule {
    action   = "rate_based_ban"
    priority = 1000
    match {
      versioned_expr = "SRC_IPS_V1"
      config {
        src_ip_ranges = ["*"]
      }
    }
    rate_limit_options {
      conform_action = "allow"
      exceed_action  = "deny(429)"
      rate_limit_threshold {
        count        = 100
        interval_sec = 60
      }
      ban_duration_sec = 300
    }
  }

  # Rule 2: Block known bad IPs
  rule {
    action   = "deny(403)"
    priority = 2000
    match {
      versioned_expr = "SRC_IPS_V1"
      config {
        src_ip_ranges = ["192.0.2.0/24", "198.51.100.0/24"]
      }
    }
  }

  # Rule 3: OWASP Top 10 protection
  rule {
    action   = "deny(403)"
    priority = 3000
    match {
      expr {
        expression = "evaluatePreconfiguredExpr('sqli-v33-stable')"
      }
    }
  }

  # Rule 4: XSS protection
  rule {
    action   = "deny(403)"
    priority = 3001
    match {
      expr {
        expression = "evaluatePreconfiguredExpr('xss-v33-stable')"
      }
    }
  }

  # Default: allow
  rule {
    action   = "allow"
    priority = 2147483647
    match {
      versioned_expr = "SRC_IPS_V1"
      config {
        src_ip_ranges = ["*"]
      }
    }
  }
}
```

### GCP Anthos Service Mesh Configuration

```yaml
# Istio VirtualService on Anthos Service Mesh
# Maps to our CanaryTrafficStrategy + RoutingService
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: user-service
  namespace: production
spec:
  hosts:
    - user-service.production.svc.cluster.local
  http:
    - match:
        - headers:
            x-canary:
              exact: "true"
      route:
        - destination:
            host: user-service.production.svc.cluster.local
            subset: canary
      timeout: 10s
    - route:
        - destination:
            host: user-service.production.svc.cluster.local
            subset: stable
          weight: 95
        - destination:
            host: user-service.production.svc.cluster.local
            subset: canary
          weight: 5
      retries:
        attempts: 3
        perTryTimeout: 2s
        retryOn: 5xx,connect-failure
      timeout: 10s
```

### GCP Cost Estimate (Production Scale)

| Service | Spec | Monthly Cost (est.) |
|---------|------|-------------------|
| Cloud Load Balancing | Global HTTP(S) LB, 100M requests | $200 |
| Cloud CDN | 10 TB egress, cache fills | $800 |
| Cloud Armor | 1 policy, 5 rules, 100M requests | $200 |
| Apigee (Standard) | 100M API calls/month | $2,500 |
| Cloud Endpoints (alternative) | 100M requests | $30 |
| Anthos Service Mesh | Included with GKE Enterprise | $0 (part of GKE) |
| GKE | 6 x e2-standard-4 nodes | $800 |
| Memorystore Redis | 6 GB, standard tier | $350 |
| Identity Platform | 100K MAU | $0 (first 50K free) + $50 |
| Secret Manager | 100 secrets, 100K accesses | $5 |
| Cloud Monitoring | Metrics + tracing + logging | $150 |
| **Total (Apigee)** | | **~$5,100/month** |
| **Total (Endpoints)** | | **~$2,600/month** |

---

## Cross-Cloud Comparison

### API Gateway Services

| Feature | AWS API Gateway | Azure APIM | GCP Apigee | GCP Cloud Endpoints |
|---------|----------------|------------|------------|---------------------|
| Type | Managed | Managed | Managed | Managed |
| Pricing model | Per request | Per unit (capacity) | Per API call | Per request |
| JWT validation | Cognito authorizer | Policy (validate-jwt) | OAuthV2 policy | Firebase Auth |
| Rate limiting | Usage plans | Policy (rate-limit) | Spike arrest + quota | N/A (use Cloud Armor) |
| Response caching | Built-in (0.5-237 GB) | Policy (cache) | ResponseCache policy | Not built-in |
| gRPC support | HTTP API only | Via self-hosted gateway | Yes | Yes (native) |
| WebSocket | Dedicated WebSocket API | Not native | Not native | Not native |
| OpenAPI import | Yes | Yes | Yes | Yes |
| Developer portal | No (use third-party) | Built-in | Built-in | No |
| Custom plugins | Lambda authorizer | Custom policies (C#) | JavaScript/Java callouts | N/A |
| Latency added | ~10-30ms | ~10-50ms | ~5-20ms | ~5-10ms |

### Service Mesh Services

| Feature | AWS App Mesh | Azure OSM / Istio | GCP Anthos SM | GCP Traffic Director |
|---------|-------------|-------------------|---------------|---------------------|
| Data plane | Envoy | Envoy (Istio) / Linkerd-like (OSM) | Envoy (Istio) | Envoy or proxyless gRPC |
| Control plane | AWS-managed | Self-managed (Istio) or managed | Google-managed Istio | Google-managed xDS |
| mTLS | ACM Private CA | Istio Citadel (auto) | Mesh CA (auto) | Automatic |
| Traffic splitting | Virtual router weights | VirtualService weights | VirtualService weights | Traffic policy |
| Circuit breaking | Outlier detection | DestinationRule | DestinationRule | Backend service config |
| Canary deploys | Weighted targets | VirtualService | VirtualService + Flagger | Weighted backends |
| Multi-cluster | CloudMap-based | Istio multi-cluster | Anthos fleet | Cross-region backends |
| Observability | X-Ray, CloudWatch | Prometheus, Jaeger | Cloud Monitoring, Cloud Trace | Cloud Monitoring |

### WAF Services

| Feature | AWS WAF | Azure WAF | GCP Cloud Armor |
|---------|---------|-----------|-----------------|
| Attachment point | CloudFront, ALB, API GW | Front Door, App GW | Cloud LB |
| Rate limiting | Rate-based rules | Custom rules | Rate-based ban |
| Managed rules | AWS, marketplace | OWASP CRS, Microsoft | Pre-configured expressions |
| Bot detection | Bot Control | Bot Manager | reCAPTCHA Enterprise |
| DDoS protection | Shield Standard (free) + Advanced | DDoS Protection | Cloud Armor Managed Protection |
| Custom rules | JSON rule syntax | Custom rules | CEL expressions |
| Geo-blocking | GeoMatch condition | GeoMatch condition | origin.region_code |
| IP reputation | Managed IP reputation list | Threat Intelligence | Google Threat Intelligence |

### Service Discovery

| Feature | AWS Cloud Map | Azure (k8s DNS) | GCP Cloud Service Directory |
|---------|--------------|-----------------|---------------------------|
| Type | DNS + API | Kubernetes DNS (CoreDNS) | Managed directory |
| Health checks | HTTP, TCP, custom | k8s liveness/readiness probes | Health status via API |
| DNS integration | Route 53 auto-registration | kube-dns / CoreDNS | Cloud DNS integration |
| API query | DiscoverInstances API | k8s API (Endpoints) | Lookup API |
| Multi-cluster | Via private DNS zones | Istio multi-cluster | Cross-project |
| Registration | ECS auto-registration | k8s auto (Endpoints) | API-based |

---

## Simulation-to-Cloud Mapping

| Our Java Component | AWS Service | Azure Service | GCP Service |
|-------------------|-------------|---------------|-------------|
| `GatewayService` (facade) | API Gateway + ALB | APIM + App Gateway | Cloud LB + Apigee |
| `RequestRouter` (route matching) | API Gateway resources | APIM URL routing | Cloud LB URL map |
| `AuthService` (JWT validation) | Cognito authorizer | APIM validate-jwt | Identity Platform |
| `RateLimitService` (token bucket) | Usage plans + WAF | APIM rate-limit policy | Cloud Armor rate rules |
| `CircuitBreakerService` (state machine) | App Mesh outlier detection | Istio DestinationRule | Anthos SM DestinationRule |
| `LoadBalancerService` (strategy) | ALB/NLB target groups | App Gateway backend pool | Cloud LB backend service |
| `ConsistentHashLoadBalancer` | ALB (not natively, use NLB) | App Gateway (not native) | Cloud LB (consistent hash) |
| `ServiceRegistry` (discovery) | Cloud Map | k8s Services (AKS) | Service Directory / k8s |
| `ServiceMeshService` (sidecar) | App Mesh (Envoy) | Istio on AKS (Envoy) | Anthos SM (Envoy) |
| `TlsEngine` (mTLS) | ACM Private CA + App Mesh | Istio Citadel on AKS | Mesh CA (Anthos) |
| `CanaryTrafficStrategy` | App Mesh weighted targets | Istio VirtualService | Istio VirtualService |
| `RateLimiterEngine` (Redis state) | ElastiCache Redis | Azure Cache for Redis | Memorystore Redis |
| `Route` model (builder) | API GW resource + method | APIM API + operation | Cloud LB URL map rule |

---

## Interview Quick Reference

### "How would you deploy this on AWS?"

```
1. Route 53 → CloudFront (edge TLS + caching)
2. WAF (rate limit + OWASP rules)
3. API Gateway (auth via Cognito, rate limit via usage plans)
4. ALB (path-based routing to ECS/EKS target groups)
5. App Mesh (Envoy sidecars for mTLS, retries, circuit breaking)
6. ECS Fargate or EKS (container workloads)
7. ElastiCache Redis (distributed rate limiting, response cache)
8. Cloud Map (service discovery, DNS-based)
9. X-Ray + CloudWatch (tracing + metrics + logging)
Cost: ~$3,000/month for 100M requests/month
```

### "How would you choose between AWS, Azure, and GCP?"

```
AWS: Best if already on AWS. API Gateway + App Mesh is well-integrated.
  Strength: Mature ecosystem, Lambda integration, CloudFront global edge.
  Weakness: App Mesh is less feature-rich than Istio.

Azure: Best for enterprise (Azure AD integration).
  Strength: APIM has the richest policy engine (XML policies for everything).
  Weakness: Service mesh story is fragmented (OSM retired, Istio recommended).

GCP: Best for Kubernetes-native and gRPC workloads.
  Strength: Traffic Director with proxyless gRPC (no sidecar!), Anthos SM.
  Weakness: Apigee is expensive; Cloud Endpoints is bare-bones.
```

### "What is the minimum viable production setup?"

```
Minimum (single cloud, Kubernetes):
  1. Kubernetes (EKS/AKS/GKE) with Ingress controller (Nginx Ingress or Envoy Gateway)
  2. Istio or Linkerd for service mesh (mTLS, traffic splitting, circuit breaking)
  3. Redis (ElastiCache/Memorystore) for distributed rate limiting
  4. OAuth 2.0 provider (Auth0 / Cognito / Azure AD) for JWT auth
  5. Prometheus + Grafana for observability
  Cost: ~$500-1,000/month (small scale)

This maps directly to our simulation:
  Ingress → GatewayService
  Istio → ServiceMeshService
  Redis → RateLimiterEngine
  Auth0 → AuthService (JwtAuthStrategy)
  Prometheus → metrics from all components
```
