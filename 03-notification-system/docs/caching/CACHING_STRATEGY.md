# Caching Strategy: Notification System

> What to cache, what NOT to cache, invalidation strategies, and how to discuss caching
> tradeoffs in a system design interview.

---

## Caching Overview

```
  Request Flow with Cache Layers
  ================================

  NotificationService
       |
       v
  +------------------+     +------------------+     +------------------+
  | L1: Local Cache  | --> | L2: Redis        | --> | L3: Database     |
  | (Caffeine)       |     | (Distributed)    |     | (Source of Truth)|
  +------------------+     +------------------+     +------------------+
  | In-process        |     | Shared across     |     | PostgreSQL /     |
  | Fastest (~1us)    |     | all instances     |     | Cassandra        |
  | Small (100MB)     |     | (~1ms)            |     | (~5-50ms)        |
  | Per-instance      |     | Large (GBs)       |     | Authoritative    |
  +------------------+     +------------------+     +------------------+

  Read: L1 -> miss -> L2 -> miss -> L3 -> populate L2 -> populate L1
```

---

## What to Cache

### 1. Templates -- Cache Aggressively

```
  Key:        tmpl:{templateId}:{locale}
  TTL:        Indefinite (until explicitly invalidated)
  Storage:    L1 (Caffeine, 1000 entries) + L2 (Redis)
  Hit ratio:  ~99% (templates rarely change)

  Rationale:  Templates are read on EVERY notification send.
              They change maybe once a week.
              This is the highest-ROI cache in the system.
```

| Property | Value |
|----------|-------|
| Read frequency | Every notification send |
| Write frequency | Rarely (weekly/monthly) |
| Staleness tolerance | Minutes (old template wording is not harmful) |
| Size | Small (~1-5KB per template) |
| Invalidation | Explicit on template update |

### 2. User Preferences -- Cache with Short TTL

```
  Key:        pref:{userId}
  TTL:        5 minutes
  Storage:    L2 (Redis only -- NOT L1, too many users)
  Hit ratio:  ~95% (most users never change preferences)

  Rationale:  Must respect opt-out within a reasonable window.
              5-minute TTL balances performance vs compliance.
              Critical opt-outs (unsubscribe) trigger immediate invalidation.
```

| Property | Value |
|----------|-------|
| Read frequency | Every notification send |
| Write frequency | Rarely (user changes settings) |
| Staleness tolerance | **Low** -- 5 min max. Opt-out must be honored quickly. |
| Size | Small (~200 bytes per user) |
| Invalidation | TTL expiry + explicit invalidation on opt-out |

**Why not L1 for preferences?**
If you have 10M users and 5 service instances, L1 caching means 5 separate copies that can go stale independently. A user opts out, but 4 of 5 instances still have the old preference. Redis (L2) is shared, so one invalidation covers all instances.

### 3. Device Tokens -- Cache with Long TTL

```
  Key:        tokens:{userId}
  Value:      Set of {token, platform, lastSeen}
  TTL:        24 hours
  Storage:    L2 (Redis)
  Hit ratio:  ~98%

  Rationale:  Device tokens rarely change (only on reinstall/clear data).
              24h TTL is safe because stale tokens result in a 410 from
              FCM/APNs, which triggers immediate invalidation.
              Self-healing: bad token -> 410 -> evict -> next send fetches fresh.
```

| Property | Value |
|----------|-------|
| Read frequency | Every push notification |
| Write frequency | On app install, token refresh |
| Staleness tolerance | High (stale token = 410 error = auto-evict) |
| Size | ~100 bytes per token, ~3 tokens per user |
| Invalidation | TTL + immediate on 410 response |

### 4. Dedup Keys (Idempotency) -- Redis SET

```
  Key:        dedup:{hash(userId + templateId + channel + timeWindow)}
  Value:      1 (just a flag)
  TTL:        24 hours
  Storage:    Redis only (SET NX -- atomic check-and-set)

  Rationale:  Prevents duplicate sends within a 24-hour window.
              Uses SET NX for atomic "check if exists, set if not" in one call.
              No L1 cache -- must be globally consistent across all instances.
```

| Property | Value |
|----------|-------|
| Read frequency | Every notification send |
| Write frequency | Every notification send |
| Staleness tolerance | **None** -- must be globally consistent |
| Size | ~64 bytes per key |
| Invalidation | TTL only (auto-expires after 24h) |

---

## What NOT to Cache

### Notification Delivery Status

```
  NEVER CACHE delivery status. Always read from the source of truth.

  Why:
  - Status changes rapidly: PENDING -> SENT -> DELIVERED -> READ
  - Stale status = incorrect UI ("still sending" when already delivered)
  - Debugging with stale status is a nightmare
  - Status is read infrequently (only when user checks or for monitoring)
  - The read pattern is already optimized by Cassandra (partition key = userId)
```

