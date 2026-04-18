# Technology Choices: Notification System

> Every technology, why it was chosen, alternatives considered, and how to discuss it in interviews.

---

## Technology Map

```
  +-------------+      +-------------------+      +-------------------+
  |  API Layer  | ---> |  Message Broker    | ---> |  Channel Workers  |
  |  (REST)     |      |  (Kafka)           |      |  (per-channel)    |
  +-------------+      +-------------------+      +-------------------+
       |                       |                         |
       v                       v                         v
  +---------+           +-----------+            +--------------+
  | Redis   |           | Cassandra |            | External APIs|
  | (cache, |           | (notif    |            | FCM, SES,    |
  |  dedup, |           |  logs)    |            | Twilio       |
  |  rate   |           +-----------+            +--------------+
  |  limit) |           | PostgreSQL|
  +---------+           | (prefs,   |
                        |  templates)|
                        +-----------+
```

---

## 1. Apache Kafka -- The Core Message Broker

### Why Kafka

| Requirement | Kafka Capability |
|------------|-----------------|
| High throughput (millions of notifications/day) | Millions of messages/sec per topic |
| Durability (no lost notifications) | Replicated log, configurable retention |
| Replay (reprocess failed batch) | Consumer can seek to any offset |
| Per-channel scaling | Consumer groups per channel |
| Ordering (per-user) | Partition key = userId |
| Priority handling | Separate topics per priority level |

### Topic Design

```
  notifications.high     --> OTP, 2FA, payment alerts
  notifications.medium   --> order updates, shipping
  notifications.low      --> marketing, digests, newsletters

  delivery-events        --> SENT, DELIVERED, FAILED, BOUNCED
  dlq.notifications      --> Dead Letter Queue for failed messages
```

### Consumer Group Design

```
  Topic: notifications.high
    |
    +-- consumer-group: push-workers    (3 instances)
    +-- consumer-group: email-workers   (5 instances)
    +-- consumer-group: sms-workers     (2 instances)
    +-- consumer-group: inapp-workers   (2 instances)

  Each group independently consumes ALL messages.
  Fan-out: one notification can trigger multiple channels.
  Scaling: add more instances to any group independently.
```

### Kafka Configuration Interview Points

```
  acks = all                   --> no message loss (wait for all replicas)
  replication.factor = 3       --> survive 2 broker failures
  min.insync.replicas = 2      --> block writes if < 2 replicas alive
  enable.idempotence = true    --> producer-side dedup
  max.poll.records = 100       --> control batch size per consumer poll
  auto.offset.reset = earliest --> new consumers start from beginning
```

### Alternatives Comparison

| Feature | Kafka | Amazon SQS | RabbitMQ |
|---------|-------|-----------|----------|
| Throughput | Very high (millions/s) | High (auto-scales) | Moderate |
| Message replay | Yes (offset seek) | No (message deleted after consume) | No (unless DLX) |
| Ordering | Per-partition | FIFO queues only | Per-queue |
| Fan-out | Consumer groups | SNS + SQS | Exchange routing |
| Ops complexity | High (ZooKeeper/KRaft) | Zero (managed) | Moderate |
| Cost at scale | Lower (self-hosted) | Pay per request | Moderate |
| **Best for** | **Event streaming, high volume** | **Simple queues, AWS-native** | **Complex routing rules** |

### Interview Talking Point

> "We chose Kafka because we need replay capability for reprocessing, per-partition ordering
> for user-level consistency, and consumer groups for independent per-channel scaling. SQS
> would be simpler but lacks replay -- if a consumer bug corrupts a batch of notifications,
> we cannot reprocess from Kafka offsets with SQS."

---

## 2. Push: FCM + APNs

### Architecture

```
  PushNotificationHandler
       |
       +-- Android / Web --> Firebase Cloud Messaging (FCM)
       |                     - HTTP v1 API
       |                     - Topic messaging for broadcast
       |                     - Supports data + notification payloads
       |
       +-- iOS -------------> Apple Push Notification Service (APNs)
                              - HTTP/2 connection
                              - JWT or certificate auth
                              - Alert vs silent push
```

