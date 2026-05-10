# Observability Platform (Datadog / Grafana / New Relic)

## Problem Summary

Design an **observability platform** (like Datadog, Grafana, or New Relic) that provides unified visibility into distributed systems through the **three pillars of observability**: metrics, traces, and logs. The core challenge is the **time-series storage engine** -- maintain a bucketed **TreeMap<epochSecond, List<MetricPoint>>** that enables O(log n) range queries via `TreeMap.subMap()` and supports **downsampling** (raw -> 1-min avg -> 5-min avg -> 1-hour avg) to compress historical data by 1000x while preserving trends. **Metric types** cover all observability needs: **counters** (monotonically increasing, e.g. request count), **gauges** (point-in-time, e.g. CPU usage), **histograms** (value distributions across buckets, e.g. response sizes), and **timers** (latency measurements with P50/P95/P99 percentiles via the nearest-rank method). **Distributed tracing** uses **span trees** where each Span carries a traceId, spanId, and parentSpanId; the **TraceAssembler** buffers spans arriving out-of-order from different services and assembles them into complete Trace trees when the root span (parentSpanId == null) is found. **Context propagation** via W3C TraceContext header (`traceparent: 00-{traceId}-{spanId}-01`) links spans across service boundaries. **Sampling strategies** balance coverage vs cost: **head-based** (deterministic hash of traceId at creation, consistent across all services, zero coordination), **tail-based** (collect ALL spans, decide after trace completion -- keep errors and slow traces, requires buffering), and **rate-limited** (AtomicInteger sliding-window counter, max N traces/second, thread-safe via CAS). **Structured logging** with **correlation IDs** (traceId + spanId injected into every log entry) is the "glue" between pillars -- jump from a log line to the full trace to related metrics. **Alerting** uses pluggable strategies: **threshold** (avg of recent points > static threshold, e.g. CPU > 80%) and **anomaly detection** (mean +/- k*stdDev, catches dynamic spikes without hardcoded values). **Service dependency mapping** builds a directed graph from trace data (who calls whom), enabling blast radius analysis and cascading failure detection. **High-cardinality metric management** is the #1 cost driver -- each unique label combination creates a new time series (10K users x 100 endpoints = 1M series); mitigate by dropping/bucketing high-card tags at ingestion. The system is **AP for metrics** (a 5-second-stale dashboard reading is acceptable; losing metric points during a partition is tolerable with backfill) and **CP for alerting state** (a missed critical alert or duplicate page is a correctness violation -- imagine not alerting on a production outage).

---

## 1-Minute Interview Revision

**Memorize these bullets. They cover 80% of what interviewers want to hear.**

- **Time-Series Storage: TreeMap<epochSecond, List<MetricPoint>> with O(log n) range queries via subMap(). Downsampling reduces storage 1000x: raw for 1h, 1-min avg for 24h, 5-min avg for 7d, 1-hour avg for 1y.** The TreeMap bucketed structure is the heart of the metrics pillar. Each metric name maps to a TreeMap where keys are epoch seconds and values are lists of data points recorded in that second. Range queries use `subMap(fromEpochSec, toEpochSec + 1)` which is O(log n) for seek + O(k) for iteration. Downsampling works by grouping points into larger time buckets and averaging: 60 raw points per minute become 1 averaged point. At ingestion rates of 1M points/sec, downsampling is the only way to keep storage costs under control. In production, use a columnar TSDB like InfluxDB (TSM engine), TimescaleDB (PostgreSQL hypertable), or ClickHouse (MergeTree) that compresses time-ordered data 10-20x better than row stores.

- **Three Pillars Correlated by TraceId: Metrics (counters, gauges, histograms, timers), Traces (span trees with parent-child relationships), Logs (structured entries with traceId/spanId).** The key insight is that all three pillars share correlation IDs. A log entry says "slow query: 250ms" -- click the traceId to see the full distributed trace -- see which span was slow -- correlate with the `http.request.duration_ms` metric to check if this is a trend or an outlier. Without correlation IDs, each pillar is an isolated silo. The three signals are: metrics tell you WHAT is wrong (error rate spike), traces tell you WHERE it is wrong (which service/span), logs tell you WHY it is wrong (the specific error message/stack trace). Implement correlation by injecting traceId and spanId into every LogEntry at creation time and tagging metric points with service name for cross-referencing.

- **Sampling Strategies: Head-based (hash traceId at creation, consistent, zero coordination), Tail-based (collect all, decide post-completion, keep errors/slow), Rate-limited (AtomicInteger sliding window, N/sec cap, thread-safe CAS).** Head-based is the default: `Math.abs(traceId.hashCode()) % 100 < sampleRate * 100`. Because the same traceId always hashes to the same bucket, every span in the trace gets the same sample/no-sample decision with zero cross-service coordination. But it randomly drops interesting traces (errors, slow requests). Tail-based fixes this by buffering ALL spans and deciding after the trace completes -- if any span has error=true or latency > threshold, keep the entire trace. The cost is memory: every span must be buffered until the trace completes (typically 30-60s timeout). Rate-limited provides a cost ceiling: `AtomicInteger` counter resets every epoch second via `compareAndSet`, caps at N traces/sec regardless of traffic volume. Production: combine all three as layers (head-based 10% baseline + tail-based for errors + rate-limit as safety valve).

