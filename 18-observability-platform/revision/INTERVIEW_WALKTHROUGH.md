# Observability Platform -- Staff Engineer Interview Walkthrough

> **Target role:** Staff Engineer | **Time budget:** 35 minutes
> **Comparable systems:** Datadog, Grafana/Prometheus, New Relic, Honeycomb, Jaeger
> **Codebase reference:** `com.systemdesign.observability` (Project 18)

---

## TABLE OF CONTENTS

```
Phase 1 : Clarify Requirements .............. 2-3 min  (lines   32-180)
Phase 2 : High-Level Architecture ........... 5-7 min  (lines  182-394)
Phase 3 : Deep Dive -- Time-Series Storage .. 8-10 min (lines  396-758)
Phase 4 : Deep Dive -- Distributed Tracing .. 5-7 min  (lines  760-1077)
Phase 5 : Alerting Pipeline ................. 3-5 min  (lines 1079-1348)
Phase 6 : Scaling & Tradeoffs ............... 3-5 min  (lines 1350-1615)
Phase 7 : Edge Cases ........................ 2-3 min  (lines 1617-1896)
Appendix A-0 : Service Dependency Mapping ... (lines 1898-1964)
Appendix A : Design Patterns Cheat Sheet .... (lines 1966-2017)
Appendix B : Complexity Cheat Sheet ......... (lines 2019-2066)
Appendix C : Quick-Fire Q&A Bank ............ (lines 2068-2145)
Appendix D : RED/USE Dashboard Reference .... (lines 2147-2209)
Appendix E : End-to-End Trace Walkthrough ... (lines 2211-2272)
Appendix F : Comparison with Real Systems ... (lines 2274-2338)
Appendix G : Whiteboard Drawing Order ....... (lines 2340-2393)
Appendix H : Anti-Patterns to Avoid ......... (lines 2395-2459)
Appendix I : Interview Timing Cheat Sheet ... (lines 2461-2496)
```

---
---

## PHASE 1: CLARIFY REQUIREMENTS (2-3 min)

### What this phase signals to the interviewer

```
STAFF SIGNAL: You drive ambiguity instead of waiting for answers.
You ask targeted questions that reveal hidden constraints,
then confirm scope before drawing a single box.
Juniors jump to drawing; Staff engineers anchor first.
```

### Questions to ask the interviewer (pick 6-8)

Ask these in a natural conversational order. Do not read them like a
checklist. Group them into three buckets: scale, pillars, and
constraints.

#### Bucket 1 -- Scale & Ingestion Rate

```
Q1: "What's the expected metric ingestion rate -- are we talking
     10K metrics/sec or 100K+ metrics/sec?"
     WHY: Determines whether a single time-series DB suffices or
          we need partitioned ingestion with write-ahead buffering.

Q2: "How many spans per second for distributed tracing -- 1K or 10K+?"
     WHY: 1K spans/sec = single collector node.
          10K+ = need a collector fleet with load balancing.
          100K+ = need aggressive sampling before storage.

Q3: "What's the expected log volume -- GB/day or TB/day?"
     WHY: GB/day = single Elasticsearch cluster.
          TB/day = tiered storage (hot/warm/cold) with index
          lifecycle management.

Q4: "How many services are we observing -- tens or thousands?"
     WHY: Tens = flat service map. Thousands = need hierarchical
          grouping, high-cardinality tag management, and service
          dependency graph pruning.
```

#### Bucket 2 -- Three Pillars & Correlation

```
Q5: "Do we need all three pillars -- metrics, traces, and logs --
     or is the focus on one or two?"
     WHY: Shows you know the three-pillar model. Full correlation
          across all three is the hardest problem.

Q6: "Should metrics, traces, and logs be correlated via a shared
     context (traceId, spanId) for unified querying?"
     WHY: Correlation is what separates Datadog-level platforms
          from standalone Prometheus + Jaeger + ELK deployments.
          It drives schema design and ingestion pipeline choices.

Q7: "What retention periods do we need? 7 days hot, 30 days warm,
     1 year cold? Or something different?"
     WHY: Retention directly shapes the storage tier architecture.
          Short retention = aggressive downsampling.
          Long retention = columnar cold storage (Parquet/ORC).
```

#### Bucket 3 -- Alerting & SLOs

```
Q8: "What's the alerting latency SLO -- should alerts fire within
     30 seconds of a threshold breach, or is 5 minutes acceptable?"
     WHY: 30s = streaming evaluation on the ingestion pipeline.
          5min = batch evaluation with periodic rule sweeps.
          This is a major architectural fork.

Q9: "Do we need anomaly detection (statistical, ML-based) or just
     static threshold alerting?"
     WHY: Static thresholds = simple rule engine.
          Anomaly detection = running statistics (stddev, percentiles)
          over sliding windows, which adds compute cost.

Q10: "Should the platform support custom dashboards and ad-hoc
      queries, or are pre-built RED/USE dashboards sufficient?"
      WHY: Custom dashboards = need a flexible query language
           (PromQL-like). Pre-built = can optimize storage for
           known query patterns.
```

### Follow-up about cardinality

```
"One more thing -- are there any known high-cardinality dimensions
like user_id, request_id, or container_id that services emit as
metric tags?"

WHY YOU ASK THIS:
  - High cardinality (>100K unique values) in metric tags causes
    a combinatorial explosion in time-series count.
  - A metric with 5 tags, each with 100 values = 100^5 = 10B series.
  - This is THE failure mode for time-series databases at scale.
  - Staff engineers name this risk proactively.
```

### Clarified scope (write on whiteboard/doc)

After hearing answers, summarize aloud:

```
+--------------------------------------+--------------------------------------+
|            IN SCOPE                  |           OUT OF SCOPE               |
+--------------------------------------+--------------------------------------+
| Metric ingestion & time-series store | User-facing UI/dashboard frontend   |
| Distributed tracing with sampling    | Log parsing / custom log formats    |
| Structured log collection            | Infrastructure provisioning (K8s)   |
| Alerting (threshold + anomaly)       | APM code-level profiling            |
| Service dependency mapping           | Synthetic monitoring / uptime checks|
| RED/USE dashboard data layer         | Billing / multi-tenant metering     |
| Correlation across pillars           | Authentication & RBAC               |
| High-cardinality mitigation          | Compliance / audit trail            |
+--------------------------------------+--------------------------------------+
```

```
TALKING POINT:
"I'll focus on the backend platform: how metrics, traces, and logs
flow from services through ingestion, storage, querying, and alerting.
I'll design for 100K metrics/sec, 10K spans/sec, and 50GB logs/day
as the baseline, with horizontal scaling for 10x growth."
```

### Common follow-up questions for Phase 1

```
Q: "What if the interviewer says 'just design whatever you think
    is right'?"
A: Default to this scope: 100K metrics/sec, 10K spans/sec, 50GB
   logs/day, 200 services, 7-day hot / 30-day warm / 1-year cold
   retention, <60s alerting latency, threshold + anomaly detection.

Q: "Should I mention Datadog/Prometheus by name?"
A: Yes, briefly: "This is similar to what Datadog or Grafana Cloud
   provides, but I'll design from first principles."
   Shows awareness without name-dropping.

Q: "What if they ask about open-source vs. proprietary?"
A: "For the ingestion layer I'd consider OpenTelemetry as the
   standard wire format. For storage, Prometheus/VictoriaMetrics
   for metrics, Jaeger/Tempo for traces, Loki/Elasticsearch for
   logs. But let me design the core architecture first."
```

---
---

## PHASE 2: HIGH-LEVEL ARCHITECTURE (5-7 min)

### What this phase signals to the interviewer

```
STAFF SIGNAL: You decompose the system into latency zones, name
the key components, and draw data flow arrows -- not just boxes.
You call out which zone is on the critical path and which is async.
```

### Architecture Zones

Present the system as four latency zones. This framing immediately
shows the interviewer you think about SLAs, not just functionality.

```
ZONE 1 -- Ingestion Pipeline (<100ms p99 acknowledgment)
  Agents/SDKs -> Collectors -> Kafka Topics -> Storage Writers
  "Services emit telemetry via lightweight agents. Collectors
   receive, validate, and route data to Kafka. Storage writers
   consume from Kafka and persist. The agent gets an ACK within
   100ms so it never blocks the application."

ZONE 2 -- Storage Tier (write path: <500ms p99 to durable)
  Kafka -> MetricWriter -> TimeSeriesStore
  Kafka -> TraceWriter -> TraceAssembler -> TraceRepository
  Kafka -> LogWriter -> LogProcessor -> LogRepository
  "Each pillar has its own storage backend optimized for its
   access pattern. Metrics use a bucketed time-series store,
   traces use a span-indexed store, logs use an inverted index."

ZONE 3 -- Query Tier (<2s p99 for dashboard queries)
  QueryEngine -> MetricAggregator -> TimeSeriesStore
  QueryEngine -> TraceAssembler -> TraceRepository
  QueryEngine -> LogProcessor -> LogRepository
  "The query tier reads from the storage backends, applies
   aggregation (downsampling, percentile computation), and
   returns results. Dashboard queries must complete in <2s."

ZONE 4 -- Alerting Engine (<60s from breach to notification)
  AlertService -> AlertRules -> AlertingStrategy -> NotificationRouter
  "The alerting engine evaluates rules against recent metric data
   on a configurable interval (default: 15s). When a rule fires,
   the notification router deduplicates, groups, and sends alerts."
```

### ASCII Architecture Diagram (draw this)

```
                       OBSERVABILITY PLATFORM
  =====================================================================

  ZONE 1: INGESTION PIPELINE (<100ms ACK)

  +----------+    +----------+    +----------+    +----------+
  | Service  |    | Service  |    | Service  |    | Service  |
  |  Agent   |    |  Agent   |    |  Agent   |    |  Agent   |
  +----+-----+    +----+-----+    +----+-----+    +----+-----+
       |               |               |               |
       +-------+-------+-------+-------+               |
               |               |                       |
               v               v                       v
        +------+------+  +----+-----+           +-----+------+
        |   Metric    |  |  Trace   |           |    Log     |
        |  Collector  |  | Collector|           |  Collector |
        +------+------+  +----+-----+           +-----+------+
               |               |                       |
  =====================================================================
  ZONE 2: STORAGE TIER (write <500ms durable)
               |               |                       |
               v               v                       v
        +------+------+  +----+-----+           +-----+------+
        |   Kafka     |  |  Kafka   |           |   Kafka    |
        |metrics.raw  |  |spans.raw |           |  logs.raw  |
        +------+------+  +----+-----+           +-----+------+
               |               |                       |
               v               v                       v
        +------+------+  +----+-------+         +-----+------+
        | TimeSeries  |  |   Trace    |         |    Log     |
        |   Store     |  | Assembler  |         |  Processor |
        | (Bucketed   |  | + Sampling |         | (Structured|
        |  TreeMap)   |  |   Engine   |         |  Indexing) |
        +------+------+  +----+-------+         +-----+------+
               |               |                       |
  =====================================================================
  ZONE 3: QUERY TIER (<2s p99)
               |               |                       |
               v               v                       v
        +------+------+  +----+-------+         +-----+------+
        |   Metric    |  |   Trace    |         |    Log     |
        | Aggregator  |  | Repository |         | Repository |
        | (Rate, Pct) |  |            |         |            |
        +------+------+  +----+-------+         +-----+------+
               |               |                       |
               +-------+-------+-----------+-----------+
                       |                   |
                       v                   v
                +------+------+     +------+------+
                |   Query     |     |  Dashboard  |
                |   Engine    |     |   Service   |
                | (PromQL-    |     | (RED / USE) |
                |  like DSL)  |     |             |
                +------+------+     +------+------+
                       |
  =====================================================================
  ZONE 4: ALERTING ENGINE (<60s to notification)
                       |
                       v
                +------+------+     +-------------+
                |   Alert     | <-- | Alert Rules |
                |   Service   |     | (Threshold  |
                |             |     |  + Anomaly) |
                +------+------+     +-------------+
                       |
                       v
                +------+------+
                | Notification|
                |   Router    |
                | (Dedup,     |
                |  Group,     |
                |  Silence)   |
                +------+------+
                    /  |  \
                   v   v   v
               Slack Email PagerDuty
```

### What to say while drawing

