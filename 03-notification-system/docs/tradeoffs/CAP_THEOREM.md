# CAP Theorem Analysis: Notification System

> How to reason about consistency, availability, and partition tolerance
> in a notification system -- and how to explain it in an interview.

---

## CAP Theorem Recap

```
                     Consistency (C)
                        /\
                       /  \
                      /    \
                     / CP   \
                    /  zone  \
                   /          \
                  /     CA     \
                 /    (no net   \
                /   partitions)  \
               /                  \
              /____________________\
  Availability (A)      AP zone     Partition
                                   Tolerance (P)

  In a distributed system, you can only guarantee TWO of three.
  Since network partitions WILL happen, the real choice is: CP or AP.
```

**Key insight for interviews:** P (partition tolerance) is not optional in any distributed system. The real question is always: when a partition happens, do you sacrifice C or A?

---

## This System is AP -- Here is Why

### The Core Argument

```
  Scenario: Network partition between notification service and email provider.

  AP (our choice):
    - Keep accepting and sending notifications
    - Some may be duplicated (at-least-once)
    - User gets their OTP / order update / alert

  CP (alternative):
    - Reject or queue notifications until consistency is confirmed
    - User does NOT get their OTP for 30 seconds
    - User cannot log in
    - User calls support
```

**A duplicate notification is annoying. A missing notification is unacceptable.**

This is especially true for:
- OTP / 2FA codes (user cannot log in)
- Order confirmations (user thinks order failed, places another)
- Payment alerts (regulatory requirement in some jurisdictions)
- Critical system alerts

### Where Consistency Matters (The Exception)

| Data | C or A? | Why |
|------|---------|-----|
| Notification delivery | **A** | Duplicate is OK. Missing is not. |
| Notification status logs | **A** | Eventually consistent is fine. |
| **User preferences (opt-out)** | **C** | If user opts out and we send anyway, that is a compliance violation (CAN-SPAM, GDPR). Read from source of truth, not stale cache. |
| **Frequency caps** | **A** | Slightly exceeding a cap is OK. Under-delivering is worse. |
| Template content | **A** | Stale template for a few seconds is acceptable. |

### The Preference Exception in Detail

```
  User opts out of marketing emails at 10:00:00 AM.

  WRONG (AP everywhere):
    10:00:01 - Cache still has old preference
    10:00:02 - Marketing email sent  <-- violation!
    10:00:05 - Cache refreshes

  RIGHT (CP for preferences):
    10:00:01 - Preference check reads from DB (source of truth)
    10:00:02 - Marketing email blocked
    OR:
    10:00:01 - Preference change event invalidates cache immediately
    10:00:02 - Cache miss -> read from DB -> block
```

**Rule of thumb:** Fail-open for delivery (send it), fail-closed for opt-out enforcement (block it).

---

## Delivery Semantics: The Tradeoffs Table

| Semantic | How It Works | Pros | Cons | Use Case |
|----------|-------------|------|------|----------|
| **At-most-once** | Fire and forget. No retry. | Simplest. No duplicates. | Messages lost on failure. | Non-critical analytics events. |
| **At-least-once** | Retry on failure. Ack after processing. | No message loss. Reliable. | Duplicates possible. Needs dedup for critical paths. | **Our choice.** Notifications, order events. |
| **Exactly-once** | Idempotency key + dedup store + transactional processing. | Perfect semantics. No loss, no duplicates. | Hard to implement. Higher latency. More infrastructure. | Payment notifications, billing events. |

### Our Implementation: At-Least-Once with Idempotency

```
  Send Request
       |
       v
  Generate idempotency key: hash(userId + templateId + channel + truncatedTimestamp)
       |
       v
  Check Redis: SET NX key TTL 24h
       |
       +-- Key exists? --> skip (already sent, this is a duplicate)
       |
       +-- Key new? --> proceed with send
                             |
                             +-- Success --> done
                             |
                             +-- Failure --> retry (key already set,
                                            but that is OK -- we WANT
                                            to retry the same message)
```

**Why not exactly-once everywhere?**
- Exactly-once requires coordination between the notification service, the queue, and the external provider (FCM, SES, Twilio).
- External providers do NOT support transactional semantics. Once you call the Twilio API, you cannot "uncommit" an SMS.
- The cost and complexity are justified only for payment/billing notifications.

---

## Ordering Guarantees