### Key Challenges

| Challenge | Solution |
|-----------|----------|
| **Token management** | Store device tokens in Redis (24h TTL) + DB. Invalidate on 410 (unregistered) from FCM/APNs. |
| **Multi-device** | One user, multiple devices. Fan-out: send to ALL registered tokens for that user. |
| **Silent vs alert** | Silent push for data sync (no user-visible notification). Alert for user-facing messages. |
| **Payload size** | FCM: 4KB limit. APNs: 4KB. Keep payloads small; deep link to app for details. |
| **Rate limits** | FCM: no hard limit but throttles. APNs: undocumented. Use exponential backoff on 429. |

### Interview Talking Point

> "Push is the trickiest channel because of device token lifecycle. Tokens change when users
> reinstall the app or clear data. We cache tokens in Redis with a 24-hour TTL and invalidate
> immediately when FCM or APNs returns a 410 (device unregistered). For multi-device users,
> we fan out to every registered token."

---

## 3. Email: Amazon SES / SendGrid

### Architecture

```
  EmailNotificationHandler
       |
       +-- Render HTML template (Handlebars)
       +-- Attach headers (List-Unsubscribe, X-Priority)
       +-- Send via SES API (or SendGrid REST API)
       +-- Process webhooks: bounce, complaint, open, click
```

### SES vs SendGrid

| Feature | Amazon SES | SendGrid |
|---------|-----------|----------|
| Cost | ~$0.10 per 1K emails | ~$0.50-1.00 per 1K |
| Deliverability tracking | Basic (SNS notifications) | Excellent (dashboard, webhooks) |
| Template support | Basic | Rich (visual editor) |
| Warm-up required | Yes (new accounts throttled) | Less aggressive |
| AWS integration | Native | Requires setup |
| **Best for** | High-volume, cost-sensitive | Deliverability-critical |

### Deliverability: DKIM / SPF / DMARC

```
  DNS Records Required:
  =====================

  SPF:   TXT "v=spf1 include:amazonses.com ~all"
         --> Authorizes SES to send on behalf of your domain

  DKIM:  CNAME records (3x) provided by SES
         --> Cryptographic signature proving email was not tampered

  DMARC: TXT "_dmarc.example.com" "v=DMARC1; p=quarantine; rua=..."
         --> Policy for handling SPF/DKIM failures

  Without these: emails land in spam. Period.
```

### Interview Talking Point

> "For email, deliverability is everything. We use SES for cost efficiency and configure DKIM,
> SPF, and DMARC on day one. We process bounce and complaint webhooks to maintain sender
> reputation -- if an address hard-bounces, we mark it and never send again. A poor sender
> reputation means ALL your emails go to spam, not just the bounced ones."

---

## 4. SMS: Twilio / Amazon SNS

### Architecture

```
  SMSNotificationHandler
       |
       +-- Format phone to E.164: +1234567890
       +-- Check country routing table
       +-- Send via Twilio REST API (or SNS Publish)
       +-- Process delivery receipt callback
```

### Twilio vs SNS

| Feature | Twilio | Amazon SNS |
|---------|--------|-----------|
| Global coverage | 180+ countries | Limited regions |
| Number management | Local numbers, short codes, toll-free | Long codes only |
| Cost (US) | ~$0.0079/msg | ~$0.00645/msg |
| Cost (India) | ~$0.04/msg | ~$0.02/msg |
| Delivery receipts | Real-time webhooks | CloudWatch (delayed) |
| Two-way SMS | Yes | Limited |
| **Best for** | **Global, feature-rich** | **AWS-native, US-focused** |

### Cost Awareness (Interview Gold)

```
  SMS is the most EXPENSIVE channel by far:

  Channel       Cost per 1M messages
  ---------     --------------------
  Push (FCM)    Free
  Email (SES)   ~$100
  In-App (WS)   ~$0 (infra only)
  SMS (Twilio)  ~$7,500 (US)
                ~$40,000 (international avg)

  This is why SMS should be reserved for:
  - OTP / 2FA (no alternative)
  - Critical alerts (account security)
  - Users who explicitly opted in

  Marketing via SMS = burning money.
```