```
"Let me walk through the data flow for a single request that
generates all three telemetry signals:

 1. A user request hits the API gateway. The gateway injects a
    traceId (W3C TraceContext header: traceparent) and starts
    a root span.

 2. Each downstream service's agent collects three signals:
    - METRICS: request counter, latency histogram, error rate
    - TRACE: span with traceId, spanId, parentSpanId, timing
    - LOG: structured JSON log with traceId and spanId embedded

 3. Agents batch and forward to per-pillar collectors. Collectors
    validate, enrich (add hostname, region tags), and publish to
    the corresponding Kafka topic.

 4. Storage writers consume from Kafka:
    - MetricWriter -> TimeSeriesStore (bucketed TreeMap)
    - TraceWriter -> SamplingEngine -> TraceAssembler -> TraceRepository
    - LogWriter -> LogProcessor (structured indexing) -> LogRepository

 5. The QueryEngine serves dashboard and ad-hoc queries, fanning
    out to the appropriate storage backend.

 6. The AlertService runs on a 15-second tick, evaluating rules
    against recent metric data via the AlertingStrategy (threshold
    or anomaly detection). Fired alerts are routed through the
    notification pipeline with dedup and grouping."
```

### Design patterns visible in the architecture

```
| Pattern     | Where                             | Why                          |
|-------------|-----------------------------------|------------------------------|
| Strategy x3 | Sampling, Alerting, Aggregation  | Swappable algorithms         |
| Builder     | Metric.Builder, Span.Builder      | Complex object construction  |
| Repository  | MetricRepo, TraceRepo, LogRepo,   | Decouple storage from logic  |
|             | AlertRepo                         |                              |
| Factory     | AppConfig                         | Centralized wiring           |
| Observer    | Alert state transitions            | Decouple event propagation   |
| Facade      | ObservabilityService              | Single entry point           |
| Pipeline    | Collector -> Kafka -> Writer       | Staged async processing      |
```

### Common follow-up questions for Phase 2

```
Q: "Why Kafka instead of direct writes to storage?"
A: "Kafka decouples ingestion from storage. If the time-series
   DB is slow or down, Kafka buffers the data. It also enables
   fan-out: the same metric stream feeds both the storage writer
   AND the alerting engine. Without Kafka, a slow consumer blocks
   the agent -- and the agent must never block the application."

Q: "Why separate collectors per pillar instead of one unified
    collector?"
A: "Each pillar has different serialization, validation, and
   routing needs. Metrics are small (name + value + tags + timestamp),
   spans are medium (parent chain + tags + logs), and log entries
   are large (free-text body + structured fields). Separate
   collectors let us scale each independently. In practice,
   OpenTelemetry Collector handles all three with internal
   pipeline isolation."

Q: "Where is the single point of failure?"
A: "The Kafka cluster. If Kafka goes down, ingestion stalls.
   Mitigation: agents buffer locally (ring buffer or disk queue)
   and replay when Kafka recovers. We also run Kafka with
   replication factor 3 and min.insync.replicas=2 for durability."

Q: "How do you handle backpressure?"
A: "At three levels:
   1. Agent-side: ring buffer drops oldest data when full (bounded)
   2. Collector-side: HTTP 429 back to agents when queue depth
      exceeds threshold
   3. Kafka-side: consumer lag monitoring -- if a writer falls
      behind, we auto-scale consumer instances or activate
      emergency sampling."
```

---
---

## PHASE 3: DEEP DIVE -- TIME-SERIES STORAGE (8-10 min)

### What this phase signals to the interviewer

```
STAFF SIGNAL: You can explain a storage engine clearly, draw the
write and read paths step by step, analyze the space-time tradeoffs,
and connect them to a real production use case. This is the "depth"
signal that separates Staff from Senior.
```

### What to say to transition into this deep dive

```
"Let me deep-dive into the hardest storage problem in observability:
time-series data at scale. Our system ingests 100K metric data points
per second. Each data point is a (name, tags, value, timestamp)
tuple. The challenge is: how do we write at this rate, store months
of data, and still answer aggregation queries in under 2 seconds?"
```

### Time-Series Data Model

```
"First, let me define the data model:

  METRIC POINT = (name, tags, value, timestamp)

  Example:
    name     = http_request_duration_seconds
    tags     = {service=api-gateway, method=GET, status=200, region=us-east}
    value    = 0.042
    timestamp = 2024-03-15T10:30:00.000Z

  A TIME SERIES is a unique combination of (name + tags). So:
    http_request_duration_seconds{service=api-gateway, method=GET, status=200}
    http_request_duration_seconds{service=api-gateway, method=GET, status=500}
  are TWO different time series, even though they share the same name.

  This is the cardinality problem: if 'status' has 5 values, 'method'
  has 4, 'service' has 200, and 'region' has 5, we have
  5 * 4 * 200 * 5 = 20,000 time series for just ONE metric name."
```

### Write Path (draw this)

```
                     TIME-SERIES WRITE PATH
    ================================================================

    +----------+     +----------+     +----------+     +-----------+
    | Metric   | --> | Write    | --> | WAL      | --> | In-Memory |
    | Collector|     | Buffer   |     | (Append  |     | Buffer    |
    |          |     | (Batch)  |     |  Only)   |     | (TreeMap) |
    +----------+     +----------+     +----------+     +-----------+
                                                            |
                                                            | flush every
                                                            | 2 min or
                                                            | 64MB
                                                            v
                                                       +-----------+
                                                       | Immutable |
                                                       | Block     |
                                                       | (Sorted   |
                                                       |  by time) |
                                                       +-----------+
                                                            |
                                                            | compact
                                                            v
                                                       +-----------+
                                                       | Compacted |
                                                       | Blocks    |
                                                       | (2h, 6h,  |
                                                       |  24h)     |
                                                       +-----------+

    STEP-BY-STEP:
    1. Collector publishes MetricPoint to Kafka (metrics.raw topic)
    2. MetricWriter consumes from Kafka in batches (1000 points / 100ms)
    3. Each point is appended to the WAL (append-only, sequential I/O)
    4. Point is inserted into the in-memory buffer (our TimeSeriesStore
       uses TreeMap<Long, List<MetricPoint>> keyed by epoch second)
    5. When buffer reaches 64MB or 2 minutes of data, it is flushed
       to an immutable on-disk block
    6. Background compaction merges small blocks into larger ones
       (2-hour blocks -> 6-hour -> 24-hour)
```

### What to say about the write path

```
"The write path is optimized for sequential I/O and batching:

 Step 1: The WAL (Write-Ahead Log) ensures durability. Every
 incoming point is appended to a sequential log before touching
 any indexed structure. If the process crashes, we replay the
 WAL on startup. This is the same pattern used by Prometheus
 TSDB and LevelDB.

 Step 2: The in-memory buffer is where our TimeSeriesStore lives.
 In our codebase, it's a ConcurrentHashMap<String, TreeMap<Long,
 List<MetricPoint>>> -- metric name maps to a sorted tree of
 epoch-second buckets. TreeMap gives us O(log n) insertion and
 efficient range scans via subMap().

 Step 3: When the buffer fills, we freeze it and flush to disk
 as an immutable block. New writes go to a fresh buffer. This
 is the LSM-tree (Log-Structured Merge Tree) pattern: writes
 go to memory, periodic flushes produce sorted runs on disk.

 Step 4: Compaction merges many small blocks into fewer large
 blocks. This reduces the number of files the read path must
 scan and enables better compression (delta-of-delta encoding
 for timestamps, XOR encoding for values -- same as Gorilla
 paper from Facebook)."
```

### Compression: Gorilla Encoding

```
"For time-series compression, I'd use the Gorilla algorithm
(Facebook, 2015):

 TIMESTAMPS (delta-of-delta encoding):
   Raw:    [1000, 1015, 1030, 1045, 1060]
   Delta:  [1000,   15,   15,   15,   15]
   DoD:    [1000,   15,    0,    0,    0]
   The runs of zeros compress to just a few bits.

 VALUES (XOR encoding):
   If consecutive values are similar (e.g., CPU usage 42.1, 42.3,
   42.2), the XOR of adjacent IEEE 754 floats has many leading
   and trailing zeros, which compress well.

 Result: ~1.37 bytes per data point (vs. 16 bytes raw).
 At 100K points/sec, that's 137KB/sec = ~11.5 GB/day.
 Without compression: 1.6 GB/day * 10 = 16 GB/day."
```

### Read Path (draw this)

```
                     TIME-SERIES READ PATH
    ================================================================

    +----------+     +----------+     +----------+     +-----------+
    | Dashboard| --> | Query    | --> | Metric   | --> | Time      |
    | / API    |     | Engine   |     | Aggregator    | Series    |
    +----------+     +----------+     +----------+     | Store     |
                                           |           +-----------+
                                           |                |
                                           v                v
                                    +------+------+  +------+------+
                                    | Downsampled |  | Raw Blocks  |
                                    | Pre-Agg     |  | (if recent) |
                                    | Buckets     |  |             |
                                    +------+------+  +------+------+
                                           |                |
                                           +--------+-------+
                                                    |
                                                    v
                                             +------+------+
                                             | Merge &     |
                                             | Aggregate   |
                                             | (avg, sum,  |
                                             |  p50, p99)  |
                                             +------+------+
                                                    |
                                                    v
                                             +------+------+
                                             |   Result    |
                                             |   (JSON)    |
                                             +-------------+

    QUERY EXAMPLE:
      "Give me the p99 latency of http_request_duration_seconds
       for service=api-gateway over the last 24 hours, grouped
       by 5-minute buckets."

    EXECUTION PLAN:
      1. Parse query -> identify metric name, tags, time range, agg
      2. Check if a pre-aggregated rollup exists for this query
         (5-min avg/p99 rollup for the last 24h -> yes)
      3. If yes: read from the rollup table (fast, <100ms)
      4. If no: scan raw blocks for the 24h range
         - For each block, binary search by timestamp
         - Filter by tags (service=api-gateway)
         - Stream data points into the aggregation function
      5. MetricAggregator computes p99 via sorted array or t-digest
      6. Return the result bucketed by the requested interval
```

### What to say about the read path

```
"The read path has two strategies depending on query freshness:

 RECENT DATA (last 5 minutes):
   Read directly from the in-memory buffer. Our TimeSeriesStore's
   query() method uses TreeMap.subMap(fromEpoch, toEpoch) for
   O(log n + k) range scans where k is the number of points.

 HISTORICAL DATA (hours to days):
   Read from on-disk blocks. Each block has a time range index,
   so we skip blocks outside the query range. Within a block,
   we binary search to the start timestamp and scan forward.

 DOWNSAMPLED DATA (weeks to months):
   Pre-aggregated rollups at 1-min, 5-min, 1-hour granularity.
   Our TimeSeriesStore.downsample() method computes bucket averages.
   For p99, we'd use t-digest or DDSketch which are mergeable.

 The key optimization is that most dashboard queries hit
 pre-aggregated data. Only ad-hoc drill-downs hit raw blocks."
```

### Pre-Aggregation and Rollups

```
"Pre-aggregation is the secret to fast dashboard queries. Here's
how it works:

 ROLLUP PIPELINE (runs continuously alongside ingestion):

 +-----------+     +-----------+     +-----------+     +-----------+
 | Raw Data  | --> | 1-Minute  | --> | 5-Minute  | --> | 1-Hour    |
 | (per-sec) |     | Rollup    |     | Rollup    |     | Rollup    |
 +-----------+     +-----------+     +-----------+     +-----------+

 FOR EACH ROLLUP BUCKET, WE STORE:
   - count:  number of data points in the bucket
   - sum:    sum of all values
   - min:    minimum value
   - max:    maximum value
   - avg:    sum / count (derivable, but cached for speed)
   - p50:    t-digest sketch (mergeable percentile)
   - p95:    t-digest sketch
   - p99:    t-digest sketch

 WHY THESE ARE MERGEABLE:
   - count: sum(count_a, count_b) = total count
   - sum:   sum(sum_a, sum_b)     = total sum
   - min:   min(min_a, min_b)     = total min
   - max:   max(max_a, max_b)     = total max
   - avg:   (sum_a + sum_b) / (count_a + count_b)
   - p99:   merge(tdigest_a, tdigest_b) -> valid tdigest

 NON-MERGEABLE OPERATIONS (common interview trap):
   - average of averages != overall average
   - percentile of percentiles != overall percentile
   This is why we store sum+count instead of just avg,
   and t-digest sketches instead of just percentile values.

 EXAMPLE QUERY RESOLUTION:
   Query: 'p99 latency for api-gateway, last 24 hours, 5m buckets'
   Plan:  Read 288 five-minute rollup buckets (24h / 5min = 288)
          Each bucket has a t-digest sketch.
          Merge adjacent sketches if coarser resolution is needed.
          Return 288 p99 values.
   Cost:  288 reads vs. 86,400 * N raw point reads. ~300x cheaper."
```