```
  Global ordering:    NOT required.
                      Push and email for the same event can arrive in any order.

  Per-user ordering:  NICE TO HAVE for in-app notifications.
                      User's notification feed should show newest first.
                      Achieved via: Kafka partition key = userId
                      (all events for one user go to same partition = ordered)

  Per-channel:        NOT required.
                      SMS #2 can arrive before SMS #1. Users expect this.

  Causal ordering:    SOMETIMES needed.
                      "Order confirmed" must come after "Order placed."
                      Achieved via: sequence numbers or causal dependency tracking.
```

---

## Practical Interview Answer

> "This notification system is AP. When there is a network partition, we keep sending
> notifications because a missing OTP or order confirmation is far worse than a duplicate.
> We handle duplicates with idempotency keys in Redis -- hash of userId, templateId, channel,
> and a time window. The one exception is user preferences: when a user opts out, that must
> be honored immediately, so we read preferences from the source of truth, not a stale cache.
> We use at-least-once delivery semantics and upgrade to exactly-once with idempotency keys
> only where duplication would be harmful, like payment notifications."

---

## Fail-Open vs Fail-Closed Decision Matrix

| Component Down | Behavior | Rationale |
|---------------|----------|-----------|
| Template cache miss | **Fail-open**: fetch from DB, send with slight delay | Missing notification is worse than 50ms extra latency |
| Preference service down | **Fail-closed**: do NOT send | Sending to an opted-out user is a compliance violation |
| Rate limiter (Redis) down | **Fail-open**: send without rate check | Slightly exceeding frequency cap is acceptable |
| Dedup cache (Redis) down | **Fail-open**: send (may duplicate) | Duplicate is better than missing |
| DLQ is full | **Fail-closed**: alert, stop processing | Silently dropping failed messages means undetected data loss |

---

## Follow-Up Questions and Answers

### Q1: "What if Redis (dedup cache) goes down? Do you stop sending notifications?"

> No. We fail-open. Redis being down means we cannot dedup, so some users might get a
> duplicate notification. That is acceptable. We alert the on-call engineer, but we do NOT
> stop the pipeline. The only thing that stops the pipeline is the preference service being
> down -- we will not risk sending to opted-out users.

### Q2: "How do you handle exactly-once for payment notifications?"

> We use an idempotency key (hash of transaction ID + channel) stored in Redis with a 24-hour
> TTL. Before sending, we do a SET NX. If the key already exists, we skip. This is not true
> exactly-once (the external provider might still duplicate), but it is as close as we can get
> without provider-side dedup support. For payment SMS specifically, we also store a sent flag
> in the database as a second check.

### Q3: "How do you prevent a thundering herd after a partition heals?"

> When a partition heals, queued notifications could flood the system. We handle this with:
> 1. Rate limiting per channel at the consumer level (token bucket).
> 2. Kafka consumer groups with controlled concurrency (max.poll.records).
> 3. Priority queues: OTP and critical alerts drain first, marketing can wait.
> 4. Circuit breaker on external providers: if FCM returns 429, back off exponentially.

### Q4: "You said at-least-once. How many retries? What is the backoff?"

> Exponential backoff with jitter: 1s, 2s, 4s, 8s, 16s (5 retries max). After 5 failures,
> the notification goes to a Dead Letter Queue. A separate process reviews the DLQ:
> - Transient errors (timeout, 503): retry after 1 hour.
> - Permanent errors (invalid token, invalid email): mark as FAILED, do not retry.
> - Unknown errors: alert on-call for manual review.

### Q5: "How would you migrate from at-least-once to exactly-once?"

> Incrementally, per channel:
> 1. Add idempotency key generation to the notification model.
> 2. Add Redis SET NX check before send in each handler.
> 3. For channels where the provider supports dedup (e.g., SES MessageDeduplicationId),
>    pass the idempotency key to the provider.
> 4. For channels without provider dedup (SMS), accept that the last mile may still duplicate
>    and document this as a known limitation.
> This is a spectrum, not a binary switch.

### Q6: "What happens if Kafka loses a message?"

> Kafka with acks=all and replication factor 3 makes message loss extremely unlikely. But
> if it happens: the notification is simply not delivered. Our monitoring catches this via
> a "sent but not delivered within SLA" alert. The user or upstream system can retry via the
> API. We do NOT build complex reconciliation for an event that should never happen with
> proper Kafka configuration -- that would be over-engineering.