- **Percentile Aggregation (P50/P95/P99): Nearest-rank method -- sort values ascending, index = ceil(P/100 * N) - 1. P99 > average for SLO monitoring because average hides tail latency.** Average latency of 50ms means nothing if 1% of users experience 5000ms. P99 = "the value below which 99% of observations fall." For a dashboard showing API latency: P50 = typical experience, P95 = degraded experience, P99 = worst-case experience (minus outliers). The implementation sorts all values in the window and picks the value at the percentile rank index. This is O(n log n) per query. In production, use streaming approximation algorithms: **t-digest** (merging centroids, 1% accuracy with O(1) memory per merge), **DDSketch** (logarithmic bucketing, guaranteed relative accuracy), or **HDR Histogram** (pre-allocated bucket array, zero allocation at runtime). Prometheus uses histograms with predefined bucket boundaries and estimates percentiles via linear interpolation between buckets.

- **Alerting Pipeline: Threshold (avg > static value) vs Anomaly Detection (mean +/- k*stdDev). Alert lifecycle: FIRING -> ACKNOWLEDGED -> RESOLVED. Avoid alert fatigue: WARNING for investigation, CRITICAL for pages, always define runbooks.** Threshold alerting is simple but brittle -- it requires manual tuning per metric and fails for metrics with natural variation (traffic varies 10x between peak and trough). Anomaly detection computes the statistical baseline dynamically: mean and standard deviation of the recent window, then flags any point deviating by more than k standard deviations (k=2 for 95% confidence, k=3 for 99.7%). The AlertService evaluates all rules every 60 seconds, queries the last 60s of metric data, and fires alerts via the pluggable AlertingStrategy. Alert state machine: FIRING (condition met) -> ACKNOWLEDGED (operator saw it) -> RESOLVED (condition cleared or manually resolved). In production, add de-duplication (same rule fires only once), notification routing (PagerDuty, Slack, email), escalation policies, and maintenance windows.

- **High-Cardinality Management: Each unique label combination = one time series. 10K users x 100 endpoints = 1M series. Control at ingestion, not query. Strategies: drop high-card tags, hash/bucket (user_id mod 100), cardinality limits, separate columnar store (ClickHouse).** High cardinality is the #1 operational challenge in observability. A metric `api.requests{user_id=<unique>}` with 10K unique users creates 10K time series. Multiply by 100 endpoints and 10 HTTP methods = 10M series, each consuming memory for the active head block and disk for historical data. Mitigation at ingestion: (1) Drop tags that are too unique (user_id, request_id) -- store them in logs instead. (2) Bucket: `user_id -> user_bucket = hash(userId) % 100` reduces 10K series to 100. (3) Cardinality limits: reject or sample metrics exceeding N unique label combinations per metric name. (4) Separate storage: route high-card metrics to ClickHouse (columnar, 10x better for high-cardinality scans) instead of Prometheus/InfluxDB (optimized for low-cardinality).

---

## Class Hierarchy