### Cardinality Explosion Mitigation

```
"Cardinality explosion is the number one operational problem
in time-series databases. Let me explain how we handle it:

 THE PROBLEM:
   If a developer adds user_id as a metric tag, and we have
   10M users, every metric multiplies by 10M. A metric with
   3 other tags (method * status * region = 4 * 5 * 5 = 100)
   now has 100 * 10M = 1 BILLION time series.

 MITIGATION STRATEGIES:

   1. TAG ALLOWLISTING (compile-time)
      Only allow pre-approved tag keys. Block user_id, request_id,
      session_id, and similar high-cardinality tags at the
      collector level. This is the strongest defense.

   2. CARDINALITY LIMITS (runtime)
      Each metric name has a max cardinality budget (e.g., 10K
      unique tag combinations). When the limit is hit, new
      tag combinations are dropped and an alert fires.

   3. TAG AGGREGATION (automatic)
      When cardinality exceeds a soft limit, automatically
      aggregate low-value tag dimensions. For example, collapse
      status=201, status=202, status=204 into status=2xx.

   4. METRIC RELABELING (ingestion-time)
      At the collector, apply relabeling rules that drop or
      rename tags before they reach storage. Similar to
      Prometheus metric_relabel_configs.

   5. STREAMING CARDINALITY ESTIMATION (HyperLogLog)
      Use HyperLogLog sketches to estimate the cardinality of
      each tag key in real-time without storing all unique values.
      Alert when estimated cardinality exceeds the budget."
```

### Diagram: Cardinality Control Flow

```
    +----------+     +-----------+     +-------------+     +-----------+
    | Incoming | --> | Tag       | --> | Cardinality | --> | TimeSeries|
    | Metric   |     | Allowlist |     | Counter     |     | Store     |
    | Point    |     | Filter    |     | (HLL)       |     |           |
    +----------+     +-----------+     +-------------+     +-----------+
                          |                  |
                          v                  v
                     +----------+     +-------------+
                     | DROPPED  |     | ALERT:      |
                     | (blocked |     | Cardinality |
                     |  tag key)|     | budget 80%  |
                     +----------+     +-------------+
```

### Common follow-up questions for Phase 3

```
Q: "Why not just use Prometheus?"
A: "Prometheus is excellent for metrics but has limitations at our
   scale: its local TSDB doesn't support horizontal sharding
   natively. For 100K metrics/sec across hundreds of services,
   we'd need a distributed layer like Thanos or VictoriaMetrics
   on top. My design shows the same write path as Prometheus
   TSDB (WAL + in-memory head + immutable blocks + compaction)
   but with a Kafka-based ingestion tier for horizontal scaling."

Q: "How does downsampling work without losing percentiles?"
A: "You can't average averages or merge raw percentiles. Instead,
   we store mergeable sketches:
   - t-digest for percentiles (merge two t-digests -> valid t-digest)
   - DDSketch for percentiles (deterministic, mergeable)
   - min/max/sum/count for basic aggregates (trivially mergeable)
   During downsampling, we compute and store the sketch for each
   bucket. At query time, we merge the sketches across buckets."

Q: "What's the write amplification concern?"
A: "LSM-trees trade write amplification for read performance.
   Each data point is written to: (1) WAL, (2) in-memory buffer,
   (3) flushed block, (4) compacted block. That's 3-4x write
   amplification. At 100K points/sec, the disk sees 300-400K
   effective writes/sec. SSDs handle this well; HDDs would not.
   The upside is that reads are fast because compacted blocks
   are sorted and indexed."

Q: "How do you handle out-of-order writes?"
A: "Time-series data is mostly in-order but network delays can
   cause out-of-order arrivals. Two approaches:
   1. Allow a configurable out-of-order window (e.g., 5 minutes).
      Points within the window are inserted into the current
      in-memory buffer even if they're slightly old.
   2. Points older than the window are written to a separate
      out-of-order WAL and reconciled during compaction.
   Prometheus added native out-of-order support in 2.39 for
   exactly this reason."
```

---
---

## PHASE 4: DEEP DIVE -- DISTRIBUTED TRACING (5-7 min)

### What this phase signals to the interviewer

```
STAFF SIGNAL: You understand the full lifecycle of a trace: context
propagation, span collection, sampling trade-offs, and trace assembly.
You can explain why tail-based sampling is harder than head-based
and when each is appropriate.
```

### What to say to transition into this deep dive

```
"Now let me deep-dive into distributed tracing -- specifically,
how we collect spans from hundreds of services, propagate context
across process boundaries, decide what to sample, and assemble
complete traces for querying."
```

### Context Propagation (draw this)

```
                  W3C TRACECONTEXT PROPAGATION
    ================================================================

    Service A                Service B                Service C
    +------------------+     +------------------+     +------------------+
    | Root Span        |     | Child Span       |     | Child Span       |
    | traceId: abc123  |     | traceId: abc123  |     | traceId: abc123  |
    | spanId: span-1   |     | spanId: span-2   |     | spanId: span-3   |
    | parentId: null   |     | parentId: span-1 |     | parentId: span-2 |
    +--------+---------+     +--------+---------+     +------------------+
             |                        |
             | HTTP Header:           | HTTP Header:
             | traceparent:           | traceparent:
             | 00-abc123-span1-01    | 00-abc123-span2-01
             |                        |
             +----------->            +----------->

    HEADER FORMAT (W3C TraceContext):
      traceparent: {version}-{trace-id}-{parent-id}-{trace-flags}
      Example:     00-abc123def456-span1aaa-01

      version     = 00 (current spec)
      trace-id    = 128-bit hex, globally unique
      parent-id   = 64-bit hex, the current span's ID
      trace-flags = 01 means "sampled" (this trace should be recorded)

    BAGGAGE PROPAGATION (for correlation):
      tracestate: vendorkey=vendorvalue
      baggage:    user_id=12345,deployment=canary

    WHY W3C TRACECONTEXT:
      - Vendor-neutral standard (unlike Zipkin B3 or Jaeger headers)
      - Supported by OpenTelemetry, all major APM vendors
      - Carries the sampling decision so downstream services
        respect it (no need to re-decide)
```

### What to say about context propagation

```
"Context propagation is the foundation of distributed tracing.
Without it, spans from different services can't be correlated.

 In our codebase, the TraceContext model carries:
   - traceId:   globally unique, generated at the entry point
   - spanId:    unique per span, generated by each service
   - parentSpanId: the caller's spanId
   - baggage:   key-value pairs propagated to all downstream services

 When Service A calls Service B via HTTP, the agent injects the
 traceparent header. Service B's agent extracts it, creates a
 new child span with a fresh spanId, and sets parentSpanId to
 Service A's spanId. The traceId stays the same throughout.

 For non-HTTP protocols (gRPC, Kafka, RabbitMQ), the same
 context is propagated via metadata/headers specific to each
 transport. OpenTelemetry provides propagators for all of these."
```

### Span Collection and Trace Assembly

```
                     SPAN COLLECTION PIPELINE
    ================================================================

    Services emit spans            Collector buffers
    asynchronously                 and batches
    +----------+  +----------+    +-----------+
    | Span     |  | Span     | -> | Trace     | -> Kafka (spans.raw)
    | (svc A)  |  | (svc B)  |    | Collector |
    | t=100ms  |  | t=150ms  |    | (batch    |
    +----------+  +----------+    |  1000/5s) |
                                  +-----------+

    Trace Assembler consumes       Sampling decides
    from Kafka                     what to keep
    +-----------+                  +-----------+
    | Trace     | <-- Kafka <--    | Sampling  |
    | Assembler |                  | Engine    |
    |           |                  | (Strategy)|
    +-----------+                  +-----------+
         |
         v
    Assembles spans into Trace
    +-----------+
    | Trace     |
    | {traceId: abc123,
    |  spans: [span-1, span-2, span-3],
    |  rootService: "api-gateway",
    |  duration: 250ms}
    +-----------+
         |
         v
    +-----------+
    | Trace     |
    | Repository|
    +-----------+

    ASSEMBLY ALGORITHM (from our TraceAssembler):
      1. Incoming spans are buffered in pendingSpans map (traceId -> List<Span>)
      2. When assembleTrace(traceId) is called:
         a. Find the root span (parentSpanId == null)
         b. If no root span yet, keep waiting (spans arrive out of order)
         c. Create a Trace object from the root span
         d. Add all buffered spans to the Trace
         e. Remove from pendingSpans (free memory)
      3. Trigger assembly after a timeout (e.g., 30 seconds after
         first span arrives) OR when all expected spans are present

    SPAN ORDERING CHALLENGE:
      Spans arrive out of order because:
      - Network latency varies between services and collectors
      - Service C might report its span before Service A
      - Batch intervals differ across agents

      Our TraceAssembler handles this by buffering all spans for
      a traceId and assembling only when the root span is found.
      In production, you'd add a timeout to handle missing root spans.
```

### Sampling Strategies (critical topic)

```
    SAMPLING STRATEGY COMPARISON
    ================================================================

    +------------------+------------------+------------------+
    |   HEAD-BASED     |   TAIL-BASED     |   RATE-LIMITED   |
    +==================+==================+==================+
    | Decision point:  | Decision point:  | Decision point:  |
    | At trace start   | After trace      | At trace start   |
    | (root span)      | completes        | (token bucket)   |
    +------------------+------------------+------------------+
    | How it works:    | How it works:    | How it works:    |
    | Hash(traceId)    | Collect ALL spans| Limit to N       |
    | % 100 < rate?   | then filter by:  | traces/sec.      |
    | If yes: sample   | - error status   | Token bucket     |
    | If no: drop      | - high latency   | refills at rate. |
    |                  | - rare operation | If token avail:  |
    |                  |                  | sample. Else drop|
    +------------------+------------------+------------------+
    | Pros:            | Pros:            | Pros:            |
    | - Simple         | - Keeps ALL      | - Predictable    |
    | - No buffering   |   interesting    |   cost           |
    | - Consistent     |   traces         | - Smooth traffic |
    |   (all spans     | - 100% of errors | - Simple impl    |
    |   in a trace     |   captured       |                  |
    |   sampled same)  | - Latency        |                  |
    |                  |   outliers kept  |                  |
    +------------------+------------------+------------------+
    | Cons:            | Cons:            | Cons:            |
    | - Misses errors  | - Must buffer    | - May miss       |
    |   (error happens |   ALL spans      |   bursts         |
    |   after decision)|   temporarily    | - Not correlated |
    | - Blind to       | - Higher memory  |   with trace     |
    |   latency        |   cost           |   importance     |
    | - Misses rare    | - Higher latency | - Random         |
    |   operations     |   to decision    |   selection      |
    +------------------+------------------+------------------+
    | Use when:        | Use when:        | Use when:        |
    | - Budget is      | - Error traces   | - Fixed budget   |
    |   tight          |   are critical   | - Uniform load   |
    | - Uniform        | - SLA violations | - Cost ceiling   |
    |   traffic        |   must be        |   is paramount   |
    | - Simple setup   |   captured       |                  |
    +------------------+------------------+------------------+

    OUR IMPLEMENTATION (from the codebase):

    HeadBasedSamplingStrategy:
      hash = Math.abs(traceId.hashCode()) % 100
      if (hash < sampleRate * 100) -> SAMPLE
      Deterministic: same traceId always gets same decision.
      All spans in a trace are sampled or dropped together.

    TailBasedSamplingStrategy:
      shouldSample(context) always returns true (collect everything)
      shouldSample(context, operationName) checks baggage:
        - baggage["error"] == "true" -> KEEP (errors always sampled)
        - baggage["latency_ms"] > threshold -> KEEP (slow traces)
        - otherwise -> DROP

    RateLimitedSamplingStrategy:
      Token bucket with configurable rate (e.g., 100 traces/sec).
      Each sampling decision consumes one token.
      If no tokens available -> DROP.
```