### Interview Talking Point

> "SMS is 75x more expensive than email. We use it only for OTP and critical alerts. For
> international routing, we use Twilio because of its coverage in 180+ countries. We also
> implement SMS-specific rate limiting -- no user gets more than 5 SMS per day unless they
> are OTP messages."

---

## 5. In-App: WebSocket / SSE

### Architecture

```
  InAppNotificationHandler
       |
       +-- Check: is user currently connected?
       |     |
       |     +-- YES --> push via WebSocket immediately
       |     |
       |     +-- NO  --> store in DB, deliver on next connection
       |
       +-- Always: persist to notification_inbox table
       +-- Update unread count badge

  Client Connection:
  ==================
  1. WebSocket (preferred): full-duplex, real-time
  2. Server-Sent Events (SSE): simpler, one-way, good enough
  3. Long polling (fallback): for legacy clients
```

### WebSocket vs SSE vs Polling

| Feature | WebSocket | SSE | Long Polling |
|---------|----------|-----|-------------|
| Direction | Bidirectional | Server-to-client | Server-to-client |
| Connection | Persistent | Persistent | Repeated requests |
| Latency | Real-time | Real-time | 1-30 second delay |
| Browser support | Universal | Universal (except IE) | Universal |
| Scalability | Connection-heavy | Connection-heavy | Request-heavy |
| Load balancer | Needs sticky sessions or WS-aware LB | Standard HTTP | Standard HTTP |
| **Best for** | **Chat, interactive** | **Notification feeds** | **Simple fallback** |

### Interview Talking Point

> "In-app is the only channel we fully control. We use WebSockets for real-time delivery
> with SSE as a fallback. The key design decision is that we ALWAYS persist to the database,
> even if the user is online, because the notification inbox is a permanent feature -- users
> scroll back through past notifications. The WebSocket is just an optimization for instant
> delivery."

---

## 6. Database: Cassandra + PostgreSQL

### Dual Database Strategy

```
  +------------------+                    +------------------+
  | Cassandra        |                    | PostgreSQL       |
  +------------------+                    +------------------+
  | Notification logs|                    | User preferences |
  | Delivery events  |                    | Templates        |
  | Audit trail      |                    | Channel configs  |
  +------------------+                    +------------------+
  | Write-heavy      |                    | Read-heavy       |
  | Time-series      |                    | Relational       |
  | TTL (90 days)    |                    | ACID required    |
  | No joins needed  |                    | Joins needed     |
  | Partition by     |                    | Small dataset    |
  |   userId + month |                    |                  |
  +------------------+                    +------------------+
```

### Why Cassandra for Notifications

| Requirement | Cassandra Fit |
|------------|---------------|
| Millions of writes/day | Linear write scaling |
| Time-series data (notifications have timestamps) | Native TTL, time-based partitioning |
| No complex queries (just "get user's notifications") | Partition key = userId, clustering = timestamp |
| 90-day retention | TTL at row level |
| Multi-region | Tunable consistency per query |

### Why PostgreSQL for Preferences

| Requirement | PostgreSQL Fit |
|------------|---------------|
| Strong consistency (opt-out must be immediate) | ACID transactions |
| Relational (user -> channels -> preferences) | Foreign keys, joins |
| Small dataset (~millions of users, not billions of rows) | Single node is fine |
| Complex queries (reporting, analytics) | Full SQL |

### Interview Talking Point

> "We use a polyglot persistence strategy. Notification logs go to Cassandra -- it handles
> millions of writes per day with TTL-based retention. User preferences go to PostgreSQL
> because we need ACID guarantees when a user opts out. The key insight is that notifications
> are write-heavy and append-only, while preferences are read-heavy and update-in-place.
> Different access patterns warrant different databases."

---

## 7. Redis -- Caching, Dedup, Rate Limiting

### Three Distinct Uses

