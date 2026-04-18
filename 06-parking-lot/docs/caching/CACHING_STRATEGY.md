# Caching Strategy for the Parking Lot System

> Interview-ready reference for a Senior Java developer.
> For a single parking lot, caching is simple -- everything fits in memory.
> The complexity is in concurrency, not in caching.

---

## Table of Contents

| Data | Cache Location | Invalidation | Why |
|------|---------------|--------------|-----|
| Spot availability | In-memory (ConcurrentHashMap) | On park/vacate | Primary data, always hot |
| Display board | Push from cache (Observer) | On availability change | Real-time refresh |
| Active tickets | In-memory (HashMap) | On exit/payment | Few thousand max |
| Pricing rules | Strategy objects | At startup / config reload | Rarely changes |
| Multi-lot availability | Redis | Event-driven + TTL | Cross-lot coordination |

---

## 1. Spot Availability -- The Core Cache

### Why In-Memory Is Sufficient

A single parking lot has at most a few thousand spots. Each spot is ~100 bytes of data. Total memory: **~500 KB** for a 5,000-spot mega-lot. This fits trivially in the JVM heap.

```
  Spot count     Memory         External cache needed?
  ==========     ======         ======================
  100 spots      ~10 KB         No
  1,000 spots    ~100 KB        No
  5,000 spots    ~500 KB        No
  50,000 spots   ~5 MB          Still no (single JVM)
```

### Data Structure

```java
public class Floor {
    private final int floorNumber;
    private final List<ParkingSpot> spots;  // In-memory, always up to date

    public long getAvailableCount() {
        return spots.stream()
            .filter(ParkingSpot::isAvailable)
            .count();
    }

    public long getAvailableCount(SpotType type) {
        return spots.stream()
            .filter(s -> s.getSpotType() == type)
            .filter(ParkingSpot::isAvailable)
            .count();
    }
}
```

### Cache Invalidation

There is no cache invalidation problem because the in-memory data IS the source of truth. When `spot.park(vehicle)` is called, the spot's status field changes immediately. No stale cache. No TTL. No cache-aside pattern.

```
  parkVehicle(car)
      |
      +-- spot.park(car)              // status = OCCUPIED (immediate)
      |
      +-- displayBoard.update(lot)    // reads fresh status (no stale data)
```

---

## 2. Display Board -- Observer-Driven Refresh

### Push Model (Observer Pattern)

```
  ParkingService                    DisplayBoard
  ==============                    ============

  parkVehicle()
      |
      +-- spot.park(car)
      |
      +-- notifyObservers()  ------> onAvailabilityChanged(lot)
                                          |
                                          v
                                    Recalculate counts
                                    Render to LED board
                                    
                                    Floor 1: Cars 8/50 | Motor 3/10 | Large 2/5
                                    Floor 2: Cars 12/50 | Motor 5/10 | Large 0/5
```

### Pull Model (Polling Fallback)

```java
// DisplayBoard polls every 5 seconds as a fallback
// In case an Observer notification is missed
public class DisplayBoardPoller implements Runnable {
    private final ParkingLot lot;
    private final DisplayBoard board;

    @Override
    public void run() {
        while (true) {
            board.update(lot);       // Refresh from in-memory state
            Thread.sleep(5_000);     // 5-second poll interval
        }
    }
}
```

### Which Model to Use

| Model | Latency | Reliability | Complexity |
|-------|---------|-------------|------------|
| Push only | Real-time | Risk of missed event | Low |
| Poll only | 0-5 seconds | Always catches up | Low |
| **Push + poll** | Real-time + resilient | Best of both | Medium |

**Recommendation**: Push for real-time updates, poll every 5 seconds as a heartbeat/fallback.

---

## 3. Ticket Lookup -- HashMap Is Enough

### Why Not Redis

| Factor | Value |
|--------|-------|
| Active tickets at any time | ~500-2,000 (lot capacity) |
| Memory per ticket | ~200 bytes |
| Total memory | ~400 KB |
| Lookup pattern | By ticket ID (exact match) |
| Access pattern | Write once on entry, read once on exit |

This is a textbook HashMap use case. Redis would add network latency, deployment complexity, and operational burden for zero benefit.

### Data Structure

```java
public class InMemoryTicketRepository implements TicketRepository {
    // ConcurrentHashMap for thread safety
    private final Map<String, ParkingTicket> activeTickets = new ConcurrentHashMap<>();

    @Override
    public void save(ParkingTicket ticket) {
        activeTickets.put(ticket.getTicketId(), ticket);
    }

    @Override
    public Optional<ParkingTicket> findById(String ticketId) {
        return Optional.ofNullable(activeTickets.get(ticketId));
    }

    @Override
    public List<ParkingTicket> findActiveTickets() {
        return activeTickets.values().stream()
            .filter(t -> t.getExitTime() == null)
            .toList();
    }
}
```

### Ticket Lifecycle

```
  ENTRY                              EXIT
  =====                              ====

  issueTicket()                      getTicket(ticketId)
      |                                  |
      v                                  v
  activeTickets.put(id, ticket)      activeTickets.get(id)
                                         |
                                         v
                                     calculatePrice()
                                     processPayment()
                                         |
                                         v
                                     activeTickets.remove(id)  // or mark closed
```