### What to say about sampling

```
"Sampling is the most important cost-control mechanism in tracing.
At 10K spans/sec with an average trace depth of 5, that's 2K
traces/sec. Storing all of them costs ~$X/month.

 My recommendation is a HYBRID approach:

   1. HEAD-BASED for the baseline: sample 10% of all traces
      deterministically. This gives you a statistically valid
      sample for RED metrics (Rate, Error, Duration).

   2. TAIL-BASED for interesting traces: collect all spans
      temporarily in a buffer, then keep 100% of error traces
      and traces exceeding the p99 latency threshold. This ensures
      you never miss a production incident.

   3. RATE-LIMITED as a safety valve: cap total stored traces
      at 500/sec to prevent cost overruns during traffic spikes.

 The SamplingEngine in our codebase uses the Strategy pattern,
 so we can swap between these at runtime without code changes."
```

### Trace Storage Schema

```
"Let me describe how traces are stored for efficient querying:

 PRIMARY TABLE (span-level, indexed by traceId):
   +----------+----------+-----------+---------+-------+--------+------+
   | trace_id | span_id  | parent_id | service | op    | start  | dur  |
   +==========+==========+===========+=========+=======+========+======+
   | abc123   | span-1   | null      | api-gw  | GET / | t=1000 | 42ms |
   | abc123   | span-2   | span-1    | order   | query | t=1005 | 30ms |
   | abc123   | span-3   | span-2    | db      | SELECT| t=1010 | 20ms |
   +----------+----------+-----------+---------+-------+--------+------+

 SECONDARY INDICES (for search):
   - By service name: "show me all traces involving order-service"
   - By operation name: "show me all traces with GET /api/orders"
   - By duration: "show me all traces slower than 500ms"
   - By status: "show me all traces with ERROR spans"
   - By time range: "show me traces from the last hour"

 TAG INDEX (for filtering):
   - Span tags are stored as key-value pairs in a separate table
   - Indexed by (tag_key, tag_value, trace_id) for fast lookup
   - Example: find all traces where http.status_code=500

 STORAGE ENGINE CHOICE:
   - Cassandra: good for write-heavy, time-partitioned data
     Partition key = traceId, clustering key = spanId
     TTL for automatic expiration
   - ClickHouse: columnar, excellent for aggregation queries
     'How many traces per service had errors in the last hour?'
   - Elasticsearch: flexible querying but higher storage cost

 OUR CODEBASE:
   TraceRepository stores complete Trace objects (with all spans).
   In production, we'd shard by traceId hash across Cassandra nodes."
```

### Common follow-up questions for Phase 4

```
Q: "How does tail-based sampling work in practice with
    microservices?"
A: "It requires a centralized tail-sampling collector. All spans
   flow through this collector (or a set of sharded collectors).
   The collector buffers spans for 30-60 seconds, waiting for the
   trace to complete. Once complete, it evaluates the sampling
   policy (error? slow? rare operation?) and either keeps or drops
   the entire trace. The challenge is the memory cost of buffering
   -- at 10K spans/sec with 30s buffer, that's 300K spans in
   memory. Each span is ~500 bytes, so ~150MB. Manageable for
   a dedicated collector."

Q: "What happens if a span is lost in transit?"
A: "The trace will be incomplete -- one branch of the call tree
   will be missing. The TraceAssembler detects this as a gap
   (a span references a parentSpanId that doesn't exist in the
   trace). We mark the trace as 'partial' and still display it,
   with the missing span shown as a dashed box. For alerting,
   we track the 'trace completeness' metric -- if it drops below
   95%, something is wrong with the collection pipeline."

Q: "How do you handle fan-out traces (one service calls 100
    downstream services)?"
A: "Fan-out traces produce very wide traces (100 child spans
   under one parent). For storage, this is fine -- each span is
   independent. For visualization, we collapse sibling spans of
   the same operation into a summary row: 'auth-service.validate
   x100, avg=5ms, max=50ms'. This keeps the trace view readable."

Q: "What about async traces (Kafka, queues)?"
A: "For Kafka, the producer injects the traceparent header into
   the Kafka message headers. The consumer extracts it and creates
   a new span with a FOLLOWS_FROM relationship (not CHILD_OF).
   This produces a linked trace where the consumer's span is
   connected to the producer's span but with a time gap. Our
   TraceContext model supports this via the baggage field."
```

---
---

## PHASE 5: ALERTING PIPELINE (3-5 min)

### What this phase signals to the interviewer

```
STAFF SIGNAL: You understand that alerting is not just "if value > X,
send email." You can explain evaluation frequency, hysteresis,
deduplication, grouping, silencing, and inhibition -- the same
features that make Alertmanager and PagerDuty effective.
```

### What to say to transition into this phase

```
"Now let me cover the alerting pipeline -- how we go from a metric
crossing a threshold to an engineer getting a page. This sounds
simple but has several subtle requirements: we need to avoid alert
storms, deduplicate repeated firings, support silencing during
maintenance, and detect anomalies that static thresholds miss."
```

### Structured Logging and Correlation (brief mention before alerting)

```
"Before diving into alerting, let me briefly touch on the logging
pillar since it ties into the correlation story:

 STRUCTURED LOG FORMAT:
   Every log entry is a JSON object with mandatory fields:

   {
     "timestamp":  "2024-03-15T10:30:00.042Z",
     "level":      "ERROR",
     "service":    "order-service",
     "traceId":    "abc123",
     "spanId":     "span-2",
     "message":    "Database connection timeout",
     "attributes": {
       "db.host":     "primary-db.internal",
       "db.query":    "SELECT * FROM orders WHERE id=123",
       "retry_count": 2
     }
   }

 KEY DESIGN DECISIONS:
   1. traceId and spanId are MANDATORY fields -- enforced at the
      agent level. This enables metrics-to-traces-to-logs drill-down.
   2. Structured fields (not free text) enable efficient filtering:
      'show me all ERROR logs from order-service in the last hour
       where db.host=primary-db.internal'
   3. The LogProcessor builds an inverted index on (service, level,
      traceId, timestamp) for fast search.

 LOG STORAGE:
   - Hot (7d): Elasticsearch or Loki with full indexing
   - Warm (30d): compressed, limited index (only service + level)
   - Cold (1yr): object store (S3), batch search only

 CORRELATION WORKFLOW (what the engineer does):
   1. Dashboard shows error rate spike for order-service
   2. Click -> shows traces with errors in that time window
   3. Click a trace -> shows the trace waterfall with the ERROR span
   4. Click the ERROR span -> shows the linked log entries
   5. Log shows 'Database connection timeout' with db.host
   6. Engineer now knows the root cause without guessing

 This is why correlation matters -- it turns a 30-minute
 investigation into a 2-minute drill-down."
```

### Alert Rule Evaluation Pipeline (draw this)

```
                     ALERTING PIPELINE
    ================================================================

    Step 1: Rule Evaluation (every 15s tick)
    +-------------+     +-------------+     +-------------+
    | Alert       | --> | For each    | --> | Query last  |
    | Service     |     | enabled     |     | 60s of data |
    | .evaluate() |     | AlertRule   |     | from Metric |
    |             |     |             |     | Service     |
    +-------------+     +-------------+     +------+------+
                                                   |
    Step 2: Strategy Evaluation                    v
    +-------------+     +-------------+     +------+------+
    | Threshold   |     | Anomaly     |     | Alerting    |
    | Strategy    | OR  | Detection   | <-- | Strategy    |
    | (value >    |     | (stddev     |     | .shouldAlert|
    |  threshold) |     |  outlier)   |     | (rule, pts) |
    +------+------+     +------+------+     +-------------+
           |                   |
           v                   v
    Step 3: State Machine
    +-----------------------------------------------------------+
    |                                                           |
    |  OK -----(breach)-----> PENDING -----(for_duration)----> |
    |   ^                                     FIRING            |
    |   |                                       |               |
    |   +-------(recovered)---------------------+               |
    |                                           |               |
    |                                    +------v------+        |
    |                                    | ACKNOWLEDGED |        |
    |                                    | (by operator)|        |
    |                                    +------+------+        |
    |                                           |               |
    |                                    +------v------+        |
    |                                    |   RESOLVED  |        |
    |                                    +-------------+        |
    +-----------------------------------------------------------+

    Step 4: Notification Routing
    +-------------+     +-------------+     +-------------+
    | Fired Alert | --> | Dedup &     | --> | Group by    |
    |             |     | Throttle    |     | service +   |
    |             |     | (1 per rule |     | severity    |
    |             |     |  per 5min)  |     |             |
    +-------------+     +------+------+     +------+------+
                               |                   |
                               v                   v
                        +------+------+     +------+------+
                        | Silence     |     | Inhibit     |
                        | Check       |     | Check       |
                        | (maint      |     | (parent     |
                        |  window?)   |     |  alert?)    |
                        +------+------+     +------+------+
                               |                   |
                               v                   v
                        +------+------+     +------+------+
                        | Route by    |     | Escalation  |
                        | severity:   |     | (if not ACK |
                        | CRITICAL -> |     |  in 15min)  |
                        |   PagerDuty |     |             |
                        | WARNING ->  |     |             |
                        |   Slack     |     |             |
                        | INFO ->     |     |             |
                        |   Email     |     |             |
                        +-------------+     +-------------+
```

### Threshold Alerting

```
"Our ThresholdAlertingStrategy is straightforward:

 RULE DEFINITION (from AlertRule model):
   name          = 'High API Latency'
   metricName    = 'http_request_duration_seconds'
   condition     = 'GREATER_THAN'
   threshold     = 0.5  (500ms)
   severity      = CRITICAL
   enabled       = true

 EVALUATION:
   1. Query the last 60 seconds of data for the metric
   2. Compute the average (or latest value, configurable)
   3. If average > threshold -> shouldAlert() returns true
   4. AlertService creates an Alert with FIRING status

 In our codebase, AlertService.evaluateRules() iterates over all
 enabled rules, queries MetricService for recent data, and passes
 the data points to the alerting strategy. The firingRules map
 tracks which rules are currently in FIRING state to prevent
 duplicate alerts."
```

### Anomaly Detection

```
"Our AnomalyDetectionAlertingStrategy uses z-score detection:

 ALGORITHM:
   1. Collect the last 60 seconds of data points (minimum 3)
   2. Compute mean = avg(all values)
   3. Compute stdDev = sqrt(avg((value - mean)^2))
   4. Check latest value: deviation = |latest - mean|
   5. If deviation > (multiplier * stdDev) -> ANOMALY

 DEFAULT MULTIPLIER: 2.0 (corresponds to ~95% confidence interval)

 EXAMPLE:
   Points: [100, 102, 98, 101, 99, 350]
   Mean:   125.0
   StdDev: 91.4
   Deviation of 350: |350 - 125| = 225
   225 > 2.0 * 91.4 (182.8)? YES -> ALERT

 LIMITATIONS:
   - Requires at least 3 data points (our code enforces this)
   - Assumes roughly normal distribution
   - Seasonal patterns (daily traffic cycles) need a longer
     lookback window or seasonal decomposition

 PRODUCTION ENHANCEMENT:
   For seasonal data, use Holt-Winters triple exponential smoothing
   or a simple approach: compare current value to the same time
   yesterday and same time last week."
```

### Deduplication and Grouping

```
"The notification pipeline is just as important as the detection.
Here's how we prevent alert storms:

 1. DEDUPLICATION (idempotency)
    Key = (ruleId + metricName + tag combination)
    If an alert with this key is already FIRING, don't send
    another notification. Our AlertService uses the firingRules
    map (ruleId -> alertId) for this.

 2. THROTTLING
    Maximum one notification per rule per 5-minute window.
    Even if the metric oscillates above/below threshold,
    the engineer only gets notified once per window.

 3. GROUPING
    Batch related alerts into a single notification:
    'api-gateway: 3 alerts firing (latency, error rate, saturation)'
    Group by service name + severity level.

 4. SILENCING
    During a maintenance window, silence alerts for specific
    services. Silenced alerts are still evaluated and recorded,
    but notifications are suppressed.

 5. INHIBITION
    If a parent alert is firing (e.g., 'database-down'), suppress
    child alerts that are consequences (e.g., 'high-api-latency',
    'increased-error-rate'). This prevents cascading pages.

 6. ESCALATION
    If an alert is not acknowledged within 15 minutes, escalate
    to the next tier (e.g., from on-call engineer to team lead)."
```