```
  Redis
    |
    +-- Cache Layer
    |     Template cache:     GET tmpl:{id}           TTL: until invalidated
    |     Preference cache:   GET pref:{userId}       TTL: 5 min
    |     Device token cache: GET token:{userId}      TTL: 24h
    |
    +-- Dedup Store
    |     Idempotency key:    SET NX dedup:{key}      TTL: 24h
    |     Returns 1 (new) or 0 (duplicate)
    |
    +-- Rate Limiter
          Frequency cap:      INCR ratelimit:{userId}:{channel}:{date}
          Check:              GET >= threshold? --> block
          TTL: end of day (auto-reset)
```

### Rate Limiting Implementation

```
  Key:   ratelimit:u-123:sms:2024-01-15
  Logic:
    count = INCR key
    if count == 1:
        EXPIRE key 86400    (set TTL on first increment)
    if count > MAX_SMS_PER_DAY:
        reject notification
        log "rate limited"
```

### Interview Talking Point

> "Redis serves three roles: caching (templates, preferences, device tokens), deduplication
> (idempotency keys with SET NX), and rate limiting (INCR with daily TTL). Each use case
> has a different TTL strategy. We chose Redis over Memcached because we need SET NX for
> atomic dedup checks and INCR for atomic rate limiting -- both require more than simple
> key-value GET/SET."

---

## 8. Template Engine -- Handlebars / Mustache

### How It Works

```
  Template (stored in DB):
  ========================
  Subject: "Your order {{orderId}} has shipped!"
  Body:    "Hi {{userName}}, your order is on the way.
            Track it here: {{trackingUrl}}"

  Context (from notification metadata):
  =====================================
  { "orderId": "ORD-456", "userName": "Karan", "trackingUrl": "https://..." }

  Rendered Output:
  ================
  Subject: "Your order ORD-456 has shipped!"
  Body:    "Hi Karan, your order is on the way.
            Track it here: https://..."
```

### Why Templates

| Benefit | Explanation |
|---------|-------------|
| Non-engineers can edit copy | Product/marketing updates without code deploys |
| Localization | Same template ID, different locale files |
| A/B testing | Multiple template variants per notification type |
| Compliance | Legal can review exact wording |

### Interview Talking Point

> "Templates decouple notification content from notification logic. The service just passes
> a templateId and a context map. This lets the product team update email copy without a
> code deploy. In our implementation, templates are cached in Redis and invalidated on change."

---

## 9. Observability Stack

### Key Metrics to Track

```
  Delivery Metrics (per channel):
  ================================
  delivery_rate          = sent / attempted        Target: > 98%
  failure_rate           = failed / attempted      Alert: > 2%
  bounce_rate (email)    = bounced / sent          Alert: > 5%
  retry_rate             = retried / attempted     Alert: > 10%

  Latency Metrics:
  ================
  p50_delivery_latency   = median send time        Target: < 1s (push), < 5s (email)
  p99_delivery_latency   = tail latency            Alert: > 30s

  Queue Metrics:
  ==============
  kafka_consumer_lag     = latest offset - current Target: < 1000
  dlq_depth              = messages in DLQ          Alert: > 0 (always investigate)
  queue_throughput       = messages/sec             Baseline for capacity planning

  Business Metrics:
  =================
  opt_out_rate           = opt-outs / total users   Track trends
  open_rate (email)      = opened / delivered       Track engagement
  click_rate             = clicked / delivered      Track engagement
```

### Alerting Rules

| Metric | Threshold | Action |
|--------|-----------|--------|
| DLQ depth > 0 | Immediate | Page on-call: messages are failing permanently |
| Consumer lag > 10,000 | 5 min sustained | Scale up consumers |
| Delivery failure rate > 5% | 1 min window | Circuit breaker + alert |
| p99 latency > 30s | 5 min sustained | Investigate provider / downstream |
| Bounce rate > 5% | 1 hour | Pause email, investigate sender reputation |

### Interview Talking Point

> "The most important metric is DLQ depth. If it is greater than zero, notifications are
> permanently failing and we need to investigate immediately. The second is consumer lag --
> if it is growing, we are falling behind and need to scale. Everything else is important
> but not as urgent."