### Rate Limiting Counters

```
  These live in Redis but are NOT a "cache" -- Redis IS the source of truth.

  Key:        ratelimit:{userId}:{channel}:{date}
  Operation:  INCR (atomic increment)
  TTL:        End of day (86400 seconds, set on first INCR)

  If Redis is down, we fail-open (allow the send, accept over-limit risk).
  We do NOT fall back to a DB for rate limiting -- the latency would kill throughput.
```

---

## Cache Layers: When to Use Each

```
  +---------------------+-------------------+-------------------+
  |                     | L1 (Caffeine)     | L2 (Redis)        |
  +---------------------+-------------------+-------------------+
  | Latency             | ~1 microsecond    | ~1 millisecond    |
  | Shared across nodes | No (per-instance) | Yes (cluster)     |
  | Size limit          | ~100MB heap       | GBs               |
  | Consistency         | Weakest           | Strong (single)   |
  | Use for             | Hot, rarely       | Shared, frequently|
  |                     | changing data     | changing data     |
  +---------------------+-------------------+-------------------+

  Decision Matrix:
  ================
  Data changes rarely + small dataset  --> L1 + L2  (templates)
  Data changes rarely + large dataset  --> L2 only  (preferences)
  Must be globally consistent          --> L2 only  (dedup keys)
  Operational counter                  --> L2 only  (rate limits)
```

---

## Invalidation Strategies

### Template Cache Invalidation

```
  Template Updated (via admin API)
       |
       v
  1. Write new template to PostgreSQL
  2. Publish event: "template.updated" {templateId, locale}
  3. All service instances receive event:
       a. Delete from L1 (Caffeine): cache.invalidate("tmpl:" + id)
       b. Delete from L2 (Redis):    DEL tmpl:{id}:{locale}
  4. Next read triggers cache miss -> re-populate from DB

  Why event-based (not TTL)?
  - Templates are cached indefinitely (no TTL)
  - We want the update to take effect within seconds, not minutes
  - Event = explicit, deterministic invalidation
```

### Preference Cache Invalidation

```
  User Updates Preferences (e.g., opts out of email)
       |
       v
  1. Write to PostgreSQL (source of truth)
  2. DELETE from Redis: DEL pref:{userId}
  3. Next notification send -> cache miss -> read fresh from DB

  Dual protection:
  - Explicit invalidation on write (immediate)
  - 5-minute TTL as a safety net (catches missed invalidations)

  Critical path for opt-out:
  - The preference API MUST invalidate the cache before returning 200
  - If cache invalidation fails, the API should still return 200
    (DB is the source of truth), but log an alert
```

### Device Token Invalidation

```
  Three triggers:
  ===============

  1. App reports new token (on launch):
     --> Upsert in DB + SET in Redis

  2. FCM/APNs returns 410 (unregistered):
     --> DELETE from DB + DEL from Redis
     --> This is self-healing: bad tokens auto-evict

  3. 24h TTL expires:
     --> Next push -> cache miss -> fetch from DB
     --> If token is still valid, re-cache
     --> If token was deleted (user uninstalled), DB returns empty
```

---

## Frequency Cap Tracking

```
  Purpose: Limit how many notifications a user receives per channel per day.
  Example: Max 3 marketing emails/day, max 5 SMS/day (except OTP).

  Implementation:
  ===============

  Key:     ratelimit:{userId}:{channel}:{date}
  Example: ratelimit:u-123:email:2024-01-15

  On each send attempt:
  ---------------------
  count = Redis INCR ratelimit:{userId}:{channel}:{date}
  if count == 1:
      Redis EXPIRE key 86400          // auto-cleanup next day
  if count > channelLimit:
      REJECT notification
      log.warn("Frequency cap hit: userId={}, channel={}, count={}")
      return

  Channel Limits:
  ===============
  | Channel | Daily Limit | Exception |
  |---------|------------|-----------|
  | Push    | 10         | None      |
  | Email   | 5          | Transactional (OTP, receipts) bypass |
  | SMS     | 3          | OTP bypasses |
  | In-App  | No limit   | Always deliver |

  Priority Override:
  - CRITICAL and HIGH priority notifications bypass frequency caps
  - Only MEDIUM and LOW are rate-limited
  - This ensures OTP and security alerts always get through
```

---

## Risks and Mitigations

### Risk 1: Stale Preferences (The Big One)