### Common follow-up questions for Phase 5

```
Q: "How do you avoid flapping alerts (rapid fire-resolve-fire)?"
A: "Hysteresis. The rule has two thresholds:
     - FIRE when value > 500ms for 2 consecutive evaluation cycles
     - RESOLVE when value < 400ms for 3 consecutive cycles
   The resolve threshold is intentionally lower than the fire
   threshold. The 'for_duration' parameter in the alert rule
   specifies how long the condition must persist before firing.
   This is exactly how Prometheus alerting rules work."

Q: "What if the alerting engine itself goes down?"
A: "Run multiple instances of AlertService. Each instance
   evaluates all rules (since evaluation is read-only). Use a
   leader election or lock (Redis SETNX) so only one instance
   sends notifications. If the leader dies, another takes over
   within 30 seconds. The rule state (firingRules map) must be
   shared -- either in Redis or the alert database."

Q: "How do you handle composite alerts (alert if A AND B are
    both above threshold)?"
A: "Two approaches:
   1. Alert-level: evaluate each rule independently, then the
      notification router checks if the correlated set of rules
      are all firing before sending.
   2. Query-level: write a composite query that references
      multiple metrics: 'latency > 500ms AND error_rate > 5%'.
      This requires a more expressive rule language (like PromQL
      with boolean operators)."
```

---
---

## PHASE 6: SCALING & TRADEOFFS (3-5 min)

### What this phase signals to the interviewer

```
STAFF SIGNAL: You can reason about scale, name specific numbers,
and explain tradeoffs without hand-waving. You can explain WHY
you'd choose push vs. pull, or sampling vs. full collection,
not just THAT you'd choose one.
```

### Horizontal Scaling Strategy

```
"Let me walk through how each layer scales horizontally:

 INGESTION LAYER:
   - Metric collectors: stateless, scale by adding instances
     behind a load balancer. Each instance writes to a Kafka
     partition. N collectors = N * throughput.
   - Trace collectors: stateless for head-based sampling.
     For tail-based: shard by traceId (hash-based routing)
     so all spans for a trace go to the same collector.
   - Log collectors: stateless, scale linearly.

 KAFKA LAYER:
   - Partition by metric name (metrics topic) or traceId (spans
     topic). More partitions = more parallelism.
   - At 100K metrics/sec with 100 partitions = 1K msg/sec/partition.
   - Replication factor 3 for durability.

 STORAGE LAYER:
   - Metrics: shard by metric name hash across N time-series
     store instances. Consistent hashing for rebalancing.
   - Traces: shard by traceId across N trace store instances.
   - Logs: shard by service name or time-based partitioning
     (daily indices, like Elasticsearch).

 QUERY LAYER:
   - Query routers fan-out to relevant shards, merge results.
   - For aggregation queries: each shard computes partial
     aggregates (partial t-digest, partial sum/count), the
     router merges them.
   - Query caching: LRU cache for repeated dashboard queries.

 ALERTING LAYER:
   - Alert rule evaluation can be partitioned: rules 1-1000
     on instance A, 1001-2000 on instance B.
   - Each instance reads from the metric store (read-only).
   - Notification routing is centralized (single writer)
     with leader election for HA."
```

### Push vs. Pull Model

```
    PUSH vs. PULL TRADEOFF
    ================================================================

    +------------------+---------------------------+---------------------------+
    |                  |         PUSH              |         PULL              |
    +==================+===========================+===========================+
    | How it works     | Services push data to     | Collector scrapes         |
    |                  | collector/gateway         | service endpoints         |
    +------------------+---------------------------+---------------------------+
    | Example          | StatsD, Datadog Agent,    | Prometheus, VictoriaMetrics|
    |                  | OpenTelemetry OTLP        | /metrics endpoint         |
    +------------------+---------------------------+---------------------------+
    | Service          | Service must know the     | Service just exposes an   |
    | awareness        | collector endpoint        | endpoint; doesn't know    |
    |                  |                           | who reads it              |
    +------------------+---------------------------+---------------------------+
    | Scaling          | Collector must handle     | Scraper controls the rate;|
    |                  | burst traffic (backpres.) | can slow down if overloaded|
    +------------------+---------------------------+---------------------------+
    | Discovery        | Service registers with    | Scraper uses service      |
    |                  | collector or pushes to    | discovery (K8s, Consul)   |
    |                  | a fixed gateway           | to find targets           |
    +------------------+---------------------------+---------------------------+
    | Short-lived      | Works well -- push before | Misses short-lived jobs   |
    | processes        | exit                      | between scrape intervals  |
    +------------------+---------------------------+---------------------------+
    | Firewall         | Service initiates outbound| Scraper needs inbound     |
    | friendliness     | connection (easier)       | access to all services    |
    +------------------+---------------------------+---------------------------+
    | Our choice       | PUSH for traces and logs  | PULL for metrics          |
    |                  | (event-driven, variable   | (regular interval, known  |
    |                  | volume, latency-sensitive) | endpoints, rate control)  |
    +------------------+---------------------------+---------------------------+

    TALKING POINT:
    "In practice, most modern platforms use BOTH:
     - PULL for metrics (Prometheus model -- predictable, rate-controlled)
     - PUSH for traces and logs (event-driven, variable volume)
     - OpenTelemetry supports both via the OTLP protocol
     Our design uses push via Kafka for all three pillars because
     it simplifies the architecture and provides built-in buffering."
```

### Sampling vs. Full Collection

```
    SAMPLING TRADEOFFS
    ================================================================

    +------------------+---------------------------+---------------------------+
    |                  |    FULL COLLECTION        |    SAMPLED (e.g., 10%)    |
    +==================+===========================+===========================+
    | Cost             | 10x storage, 10x compute  | 1x baseline               |
    +------------------+---------------------------+---------------------------+
    | Accuracy         | Perfect -- every event    | Statistical (sufficient   |
    |                  | is recorded               | for aggregates, not for   |
    |                  |                           | individual trace lookup)  |
    +------------------+---------------------------+---------------------------+
    | Debugging        | Can find any trace by ID  | May miss specific trace   |
    +------------------+---------------------------+---------------------------+
    | Alerting         | Alerts on exact data      | Alerts on sampled data    |
    |                  |                           | (may miss brief spikes)   |
    +------------------+---------------------------+---------------------------+
    | Our approach     | Full collection for       | 10% head-based sampling   |
    |                  | metrics (small per-point  | for traces + tail-based   |
    |                  | cost). Full for logs      | 100% for errors/slow.     |
    |                  | (needed for grep).        | Metrics: no sampling.     |
    +------------------+---------------------------+---------------------------+

    KEY INSIGHT:
    "Metrics should NEVER be sampled -- they're aggregates already.
     A counter value of 42 doesn't benefit from sampling because
     it's a pre-computed aggregate. Traces SHOULD be sampled because
     each trace is a detailed record of one request, and storing
     all requests is prohibitively expensive at scale."
```

### Storage Tiering

```
    STORAGE TIERING (hot / warm / cold)
    ================================================================

    +--------+  7 days   +---------+  30 days  +--------+ 1 year
    |  HOT   | -------> |  WARM   | -------> |  COLD  | -------> DELETE
    | (SSD)  |          | (HDD)   |          | (Object|
    |        |          |         |          |  Store) |
    +--------+          +---------+          +--------+

    HOT (0-7 days):
      - Storage: NVMe SSD
      - Format: raw blocks (second-level granularity)
      - Access: real-time queries, alerting
      - Cost: $$$

    WARM (7-30 days):
      - Storage: HDD or cheaper SSD
      - Format: 5-minute downsampled blocks
      - Access: dashboard queries, trend analysis
      - Cost: $$

    COLD (30 days - 1 year):
      - Storage: Object store (S3, GCS)
      - Format: 1-hour downsampled, columnar (Parquet)
      - Access: ad-hoc forensic queries (slow, 10-30s)
      - Cost: $

    TRANSITION LOGIC:
      - Background job runs daily
      - For each metric: downsample hot data and move to warm
      - For each warm metric: further downsample and move to cold
      - Delete data older than retention period

    TALKING POINT:
    "Storage tiering is essential because 90% of queries hit the
     last 6 hours of data, but compliance requires 1-year retention.
     Without tiering, you're paying SSD prices for data nobody
     queries. With tiering, the cost per data point drops 10-50x
     as it moves from hot to cold."
```

### Capacity Estimation (back-of-envelope)

```
"Let me estimate the resource requirements for our baseline scale:

 METRICS:
   - Ingestion: 100K points/sec
   - Point size: 1.37 bytes compressed (Gorilla encoding)
   - Daily ingest: 100K * 86400 * 1.37 = ~11.5 GB/day
   - Hot storage (7d raw): 11.5 * 7 = ~80 GB
   - Warm storage (30d, 5-min downsample): 80 / 300 * 30 = ~8 GB
   - Cold storage (1yr, 1-hour downsample): 80 / 3600 * 365 = ~8 GB
   - Total metrics storage: ~100 GB

 TRACES:
   - Ingestion: 10K spans/sec (avg 5 spans/trace = 2K traces/sec)
   - Span size: ~500 bytes (after compression)
   - With 10% head-based sampling: 1K spans/sec stored
   - Plus tail-based (assume 5% error/slow): 500 spans/sec extra
   - Total stored: ~1.5K spans/sec = ~65 GB/day
   - Hot storage (7d): 65 * 7 = ~455 GB
   - No downsampling for traces (they're discrete events)
   - Cold storage (30d): 65 * 30 = ~1.95 TB

 LOGS:
   - Ingestion: 50 GB/day (raw)
   - Compression ratio: ~10:1 = 5 GB/day stored
   - Index overhead: ~15% = 0.75 GB/day
   - Hot storage (7d): 5.75 * 7 = ~40 GB
   - Warm storage (30d): ~170 GB (reduced index)
   - Cold storage (1yr): ~2 TB (compressed, no index)

 KAFKA:
   - 3 topics (metrics, spans, logs)
   - Retention: 24 hours for replay capability
   - Daily throughput: 11.5 + 65 + 50 = ~126 GB/day
   - Kafka storage: 126 GB * 3 replicas = ~378 GB

 COMPUTE (approximate):
   - Collectors: 5 instances (2 CPU, 4GB RAM each)
   - Storage writers: 5 instances (4 CPU, 8GB RAM each)
   - Query nodes: 3 instances (8 CPU, 32GB RAM each)
   - Alert evaluator: 2 instances (2 CPU, 4GB RAM each)
   - Kafka brokers: 5 instances (4 CPU, 16GB RAM each)

 TOTAL:
   Storage: ~5 TB (all tiers, 1 year retention)
   Compute: 20 instances
   Monthly cost estimate: ~$5-8K (cloud, reserved instances)"
```

### Common follow-up questions for Phase 6

```
Q: "How do you handle a 10x traffic spike?"
A: "Three layers of defense:
   1. Agent-side: ring buffer with bounded size. If the buffer
      fills, the oldest data is dropped (not the newest).
   2. Kafka: absorbs the spike. Consumers may fall behind
      (consumer lag increases) but data is not lost.
   3. Emergency sampling: if consumer lag exceeds a threshold
      (e.g., 5 minutes), activate aggressive sampling (drop to
      1% for non-error traces) until the lag recovers.
   The key insight: it's better to lose some telemetry data
   than to crash the observability platform itself."

Q: "What about multi-region deployment?"
A: "Each region runs its own ingestion and storage stack.
   Cross-region queries use a federated query layer that fans
   out to each region's query tier and merges results. For
   alerting, each region runs its own alert evaluator.
   Global alerts (e.g., 'total error rate across all regions')
   require a global metric rollup -- each region publishes
   its aggregated counts to a central metrics stream."

Q: "What's the total cost estimate?"
A: "Back-of-envelope:
   - 100K metrics/sec * 1.37 bytes * 86400s = ~11.5 GB/day (hot)
   - 10K spans/sec * 500 bytes * 10% sample = ~43 GB/day
   - 50 GB logs/day (compressed ~10:1 = 5 GB stored)
   - Total hot storage: ~60 GB/day * 7 days = ~420 GB
   - Warm: 30 days downsampled = ~100 GB
   - Cold: 1 year in Parquet = ~500 GB
   - Compute: 10 collector nodes, 5 storage nodes, 3 query nodes
   - Kafka: 5 brokers, 3 topics, 100 partitions each"
```

