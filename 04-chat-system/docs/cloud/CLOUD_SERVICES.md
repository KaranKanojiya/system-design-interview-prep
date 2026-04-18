# Chat System — Cloud Service Mapping

## Component-to-Service Mapping

| Component | AWS | GCP | Azure | Notes |
|-----------|-----|-----|-------|-------|
| WebSocket Gateway | API Gateway (WebSocket) / ALB | Cloud Endpoints | SignalR Service | API GW supports WS natively |
| Connection Servers | ECS / EKS | Cloud Run / GKE | AKS | Stateful — need connection registry |
| Message Queue | MSK (Kafka) / Kinesis | Pub/Sub | Event Hubs | Kafka for ordering guarantees |
| Message Storage | Keyspaces (Cassandra) / DynamoDB | Bigtable | Cosmos DB | Write-heavy, time-series |
| Presence/Cache | ElastiCache (Redis) | Memorystore | Azure Cache | TTL for heartbeat, pub/sub for presence |
| User/Group DB | RDS (PostgreSQL) | Cloud SQL | SQL Database | Small relational data |
| Media Storage | S3 + CloudFront | Cloud Storage + CDN | Blob Storage + CDN | Pre-signed URLs |
| Push Notifications | SNS + FCM/APNs | Firebase | Notification Hubs | For offline users |
| Monitoring | CloudWatch | Cloud Monitoring | Azure Monitor | Connection count, msg latency |
| DNS/Routing | Route 53 | Cloud DNS | Traffic Manager | Geo-routing for connection servers |

---

## AWS Reference Architecture

```
                           ┌──────────────────────────────────────────────────┐
                           │                   Route 53                       │
                           │          (Geo-routing to nearest region)         │
                           └────────────────────┬─────────────────────────────┘
                                                │
                           ┌────────────────────▼─────────────────────────────┐
                           │          ALB / API Gateway (WebSocket)           │
                           │       (Sticky sessions OR connection registry)   │
                           └────────┬───────────┬───────────┬─────────────────┘
                                    │           │           │
                    ┌───────────────▼──┐  ┌─────▼────────┐ │  ┌──────────────┐
                    │ EKS Connection   │  │ EKS Connection│ │  │ EKS Connection│
                    │ Server Pod 1     │  │ Server Pod 2  │ │  │ Server Pod N  │
                    │ (50K WS conns)   │  │ (50K WS conns)│ │  │ (50K WS conns)│
                    └───────┬──────────┘  └──────┬────────┘ │  └──────┬───────┘
                            │                    │          │         │
                            └─────────┬──────────┘          │         │
                                      │                     │         │
                    ┌─────────────────▼─────────────────────▼─────────▼───────┐
                    │            ElastiCache (Redis) Cluster                   │
                    │  ┌──────────────────┐  ┌──────────────────────────────┐  │
                    │  │ Connection Registry│  │ Presence (TTL heartbeat)   │  │
                    │  │ user→server map   │  │ user→{status, last_seen}   │  │
                    │  └──────────────────┘  └──────────────────────────────┘  │
                    └─────────────────────────────────────────────────────────┘
                                      │
                    ┌─────────────────▼───────────────────────────────────────┐
                    │              Message Service (EKS — stateless)          │
                    │  Validates, persists, routes to recipient's server      │
                    └─────────────────┬───────────────────────────────────────┘
                                      │
                    ┌─────────────────▼───────────────────────────────────────┐
                    │                  MSK (Kafka)                            │
                    │  Partition key = conversation_id (ordering guarantee)   │
                    │  Topics: messages, presence, receipts, typing           │
                    └─────────────────┬───────────────────────────────────────┘
                                      │
              ┌───────────────────────┼───────────────────────┐
              ▼                       ▼                       ▼
   ┌──────────────────┐  ┌──────────────────────┐  ┌─────────────────────┐
   │ Message Router    │  │ Keyspaces (Cassandra) │  │ S3 + CloudFront    │
   │ (Fan-out worker)  │  │ Message persistence   │  │ Media storage      │
   │ Looks up conn     │  │ PK: conversation_id   │  │ Pre-signed URLs    │
   │ registry, pushes  │  │ CK: timestamp+seq     │  │                    │
   └────────┬─────────┘  └──────────────────────┘  └─────────────────────┘
            │
            ▼
   ┌──────────────────┐
   │ SNS → FCM/APNs   │
   │ (offline users)  │
   └──────────────────┘
```

---

## WebSocket Scaling: Sticky Sessions vs Connection Registry