```
Metric (aggregated metric definition, Builder)    MetricPoint (single data point, immutable)
  |-- id (UUID)                                     |-- name (metric name)
  |-- name ("http.request.duration_ms")             |-- value (double)
  |-- metricType: COUNTER | GAUGE                   |-- timestamp (Instant)
  |             | HISTOGRAM | TIMER                  |-- metricType (enum)
  |-- description                                   |-- tags: Map<String,String>
  |-- unit ("ms", "bytes", "%")                     |-- of(name, value, type, tags) [factory]
  |-- tags: Map<String,String> (immutable)
  |-- dataPoints: List<MetricPoint>
  |-- createdAt (Instant)
  |-- getLatestValue() -> Optional<Double>
  |-- getPointsInRange(from, to) -> List<MetricPoint>

Span (single operation, Builder)                  Trace (complete distributed trace)
  |-- traceId (shared across all spans)             |-- traceId
  |-- spanId (UUID, unique per span)                |-- rootSpan: Span
  |-- parentSpanId (nullable; null = root)          |-- spans: List<Span>
  |-- operationName ("POST /api/orders")            |-- serviceName (entry-point service)
  |-- serviceName ("order-service")                 |-- startTime (Instant)
  |-- startTime (Instant)                           |-- duration (Duration)
  |-- endTime (Instant, set on finish())            |-- addSpan(span) -> auto-detect root
  |-- duration (computed: end - start)              |-- getSpansByService(service) -> List
  |-- status: OK | ERROR | TIMEOUT | CANCELLED      |-- getErrorSpans() -> List
  |-- tags: Map<String,String>                      |-- buildSpanTree() -> Map<parentId, List<Span>>
  |-- logs: List<SpanLog>
  |-- finish() -> stamp endTime, compute duration

TraceContext (propagation carrier)                LogEntry (structured log entry)
  |-- traceId                                       |-- id (UUID)
  |-- spanId                                        |-- timestamp (Instant)
  |-- parentSpanId                                  |-- level: TRACE(0) | DEBUG(1) | INFO(2)
  |-- sampled: boolean                              |        | WARN(3) | ERROR(4) | FATAL(5)
  |-- baggage: Map<String,String>                   |-- message
  |-- newTrace() -> static factory                  |-- serviceName
  |-- createChild(newSpanId) -> child context       |-- traceId (nullable, correlation)
  |-- setBaggage(key, value)                        |-- spanId (nullable, correlation)
                                                    |-- attributes: Map<String,String>

AlertRule (declarative rule, Builder)             Alert (fired alert instance)
  |-- id (UUID)                                     |-- id (UUID)
  |-- name ("High CPU Alert")                       |-- rule: AlertRule (originating rule)
  |-- metricName ("system.cpu.usage_percent")       |-- status: FIRING | ACKNOWLEDGED | RESOLVED
  |-- condition ("> " or "< ")                      |-- triggeredAt (Instant)
  |-- threshold (80.0)                              |-- resolvedAt (Instant, nullable)
  |-- durationSeconds (60)                          |-- currentValue (double)
  |-- severity: WARNING | CRITICAL                  |-- message
  |-- enabled: boolean                              |-- resolve() -> set RESOLVED + stamp time
                                                    |-- acknowledge() -> set ACKNOWLEDGED

ServiceNode (dependency graph vertex)             SpanLog (event attached to a Span)
  |-- serviceName                                   |-- timestamp (Instant)
  |-- dependencies: Set<String> (downstream)        |-- event (String)
  |-- dependents: Set<String> (upstream)            |-- fields: Map<String,String>
  |-- requestCount, errorRate, avgLatencyMs
  |-- addDependency(downstream)
  |-- addDependent(upstream)
  |-- updateStats(requests, errorRate, avgLatency)

TimeSeriesStore (bucketed TSDB engine)            MetricAggregator (statistical engine)
  |-- store: Map<metricName,                        |-- sum(points) -> double
  |    TreeMap<epochSecond, List<MetricPoint>>>      |-- average(points) -> double
  |-- store(point) -> bucket by epochSecond         |-- min(points) / max(points) -> double
  |-- query(metric, from, to) -> subMap range       |-- percentile(points, p) -> nearest-rank
  |-- queryLatest(metric, count) -> descending      |-- rate(points) -> delta / time
  |-- downsample(metric, bucketSize) -> avg per     |-- count(points) -> long
  |    bucket, returns List<MetricPoint>            |-- histogram(points, boundaries) -> Map

TraceAssembler (span collector + assembler)       SamplingEngine (strategy delegate)
  |-- pendingSpans: Map<traceId, List<Span>>        |-- defaultStrategy: SamplingStrategy
  |-- addSpan(span) -> buffer                       |-- shouldSample(context) -> delegate
  |-- assembleTrace(traceId) -> find root,          |-- shouldSample(context, opName) -> delegate
  |    build Trace, remove from pending             |-- setStrategy(strategy) -> runtime swap
  |-- getTraceIds() -> Set<String>
  |-- getPendingSpanCount(traceId) -> int

LogProcessor (filter + enrichment pipeline)       ObservabilityController (REST facade)
  |-- minLevel: LogLevel (default INFO)              |-- POST /metrics -> ingestMetric
  |-- filters: List<Predicate<LogEntry>>            |-- POST /traces -> startTrace
  |-- setMinLevel(level)                            |-- PUT /traces/spans/{id}/finish
  |-- addFilter(predicate)                          |-- POST /logs -> ingestLog
  |-- process(entry) -> Optional<LogEntry>          |-- POST /alerts/evaluate
  |-- processBatch(entries) -> List<LogEntry>        |-- GET /alerts
  |-- enrichWithCorrelation(entry, traceId, spanId) |-- GET /dashboard

SamplingStrategy (Strategy interface)             AlertingStrategy (Strategy interface)
  |-- HeadBasedSamplingStrategy                     |-- ThresholdAlertingStrategy
  |     hash(traceId) % 100 < rate * 100            |     avg(recentPoints) > threshold
  |-- TailBasedSamplingStrategy                     |-- AnomalyDetectionAlertingStrategy
  |     collect all, filter errors/slow              |     |latest - mean| > k * stdDev
  |-- RateLimitedSamplingStrategy                   |
  |     AtomicInteger counter, N/sec cap            |

AggregationStrategy (Strategy interface)          ObservabilityService (Facade Pattern)
  |-- PercentileAggregationStrategy                  |-- metricService, tracingService
  |     sort, nearest-rank at ceil(P/100 * N) - 1   |-- logService, alertService
  |-- RateAggregationStrategy                       |-- dashboardService, serviceMapService
  |     (last - first) / timeDeltaSeconds            |-- recordMetric, startTrace, finishSpan
                                                    |-- log, logWithTrace, evaluateAlerts

AppConfig (Composition Root / Factory / Singleton)
  |-- creates repositories (4 InMemory impls: Metric, Trace, Log, Alert)
  |-- creates engines (TimeSeriesStore, MetricAggregator, TraceAssembler, LogProcessor, SamplingEngine)
  |-- creates strategies (SamplingStrategy, AggregationStrategy, AlertingStrategy) -- swappable
  |-- creates services (Metric, Tracing, Log, Alert, Dashboard, ServiceMap, Observability)
  |-- creates controller + display
  |-- setSamplingStrategy() / setAggregationStrategy() / setAlertingStrategy() -> swap and re-wire
```

