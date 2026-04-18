# High-Level Design: Notification System

> Target: Senior Java Developer (7+ years) | System Design Interview (30-45 min)

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Scope](#2-scope)
3. [Assumptions](#3-assumptions)
4. [Functional Requirements](#4-functional-requirements)
5. [Non-Functional Requirements](#5-non-functional-requirements)
6. [API Design](#6-api-design)
7. [Traffic Estimates](#7-traffic-estimates)
8. [Data Model](#8-data-model)
9. [High-Level Architecture](#9-high-level-architecture)
10. [Component Deep Dive](#10-component-deep-dive)
11. [Fan-Out Strategy](#11-fan-out-strategy)
12. [Scaling Strategy](#12-scaling-strategy)
13. [Database Choice](#13-database-choice)
14. [Caching Strategy](#14-caching-strategy)
15. [Retry and Dead Letter Queue](#15-retry-and-dead-letter-queue)
16. [CAP Theorem Analysis](#16-cap-theorem-analysis)
17. [Rate Limiting](#17-rate-limiting)
18. [Cloud Services Mapping](#18-cloud-services-mapping)
19. [Tradeoffs Summary](#19-tradeoffs-summary)
20. [Interview Talking Points](#20-interview-talking-points)

---

## 1. Problem Statement

Build a **scalable, multi-channel notification system** capable of delivering messages through push notifications, email, SMS, and in-app channels. The system must handle billions of notifications daily with low latency for critical messages, support user preferences, and provide reliable delivery tracking.

**Why every large platform needs one:**

- Every user-facing product -- e-commerce, banking, social media, SaaS -- depends on timely notifications to keep users informed and engaged.
- Without a centralized notification system, each team builds its own sending pipeline, leading to inconsistent user experience, duplicate messages, no preference management, and wasted engineering effort.
- A dedicated notification platform becomes a shared infrastructure service, similar to authentication or logging.

**Real-world examples:**

| Use Case | Priority | Channel(s) |
|---|---|---|
| OTP / 2FA codes | CRITICAL | SMS, Push |
| Order confirmation | HIGH | Email, Push |
| Friend request accepted | MEDIUM | Push, In-App |
| Weekly digest / promotions | LOW | Email |
| Security alert (new login) | CRITICAL | Email, SMS, Push |
| Delivery status update | HIGH | Push, SMS |

---

## 2. Scope

### In Scope

- Multi-channel delivery: Push (FCM/APNs), Email (SES/SendGrid), SMS (Twilio), In-App
- User preference management (opt-in/opt-out per channel, quiet hours, frequency caps)
- Template-based notifications with variable substitution and localization
- Priority levels: CRITICAL, HIGH, MEDIUM, LOW
- Immediate and scheduled/delayed delivery
- Retry on failure with exponential backoff
- Delivery tracking and status reporting (PENDING, SENT, DELIVERED, FAILED, BOUNCED)
- Batch notifications (send to a user segment)
- Rate limiting per user per channel
- Deduplication of duplicate send requests

### Out of Scope

- Notification content creation (copywriting, A/B testing of content)
- Billing and cost tracking for SMS/email providers
- User authentication and authorization (handled by upstream services)
- Rich media rendering (handled by client SDKs)
- Analytics dashboards and reporting (separate service)
- Two-way messaging / chat

---

## 3. Assumptions

| Parameter | Value |
|---|---|
| Total notifications per day | 1 Billion |
| Channel split | Push 60%, Email 25%, SMS 10%, In-App 5% |
| Peak throughput | 50,000 notifications/sec |
| Average throughput | ~12,000 notifications/sec |
| Delivery mode | Immediate + Scheduled |
| Registered users | 500 Million |
| Average notification payload | ~200 bytes |
| Template count | ~5,000 (negligible storage) |
| Retention | 30 days hot, archive to cold storage |
| SLA for critical notifications | < 1 second end-to-end |

---

## 4. Functional Requirements

| ID | Requirement | Details |
|---|---|---|
| FR-1 | Multi-channel send | Deliver via push, email, SMS, in-app -- one or multiple channels per notification |
| FR-2 | User preferences | Opt-in/out per channel, quiet hours (no non-critical between 10 PM - 8 AM), frequency caps |
| FR-3 | Template engine | Template-based notifications with variable substitution (`{{userName}}`, `{{orderAmount}}`) |
| FR-4 | Priority levels | CRITICAL (OTP, security), HIGH (order updates), MEDIUM (social), LOW (marketing) |
| FR-5 | Scheduled delivery | Support `scheduledAt` timestamp for delayed delivery |
| FR-6 | Retry on failure | Exponential backoff with configurable max retries per channel |
| FR-7 | Delivery tracking | Track status: PENDING -> SENT -> DELIVERED / FAILED / BOUNCED |
| FR-8 | Batch send | Send notification to a user segment (list of user IDs or segment query) |
| FR-9 | Deduplication | Idempotency key to prevent duplicate sends within a 24-hour window |
| FR-10 | Rate limiting | Per-user, per-channel rate limits to prevent notification fatigue |

---

## 5. Non-Functional Requirements

| Requirement | Target |
|---|---|
| Delivery guarantee | At-least-once delivery |
| Latency (CRITICAL) | < 1 second end-to-end |
| Latency (HIGH) | < 5 seconds |
| Latency (MEDIUM/LOW) | < 30 seconds |
| Throughput | 50K notifications/sec at peak |
| Availability | 99.9% uptime (< 8.76 hours downtime/year) |
| Deduplication | Idempotency key with 24-hour TTL |
| Ordering | Not required globally; in-app notifications ordered per user |
| Data retention | 30 days hot storage, cold archive beyond |
| Scalability | Horizontal scaling of all components |

---

## 6. API Design

### 6.1 Send Notification

```
POST /api/notifications/send
Content-Type: application/json
X-Idempotency-Key: uuid-abc-123
```

**Request:**

```json
{
  "userId": "user_12345",
  "templateId": "order_confirmation_v2",
  "channels": ["PUSH", "EMAIL"],
  "priority": "HIGH",
  "data": {
    "orderId": "ORD-98765",
    "orderAmount": "$149.99",
    "deliveryDate": "2026-04-20"
  },
  "scheduledAt": null
}
```

**Response (202 Accepted):**

```json
{
  "notificationId": "notif_abc123",
  "status": "PENDING",
  "channels": ["PUSH", "EMAIL"],
  "createdAt": "2026-04-18T10:30:00Z"
}
```

### 6.2 Batch Send

```
POST /api/notifications/batch
Content-Type: application/json
```

**Request:**

```json
{
  "userIds": ["user_001", "user_002", "user_003"],
  "segmentId": null,
  "templateId": "flash_sale_v1",
  "channels": ["PUSH"],
  "priority": "LOW",
  "data": {
    "saleTitle": "Summer Flash Sale",
    "discount": "40%",
    "expiresAt": "2026-04-19T23:59:59Z"
  }
}
```

**Response (202 Accepted):**

```json
{
  "batchId": "batch_xyz789",
  "totalRecipients": 3,
  "status": "QUEUED",
  "createdAt": "2026-04-18T10:35:00Z"
}
```

### 6.3 Get User Notifications (In-App)

```
GET /api/notifications/user_12345?channel=IN_APP&page=0&size=20
```

**Response (200 OK):**

```json
{
  "userId": "user_12345",
  "notifications": [
    {
      "notificationId": "notif_abc123",
      "title": "Order Confirmed",
      "body": "Your order ORD-98765 for $149.99 has been confirmed.",
      "read": false,
      "priority": "HIGH",
      "createdAt": "2026-04-18T10:30:00Z"
    },
    {
      "notificationId": "notif_def456",
      "title": "Friend Request",
      "body": "John accepted your friend request.",
      "read": true,
      "priority": "MEDIUM",
      "createdAt": "2026-04-17T14:20:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 42,
  "unreadCount": 7
}
```

### 6.4 Update User Preferences

```
PUT /api/users/user_12345/preferences
Content-Type: application/json
```

**Request:**

```json
{
  "preferences": [
    {
      "channel": "EMAIL",
      "enabled": true,
      "quietHoursStart": "22:00",
      "quietHoursEnd": "08:00",
      "frequencyCap": 10
    },
    {
      "channel": "SMS",
      "enabled": true,
      "quietHoursStart": "22:00",
      "quietHoursEnd": "08:00",
      "frequencyCap": 5
    },
    {
      "channel": "PUSH",
      "enabled": true,
      "quietHoursStart": null,
      "quietHoursEnd": null,
      "frequencyCap": 50
    },
    {
      "channel": "IN_APP",
      "enabled": true,
      "quietHoursStart": null,
      "quietHoursEnd": null,
      "frequencyCap": null
    }
  ]
}
```

**Response (200 OK):**

```json
{
  "userId": "user_12345",
  "preferences": [
    { "channel": "EMAIL", "enabled": true, "quietHoursStart": "22:00", "quietHoursEnd": "08:00", "frequencyCap": 10 },
    { "channel": "SMS", "enabled": true, "quietHoursStart": "22:00", "quietHoursEnd": "08:00", "frequencyCap": 5 },
    { "channel": "PUSH", "enabled": true, "quietHoursStart": null, "quietHoursEnd": null, "frequencyCap": 50 },
    { "channel": "IN_APP", "enabled": true, "quietHoursStart": null, "quietHoursEnd": null, "frequencyCap": null }
  ],
  "updatedAt": "2026-04-18T10:40:00Z"
}
```

### 6.5 Get Notification Status

```
GET /api/notifications/notif_abc123/status
```

**Response (200 OK):**

```json
{
  "notificationId": "notif_abc123",
  "userId": "user_12345",
  "channels": [
    {
      "channel": "PUSH",
      "status": "DELIVERED",
      "sentAt": "2026-04-18T10:30:01Z",
      "deliveredAt": "2026-04-18T10:30:02Z",
      "retryCount": 0
    },
    {
      "channel": "EMAIL",
      "status": "SENT",
      "sentAt": "2026-04-18T10:30:03Z",
      "deliveredAt": null,
      "retryCount": 0
    }
  ]
}
```

---

## 7. Traffic Estimates

### Throughput

| Metric | Value |
|---|---|
| Total notifications/day | 1,000,000,000 (1B) |
| Average notifications/sec | ~12,000 |
| Peak notifications/sec | ~50,000 |
| Push notifications/day | 600,000,000 (60%) |
| Email notifications/day | 250,000,000 (25%) |
| SMS notifications/day | 100,000,000 (10%) |
| In-App notifications/day | 50,000,000 (5%) |

### Storage

| Data | Calculation | Result |
|---|---|---|
| Notification records/day | 1B x 200 bytes | 200 GB/day |
| Notification records/month | 200 GB x 30 | 6 TB/month |
| Notification records/year | 6 TB x 12 | ~73 TB/year |
| User preferences | 500M users x 100 bytes | 50 GB (total) |
| Templates | ~5,000 x 2 KB | ~10 MB (negligible) |
| Delivery logs | 1B x 150 bytes (avg 1.2 attempts) | ~180 GB/day |
| Device tokens | 500M users x 200 bytes | ~100 GB |

### Storage Strategy

```
Hot Storage (last 30 days):   ~6 TB notifications + ~5.4 TB delivery logs
Warm Storage (30-90 days):    Compressed, query-able for support tickets
Cold Storage (90+ days):      S3/GCS archive, accessed only for audits
```

### Bandwidth

| Direction | Calculation | Result |
|---|---|---|
| Ingress (API requests) | 50K/sec x 500 bytes | ~25 MB/sec |
| Egress to providers | 50K/sec x 300 bytes | ~15 MB/sec |
| Kafka internal | 50K/sec x 400 bytes | ~20 MB/sec |

---

## 8. Data Model

### 8.1 notification

```sql
CREATE TABLE notification (
    id              UUID PRIMARY KEY,
    user_id         VARCHAR(64) NOT NULL,
    template_id     VARCHAR(128) NOT NULL,
    channel         VARCHAR(16) NOT NULL,    -- PUSH, EMAIL, SMS, IN_APP
    priority        VARCHAR(16) NOT NULL,    -- CRITICAL, HIGH, MEDIUM, LOW
    status          VARCHAR(16) NOT NULL,    -- PENDING, SENT, DELIVERED, FAILED, BOUNCED
    data_json       TEXT,                    -- JSON payload for template variables
    idempotency_key VARCHAR(128),
    scheduled_at    TIMESTAMP,
    sent_at         TIMESTAMP,
    delivered_at    TIMESTAMP,
    retry_count     INT DEFAULT 0,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL
);

-- Partition key: (user_id, created_at) for Cassandra
-- Indexes: user_id + channel, status, scheduled_at
```

### 8.2 user_preference

```sql
CREATE TABLE user_preference (
    user_id           VARCHAR(64) NOT NULL,
    channel           VARCHAR(16) NOT NULL,
    enabled           BOOLEAN DEFAULT TRUE,
    quiet_hours_start TIME,             -- e.g., 22:00
    quiet_hours_end   TIME,             -- e.g., 08:00
    frequency_cap     INT,              -- max notifications per day for this channel
    updated_at        TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, channel)
);
```

### 8.3 notification_template

```sql
CREATE TABLE notification_template (
    id                VARCHAR(128) PRIMARY KEY,
    name              VARCHAR(256) NOT NULL,
    channel           VARCHAR(16) NOT NULL,
    subject_template  TEXT,                 -- for email: "Order {{orderId}} confirmed"
    body_template     TEXT NOT NULL,        -- "Hi {{userName}}, your order..."
    variables         TEXT,                 -- JSON array: ["userName", "orderId", "orderAmount"]
    locale            VARCHAR(10) DEFAULT 'en',
    active            BOOLEAN DEFAULT TRUE,
    created_at        TIMESTAMP NOT NULL,
    updated_at        TIMESTAMP NOT NULL
);
```

### 8.4 delivery_log

```sql
CREATE TABLE delivery_log (
    id                UUID PRIMARY KEY,
    notification_id   UUID NOT NULL,
    attempt           INT NOT NULL,       -- 1, 2, 3...
    status            VARCHAR(16) NOT NULL, -- SENT, FAILED, DELIVERED, BOUNCED
    provider          VARCHAR(32),         -- FCM, APNs, SES, SendGrid, Twilio
    provider_response TEXT,                -- raw response or error from provider
    error_code        VARCHAR(64),
    timestamp         TIMESTAMP NOT NULL
);

-- Partition key: (notification_id) for Cassandra
```

### Entity Relationship

```
┌──────────────────┐       ┌─────────────────────┐
│ user_preference  │       │ notification_template│
│                  │       │                      │
│ user_id (PK)     │       │ id (PK)              │
│ channel (PK)     │       │ name                 │
│ enabled          │       │ channel              │
│ quiet_hours_*    │       │ subject_template     │
│ frequency_cap    │       │ body_template        │
└────────┬─────────┘       │ variables            │
         │                 └──────────┬────────────┘
         │ user_id                    │ template_id
         ▼                            ▼
┌──────────────────────────────────────────────┐
│                notification                   │
│                                               │
│ id (PK)                                       │
│ user_id ──────── FK to user                   │
│ template_id ──── FK to template               │
│ channel, priority, status                     │
│ data_json, scheduled_at, sent_at              │
│ retry_count, created_at                       │
└──────────────────────┬────────────────────────┘
                       │ notification_id
                       ▼
              ┌─────────────────┐
              │  delivery_log   │
              │                 │
              │ id (PK)         │
              │ notification_id │
              │ attempt         │
              │ status          │
              │ provider_response│
              └─────────────────┘
```

---

## 9. High-Level Architecture

```
┌────────────────────────────────────────────────────────────────────────────────────────────┐
│                                     CLIENTS / SERVICES                                     │
│                                                                                            │
│   Order Service    Auth Service    Social Service    Marketing Service    Scheduler (Cron)  │
└────────┬──────────────┬──────────────┬─────────────────┬──────────────────┬─────────────────┘
         │              │              │                 │                  │
         ▼              ▼              ▼                 ▼                  ▼
┌────────────────────────────────────────────────────────────────────────────────────────────┐
│                                      API GATEWAY                                           │
│                         (Rate Limiting, Auth, Load Balancing)                               │
└────────────────────────────────────────┬───────────────────────────────────────────────────┘
                                         │
                    ┌────────────────────┼────────────────────┐
                    ▼                    ▼                    ▼
          ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
          │  Notification   │  │   Preference    │  │    Template     │
          │    Service      │  │    Service      │  │    Service      │
          │                 │  │                 │  │                 │
          │ - Validate req  │  │ - CRUD prefs    │  │ - CRUD templates│
          │ - Check prefs   │  │ - Quiet hours   │  │ - Variable sub  │
          │ - Resolve tmpl  │  │ - Freq caps     │  │ - Localization  │
          │ - Dedup check   │  │ - Rate limits   │  │                 │
          │ - Publish to Q  │  │                 │  │                 │
          └────────┬────────┘  └────────┬────────┘  └────────┬────────┘
                   │                    │                     │
                   │              ┌─────┴──────┐       ┌─────┴──────┐
                   │              │ PostgreSQL │       │   Redis    │
                   │              │ (Prefs)    │       │  (Cache)   │
                   │              └────────────┘       └────────────┘
                   │
                   ▼
┌────────────────────────────────────────────────────────────────────────────────────────────┐
│                              KAFKA (Priority Queues)                                       │
│                                                                                            │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌─────────────┐ │
│  │   CRITICAL   │  │     HIGH     │  │    MEDIUM    │  │     LOW      │  │  SCHEDULED  │ │
│  │   Topic      │  │    Topic     │  │    Topic     │  │    Topic     │  │    Topic    │ │
│  │  (32 parts)  │  │  (64 parts)  │  │ (128 parts)  │  │ (128 parts)  │  │ (16 parts)  │ │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬──────┘ │
└─────────┼─────────────────┼─────────────────┼─────────────────┼─────────────────┼─────────┘
          │                 │                 │                 │                 │
          ▼                 ▼                 ▼                 ▼                 ▼
┌────────────────────────────────────────────────────────────────────────────────────────────┐
│                                    WORKER POOL                                             │
│                                                                                            │
│  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐               │
│  │  Push Worker  │  │ Email Worker  │  │  SMS Worker   │  │ In-App Worker │               │
│  │  (Consumer    │  │  (Consumer    │  │  (Consumer    │  │  (Consumer    │               │
│  │   Group)      │  │   Group)      │  │   Group)      │  │   Group)      │               │
│  └───────┬───────┘  └───────┬───────┘  └───────┬───────┘  └───────┬───────┘               │
│          │                  │                   │                  │                        │
└──────────┼──────────────────┼───────────────────┼──────────────────┼────────────────────────┘
           │                  │                   │                  │
           ▼                  ▼                   ▼                  ▼
    ┌─────────────┐   ┌─────────────┐    ┌─────────────┐   ┌─────────────┐
    │  FCM / APNs │   │ SES /       │    │   Twilio /  │   │  Cassandra  │
    │  (Push)     │   │ SendGrid    │    │   Nexmo     │   │  (In-App    │
    │             │   │ (Email)     │    │   (SMS)     │   │   Store)    │
    └──────┬──────┘   └──────┬──────┘    └──────┬──────┘   └─────────────┘
           │                 │                  │
           ▼                 ▼                  ▼
    ┌─────────────────────────────────────────────────┐
    │              DELIVERY TRACKER                    │
    │                                                  │
    │  - Webhook receivers (SES, FCM, Twilio)          │
    │  - Status updates: DELIVERED / BOUNCED / FAILED  │
    │  - Updates notification status in Cassandra      │
    └──────────────────────┬──────────────────────────┘
                           │
                    ┌──────┴───────┐
                    │  Cassandra   │
                    │ (Notif Logs) │
                    └──────────────┘

┌────────────────────────────────────────────────────────────────────────────────────────────┐
│                              SUPPORTING SERVICES                                           │
│                                                                                            │
│  ┌──────────────────┐   ┌──────────────────┐   ┌──────────────────┐                       │
│  │ Scheduler Service│   │  Dead Letter      │   │  Redis Cluster   │                       │
│  │                  │   │  Queue (DLQ)      │   │                  │                       │
│  │ - Polls scheduled│   │                   │   │ - Dedup cache    │                       │
│  │   topic at       │   │ - Permanent fails │   │ - Rate limiting  │                       │
│  │   interval       │   │ - Invalid tokens  │   │ - Pref cache     │                       │
│  │ - Publishes to   │   │ - Alerting on     │   │ - Template cache │                       │
│  │   priority topic │   │   queue growth    │   │ - Device tokens  │                       │
│  │   when due       │   │                   │   │                  │                       │
│  └──────────────────┘   └──────────────────┘   └──────────────────┘                       │
└────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Request Flow (Critical Notification -- OTP)

```
1. Auth Service ──POST /send──▶ API Gateway ──▶ Notification Service
2. Notification Service:
   a. Dedup check (Redis: idempotency key)
   b. Fetch user preference (Redis cache / PostgreSQL)
   c. Check: channel enabled? quiet hours? frequency cap?
   d. Resolve template (Redis cache / PostgreSQL)
   e. Substitute variables into template
   f. Publish to Kafka CRITICAL topic
3. Kafka ──▶ SMS Worker (consumer group)
4. SMS Worker:
   a. Call Twilio API to send SMS
   b. Write to delivery_log (Cassandra)
   c. Update notification status to SENT
5. Twilio Webhook ──▶ Delivery Tracker
   a. Update status to DELIVERED
   b. Write to delivery_log

End-to-end: < 1 second
```

---

## 10. Component Deep Dive

### 10.1 Notification Service

The central orchestrator. It is stateless and horizontally scalable.

**Responsibilities:**

1. **Validate request** -- check required fields, validate templateId exists, verify userId
2. **Deduplication** -- check `idempotency_key` in Redis (SET NX with 24h TTL)
3. **Check preferences** -- call Preference Service to verify channel is enabled, not in quiet hours, under frequency cap
4. **Resolve template** -- call Template Service, substitute variables into subject/body
5. **Assign priority** -- map to correct Kafka topic
6. **Publish to queue** -- produce message to appropriate Kafka priority topic

```
NotificationRequest
       │
       ▼
┌──────────────┐    ┌──────────────┐
│ Dedup Check  │───▶│ DUPLICATE?   │──YES──▶ Return 409 Conflict
│  (Redis)     │    │              │
└──────────────┘    └──────┬───────┘
                           │ NO
                           ▼
                   ┌──────────────┐
                   │ Check Prefs  │──BLOCKED──▶ Return 200 (silently dropped)
                   │              │
                   └──────┬───────┘
                          │ ALLOWED
                          ▼
                   ┌──────────────┐
                   │ Resolve      │
                   │ Template     │
                   └──────┬───────┘
                          │
                          ▼
                   ┌──────────────┐
                   │ Publish to   │
                   │ Kafka Topic  │
                   └──────────────┘
```

### 10.2 Priority Queue (Kafka)

Kafka serves as the backbone for decoupling ingestion from delivery.

| Topic | Partitions | Consumer Instances | Rationale |
|---|---|---|---|
| `notif.critical` | 32 | 32 | Max parallelism for OTP/security |
| `notif.high` | 64 | 32-64 | Fast delivery for transactional |
| `notif.medium` | 128 | 32-64 | Social notifications, some delay ok |
| `notif.low` | 128 | 16-32 | Marketing, can batch and throttle |
| `notif.scheduled` | 16 | 8 | Scheduler polls and re-publishes |
| `notif.dlq` | 16 | 4 | Dead letter processing |

**Why separate topics (not a single topic with priority headers):**

- Consumer groups can be scaled independently per priority
- Critical messages are never starved behind a backlog of marketing notifications
- Different retention and compaction policies per topic

### 10.3 Channel Workers

Each channel has a dedicated Kafka consumer group. Workers are stateless and auto-scalable.

| Worker | Provider | Behavior |
|---|---|---|
| **Push Worker** | FCM (Android), APNs (iOS) | Batch API calls (FCM supports up to 500/batch). Handle invalid tokens by removing from device registry. |
| **Email Worker** | Amazon SES, SendGrid | Bulk send API. Handle bounces/complaints via SNS webhooks. Respect provider rate limits. |
| **SMS Worker** | Twilio, Nexmo | Single send per API call (regulatory). More expensive, so rate limited carefully. |
| **In-App Worker** | Direct DB write | Write to Cassandra (notification store). Fastest channel, no external dependency. Client polls or uses WebSocket. |

**Worker processing flow:**

```
1. Consume message from Kafka
2. Look up device token / email / phone (from user profile service or cache)
3. Call external provider API
4. On SUCCESS: commit offset, write delivery_log, update status to SENT
5. On TRANSIENT FAILURE: retry with backoff, do NOT commit offset
6. On PERMANENT FAILURE: publish to DLQ, commit offset
```

### 10.4 Preference Service

- **Read-heavy** service: for every notification sent, preferences are checked. Writes are rare (user updates settings occasionally).
- Cache aggressively in Redis with a TTL of 1 hour and cache invalidation on write.
- Default preferences if user has not explicitly configured (all channels enabled, standard quiet hours).

**Preference check order:**

```
1. Is channel enabled for this user?          → NO → drop
2. Is it quiet hours for non-critical?        → YES → defer to end of quiet hours
3. Has frequency cap been reached today?      → YES → drop
4. Is user rate-limited?                      → YES → throttle
5. ALLOW
```

### 10.5 Template Engine

- Templates stored in PostgreSQL, cached in Redis.
- Variable substitution using Mustache-style `{{variable}}` syntax.
- Support for locale-based templates (e.g., `order_confirmation_v2_es` for Spanish).
- Templates are versioned; active flag controls which version is live.

**Example:**

```
Template: "Hi {{userName}}, your order {{orderId}} for {{orderAmount}} is confirmed!"
Data:     {"userName": "Karan", "orderId": "ORD-123", "orderAmount": "$49.99"}
Result:   "Hi Karan, your order ORD-123 for $49.99 is confirmed!"
```

### 10.6 Scheduler Service

Handles delayed/scheduled notifications.

**Approach:**

1. Notification Service publishes scheduled messages to `notif.scheduled` Kafka topic.
2. Scheduler Service is a polling-based consumer:
   - Reads messages from the scheduled topic.
   - Stores them in a time-indexed store (Cassandra or Redis Sorted Set with score = scheduled timestamp).
   - A cron-like poller runs every second, queries for messages where `scheduledAt <= now()`.
   - Re-publishes due messages to the appropriate priority topic.
3. For near-future scheduling (< 5 min), can use Kafka's delayed message pattern or a simple in-memory delay queue.

### 10.7 Retry Handler

```
Retry Policy:
  Attempt 1: Immediate
  Attempt 2: 1 second delay
  Attempt 3: 2 seconds delay
  Attempt 4: 4 seconds delay
  Attempt 5: 8 seconds delay
  Attempt 6: GIVE UP → publish to DLQ

Max retries per channel:
  CRITICAL: 5 retries
  HIGH:     4 retries
  MEDIUM:   3 retries
  LOW:      2 retries
```

**Transient vs Permanent failures:**

| Failure Type | Examples | Action |
|---|---|---|
| Transient | Provider timeout, 429 rate limited, 503 unavailable | Retry with backoff |
| Permanent | Invalid device token, email bounced, unsubscribed | Move to DLQ, update status to FAILED/BOUNCED |

### 10.8 Delivery Tracker

- Receives **webhooks** from external providers:
  - **SES**: SNS notifications for delivery, bounce, complaint
  - **FCM**: Delivery receipts (if enabled)
  - **Twilio**: Status callback URL per message
- Updates `notification.status` and writes to `delivery_log` in Cassandra.
- Exposes `GET /api/notifications/{id}/status` for clients.

---

## 11. Fan-Out Strategy

Sending a batch notification to a large user segment (e.g., 1 million users) requires careful fan-out to avoid overwhelming the system.

### Naive Approach (Bad)

```
Client sends 1M user IDs → Notification Service fans out 1M messages immediately → Kafka flooded
```

Problems: API timeout, massive memory usage, Kafka producer backpressure, downstream provider rate limits hit.

### Staged Fan-Out (Good)

```
Step 1: Client ──POST /batch──▶ Notification Service
        Creates one batch record in DB, publishes ONE batch message to Kafka

Step 2: Batch Worker consumes the batch message
        Reads user segment (1M user IDs) from DB or segment service
        Splits into chunks of 1,000

Step 3: For each chunk of 1,000 users:
        ┌─────────────────────────────────────────────┐
        │ Check preferences for all 1,000 users       │
        │ Filter out opted-out / rate-limited users    │
        │ Resolve template once (shared across chunk)  │
        │ Publish individual messages to channel topics │
        │ Throttle: 10 chunks/second = 10K messages/sec│
        └─────────────────────────────────────────────┘

Step 4: Channel workers process at their own pace
```

### Fan-Out Throttling

```
Batch size: 1,000,000 users
Chunk size: 1,000 users
Chunks: 1,000
Fan-out rate: 10 chunks/sec
Total fan-out time: ~100 seconds (acceptable for marketing)

For CRITICAL batches: increase rate to 100 chunks/sec → ~10 seconds
```

---

## 12. Scaling Strategy

### Kafka Scaling

| Topic | Partitions | Reasoning |
|---|---|---|
| `notif.critical` | 32 | Low volume but needs lowest latency. 32 consumers max. |
| `notif.high` | 64 | High volume transactional. 64 consumers for throughput. |
| `notif.medium` | 128 | Highest volume. More partitions = more parallel consumers. |
| `notif.low` | 128 | Marketing bursts. Partitions absorb spikes. |

### Worker Auto-Scaling

```
Metric: Kafka consumer lag (messages behind)
Scale-up trigger:  lag > 10,000 messages for 2 minutes
Scale-down trigger: lag < 100 messages for 10 minutes

Worker pools (Kubernetes HPA):
  Push Workers:   min=10, max=200
  Email Workers:  min=10, max=100
  SMS Workers:    min=5,  max=50
  In-App Workers: min=5,  max=30
```

### Service Scaling

| Service | Strategy | Notes |
|---|---|---|
| Notification Service | Horizontal (stateless) | Behind load balancer, 10-50 instances |
| Preference Service | Horizontal + cache | Redis cache absorbs 95% of reads |
| Template Service | Horizontal + cache | Rarely changes, cache hit rate > 99% |
| Scheduler Service | Leader election | Only one active scheduler to prevent duplicates |
| Delivery Tracker | Horizontal | Webhook receiver, stateless |

### Data Scaling

- **Cassandra:** Add nodes linearly. Time-partition notifications by day. TTL-based auto-cleanup after 30 days.
- **PostgreSQL:** Single primary + read replicas for preferences. At 50 GB, vertical scaling is sufficient.
- **Redis Cluster:** 6+ nodes, hash-slot based sharding. Separate clusters for dedup vs. caching.

---

## 13. Database Choice

| Database | Use Case | Justification |
|---|---|---|
| **Apache Cassandra** | Notification records, delivery logs | Write-heavy (1B+ writes/day), time-series data, built-in TTL for auto-expiry, linear horizontal scaling, tunable consistency. Partition key: `(user_id, day_bucket)` for efficient per-user queries. |
| **PostgreSQL** | User preferences, templates | Relational data with strong consistency needed. Small dataset (50 GB prefs, 10 MB templates). ACID transactions for preference updates. Read replicas for scale. |
| **Redis** | Dedup cache, rate limiting, preference cache, template cache, device tokens | Sub-millisecond reads. Dedup: `SET key NX EX 86400`. Rate limiting: `INCR + EXPIRE` pattern. Sorted sets for scheduled notifications. |

### Why Not...

| Alternative | Reason to Skip |
|---|---|
| MySQL | Cassandra better for write-heavy, time-series workloads at this scale |
| MongoDB | No significant advantage over Cassandra for this use case; Cassandra has better linear scaling |
| DynamoDB | Viable alternative to Cassandra, but vendor lock-in. On-demand pricing can be expensive at 1B writes/day |
| Kafka (as DB) | Good for event sourcing but not for querying notification history per user |

---

## 14. Caching Strategy

| Cache Target | Key Pattern | TTL | Invalidation | Hit Rate |
|---|---|---|---|---|
| User preferences | `pref:{userId}` | 1 hour | Cache-aside; invalidate on PUT | ~95% |
| Templates | `tmpl:{templateId}` | 6 hours | Invalidate on template update | ~99% |
| Device tokens | `device:{userId}` | 24 hours | Invalidate on token refresh | ~90% |
| Dedup (idempotency) | `dedup:{idempotencyKey}` | 24 hours | Auto-expire | N/A |
| Rate limit counters | `rate:{userId}:{channel}:{date}` | End of day | Auto-expire | N/A |
| Frequency cap counters | `freq:{userId}:{channel}:{date}` | End of day | Auto-expire | N/A |

### Deduplication Flow

```
Incoming request with X-Idempotency-Key: "abc-123"

Redis: SET dedup:abc-123 NX EX 86400
  → Key set (new request)    → proceed
  → Key exists (duplicate)   → return cached response, HTTP 409
```

### Rate Limiting Flow (Sliding Window)

```
Redis: INCR rate:user_123:PUSH:2026-04-18
Redis: EXPIRE rate:user_123:PUSH:2026-04-18 86400  (only on first INCR)

If count > frequency_cap → reject / defer
```

---

## 15. Retry and Dead Letter Queue

### Retry Strategy

```
┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│ Attempt 1│───▶│ Attempt 2│───▶│ Attempt 3│───▶│ Attempt 4│───▶│ Attempt 5│───▶ DLQ
│ (0s)     │    │ (+1s)    │    │ (+2s)    │    │ (+4s)    │    │ (+8s)    │
└──────────┘    └──────────┘    └──────────┘    └──────────┘    └──────────┘
   FAIL            FAIL            FAIL            FAIL            FAIL
```

### Retry Implementation

- Workers use Kafka consumer `pause/resume` with a delay before retry.
- Alternatively, publish to a retry topic with a header indicating next retry time. A retry consumer re-publishes to the original topic after delay.
- Jitter added to backoff: `delay = baseDelay * 2^attempt + random(0, 1s)` to prevent thundering herd.

### Dead Letter Queue (DLQ)

**Messages land in DLQ when:**

| Scenario | Action |
|---|---|
| Max retries exhausted | Move to DLQ, mark FAILED |
| Invalid device token | Move to DLQ, remove token from registry |
| Email hard bounce | Move to DLQ, mark email as invalid |
| User unsubscribed at provider level | Move to DLQ, update preferences |
| Malformed notification data | Move to DLQ, alert engineering |

**DLQ Monitoring:**

- Alert if DLQ depth > 10,000 messages (indicates systemic issue)
- Dashboard showing DLQ growth rate, top failure reasons
- Manual or automated replay of DLQ messages after fixing root cause

---

## 16. CAP Theorem Analysis

### Classification: AP System (Availability + Partition Tolerance)

```
                    Consistency
                        /\
                       /  \
                      /    \
                     / This \
                    / System \
                   /   is AP  \
                  /____________\
          Availability ──── Partition
                              Tolerance
```

### Rationale

| Decision | Reasoning |
|---|---|
| **At-least-once** over exactly-once | A duplicate notification is annoying but tolerable. A missing OTP or security alert is unacceptable. Exactly-once is expensive and complex. |
| **Eventual consistency** for status | Delivery status may lag by seconds. Acceptable for tracking. |
| **Strong consistency** for preferences | User expects immediate effect when opting out. PostgreSQL provides this. |
| **Dedup as best-effort** | Redis-based idempotency key catches most duplicates. In rare partition scenarios, a duplicate may slip through. |

### Idempotency Strategy

```
Client includes X-Idempotency-Key in request header
  → Redis SET NX with 24h TTL
  → If key exists, return cached response
  → If Kafka producer retries, same message ID prevents duplicate publish

Downstream: Provider APIs are generally idempotent or we use provider-level dedup IDs
  → SES: MessageDeduplicationId
  → FCM: collapse_key for updates
```

---

## 17. Rate Limiting

### Per-User Rate Limits

| Category | Channel | Limit | Window |
|---|---|---|---|
| Marketing / LOW | PUSH | 3/day | 24 hours |
| Marketing / LOW | EMAIL | 2/day | 24 hours |
| Marketing / LOW | SMS | 1/day | 24 hours |
| Social / MEDIUM | PUSH | 20/day | 24 hours |
| Social / MEDIUM | EMAIL | 10/day | 24 hours |
| Transactional / HIGH | ALL | Unlimited | -- |
| Critical | ALL | Unlimited | -- |

### Quiet Hours

```
Default quiet hours: 10:00 PM - 8:00 AM (user's local timezone)

During quiet hours:
  CRITICAL notifications → SEND (always)
  HIGH notifications     → SEND (always)
  MEDIUM notifications   → DEFER to 8:00 AM
  LOW notifications      → DEFER to 8:00 AM
```

### System-Level Rate Limiting

| Provider | Rate Limit | Our Limit |
|---|---|---|
| FCM | 1,000 req/sec per project | 800 req/sec (80% buffer) |
| SES | 50,000 emails/sec (with warmup) | 40,000 emails/sec |
| Twilio | Varies by number pool | Configurable per account |

### Implementation

```java
// Redis-based sliding window rate limiter (pseudocode)
public boolean isAllowed(String userId, String channel, Priority priority) {
    if (priority == CRITICAL || priority == HIGH) return true;

    String key = "rate:" + userId + ":" + channel + ":" + today();
    long count = redis.incr(key);
    if (count == 1) redis.expire(key, 86400);

    int limit = getFrequencyCap(userId, channel);
    return count <= limit;
}
```

---

## 18. Cloud Services Mapping

| Component | AWS | GCP | Azure |
|---|---|---|---|
| API Gateway | API Gateway / ALB | Cloud Endpoints / API Gateway | API Management |
| Notification Service | ECS / EKS | GKE / Cloud Run | AKS / Container Apps |
| Message Queue | Amazon MSK (Kafka) | Confluent on GCP / Pub/Sub | Event Hubs (Kafka mode) |
| Push Notifications | SNS + FCM/APNs | Firebase Cloud Messaging | Notification Hubs |
| Email | SES | SendGrid (3rd party) | Communication Services |
| SMS | SNS / Pinpoint | Firebase / Twilio | Communication Services |
| Notification Store | DynamoDB / Keyspaces | Bigtable / Datastore | Cosmos DB (Cassandra API) |
| Preferences Store | RDS PostgreSQL | Cloud SQL | Azure Database for PostgreSQL |
| Cache | ElastiCache (Redis) | Memorystore (Redis) | Azure Cache for Redis |
| Scheduler | EventBridge / Step Functions | Cloud Scheduler / Cloud Tasks | Logic Apps / Durable Functions |
| Cold Storage | S3 + Glacier | Cloud Storage (Coldline) | Blob Storage (Cool/Archive) |
| Monitoring | CloudWatch + X-Ray | Cloud Monitoring + Trace | Application Insights |
| CDN (for email assets) | CloudFront | Cloud CDN | Azure CDN |

---

## 19. Tradeoffs Summary

| Decision | Option Chosen | Alternative | Why |
|---|---|---|---|
| **Delivery guarantee** | At-least-once | Exactly-once | Simpler, cheaper. Duplicate is tolerable; missed notification is not. |
| **Queue technology** | Kafka | RabbitMQ / SQS | Kafka handles 50K/sec easily, durable, replay-able, partitioned. RabbitMQ does not scale as well horizontally. |
| **Separate topics per priority** | Yes | Single topic with headers | Prevents low-priority backlog from starving critical messages. Independent scaling. |
| **Notification store** | Cassandra | DynamoDB | Open source, no vendor lock-in, excellent for time-series writes with TTL. DynamoDB is viable on AWS-only. |
| **Preference store** | PostgreSQL | Cassandra | Small dataset (50 GB), strong consistency needed for opt-out. Relational model fits well. |
| **Fan-out strategy** | Staged (chunked) | Immediate | Prevents queue flooding for batch sends to millions of users. Adds ~100s latency for batch, acceptable for marketing. |
| **Scheduling** | Polling-based | Kafka delayed topics | Kafka does not natively support delayed messages. Polling from a time-indexed store is simpler and battle-tested. |
| **Template rendering** | Server-side | Client-side | Server-side ensures consistency across channels. Client only receives the final rendered message. |
| **Retry mechanism** | Exponential backoff + DLQ | Fixed interval / Infinite retry | Backoff prevents thundering herd. DLQ prevents poison messages from blocking the queue. |
| **Rate limiting** | Redis sliding window | Token bucket in-memory | Redis is shared across instances. In-memory would require sticky sessions or coordination. |

---

## 20. Interview Talking Points

### Opening Statement (30 seconds)

> "I'll design a notification system that handles 1 billion notifications per day across push, email, SMS, and in-app channels. The key challenges are prioritization, reliable delivery at scale, user preference management, and handling failures gracefully."

### Key Points to Emphasize

1. **Priority-based processing** -- "We separate Kafka topics by priority so a marketing batch of 10M messages never delays an OTP. Critical notifications get dedicated consumers and more resources."

2. **Fan-out strategy** -- "For batch sends to 1M users, we don't fan out immediately. A batch worker chunks the list and throttles publishing to prevent queue flooding."

3. **At-least-once delivery** -- "We chose AP over CP. A duplicate push notification is annoying but acceptable. A missed OTP breaks the user flow. We use idempotency keys for best-effort dedup."

4. **Preference enforcement** -- "Every notification passes through a preference check: is the channel enabled, is it quiet hours, has the frequency cap been reached. This happens before queuing to avoid wasted work."

5. **Retry and DLQ** -- "Transient failures get exponential backoff with jitter. Permanent failures like invalid tokens go to a dead letter queue. We alert on DLQ growth to catch systemic issues."

### Common Follow-Up Questions

| Question | Answer |
|---|---|
| "How do you handle exactly-once delivery?" | "We don't guarantee it. We use idempotency keys at the API layer and collapse keys at the provider level (FCM collapse_key, SES dedup ID). True exactly-once across distributed systems is prohibitively expensive." |
| "What if Kafka goes down?" | "Kafka is deployed as a multi-broker cluster with replication factor 3. If a broker fails, partitions are reassigned. If the entire cluster is down, the API Gateway returns 503 and clients retry. We can also have a fallback SQS queue." |
| "How do you handle timezone for quiet hours?" | "User preference stores timezone. Notification Service converts quiet hours to UTC at check time. For batch sends, we group users by timezone and schedule accordingly." |
| "How do you prevent a single user from being spammed?" | "Three layers: frequency cap per channel per day, rate limiting via Redis sliding window, and quiet hours. Marketing is capped at 3 push/day. Transactional bypasses caps but is still logged." |
| "How do you monitor the system?" | "Kafka consumer lag per topic, delivery success rate per channel per provider, P99 latency for critical notifications, DLQ depth, and provider error rates. Alert on anomalies." |
| "What about ordering?" | "We don't guarantee global ordering -- it's unnecessary for notifications. For in-app, we order by timestamp on the client side. If strict ordering were needed, we'd partition by user_id." |
| "How would you add a new channel (e.g., WhatsApp)?" | "Add a new consumer group (WhatsApp Worker), a new provider integration, a new entry in user_preference, and new templates. The architecture is channel-agnostic by design -- Kafka topics are priority-based, not channel-based." |

### Whiteboard Sketch Order (Interview Pacing)

```
Minutes 0-3:   Requirements gathering, clarify scope
Minutes 3-8:   API design (draw the 3 main endpoints)
Minutes 8-15:  High-level architecture (the big diagram)
Minutes 15-22: Deep dive into Kafka topics, priority handling, worker pools
Minutes 22-28: Data model, database choices (Cassandra + PostgreSQL + Redis)
Minutes 28-35: Fan-out strategy, retry/DLQ, rate limiting
Minutes 35-40: Scaling, monitoring, tradeoffs
Minutes 40-45: Handle follow-up questions
```

---

*This design handles 1B notifications/day with sub-second delivery for critical messages, graceful degradation under load, and extensibility for new channels and providers.*
