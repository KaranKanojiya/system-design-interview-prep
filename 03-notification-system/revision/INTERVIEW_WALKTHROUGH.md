# Interview Walkthrough — Notification System

> Target: 35-minute system design interview. Phase timings are guidelines.

---

## Phase 1: Clarify Requirements (2-3 min)

**Ask these questions before designing anything:**

| Question | Expected Answer | Why It Matters |
|----------|----------------|----------------|
| Which channels? | Push, Email, SMS, In-App | Determines handler count and provider integration |
| Priority levels? | CRITICAL (OTP), HIGH, MEDIUM, LOW | Queue topology decision |
| User preferences? | Opt-in/out, quiet hours, frequency caps | Adds preference service to architecture |
| Scale? | ~1B notifications/day | Drives Kafka vs SQS, DB choice |
| Latency SLA? | OTP < 5s, marketing < minutes | Separate queues for priority |
| Delivery guarantee? | At-least-once acceptable | Simplifies design vs exactly-once |
| Batch support? | Yes, send to millions at once | Fan-out strategy needed |

**Say out loud:**
> "I want to confirm: we need a multi-channel notification system with priority-based delivery, user preferences, and at-least-once guarantees at 1B messages/day scale."

---

## Phase 2: Traffic Estimation (2-3 min)

```
Daily volume:     1B notifications/day
Per second:       ~12K/sec average, ~50K/sec peak

Channel breakdown (typical):
  Push:    40%  = 400M/day
  Email:   35%  = 350M/day
  In-App:  20%  = 200M/day
  SMS:      5%  =  50M/day

Storage (notifications DB):
  1 notification ~ 500 bytes
  1B/day x 500B = 500 GB/day
  30-day retention = 15 TB

Queue throughput:
  Kafka: 12K msgs/sec sustained, 50K peak
  ~10 partitions per priority topic (4 topics = 40 partitions)

Cache:
  100M users x 200B preferences = 20 GB Redis
  10K templates x 5 KB = 50 MB (trivial)
```

---

## Phase 3: API Design (2 min)

```
POST /api/v1/notifications/send
{
  "userId": "u123",
  "templateId": "otp_login",
  "channel": "SMS",
  "priority": "CRITICAL",
  "params": {"code": "482910"},
  "idempotencyKey": "req-abc-123"
}
Response: 202 Accepted { "notificationId": "n456", "status": "QUEUED" }

POST /api/v1/notifications/batch
{
  "segmentId": "seg-promo-2026",
  "templateId": "spring_sale",
  "channel": "EMAIL",
  "priority": "LOW"
}

GET  /api/v1/notifications/{id}/status
PUT  /api/v1/users/{id}/preferences
GET  /api/v1/users/{id}/notifications?page=1&size=20
```

**Key point:** Return **202 Accepted** (not 200) — processing is async.

---

## Phase 4: High-Level Architecture (5-7 min) — CORE

**This is where you spend the most time. Draw this on the whiteboard:**

```
┌────────┐     ┌──────────────┐     ┌─────────────┐     ┌──────────────┐
│ Client │────►│ API Gateway  │────►│ Notification │────►│  Preference  │
│        │     │ (Rate Limit) │     │   Service    │     │   Service    │
└────────┘     └──────────────┘     └──────┬───────┘     │  (Redis+DB)  │
                                           │             └──────────────┘
                                           ▼
                                   ┌───────────────┐
                                   │     Kafka      │
                                   ├───────────────┤
                                   │ T: critical    │ ◄── dedicated consumers
                                   │ T: high        │
                                   │ T: medium      │
                                   │ T: low         │
                                   └───────┬───────┘
                                           │
                          ┌────────────────┼────────────────┐
                          ▼                ▼                 ▼
                   ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
                   │ Push Worker │ │ Email Worker│ │ SMS Worker  │
                   │  (FCM/APNs)│ │   (SES)     │ │  (Twilio)   │
                   └──────┬──────┘ └──────┬──────┘ └──────┬──────┘
                          │               │                │
                          ▼               ▼                ▼
                   ┌──────────────────────────────────────────┐
                   │         Delivery Tracker                  │
                   │    (Status → DynamoDB, Metrics)           │
                   └──────────────────────────────────────────┘
                          │
                   Failed (max retries exceeded)
                          ▼
                   ┌──────────────┐
                   │     DLQ      │──► Alert + Manual Review
                   └──────────────┘
```

**Walk through the flow:**
1. Client calls API with notification request
2. Notification Service validates, checks user preferences, renders template
3. If user allows: enqueue to correct priority Kafka topic
4. Channel-specific worker consumes, calls external provider
5. Delivery status tracked. Failures retried with exponential backoff.
6. After max retries: route to DLQ, alert ops team.

---

## Phase 5: Channel Handlers Deep Dive (3-4 min)

| Channel | Provider | Key Challenge | Batch Support |
|---------|----------|---------------|---------------|
| Push | FCM (Android/Web), APNs (iOS) | Token management, device registration refresh | FCM: 500/batch |
| Email | AWS SES | Bounce handling, spam score, warmup IP reputation | SES: 50/batch |
| SMS | Twilio | Highest cost, carrier rate limits, country regulations | Limited |
| In-App | Internal (write to DB) | Pagination, read/unread state, WebSocket for real-time | Bulk insert |