---

## Key Components

| Component | Role |
|-----------|------|
| `Metric` | Core domain entity for metric definitions. Created via Builder pattern. Tracks name, type (COUNTER/GAUGE/HISTOGRAM/TIMER), unit, tags, and collected data points. `getLatestValue()` returns the most recent reading. `getPointsInRange()` enables time-windowed queries. |
| `MetricPoint` | Immutable data point snapshot -- name, value, timestamp, type, and tags. Created via `MetricPoint.of()` factory with automatic `Instant.now()` timestamping. The atomic unit of time-series storage. |
| `Span` | Single operation in a distributed trace. Created via Builder pattern. Carries traceId (shared across trace), spanId (unique), parentSpanId (null for root spans). `finish()` stamps endTime and computes duration. Supports tags and SpanLog events for contextual enrichment. |
| `Trace` | Complete distributed trace -- a directed tree of Spans. Auto-detects the root span (parentSpanId == null) on `addSpan()`. `buildSpanTree()` returns parent-to-children adjacency map for visualization. `getErrorSpans()` finds all ERROR/TIMEOUT spans for debugging. |
| `TraceContext` | Carries trace identity across service boundaries for W3C-style context propagation. `newTrace()` factory creates fresh context. `createChild(newSpanId)` propagates traceId to child spans. Baggage map carries arbitrary key-value metadata across services. |
| `LogEntry` | Structured log entry with severity level (TRACE through FATAL), service name, message, and optional traceId/spanId correlation. The correlation IDs are the "glue" -- linking logs to traces to metrics for cross-signal investigation. |
| `TimeSeriesStore` | In-memory bucketed TSDB. `TreeMap<epochSecond, List<MetricPoint>>` per metric name. `query()` uses `TreeMap.subMap()` for O(log n) range seeks. `downsample()` averages points within configurable time buckets (5-min, 1-hour) to compress historical data. `ConcurrentHashMap` top-level + `synchronizedList` buckets for thread safety. |
| `MetricAggregator` | Statistical engine computing sum, average, min, max, percentile (nearest-rank), rate (delta/time), count, and histogram (bucket distribution). The percentile implementation sorts values and picks `ceil(P/100 * N) - 1`. Rate computes `(lastValue - firstValue) / timeDeltaSeconds` for counter-type metrics. |
| `TraceAssembler` | Collects spans arriving out-of-order from distributed services. Buffers in `ConcurrentHashMap<traceId, List<Span>>`. `assembleTrace()` finds the root span, builds the Trace object, and removes assembled spans from the buffer. Handles partial traces gracefully (returns Optional.empty if no root found). |
| `SamplingEngine` | Strategy delegate for trace sampling decisions. Wraps a `SamplingStrategy` and delegates `shouldSample()` calls. Supports runtime strategy swapping via `setStrategy()` for adaptive sampling. |
| `LogProcessor` | Filter and enrichment pipeline for log entries. Chain of Responsibility: applies minimum-level gate first, then all custom `Predicate<LogEntry>` filters. `enrichWithCorrelation()` injects traceId/spanId for cross-signal linking. Only entries surviving all filters are persisted. |
| `AlertRule` | Declarative alert rule (Builder pattern) specifying metric name, condition (> or <), threshold, duration (how long condition must hold), and severity (WARNING/CRITICAL). Immutable after construction. The "what to monitor" definition. |
| `Alert` | Fired alert instance linked to its originating AlertRule. State machine: FIRING -> ACKNOWLEDGED -> RESOLVED. `resolve()` stamps resolution time. `acknowledge()` records operator visibility. The "something is wrong right now" event. |
| `AlertService` | Alert rule evaluation engine. On `evaluateRules()`: iterates enabled rules, queries last 60s of metric data, delegates to AlertingStrategy, and creates/resolves alerts based on state transitions. Tracks firing rules via `Map<ruleId, alertId>` to detect state changes. |
| `ServiceMapService` | Builds service dependency graph from observed calls. Maintains `ServiceNode` vertices and edge statistics (request count, error rate, avg latency). `getTopology()` returns adjacency list. `printServiceMap()` renders ASCII tree from root services (no dependents) with circular reference detection. |
| `DashboardService` | Aggregates all three pillars into unified views. `getMetricSummary()` computes avg/min/max/p50/p95/p99/count/rate. `getTraceSummary()` computes trace count, avg duration, error rate. `getSystemOverview()` builds a formatted multi-line summary. Implements RED method (Rate, Errors, Duration) and USE method (Utilization, Saturation, Errors). |
| `ObservabilityService` | **Facade Pattern (GoF)** -- single entry point wrapping MetricService, TracingService, LogService, AlertService, DashboardService, and ServiceMapService. Callers interact with one class instead of six. Hides internal wiring and delegation complexity. |
| `AppConfig` | **Factory Pattern + Composition Root + Singleton** -- lazily creates and wires all 20+ objects (repositories, engines, strategies, services, controller, display). Strategy setters (`setSamplingStrategy()`, etc.) invalidate dependent objects for re-creation on next access. No DI framework needed. |

---

## Key Tradeoffs