---

## 4. Pricing Rules -- Loaded at Startup

### Why Cache in Strategy Objects

Pricing rules (hourly rates, flat rates, time-based surcharges) change infrequently -- maybe once a quarter. They are loaded at application startup and cached inside the strategy objects themselves.

```java
public class HourlyPricingStrategy implements PricingStrategy {
    // Cached at construction time -- no external lookup per call
    private final Map<SpotType, BigDecimal> hourlyRates;

    public HourlyPricingStrategy(Map<SpotType, BigDecimal> rates) {
        this.hourlyRates = Map.copyOf(rates);  // Immutable copy
    }

    @Override
    public BigDecimal calculate(Duration duration, SpotType spotType) {
        long hours = Math.max(1, (long) Math.ceil(duration.toMinutes() / 60.0));
        return hourlyRates.get(spotType).multiply(BigDecimal.valueOf(hours));
    }
}
```

### Rate Configuration

```
  Application Startup
      |
      v
  AppConfig.loadPricingRates()
      |
      +-- Read from config file / database / environment
      |
      v
  HourlyPricingStrategy(rates)    <-- rates cached in object
      |
      v
  ParkingService(... pricingStrategy ...)

  [Pricing never hits DB again until restart or explicit reload]
```

### Hot Reload (Production Enhancement)

```java
// If rates need to change without restart:
public class ConfigurablePricingStrategy implements PricingStrategy {
    private volatile Map<SpotType, BigDecimal> hourlyRates;

    public void reloadRates(Map<SpotType, BigDecimal> newRates) {
        this.hourlyRates = Map.copyOf(newRates);  // Atomic swap
    }
}
```

---

## 5. Multi-Lot Chain -- When Caching Matters

When the interviewer asks "What about 50 lots across a city?", NOW external caching becomes relevant.

### Architecture

```
  +----------+    +----------+    +----------+
  |  Lot A   |    |  Lot B   |    |  Lot C   |
  | (local   |    | (local   |    | (local   |
  |  memory) |    |  memory) |    |  memory) |
  +----+-----+    +----+-----+    +----+-----+
       |               |               |
       v               v               v
  +----+---------------+---------------+----+
  |            Redis Cluster                |
  | Key: "lot:A:available" = 42             |
  | Key: "lot:B:available" = 15             |
  | Key: "lot:C:available" = 0              |
  | TTL: 30 seconds (auto-expire stale)     |
  +----+------------------------------------+
       |
       v
  +----+-----+    +----------+
  | API      |    | Mobile   |
  | Gateway  |    | App CDN  |
  +----------+    +----------+
```

### Caching Layers for Multi-Lot

| Layer | Technology | Data | TTL | Consistency |
|-------|-----------|------|-----|-------------|
| Lot-local | JVM heap | Exact spot status | N/A (source of truth) | Strong |
| Cross-lot | Redis | Aggregate counts per lot | 30 seconds | Eventual |
| API response | HTTP cache headers | "Lot A: 42 available" | 10 seconds | Eventual |
| Mobile app | CDN + local cache | Lot list + availability | 30 seconds | Eventual |

### Event-Driven Updates

```
  Lot A: car parks
      |
      v
  Update local state (immediate)
      |
      v
  Publish event to Kafka/SNS:
  { "lotId": "A", "available": 41, "timestamp": "..." }
      |
      v
  Redis subscriber updates: SET lot:A:available 41
      |
      v
  Mobile app receives push notification (optional)
```

---

## Caching Decision Flowchart

```
  "Should I use an external cache?"
      |
      +-- Single lot?
      |       |
      |       YES --> In-memory only. HashMap / ConcurrentHashMap.
      |               No Redis, no Memcached, no external cache.
      |
      +-- Multi-lot, same region?
      |       |
      |       YES --> Redis for cross-lot aggregated counts.
      |               Each lot still uses local memory for its own spots.
      |
      +-- Multi-lot, global?
              |
              YES --> Redis cluster (regional replicas) + CDN for mobile.
                      Event bus (Kafka/SNS) for real-time propagation.
```

---

## Interview Insight

> "For a single parking lot, caching is simple -- everything fits in memory. A 5,000-spot lot uses less than 1 MB of heap. The source of truth is the in-memory data structure, updated atomically on park/vacate. There is no cache invalidation problem because there is no separate cache -- the data structure IS the cache.
>
> The complexity in a parking lot system is concurrency (two cars competing for the same spot), not caching. I would spend zero time on Redis/Memcached for a single lot and all my time on thread-safe spot assignment.
>
> For a multi-lot chain, that changes: Redis for cross-lot availability, CDN for mobile app, event bus for real-time updates. But each individual lot remains in-memory."

---

## Summary

| Data | Single Lot | Multi-Lot Chain |
|------|-----------|-----------------|
| Spot availability | `List<ParkingSpot>` in JVM | Local JVM + Redis aggregate |
| Display board | Observer push + 5s poll | MQTT push + HTTP fallback |
| Active tickets | `ConcurrentHashMap<String, Ticket>` | Local HashMap + PostgreSQL |
| Pricing rules | Strategy object fields | Config service + local cache |
| Cross-lot counts | N/A | Redis with 30s TTL |
| Mobile app | N/A | CDN + push notifications |