**Strategy Pattern in action:**

```java
interface NotificationHandler {
    DeliveryResult send(Notification notification);
    boolean supports(Channel channel);
}

class PushHandler implements NotificationHandler { ... }
class EmailHandler implements NotificationHandler { ... }
class SmsHandler implements NotificationHandler { ... }
class InAppHandler implements NotificationHandler { ... }
```

Each handler is independent and pluggable. Adding a new channel = add a new handler class.

---

## Phase 6: Preferences & Templates (3 min)

**Preference check is a gate before sending:**

```
shouldSend(userId, channel, priority):
  1. Is user opted-in for this channel?          → No: drop
  2. Is it quiet hours in user's timezone?        → Yes + not CRITICAL: delay
  3. Has user exceeded frequency cap for today?   → Yes: drop or digest
  4. Is notification category blocked by user?    → Yes: drop
  → All passed: proceed to send
```

**Template rendering:**

```
Template: "Hi {{name}}, your OTP is {{code}}. Valid for {{ttl}} minutes."
Params:   {"name": "Karan", "code": "482910", "ttl": "5"}
Output:   "Hi Karan, your OTP is 482910. Valid for 5 minutes."
```

- Templates stored in DB, cached in Redis (5-min TTL)
- Immutable versions — notification references specific version ID
- Supports per-channel variants (email HTML vs SMS plain text)

---

## Phase 7: Retry & Reliability (3-4 min)

**Retry strategy:**

```
Attempt 1:  immediate
Attempt 2:  1s  + jitter
Attempt 3:  4s  + jitter
Attempt 4:  16s + jitter
Attempt 5:  64s + jitter
→ Max retries exceeded → DLQ
```

**Classify failures:**

| Type | Example | Action |
|------|---------|--------|
| Transient | Provider 503, timeout | Retry with backoff |
| Permanent | Invalid device token, bounced email | DLQ immediately, no retry |
| Rate limited | Provider 429 | Backoff per provider rate limit |

**Idempotency:**
- Key = hash(userId + templateId + params + 1-hour window)
- Stored in Redis with 1-hour TTL
- Check before enqueue AND before send (belt and suspenders)

**At-least-once guarantee:**
- Kafka consumer commits offset only AFTER successful provider call
- If worker crashes mid-send: message redelivered, dedup catches duplicate

---

## Phase 8: Scaling & Batch (3-4 min)

**Kafka scaling:**
- Partitions per topic: start with 10, scale to 100+ for high-traffic topics
- Partition key: userId (ensures ordering per user)
- Consumer groups: one group per channel per priority (e.g., `push-critical-cg`)
- Auto-scaling workers based on consumer lag metric

**Batch send to 10M users:**

```
1. Batch API receives segmentId + templateId
2. Segment resolved to user list (from user service)
3. Chunk into 1,000-user segments
4. Enqueue each chunk as one Kafka message
5. Worker expands chunk → per-user preference check → individual sends
6. Progress tracked: 10M / completedCount
```

**Backpressure:** If Kafka lag > threshold, stop accepting LOW priority batches. CRITICAL always accepted.

---

## Phase 9: Tradeoffs & CAP (2 min)

| Topic | Decision | Rationale |
|-------|----------|-----------|
| CAP | AP (Available + Partition Tolerant) | Sending a duplicate is better than dropping a notification |
| Delivery | At-least-once + dedup | Exactly-once adds unacceptable latency at 1B/day |
| Queue | Kafka over SQS | Higher throughput, topic-based routing, replay capability |
| Notification DB | DynamoDB over PostgreSQL | Write-heavy, time-series, horizontal scale |
| Preference DB | PostgreSQL over DynamoDB | Small dataset, complex queries, joins |

---

## Red Flags (Avoid These)

- Designing a synchronous system (call provider in API request path)
- Single queue for all priorities (CRITICAL blocked by marketing backlog)
- No mention of retry or failure handling
- Ignoring user preferences
- No rate limiting or backpressure
- Storing notifications in a relational DB at 1B/day scale

## Green Flags (Interviewer Wants to Hear)

- Separate queues per priority with dedicated consumers
- Strategy pattern for channel handlers (extensible)
- Preference check as a gate before sending
- Idempotency keys for dedup
- Circuit breaker for external provider failures
- Fan-out chunking for batch sends
- DLQ with alerting for failed notifications
- 202 Accepted (async processing)

---

## 30-Second Elevator Pitch

> "The system receives notification requests via a REST API, validates them, checks user preferences and quiet hours, then enqueues to priority-specific Kafka topics. Channel-specific workers consume messages using the Strategy pattern — each handler talks to its provider: FCM for push, SES for email, Twilio for SMS. We use at-least-once delivery with Redis-based idempotency dedup. Failures retry with exponential backoff and eventually route to a DLQ. For batch sends to millions of users, we chunk the audience and fan out through the same pipeline. The system handles 1B notifications/day with separate scaling per channel and priority."
