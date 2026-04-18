# Cloud Services Mapping -- Rate Limiter

> Interview-focused reference: map each architectural component to AWS, GCP, and Azure equivalents.

---

## Service Comparison Table

| Component | AWS | GCP | Azure | Notes |
|-----------|-----|-----|-------|-------|
| Built-in Rate Limiting | API Gateway throttling | Cloud Endpoints quotas | API Management policies | Easiest option if using managed gateway |
| Counter Store | ElastiCache (Redis) | Memorystore (Redis) | Azure Cache for Redis | Core of custom rate limiter |
| Rules Database | DynamoDB / RDS | Firestore / Cloud SQL | Cosmos DB / SQL DB | Small dataset, read-heavy |
| Compute | ECS / EKS / Lambda@Edge | Cloud Run / GKE | AKS / Functions | Rate limiting at edge = lowest latency |
| Load Balancer | ALB / NLB | Cloud Load Balancing | Application Gateway | Must consider sticky sessions vs distributed |
| CDN/Edge | CloudFront + Lambda@Edge | Cloud CDN | Azure CDN + Functions | Rate limit at edge for DDoS |
| WAF | AWS WAF | Cloud Armor | Azure WAF | IP-based rate limiting built-in |
| Monitoring | CloudWatch | Cloud Monitoring | Azure Monitor | Track 429 rates, limit utilization |
| Config Store | Parameter Store / AppConfig | Runtime Configurator | App Configuration | Rule storage and hot-reload |

---

## AWS Reference Architecture

```
                      ┌──────────────┐
                      │  CloudFront  │  ← Edge-level rate limiting
                      │  + Lambda@   │    (IP-based, simple rules)
                      │    Edge      │
                      └──────┬───────┘
                             │
                      ┌──────▼───────┐
                      │ API Gateway  │  ← Built-in throttling
                      │  (Throttle:  │    per-API, per-stage,
                      │  10K req/s)  │    per-client (usage plans)
                      └──────┬───────┘
                             │
                      ┌──────▼───────┐
                      │     ALB      │  ← Health checks, routing
                      └──────┬───────┘
                             │
                ┌────────────┼────────────┐
                │            │            │
          ┌─────▼─────┐┌────▼────┐┌─────▼─────┐
          │  ECS/EKS  ││ ECS/EKS ││  ECS/EKS  │  ← Rate limiter
          │ (Service) ││(Service)││ (Service) │    middleware runs here
          └─────┬─────┘└────┬────┘└─────┬─────┘
                └────────────┼────────────┘
                       ┌─────▼─────┐
                       │   Redis   │  ← ElastiCache cluster
                       │ (Counters)│    Atomic INCR + Lua scripts
                       └─────┬─────┘
                             │
                    ┌────────┼────────┐
                    │                 │
             ┌──────▼──────┐  ┌──────▼──────┐
             │  DynamoDB   │  │  AppConfig  │
             │   (Rules)   │  │ (Hot Reload)│
             └─────────────┘  └─────────────┘
```

---

## Serverless Rate Limiting: Lambda@Edge

For simple IP-based limiting without managing infrastructure:

```
CloudFront Request → Lambda@Edge (viewer-request) → Check IP counter in
DynamoDB global table → Allow or return 429

Pros:
  - Zero servers to manage
  - Global by default (runs at every CloudFront edge)
  - Sub-10ms for most requests (DynamoDB DAX or local cache)

Cons:
  - DynamoDB latency from edge locations (mitigate with DAX)
  - Lambda@Edge has strict size/timeout limits (5s, 1MB)
  - Only practical for simple rules (IP-based, fixed window)
  - No Lua scripts -- atomicity depends on DynamoDB conditional writes
```

**Best for**: Simple IP-based rate limiting, DDoS protection, pre-filtering before requests hit origin.

---

## Built-in vs Custom Rate Limiting

| Criteria | Built-in (API Gateway) | Custom (Redis + Middleware) |
|----------|----------------------|---------------------------|
| Setup time | Minutes | Days to weeks |
| Per-API throttling | Yes | Yes |
| Per-user / per-tenant limits | Limited (usage plans) | Full flexibility |
| Algorithm choice | Token bucket only | Any (token bucket, sliding window, etc.) |
| Complex rules | No | Yes (user + endpoint + tier combos) |
| Multi-algorithm | No | Yes (different algos per endpoint) |
| Response headers | Basic | Full custom (X-RateLimit-*) |
| Cost | Included with gateway | Redis + compute costs |
| Operational burden | None | Redis cluster management |

### Decision Framework