---
---

## PHASE 7: EDGE CASES (2-3 min)

### What this phase signals to the interviewer

```
STAFF SIGNAL: You proactively name failure modes and their mitigations
without waiting for the interviewer to ask. This shows production
experience -- you've been paged at 3am for these issues.
```

### Edge Case 1: Cardinality Explosion

```
SCENARIO:
  A developer deploys a new service version that emits a metric
  with user_id as a tag. Within 30 minutes, the time-series
  store creates 5M new series, memory usage spikes 10x, and
  write latency goes from 5ms to 500ms.

DETECTION:
  The cardinality counter (HyperLogLog per metric name) triggers
  an alert when estimated unique series exceeds the budget.

MITIGATION:
  1. IMMEDIATE: The collector's tag allowlist blocks user_id
     (it's not in the approved list). New points are dropped.
  2. REACTIVE: If the allowlist wasn't configured, the cardinality
     limiter kicks in at 10K unique series and drops new combos.
  3. CLEANUP: Compaction removes the orphaned series from storage
     after their data ages out.

PREVENTION:
  CI/CD pipeline validates metric names and tags against a schema
  registry before deployment. Unapproved tags fail the build.

WHAT TO SAY:
  "Cardinality explosion is the most common production incident
   in observability platforms. At Datadog's scale, a single
   misconfigured metric can generate millions of time series
   in minutes. The defense is layered: allowlists, budgets,
   HyperLogLog estimation, and CI/CD validation."
```

### Edge Case 2: Clock Skew in Distributed Traces

```
SCENARIO:
  Service A reports a span starting at t=1000ms.
  Service B (called by A) reports its span starting at t=995ms.
  This makes it look like B started BEFORE A called it -- which
  is physically impossible.

CAUSE:
  NTP clock drift between servers. Typical drift: 1-10ms.
  In extreme cases (VM migration, NTP outage): 100ms+.

IMPACT:
  - Trace waterfall diagram shows impossible ordering
  - Latency calculations become negative
  - Root cause analysis is confusing

MITIGATION:
  1. USE MONOTONIC CLOCKS for duration measurement within a
     single service (System.nanoTime() in Java, not
     System.currentTimeMillis()). Our Span.finish() uses
     Instant.now() -- in production, we'd use a monotonic source
     for the duration calculation.
  2. TRACE-RELATIVE TIMESTAMPS: store span start times relative
     to the root span's start time, not absolute wall clock time.
     This eliminates cross-service clock drift for display purposes.
  3. SERVER-SIDE TIMESTAMP CORRECTION: the collector compares
     agent-reported timestamps with its own receive time.
     If the difference exceeds a threshold (e.g., 30s), adjust
     the span's timestamp to the collector's clock.
  4. NTP MONITORING: monitor clock drift as a metric. Alert if
     any host drifts more than 50ms from the NTP reference.

WHAT TO SAY:
  "Clock skew is unavoidable in distributed systems. Our trace
   assembler tolerates it by using relative timestamps for display
   and capping the drift correction at 30 seconds. For duration
   measurement, we use monotonic clocks within each service."
```

### Edge Case 3: Alert Storms

```
SCENARIO:
  A database goes down. 50 services that depend on it all start
  failing simultaneously. The alerting engine fires 200+ alerts
  in 10 seconds. The on-call engineer's phone explodes.

CAUSE:
  Every service has independent alert rules (latency, error rate,
  availability). They all trigger at once because they share a
  common dependency.

MITIGATION:
  1. GROUPING: Batch alerts by service and time window. Instead
     of 200 individual alerts, send: "50 services affected,
     root cause: database-primary is down."

  2. INHIBITION: Define inhibition rules. If 'database-down' is
     firing, suppress all 'high-latency' and 'high-error-rate'
     alerts for services that depend on the database.
     Requires the service dependency map from ServiceMapService.

  3. ROOT CAUSE RANKING: Use the service dependency graph to
     identify the most upstream failing service. Present it as
     the likely root cause.

  4. FLOOD CONTROL: Rate-limit notifications to max 10 per minute
     per channel. Buffer the rest and send a summary: "47 more
     alerts suppressed -- see dashboard."

WHAT TO SAY:
  "Alert storms are the #2 operational problem (after cardinality
   explosion). The solution is combining service dependency mapping
   with alert inhibition. Our ServiceMapService builds the
   dependency graph from observed calls -- registerCall(caller,
   callee) -- which the alerting pipeline uses to suppress
   cascading alerts."
```

### Edge Case 4: Cold Start / Bootstrap Problem

```
SCENARIO:
  A new service is deployed. It has no historical data. The
  anomaly detection alerting strategy needs at least 3 data
  points to compute a meaningful stddev. For the first 3
  evaluation cycles (45 seconds), the service has no anomaly
  coverage.

CAUSE:
  Statistical models require a warm-up period. The
  AnomalyDetectionAlertingStrategy returns false (no alert)
  when fewer than 3 points are available.

MITIGATION:
  1. DUAL ALERTING: During the warm-up period, fall back to
     static threshold alerting. Once enough data is collected,
     switch to anomaly detection.

  2. SEEDED BASELINES: Pre-populate baselines from a staging
     environment or from similar services (e.g., if api-gateway-v2
     is a new version of api-gateway-v1, seed its baseline from
     v1's historical data).

  3. PROGRESSIVE SENSITIVITY: Start with a high stddev multiplier
     (e.g., 4.0 = only extreme anomalies) and gradually reduce
     it to the target (2.0) over the first hour as more data
     accumulates.

WHAT TO SAY:
  "Cold start affects anomaly detection, dashboards (no historical
   trend to compare), and service maps (no edges yet). We handle
   it by dual alerting during warm-up and seeding baselines from
   similar services."
```

### Edge Case 5: High-Cardinality Trace Tags

```
SCENARIO:
  A service starts propagating user_id in span tags. With 10M users,
  every span now carries a high-cardinality tag. The trace storage
  index grows 100x, and tag-based queries become slow.

CAUSE:
  Unlike metrics (where cardinality multiplies time series), trace
  tag cardinality affects index size and query performance.
  A search for "tag:user_id=12345" must scan the entire tag index.

MITIGATION:
  1. SEPARATE TAG TIERS: distinguish between indexed tags (low
     cardinality: service, operation, status) and stored-only tags
     (high cardinality: user_id, request_id). Stored-only tags are
     visible when viewing a specific trace but not searchable.
  2. TAG BUDGET PER SPAN: limit to 20 indexed tags per span.
     Additional tags are stored as unindexed baggage.
  3. BLOOM FILTERS: for high-cardinality indexed tags, use bloom
     filters to quickly eliminate shards that don't contain the
     target value before doing a full scan.

WHAT TO SAY:
  "Trace tag cardinality is a different problem than metric tag
   cardinality. In metrics, it multiplies time series. In traces,
   it bloats the index. The solution is tiered indexing: low-card
   tags are fully indexed, high-card tags are stored but not indexed."
```

### Edge Case 6: Collector Failure and Data Loss

```
SCENARIO:
  A trace collector instance crashes. It had 5,000 spans buffered
  in memory that were not yet flushed to Kafka.

CAUSE:
  In-memory buffering without checkpointing.

MITIGATION:
  1. WAL at the collector: write spans to a local WAL before
     buffering. On restart, replay the WAL.
  2. Agent-side retry: the agent keeps a copy of sent data until
     it receives an ACK from the collector. If the collector
     dies before ACKing, the agent retransmits to another
     collector instance.
  3. Kafka ACK semantics: use acks=all (wait for all replicas)
     so data is durable once the collector receives Kafka's ACK.

WHAT TO SAY:
  "Data loss at the collector is the most impactful failure because
   it loses data from ALL services routed to that collector. The
   defense is redundancy: multiple collector instances, agent-side
   retry with local buffering, and WAL at the collector for crash
   recovery."
```

### Edge Case 7: Log Correlation Failure

```
SCENARIO:
  A service emits logs WITHOUT the traceId field. When an engineer
  tries to correlate logs with a trace, the logs for that service
  are missing from the trace timeline.

CAUSE:
  The service wasn't properly instrumented. The logging library
  doesn't have the OpenTelemetry context propagation hook installed.

MITIGATION:
  1. LOG ENRICHMENT AT COLLECTOR: the log collector can attempt
     to inject traceId by matching the log's timestamp and
     service name against recent spans. This is best-effort.
  2. MANDATORY CORRELATION CHECK: a CI/CD gate that verifies
     all log statements include the traceId field. Fail the
     build if the logging framework is not wired to the tracing
     context.
  3. SIDECAR INJECTION: for containerized deployments, a sidecar
     proxy (like Envoy) can inject traceId into log entries
     by intercepting the service's stdout/stderr and enriching
     each line with the active trace context.

WHAT TO SAY:
  "Correlation breaks when instrumentation is inconsistent.
   The safest approach is automatic instrumentation via agents
   or sidecars, not relying on developers to manually add
   traceId to every log statement."
```

### Edge Case 8: Query of Death

```
SCENARIO:
  A user issues a dashboard query that selects ALL time series
  for the last 30 days with no tag filters. This returns 10M
  series * 30 days * 86400 points/day = trillions of data points.
  The query node runs out of memory and crashes.

MITIGATION:
  1. QUERY LIMITS: max time range (7 days for raw data), max
     series count (10K), max data points (1M per query).
  2. AUTOMATIC DOWNSAMPLING: if the query range is > 6 hours,
     automatically serve from the 5-minute rollup, not raw data.
  3. QUERY TIMEOUT: kill queries that run longer than 30 seconds.
  4. COST ESTIMATION: before executing, estimate the query cost
     (series count * time range * point density). If it exceeds
     a threshold, reject with a suggestion to add filters.

WHAT TO SAY:
  "Unbounded queries are a constant threat. The query engine must
   enforce limits and automatically downsample. This is why
   Prometheus has a max_samples limit and Grafana has a max
   data points setting."
```

---
---

## APPENDIX A-0: SERVICE DEPENDENCY MAPPING

How the service map is built and used for root cause analysis.

```
    SERVICE DEPENDENCY MAP
    ================================================================

    Built automatically from trace data by ServiceMapService:

    Every time a span shows Service A calling Service B, we call:
      serviceMapService.registerCall("service-a", "service-b", latencyMs, success)

    This builds an adjacency list with rolling statistics per edge:
      - Total requests between the pair
      - Error rate (failed calls / total calls)
      - Average latency

    VISUALIZATION (from ServiceMapService.printServiceMap):

      \-- api-gateway
          |-- order-service
          |   |-- database
          |   \-- cache
          |-- user-service
          |   \-- database
          \-- notification-service
              \-- email-provider

    USE CASES:

    1. ROOT CAUSE ANALYSIS:
       When multiple services are failing, traverse the dependency
       graph to find the deepest (most upstream) failing node.
       If database is failing -> order-service and user-service
       fail -> api-gateway fails. Root cause = database.

    2. BLAST RADIUS ESTIMATION:
       "If order-service goes down, which services are affected?"
       Answer: getDependents("order-service") = ["api-gateway"]
       Plus transitive dependents of api-gateway.

    3. CHANGE RISK ASSESSMENT:
       "If I deploy a new version of database, what's the impact?"
       Answer: all services with a direct or transitive dependency
       on database. Used by CI/CD to determine test scope.

    4. ALERT INHIBITION:
       If database has an active alert, suppress alerts for all
       services that depend on database (they're likely cascading).

    5. SLO DEPENDENCY CHAIN:
       If api-gateway has a 99.9% availability SLO, and it depends
       on order-service and user-service, each must have >= 99.95%
       availability to meet the gateway's SLO (assuming independence).

    OUR CODEBASE:
      ServiceMapService maintains:
        - serviceNodes: Map<String, ServiceNode> (vertices)
        - edgeStats: Map<String, long[]> (edge weights)
      ServiceNode tracks:
        - dependencies: Set<String> (downstream services)
        - dependents: Set<String> (upstream callers)
        - Rolling stats: requestCount, errorRate, avgLatency
```