This is a **critical interview topic**. WebSocket connections are stateful — a message for User B must reach the *specific* server holding B's WebSocket.

### Option 1: Sticky Sessions (ALB)

```
Client ──► ALB (sticky session cookie) ──► always routes to same server
```

| Aspect | Detail |
|--------|--------|
| Pros | Simple, no external registry needed |
| Cons | Uneven load distribution, server failure loses all connections, hard to scale down |
| When | Small scale (<100K connections), prototyping |

### Option 2: Connection Registry (Production approach)

```
Client connects ──► Connection Server registers {userId → serverId} in Redis
Message arrives  ──► Look up Redis → find server → forward via internal channel
```

| Aspect | Detail |
|--------|--------|
| Pros | Any server can route to any user, graceful failover, even load |
| Cons | Extra Redis lookup per message, registry must stay in sync |
| When | Large scale (millions of connections), production systems |

**How the registry works:**
1. Client connects to Connection Server via WebSocket
2. Server writes `user:123 → server:ws-pod-7` to Redis (with TTL = heartbeat interval)
3. To send a message to User 123: look up Redis, find `ws-pod-7`, forward via internal gRPC/Kafka
4. On disconnect: remove entry from Redis
5. On server crash: TTL expires automatically, client reconnects to another server

**Interview insight:** WhatsApp uses a custom Erlang-based connection registry. For an interview, Redis is the standard answer — fast lookups, TTL handles cleanup, pub/sub can broadcast presence changes.

---

## Cost Analysis: WebSocket Connections Are the Big Cost Driver

### The Math

| Metric | Value |
|--------|-------|
| DAU | 500M |
| Peak concurrent connections | ~200M (40% of DAU online simultaneously) |
| Connections per server | 50K (typical for a well-tuned server with 32GB RAM) |
| **Servers needed** | **~4,000 connection servers** (200M / 50K) |
| With 2x headroom for failover | **~8,000-10,000 servers** |

### Cost Breakdown (AWS, rough estimates)

| Resource | Spec | Monthly Cost |
|----------|------|-------------|
| 10,000 connection servers | c5.4xlarge (16 vCPU, 32GB) | ~$5M/month |
| MSK (Kafka) cluster | 30 brokers, kafka.m5.4xlarge | ~$200K/month |
| ElastiCache Redis | 50-node cluster, r6g.2xlarge | ~$150K/month |
| Keyspaces (Cassandra) | On-demand, 40B writes/day | ~$400K/month |
| S3 + CloudFront | Media storage + delivery | ~$300K/month |
| **Total** | | **~$6M/month** |

### Why WebSocket Servers Dominate Cost

- Each WebSocket connection holds ~20KB of memory (buffers, state, TLS context)
- 50K connections per server = ~1GB just for connections + OS overhead + message processing
- Connection servers are **always on** — can't scale to zero like stateless services
- This is why WhatsApp famously ran on Erlang — lightweight processes handle 2M+ connections per server

### Cost Optimization Strategies

1. **Use Erlang/Elixir or Rust** for connection servers (2M+ connections per server vs 50K in Java)
2. **Connection multiplexing** — mobile clients share connections for multiple chats
3. **Aggressive idle timeout** — drop connections after 5 min of inactivity, rely on push notifications
4. **Reserved instances / Savings Plans** — 3-year commitment for 60% savings on always-on servers
5. **Regional deployment** — only deploy connection servers in regions with users

---

## Interview Tip

> "I'd start with **managed WebSocket via API Gateway** for simplicity — it handles connection management, scaling, and TLS termination out of the box. But at WhatsApp scale (500M DAU), I'd move to **custom connection servers on EKS** for three reasons:
> 1. **Cost** — API Gateway charges per message ($1 per million), which is $40K/day at 40B messages
> 2. **Control** — custom connection servers let us optimize memory per connection (Erlang/Rust)
> 3. **Features** — custom servers support connection migration, graceful drain, and binary protocols
>
> The key architectural insight is separating the **stateful connection layer** (must know which user is on which server) from the **stateless message processing layer** (can scale horizontally without coordination)."

---

## Quick Reference: Which Service When

| Scale | WebSocket Layer | Message Queue | Message DB |
|-------|----------------|---------------|------------|
| MVP (<10K users) | API Gateway WebSocket | SQS | DynamoDB |
| Growth (10K-1M) | ALB + ECS Fargate | MSK Serverless | DynamoDB |
| Scale (1M-100M) | ALB + EKS | MSK Provisioned | Keyspaces |
| WhatsApp (500M+) | Custom EKS + Connection Registry | MSK (large cluster) | Self-managed Cassandra |
