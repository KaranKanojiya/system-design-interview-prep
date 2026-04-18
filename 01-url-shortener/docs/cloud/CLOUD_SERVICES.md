# Cloud Services Mapping -- URL Shortener

> Interview-focused reference: map each architectural component to AWS, GCP, and Azure equivalents.

---

## Service Comparison Table

| Component | AWS | GCP | Azure | Notes |
|-----------|-----|-----|-------|-------|
| API Gateway | API Gateway | Cloud Endpoints / Apigee | API Management | Rate limiting, auth |
| Load Balancer | ALB / NLB | Cloud Load Balancing | Application Gateway | L7 for HTTP routing |
| Compute | ECS / EKS / Lambda | Cloud Run / GKE | AKS / Container Apps | Lambda for simple, ECS for full control |
| Database | DynamoDB | Cloud Bigtable / Firestore | Cosmos DB | DynamoDB ideal for key-value |
| Cache | ElastiCache (Redis) | Memorystore | Azure Cache for Redis | Redis for sub-ms reads |
| Message Queue | SQS / Kinesis | Pub/Sub | Event Hubs / Service Bus | Kinesis for analytics stream |
| CDN | CloudFront | Cloud CDN | Azure CDN | Edge caching for redirects |
| DNS | Route 53 | Cloud DNS | Azure DNS | Custom short domain |
| Storage | S3 | Cloud Storage | Blob Storage | Analytics exports |
| Monitoring | CloudWatch | Cloud Monitoring | Azure Monitor | Metrics + alerts |
| Logging | CloudWatch Logs | Cloud Logging | Log Analytics | Centralized logs |

---

## AWS Reference Architecture

```
                          ┌─────────────┐
                          │  Route 53   │
                          │ (short.url) │
                          └──────┬──────┘
                                 │
                          ┌──────▼──────┐
                          │ CloudFront  │  ← Edge-cached 301 redirects
                          │   (CDN)     │
                          └──────┬──────┘
                                 │
                          ┌──────▼──────┐
                          │ API Gateway │  ← Rate limiting, API keys
                          └──────┬──────┘
                                 │
                          ┌──────▼──────┐
                          │     ALB     │  ← Health checks, routing
                          └──────┬──────┘
                                 │
                    ┌────────────┼────────────┐
                    │            │            │
              ┌─────▼─────┐┌────▼────┐┌─────▼─────┐
              │  ECS/EKS  ││ ECS/EKS ││  ECS/EKS  │  ← App containers
              │ (Service) ││(Service)││ (Service) │
              └─────┬─────┘└────┬────┘└─────┬─────┘
                    └────────────┼────────────┘
                           ┌────▼────┐
                           │  Redis  │  ← ElastiCache
                           │ (Cache) │     Sub-ms reads
                           └────┬────┘
                                │ cache miss
                         ┌──────▼──────┐
                         │  DynamoDB   │  ← shortUrl → longUrl
                         │ (Database)  │     On-demand capacity
                         └──────┬──────┘
                                │
                    ┌───────────┼───────────┐
                    │                       │
             ┌──────▼──────┐         ┌──────▼──────┐
             │   Kinesis   │         │     S3      │
             │  (Stream)   │         │  (Exports)  │
             └──────┬──────┘         └─────────────┘
                    │
             ┌──────▼──────┐
             │   Lambda    │  ← Analytics aggregation
             │ (Consumer)  │
             └─────────────┘
```

---

## Cost Considerations

### What Gets Expensive

| Resource | Why It Costs | At Scale (300K reads/sec) |
|----------|-------------|--------------------------|
| **DB Reads** | DynamoDB charges per RCU; 300K/sec = massive throughput | Use DAX or Redis in front |
| **Cache** | ElastiCache nodes run 24/7 regardless of traffic | Right-size; use reserved instances |
| **Data Transfer** | Cross-AZ and outbound internet transfer adds up | Keep services in same AZ when possible |
| **CDN** | CloudFront charges per request + data out | Still cheaper than hitting origin every time |

### What's Cheap