---

## APPENDIX A: DESIGN PATTERNS CHEAT SHEET

Patterns used in the observability platform and where they appear.

```
+------------------+----------------------------+-----------------------------------+
| Pattern          | Where in Codebase          | Why                               |
+==================+============================+===================================+
| Strategy         | SamplingStrategy           | Swap head/tail/rate-limited       |
|                  |   HeadBasedSampling        | sampling at runtime without       |
|                  |   TailBasedSampling        | changing the SamplingEngine.      |
|                  |   RateLimitedSampling      |                                   |
|                  |                            |                                   |
|                  | AlertingStrategy           | Swap threshold vs anomaly         |
|                  |   ThresholdAlerting        | detection without changing        |
|                  |   AnomalyDetectionAlerting | the AlertService.                 |
|                  |                            |                                   |
|                  | AggregationStrategy        | Swap rate vs percentile           |
|                  |   RateAggregation          | aggregation for metric queries.   |
|                  |   PercentileAggregation    |                                   |
+------------------+----------------------------+-----------------------------------+
| Builder          | Metric.Builder             | Metrics have many optional fields |
|                  | Span.Builder               | (description, unit, tags, data    |
|                  |                            | points). Builder avoids telescoping|
|                  |                            | constructors.                     |
+------------------+----------------------------+-----------------------------------+
| Repository       | MetricRepository           | Decouple storage implementation   |
|                  | TraceRepository            | from business logic. Swap         |
|                  | LogRepository              | InMemory for Cassandra/ES without |
|                  | AlertRepository            | touching service code.            |
+------------------+----------------------------+-----------------------------------+
| Factory          | AppConfig                  | Centralized object wiring.        |
|                  |                            | Creates all services, engines,    |
|                  |                            | strategies in one place.          |
+------------------+----------------------------+-----------------------------------+
| Facade           | ObservabilityService       | Single entry point that           |
|                  | ObservabilityController    | coordinates MetricService,        |
|                  |                            | TracingService, LogService,       |
|                  |                            | AlertService, DashboardService.   |
+------------------+----------------------------+-----------------------------------+
| Observer         | Alert state transitions    | When an alert transitions from    |
|                  | (FIRING -> RESOLVED)       | FIRING to RESOLVED, notify the    |
|                  |                            | notification pipeline without     |
|                  |                            | tight coupling.                   |
+------------------+----------------------------+-----------------------------------+
| Pipeline /       | Collector -> Kafka ->      | Each stage is independent,        |
| Chain of         | Writer -> Store            | buffers between stages, can be    |
| Responsibility   |                            | scaled independently.             |
+------------------+----------------------------+-----------------------------------+
```

---

## APPENDIX B: COMPLEXITY CHEAT SHEET

Time and space complexity of key operations.

```
+-----------------------------------+------------------+------------------+
| Operation                         | Time             | Space            |
+===================================+==================+==================+
| Metric point ingestion            | O(log n) per     | O(n) for n       |
| (TimeSeriesStore.store)           | point (TreeMap   | data points      |
|                                   | insert)          |                  |
+-----------------------------------+------------------+------------------+
| Range query                       | O(log n + k)     | O(k) for k       |
| (TimeSeriesStore.query)           | (subMap + scan)  | result points    |
+-----------------------------------+------------------+------------------+
| Downsample                        | O(n)             | O(n/b) where b   |
| (TimeSeriesStore.downsample)      | (scan all points)| = bucket factor  |
+-----------------------------------+------------------+------------------+
| Span ingestion                    | O(1) amortized   | O(s) for s       |
| (TraceAssembler.addSpan)          | (HashMap put)    | pending spans    |
+-----------------------------------+------------------+------------------+
| Trace assembly                    | O(s) for s spans | O(s)             |
| (TraceAssembler.assembleTrace)    | (find root, add) |                  |
+-----------------------------------+------------------+------------------+
| Head-based sampling               | O(1)             | O(1)             |
| (hash + modulo)                   |                  |                  |
+-----------------------------------+------------------+------------------+
| Tail-based sampling               | O(1)             | O(1) per call    |
| (baggage lookup)                  |                  | O(s) for buffer  |
+-----------------------------------+------------------+------------------+
| Alert rule evaluation             | O(r * p) for r   | O(r) for rule    |
| (AlertService.evaluateRules)      | rules, p points  | state tracking   |
+-----------------------------------+------------------+------------------+
| Anomaly detection (z-score)       | O(p) for p       | O(1) running     |
| (mean, stddev, compare)           | data points      | stats            |
+-----------------------------------+------------------+------------------+
| Service map edge insert           | O(1) amortized   | O(V + E) for     |
| (ServiceMapService.registerCall)  |                  | vertices + edges |
+-----------------------------------+------------------+------------------+
| Service map topology query        | O(V)             | O(V + E)         |
| (ServiceMapService.getTopology)   |                  |                  |
+-----------------------------------+------------------+------------------+
| HyperLogLog cardinality estimate  | O(1) per insert  | O(16KB) per      |
|                                   | O(1) per query   | metric name      |
+-----------------------------------+------------------+------------------+
```

---

## APPENDIX C: QUICK-FIRE Q&A BANK

Rapid-fire questions the interviewer might ask at any point.

```
Q: "How is this different from Prometheus?"
A: "Prometheus is a single-node metrics engine with a local TSDB.
   Our design adds: (1) distributed ingestion via Kafka, (2) traces
   and logs alongside metrics, (3) horizontal storage sharding,
   (4) tail-based sampling. Prometheus is a subset of what we're
   building -- it's the metrics pillar only."

Q: "Why not just use the ELK stack?"
A: "ELK (Elasticsearch + Logstash + Kibana) is optimized for logs.
   It's not designed for high-cardinality metric time-series data
   (Elasticsearch's inverted index doesn't compress timestamps
   like a TSDB) or distributed tracing (no native trace assembly).
   You CAN add APM to Elastic, but it's bolted on, not native."

Q: "What's OpenTelemetry and how does it relate?"
A: "OpenTelemetry is the vendor-neutral standard for telemetry
   collection. It defines: (1) APIs for instrumenting code,
   (2) SDKs that implement the APIs, (3) the OTLP wire protocol,
   (4) the OTel Collector for processing and routing. Our platform
   would accept OTLP as the ingestion format, making it compatible
   with any OTel-instrumented service."

Q: "How do you correlate metrics, traces, and logs?"
A: "Via three shared identifiers:
   1. traceId -- embedded in logs and linked to trace spans
   2. service name -- shared across all three pillars
   3. timestamp -- approximate correlation via time window
   When investigating an incident, the workflow is:
   metrics -> identify anomalous service -> find traces with
   errors in that time window -> drill into logs for that traceId."

Q: "What's the difference between RED and USE methodologies?"
A: "RED (Rate, Errors, Duration) is for request-driven services:
   how fast, how often failing, how slow. USE (Utilization,
   Saturation, Errors) is for resources: how full, how overloaded,
   how broken. Use RED for microservices, USE for infrastructure
   (CPU, memory, disk, network)."

Q: "How do you handle metric name collisions across services?"
A: "Namespace by service name. The full metric identity is:
   (service_name, metric_name, tags). Two services can both emit
   'http_request_duration_seconds' without collision because the
   service tag differentiates them."

Q: "What query language would you use?"
A: "A PromQL-like DSL for metrics:
   rate(http_requests_total{service='api', status='500'}[5m])
   For traces: filter by service, operation, duration, status.
   For logs: full-text search with structured field filters.
   In production, consider supporting both PromQL (metrics) and
   TraceQL (Tempo's query language) or a unified query layer."

Q: "How do you handle schema evolution for metrics?"
A: "Metric schemas evolve when teams add/remove tags or change
   metric names. We version metric schemas in a registry. When
   a new version is detected, the ingestion pipeline applies a
   migration (rename, drop, or map old tags to new). Backward
   compatibility: queries against old schemas are rewritten to
   the current schema transparently."

Q: "What's the blast radius of a bad deploy to the observability
    platform itself?"
A: "We monitor the observability platform WITH itself (meta-monitoring)
   plus an independent health check system (simple ping/pong
   with external alerting). The blast radius is limited by:
   1. Per-pillar isolation (metrics crash doesn't affect traces)
   2. Kafka buffering (storage outage doesn't lose ingestion)
   3. Agent-side ring buffers (collector outage doesn't block apps)
   The worst case is losing the query tier, which means dashboards
   go blank but data is still being ingested and stored."
```

---

## APPENDIX D: RED/USE DASHBOARD REFERENCE

Reference for the two standard dashboard methodologies.

```
    RED METHOD (for request-driven services)
    ================================================================

    +------------+--------------------------------------------------+
    | R - Rate   | Requests per second                              |
    |            | rate(http_requests_total[5m])                     |
    |            | Panel: line graph, grouped by service             |
    +------------+--------------------------------------------------+
    | E - Errors | Error rate as a percentage                       |
    |            | rate(http_requests_total{status=~"5.."}[5m])      |
    |            | / rate(http_requests_total[5m]) * 100             |
    |            | Panel: line graph with red threshold line at 1%   |
    +------------+--------------------------------------------------+
    | D-Duration | Latency percentiles (p50, p95, p99)              |
    |            | histogram_quantile(0.99,                          |
    |            |   rate(http_request_duration_bucket[5m]))         |
    |            | Panel: line graph with p50, p95, p99 lines       |
    +------------+--------------------------------------------------+

    USE METHOD (for infrastructure resources)
    ================================================================

    +----------------+----------------------------------------------+
    | U-Utilization  | Fraction of resource capacity in use         |
    |                | CPU: avg(cpu_usage_percent) by host          |
    |                | Memory: mem_used / mem_total                 |
    |                | Disk: disk_used / disk_total                 |
    |                | Panel: gauge (0-100%) with green/yellow/red  |
    +----------------+----------------------------------------------+
    | S-Saturation   | Degree to which resource has extra work      |
    |                | queued that it can't service                 |
    |                | CPU: load average / num_cores                |
    |                | Disk: IO queue depth                         |
    |                | Network: TCP retransmission rate             |
    |                | Panel: line graph with threshold at 80%      |
    +----------------+----------------------------------------------+
    | E-Errors       | Count of error events                        |
    |                | Disk: SMART errors, IO errors                |
    |                | Network: packet drops, CRC errors            |
    |                | Panel: counter with alert at > 0             |
    +----------------+----------------------------------------------+

    DASHBOARD LAYOUT:
    +---------+---------+---------+
    |  Rate   | Errors  |Duration |    <-- RED (top row)
    +---------+---------+---------+
    |  Util   |  Satur  | Errors  |    <-- USE (bottom row)
    +---------+---------+---------+

    TALKING POINT:
    "RED dashboards answer 'is the service healthy?' while USE
     dashboards answer 'is the infrastructure healthy?' Together
     they cover both application-level and infrastructure-level
     observability. Our DashboardService computes these from the
     MetricAggregator and serves them via the QueryEngine."
```

---

## APPENDIX E: END-TO-END TRACE WALKTHROUGH

Numbered flow showing how a single request generates telemetry
across all three pillars.