| Decision | Option A | Option B | This Design |
|----------|----------|----------|-------------|
| Time-series storage engine | Relational DB (PostgreSQL with time-range indexes) | In-memory TreeMap bucketed by epoch second | **In-memory TreeMap** -- `TreeMap.subMap()` gives O(log n) range seek + O(k) iteration, orders of magnitude faster than SQL `SELECT WHERE timestamp BETWEEN`. At 1M points/sec, database round-trips add 1-5ms per query and create write amplification. TreeMap buckets by epoch second give natural time partitioning. Downsampling compresses 1000x. In production, use a purpose-built TSDB: InfluxDB (TSM engine with WAL + compaction), TimescaleDB (PostgreSQL hypertables with chunk-level compression), or VictoriaMetrics (merge-tree with deduplication). |
| Metric aggregation approach | Pre-computed aggregates at write time (rollup on ingest) | On-the-fly aggregation at query time (sort + compute) | **On-the-fly aggregation** -- pre-computation requires knowing all query patterns upfront and doubles write amplification (store raw + all rollups). On-the-fly via MetricAggregator (sort, percentile, rate) is flexible: any percentile, any time window, any metric. Tradeoff: query latency scales with data volume. In production, hybrid: pre-compute common rollups (1-min, 5-min, 1-hour averages) at write time, fall back to on-the-fly for ad-hoc queries. Prometheus uses this hybrid approach with recording rules. |
| Trace sampling strategy | Head-based only (simple, consistent, deterministic) | Tail-based only (keeps interesting traces, loses nothing important) | **All three via Strategy pattern** -- head-based (50% baseline), tail-based (keep errors + slow), rate-limited (cost ceiling). Head-based alone randomly drops 50% of error traces -- unacceptable for debugging production issues. Tail-based alone requires buffering ALL spans in memory for 30-60s -- at 100K spans/sec, that is 6M spans in RAM. Rate-limited alone caps throughput but has no intelligence about what to keep. Layered: head-based reduces volume 10x, tail-based ensures error/slow traces survive, rate-limited prevents cost explosion during traffic spikes. |
| Alerting detection method | Static thresholds only (simple, predictable, requires tuning) | Anomaly detection only (adaptive, no manual thresholds) | **Both via Strategy pattern** -- static thresholds for well-understood metrics (CPU > 80%, disk < 10%), anomaly detection for dynamic metrics (request latency varies with traffic patterns). Static thresholds cause alert fatigue when natural variation triggers false positives. Anomaly detection alone produces false positives during expected changes (deployments, traffic shifts). Best practice: use thresholds for infrastructure metrics (binary yes/no) and anomaly detection for application metrics (relative to baseline). Swap strategies at runtime via `setAlertingStrategy()`. |
| Log storage model | Full-text search index (Elasticsearch/OpenSearch) | Structured fields with trace correlation (in-memory repository) | **Structured fields with correlation** -- full-text search is overkill for correlated debugging. The key operation is "give me all logs for traceId=X" (index on traceId, O(1) lookup) not "search for 'timeout' across all logs" (full-text, inverted index). In production, use Elasticsearch/Loki: Elasticsearch for full-text search + structured queries, Grafana Loki for label-indexed streams (cheaper, no full-text index, grep-like queries). Always inject traceId into every log entry at the SDK level. |
| Service dependency graph | Static configuration (YAML/JSON declaring dependencies) | Dynamic discovery from trace data (runtime topology) | **Dynamic from trace data** -- static config goes stale within days as services evolve. The ServiceMapService builds the graph automatically from `registerCall()` events extracted from trace spans. Every trace span implicitly carries "service A called service B" information. Dynamic gives real topology, edge statistics (error rate, latency), and detects unexpected dependencies. Tradeoff: requires sufficient trace volume for accuracy. In production, combine with static config for known dependencies + dynamic discovery for validation. |
| High-cardinality handling | Allow unlimited label combinations (simple, expensive) | Drop or bucket high-cardinality tags at ingestion | **Drop/bucket at ingestion** -- allowing unlimited cardinality is a ticking cost bomb. A single metric with `user_id` label at 1M unique users creates 1M time series, each consuming ~3KB active memory (Prometheus head block). That is 3GB for one metric name. Mitigation: tag cardinality limits (reject metrics exceeding N unique combos per name), hash bucketing (`user_id -> bucket = hash % 100`), or routing to ClickHouse (columnar store handles high-cardinality scans 100x better than Prometheus). Always enforce at ingestion -- by the time high-cardinality data reaches storage, the damage is done. |

---