```
  Scenario:
  ---------
  10:00:00 - User opts out of marketing email
  10:00:01 - Cache still has "opted_in" (5-min TTL)
  10:00:02 - Marketing email sent to opted-out user  <-- BAD

  Mitigation:
  -----------
  1. Explicit cache invalidation on preference write (not just TTL)
  2. Preference update API does: DB write -> Redis DEL -> return 200
  3. Belt and suspenders: 5-min TTL catches any missed invalidations
  4. For legal-sensitive channels (email marketing), ALWAYS read from DB
     (skip cache entirely for marketing sends -- they are not latency-critical)
```

### Risk 2: Cache Stampede on Popular Templates

```
  Scenario:
  ---------
  A system-wide notification uses template "tmpl-maintenance-alert."
  Template cache entry expires.
  1000 notification workers simultaneously miss cache.
  1000 simultaneous DB queries for the same template.

  Mitigation:
  -----------
  1. Cache-aside with locking (singleflight pattern):
     - First miss acquires a Redis lock: SET NX lock:tmpl:{id} TTL 5s
     - Winner fetches from DB, populates cache
     - Losers wait (short spin) then read from cache
  2. Templates have no TTL -- they are invalidated explicitly.
     Stampede only happens after an explicit invalidation.
  3. Pre-warm: on deploy, load all active templates into cache.
```

### Risk 3: Redis Failure

```
  Scenario: Redis cluster goes down.

  Impact per use case:
  ====================
  | Use Case      | Redis Down Impact | Fallback |
  |---------------|-------------------|----------|
  | Template cache | +5ms latency     | Read from DB (L1 may still have it) |
  | Preference cache | +5ms latency  | Read from DB (always authoritative) |
  | Dedup keys    | Duplicates possible | Fail-open: accept duplicates |
  | Rate limiting | Caps not enforced | Fail-open: allow all sends |
  | Device tokens | +5ms latency     | Read from DB |

  None of these are catastrophic. The system degrades gracefully.
  Most important: NEVER let Redis failure stop notification delivery.
```

### Risk 4: Memory Pressure (L1 Cache)

```
  Mitigation:
  -----------
  Caffeine configuration:
    - maximumSize(1000)          // evict LRU beyond 1000 entries
    - maximumWeight(100MB)       // if entries vary in size
    - expireAfterWrite(10min)    // safety net even for "indefinite" entries
    - recordStats()              // monitor hit rate, eviction count

  Monitor: if L1 hit rate drops below 90%, either increase size or
  check if access patterns changed.
```

---

## Interview Talking Points

### "Walk me through the caching strategy."

> "We use a two-layer cache: local Caffeine for hot data like templates, and Redis for shared
> data like user preferences and dedup keys. Templates are cached indefinitely with explicit
> invalidation. Preferences have a 5-minute TTL because we need to honor opt-outs quickly.
> Dedup keys use Redis SET NX for atomic idempotency checks. The key rule: we never cache
> delivery status -- it must always be read from the source of truth."

### "What if a user opts out but the cache has not refreshed?"

> "We handle this with explicit invalidation, not just TTL. When a user updates preferences,
> we write to the database, then immediately DELETE the Redis key. The next notification
> check will be a cache miss and read the fresh value from the database. The 5-minute TTL
> is a safety net, not the primary invalidation mechanism. For legally sensitive channels
> like marketing email, we skip the cache entirely and always read from the database."

### "How do you handle cache stampede?"

> "Templates use the singleflight pattern: on a cache miss, the first worker acquires a
> Redis lock and fetches from the database. Other workers wait briefly and then read the
> freshly populated cache. But honestly, stampede is rare for us because templates have no
> TTL -- they are only invalidated explicitly, which happens infrequently. We also pre-warm
> the cache on deployment."

### "Why not cache everything in L1?"

> "L1 (Caffeine) is per-instance. If you have 10 service instances, you have 10 independent
> caches. For user preferences, a cache invalidation would need to reach all 10 instances.
> Redis (L2) is shared -- one DELETE invalidates for everyone. We use L1 only for data that
> is small, hot, and tolerant of brief staleness -- basically just templates."

---

## Quick Reference Table

| Data | L1 | L2 (Redis) | TTL | Invalidation | Staleness OK? |
|------|-----|-----------|-----|-------------|---------------|
| Templates | Yes | Yes | None (explicit) | Event-driven on update | Minutes OK |
| Preferences | No | Yes | 5 min | Explicit on opt-out + TTL | **5 min max** |
| Device tokens | No | Yes | 24h | Explicit on 410 + TTL | Hours OK (self-healing) |
| Dedup keys | No | Yes | 24h | TTL only | N/A (write-once) |
| Rate limits | No | Yes (source of truth) | Daily reset | TTL only | N/A (counter) |
| Delivery status | No | **No** | N/A | N/A | **Never cache** |
