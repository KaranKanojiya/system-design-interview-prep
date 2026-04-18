# Cloud Services Mapping — Notification System

## Component-to-Service Mapping

| Component | AWS | GCP | Azure | Notes |
|-----------|-----|-----|-------|-------|
| Push Notifications | SNS + FCM/APNs | Firebase Cloud Messaging | Notification Hubs | FCM for Android/Web, APNs for iOS |
| Email | SES | (3rd party: SendGrid) | Communication Services | SES cheapest at scale |
| SMS | SNS / Pinpoint | (3rd party: Twilio) | Communication Services | Twilio for global reach |
| Message Queue | SQS / Kinesis / MSK | Pub/Sub | Event Hubs / Service Bus | MSK (Managed Kafka) for high throughput |
| Compute | ECS / EKS / Lambda | Cloud Run / GKE | AKS / Container Apps | Workers on ECS, Lambda for low-volume |
| Notification DB | DynamoDB / Keyspaces | Bigtable / Firestore | Cosmos DB | Time-series, write-heavy |
| Preferences DB | RDS (PostgreSQL) | Cloud SQL | SQL Database | Relational, small dataset |
| Cache | ElastiCache (Redis) | Memorystore | Azure Cache | Templates, preferences, dedup |
| Scheduler | EventBridge Scheduler | Cloud Scheduler | Logic Apps | Delayed/scheduled notifications |
| Monitoring | CloudWatch | Cloud Monitoring | Azure Monitor | DLQ depth alerts critical |
| Dead Letter Queue | SQS DLQ | Pub/Sub DLQ | Service Bus DLQ | Auto-routing on max retries |

---

## AWS Reference Architecture

```
                         ┌─────────────────────────────────────────────────┐
                         │                  AWS Cloud                      │
                         │                                                 │
  Client ──► API Gateway ──► Lambda / ECS ──┬── Preference Check ◄── RDS  │
                         │    (Ingestion)    │   (Redis Cache)             │
                         │                   ▼                             │
                         │           ┌───────────────┐                     │
                         │           │  SQS / MSK    │                     │
                         │           │  (by priority) │                     │
                         │           ├───────────────┤                     │
                         │           │ CRITICAL topic │                     │
                         │           │ HIGH topic     │                     │
                         │           │ MEDIUM topic   │                     │
                         │           │ LOW topic      │                     │
                         │           └──────┬────────┘                     │
                         │                  ▼                              │
                         │        ┌─────────────────┐                     │
                         │        │  ECS Workers /   │                     │
                         │        │  Lambda (per ch) │                     │
                         │        └──┬───┬───┬───┬──┘                     │
                         │           │   │   │   │                        │
                         │           ▼   ▼   ▼   ▼                        │
                         │         SES  SNS  ──  DynamoDB                 │
                         │        (Email)(Push)  (In-App)                 │
                         │              │                                  │
                         │              ▼                                  │
                         │           Twilio                                │
                         │           (SMS)                                 │
                         │                                                 │
                         │  Failures ──► SQS DLQ ──► CloudWatch Alarm     │
                         │  Status  ──► DynamoDB (delivery tracking)      │
                         └─────────────────────────────────────────────────┘
```

---

## Serverless Option (Low-to-Medium Scale)

```
API Gateway ──► Lambda (validate + enqueue)
                    │
                    ▼
               SQS (per priority)
                    │
                    ▼
            Lambda Workers (per channel)
               │       │       │
               ▼       ▼       ▼
             SES     FCM    Twilio
```

**When to use:** < 100K notifications/day, cost-sensitive, bursty traffic.
**When NOT to use:** Sustained high throughput (Lambda cold starts + 15-min timeout).

---

## Cost Analysis (per 1,000 notifications)

| Channel | Service | Cost / 1K | Notes |
|---------|---------|-----------|-------|
| Email | SES | ~$0.10 | Cheapest channel at scale |
| Push | FCM/APNs | Free | No per-message cost from FCM |
| SMS | SNS/Twilio | ~$7.50 | Varies by country; US avg ~$0.0075/msg |
| In-App | DynamoDB write | ~$0.00125 | 1 WCU per msg, negligible |

**Key insight for interviews:** Email and push are nearly free at scale. SMS dominates cost. A system sending 1B notifications/day with 10% SMS spends ~$750K/month on SMS alone.

**Cost optimization strategies:**
- Batch SMS where possible (digest mode)
- Use push as preferred channel over SMS when device token available
- SES dedicated IPs ($24.95/mo each) needed above 50K emails/day for deliverability
- Reserved capacity for DynamoDB vs on-demand (40% savings at steady state)

---

## Managed Services vs Custom — Interview Talking Point

| Aspect | Managed (SES, FCM, SNS) | Custom (self-hosted SMTP, etc.) |
|--------|--------------------------|----------------------------------|
| Time to market | Days | Weeks-months |
| Ops overhead | Low (provider handles scaling) | High (you manage infra) |
| Cost at scale | Higher per-unit | Lower per-unit |
| Flexibility | Limited by provider API | Full control |
| Deliverability | Provider reputation shared | Own IP reputation |

**Interview answer pattern:**
> "Start with managed services for speed. As we hit scale thresholds — typically 1M+ messages/day for a single channel — evaluate self-hosted for cost. Email is the first candidate because SMTP infrastructure is well-understood. Push always stays managed (FCM/APNs are mandatory gatekeepers). SMS almost always stays with aggregators like Twilio."

---

## Quick Reference: Which AWS Services for Which Scale

| Scale | Queue | Compute | DB |
|-------|-------|---------|-----|
| < 100K/day | SQS | Lambda | DynamoDB on-demand |
| 100K - 10M/day | SQS + SNS fan-out | ECS Fargate | DynamoDB provisioned |
| 10M - 1B/day | MSK (Kafka) | ECS on EC2 | DynamoDB + DAX cache |
| > 1B/day | MSK multi-cluster | EKS | Keyspaces (Cassandra) |
