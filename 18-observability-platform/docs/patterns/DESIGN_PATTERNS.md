# Observability Platform -- Design Patterns

> Project 18 | 10 GoF patterns across ~50 Java classes
> Each pattern: GoF category, anti-pattern, clean solution, numbered call chain, ASCII diagram, interview soundbite

---

## Table of Contents

1. [Strategy Pattern (Sampling)](#1-strategy-pattern--sampling)
2. [Strategy Pattern (Aggregation)](#2-strategy-pattern--aggregation)
3. [Strategy Pattern (Alerting)](#3-strategy-pattern--alerting)
4. [Builder Pattern (Metric, Span, AlertRule)](#4-builder-pattern--metric-span-alertrule)
5. [Factory Method / Composition Root (AppConfig)](#5-factory-method--composition-root-appconfig)
6. [Repository Pattern (4 Repositories)](#6-repository-pattern--4-repositories)
7. [Facade Pattern (ObservabilityService)](#7-facade-pattern--observabilityservice)
8. [Observer Pattern (Alert Triggers)](#8-observer-pattern--alert-triggers)
9. [Decorator Pattern (Span Enrichment)](#9-decorator-pattern--span-enrichment)
10. [Chain of Responsibility (LogProcessor)](#10-chain-of-responsibility--logprocessor)
11. [Template Method (MetricAggregator)](#11-template-method--metricaggregator)
12. [Singleton Pattern (AppConfig Lazy Init)](#12-singleton-pattern--appconfig-lazy-init)
13. [Pattern Interaction Map](#13-pattern-interaction-map)
14. [Interview Quick-Reference Table](#14-interview-quick-reference-table)

---

## 1. Strategy Pattern -- Sampling

### GoF Category
**Behavioral** -- Defines a family of algorithms, encapsulates each one, and makes them
interchangeable. Strategy lets the algorithm vary independently from clients that use it.

### Anti-Pattern: Hardcoded Sampling Logic

```java
// BAD: Giant if/else chain that grows with every new sampling approach
public class TracingService {
    private String samplingMode = "HEAD_BASED";

    public boolean shouldSample(TraceContext ctx, String op) {
        if (samplingMode.equals("HEAD_BASED")) {
            int hash = Math.abs(ctx.getTraceId().hashCode());
            return (hash % 100) < 50;
        } else if (samplingMode.equals("TAIL_BASED")) {
            return "true".equals(ctx.getBaggageItem("error"));
        } else if (samplingMode.equals("RATE_LIMITED")) {
            // Token bucket logic mixed into the service...
            return counter.incrementAndGet() <= maxPerSecond;
        }
        // Every new strategy = another else-if branch
        // Violates OCP: modification instead of extension
        return true;
    }
}
```

**Problems**:
- Violates OCP -- adding a new strategy requires modifying TracingService.
- Violates SRP -- TracingService now owns sampling algorithm logic.
- Impossible to unit test one strategy in isolation.
- Runtime strategy swapping requires string-based switching (fragile).

### Clean Solution: Strategy Interface + Concrete Strategies

```java
// Interface: one contract, many implementations
public interface SamplingStrategy {
    boolean shouldSample(TraceContext context);
    boolean shouldSample(TraceContext context, String operationName);
    String getStrategyName();
}

// Concrete Strategy 1: Head-Based
public class HeadBasedSamplingStrategy implements SamplingStrategy {
    private final double sampleRate;
    @Override
    public boolean shouldSample(TraceContext context) {
        int hash = Math.abs(context.getTraceId().hashCode());
        return (hash % 100) < (int)(sampleRate * 100);
    }
}

// Concrete Strategy 2: Tail-Based
public class TailBasedSamplingStrategy implements SamplingStrategy {
    @Override
    public boolean shouldSample(TraceContext context) { return true; }
    @Override
    public boolean shouldSample(TraceContext ctx, String op) {
        return "true".equals(ctx.getBaggageItem("error"))
            || Long.parseLong(ctx.getBaggageItem("latency_ms")) > threshold;
    }
}

// Concrete Strategy 3: Rate-Limited
public class RateLimitedSamplingStrategy implements SamplingStrategy {
    @Override
    public boolean shouldSample(TraceContext context) {
        // Sliding-window token bucket with AtomicInteger
    }
}
```

### Numbered Call Chain

```
1. ObservabilityPlatformApp creates AppConfig
2. AppConfig.getSamplingStrategy() -> default HeadBasedSamplingStrategy(1.0)
3. AppConfig.getTracingService() -> new TracingService(repo, assembler, samplingStrategy)
4. TracingService.startTrace("POST /api/orders", "api-gateway")
   4a. TraceContext.newTrace() -> fresh traceId
   4b. samplingStrategy.shouldSample(context, "POST /api/orders")
   4c. HeadBasedSamplingStrategy: hash(traceId) % 100 < 100 -> true
   4d. Build root Span, return
5. config.setSamplingStrategy(new TailBasedSamplingStrategy(1.0, 100))
   5a. AppConfig nulls out: samplingEngine, tracingService, observabilityService, controller
6. config.getTracingService() -> re-creates with TailBasedSamplingStrategy
7. Next startTrace() call uses tail-based logic instead
```

### ASCII Diagram

```
                +-------------------+
                | SamplingStrategy  |  <<interface>>
                |                   |
                | + shouldSample()  |
                | + getStrategyName |
                +---------+---------+
                          |
          +---------------+---+-----------------+
          |                   |                  |
+---------+---------+ +-------+---------+ +------+----------+
| HeadBased         | | TailBased       | | RateLimited     |
| SamplingStrategy  | | SamplingStrategy| | SamplingStrategy |
|                   | |                 | |                  |
| hash(traceId)     | | always collect; | | AtomicInteger    |
| mod sampleRate    | | filter post-hoc | | per-second cap   |
+-------------------+ +-----------------+ +------------------+
          ^                   ^                  ^
          |                   |                  |
          +------- SamplingEngine delegates -----+
                          |
                          v
                  +----------------+
                  | TracingService |
                  +----------------+
```

### Interview Soundbite

> "We use Strategy for sampling because the algorithm must be swappable at runtime.
> Head-based is cheap but blind -- it drops interesting traces. Tail-based keeps errors
> but requires buffering all spans. Rate-limited provides cost control. In production,
> you layer all three: head-based as a 10% baseline, tail-based for 100% of errors,
> rate-limited as a safety valve at 1000 traces/second."

---

## 2. Strategy Pattern -- Aggregation

### GoF Category
**Behavioral** -- Same pattern, different domain: metric aggregation algorithms.

### Anti-Pattern: Switch Statement in MetricService

```java
// BAD: MetricService owns all aggregation logic
public double aggregate(String metricName, String aggregationType) {
    List<MetricPoint> points = query(metricName);
    switch (aggregationType) {
        case "P99":
            // 20 lines of percentile calculation
        case "RATE":
            // 15 lines of rate calculation
        case "EWMA":
            // 25 lines of exponential weighted moving average
        default:
            throw new IllegalArgumentException("Unknown: " + aggregationType);
    }
    // MetricService is now 500 lines and growing
}
```

### Clean Solution: AggregationStrategy Interface

```java
public interface AggregationStrategy {
    double aggregate(List<MetricPoint> points);
    String getStrategyName();
}

public class PercentileAggregationStrategy implements AggregationStrategy {
    private final double percentile;  // e.g., 99.0
    @Override
    public double aggregate(List<MetricPoint> points) {
        // Sort ascending, nearest-rank: index = ceil(P/100 * N) - 1
        List<MetricPoint> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparingDouble(MetricPoint::getValue));
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, index)).getValue();
    }
}

public class RateAggregationStrategy implements AggregationStrategy {
    @Override
    public double aggregate(List<MetricPoint> points) {
        // Sort by timestamp, rate = (last - first) / timeDelta
    }
}
```

### Numbered Call Chain

```
1. AppConfig.getAggregationStrategy() -> default PercentileAggregationStrategy(99)
2. MetricService.aggregate("http.request.duration_ms", from, to, strategy)
   2a. timeSeriesStore.query("http.request.duration_ms", from, to) -> List<MetricPoint>
   2b. strategy.aggregate(points)
   2c. PercentileAggregationStrategy:
       - Sort points by value ascending
       - index = ceil(99/100 * size) - 1
       - Return sorted[index]
3. config.setAggregationStrategy(new RateAggregationStrategy())
   3a. AppConfig nulls out: metricService, dashboardService, observabilityService, controller
4. Next aggregate() call uses rate logic: (lastValue - firstValue) / timeDelta
```

### ASCII Diagram

```
          +---------------------+
          | AggregationStrategy |  <<interface>>
          |                     |
          | + aggregate(points) |
          | + getStrategyName() |
          +----------+----------+
                     |
          +----------+----------+
          |                     |
+---------+----------+ +-------+-----------+
| PercentileAgg      | | RateAgg           |
| Strategy           | | Strategy          |
|                    | |                   |
| Sort + nearest-    | | (last - first) /  |
| rank index         | | timeDelta         |
+--------------------+ +-------------------+

  Called by: MetricService.aggregate(name, from, to, strategy)
             DashboardService.getMetricSummary(name, window)
```

### Interview Soundbite

> "Aggregation is a Strategy because dashboards need different views of the same data.
> P99 latency for SLO monitoring, rate-of-change for counter metrics, average for gauges.
> The caller picks the strategy; the metric service just delegates. Adding EWMA or
> histogram-merge is one new class, zero changes to MetricService."

---

## 3. Strategy Pattern -- Alerting

### GoF Category
**Behavioral** -- Third application of Strategy in the platform: alert evaluation algorithms.

### Anti-Pattern: Mixed Alert Logic

```java
// BAD: AlertService contains all evaluation algorithms
public boolean evaluate(AlertRule rule, List<MetricPoint> points) {
    if (rule.getAlertType().equals("THRESHOLD")) {
        double avg = mean(points);
        return avg > rule.getThreshold();
    } else if (rule.getAlertType().equals("ANOMALY")) {
        double mean = mean(points);
        double stdDev = stdDev(points, mean);
        return Math.abs(latest(points) - mean) > 2.0 * stdDev;
    } else if (rule.getAlertType().equals("SEASONAL")) {
        // Holt-Winters decomposition mixed into AlertService...
    }
}
```

### Clean Solution: AlertingStrategy Interface

```java
public interface AlertingStrategy {
    boolean shouldAlert(AlertRule rule, List<MetricPoint> recentPoints);
    String getStrategyName();
}

public class ThresholdAlertingStrategy implements AlertingStrategy {
    @Override
    public boolean shouldAlert(AlertRule rule, List<MetricPoint> recentPoints) {
        double average = recentPoints.stream()
            .mapToDouble(MetricPoint::getValue).average().orElse(0.0);
        if (rule.getCondition().startsWith("<")) return average < rule.getThreshold();
        return average > rule.getThreshold();
    }
}

public class AnomalyDetectionAlertingStrategy implements AlertingStrategy {
    private final double stdDevMultiplier;
    @Override
    public boolean shouldAlert(AlertRule rule, List<MetricPoint> recentPoints) {
        if (recentPoints.size() < 3) return false;
        double mean = mean(recentPoints);
        double stdDev = stdDev(recentPoints, mean);
        double latest = recentPoints.get(recentPoints.size() - 1).getValue();
        return Math.abs(latest - mean) > (stdDevMultiplier * stdDev);
    }
}
```

### Numbered Call Chain

```
1. AppConfig.getAlertingStrategy() -> default ThresholdAlertingStrategy
2. AlertService.evaluateRules()
   2a. For each enabled rule:
   2b.   metricService.query(rule.metricName, now-60s, now)
   2c.   alertingStrategy.shouldAlert(rule, points)
   2d.   ThresholdAlertingStrategy:
         - mean = average of point values
         - rule.condition = ">" -> return mean > rule.threshold
   2e.   If triggered: new Alert(rule, currentValue, message) -> alertRepo.save()
3. config.setAlertingStrategy(new AnomalyDetectionAlertingStrategy(2.0))
   3a. AppConfig nulls out: alertService, observabilityService, controller
4. Next evaluateRules() uses anomaly detection:
   4a. mean, stdDev of recent points
   4b. abs(latest - mean) > 2.0 * stdDev -> alert
```

### ASCII Diagram

```
          +--------------------+
          | AlertingStrategy   |  <<interface>>
          |                    |
          | + shouldAlert()    |
          | + getStrategyName()|
          +---------+----------+
                    |
          +---------+----------+
          |                    |
+---------+---------+ +--------+------------------+
| ThresholdAlerting | | AnomalyDetectionAlerting  |
| Strategy          | | Strategy                  |
|                   | |                           |
| avg(points)       | | mean +/- k * stdDev       |
| > or < threshold  | | latest outside band?      |
+-------------------+ +---------------------------+

                    ^
                    |
            +-------+-------+
            | AlertService  |
            | evaluateRules |
            +---------------+
```

### Interview Soundbite

> "Static thresholds are simple but brittle for dynamic workloads. Anomaly detection
> adapts because the 'normal' range is computed from the data itself. We use Strategy
> so AlertService doesn't care which algorithm is active -- it just calls shouldAlert().
> In production, you'd add Holt-Winters for seasonal workloads, Prophet for ML-based
> forecasting -- each is one new class implementing AlertingStrategy."

---

## 4. Builder Pattern -- Metric, Span, AlertRule

### GoF Category
**Creational** -- Separates the construction of a complex object from its representation
so that the same construction process can create different representations.

### Anti-Pattern: Telescoping Constructors

```java
// BAD: 8-parameter constructor -- which argument is which?
Metric m = new Metric(
    UUID.randomUUID().toString(),  // id
    "http.requests",               // name
    MetricType.COUNTER,            // type
    "Request counter",             // description
    "count",                       // unit
    tags,                          // tags
    new ArrayList<>(),             // dataPoints
    Instant.now()                  // createdAt
);
// Caller must remember: is description before or after unit?
// What if we add a new optional field? Every caller breaks.
```

**Problems**:
- Parameter order is fragile -- easy to swap description and unit.
- All parameters must be provided even when most are optional.
- Adding a new field requires updating every callsite.

### Clean Solution: Static Inner Builder

```java
public class Metric {
    private Metric(Builder builder) { /* copy fields from builder */ }

    public static class Builder {
        // Required fields in constructor
        public Builder(String name, MetricType metricType) { ... }

        // Optional fields via fluent setters
        public Builder id(String id) { this.id = id; return this; }
        public Builder description(String desc) { this.description = desc; return this; }
        public Builder unit(String unit) { this.unit = unit; return this; }
        public Builder tags(Map<String, String> tags) { this.tags = tags; return this; }

        public Metric build() { return new Metric(this); }
    }
}

// Usage: clean, self-documenting, only set what you need
Metric metric = new Metric.Builder("http.requests", MetricType.COUNTER)
    .description("Total HTTP request count")
    .unit("count")
    .tags(Map.of("method", "GET"))
    .build();
```

### All Three Builders in This Codebase

**Metric.Builder**:
```
Required: name, metricType
Optional: id, description, unit, tags, dataPoints, createdAt
Defaults: id=UUID, createdAt=Instant.now()
```

**Span.Builder**:
```
Required: traceId, operationName, serviceName
Optional: spanId, parentSpanId, startTime, endTime, duration, status, tags, logs
Defaults: spanId=UUID, startTime=Instant.now()
```

**AlertRule.Builder**:
```
Required: name, metricName
Optional: id, condition, threshold, durationSeconds, severity, enabled
Defaults: id=UUID, durationSeconds=60, severity=WARNING, enabled=true
```

### Numbered Call Chain

```
1. Demo creates an AlertRule:
   new AlertRule.Builder("High CPU Alert", "system.cpu.usage_percent")
     .condition(">")
     .threshold(80.0)
     .durationSeconds(60)
     .severity(AlertSeverity.CRITICAL)
     .build()

2. Builder stores each field:
   2a. Builder constructor: name="High CPU Alert", metricName="system.cpu.usage_percent"
   2b. .condition(">")        -> this.condition = ">"
   2c. .threshold(80.0)       -> this.threshold = 80.0
   2d. .durationSeconds(60)   -> this.durationSeconds = 60
   2e. .severity(CRITICAL)    -> this.severity = CRITICAL

3. .build() calls private AlertRule(Builder) constructor:
   3a. Copies all fields from builder to final AlertRule fields
   3b. Returns immutable AlertRule instance
```

### ASCII Diagram

```
+-------------------+       +---------------------+
|   Client Code     |       |   Metric.Builder    |
|                   |       |                     |
| new Builder(name, |------>| name: String        |
|   metricType)     |       | metricType: MetricType|
|   .description()  |------>| description: String |
|   .unit()         |------>| unit: String        |
|   .tags()         |------>| tags: Map           |
|   .build()        |------>| build():Metric -----+----> Metric (immutable)
+-------------------+       +---------------------+      private constructor

+-------------------+       +---------------------+
|   Client Code     |       |   Span.Builder      |
|                   |       |                     |
| new Builder(trace,|------>| traceId: String     |
|   op, service)    |       | operationName: String|
|   .parentSpanId() |------>| parentSpanId: String|
|   .status()       |------>| status: SpanStatus  |
|   .tags()         |------>| tags: Map           |
|   .build()        |------>| build():Span -------+----> Span (mutable lifecycle)
+-------------------+       +---------------------+      finish() stamps endTime
```

### Interview Soundbite

> "Builder solves the telescoping constructor problem for Metric, Span, and AlertRule.
> Each has 2-3 required fields in the Builder constructor and 5+ optional fields via
> fluent setters. The private constructor ensures objects can only be created through
> the builder, preventing partially initialized state. The tradeoff is more verbose
> setup code, but it's self-documenting and callsite-safe."

---

## 5. Factory Method / Composition Root (AppConfig)

### GoF Category
**Creational** -- Defines an interface for creating objects, but lets subclasses decide
which classes to instantiate. Here applied as a Composition Root that centralizes all
object creation and wiring.

### Anti-Pattern: Scattered Object Creation

```java
// BAD: Each class creates its own dependencies
public class TracingService {
    private final TraceRepository repo = new InMemoryTraceRepository();     // hardcoded
    private final TraceAssembler assembler = new TraceAssembler();          // hardcoded
    private final SamplingStrategy sampling = new HeadBasedSamplingStrategy(0.5); // hardcoded

    // Cannot swap to PostgresTraceRepository or TailBasedSampling without editing this class
}
```

**Problems**:
- Concrete dependencies are hardcoded -- violates DIP.
- Cannot swap implementations without modifying source.
- Testing requires modifying production code.
- No single place to see the full dependency graph.

### Clean Solution: AppConfig as Composition Root

```java
public class AppConfig {
    private SamplingStrategy samplingStrategy;
    private TracingService tracingService;

    public SamplingStrategy getSamplingStrategy() {
        if (samplingStrategy == null)
            samplingStrategy = new HeadBasedSamplingStrategy(1.0);  // default
        return samplingStrategy;
    }

    public void setSamplingStrategy(SamplingStrategy strategy) {
        this.samplingStrategy = strategy;
        this.tracingService = null;       // invalidate dependents
        this.observabilityService = null;
        this.controller = null;
    }

    public TracingService getTracingService() {
        if (tracingService == null)
            tracingService = new TracingService(
                getTraceRepository(), getTraceAssembler(), getSamplingStrategy());
        return tracingService;
    }
}
```

### Numbered Call Chain

```
1. ObservabilityPlatformApp: new AppConfig()
2. config.getController()
   2a. controller == null -> need to create
   2b. config.getObservabilityService()
       2b-i.   observabilityService == null -> need to create
       2b-ii.  config.getMetricService()
               -> metricService == null
               -> new MetricService(getMetricRepository(), getTimeSeriesStore(), getMetricAggregator())
               -> getMetricRepository() -> new InMemoryMetricRepository()
               -> getTimeSeriesStore() -> new TimeSeriesStore()
               -> getMetricAggregator() -> new MetricAggregator()
       2b-iii. config.getTracingService()
               -> new TracingService(getTraceRepository(), getTraceAssembler(), getSamplingStrategy())
       2b-iv.  config.getLogService()
               -> new LogService(getLogRepository(), getLogProcessor())
       2b-v.   config.getAlertService()
               -> new AlertService(getAlertRepository(), getAlertingStrategy(), getMetricService())
       2b-vi.  config.getDashboardService()
               -> new DashboardService(metric, tracing, log)
       2b-vii. config.getServiceMapService()
               -> new ServiceMapService()
       2b-viii. new ObservabilityService(all 6 services)
   2c. config.getAlertService() -> already created, reused
   2d. config.getDashboardService() -> already created, reused
   2e. new ObservabilityController(observability, alert, dashboard)
3. All subsequent getX() calls return the cached instance
```

### ASCII Diagram

```
                    +---------------------------+
                    |        AppConfig           |
                    |    (Composition Root)      |
                    |                           |
                    | -- Repositories (4) ------+---> InMemoryMetricRepository
                    |                           |---> InMemoryTraceRepository
                    |                           |---> InMemoryLogRepository
                    |                           |---> InMemoryAlertRepository
                    |                           |
                    | -- Engines (5) -----------+---> MetricAggregator
                    |                           |---> TraceAssembler
                    |                           |---> LogProcessor
                    |                           |---> TimeSeriesStore
                    |                           |---> SamplingEngine
                    |                           |
                    | -- Strategies (3) --------+---> HeadBasedSamplingStrategy
                    |    (swappable via setters)|---> PercentileAggregationStrategy
                    |                           |---> ThresholdAlertingStrategy
                    |                           |
                    | -- Services (7) ----------+---> MetricService
                    |                           |---> TracingService
                    |                           |---> LogService
                    |                           |---> AlertService
                    |                           |---> DashboardService
                    |                           |---> ServiceMapService
                    |                           |---> ObservabilityService
                    |                           |
                    | -- Controller (1) --------+---> ObservabilityController
                    | -- Display (1) -----------+---> ObservabilityStatsDisplay
                    +---------------------------+

  Strategy Swap Cascade:
    setSamplingStrategy()
      -> nulls: samplingEngine, tracingService, observabilityService, controller
    setAggregationStrategy()
      -> nulls: metricService, dashboardService, observabilityService, controller
    setAlertingStrategy()
      -> nulls: alertService, observabilityService, controller
```

### Interview Soundbite

> "AppConfig is our composition root -- the single place where all dependencies are
> wired. Lazy initialization means we only create what we need. Strategy setters
> cascade-invalidate dependents so the entire object graph stays consistent after
> a runtime strategy swap. In a Spring application this would be the DI container;
> here we do it manually to keep the pattern visible."

---

## 6. Repository Pattern -- 4 Repositories

### GoF Category
**Structural** (from DDD/Enterprise patterns) -- Mediates between the domain and data
mapping layers using a collection-like interface for accessing domain objects.

### Anti-Pattern: Direct Data Access in Services

```java
// BAD: Service directly owns and manages the data store
public class MetricService {
    private final Map<String, Metric> store = new ConcurrentHashMap<>();  // data access logic

    public void recordMetric(Metric m) {
        store.put(m.getId(), m);  // persistence logic mixed with business logic
    }

    public List<Metric> findByType(MetricType type) {
        return store.values().stream()  // query logic mixed with business logic
            .filter(m -> m.getMetricType() == type)
            .collect(Collectors.toList());
    }
    // Service is now coupled to ConcurrentHashMap -- cannot swap to DB
}
```

### Clean Solution: Interface + InMemory Implementation

```java
// Interface: defines what the service needs
public interface MetricRepository {
    void save(Metric metric);
    Optional<Metric> findById(String id);
    List<Metric> findByName(String name);
    List<Metric> findByType(MetricType type);
    List<Metric> findAll();
    void deleteById(String id);
}

// Implementation: how it's stored (can swap without touching the service)
public class InMemoryMetricRepository implements MetricRepository {
    private final Map<String, Metric> store = new ConcurrentHashMap<>();

    @Override
    public void save(Metric metric) { store.put(metric.getId(), metric); }

    @Override
    public List<Metric> findByName(String name) {
        return store.values().stream()
            .filter(m -> m.getName().equals(name))
            .collect(Collectors.toList());
    }
    // ... all other interface methods
}

// Service depends on the interface, not the implementation
public class MetricService {
    private final MetricRepository metricRepo;  // injected via constructor
    public MetricService(MetricRepository repo, ...) { this.metricRepo = repo; }
}
```

### All Four Repository Pairs

```
Interface                   Implementation                  Backing Store
---                         ---                             ---
MetricRepository            InMemoryMetricRepository        ConcurrentHashMap<id, Metric>
TraceRepository             InMemoryTraceRepository         ConcurrentHashMap<traceId, Trace>
LogRepository               InMemoryLogRepository           ConcurrentHashMap<seq, LogEntry>
AlertRepository             InMemoryAlertRepository         ConcurrentHashMap<id, Alert>
```

### Numbered Call Chain

```
1. AppConfig.getMetricRepository() -> new InMemoryMetricRepository()
2. AppConfig.getMetricService() -> new MetricService(metricRepo, tsStore, aggregator)
3. MetricService.recordMetric("http.requests", 1, COUNTER, tags)
   3a. Create MetricPoint
   3b. timeSeriesStore.store(point)
   3c. metricRepo.findByName("http.requests")  // <-- Repository call
       -> InMemoryMetricRepository.findByName()
       -> store.values().stream().filter(name matches).collect()
   3d. If empty: metricRepo.save(new Metric.Builder(...).build())
       -> InMemoryMetricRepository.save()
       -> store.put(metric.id, metric)
```

### ASCII Diagram

```
+------------------+                +------------------------------+
| MetricService    |   depends on   | MetricRepository             |
|                  |--------------->| <<interface>>                |
| recordMetric()   |                |                              |
| query()          |                | + save(Metric)               |
| aggregate()      |                | + findById(String): Optional |
+------------------+                | + findByName(String): List   |
                                    | + findAll(): List            |
                                    +---------------+--------------+
                                                    |
                                                    | implements
                                                    v
                                    +------------------------------+
                                    | InMemoryMetricRepository     |
                                    |                              |
                                    | ConcurrentHashMap<id, Metric>|
                                    | .save() -> map.put()         |
                                    | .findByName() -> stream()    |
                                    +------------------------------+

  Swap path (zero service changes):
    InMemoryMetricRepository --> PostgresMetricRepository
    InMemoryMetricRepository --> RedisMetricRepository
    InMemoryMetricRepository --> TimescaleDBMetricRepository
```

### Interview Soundbite

> "Four Repository interfaces abstract the data layer. Services depend on the interface,
> so swapping InMemory for Postgres or TimescaleDB is one new class and one wiring
> change in AppConfig. The in-memory implementations use ConcurrentHashMap and return
> immutable copies from findAll() to prevent callers from mutating the store."

---

## 7. Facade Pattern -- ObservabilityService

### GoF Category
**Structural** -- Provides a unified interface to a set of interfaces in a subsystem.
Facade defines a higher-level interface that makes the subsystem easier to use.

### Anti-Pattern: Client Talks to Six Services

```java
// BAD: Every caller must know about and coordinate six services
public class SomeController {
    private final MetricService metricService;
    private final TracingService tracingService;
    private final LogService logService;
    private final AlertService alertService;
    private final DashboardService dashboardService;
    private final ServiceMapService serviceMapService;

    // Constructor with 6 parameters
    // Every new service = change every controller/caller
    // Cross-cutting operations require manual coordination
}
```

### Clean Solution: Facade Wraps All Sub-Services

```java
public class ObservabilityService {
    private final MetricService metricService;
    private final TracingService tracingService;
    private final LogService logService;
    private final AlertService alertService;
    private final DashboardService dashboardService;
    private final ServiceMapService serviceMapService;

    // Simple delegations
    public void recordMetric(String name, double value, MetricType type, Map<String, String> tags) {
        metricService.recordMetric(name, value, type, tags);
    }
    public Span startTrace(String op, String service) {
        return tracingService.startTrace(op, service);
    }
    public void log(LogLevel level, String msg, String service) {
        logService.log(level, msg, service);
    }
    // ... delegates for all operations
}
```

### Numbered Call Chain

```
1. ObservabilityController receives request
2. controller.ingestMetric("cpu.usage", 85.0, GAUGE, tags)
   2a. Print "[CONTROLLER] POST /metrics"
   2b. observabilityService.recordMetric("cpu.usage", 85.0, GAUGE, tags)
3. ObservabilityService.recordMetric()
   3a. Delegates to metricService.recordMetric("cpu.usage", 85.0, GAUGE, tags)
4. MetricService.recordMetric()
   4a. MetricPoint.of("cpu.usage", 85.0, GAUGE, tags)
   4b. timeSeriesStore.store(point)
   4c. metricRepo.findByName("cpu.usage") -> check existence
   4d. Print "[METRIC] Recorded GAUGE 'cpu.usage' = 85.0"
```

### ASCII Diagram

```
+-------------------------+
| ObservabilityController |
| (or any client)         |
+------------+------------+
             |
             |  single dependency
             v
+----------------------------+
| ObservabilityService       |
|        (FACADE)            |
|                            |
| recordMetric()  --------+ |
| startTrace()    --------|-+---> TracingService
| finishSpan()    --------|-+---> TracingService
| log()           --------|-+---> LogService
| logWithTrace()  --------|-+---> LogService
| evaluateAlerts()--------|-+---> AlertService
| getSystemOverview()-----|-+---> DashboardService
| registerServiceCall()---|-+---> ServiceMapService
|                    ------+ |
|                    |       |
|                    v       |
|             MetricService  |
+----------------------------+

  Without Facade: Controller needs 6 injected services
  With Facade:    Controller needs 1 injected service (+ 2 for direct access)
```

### Interview Soundbite

> "ObservabilityService is a Facade over six sub-services. The controller talks to one
> class instead of six, which cuts coupling from O(C*S) to O(C+S) where C=callers
> and S=services. Adding a seventh service (e.g., SLOService) requires one change in
> the facade, zero changes in existing callers. It also provides a natural place for
> cross-cutting concerns like audit logging or rate limiting."

---

## 8. Observer Pattern -- Alert Triggers

### GoF Category
**Behavioral** -- Defines a one-to-many dependency between objects so that when one
object changes state, all its dependents are notified and updated automatically.

### Anti-Pattern: Polling with No Event System

```java
// BAD: AlertService manually polls metrics on a timer
public class AlertService {
    public void checkAlerts() {
        while (true) {
            Thread.sleep(10000);
            for (AlertRule rule : rules) {
                double latest = metricService.getLatest(rule.getMetricName());
                if (latest > rule.getThreshold()) fireAlert(rule, latest);
            }
        }
    }
    // Wasteful polling, tight coupling, no clean shutdown
}
```

### Clean Solution: Event-Driven Evaluation

In this codebase, the Observer pattern manifests as AlertService reacting to metric data changes:

```java
public class AlertService {
    private final List<AlertRule> rules = new CopyOnWriteArrayList<>();
    private final Map<String, String> firingRules = new HashMap<>();  // ruleId -> alertId

    // "Observer" method: called when metric data may have changed
    public void evaluateRules() {
        for (AlertRule rule : rules) {
            List<MetricPoint> points = metricService.query(rule.getMetricName(), ...);
            boolean shouldAlert = alertingStrategy.shouldAlert(rule, points);

            if (shouldAlert && !firingRules.containsKey(rule.getId())) {
                // State change: OK -> FIRING
                Alert alert = new Alert(rule, currentValue, message);
                alertRepo.save(alert);
                firingRules.put(rule.getId(), alert.getId());
            } else if (!shouldAlert && firingRules.containsKey(rule.getId())) {
                // State change: FIRING -> RESOLVED
                resolveAlert(firingRules.remove(rule.getId()));
            }
        }
    }
}
```

### Numbered Call Chain

```
1. Metrics flow in: metricService.recordGauge("cpu.usage", 92, tags)
2. Evaluation trigger: alertService.evaluateRules()  (could be periodic or event-driven)
3. For rule "High CPU" watching "cpu.usage" with threshold > 80:
   3a. metricService.query("cpu.usage", now-60s, now) -> [85, 88, 90, 92]
   3b. alertingStrategy.shouldAlert(rule, points)
       -> ThresholdAlertingStrategy: mean(85,88,90,92) = 88.75 > 80 -> true
   3c. firingRules does NOT contain this rule's ID -> NEW alert
   3d. new Alert(rule, 92, "Rule 'High CPU' triggered: > 80 (current=92)")
   3e. alertRepo.save(alert)
   3f. firingRules.put(ruleId, alertId)
   3g. Print "[ALERT] FIRING -- rule 'High CPU' | value=92 | threshold=80"
4. Later, CPU recovers: metricService.recordGauge("cpu.usage", 45, tags)
5. alertService.evaluateRules()
   5a. query -> [45]
   5b. shouldAlert -> 45 > 80 = false
   5c. firingRules contains this rule -> RESOLVE
   5d. resolveAlert(alertId) -> alert.resolve() -> status=RESOLVED, resolvedAt=now
   5e. Print "[ALERT] RESOLVED -- rule 'High CPU' recovered"
```

### ASCII Diagram

```
  +-----------------+    record     +-----------------+
  |  MetricService  |<-------------|  Metric Sources  |
  |                 |              |  (counters, etc) |
  +--------+--------+              +-----------------+
           |
           | query(metricName, window)
           v
  +-----------------+    evaluate   +-----------------+
  |  AlertService   |<-------------|  Scheduler /     |
  |                 |              |  Manual Trigger  |
  |  - rules[]     |              +-----------------+
  |  - firingRules |
  +--------+--------+
           |
           | shouldAlert(rule, points)
           v
  +-----------------+
  | AlertingStrategy|
  | (threshold or   |
  |  anomaly detect)|
  +-----------------+
           |
           | true -> new Alert(FIRING)
           | false + was firing -> resolve()
           v
  +-----------------+
  | AlertRepository |
  |  save / update  |
  +-----------------+
```

### Interview Soundbite

> "AlertService implements the Observer concept: alert rules 'observe' metric data and
> react to threshold crossings. The firingRules map tracks state transitions -- OK to
> FIRING on breach, FIRING to RESOLVED on recovery. This prevents duplicate alerts for
> the same condition and enables clean state management."

---

## 9. Decorator Pattern -- Span Enrichment

### GoF Category
**Structural** -- Attaches additional responsibilities to an object dynamically.
Decorators provide a flexible alternative to subclassing for extending functionality.

### Anti-Pattern: God Span Constructor

```java
// BAD: Try to capture everything at creation time
Span span = new Span(traceId, spanId, parentId, op, service, startTime,
    endTime, duration, SpanStatus.OK,
    Map.of("http.method", "GET", "http.url", "/api/users",
           "http.status_code", "200", "db.system", "postgres",
           "db.statement", "SELECT * FROM users"),
    List.of(new SpanLog(now, "cache.miss", Map.of("key", "user:123")),
            new SpanLog(now, "db.query", Map.of("duration_ms", "45"))));
// Impossible to know all tags/logs at construction time
// What about tags added by middleware? By the database driver?
```

### Clean Solution: Progressive Enrichment

```java
// Span is created with minimal fields via Builder
Span span = new Span.Builder(traceId, "GET /api/users", "user-service")
    .build();

// Decorated with tags as execution progresses
span.addTag("http.method", "GET");
span.addTag("http.url", "/api/users");

// Decorated with logs as events occur
span.addLog(new SpanLog(Instant.now(), "cache.miss",
    Map.of("key", "user:123")));

// More decoration by downstream middleware
span.addTag("http.status_code", "200");
span.addLog(new SpanLog(Instant.now(), "db.query",
    Map.of("duration_ms", "45")));

// Finalize
span.finish();  // stamps endTime, computes duration, defaults status to OK
```

### Numbered Call Chain

```
1. TracingService.startTrace("GET /api/users", "user-service")
   -> Span.Builder(traceId, "GET /api/users", "user-service").build()
   -> Minimal span: traceId, spanId(UUID), operationName, serviceName, startTime(now)
2. Application code enriches progressively:
   span.addTag("http.method", "GET")              // decoration step 1
   span.addTag("http.url", "/api/users/123")      // decoration step 2
3. Cache middleware:
   span.addLog(new SpanLog(now, "cache.miss",      // decoration step 3
       Map.of("key", "user:123")))
4. Database driver:
   span.addTag("db.system", "postgres")            // decoration step 4
   span.addLog(new SpanLog(now, "db.query",        // decoration step 5
       Map.of("duration_ms", "45", "rows", "1")))
5. Response middleware:
   span.addTag("http.status_code", "200")          // decoration step 6
6. TracingService.finishSpan(span)
   -> span.finish() -- endTime=now, duration=end-start, status=OK
   -> Tags: {http.method, http.url, db.system, http.status_code}
   -> Logs: [cache.miss, db.query]
```

### ASCII Diagram

```
  +----------------------------+
  |     Span (initial)         |
  |                            |
  |  traceId, spanId, op, svc |
  |  tags: {}                  |
  |  logs: []                  |
  +----------------------------+
              |
              v  addTag("http.method", "GET")
  +----------------------------+
  |     Span (enriched)        |
  |                            |
  |  tags: {http.method: GET}  |
  |  logs: []                  |
  +----------------------------+
              |
              v  addLog(SpanLog("cache.miss"))
  +----------------------------+
  |     Span (enriched more)   |
  |                            |
  |  tags: {http.method: GET}  |
  |  logs: [cache.miss]        |
  +----------------------------+
              |
              v  addTag("db.system", "postgres") + addLog("db.query")
  +----------------------------+
  |     Span (fully enriched)  |
  |                            |
  |  tags: {http.method: GET,  |
  |         db.system: postgres}|
  |  logs: [cache.miss, db.qry]|
  +----------------------------+
              |
              v  finish()
  +----------------------------+
  |     Span (complete)        |
  |                            |
  |  endTime: set              |
  |  duration: computed        |
  |  status: OK                |
  +----------------------------+
```

### Interview Soundbite

> "Span enrichment is a Decorator pattern -- we add tags and logs progressively as the
> request flows through middleware layers. The span starts minimal (traceId, operation,
> service) and gains context as each layer decorates it. This is exactly how OpenTelemetry
> works: HTTP middleware adds http.* tags, DB drivers add db.* tags, and the application
> adds business-specific tags. Each layer enriches independently."

---

## 10. Chain of Responsibility -- LogProcessor

### GoF Category
**Behavioral** -- Avoids coupling the sender of a request to its receiver by giving
more than one object a chance to handle the request. Chain the receiving objects and
pass the request along the chain until an object handles it.

### Anti-Pattern: Nested If Statements for Log Filtering

```java
// BAD: All filtering logic in one monolithic method
public Optional<LogEntry> processLog(LogEntry entry) {
    if (entry.getLevel().getSeverity() < LogLevel.INFO.getSeverity()) {
        return Optional.empty();  // level filter
    }
    if (entry.getServiceName().equals("healthcheck")) {
        return Optional.empty();  // service filter
    }
    if (entry.getMessage().contains("password")) {
        return Optional.empty();  // PII filter
    }
    if (entry.getMessage().length() > 10000) {
        return Optional.empty();  // size filter
    }
    // Every new filter = modify this method
    // Cannot compose or reorder filters dynamically
    return Optional.of(entry);
}
```

### Clean Solution: Predicate Pipeline

```java
public class LogProcessor {
    private LogLevel minLevel = LogLevel.INFO;
    private final List<Predicate<LogEntry>> filters = new ArrayList<>();

    public void setMinLevel(LogLevel level) { this.minLevel = level; }
    public void addFilter(Predicate<LogEntry> filter) { filters.add(filter); }

    public Optional<LogEntry> process(LogEntry entry) {
        // Stage 1: Level gate
        if (!entry.getLevel().isAtLeast(minLevel)) return Optional.empty();

        // Stage 2: Custom filter chain -- entry must pass ALL predicates
        for (Predicate<LogEntry> filter : filters) {
            if (!filter.test(entry)) return Optional.empty();
        }

        return Optional.of(entry);
    }

    public List<LogEntry> processBatch(List<LogEntry> entries) {
        return entries.stream()
            .map(this::process)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(Collectors.toList());
    }

    public void enrichWithCorrelation(LogEntry entry, String traceId, String spanId) {
        entry.setTraceId(traceId);
        entry.setSpanId(spanId);
    }
}
```

### Numbered Call Chain

```
1. AppConfig.getLogProcessor() -> new LogProcessor()
   (default minLevel = INFO, no custom filters)

2. Runtime configuration:
   logProcessor.setMinLevel(LogLevel.WARN);
   logProcessor.addFilter(entry -> !entry.getMessage().contains("healthcheck"));
   logProcessor.addFilter(entry -> entry.getMessage().length() <= 10000);

3. LogService.log(DEBUG, "Cache hit", "user-service")
   3a. Create LogEntry(DEBUG, "Cache hit", "user-service")
   3b. logProcessor.process(entry)
       Stage 1: DEBUG.isAtLeast(WARN) -> false -> FILTERED OUT
   3c. Return Optional.empty()

4. LogService.log(ERROR, "Connection pool exhausted", "payment-service")
   4a. Create LogEntry(ERROR, "Connection pool exhausted", "payment-service")
   4b. logProcessor.process(entry)
       Stage 1: ERROR.isAtLeast(WARN) -> true -> PASS
       Stage 2: filter[0]: !msg.contains("healthcheck") -> true -> PASS
       Stage 2: filter[1]: msg.length() <= 10000 -> true -> PASS
   4c. logRepo.save(entry)
   4d. Print "[LOG] [ERROR] payment-service: Connection pool exhausted"
```

### ASCII Diagram

```
  +------------+
  | LogEntry   |
  | (incoming) |
  +-----+------+
        |
        v
  +-----+----------+     fail    +----------+
  | Level Gate      |----------->| FILTERED |
  | >= minLevel?    |            | (dropped)|
  +-----+----------+            +----------+
        | pass
        v
  +-----+----------+     fail    +----------+
  | Filter[0]      |----------->| FILTERED |
  | custom pred    |            | (dropped)|
  +-----+----------+            +----------+
        | pass
        v
  +-----+----------+     fail    +----------+
  | Filter[1]      |----------->| FILTERED |
  | custom pred    |            | (dropped)|
  +-----+----------+            +----------+
        | pass
        v
  +-----+----------+     fail    +----------+
  | Filter[N]      |----------->| FILTERED |
  | custom pred    |            | (dropped)|
  +-----+----------+            +----------+
        | pass
        v
  +-----+----------+
  | SURVIVED       |
  | -> save to repo|
  +----------------+
```

### Interview Soundbite

> "LogProcessor is a Chain of Responsibility -- entries flow through a level gate and
> then a list of predicate filters. Any filter can drop the entry. Filters are composable
> and orderable at runtime: add a PII scrubber, a size limiter, a service allowlist --
> each is one lambda, zero changes to LogProcessor. The enrichWithCorrelation method
> also decorates entries with traceId/spanId for cross-signal correlation."

---

## 11. Template Method -- MetricAggregator

### GoF Category
**Behavioral** -- Defines the skeleton of an algorithm in an operation, deferring some
steps to subclasses. Template Method lets subclasses redefine certain steps of an
algorithm without changing the algorithm's structure.

### Anti-Pattern: Copy-Pasted Aggregation Logic

```java
// BAD: Every aggregation function repeats the same null/empty checks
public double computeP99(List<MetricPoint> points) {
    if (points == null || points.isEmpty()) return 0.0;
    // ... percentile logic
}
public double computeRate(List<MetricPoint> points) {
    if (points == null || points.size() < 2) return 0.0;
    // ... rate logic
}
public double computeHistogram(List<MetricPoint> points) {
    if (points == null || points.isEmpty()) return 0.0;
    // ... histogram logic
}
// Same guard clauses, same empty-handling, copied everywhere
```

### Clean Solution: Consistent Method Shape

In MetricAggregator, every aggregation method follows the same template:

```java
public class MetricAggregator {
    // Template: check empty -> compute -> return
    public double sum(List<MetricPoint> points) {
        if (points.isEmpty()) return 0.0;                   // Step 1: guard
        return points.stream().mapToDouble(getValue).sum();  // Step 2: compute
    }

    public double average(List<MetricPoint> points) {
        if (points.isEmpty()) return 0.0;                   // Step 1: guard
        return points.stream().mapToDouble(getValue)         // Step 2: compute
            .average().orElse(0.0);
    }

    public double percentile(List<MetricPoint> points, double p) {
        if (points.isEmpty()) return 0.0;                   // Step 1: guard
        List<Double> sorted = sort(points);                  // Step 2a: prepare
        int index = nearestRank(p, sorted.size());           // Step 2b: compute
        return sorted.get(index);                            // Step 2c: return
    }

    public double rate(List<MetricPoint> points) {
        if (points.size() < 2) return 0.0;                  // Step 1: guard
        List<MetricPoint> sorted = sortByTime(points);       // Step 2a: prepare
        return delta(sorted) / timeDelta(sorted);            // Step 2b: compute
    }

    public Map<String, Long> histogram(List<MetricPoint> points, double[] bounds) {
        if (points.isEmpty()) return Map.of();               // Step 1: guard
        // Step 2: initialize buckets, distribute, return    // Step 2: compute
    }
}
```

**Template structure**:
```
Step 1: Guard clause (empty/null check, minimum size check)
Step 2: Core computation (specific to each aggregation type)
Step 3: Return result (always a numeric value or structured map)
```

### Numbered Call Chain

```
1. DashboardService.getMetricSummary("http.request.duration_ms", Duration.ofMinutes(15))
2. metricService.query("http.request.duration_ms", from, now) -> [45.2, 120.5, 23.1, 89.7]
3. MetricAggregator.average(points)
   3a. Guard: points.isEmpty() -> false -> proceed
   3b. Compute: stream().mapToDouble(getValue).average() -> 69.625
4. MetricAggregator.percentile(points, 99)
   4a. Guard: points.isEmpty() -> false -> proceed
   4b. Sort ascending: [23.1, 45.2, 89.7, 120.5]
   4c. index = ceil(99/100 * 4) - 1 = ceil(3.96) - 1 = 4 - 1 = 3
   4d. Return sorted[3] = 120.5
5. MetricAggregator.rate(points)
   5a. Guard: points.size() < 2 -> false -> proceed
   5b. Sort by timestamp
   5c. valueDiff = last - first, timeDiff = duration in seconds
   5d. Return valueDiff / timeDiff
```

### ASCII Diagram

```
  +------------------------------+
  |      MetricAggregator        |
  |      (Template Method)       |
  +------------------------------+
  |                              |
  | sum(points):                 |   TEMPLATE:
  |   [guard] -> [stream sum]    |   1. Guard clause
  |                              |   2. Core computation
  | average(points):             |   3. Return result
  |   [guard] -> [stream avg]    |
  |                              |
  | min(points):                 |
  |   [guard] -> [stream min]    |
  |                              |
  | max(points):                 |
  |   [guard] -> [stream max]    |
  |                              |
  | percentile(points, p):       |
  |   [guard] -> [sort] ->       |
  |   [nearest-rank] -> [return] |
  |                              |
  | rate(points):                |
  |   [guard(min 2)] -> [sort    |
  |   by time] -> [delta/time]   |
  |                              |
  | count(points):               |
  |   [return size]              |
  |                              |
  | histogram(points, bounds):   |
  |   [guard] -> [init buckets]  |
  |   -> [distribute] -> [return]|
  +------------------------------+
```

### Interview Soundbite

> "MetricAggregator uses Template Method -- every aggregation follows the same skeleton:
> guard clause for empty input, then the core computation, then return. This prevents
> the common bug of forgetting empty-list checks and ensures consistent behavior
> (always returns 0.0 for empty input, never throws). The AggregationStrategy interface
> extends this further: each strategy implementation follows the same template shape
> but with different core algorithms."

---

## 12. Singleton Pattern -- AppConfig Lazy Init

### GoF Category
**Creational** -- Ensures a class has only one instance and provides a global point
of access to it.

### Anti-Pattern: Eager Initialization of Everything

```java
// BAD: All objects created eagerly at startup, even if never used
public class AppConfig {
    private final InMemoryMetricRepository metricRepo = new InMemoryMetricRepository();
    private final InMemoryTraceRepository traceRepo = new InMemoryTraceRepository();
    private final MetricAggregator aggregator = new MetricAggregator();
    private final MetricService metricService = new MetricService(metricRepo, ...);
    private final TracingService tracingService = new TracingService(traceRepo, ...);
    // ... 20+ eager initializations
    // Problem: creating AppConfig creates EVERYTHING, even if only MetricService is needed
    // Problem: initialization order matters -- tracingService depends on traceRepo existing
}
```

### Clean Solution: Lazy Initialization per Field

```java
public class AppConfig {
    private InMemoryMetricRepository metricRepository;

    public InMemoryMetricRepository getMetricRepository() {
        if (metricRepository == null) {
            metricRepository = new InMemoryMetricRepository();
        }
        return metricRepository;
    }

    // Each getter lazily creates its object on first access
    // Dependencies are resolved by calling other getters (which also lazy-init)

    public MetricService getMetricService() {
        if (metricService == null) {
            metricService = new MetricService(
                getMetricRepository(),      // lazy: creates if null
                getTimeSeriesStore(),       // lazy: creates if null
                getMetricAggregator()       // lazy: creates if null
            );
        }
        return metricService;
    }
}
```

### Numbered Call Chain

```
1. new AppConfig()  -- all fields are null (no eager init)
2. config.getMetricService()
   2a. metricService == null -> need to create
   2b. getMetricRepository()
       -> metricRepository == null
       -> new InMemoryMetricRepository()
       -> cache in field
   2c. getTimeSeriesStore()
       -> timeSeriesStore == null
       -> new TimeSeriesStore()
       -> cache in field
   2d. getMetricAggregator()
       -> metricAggregator == null
       -> new MetricAggregator()
       -> cache in field
   2e. new MetricService(metricRepo, tsStore, aggregator)
   2f. Cache in metricService field
3. config.getMetricService() (second call)
   3a. metricService != null -> return cached instance
```

### ASCII Diagram

```
  AppConfig (all null at creation)
  +-----------------------------------------------+
  | metricRepository:     null -----> created on   |
  | traceRepository:      null       first access  |
  | logRepository:        null                     |
  | alertRepository:      null                     |
  | metricAggregator:     null                     |
  | traceAssembler:       null                     |
  | logProcessor:         null                     |
  | timeSeriesStore:      null                     |
  | samplingEngine:       null                     |
  | samplingStrategy:     null                     |
  | aggregationStrategy:  null                     |
  | alertingStrategy:     null                     |
  | metricService:        null                     |
  | tracingService:       null                     |
  | logService:           null                     |
  | alertService:         null                     |
  | dashboardService:     null                     |
  | serviceMapService:    null                     |
  | observabilityService: null                     |
  | controller:           null                     |
  | statsDisplay:         null                     |
  +-----------------------------------------------+

  After getController():
  +-----------------------------------------------+
  | metricRepository:     [InMemoryMetricRepo]     |
  | traceRepository:      [InMemoryTraceRepo]      |
  | logRepository:        [InMemoryLogRepo]        |
  | alertRepository:      [InMemoryAlertRepo]      |
  | metricAggregator:     [MetricAggregator]       |
  | ... all initialized via dependency chain ...   |
  | controller:           [ObservabilityController]|
  +-----------------------------------------------+

  Strategy swap (setSamplingStrategy):
  +-----------------------------------------------+
  | samplingStrategy:     [NEW strategy]           |
  | samplingEngine:       null  <-- invalidated    |
  | tracingService:       null  <-- invalidated    |
  | observabilityService: null  <-- invalidated    |
  | controller:           null  <-- invalidated    |
  | (others remain cached)                         |
  +-----------------------------------------------+
```

### Interview Soundbite

> "AppConfig uses Singleton-style lazy initialization -- each component is created on
> first access and cached. This solves two problems: initialization order (dependencies
> are resolved by calling getters that themselves lazy-init) and startup cost (only
> create what's actually used). Strategy setters cascade-invalidate dependents so
> a runtime strategy swap triggers re-creation of the minimal object subgraph."

---

## 13. Pattern Interaction Map

```
+-------------------+     delegates to      +-------------------+
| ObservabilityCtrl |-------------------->| ObservabilityService|
| (Controller)      |                      |    (FACADE)        |
+-------------------+                      +---------+---------+
                                                     |
                     +---------------+---------------+---------------+
                     |               |               |               |
                     v               v               v               v
              +-----------+   +-----------+   +-----------+   +-----------+
              |MetricSvc  |   |TracingSvc |   | LogSvc    |   |AlertSvc   |
              +-+-------+-+   +-+-------+-+   +-+-------+-+   +-+-------+-+
                |       |       |       |       |       |       |       |
                v       v       v       v       v       v       v       v
          [REPO]   [ENGINE] [REPO] [ENGINE] [REPO] [ENGINE] [REPO] [STRATEGY]
          MetricRepo TSStore TraceRepo Assembl LogRepo LogProc AlertRepo AlertStrat
            |         |         |       |       |       |       |       |
            v         v         v       v       v       v       v       v
          [PATTERN] [PATTERN] [PATTERN][PATTERN][PATTERN][PATTERN][PATTERN][PATTERN]
          Repo+DIP  TreeMap   Repo+DIP Builder  Repo+DIP Chain   Repo+DIP Strategy
                               +SamplingStrat            of Resp
                               (STRATEGY)                (BEHAVIORAL)
```

Pattern interaction summary:
- **AppConfig (Factory+Singleton)** creates all objects with **Builder** for entities.
- **ObservabilityService (Facade)** delegates to 6 services.
- Each service uses **Repository** interfaces for data access.
- **Strategy** appears 3 times: sampling, aggregation, alerting.
- **LogProcessor (Chain of Responsibility)** filters entries before repository storage.
- **Span Decorator** enriches spans progressively.
- **Observer** drives the AlertService evaluation loop.
- **Template Method** standardizes MetricAggregator computations.

---

## 14. Interview Quick-Reference Table

| #  | Pattern                    | GoF Category | Files                                    | One-Line Summary                                          |
|----|----------------------------|--------------|------------------------------------------|-----------------------------------------------------------|
| 1  | Strategy (Sampling)        | Behavioral   | SamplingStrategy + 3 impls               | Swap head/tail/rate-limited sampling at runtime            |
| 2  | Strategy (Aggregation)     | Behavioral   | AggregationStrategy + 2 impls            | Swap P99/rate aggregation for dashboards                   |
| 3  | Strategy (Alerting)        | Behavioral   | AlertingStrategy + 2 impls               | Swap threshold/anomaly detection for alert evaluation      |
| 4  | Builder                    | Creational   | Metric.Builder, Span.Builder, AlertRule.Builder | Flexible construction of complex immutable objects   |
| 5  | Factory / Composition Root | Creational   | AppConfig                                | Single wiring point with lazy init and cascade invalidation|
| 6  | Repository                 | Structural*  | 4 interfaces + 4 InMemory impls          | Abstract data access; swap InMemory for DB with zero service changes |
| 7  | Facade                     | Structural   | ObservabilityService                     | One entry point wrapping 6 sub-services                    |
| 8  | Observer                   | Behavioral   | AlertService.evaluateRules()             | Alert rules react to metric data changes                   |
| 9  | Decorator                  | Structural   | Span.addTag(), Span.addLog()             | Progressive enrichment as request flows through layers     |
| 10 | Chain of Responsibility    | Behavioral   | LogProcessor filter pipeline             | Composable, orderable filters; any can drop the entry      |
| 11 | Template Method            | Behavioral   | MetricAggregator                         | Consistent guard-compute-return skeleton for all aggregations|
| 12 | Singleton (Lazy Init)      | Creational   | AppConfig field-level lazy init          | Create on first access, cache, cascade-invalidate on swap  |

*Repository is from DDD/Enterprise patterns.

### Quick Interview Openers

**"Tell me about the design patterns in your observability platform."**

> "We use 10 GoF patterns. The three Strategy instances are the most important -- they
> let us swap sampling algorithms (head-based vs tail-based), aggregation functions
> (P99 vs rate), and alerting approaches (threshold vs anomaly detection) at runtime
> through AppConfig setters that cascade-invalidate dependent objects."

**"Why Builder over constructors?"**

> "Metric has 8 fields, Span has 11, AlertRule has 8 -- telescoping constructors are
> unreadable and error-prone. Builder makes required fields explicit in the constructor
> and optional fields self-documenting via fluent setters."

**"How do you handle cross-cutting concerns?"**

> "The Facade (ObservabilityService) provides a natural cross-cutting point. The Chain
> of Responsibility (LogProcessor) handles log filtering and enrichment. Span Decoration
> adds context progressively. Trace correlation (traceId/spanId in logs) links all three
> observability pillars."

---

## End of Design Patterns Document