```
Start here:
  │
  ├─ Do you need per-user or per-tenant limits?
  │    ├─ No  → Use API Gateway built-in throttling
  │    └─ Yes → Custom rate limiter
  │
  ├─ Do you need multiple algorithms?
  │    ├─ No  → API Gateway (token bucket) is fine
  │    └─ Yes → Custom rate limiter
  │
  ├─ Do you need composite keys (user + endpoint + tier)?
  │    ├─ No  → API Gateway
  │    └─ Yes → Custom rate limiter
  │
  └─ Is this a multi-tenant SaaS platform?
       ├─ No  → API Gateway is probably enough
       └─ Yes → Custom rate limiter (per-tenant quotas are essential)
```

---

## Cost Considerations

### What Gets Expensive

| Resource | Why It Costs | At Scale (100K req/s) |
|----------|-------------|----------------------|
| **Redis** | ElastiCache runs 24/7 regardless of traffic | r6g.xlarge cluster ~$500/mo; still cheap vs alternatives |
| **API Gateway** | $3.50/million requests adds up fast | 100K/s = 259B/month = $900K. Consider ALB-only at this scale |
| **Lambda@Edge** | Per-invocation cost at high volume | 100K/s = expensive. Use for pre-filtering only |
| **DynamoDB** | WCUs for counter updates | Atomic INCR per request. Use Redis instead for hot counters |

### What's Cheap

- **Redis memory**: Rate limit counters are tiny (~100 bytes each). Millions of keys fit in a few GB.
- **AppConfig**: Pennies/month for rule configuration storage.
- **CloudWatch**: Standard metrics are free. Custom metrics at $0.30/metric/month.
- **WAF rules**: $5/rule/month for IP-based rate limiting. Cheapest option for simple cases.

### Cost Optimization Tips

1. **Redis over DynamoDB for counters** -- Redis INCR is faster and cheaper at high throughput
2. **API Gateway throttling is free** -- included in the per-request pricing you already pay
3. **WAF for IP-based limits** -- $5/rule/month beats building custom infrastructure
4. **Tiered approach** -- WAF (IP) → API Gateway (API) → Custom (user) -- each layer filters traffic before the next
5. **Reserved instances** for Redis if traffic is predictable

---

## Interview Tips

### "How would you deploy a rate limiter on AWS?"

> "I'd layer it. AWS WAF handles IP-based rate limiting at the edge -- cheap and effective against
> DDoS. API Gateway provides per-API throttling with usage plans for different client tiers.
> For per-user rate limiting with custom rules, I'd run a middleware in ECS that checks
> ElastiCache Redis using Lua scripts for atomic counter operations. Rules are stored in
> DynamoDB with hot-reload via AppConfig. CloudWatch tracks 429 rates and limit utilization."

### "How would you deploy this on GCP?"

> "Cloud Armor for IP-based rate limiting at the edge. Cloud Endpoints or Apigee for
> per-API throttling. For custom rate limiting, Cloud Run services with Memorystore Redis
> for atomic counters. Firestore for rule storage. Cloud Monitoring for 429 tracking."

### "How would you deploy this on Azure?"

> "Azure WAF for IP-based limiting. API Management policies for per-API and per-subscription
> throttling -- Azure APIM has very rich rate limiting policies built in. For custom logic,
> AKS or Container Apps with Azure Cache for Redis. App Configuration for rule hot-reload."

### The Key Interview One-Liner

> "In practice, I'd start with API Gateway's built-in throttling and only build custom rate
> limiting if I need per-user limits, complex rules, or multiple algorithms. Most teams over-engineer
> this -- built-in covers 80% of use cases."

---

## Quick Reference: Which Cloud Service When

| Decision | Choose This | When |
|----------|------------|------|
| WAF vs API Gateway throttling | WAF | IP-based DDoS protection, no per-user logic needed |
| WAF vs API Gateway throttling | API Gateway | Per-API, per-stage, per-client-key throttling |
| Built-in vs Custom | Built-in | Simple per-API throttling, no per-user or per-tenant rules |
| Built-in vs Custom | Custom | Per-user, per-tenant, composite keys, algorithm flexibility |
| Lambda@Edge vs ECS | Lambda@Edge | Simple IP checks at the edge, low/moderate traffic |
| Lambda@Edge vs ECS | ECS | Complex rules, high throughput, Redis Lua scripts |
| DynamoDB vs Redis (counters) | Redis | Hot counters, atomic INCR, sub-ms latency, Lua scripts |
| DynamoDB vs Redis (rules) | DynamoDB | Rule definitions are small, read-heavy, rarely change |