## Design Patterns

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** | `SamplingStrategy`: HeadBased vs TailBased vs RateLimited | Swap trace sampling algorithm at runtime. Head-based for baseline, tail-based for error retention, rate-limited for cost control. All three implement `shouldSample(TraceContext)`. |
| **Strategy** | `AggregationStrategy`: Percentile vs Rate | Swap metric aggregation function. Percentile for latency SLOs (P50/P95/P99), Rate for throughput (requests/sec from counter deltas). Both implement `aggregate(List<MetricPoint>)`. |
| **Strategy** | `AlertingStrategy`: Threshold vs AnomalyDetection | Swap alert evaluation logic. Threshold for static limits (CPU > 80%), anomaly detection for dynamic baselines (k*stdDev). Both implement `shouldAlert(AlertRule, List<MetricPoint>)`. |
| **Builder** | `Metric.Builder`, `Span.Builder`, `AlertRule.Builder` | Complex objects with 8-14 fields. Builder avoids telescoping constructors, enforces required fields via constructor (name + type), defaults optional ones (tags = empty, severity = WARNING). Fluent API for readability. |
| **Factory** | `AppConfig` as Composition Root | Lazily creates and wires all 20+ dependencies. No DI framework needed. Strategy setters invalidate dependent objects for automatic re-creation. Single entry point for demo and tests. |
| **Repository** | `MetricRepository`, `TraceRepository`, `LogRepository`, `AlertRepository` (4 interfaces + 4 InMemory impls) | Abstract data access behind interfaces. Swap InMemory for Elasticsearch/InfluxDB/PostgreSQL without touching service logic. Each interface has findById, findAll, save, plus domain-specific queries (findByName, findByServiceName). |
| **Facade** | `ObservabilityService` orchestrates 6 sub-services | Single unified API. Controllers/demos/tests interact with one class instead of MetricService + TracingService + LogService + AlertService + DashboardService + ServiceMapService. Hides delegation wiring. |
| **Observer** | Alert triggers on metric threshold crossings | AlertService observes metric values. On `evaluateRules()`, queries recent metric data and fires alerts when conditions are met. Decouples metric ingestion from alert evaluation -- metrics flow continuously, alerts evaluate periodically. |
| **Decorator** | Span enrichment with tags and SpanLogs | Spans start minimal (traceId, operationName, serviceName). `addTag()` and `addLog()` decorate with contextual metadata without changing the Span interface. Each call adds information without modifying the core structure. |
| **Chain of Responsibility** | `LogProcessor` filter pipeline | Log entries pass through a chain: minimum-level gate -> custom predicates (List<Predicate<LogEntry>>). Each filter can accept or reject. Entry must pass ALL filters to survive. New filters added via `addFilter()` without modifying existing ones. |
| **Template Method** | `MetricAggregator` statistical functions | Base aggregation flow: query points -> compute statistic -> return value. Each method (sum, average, percentile, rate, histogram) follows the same template but applies different mathematical operations. Subclasses (strategies) override the aggregation logic. |
| **Singleton** | `AppConfig` lazy initialization | Each getter creates the instance once and caches it. Subsequent calls return the cached instance. Ensures single instance of each service/repository. Thread-unsafe by design (single-threaded demo). In production, use DI container (Spring) for lifecycle management. |

---

## Real-World Use Cases & Industry Applications

### 1. Microservices Debugging: "Why Is Checkout Slow?" (Uber, Netflix, Shopify)
**Problem:** Users report slow checkout. Average latency looks fine at 150ms, but P99 is 8 seconds. Which of the 15 services in the checkout path is the bottleneck?
**How this system solves it:** Distributed tracing shows the full span tree: API Gateway (2ms) → Cart Service (5ms) → Inventory Service (3ms) → Payment Service (7800ms!) → Order Service (10ms). The payment span reveals a slow downstream call to the fraud detection API. Correlated logs on the payment span show: "Fraud model timeout, retrying..." — the ML model is overloaded.
**Production examples:** Uber uses Jaeger to trace rides across 4000+ microservices. Shopify traces checkout requests across 100+ services to maintain P99 < 500ms SLA. Netflix uses distributed tracing to debug streaming latency across CDN, API, and recommendation services.

### 2. Incident Response: "The Site Is Down" (PagerDuty, Datadog, Grafana)
**Problem:** 3 AM page: "Error rate > 5% on API Gateway." The on-call engineer needs to identify root cause within the 15-minute SLA.
**How this system solves it:** Alert fires (threshold: error_rate > 5%) → Engineer opens dashboard → Metrics show error spike started 10 minutes ago → Service dependency map highlights Payment Service as the source → Click into Payment Service traces → See timeout errors calling Stripe API → Correlated logs show "TLS handshake timeout" → Root cause: Stripe's edge server in us-east-1 is degraded. Mitigation: failover to eu-west-1 endpoint.
**Production examples:** Datadog's APM + logs + metrics correlation reduces MTTR by 60% (their published case studies). PagerDuty integrates with observability platforms for automated incident triage. LinkedIn uses their observability stack to maintain 99.99% availability.

### 3. SLO Monitoring & Error Budgets (Google SRE, Honeycomb, Chronosphere)
**Problem:** The platform team committed to 99.95% availability SLO for the Order API (allows 21.9 minutes downtime/month). How do you measure and alert before the budget burns?
**How this system solves it:** Metrics track: request_count (counter) and error_count (counter) per service. SLI = 1 - (error_count / request_count). Dashboard shows rolling 30-day SLO burn rate. Alert rule: "If burn rate exceeds 2x for 1 hour, page the team" (fast burn) or "If burn rate exceeds 1.2x for 6 hours, create a ticket" (slow burn). This catches both sudden spikes and gradual degradation.
**Production examples:** Google SRE pioneered error budget methodology (documented in the SRE book). Honeycomb provides SLO burn rate dashboards natively. Chronosphere (founded by ex-Uber engineers) built SLO monitoring for Uber's 4000-service architecture.

