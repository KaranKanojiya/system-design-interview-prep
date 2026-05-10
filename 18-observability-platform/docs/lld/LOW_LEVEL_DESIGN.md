# Observability Platform -- Low-Level Design

> Project 18 | ~50 Java classes | 10 GoF patterns | 12 runnable demos
> Three pillars: Metrics, Traces, Logs -- unified through correlation IDs

---

## Table of Contents

1. [Core Modules Overview](#1-core-modules-overview)
2. [Package Structure](#2-package-structure)
3. [Class Diagram (ASCII)](#3-class-diagram-ascii)
4. [Entity Design](#4-entity-design)
5. [Interface Contracts](#5-interface-contracts)
6. [Strategy Implementations](#6-strategy-implementations)
7. [Engine Design](#7-engine-design)
8. [Service Layer](#8-service-layer)
9. [Controller Layer](#9-controller-layer)
10. [Configuration and Wiring](#10-configuration-and-wiring)
11. [Display Layer](#11-display-layer)
12. [Exception Hierarchy](#12-exception-hierarchy)
13. [Concurrency Model](#13-concurrency-model)
14. [SOLID Principles Mapping](#14-solid-principles-mapping)
15. [Sample Workflows](#15-sample-workflows)
16. [Design Patterns Summary](#16-design-patterns-summary)
17. [Extensibility Points](#17-extensibility-points)
18. [Data Structures and Algorithms](#18-data-structures-and-algorithms)
19. [Interview Talking Points](#19-interview-talking-points)

---

## 1. Core Modules Overview

The platform is organized into eight packages, each with a single responsibility:

| Package       | Files | Responsibility                                      |
|---------------|-------|-----------------------------------------------------|
| `model`       | 15    | Domain entities, enums, value objects                |
| `engine`      | 5     | Computational cores (time-series, aggregation, etc.) |
| `repository`  | 8     | Data access abstraction (4 interfaces + 4 impls)    |
| `service`     | 7     | Business logic orchestration                         |
| `strategy`    | 10    | Pluggable algorithms (sampling, aggregation, alert)  |
| `controller`  | 1     | Simulated REST endpoint facade                       |
| `config`      | 1     | Composition root / dependency wiring                 |
| `display`     | 1     | Console visualization                                |
| `exception`   | 4     | Domain-specific error hierarchy                      |

Total: ~50 Java files with zero external dependencies (pure JDK 17+).

---

## 2. Package Structure

```
src/main/java/com/systemdesign/observability/
|
|-- model/                          # Domain entities and value objects
|   |-- Metric.java                 # Aggregated metric definition (Builder)
|   |-- MetricPoint.java            # Immutable single data point
|   |-- MetricType.java             # Enum: COUNTER, GAUGE, HISTOGRAM, TIMER
|   |-- Span.java                   # Single unit of work in a trace (Builder)
|   |-- SpanLog.java                # Immutable event attached to a span
|   |-- SpanStatus.java             # Enum: OK, ERROR, TIMEOUT, CANCELLED
|   |-- Trace.java                  # Tree of spans for one request
|   |-- TraceContext.java           # Context propagation across services
|   |-- LogEntry.java               # Structured log with optional correlation
|   |-- LogLevel.java               # Enum: TRACE, DEBUG, INFO, WARN, ERROR, FATAL
|   |-- Alert.java                  # Fired alert instance
|   |-- AlertRule.java              # Declarative alert definition (Builder)
|   |-- AlertSeverity.java          # Enum: INFO, WARNING, CRITICAL, PAGE
|   |-- AlertStatus.java            # Enum: PENDING, FIRING, ACKNOWLEDGED, RESOLVED
|   |-- ServiceNode.java            # Vertex in service dependency graph
|
|-- engine/                         # Computational cores
|   |-- TimeSeriesStore.java        # TreeMap-based bucketed storage
|   |-- TraceAssembler.java         # Span buffer and trace assembly
|   |-- MetricAggregator.java       # Statistical functions (sum, avg, percentile)
|   |-- SamplingEngine.java         # Delegates to SamplingStrategy
|   |-- LogProcessor.java           # Filter pipeline for log entries
|
|-- repository/                     # Data access layer
|   |-- MetricRepository.java       # Interface
|   |-- InMemoryMetricRepository.java
|   |-- TraceRepository.java        # Interface
|   |-- InMemoryTraceRepository.java
|   |-- LogRepository.java          # Interface
|   |-- InMemoryLogRepository.java
|   |-- AlertRepository.java        # Interface
|   |-- InMemoryAlertRepository.java
|
|-- service/                        # Business logic
|   |-- MetricService.java          # Metric recording, storage, querying
|   |-- TracingService.java         # Trace lifecycle management
|   |-- LogService.java             # Log ingestion and search
|   |-- AlertService.java           # Alert rule evaluation
|   |-- DashboardService.java       # Cross-pillar dashboard views
|   |-- ServiceMapService.java      # Service dependency topology
|   |-- ObservabilityService.java   # Facade over all services
|
|-- strategy/                       # Pluggable algorithms
|   |-- sampling/
|   |   |-- SamplingStrategy.java           # Interface
|   |   |-- HeadBasedSamplingStrategy.java  # Deterministic hash of traceId
|   |   |-- TailBasedSamplingStrategy.java  # Post-collection error/latency filter
|   |   |-- RateLimitedSamplingStrategy.java # Token-bucket per second
|   |-- aggregation/
|   |   |-- AggregationStrategy.java        # Interface
|   |   |-- PercentileAggregationStrategy.java # Nearest-rank percentile
|   |   |-- RateAggregationStrategy.java    # Delta-value / delta-time
|   |-- alerting/
|       |-- AlertingStrategy.java           # Interface
|       |-- ThresholdAlertingStrategy.java  # Static threshold comparison
|       |-- AnomalyDetectionAlertingStrategy.java # Mean +/- k*stdDev
|
|-- controller/
|   |-- ObservabilityController.java  # Simulated REST endpoints
|
|-- config/
|   |-- AppConfig.java               # Composition root (Factory + lazy init)
|
|-- display/
|   |-- ObservabilityStatsDisplay.java # Console visualization
|
|-- exception/
|   |-- ObservabilityException.java   # Base unchecked exception
|   |-- MetricIngestionException.java # Metric ingestion failures
|   |-- TraceAssemblyException.java   # Trace assembly failures
|   |-- AlertEvaluationException.java # Alert evaluation failures
|
|-- ObservabilityPlatformApp.java     # Main: 12 demos
```

---

## 3. Class Diagram (ASCII)

### 3.1 High-Level Module Dependencies

```
                        +-------------------------+
                        | ObservabilityPlatformApp |
                        +------------+------------+
                                     |
                                     v
                        +-------------------------+
                        |       AppConfig          |
                        | (Factory + Composition   |
                        |  Root + Lazy Init)        |
                        +-----+-------+-----------+
                              |       |
              +---------------+       +----------------+
              v                                        v
  +-----------------------+              +----------------------------+
  | ObservabilityController|             | ObservabilityStatsDisplay  |
  +-----------+-----------+              +-------------+--------------+
              |                                        |
              v                                        v
  +-----------------------+        +------+------+------+------+------+
  | ObservabilityService  |------->|Metric|Tracing| Log |Alert |Svc   |
  |      (FACADE)         |        |Svc   |Svc    | Svc |Svc   |MapSvc|
  +-----------------------+        +--+---+---+---+--+--+--+---+--+---+
                                      |       |      |     |      |
                                      v       v      v     v      v
                              +-------+--+  +-+--+ +-+--+ +-+--+  |
                              |TimeSeries|  |Trace| |Log | |Alert| |
                              |Store     |  |Assem| |Proc| |Repo | |
                              +----------+  +----+  +----+ +-----+ |
                              |Metric     |                         |
                              |Aggregator |  <-- Engines            |
                              +-----------+                         |
                              |Sampling   |                         |
                              |Engine     |                         |
                              +-----------+                         |
                                                                    |
                                      +-----------------------------+
                                      v
                              +----------------+
                              | ServiceNode    |
                              | (graph vertex) |
                              +----------------+
```

### 3.2 Strategy Pattern Family

```
                  +-------------------+
                  | SamplingStrategy  |  <<interface>>
                  +--------+----------+
                           |
            +--------------+---+----------------+
            |                  |                 |
  +---------+--------+ +------+--------+ +------+---------+
  |HeadBasedSampling | |TailBasedSamp  | |RateLimitedSamp |
  |Strategy          | |Strategy       | |Strategy        |
  +---------+--------+ +------+--------+ +------+---------+
            |                  |                 |
            v                  v                 v
     hash(traceId)     check baggage     token bucket
     mod sampleRate    error/latency     per second

                  +---------------------+
                  | AggregationStrategy |  <<interface>>
                  +---------+-----------+
                            |
               +------------+------------+
               |                         |
   +-----------+---------+  +------------+----------+
   |PercentileAggregation|  |RateAggregation        |
   |Strategy             |  |Strategy               |
   +---------------------+  +-----------------------+
        nearest-rank              delta / time

                  +--------------------+
                  | AlertingStrategy   |  <<interface>>
                  +---------+----------+
                            |
               +------------+------------+
               |                         |
   +-----------+---------+  +------------+----------+
   |ThresholdAlerting    |  |AnomalyDetection       |
   |Strategy             |  |AlertingStrategy       |
   +---------------------+  +-----------------------+
      avg vs threshold        mean +/- k*stdDev
```

### 3.3 Repository Pattern

```
  +-------------------+          +--------------------------+
  | MetricRepository  |<|--------|InMemoryMetricRepository  |
  | <<interface>>     |          |  ConcurrentHashMap<id,M> |
  +-------------------+          +--------------------------+

  +-------------------+          +--------------------------+
  | TraceRepository   |<|--------|InMemoryTraceRepository   |
  | <<interface>>     |          |  ConcurrentHashMap<id,T> |
  +-------------------+          +--------------------------+

  +-------------------+          +--------------------------+
  | LogRepository     |<|--------|InMemoryLogRepository     |
  | <<interface>>     |          |  ConcurrentHashMap<seq,L>|
  +-------------------+          +--------------------------+

  +-------------------+          +--------------------------+
  | AlertRepository   |<|--------|InMemoryAlertRepository   |
  | <<interface>>     |          |  ConcurrentHashMap<id,A> |
  +-------------------+          +--------------------------+
```

### 3.4 Builder Pattern (Metric, Span, AlertRule)

```
  +------------+       +-------------------+
  | Metric     |<------| Metric.Builder    |
  |  - id      |       |  + name(String)   |
  |  - name    |       |  + metricType()   |
  |  - type    |       |  + description()  |
  |  - tags    |       |  + unit()         |
  |  - points  |       |  + tags()         |
  |  - created |       |  + dataPoints()   |
  +------------+       |  + build():Metric |
                       +-------------------+

  +------------+       +-------------------+
  | Span       |<------| Span.Builder      |
  |  - traceId |       |  + traceId(String)|
  |  - spanId  |       |  + operationName()|
  |  - parent  |       |  + serviceName()  |
  |  - op      |       |  + parentSpanId() |
  |  - service |       |  + status()       |
  |  - tags    |       |  + tags()         |
  |  - logs    |       |  + build():Span   |
  +------------+       +-------------------+

  +------------+       +-------------------+
  | AlertRule  |<------| AlertRule.Builder  |
  |  - name    |       |  + name(String)   |
  |  - metric  |       |  + metricName()   |
  |  - cond    |       |  + condition()    |
  |  - thresh  |       |  + threshold()    |
  |  - dur     |       |  + severity()     |
  |  - sev     |       |  + enabled()      |
  +------------+       |  + build():Rule   |
                       +-------------------+
```

### 3.5 Exception Hierarchy

```
  RuntimeException
       |
       v
  ObservabilityException
       |
       +-----> MetricIngestionException
       |           + metricName: String
       |
       +-----> TraceAssemblyException
       |           + traceId: String
       |
       +-----> AlertEvaluationException
                   + ruleName: String
```

---

## 4. Entity Design

### 4.1 Metric (Builder Pattern)

**Purpose**: Aggregated metric definition with its collected data points.

```java
public class Metric {
    private final String id;                    // UUID
    private final String name;                  // e.g. "http.request.duration_ms"
    private final MetricType metricType;        // COUNTER | GAUGE | HISTOGRAM | TIMER
    private final String description;           // human-readable description
    private final String unit;                  // e.g. "ms", "bytes", "%"
    private final Map<String, String> tags;     // label key-value pairs
    private final List<MetricPoint> dataPoints; // collected observations
    private final Instant createdAt;            // creation timestamp
}
```

**Key design decisions**:
- Private constructor forces use of the `Builder` -- prevents partially initialized objects.
- `tags` and `dataPoints` are defensively copied in the constructor.
- `getDataPoints()` returns `Collections.unmodifiableList()` to prevent mutation.
- `getPointsInRange(from, to)` provides time-window filtering at the entity level.

**MetricType enum**:

| Type      | Semantics                                      | Example                |
|-----------|-------------------------------------------------|------------------------|
| COUNTER   | Monotonically increasing value                  | request count          |
| GAUGE     | Point-in-time value, can go up or down          | CPU usage, active conns|
| HISTOGRAM | Distribution of values across buckets           | response size          |
| TIMER     | Duration measurement (specialized histogram)    | response latency       |

### 4.2 MetricPoint (Immutable Value Object)

**Purpose**: A single metric observation -- immutable snapshot at a point in time.

```java
public class MetricPoint {
    private final String name;              // metric name
    private final double value;             // numeric value
    private final Instant timestamp;        // when the measurement was taken
    private final MetricType metricType;    // the type of metric
    private final Map<String, String> tags; // immutable via Map.copyOf()
}
```

**Key design decisions**:
- Fully immutable -- no setters, `Map.copyOf()` for tags.
- Factory method `MetricPoint.of(name, value, type, tags)` stamps `Instant.now()` automatically.
- This is the fundamental unit stored in `TimeSeriesStore`.

### 4.3 Span (Builder Pattern)

**Purpose**: One unit of work in a distributed trace, representing one operation within one service.

```java
public class Span {
    private final String traceId;           // links all spans in one trace
    private final String spanId;            // unique within the trace (UUID)
    private final String parentSpanId;      // nullable -- root spans have no parent
    private final String operationName;     // e.g. "POST /api/orders"
    private final String serviceName;       // e.g. "api-gateway"
    private final Instant startTime;        // auto-set via Builder
    private Instant endTime;                // set when finish() is called
    private Duration duration;              // computed from start/end
    private SpanStatus status;              // OK, ERROR, TIMEOUT, CANCELLED
    private final Map<String, String> tags; // metadata labels
    private final List<SpanLog> logs;       // events within the span
}
```

**Lifecycle**:
1. Created via `Span.Builder(traceId, operationName, serviceName).build()`
2. Tags and logs added during execution: `addTag()`, `addLog()`
3. `finish()` stamps `endTime`, computes `duration`, defaults `status` to OK

**SpanLog** (immutable value object):
```java
public class SpanLog {
    private final Instant timestamp;
    private final String event;             // e.g. "cache.miss", "db.query.slow"
    private final Map<String, String> fields;
}
```

**SpanStatus enum**: `OK`, `ERROR`, `TIMEOUT`, `CANCELLED`

### 4.4 Trace

**Purpose**: A complete distributed trace -- a directed tree of Spans rooted at a single entry point.

```java
public class Trace {
    private final String traceId;       // shared by all spans
    private Span rootSpan;              // first span with no parent
    private final List<Span> spans;     // all spans in this trace
    private final String serviceName;   // service that initiated the trace
    private final Instant startTime;    // trace creation time
    private Duration duration;          // recomputed on each addSpan()
}
```

**Key methods**:
- `addSpan(span)` -- adds span, auto-detects root (parentSpanId == null), recalculates duration.
- `buildSpanTree()` -- returns `Map<String, List<Span>>` adjacency list for rendering.
- `getErrorSpans()` -- filters spans with ERROR or TIMEOUT status.
- `getSpansByService(name)` -- finds all spans belonging to a given service.

**Duration recalculation**: walks all spans to find earliest start and latest end.

### 4.5 TraceContext

**Purpose**: Carries trace identity and baggage across service boundaries for distributed context propagation.

```java
public class TraceContext {
    private final String traceId;           // shared across all services
    private final String spanId;            // current active span
    private final String parentSpanId;      // parent span (nullable for root)
    private final boolean sampled;          // sampling decision
    private final Map<String, String> baggage; // cross-service metadata
}
```

**Key methods**:
- `TraceContext.newTrace()` -- factory for fresh trace (new UUID for traceId and spanId).
- `createChild(newSpanId)` -- creates child context (current spanId becomes parentSpanId).
- `setBaggage(key, value)` -- injects metadata for tail-based sampling decisions.

**Interview note**: In production, TraceContext maps to the W3C `traceparent` header:
`traceparent: 00-{traceId}-{spanId}-{flags}`

### 4.6 LogEntry

**Purpose**: Structured log entry with optional trace correlation.

```java
public class LogEntry {
    private final String id;                    // UUID
    private final Instant timestamp;            // auto-set
    private final LogLevel level;               // TRACE..FATAL
    private final String message;               // log message
    private final String serviceName;           // originating service
    private String traceId;                     // nullable -- set for correlated logs
    private String spanId;                      // nullable -- set for correlated logs
    private final Map<String, String> attributes; // structured key-value pairs
}
```

**LogLevel enum** (ordered by severity):

| Level | Severity | Use Case                     |
|-------|----------|------------------------------|
| TRACE | 0        | Finest-grain debugging        |
| DEBUG | 1        | Developer debugging           |
| INFO  | 2        | Normal operational events     |
| WARN  | 3        | Potential issues              |
| ERROR | 4        | Failures requiring attention  |
| FATAL | 5        | System-level failures         |

`isAtLeast(LogLevel other)` -- enables level-based filtering.

### 4.7 Alert and AlertRule

**Alert** -- a fired alert instance:
```java
public class Alert {
    private final String id;            // UUID
    private final AlertRule rule;       // originating rule
    private AlertStatus status;         // PENDING -> FIRING -> ACKNOWLEDGED -> RESOLVED
    private final Instant triggeredAt;  // when the alert fired
    private Instant resolvedAt;         // null until resolved
    private final double currentValue;  // metric value that triggered the alert
    private final String message;       // human-readable description
}
```

**AlertRule** (Builder Pattern) -- declarative alert definition:
```java
public class AlertRule {
    private final String id;                // UUID
    private final String name;              // e.g. "High CPU Alert"
    private final String metricName;        // metric to watch
    private final String condition;         // ">" or "<"
    private final double threshold;         // threshold value
    private final int durationSeconds;      // how long condition must hold (default 60)
    private final AlertSeverity severity;   // INFO | WARNING | CRITICAL | PAGE
    private final boolean enabled;          // toggle without deleting
}
```

**Alert lifecycle**:
```
PENDING --> FIRING --> ACKNOWLEDGED --> RESOLVED
                  |                        ^
                  +------------------------+
                       (auto-resolve)
```

### 4.8 ServiceNode

**Purpose**: A vertex in the service dependency graph with rolling stats.

```java
public class ServiceNode {
    private final String serviceName;           // unique service name
    private final Set<String> dependencies;     // downstream services this node calls
    private final Set<String> dependents;       // upstream services that call this node
    private long requestCount;                  // total outgoing requests
    private double errorRate;                   // fraction of failed requests
    private double avgLatencyMs;                // average latency to downstream
}
```

---

## 5. Interface Contracts

### 5.1 Repository Interfaces

All four repositories follow a consistent contract pattern:

```java
public interface MetricRepository {
    void save(Metric metric);
    Optional<Metric> findById(String id);
    List<Metric> findByName(String name);
    List<Metric> findByType(MetricType type);
    List<Metric> findAll();
    void deleteById(String id);
}

public interface TraceRepository {
    void save(Trace trace);
    Optional<Trace> findById(String traceId);
    List<Trace> findByServiceName(String serviceName);
    List<Trace> findByTimeRange(Instant from, Instant to);
    List<Trace> findAll();
}

public interface LogRepository {
    void save(LogEntry entry);
    List<LogEntry> findByLevel(LogLevel level);       // >= level
    List<LogEntry> findByServiceName(String serviceName);
    List<LogEntry> findByTraceId(String traceId);
    List<LogEntry> findByTimeRange(Instant from, Instant to);
    List<LogEntry> findAll();
}

public interface AlertRepository {
    void save(Alert alert);
    Optional<Alert> findById(String id);
    List<Alert> findByStatus(AlertStatus status);
    List<Alert> findByRuleName(String ruleName);
    List<Alert> findAll();
    List<Alert> findFiring();
}
```

**Shared contract properties**:
- All return immutable copies (`List.copyOf()`) from `findAll()`.
- All use `ConcurrentHashMap` as the backing store in their in-memory implementations.
- All implementations are thread-safe for concurrent read/write.

### 5.2 Strategy Interfaces

```java
// --- Sampling ---
public interface SamplingStrategy {
    boolean shouldSample(TraceContext context);
    boolean shouldSample(TraceContext context, String operationName);
    String getStrategyName();
}

// --- Aggregation ---
public interface AggregationStrategy {
    double aggregate(List<MetricPoint> points);
    String getStrategyName();
}

// --- Alerting ---
public interface AlertingStrategy {
    boolean shouldAlert(AlertRule rule, List<MetricPoint> recentPoints);
    String getStrategyName();
}
```

**Design**: All three strategy families follow the same shape:
1. One core method that encapsulates the algorithm.
2. A `getStrategyName()` method for logging and display.
3. The interface is parameterized by domain-specific inputs (TraceContext, MetricPoints, AlertRule).

---

## 6. Strategy Implementations

### 6.1 Sampling Strategies

#### HeadBasedSamplingStrategy

**Algorithm**:
```
1. Compute hash = Math.abs(traceId.hashCode())
2. Normalize to 0-99 range: bucket = hash % 100
3. If bucket < (sampleRate * 100) --> SAMPLE
4. Otherwise --> DROP
```

**Key properties**:
- Deterministic: same traceId always gets the same decision.
- Consistent across services: no coordination needed.
- Operation-agnostic: both `shouldSample()` overloads produce the same result.
- Constructor validates `sampleRate` is in [0.0, 1.0].

**Trade-off**: Simple and cheap, but blindly drops interesting traces (errors, slow requests).

#### TailBasedSamplingStrategy

**Algorithm**:
```
1. shouldSample(context) --> always returns true (collect everything)
2. shouldSample(context, operationName) --> post-collection decision:
   a. If baggage["error"] == "true" --> KEEP (errors are always interesting)
   b. If baggage["latency_ms"] > latencyThresholdMs --> KEEP (slow traces matter)
   c. Otherwise --> DROP
```

**Key properties**:
- Collects all spans initially (high memory cost during collection phase).
- Real filtering happens after the full trace is assembled.
- Never loses error or high-latency traces.
- Requires a collector/aggregator layer to inject baggage before invoking the strategy.

**Trade-off**: Keeps the most valuable traces but requires buffering ALL spans initially.

#### RateLimitedSamplingStrategy

**Algorithm**:
```
1. Read current epoch second: now = System.currentTimeMillis() / 1000
2. If now != windowStart --> new window:
   a. CAS windowStart to now
   b. Reset counter to 0
3. Increment counter atomically
4. If counter <= maxPerSecond --> SAMPLE
5. Otherwise --> DROP (rate limit exceeded)
```

**Key properties**:
- Thread-safe via `AtomicInteger` (counter) and `AtomicLong` (windowStart).
- Benign race on window reset can only result in slightly more samples, never fewer.
- Provides predictable cost control regardless of traffic spikes.
- Operation-agnostic: delegates to the context-only overload.

**Trade-off**: Predictable cost, but may lose important traces during traffic spikes.

### 6.2 Aggregation Strategies

#### PercentileAggregationStrategy

**Algorithm** (nearest-rank method):
```
1. If empty list --> return 0.0
2. If single point --> return that point's value
3. Copy and sort points by value ascending
4. Compute index = ceil(percentile / 100.0 * size) - 1
5. Clamp index to [0, size-1]
6. Return sorted[index]
```

**Configuration**: Pass percentile in constructor (e.g., 99 for P99, 50 for P50).

**Interview note**: In production, exact percentile requires sorting all values (O(n log n)).
For streaming, use t-digest or DDSketch for O(1) amortized insertion with bounded error.

#### RateAggregationStrategy

**Algorithm**:
```
1. If fewer than 2 points --> return 0.0
2. Sort points by timestamp ascending (copy to avoid mutation)
3. valueDelta = last.value - first.value
4. timeDelta = Duration.between(first.timestamp, last.timestamp).toMillis() / 1000.0
5. If timeDelta <= 0 --> return 0.0
6. Return valueDelta / timeDelta  (units: value-change per second)
```

**Use case**: Counter metrics (e.g., `requests_total`) where the raw value is monotonically
increasing and we want "requests per second" on the dashboard.

### 6.3 Alerting Strategies

#### ThresholdAlertingStrategy

**Algorithm**:
```
1. Compute arithmetic mean of all recentPoints
2. Parse condition from rule (starts with ">" or "<")
3. If condition starts with "<" --> return (average < threshold)
4. Otherwise (default ">") --> return (average > threshold)
```

**Use case**: "Alert if CPU > 80%", "Alert if free disk < 10 GB".

#### AnomalyDetectionAlertingStrategy

**Algorithm**:
```
1. If fewer than 3 points --> don't alert (not enough data)
2. Compute mean = average of all point values
3. Compute population variance = avg(sum((value - mean)^2))
4. Compute stdDev = sqrt(variance)
5. Get latest point value
6. Compute deviation = abs(latest.value - mean)
7. If deviation > (stdDevMultiplier * stdDev) --> ALERT
```

**Configuration**: `stdDevMultiplier` (default 2.0 = ~95% confidence interval).

**Trade-off**: Adapts to dynamic workloads (no hardcoded thresholds), but requires
a minimum baseline of data and is sensitive to non-Gaussian distributions.

---

## 7. Engine Design

### 7.1 TimeSeriesStore

**Data structure**:
```
Map<String, TreeMap<Long, List<MetricPoint>>>
     ^              ^            ^
     |              |            |
  metricName   epochSecond   points in that second
```

The outer map is a `ConcurrentHashMap` for thread-safe metric-level access.
The inner `TreeMap<Long, ...>` provides O(log n) range queries via `subMap()`.
Point lists within each second bucket use `Collections.synchronizedList()`.

#### store(MetricPoint point)

```
1. computeIfAbsent(point.name, k -> new TreeMap<>())
2. computeIfAbsent(point.timestamp.epochSecond, k -> synchronizedList(new ArrayList<>()))
3. add(point)
```

Time complexity: O(log S) where S = number of distinct seconds for that metric.

#### query(metricName, from, to)

```
1. Get TreeMap for metricName (null check -> empty list)
2. subMap(from.epochSecond, to.epochSecond + 1)   // +1 to include 'to' second
3. flatMap all bucket lists
4. Collect to list
```

Time complexity: O(log S + K) where K = number of points in the range.

**Why TreeMap.subMap is powerful**: It returns a *view* -- no copying of the tree structure.
The +1 on the upper bound handles the inclusive semantics (subMap is inclusive-exclusive).

#### queryLatest(metricName, count)

```
1. Get TreeMap for metricName
2. Walk descendingMap() entries (newest first)
3. Within each bucket, walk points in reverse order
4. Collect up to 'count' points
5. Reverse the result for chronological order
```

#### downsample(metricName, bucketSize)

```
1. Get all buckets for the metric
2. For each point, compute downsample bucket key:
   bucketKey = (epochSecond / bucketSeconds) * bucketSeconds
3. Group points by bucketKey
4. For each group, compute average value
5. Create one representative MetricPoint per bucket
```

**Interview note**: Downsampling tiers in production:
- Raw data: 0-1 hour retention
- 1-minute averages: 24-hour retention
- 5-minute averages: 7-day retention
- 1-hour averages: 1-year retention

### 7.2 TraceAssembler

**Data structure**:
```
Map<String, List<Span>>
     ^          ^
     |          |
  traceId   pending spans waiting for assembly
```

`ConcurrentHashMap` outer, `Collections.synchronizedList` inner.

#### addSpan(span)

```
1. pendingSpans.computeIfAbsent(span.traceId, k -> synchronizedList(new ArrayList<>()))
2. add(span)
```

#### assembleTrace(traceId)

```
1. Get pending spans for traceId (null check -> Optional.empty)
2. Find root span: stream().filter(s -> parentSpanId == null).findFirst()
3. If no root found -> Optional.empty() (trace not yet complete)
4. Create Trace(traceId, rootSpan.serviceName)
5. For each span in pending list -> trace.addSpan(span)
6. Remove traceId from pendingSpans (assembly complete)
7. Return Optional.of(trace)
```

**Design note**: Assembly is triggered on each `finishSpan()` call. The assembler
only succeeds when a root span is present. This means child spans can arrive and
be buffered before the root -- assembly is deferred until the root arrives.

### 7.3 MetricAggregator

Provides stateless statistical functions over `List<MetricPoint>`:

| Method                         | Algorithm                                    | Complexity |
|--------------------------------|----------------------------------------------|------------|
| `sum(points)`                  | Stream mapToDouble sum                       | O(n)       |
| `average(points)`              | Stream mapToDouble average                   | O(n)       |
| `min(points)`                  | Stream mapToDouble min                       | O(n)       |
| `max(points)`                  | Stream mapToDouble max                       | O(n)       |
| `percentile(points, p)`        | Sort ascending, nearest-rank index           | O(n log n) |
| `rate(points)`                 | Sort by timestamp, (last-first)/timeDiff     | O(n log n) |
| `count(points)`                | List.size()                                  | O(1)       |
| `histogram(points, boundaries)`| Distribute into labeled buckets              | O(n * B)   |

#### Percentile calculation detail:
```
index = ceil(p / 100.0 * size) - 1
index = clamp(index, 0, size - 1)
return sorted[index]
```

This is the "nearest-rank" method used by Prometheus and Datadog.

#### Histogram detail:
```
1. Sort boundaries ascending
2. Initialize labeled buckets: "0-10", "10-50", "50-100", "100+"
3. For each point, find the bucket: iterate boundaries, first boundary > value wins
4. If value exceeds all boundaries -> overflow bucket ("100+")
5. Return Map<String, Long> of bucket -> count
```

### 7.4 SamplingEngine

A thin delegation layer that forwards sampling decisions to the active `SamplingStrategy`.

```java
public class SamplingEngine {
    private SamplingStrategy defaultStrategy;  // swappable at runtime

    public boolean shouldSample(TraceContext context) {
        return defaultStrategy.shouldSample(context);
    }

    public void setStrategy(SamplingStrategy strategy) {
        this.defaultStrategy = strategy;
    }
}
```

**Design**: The engine provides a stable reference point. Services hold a reference to
`SamplingEngine` and the underlying strategy can be swapped without rewiring services.

### 7.5 LogProcessor (Chain of Responsibility)

Processes log entries through a configurable filter pipeline.

```java
public class LogProcessor {
    private LogLevel minLevel = LogLevel.INFO;
    private final List<Predicate<LogEntry>> filters = new ArrayList<>();
}
```

#### process(LogEntry entry)

```
1. Level gate: if !entry.level.isAtLeast(minLevel) -> empty
2. Custom filters: for each predicate, if !predicate.test(entry) -> empty
3. Return Optional.of(entry)
```

#### processBatch(List<LogEntry> entries)

```
1. Stream entries
2. Map each through process()
3. Filter Optional::isPresent
4. Collect surviving entries
```

#### enrichWithCorrelation(entry, traceId, spanId)

Sets the traceId and spanId on a LogEntry for cross-signal correlation.
This is what enables "click a log line -> see the full distributed trace".

---

## 8. Service Layer

### 8.1 MetricService

**Dependencies** (constructor-injected):
- `MetricRepository` -- persists Metric definitions
- `TimeSeriesStore` -- stores MetricPoints in time order
- `MetricAggregator` -- computes statistical aggregations

**Flows**:

**recordMetric(name, value, type, tags)**:
```
1. Create MetricPoint.of(name, value, type, tags)   // stamps Instant.now()
2. timeSeriesStore.store(point)                      // O(log S)
3. If metricRepo.findByName(name).isEmpty():
   a. Create Metric via Builder
   b. metricRepo.save(metric)
4. Print [METRIC] log line
```

**query(metricName, from, to)**:
```
1. Delegate to timeSeriesStore.query(metricName, from, to)
2. Returns List<MetricPoint> in the range
```

**aggregate(metricName, from, to, strategy)**:
```
1. Query points from timeSeriesStore
2. strategy.aggregate(points)                        // Strategy pattern
3. Return computed value
```

**Convenience methods**: `recordCounter()`, `recordGauge()`, `recordHistogram()`, `recordTimer()`
-- all delegate to `recordMetric()` with the appropriate `MetricType`.

### 8.2 TracingService

**Dependencies** (constructor-injected):
- `TraceRepository` -- persists completed traces
- `TraceAssembler` -- buffers spans and assembles traces
- `SamplingStrategy` -- decides whether to sample a trace

**Internal state**: `Map<String, TraceContext> activeContexts` -- tracks active trace contexts.

**Flows**:

**startTrace(operationName, serviceName)**:
```
1. Create TraceContext.newTrace()                    // fresh traceId + spanId
2. Check sampling: samplingEngine.shouldSample(context, operationName)
3. Build root Span via Builder (parentSpanId = null)
4. Track context in activeContexts
5. Print [TRACING] log
6. Return root span
```

**startSpan(traceId, parentSpanId, operationName, serviceName)**:
```
1. Build child Span via Builder with parentSpanId set
2. Print [TRACING] log
3. Return child span
```

**finishSpan(span)**:
```
1. span.finish()                                    // stamps endTime, computes duration
2. assembler.addSpan(span)                          // buffer for assembly
3. assembled = assembler.assembleTrace(span.traceId)
4. If assembled.isPresent():
   a. traceRepo.save(assembled.get())
   b. activeContexts.remove(traceId)
   c. Print [TRACING] assembled log
5. Else:
   a. Print [TRACING] pending assembly log
```

### 8.3 LogService

**Dependencies** (constructor-injected):
- `LogRepository` -- persists log entries
- `LogProcessor` -- filters and enriches entries

**Flows**:

**log(level, message, serviceName)**:
```
1. Create LogEntry(level, message, serviceName)
2. logProcessor.process(entry)                      // level gate + custom filters
3. If survived: logRepo.save(entry), print [LOG]
4. Return Optional<LogEntry>
```

**logWithTrace(level, message, serviceName, traceId, spanId)**:
```
1. Create LogEntry(level, message, serviceName)
2. entry.setTraceId(traceId)                        // correlation!
3. entry.setSpanId(spanId)                          // correlation!
4. logProcessor.process(entry)
5. If survived: logRepo.save(entry)
6. Return Optional<LogEntry>
```

**search(serviceName, minLevel, from, to)**:
```
1. logRepo.findAll().stream()
2. Filter by serviceName match
3. Filter by level.isAtLeast(minLevel)
4. Filter by timestamp in [from, to]
5. Sort by timestamp ascending
```

**getLogsByTrace(traceId)**:
```
1. logRepo.findAll().stream()
2. Filter by traceId match
3. Sort by timestamp ascending
```

### 8.4 AlertService

**Dependencies** (constructor-injected):
- `AlertRepository` -- persists fired alerts
- `AlertingStrategy` -- Strategy pattern for threshold evaluation
- `MetricService` -- provides metric data for evaluation

**Internal state**:
- `List<AlertRule> rules` -- registered rules (CopyOnWriteArrayList)
- `Map<String, String> firingRules` -- ruleId -> alertId for currently firing rules

**Flows**:

**evaluateRules()**:
```
1. Set evaluation window: [now - 60 seconds, now]
2. For each enabled rule:
   a. Query metric data: metricService.query(rule.metricName, windowStart, now)
   b. Check strategy: alertingStrategy.shouldAlert(rule, points)
   c. If shouldAlert AND NOT already firing:
      - Create Alert(rule, currentValue, message)
      - alertRepo.save(alert)
      - Track in firingRules: ruleId -> alertId
      - Print [ALERT] FIRING
   d. If NOT shouldAlert AND currently firing:
      - Remove from firingRules
      - resolveAlert(alertId)
      - Print [ALERT] RESOLVED
```

**Alert lifecycle methods**:
- `acknowledgeAlert(alertId)` -- transitions to ACKNOWLEDGED
- `resolveAlert(alertId)` -- transitions to RESOLVED, stamps resolvedAt

### 8.5 DashboardService

**Dependencies** (constructor-injected):
- `MetricService` -- metrics pillar
- `TracingService` -- tracing pillar
- `LogService` -- logging pillar

**Flows**:

**getMetricSummary(metricName, window)**:
```
1. Query points in the time window
2. Use MetricAggregator to compute: avg, min, max, p50, p95, p99, count, rate
3. Return as Map<String, Double>
```

**getTraceSummary(serviceName)**:
```
1. Get traces by service name
2. Compute: traceCount, avgDurationMs, errorRate
3. Return as Map<String, Object>
```

**getSystemOverview()**:
```
1. Gather all metric names and latest values
2. Get 5 most recent traces with span counts and durations
3. Count logs by level
4. Format as multi-line string
```

### 8.6 ServiceMapService

**Dependencies**: None -- maintains its own in-memory graph.

**Data structures**:
- `Map<String, ServiceNode> serviceNodes` -- adjacency list
- `Map<String, long[]> edgeStats` -- "caller->callee" -> [totalRequests, errors, totalLatencyMs]

**Flows**:

**registerCall(callerService, calleeService, latencyMs, success)**:
```
1. Get or create ServiceNode for caller and callee
2. caller.addDependency(calleeService)
3. callee.addDependent(callerService)
4. Update edge stats: increment requests, errors (if !success), cumulative latency
5. Recalculate node-level stats for both caller and callee
```

**printServiceMap()**:
```
1. Find root services (no dependents -- entry points)
2. DFS tree walk with ASCII connectors (\--, |--)
3. Detect circular references
4. Print edge statistics table
```

**getTopology()**:
```
1. Build Map<String, Set<String>> -- service name to downstream dependencies
```

### 8.7 ObservabilityService (Facade)

**Dependencies** (constructor-injected):
- `MetricService`, `TracingService`, `LogService`
- `AlertService`, `DashboardService`, `ServiceMapService`

The Facade delegates every operation to the appropriate sub-service:

| Facade Method              | Delegates To                      |
|----------------------------|-----------------------------------|
| `recordMetric()`           | `metricService.recordMetric()`    |
| `startTrace()`             | `tracingService.startTrace()`     |
| `startSpan()`              | `tracingService.startSpan()`      |
| `finishSpan()`             | `tracingService.finishSpan()`     |
| `log()`                    | `logService.log()`                |
| `logWithTrace()`           | `logService.logWithTrace()`       |
| `evaluateAlerts()`         | `alertService.evaluateRules()`    |
| `getSystemOverview()`      | `dashboardService.getSystemOverview()` |
| `registerServiceCall()`    | `serviceMapService.registerCall()` |

Also exposes sub-service accessors for callers needing direct access.

---

## 9. Controller Layer

### ObservabilityController

**Dependencies** (constructor-injected):
- `ObservabilityService` -- unified facade
- `AlertService` -- direct access for alert operations
- `DashboardService` -- direct access for dashboard operations

**Simulated REST endpoints**:

| HTTP Simulation                     | Method                                  |
|-------------------------------------|-----------------------------------------|
| `POST /metrics`                     | `ingestMetric(name, value, type, tags)` |
| `POST /traces`                      | `startTrace(operationName, serviceName)`|
| `PUT /traces/spans/{id}/finish`     | `finishSpan(span)`                      |
| `POST /logs`                        | `ingestLog(level, message, serviceName)`|
| `POST /alerts/evaluate`             | `evaluateAlerts()`                      |
| `GET /alerts`                       | `getAlerts()`                           |
| `GET /dashboard`                    | `getDashboard()`                        |

Each method prints the HTTP method and path before delegating.

---

## 10. Configuration and Wiring

### AppConfig (Factory + Composition Root + Lazy Init)

**Design**: Single class that wires all dependencies via lazy initialization.
Each getter checks for null and creates the object on first access.

```
AppConfig
  |
  |-- Repositories (4)
  |   |-- getMetricRepository()    -> new InMemoryMetricRepository()
  |   |-- getTraceRepository()     -> new InMemoryTraceRepository()
  |   |-- getLogRepository()       -> new InMemoryLogRepository()
  |   |-- getAlertRepository()     -> new InMemoryAlertRepository()
  |
  |-- Engines (5)
  |   |-- getMetricAggregator()    -> new MetricAggregator()
  |   |-- getTraceAssembler()      -> new TraceAssembler()
  |   |-- getLogProcessor()        -> new LogProcessor()
  |   |-- getTimeSeriesStore()     -> new TimeSeriesStore()
  |   |-- getSamplingEngine()      -> new SamplingEngine(getSamplingStrategy())
  |
  |-- Strategies (3, swappable)
  |   |-- getSamplingStrategy()    -> default: HeadBasedSamplingStrategy(1.0)
  |   |-- getAggregationStrategy() -> default: PercentileAggregationStrategy(99)
  |   |-- getAlertingStrategy()    -> default: ThresholdAlertingStrategy()
  |
  |-- Services (7)
  |   |-- getMetricService()       -> MetricService(repo, tsStore, aggregator)
  |   |-- getTracingService()      -> TracingService(repo, assembler, samplingStrategy)
  |   |-- getLogService()          -> LogService(repo, logProcessor)
  |   |-- getAlertService()        -> AlertService(repo, alertingStrategy, metricService)
  |   |-- getDashboardService()    -> DashboardService(metric, tracing, log)
  |   |-- getServiceMapService()   -> ServiceMapService()
  |   |-- getObservabilityService()-> ObservabilityService(all 6 services)
  |
  |-- Controller
  |   |-- getController()          -> ObservabilityController(obs, alert, dashboard)
  |
  |-- Display
      |-- getStatsDisplay()        -> ObservabilityStatsDisplay(5 services)
```

**Strategy swap invalidation**: When a strategy is swapped via setter, all dependent
objects are set to null so they are re-created on next access with the new strategy:

```java
public void setSamplingStrategy(SamplingStrategy strategy) {
    this.samplingStrategy = strategy;
    this.samplingEngine = null;       // depends on samplingStrategy
    this.tracingService = null;       // depends on samplingStrategy
    this.observabilityService = null; // depends on tracingService
    this.controller = null;           // depends on observabilityService
}
```

This cascade ensures all downstream objects pick up the new strategy.

---

## 11. Display Layer

### ObservabilityStatsDisplay

**Dependencies** (constructor-injected):
- `MetricService`, `TracingService`, `LogService`, `AlertService`, `ServiceMapService`

**Output methods**:

| Method                      | Output                                        |
|-----------------------------|-----------------------------------------------|
| `printMetricSummary(name)`  | Table: AVG, MIN, MAX, P50, P99, COUNT         |
| `printTrace(trace)`         | Span tree with indented hierarchy              |
| `printRecentLogs(count)`    | Formatted table: TIMESTAMP, LEVEL, SERVICE, MSG, TRACE_ID |
| `printAlertStatus()`        | Table: RULE, STATUS, VALUE, SEVERITY, TRIGGERED_AT |
| `printServiceMap()`         | ASCII dependency graph with stats              |
| `printStats()`              | Summary: total metrics, traces, logs, alerts   |

**Trace tree rendering**:
```
1. trace.buildSpanTree() -> Map<String, List<Span>>
2. Recursive printSpanTree(tree, "root", depth=0)
3. Each span: indent + connector + operationName + [service] + duration + status
4. Recurse into children of this span
```

---

## 12. Exception Hierarchy

```
RuntimeException
    |
    +-- ObservabilityException (base)
            |
            +-- MetricIngestionException
            |       + metricName: String
            |       "Metric ingestion failed for 'X': reason"
            |
            +-- TraceAssemblyException
            |       + traceId: String
            |       "Trace assembly failed for traceId='X': reason"
            |
            +-- AlertEvaluationException
                    + ruleName: String
                    "Alert evaluation failed for rule 'X': reason"
```

**Design decisions**:
- All extend `RuntimeException` (unchecked) -- domain exceptions should not force callers
  to catch checked exceptions in a system-design context.
- Each carries a domain-specific identifier for debugging (metricName, traceId, ruleName).
- Message format is consistent: "Operation failed for entity 'name': reason".

---

## 13. Concurrency Model

### Thread-Safety Mechanisms

| Component                  | Mechanism                        | Why                                |
|----------------------------|----------------------------------|------------------------------------|
| All InMemory*Repository    | `ConcurrentHashMap`              | Multiple services write concurrently|
| TimeSeriesStore (outer)    | `ConcurrentHashMap`              | Metrics arrive from multiple threads|
| TimeSeriesStore (inner)    | `TreeMap` + `synchronizedList`   | Bucket-level concurrent writes     |
| TraceAssembler             | `ConcurrentHashMap` + `synchronizedList` | Spans arrive out of order |
| AlertService.rules         | `CopyOnWriteArrayList`           | Reads far outnumber writes         |
| RateLimitedSamplingStrategy| `AtomicInteger` + `AtomicLong`   | Lock-free sampling counter         |
| InMemoryLogRepository      | `AtomicLong` (id generator)      | Monotonic key generation           |

### Concurrency Trade-offs

1. **ConcurrentHashMap vs synchronized HashMap**: CHM allows concurrent reads with
   segment-level locking for writes. In an observability system, reads (queries) typically
   outnumber writes (ingestion) 10:1 during dashboarding, but writes dominate during
   burst ingestion. CHM handles both patterns well.

2. **TreeMap in TimeSeriesStore**: The inner TreeMap is NOT concurrent by default.
   Writes to the same metric's TreeMap could race. In production, this would be
   partitioned by metric name (each metric gets its own lock) or replaced with a
   concurrent skip list.

3. **CopyOnWriteArrayList for alert rules**: Rules change infrequently (create at deploy
   time), but are read on every evaluation cycle. COW is ideal for this read-heavy pattern.

4. **AtomicInteger/AtomicLong in RateLimitedSamplingStrategy**: Lock-free CAS operations
   for the sliding-window counter. The benign race between window reset and counter
   increment is explicitly documented and acceptable (can only over-sample, never under-sample).

---

## 14. SOLID Principles Mapping

### Single Responsibility (SRP)

| Class                  | Single Responsibility                              |
|------------------------|----------------------------------------------------|
| MetricService          | Metric recording, storage, and querying            |
| TracingService         | Trace lifecycle management                         |
| LogService             | Log ingestion and search                           |
| AlertService           | Alert rule evaluation and lifecycle                |
| DashboardService       | Cross-pillar dashboard views                       |
| ServiceMapService      | Service dependency topology                        |
| TimeSeriesStore        | Time-series storage and range queries              |
| MetricAggregator       | Statistical computations over data points          |
| LogProcessor           | Log filtering and enrichment pipeline              |
| TraceAssembler         | Span buffering and trace assembly                  |

### Open/Closed Principle (OCP)

The Strategy pattern enables extending behavior without modifying existing code:
- New `SamplingStrategy` (e.g., `AdaptiveSamplingStrategy`) -- implement interface, plug in.
- New `AggregationStrategy` (e.g., `EWMAAggregationStrategy`) -- implement interface, plug in.
- New `AlertingStrategy` (e.g., `SeasonalAlertingStrategy`) -- implement interface, plug in.
- New `Repository` implementation (e.g., `PostgresMetricRepository`) -- implement interface.

### Liskov Substitution (LSP)

All strategy implementations are fully substitutable:
- `HeadBasedSamplingStrategy` and `TailBasedSamplingStrategy` both satisfy `SamplingStrategy`.
- `ThresholdAlertingStrategy` and `AnomalyDetectionAlertingStrategy` both satisfy `AlertingStrategy`.
- `InMemoryMetricRepository` can be replaced by any `MetricRepository` implementation.

### Interface Segregation (ISP)

- `SamplingStrategy` has 3 methods (two overloads of `shouldSample` + `getStrategyName`).
- `AggregationStrategy` has 2 methods (`aggregate` + `getStrategyName`).
- `AlertingStrategy` has 2 methods (`shouldAlert` + `getStrategyName`).
- No client is forced to depend on methods it doesn't use.

### Dependency Inversion (DIP)

- Services depend on repository *interfaces* (`MetricRepository`, not `InMemoryMetricRepository`).
- Services depend on strategy *interfaces* (`SamplingStrategy`, not `HeadBasedSamplingStrategy`).
- `AppConfig` is the only class that knows about concrete implementations.
- Constructor injection throughout -- no `new` in service classes.

---

## 15. Sample Workflows

### Workflow 1: Metric Ingestion (Counter)

```
Caller
  |
  1. controller.ingestMetric("http.requests.total", 1, COUNTER, {method: "GET"})
  |
  2. ObservabilityController prints "[CONTROLLER] POST /metrics ..."
  |
  3. ObservabilityController -> observabilityService.recordMetric(...)
  |
  4. ObservabilityService -> metricService.recordMetric(...)
  |
  5. MetricService:
     a. MetricPoint.of("http.requests.total", 1, COUNTER, {method: "GET"})
        --> stamps Instant.now()
     b. timeSeriesStore.store(point)
        --> ConcurrentHashMap.computeIfAbsent("http.requests.total", new TreeMap)
        --> TreeMap.computeIfAbsent(epochSecond, new synchronizedList)
        --> list.add(point)
     c. metricRepo.findByName("http.requests.total")
        --> if empty: create Metric via Builder, metricRepo.save()
     d. Print "[METRIC] Recorded COUNTER 'http.requests.total' = 1"
```

### Workflow 2: Distributed Trace Lifecycle

```
Caller
  |
  1. controller.startTrace("POST /api/orders", "api-gateway")
  |
  2. ObservabilityService -> tracingService.startTrace(...)
  |
  3. TracingService:
     a. TraceContext.newTrace() --> fresh traceId + spanId
     b. samplingEngine.shouldSample(context, "POST /api/orders")
     c. Span.Builder(traceId, "POST /api/orders", "api-gateway").build()
     d. activeContexts.put(traceId, context)
     e. Print "[TRACING] Started trace ..."
     f. Return rootSpan
  |
  4. caller creates child spans:
     tracingService.startSpan(traceId, rootSpan.spanId, "createOrder", "order-service")
     --> Span.Builder(traceId, "createOrder", "order-service")
         .parentSpanId(rootSpan.spanId).build()
  |
  5. caller finishes spans (leaf to root):
     tracingService.finishSpan(childSpan)
     --> childSpan.finish()  // stamps endTime, computes duration
     --> assembler.addSpan(childSpan)
     --> assembler.assembleTrace(traceId)
         // Returns Optional.empty if root not yet finished
  |
  6. tracingService.finishSpan(rootSpan)
     --> rootSpan.finish()
     --> assembler.addSpan(rootSpan)
     --> assembler.assembleTrace(traceId)
         // Now root span exists -> assembly succeeds
         // Creates Trace with all buffered spans
     --> traceRepo.save(trace)
     --> activeContexts.remove(traceId)
     --> Print "[TRACING] Assembled and saved trace ... | spans=N"
```

### Workflow 3: Alert Evaluation Cycle

```
Scheduler (or manual trigger)
  |
  1. controller.evaluateAlerts()
  |
  2. AlertService.evaluateRules():
     |
     For each enabled rule:
     |
     a. Set window: [now - 60s, now]
     |
     b. metricService.query(rule.metricName, windowStart, now)
        --> timeSeriesStore.query(metricName, from, to)
        --> Returns List<MetricPoint>
     |
     c. alertingStrategy.shouldAlert(rule, points)
        |
        ThresholdAlertingStrategy:
          - average = mean of all point values
          - if rule.condition starts with "<": return average < threshold
          - else: return average > threshold
        |
        AnomalyDetectionAlertingStrategy:
          - if < 3 points: return false
          - mean = average of all values
          - stdDev = sqrt(avg((value - mean)^2))
          - latest = last point's value
          - return abs(latest - mean) > (multiplier * stdDev)
     |
     d. State transition:
        - NOT firing + shouldAlert -> Create Alert(FIRING), save, track in firingRules
        - IS firing + !shouldAlert -> resolveAlert(), remove from firingRules
        - IS firing + shouldAlert  -> no change (still firing)
        - NOT firing + !shouldAlert -> no change (still OK)
```

### Workflow 4: Log with Trace Correlation

```
Caller
  |
  1. logService.logWithTrace(INFO, "Request received", "user-service", traceId, spanId)
  |
  2. LogService:
     a. new LogEntry(INFO, "Request received", "user-service")
     b. entry.setTraceId(traceId)     // correlation link
     c. entry.setSpanId(spanId)       // correlation link
     d. logProcessor.process(entry)
        |
        LogProcessor:
          - Level gate: INFO >= INFO (minLevel) -> pass
          - Custom filters: iterate predicates, all pass -> pass
          - Return Optional.of(entry)
     |
     e. logRepo.save(entry)
     f. Print "[LOG] [INFO] user-service: Request received (traceId=...)"
  |
  3. Later: logService.getLogsByTrace(traceId)
     --> Returns all log entries with matching traceId, sorted by timestamp
     --> This links logs <-> traces: the "glue" between observability pillars
```

### Workflow 5: Service Dependency Registration

```
Caller
  |
  1. serviceMapService.registerCall("api-gateway", "order-service", 45, true)
  |
  2. ServiceMapService:
     a. serviceNodes.computeIfAbsent("api-gateway", ServiceNode::new)
     b. serviceNodes.computeIfAbsent("order-service", ServiceNode::new)
     c. caller.addDependency("order-service")       // downstream
     d. callee.addDependent("api-gateway")           // upstream
     e. edgeStats["api-gateway->order-service"] = [requests++, errors, latency+=45]
     f. updateNodeStats("api-gateway")
        - Walk all outgoing edges for api-gateway
        - Compute: totalRequests, totalErrors, totalLatency
        - node.updateStats(requests, errorRate, avgLatency)
     g. updateNodeStats("order-service")
     h. Print "[SERVICE_MAP] api-gateway -> order-service | latency=45ms | success=true"
```

---

## 16. Design Patterns Summary

| #  | Pattern                 | GoF Category | Where Used                                   |
|----|-------------------------|--------------|----------------------------------------------|
| 1  | Strategy                | Behavioral   | SamplingStrategy, AggregationStrategy, AlertingStrategy |
| 2  | Builder                 | Creational   | Metric.Builder, Span.Builder, AlertRule.Builder |
| 3  | Factory Method          | Creational   | AppConfig (composition root, lazy init)      |
| 4  | Repository              | Structural*  | 4 interfaces + 4 InMemory implementations   |
| 5  | Facade                  | Structural   | ObservabilityService wraps 6 sub-services    |
| 6  | Observer                | Behavioral   | AlertService evaluates rules on metric data  |
| 7  | Decorator               | Structural   | Span enrichment via addTag()/addLog()        |
| 8  | Chain of Responsibility | Behavioral   | LogProcessor filter pipeline                 |
| 9  | Template Method         | Behavioral   | MetricAggregator (consistent aggregation shape) |
| 10 | Singleton               | Creational   | AppConfig (lazy initialization per field)    |

*Repository is from DDD/Enterprise patterns, not strictly GoF.

---

## 17. Extensibility Points

### Add a New Sampling Strategy

```java
// 1. Implement the interface
public class AdaptiveSamplingStrategy implements SamplingStrategy {
    @Override
    public boolean shouldSample(TraceContext context) { /* ... */ }
    @Override
    public boolean shouldSample(TraceContext context, String op) { /* ... */ }
    @Override
    public String getStrategyName() { return "ADAPTIVE"; }
}

// 2. Plug it in at runtime
config.setSamplingStrategy(new AdaptiveSamplingStrategy());
// AppConfig invalidates: samplingEngine, tracingService, observabilityService, controller
```

### Add a New Aggregation Strategy

```java
public class EWMAAggregationStrategy implements AggregationStrategy {
    @Override
    public double aggregate(List<MetricPoint> points) { /* EWMA logic */ }
    @Override
    public String getStrategyName() { return "EWMA"; }
}

config.setAggregationStrategy(new EWMAAggregationStrategy());
```

### Add a New Alerting Strategy

```java
public class SeasonalAlertingStrategy implements AlertingStrategy {
    @Override
    public boolean shouldAlert(AlertRule rule, List<MetricPoint> points) { /* ... */ }
    @Override
    public String getStrategyName() { return "SEASONAL"; }
}

config.setAlertingStrategy(new SeasonalAlertingStrategy());
```

### Add a New Repository Implementation

```java
public class PostgresMetricRepository implements MetricRepository {
    @Override
    public void save(Metric metric) { /* JDBC insert */ }
    @Override
    public Optional<Metric> findById(String id) { /* JDBC select */ }
    // ... all interface methods
}
// Swap in AppConfig: override getMetricRepository() to return PostgresMetricRepository
```

### Add a New Metric Type

```java
// 1. Add to enum
public enum MetricType {
    COUNTER(...), GAUGE(...), HISTOGRAM(...), TIMER(...),
    SUMMARY("Running quantile estimation");
}

// 2. Add convenience method to MetricService
public void recordSummary(String name, double value, Map<String, String> tags) {
    recordMetric(name, value, MetricType.SUMMARY, tags);
}
```

### Add a New Repository Domain (e.g., Dashboard Snapshots)

```java
// 1. Create the interface
public interface DashboardSnapshotRepository {
    void save(DashboardSnapshot snapshot);
    List<DashboardSnapshot> findByDashboardId(String id);
}

// 2. Create InMemory implementation
public class InMemoryDashboardSnapshotRepository implements DashboardSnapshotRepository { ... }

// 3. Wire in AppConfig
```

### Add New Log Processor Filters

```java
// Add custom predicates at runtime:
logProcessor.addFilter(entry -> entry.getServiceName().equals("payment-service"));
logProcessor.addFilter(entry -> !entry.getMessage().contains("healthcheck"));
logProcessor.setMinLevel(LogLevel.WARN);
```

---

## 18. Data Structures and Algorithms

### Core Data Structures

| Data Structure            | Where Used          | Why Chosen                                     |
|---------------------------|---------------------|------------------------------------------------|
| `TreeMap<Long, List<MP>>` | TimeSeriesStore     | O(log n) range queries via subMap()            |
| `ConcurrentHashMap`       | All repositories    | Thread-safe, segment-level locking             |
| `ArrayList<Span>`         | Trace               | Ordered span collection, indexed access        |
| `HashMap<String, List<Span>>` | Trace.buildSpanTree | Parent-to-children adjacency for tree rendering|
| `HashSet<String>`         | ServiceNode         | O(1) dependency/dependent lookup               |
| `LinkedHashMap`           | ServiceMapService   | Insertion-order preserving for consistent display |
| `CopyOnWriteArrayList`   | AlertService.rules  | Read-heavy, write-rare pattern                 |
| `AtomicInteger/Long`      | RateLimitedSampling | Lock-free concurrent counter                   |

### Key Algorithms

| Algorithm                       | Complexity    | Where Used               |
|---------------------------------|---------------|--------------------------|
| TreeMap.subMap (range query)    | O(log n + k)  | TimeSeriesStore.query    |
| Nearest-rank percentile         | O(n log n)    | MetricAggregator, PercentileAggregationStrategy |
| Rate of change                  | O(n log n)    | MetricAggregator, RateAggregationStrategy |
| Histogram bucketing             | O(n * B)      | MetricAggregator.histogram |
| Mean + stdDev anomaly detection | O(n)          | AnomalyDetectionAlertingStrategy |
| Deterministic hash sampling     | O(1)          | HeadBasedSamplingStrategy |
| Sliding window token bucket     | O(1)          | RateLimitedSamplingStrategy |
| Downsampling (time bucketing)   | O(n)          | TimeSeriesStore.downsample |
| DFS tree traversal              | O(V + E)      | ServiceMapService.printServiceMap |
| Span tree assembly              | O(n)          | TraceAssembler.assembleTrace |

### Complexity Summary by Operation

| Operation                    | Time          | Space    |
|------------------------------|---------------|----------|
| Ingest metric point          | O(log S)      | O(1)     |
| Range query                  | O(log S + K)  | O(K)     |
| Compute P99                  | O(n log n)    | O(n)     |
| Compute rate                 | O(n log n)    | O(n)     |
| Sample decision (head-based) | O(1)          | O(1)     |
| Sample decision (rate-limit) | O(1)          | O(1)     |
| Assemble trace               | O(n)          | O(n)     |
| Alert rule evaluation        | O(R * n)      | O(n)     |
| Build span tree              | O(n)          | O(n)     |
| Service map registration     | O(E)          | O(1)     |
| Downsample                   | O(n)          | O(B)     |

Where: S = distinct seconds, K = points in range, n = points, R = rules, B = buckets, E = edges.

---

## 19. Interview Talking Points

### Three Pillars of Observability

```
+------------------+    +------------------+    +------------------+
|     METRICS      |    |     TRACES       |    |      LOGS        |
| Counters/Gauges  |    | Spans/Trees      |    | Structured JSON  |
| Histograms/Timers|    | Context Prop.    |    | Correlation IDs  |
|                  |    |                  |    |                  |
| "What is broken" |    | "Where is broken"|    | "Why is broken"  |
+--------+---------+    +--------+---------+    +--------+---------+
         |                       |                       |
         +----------+------------+----------+------------+
                    |                       |
              Correlation IDs          Dashboards
              (traceId, spanId)        (RED/USE method)
```

### Time-Series Storage at Scale

**Interview soundbite**: "We use a bucketed TreeMap structure -- metricName to epochSecond to
points. TreeMap.subMap gives us O(log n) range queries. For production scale, we'd add
downsampling tiers: raw for 1 hour, 1-minute averages for 24 hours, 5-minute averages for
7 days, 1-hour averages for 1 year."

### Sampling Trade-offs

| Strategy     | Pros                          | Cons                              | Production Use          |
|--------------|-------------------------------|-----------------------------------|-------------------------|
| Head-based   | Simple, consistent, cheap     | Loses interesting traces          | Baseline for all traffic|
| Tail-based   | Keeps errors and slow traces  | High memory cost during collection| Error/latency capture   |
| Rate-limited | Predictable cost              | May lose important traces in bursts| Safety valve            |

**Production recommendation**: Layer all three -- head-based as baseline (e.g., 10%),
tail-based for errors and slow traces (100% of errors), rate-limited as safety valve (1000/s max).

### High-Cardinality Metric Management

High cardinality is the #1 cost driver in observability. Each unique label combination creates
a new time series:
- 10K users x 100 endpoints = 1M time series

Mitigation strategies:
1. Drop high-cardinality tags at ingestion
2. Hash/bucket: `user_id` -> `user_bucket (mod 100)`
3. Separate storage: high-card metrics to columnar store (ClickHouse)
4. Adaptive sampling: sample 1% of unique label combinations
5. Cardinality limits: reject metrics exceeding N unique label combinations

### Alert Fatigue Prevention

```
Static Thresholds:               Anomaly Detection:
  + Simple to understand           + Adapts to dynamic workloads
  + Deterministic                  + No hardcoded thresholds
  - Brittle for dynamic workloads  - Requires baseline data
  - Needs manual tuning            - Sensitive to non-Gaussian distributions
```

**Production guidance**: WARNING for investigation, CRITICAL for pages.
Always define runbooks. Use anomaly detection for workloads with natural variation.

### RED/USE Methods for Dashboards

**RED** (for request-driven services):
- **R**ate: requests per second
- **E**rrors: error rate / error count
- **D**uration: latency percentiles (P50, P95, P99)

**USE** (for resource-oriented services):
- **U**tilization: % of resource capacity in use
- **S**aturation: work queue depth
- **E**rrors: error count

### Context Propagation

W3C TraceContext header format:
```
traceparent: 00-{traceId}-{spanId}-{flags}
Example:     00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
```

The `TraceContext.createChild(newSpanId)` method models this propagation:
current spanId becomes parentSpanId, new spanId becomes the active span.
Baggage items are propagated across all services for cross-cutting concerns.

### Production Scaling Considerations

| Component           | Demo Implementation        | Production Alternative               |
|---------------------|---------------------------|--------------------------------------|
| MetricRepository    | ConcurrentHashMap          | TimescaleDB, InfluxDB, Prometheus    |
| TraceRepository     | ConcurrentHashMap          | Jaeger (Cassandra), Tempo (S3)       |
| LogRepository       | ConcurrentHashMap          | Elasticsearch, Loki, ClickHouse      |
| TimeSeriesStore     | TreeMap                    | LSM tree (RocksDB), columnar (Parquet)|
| SamplingEngine      | In-process                 | Distributed collector (OpenTelemetry) |
| MetricAggregator    | Sort-based percentile      | t-digest, DDSketch                   |
| AlertingStrategy    | Simple threshold/stddev    | Prophet, Holt-Winters, ML models     |
| ServiceMapService   | In-memory adjacency list   | Neo4j, Dynatrace Smartscape          |

---

## End of Low-Level Design Document