- **Storage**: DynamoDB storage is pennies/GB; S3 even cheaper.
- **Lambda**: Idle costs nothing. Great for analytics consumers.
- **DNS**: Route 53 is ~$0.50/hosted zone/month.
- **API Gateway**: Pay-per-request, very cheap at moderate scale.

### Cost Optimization Tips

1. **Cache aggressively** -- $200/month Redis node saves thousands in DynamoDB RCUs
2. **DynamoDB on-demand** for unpredictable traffic, **provisioned** once patterns stabilize
3. **CDN for redirects** -- 301s can be edge-cached, avoiding origin hits entirely
4. **Reserved instances** for predictable compute (ECS/ElastiCache)

---

## Interview Tips

### "How would you deploy this on AWS?"

> "I'd use DynamoDB for the key-value store since it's purpose-built for this access pattern --
> single-digit ms latency at any scale with on-demand capacity. ElastiCache Redis in front
> handles the 100:1 read-write ratio. ECS Fargate for the API servers -- no EC2 management.
> CloudFront caches 301 redirects at the edge. Kinesis streams click events to Lambda
> for async analytics. Route 53 for the custom short domain."

### "How would you deploy this on GCP?"

> "Cloud Run for the API tier -- it scales to zero and handles bursty traffic well.
> Bigtable for the key-value store at scale, or Firestore if we want simpler operations.
> Memorystore for Redis caching. Cloud CDN in front for cached redirects.
> Pub/Sub for the analytics event stream with Cloud Functions as consumers."

### "How would you deploy this on Azure?"

> "Container Apps for the API servers -- Kubernetes-based but managed.
> Cosmos DB with the Table API for the key-value store -- it gives us global distribution
> if we ever need multi-region. Azure Cache for Redis for the caching layer.
> Azure CDN for edge caching. Event Hubs for the analytics stream."

### Universal Points to Mention

- **Multi-region**: For a URL shortener, eventual consistency across regions is acceptable (AP system)
- **Auto-scaling**: All three clouds support auto-scaling compute and DB throughput
- **Infrastructure as Code**: Mention Terraform / CloudFormation / Pulumi -- shows maturity
- **Observability**: Always mention monitoring, logging, and alerting as part of the deployment

---

## Serverless vs Containers

| Aspect | Serverless (Lambda/Cloud Run) | Containers (ECS/GKE/AKS) |
|--------|-------------------------------|---------------------------|
| **Cold start** | 100-500ms (bad for redirects) | None after startup |
| **Cost at low traffic** | Near zero | Fixed cost for running instances |
| **Cost at high traffic** | Can get expensive | More predictable, cheaper at scale |
| **Scaling speed** | Seconds | Minutes (unless pre-warmed) |
| **Operational overhead** | Minimal | Medium (cluster management) |
| **Connection pooling** | Hard (stateless functions) | Easy (long-lived processes) |
| **Best for** | Analytics consumers, cron jobs | The redirect API itself |

### Recommendation for URL Shortener

**Hybrid approach**: Containers for the hot path (redirect API needs sub-10ms, no cold starts),
serverless for the cold path (analytics aggregation, cleanup jobs, reporting).

```
Hot path:  Client → CDN → ALB → ECS (containers) → Redis → DynamoDB
Cold path: DynamoDB Stream → Lambda → S3 (analytics)
```

> **Interview one-liner**: "Containers for the redirect path because cold starts are unacceptable
> at 300K reads/sec. Serverless for everything async -- analytics, cleanup, reporting."

---

## Quick Reference: Which Cloud Service When

| Decision | Choose This | When |
|----------|------------|------|
| DynamoDB vs Aurora | DynamoDB | Simple key-value, no joins, no transactions needed |
| Redis vs DAX | Redis | Need flexibility beyond just DynamoDB caching |
| Lambda vs ECS | ECS | Latency-sensitive hot path with sustained traffic |
| Lambda vs ECS | Lambda | Bursty async workloads, event-driven processing |
| SQS vs Kinesis | Kinesis | Need ordered events, replay, multiple consumers |
| CloudFront vs skip | CloudFront | 301 redirects are highly cacheable, huge read ratio |