### 4. Cascading Failure Detection (Amazon, Twitter/X, Meta)
**Problem:** A single database connection pool exhaustion in the User Service causes timeouts in 8 downstream services, creating a cascading failure that affects 40% of traffic.
**How this system solves it:** Service dependency map shows the blast radius: User Service is called by Auth, Profile, Feed, Search, Notification, Recommendation, Ad Serving, and Analytics. Anomaly detection alerts fire on all 8 services simultaneously. The observability platform's service map view immediately identifies User Service as the common root (the one service all failing services depend on). Metrics on the User Service show connections.active gauge stuck at max (pool exhaustion).
**Production examples:** Amazon's 2017 S3 outage cascaded to dozens of AWS services — observability would have identified S3 as the common dependency. Twitter's fail whale was often caused by cascading failures in their service mesh.

### 5. Performance Regression Detection in CI/CD (GitHub, Datadog, Lightstep)
**Problem:** A new deployment increases P99 latency from 200ms to 800ms on the Search API. The team needs to detect this before it reaches 100% of traffic (canary deployment).
**How this system solves it:** Metrics show latency increase correlated with the deploy timestamp. Canary deployment routes 5% of traffic to the new version — metrics compare canary vs baseline P99 in real-time. Anomaly detection (stddev-based) catches the regression automatically without hardcoded thresholds. If canary P99 > baseline P99 + 2σ, auto-rollback.
**Production examples:** GitHub uses observability to gate deployments (progressive delivery). Netflix's Kayenta performs automated canary analysis using statistical comparison of metrics. Datadog's APM deployment tracking correlates performance changes with specific git commits.

### 6. High-Cardinality Analytics: "Per-Customer API Usage" (Stripe, Twilio, AWS)
**Problem:** API platform needs to track per-customer API usage for billing, rate limiting, and abuse detection. 100K customers × 200 endpoints = 20M unique time series.
**How this system solves it:** High-cardinality management: route per-customer metrics to ClickHouse (columnar, optimized for high-cardinality scans) instead of Prometheus. Bucket customer_id into 1000 cohorts for real-time dashboards, keep full cardinality in the analytical store for billing queries. Rate-limited sampling on per-customer traces (1 trace/customer/minute) controls tracing cost.
**Production examples:** Stripe tracks per-merchant API usage across billions of requests for billing and abuse detection. AWS CloudWatch handles high-cardinality metrics for millions of AWS customers. Twilio tracks per-account message delivery metrics for SLA reporting.

---

## How to Run

```bash
cd /Users/kanojik/Documents/Karan/Autodesk_AI_Development/systemDesign
./gradlew :18-observability-platform:run
```

---

## Demo Output Preview

