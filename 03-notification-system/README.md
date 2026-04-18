# Notification System — Interview Prep

## Problem Summary

Design a multi-channel notification system supporting **push, email, SMS, and in-app** delivery with user preferences, message templates, priority-based queuing, retry with backoff, and end-to-end delivery tracking. Target: **1 billion notifications/day**.

---

## 1-Minute Interview Revision

- **1B notifications/day** across 4 channels (push, email, SMS, in-app)
- **Priority queue:** CRITICAL > HIGH > MEDIUM > LOW (separate Kafka topics)
- **User preferences:** opt-in/out per channel, quiet hours, frequency caps
- **Template engine** for consistent messaging (Builder pattern)
- **Retry** with exponential backoff + jitter, DLQ for permanent failures
- **At-least-once delivery** (AP system) — dedup via idempotency keys in Redis
- **Channel handlers:** Strategy pattern — each handler talks to its provider (FCM, SES, Twilio)
- **Fan-out:** Batch to 10M users chunked into segments, each enqueued separately
- **Tracking:** Delivery status per notification stored in time-series DB

---

## Architecture Summary

```
  Client ──► API Gateway ──► Notification Service ──► Preference Check (Redis/DB)
                                     │
                                     ▼
                              ┌──────────────┐
                              │  Kafka / SQS  │  (topics per priority)
                              └──────┬───────┘
                                     │
                    ┌────────────────┼────────────────┐
                    ▼                ▼                 ▼
              Push Worker      Email Worker      SMS Worker
              (FCM/APNs)         (SES)           (Twilio)
                    │                │                 │
                    ▼                ▼                 ▼
              Delivery Tracker ──► Notification DB (DynamoDB)
                                        │
                              Failed ──► DLQ ──► Retry / Alert
```

---

## Key Components (One-Liner Each)

| Component | Purpose |
|-----------|---------|
| Notification Service | Validates request, checks preferences, enqueues to correct priority topic |
| Priority Router | Routes to CRITICAL/HIGH/MEDIUM/LOW Kafka topics |
| Channel Handler | Strategy pattern — pluggable handler per channel (Push/Email/SMS/InApp) |
| Template Engine | Renders message body from template + variables |
| Preference Service | Checks opt-in, quiet hours, frequency caps before sending |
| Retry Manager | Exponential backoff with jitter, routes to DLQ after max retries |
| Delivery Tracker | Records sent/delivered/failed status per notification |
| Rate Limiter | Per-user and per-channel rate limits to prevent fatigue |
| Dedup Service | Redis-based idempotency key check to prevent duplicate sends |
| Batch Processor | Chunks large audiences into segments for parallel processing |

---

## Key Tradeoffs

| Decision | Option A | Option B | Our Choice | Why |
|----------|----------|----------|-------------|-----|
| Delivery model | Push to device | Pull (poll) | Push (email/SMS/push), Pull (in-app) | Push for urgency, pull for in-app feed |
| Delivery guarantee | At-least-once | Exactly-once | At-least-once + dedup | Exactly-once too expensive at 1B/day |
| Batch fan-out | Fan-out on write | Fan-out on read | Fan-out on write | Pre-compute per user for channel routing |
| Queue topology | Single priority queue | Separate queue per priority | Separate per priority | CRITICAL never blocked by LOW backlog |
| Processing | Synchronous | Asynchronous | Async via queue | Decouple ingestion from delivery |
| Preference storage | Relational (PostgreSQL) | NoSQL | Relational | Small dataset, complex queries, ACID |
| Notification storage | Relational | NoSQL (DynamoDB) | NoSQL | Write-heavy, time-series, massive scale |

---

## Design Patterns

| Pattern | Where Used |
|---------|-----------|
| **Strategy** | Channel handlers (Push/Email/SMS/InApp) — swap delivery logic |
| **Observer** | Delivery status callbacks trigger tracking updates |
| **Builder** | Notification object construction with optional fields |
| **Template Method** | Base handler defines send flow, subclasses implement channel specifics |
| **Factory** | Create correct channel handler based on notification type |
| **Producer-Consumer** | API produces to queue, workers consume and deliver |
| **Repository** | Data access abstraction for notifications and preferences |

---

## CAP Summary

- **Partition tolerant + Available (AP)** — we choose availability over consistency
- A notification sent twice is acceptable (idempotent dedup mitigates)
- A notification never sent is NOT acceptable for CRITICAL priority
- Preferences can be eventually consistent (short TTL cache)

---

## Tech Stack Summary

| Layer | Technology |
|-------|-----------|
| API | Spring Boot (REST) |
| Queue | Kafka (MSK) / SQS |
| Workers | Spring Boot consumers on ECS |
| Push | FCM (Android/Web), APNs (iOS) |
| Email | AWS SES |
| SMS | Twilio |
| Notification DB | DynamoDB |
| Preferences DB | PostgreSQL (RDS) |
| Cache | Redis (ElastiCache) |
| Monitoring | CloudWatch + PagerDuty |

---

## Common Interview Follow-Up Questions

1. **How to handle millions of push notifications at once?**
   Fan-out into chunks (5K-10K per batch), enqueue each chunk, parallel workers with FCM batch API (up to 500/request).

2. **How to ensure OTP arrives within 5 seconds?**
   CRITICAL priority topic with dedicated consumers, skip preference/quiet-hour checks, direct-to-provider with no batching.

3. **How to prevent notification fatigue?**
   Per-user frequency caps (e.g., max 5 marketing/day), digest mode for non-urgent, channel preference respect.

4. **What if FCM/APNs is down?**
   Circuit breaker pattern, queue messages for retry, fallback to alternate channel (e.g., SMS for critical push failures).

5. **How to handle timezone for quiet hours?**
   Store user timezone in preferences, convert to UTC at check time, delay delivery until quiet window ends.

6. **How to deduplicate notifications?**
   Idempotency key (hash of userId + templateId + params + timeWindow) stored in Redis with TTL.

7. **Push vs pull for in-app notifications?**
   Pull with polling or WebSocket for real-time. Store in DB, client fetches paginated feed.

8. **How to prioritize OTP over marketing notifications?**
   Separate Kafka topics per priority. CRITICAL consumers have dedicated resources, never share with LOW.

9. **How to handle unsubscribe across channels?**
   Central preference service, unsubscribe updates propagate to cache. All handlers check before sending.

10. **How to track delivery rates?**
    Webhook callbacks from providers (SES/FCM), status written to DynamoDB, aggregate metrics in CloudWatch.

11. **How to handle batch notifications to 10M users?**
    Segment into 1K-user chunks, enqueue each chunk as a message, workers expand and send individually. Use Kafka for backpressure.

12. **How to implement notification preferences at scale?**
    PostgreSQL for source of truth, Redis cache with 5-min TTL, async invalidation on preference update.

13. **What happens when the queue backs up?**
    Auto-scaling consumer groups, alert on lag > threshold, shed LOW priority traffic first.

14. **How to handle template versioning?**
    Immutable template versions, notification references version ID, old versions kept for audit.

---

## How to Run

```bash
cd 03-notification-system && ../gradlew run
```

---

## What to Improve Later

- [ ] Multi-region delivery for global latency reduction
- [ ] A/B testing framework for notification content
- [ ] ML-based optimal send-time prediction
- [ ] Rich push notifications (images, action buttons)
- [ ] Webhook-based delivery to third-party systems
- [ ] Analytics dashboard for open/click rates
- [ ] GDPR compliance — data retention and right-to-delete