```
    END-TO-END OBSERVABILITY FOR ONE REQUEST
    ================================================================

    Step | Component                 | Action                          | Pillar
    -----+---------------------------+---------------------------------+--------
      1  | API Gateway               | Receives GET /api/orders/123    | --
      2  | API Gateway Agent         | Generates traceId=abc123,       | TRACE
         |                           | creates root span (span-1)      |
      3  | API Gateway Agent         | Increments counter:             | METRIC
         |                           | http_requests_total{method=GET} |
      4  | API Gateway               | Logs: "Received request"        | LOG
         |                           | {traceId=abc123, spanId=span-1} |
      5  | API Gateway               | Calls Order Service with header:| --
         |                           | traceparent: 00-abc123-span1-01 |
      6  | Order Service Agent       | Extracts traceparent, creates   | TRACE
         |                           | child span (span-2, parent=span-1)
      7  | Order Service             | Queries database                | --
      8  | Order Service Agent       | Records db.query duration       | METRIC
         |                           | as a histogram observation      |
      9  | Order Service             | Logs: "Order 123 found"         | LOG
         |                           | {traceId=abc123, spanId=span-2} |
     10  | Order Service             | Returns response to API Gateway | --
     11  | Order Service Agent       | Finishes span-2, reports to     | TRACE
         |                           | trace collector                 |
     12  | API Gateway Agent         | Finishes span-1, reports to     | TRACE
         |                           | trace collector                 |
     13  | API Gateway Agent         | Records request duration:       | METRIC
         |                           | http_request_duration_seconds   |
         |                           | = 0.042s                        |
     14  | Metric Collector          | Receives metric points, batches | --
     15  | Trace Collector           | Receives spans, applies         | --
         |                           | SamplingEngine.shouldSample()   |
     16  | Log Collector             | Receives structured log entries | --
     17  | Kafka                     | Routes to metrics.raw,          | --
         |                           | spans.raw, logs.raw topics      |
     18  | MetricWriter              | Consumes from metrics.raw,      | METRIC
         |                           | writes to TimeSeriesStore       |
     19  | TraceWriter               | Consumes from spans.raw,        | TRACE
         |                           | TraceAssembler.addSpan()        |
     20  | TraceAssembler            | Assembles trace abc123 from     | TRACE
         |                           | span-1 + span-2                 |
     21  | LogWriter                 | Consumes from logs.raw,         | LOG
         |                           | indexes by traceId + service    |
     22  | AlertService              | Next evaluation tick: checks    | ALERT
         |                           | http_request_duration_seconds   |
         |                           | against threshold rule          |
     23  | DashboardService          | Updates RED dashboard:          | QUERY
         |                           | rate +1, duration p99 updated   |

    CORRELATION KEYS:
      - traceId (abc123) links: span-1, span-2, and both log entries
      - service name links: metrics and traces to the same service
      - timestamp links: approximate correlation across all pillars
```

---

## APPENDIX F: COMPARISON WITH REAL SYSTEMS

Use this to answer "how does your design compare to X?" questions.

```
+-------------------+-------------------+-------------------+-------------------+
| Feature           | Our Design        | Datadog           | Prometheus +      |
|                   |                   |                   | Grafana           |
+===================+===================+===================+===================+
| Metrics storage   | Bucketed TreeMap  | Custom TSDB       | Prometheus TSDB   |
|                   | (LSM-tree model)  | (sharded, multi-  | (local, single    |
|                   |                   | tenant)           | node) + Thanos    |
+-------------------+-------------------+-------------------+-------------------+
| Tracing           | TraceAssembler    | APM with tail-    | Jaeger or Tempo   |
|                   | + SamplingEngine  | based sampling    | (separate system) |
|                   | (pluggable strat) | (built-in)        |                   |
+-------------------+-------------------+-------------------+-------------------+
| Logging           | LogProcessor +    | Log Management    | Loki (log         |
|                   | structured index  | (full indexing)   | aggregation,      |
|                   |                   |                   | label-based)      |
+-------------------+-------------------+-------------------+-------------------+
| Correlation       | traceId across    | Unified: metrics  | Manual: separate  |
|                   | all three pillars | traces, logs in   | UIs for each      |
|                   |                   | one UI            | pillar            |
+-------------------+-------------------+-------------------+-------------------+
| Alerting          | AlertService +    | Monitors with     | Alertmanager      |
|                   | Strategy pattern  | anomaly detection | (threshold-based, |
|                   | (threshold +      | (ML-based)        | PromQL rules)     |
|                   | anomaly stddev)   |                   |                   |
+-------------------+-------------------+-------------------+-------------------+
| Sampling          | Head, tail, rate- | Tail-based        | Not applicable    |
|                   | limited (Strategy | (Datadog Trace    | (Jaeger has head  |
|                   | pattern, runtime  | Agent)            | -based only)      |
|                   | swappable)        |                   |                   |
+-------------------+-------------------+-------------------+-------------------+
| Service map       | ServiceMapService | Live service map  | Service graph     |
|                   | (adjacency list,  | from trace data   | plugin (limited)  |
|                   | edge stats)       |                   |                   |
+-------------------+-------------------+-------------------+-------------------+
| Query language    | PromQL-like DSL   | Custom query      | PromQL (metrics)  |
|                   |                   | language          | LogQL (logs)      |
|                   |                   |                   | TraceQL (traces)  |
+-------------------+-------------------+-------------------+-------------------+
| Ingestion model   | Push via Kafka    | Push (Datadog     | Pull (Prometheus  |
|                   |                   | Agent)            | scrapes targets)  |
+-------------------+-------------------+-------------------+-------------------+

WHAT TO SAY:

"Our design is closest to Datadog's architecture -- a unified platform
with all three pillars, push-based ingestion, tail-based sampling,
and anomaly detection in alerting. The key difference is that Datadog
is SaaS (multi-tenant, managed) while ours is self-hosted.

Prometheus + Grafana is the open-source alternative but requires
assembling multiple independent systems (Prometheus for metrics,
Jaeger for traces, Loki for logs, Alertmanager for alerts) and lacks
native cross-pillar correlation.

Honeycomb takes a different approach -- it stores raw events (not
pre-aggregated metrics) and uses columnar storage for fast ad-hoc
queries. This is more flexible but more expensive at scale."
```

---

## APPENDIX G: WHITEBOARD DRAWING ORDER

The exact sequence to draw on the whiteboard during the interview.
Practice this order so your drawing builds up naturally.

```
DRAWING 1 (Phase 2, minute 3):
  Four zones as horizontal bands.
  Start with Zone 1 (ingestion) at the top:
    Four service agents -> three collectors (metric, trace, log)
  Then Zone 2: three Kafka topics -> three storage backends
  Then Zone 3: query engine + dashboard service
  Then Zone 4: alert service + notification router
  Total drawing time: 2.5 minutes.

DRAWING 2 (Phase 3, minute 10):
  On a fresh area, draw the write path:
    Collector -> Write Buffer -> WAL -> In-Memory (TreeMap)
    -> Flush -> Immutable Block -> Compaction
  Below it, write the compression note:
    "Gorilla: delta-of-delta timestamps, XOR values = 1.37 bytes/point"
  Total drawing time: 2 minutes.

DRAWING 3 (Phase 3, minute 14):
  Below the write path, draw the cardinality control flow:
    Incoming Point -> Tag Allowlist -> Cardinality Counter (HLL)
    -> TimeSeriesStore (or DROP)
  Total drawing time: 1 minute.

DRAWING 4 (Phase 4, minute 18):
  Three service boxes with traceparent header arrows between them:
    Service A (root span) -> Service B (child) -> Service C (child)
  Below: show the W3C traceparent header format
  Total drawing time: 1.5 minutes.

DRAWING 5 (Phase 4, minute 22):
  Sampling strategy comparison as a 3-column table:
    Head-Based | Tail-Based | Rate-Limited
    Pros/Cons for each
  Total drawing time: 1 minute.

DRAWING 6 (Phase 5, minute 25):
  Alert pipeline flow:
    Rule Eval -> Strategy -> State Machine -> Dedup -> Group
    -> Silence -> Inhibit -> Route by severity
  Total drawing time: 1.5 minutes.

DRAWING 7 (Phase 6, minute 30):
  Storage tiering diagram:
    HOT (SSD, 7d) -> WARM (HDD, 30d) -> COLD (S3, 1yr) -> DELETE
  Total drawing time: 30 seconds.
```

---

## APPENDIX H: ANTI-PATTERNS TO AVOID

Things that will hurt your interview score.

```
ANTI-PATTERN 1: "We'll just store everything in Elasticsearch."
  WHY BAD: ES is not designed for time-series metrics. Its inverted
  index is optimized for text search, not numeric range scans.
  Time-series DBs use columnar storage and compression (Gorilla)
  that ES lacks.
  INSTEAD: "For metrics, I'd use a purpose-built TSDB. For logs,
  Elasticsearch or Loki. For traces, a span-indexed store."

ANTI-PATTERN 2: "We don't need sampling -- just store everything."
  WHY BAD: At 10K spans/sec, storing all traces costs ~$50K/month.
  With 10% head-based + tail-based error sampling, it's ~$8K/month
  with no loss of debugging capability for errors.
  INSTEAD: "I'd use a hybrid sampling approach: head-based for
  baseline, tail-based for errors and latency outliers."

ANTI-PATTERN 3: Not mentioning cardinality.
  WHY BAD: This is the #1 operational issue for observability platforms.
  Every interviewer who has run Prometheus or Datadog in production
  has dealt with cardinality explosions.
  INSTEAD: Proactively mention it during the time-series deep dive.

ANTI-PATTERN 4: Treating metrics, traces, and logs as independent.
  WHY BAD: The value of a unified observability platform is
  CORRELATION. If you design three separate systems with no shared
  context, you've just described Prometheus + Jaeger + ELK.
  INSTEAD: "All three pillars share traceId and service name for
  cross-pillar correlation."

ANTI-PATTERN 5: Spending all your time on one pillar.
  WHY BAD: The interviewer wants to see breadth across all three
  pillars AND depth in one. Don't spend 25 minutes on metrics
  and 0 on tracing.
  INSTEAD: Follow the phase structure. 8-10 min on metrics deep
  dive, 5-7 min on tracing deep dive, touch on logs briefly.

ANTI-PATTERN 6: Ignoring the alerting pipeline.
  WHY BAD: Observability without alerting is a dashboard nobody
  looks at. The alerting pipeline shows you understand operational
  workflows (on-call, escalation, silencing).
  INSTEAD: Spend 3-5 minutes on alerting. Mention dedup, grouping,
  inhibition, and escalation.

ANTI-PATTERN 7: "We'll use machine learning for anomaly detection."
  WHY BAD: Vague. ML is not a solution, it's a category. The
  interviewer wants to know WHICH algorithm and WHY.
  INSTEAD: "For anomaly detection, I'd start with z-score based
  detection (our AnomalyDetectionAlertingStrategy uses stddev
  multiplier of 2.0). For seasonal patterns, Holt-Winters.
  ML-based approaches (LSTM, Prophet) are for V2."

ANTI-PATTERN 8: Not considering push vs. pull.
  WHY BAD: Shows you haven't thought about the ingestion model.
  Prometheus uses pull; Datadog uses push. Both are valid but
  have different tradeoffs.
  INSTEAD: "I'd use push for traces and logs (event-driven) and
  pull for metrics (rate-controlled). In practice, OpenTelemetry
  supports both."
```

---

## APPENDIX I: INTERVIEW TIMING CHEAT SHEET

Quick reference for pacing yourself during the 35-minute interview.

```
    TIME CHECK    PHASE                     IF BEHIND
    ================================================================
    0:00-2:30     Phase 1: Clarify          Skip Q10, jump to scope
    2:30-3:00     Write scope table         Keep it to 6 items max
    3:00-9:00     Phase 2: HLD + diagram    Draw 4 zones, 2 minutes
    9:00-10:00    Transition to deep dive   "Let me go deep on..."
    10:00-19:00   Phase 3: Time-Series      Cut compression section
    19:00-20:00   Transition to tracing     "Now let me shift to..."
    20:00-26:00   Phase 4: Dist. Tracing    Cut storage schema
    26:00-27:00   Transition to alerting    "For alerting..."
    27:00-31:00   Phase 5: Alerting         Cut dedup/grouping detail
    31:00-33:00   Phase 6: Scaling          Hit push/pull + tiering
    33:00-35:00   Phase 7: Edge Cases       Pick top 2-3, skip rest

    KEY PRINCIPLE:
    If the interviewer is engaged and asking follow-ups in a phase,
    STAY IN THAT PHASE. Their engagement = points. Don't cut them
    off to follow your script. Adapt the timing.

    IF YOU HAVE EXTRA TIME (rare):
    - Discuss OpenTelemetry ecosystem
    - Explain t-digest vs DDSketch for percentiles
    - Walk through the RED/USE dashboard layout
    - Discuss multi-tenancy challenges
```

---

*End of walkthrough. Total rehearsal time: ~50 minutes for first read,
~25 minutes for subsequent reviews. Target: can deliver any phase
from memory after 3 rehearsal passes.*