```
======================================================================
   OBSERVABILITY PLATFORM -- System Design Demo
   Staff Engineer Interview Prep: Metrics, Tracing, Logs
======================================================================

======================================================================
  DEMO 1: Metric Collection (Counter, Gauge, Histogram, Timer)
======================================================================
[METRIC] Recorded COUNTER 'http.requests.total' = 1.0
[METRIC] Recorded COUNTER 'http.requests.total' = 1.0
[METRIC] Recorded COUNTER 'http.requests.total' = 1.0
[METRIC] Recorded GAUGE 'connections.active' = 42.0
[METRIC] Recorded GAUGE 'connections.active' = 38.0
[METRIC] Recorded HISTOGRAM 'http.response.size_bytes' = 1024.0
[METRIC] Recorded HISTOGRAM 'http.response.size_bytes' = 2048.0
[METRIC] Recorded HISTOGRAM 'http.response.size_bytes' = 512.0
[METRIC] Recorded HISTOGRAM 'http.response.size_bytes' = 4096.0
[METRIC] Recorded TIMER 'http.request.duration_ms' = 45.2
[METRIC] Recorded TIMER 'http.request.duration_ms' = 120.5
[METRIC] Recorded TIMER 'http.request.duration_ms' = 23.1
[METRIC] Recorded TIMER 'http.request.duration_ms' = 89.7

[DEMO] Metric types collected:
  COUNTER  -> http.requests.total (3 increments)
  GAUGE    -> connections.active (2 readings)
  HISTOGRAM-> http.response.size_bytes (4 observations)
  TIMER    -> http.request.duration_ms (4 measurements)

  KEY INSIGHT: Four metric types cover all observability needs.
  Counters only go up, Gauges go up/down, Histograms track
  distributions, Timers are histograms specialized for latency.

======================================================================
  DEMO 2: Distributed Tracing (Spans & Context Propagation)
======================================================================
[TRACING] Started trace abc123... | operation='POST /api/orders' | service='api-gateway'
[TRACING] Started span def456... | parent=root... | operation='createOrder' | service='order-service'
[TRACING] Started span ghi789... | parent=def... | operation='processPayment' | service='payment-service'
[TRACING] Finished span ghi789... | trace abc... pending assembly
[TRACING] Started span jkl012... | parent=def... | operation='INSERT orders' | service='postgres'
[TRACING] Finished span jkl012... | trace abc... pending assembly
[TRACING] Finished span def456... | trace abc... pending assembly
[TRACING] Assembled and saved trace abc123... | spans=4

[DEMO] Trace tree:
  POST /api/orders (api-gateway)
  +-- createOrder (order-service)
      |-- processPayment (payment-service)
      +-- INSERT orders (postgres)

  KEY INSIGHT: Traces are trees of spans. Context propagation
  (traceId + parentSpanId) links spans across service boundaries.
  W3C TraceContext header: traceparent: 00-{traceId}-{spanId}-01

======================================================================
  DEMO 5: Metric Aggregation (Sum, Avg, Percentile)
======================================================================
[DEMO] Latency aggregations (4 points):
  Average : 69.63 ms
  Min     : 23.10 ms
  Max     : 120.50 ms
  P50     : 45.20 ms
  P95     : 120.50 ms
  P99     : 120.50 ms

  KEY INSIGHT: P99 latency is more important than average for SLOs.
  Average hides tail latency. P99 = 'the slowest 1% of requests'.
  In production, use t-digest or DDSketch for streaming percentiles.

======================================================================
  DEMO 6: Sampling Strategies (Head, Tail, Rate-Limited)
======================================================================
[DEMO] Head-based sampling (50% rate):
  100 traces -> ~50 sampled (~50 expected)

[DEMO] Tail-based sampling (error=true, latency>100ms):
  Error trace sampled:  true
  Normal trace sampled: false

[DEMO] Rate-limited sampling (5/second):
  20 traces -> 5 sampled (max 5 expected)

  KEY INSIGHT: Head-based is simple but loses interesting traces.
  Tail-based keeps errors/slow traces but requires buffering ALL
  spans initially. Rate-limited provides predictable cost control.

======================================================================
  DEMO 7: Threshold Alerting
======================================================================
[ALERT] Created rule 'High CPU Alert' for metric 'system.cpu.usage_percent'
[ALERT] Created rule 'High Latency Alert' for metric 'http.request.duration_ms'
[ALERT] FIRING -- rule 'High CPU Alert' | metric='system.cpu.usage_percent' | value=94.0

[DEMO] Firing alerts: 1
  High CPU Alert (value=94.0)

  KEY INSIGHT: Alert rules define: metric name, condition (> or <),
  threshold, duration (how long condition must hold), and severity.

======================================================================
  DEMO 9: Service Dependency Map
======================================================================
[SERVICE_MAP] api-gateway -> user-service | latency=25ms | success=true
[SERVICE_MAP] api-gateway -> order-service | latency=45ms | success=true
[SERVICE_MAP] order-service -> payment-service | latency=80ms | success=true
[SERVICE_MAP] payment-service -> stripe-api | latency=200ms | success=true

[DEMO] Service topology:
  api-gateway -> user-service -> postgres, redis
             -> order-service -> payment-service -> stripe-api, redis
                              -> inventory-service
                              -> postgres

  KEY INSIGHT: Service maps are built from trace data (who calls whom).
  Critical for: blast radius analysis, dependency cycle detection,
  and understanding cascading failure paths.

======================================================================
  DEMO 11: High-Cardinality Metric Handling
======================================================================
[DEMO] High-cardinality metric: api.requests.by_user
  50 data points with unique user_id tags
  Points stored: 50

[DEMO] High-cardinality mitigation strategies:
  1. Drop high-cardinality tags at ingestion (user_id -> drop)
  2. Hash/bucket: user_id -> user_bucket (mod 100)
  3. Separate storage: high-card metrics -> columnar store (ClickHouse)
  4. Adaptive sampling: sample 1% of unique label combinations
  5. Cardinality limits: reject metrics exceeding N unique label combos

  KEY INSIGHT: High cardinality is the #1 cost driver in observability.
  Each unique label combination = a new time series. 10K users x
  100 endpoints = 1M time series. Control at ingestion, not query.

======================================================================
  DESIGN SUMMARY -- Observability Platform
======================================================================

  Three Pillars of Observability:
    * Metrics -- counters, gauges, histograms, timers
    * Traces  -- distributed tracing with span trees
    * Logs    -- structured logging with correlation IDs

  Core Data Structures:
    * TreeMap<epochSec, List<MetricPoint>> -- time-series storage
    * Span tree (parent->children) -- trace assembly
    * Adjacency list -- service dependency graph
    * Sorted array -- streaming percentile computation

  Key Algorithms:
    * Percentile (P50/P95/P99) -- nearest-rank method
    * Anomaly detection -- mean +/- k*stddev
    * Head-based sampling -- deterministic hash of traceId
    * Rate-limited sampling -- token bucket per second
    * Downsampling -- average within time buckets

  Design Patterns (GoF):
    * Strategy -- sampling, aggregation, alerting algorithms
    * Builder -- Metric, Span, AlertRule construction
    * Factory -- AppConfig as composition root
    * Repository -- data access abstraction (4 repos)
    * Facade -- ObservabilityService orchestrates all services
    * Observer -- alert triggers on metric thresholds
    * Decorator -- span enrichment with tags/logs
    * Chain of Responsibility -- log processing pipeline
    * Template Method -- metric aggregation
    * Singleton -- AppConfig lazy initialization
```
