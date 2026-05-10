# CAP Theorem & Distributed Tradeoffs in the Observability Platform (Datadog/Grafana-like)

> Interview-ready reference for a Senior Java developer.
> An observability platform has a SPLIT CAP requirement: CP for alerting
> (must not miss critical alerts) and AP for metric/trace/log ingestion
> (accept writes even if stale, never block producers). This split is THE
> key insight interviewers look for.

---

## Table of Contents

| Section | Key Point |
|---------|-----------|
| Split CAP Requirement | CP for alerting, AP for ingestion, AP for trace collection |
| CP for Alerting | Must not miss critical alerts; prefer unavailability over stale evaluation |
| AP for Metric Ingestion | Accept writes even if partition isolates some storage nodes |
| AP for Trace Collection | Drop before block; sampling is built-in lossy behavior |
| Push vs Pull Metrics | Pull = CP-leaning (controlled), Push = AP-leaning (decoupled) |
| Sampling Tradeoffs | Head vs tail vs adaptive; completeness vs cost |
| Storage Cost vs Query Speed | Columnar vs row, compression vs indexing |
| PACELC Analysis | When no partition: latency vs consistency choices |
| Network Partition Scenarios | Real-world failure modes and responses |
| Industry Comparison | Datadog, Grafana Cloud, New Relic architectures |
| Interview Q&A | Ready-to-use answers |

---

## Split CAP Requirement -- The Core Insight

### The Key Argument

```
  +----------------------------------------------------------------------+
  |  THE KEY INSIGHT: An observability platform has a SPLIT CAP requirement|
  |  Alerting = CP. Ingestion = AP. Know which is which.                 |
  +----------------------------------------------------------------------+
  |                                                                      |
  |         Consistency (C)                                              |
  |            /\                                                        |
  |           /  \                                                       |
  |          / CP \                                                      |
  |         /      \     <--- Alert Evaluation (must be accurate)        |
  |        / ALERT  \    <--- Alert State Transitions (FIRING->RESOLVED) |
  |       / PIPELINE \   <--- Notification Dedup (don't double-page)     |
  |      / DASHBOARD  \  <--- Dashboard during incident (correctness)    |
  |     /   QUERIES    \                                                 |
  |    /________________\                                                |
  |  Availability (A) --- Partition Tolerance (P)                        |
  |                                                                      |
  |          AP                                                          |
  |         /  \                                                         |
  |        /    \    <--- Metric Ingestion (never block producers)       |
  |       / INGEST\  <--- Trace Collection (drop before block)          |
  |      / LOG     \  <--- Log Shipping (buffer and retry)              |
  |     / WRITE    \  <--- Dashboard during normal ops (stale OK)       |
  |    /____________\                                                    |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Why This Split Exists

```
  +----------------------------------------------------------------------+
  |  THE FUNDAMENTAL TENSION                                              |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  INGESTION PATH (AP):                                                |
  |  ----------------------------------------------------------------   |
  |  100,000 metric points per second from 500 microservices.            |
  |  If the TSDB is temporarily partitioned or slow:                     |
  |    WRONG: reject metrics -> services get backpressure -> cascading   |
  |           failure -> the MONITORING system causes the OUTAGE         |
  |    RIGHT: buffer in Kafka, accept with eventual consistency          |
  |           -> some data arrives 30 seconds late, dashboards are stale |
  |           -> but services are healthy and unblocked                  |
  |                                                                      |
  |  ALERTING PATH (CP):                                                 |
  |  ----------------------------------------------------------------   |
  |  Alert rule: "error_rate > 10% for 5 minutes -> page on-call"       |
  |  If the alert evaluator reads stale data:                            |
  |    WRONG: stale cache says error_rate = 2%, actual is 15%            |
  |           -> alert doesn't fire for 30 more seconds                  |
  |           -> 30 seconds of undetected production incident            |
  |    RIGHT: query TSDB directly, even if slower                        |
  |           -> accurate alert evaluation, fire immediately             |
  |           -> if TSDB is unreachable, alert on "TSDB unreachable"    |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Component-Level CAP Decisions

```
  +----------------------------------------------------------------------+
  |  COMPONENT-LEVEL CAP DECISIONS                                        |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Component                 | CAP | Why                                |
  |  --------------------------+-----+------------------------------------+
  |  Metric Ingestion          | AP  | Never block producers. Buffer in  |
  |  (MetricService.ingest())  |     | Kafka. Accept eventual delivery.  |
  |                            |     | Missing 10s of metrics is better  |
  |                            |     | than crashing the producer.       |
  |  --------------------------+-----+------------------------------------+
  |  Trace Collection          | AP  | Traces are already sampled (10%). |
  |  (TracingService)          |     | Losing a few more during partition|
  |                            |     | is acceptable. Drop > block.      |
  |  --------------------------+-----+------------------------------------+
  |  Log Shipping              | AP  | Logs are shipped async with retry.|
  |  (LogService.ingest())     |     | Fluent Bit buffers on disk during |
  |                            |     | partition. Eventual delivery OK.  |
  |  --------------------------+-----+------------------------------------+
  |  Alert Evaluation          | CP  | Query MUST hit current TSDB data. |
  |  (AlertService.evaluate()) |     | NEVER use cached query results.   |
  |                            |     | Prefer unavailability (skip eval  |
  |                            |     | cycle) over stale evaluation.     |
  |  --------------------------+-----+------------------------------------+
  |  Alert State Transitions   | CP  | FIRING -> RESOLVED must be atomic.|
  |  (AlertStatus enum)        |     | Two alert evaluators must agree   |
  |                            |     | on current state (write-through   |
  |                            |     | to Redis, read from Redis).       |
  |  --------------------------+-----+------------------------------------+
  |  Notification Dedup        | CP  | Must not double-page on-call.     |
  |  (AlertNotificationDedup)  |     | Redis SETNX ensures exactly-one   |
  |                            |     | notification per interval.        |
  |  --------------------------+-----+------------------------------------+
  |  Dashboard Queries         | AP  | Stale dashboard data (30s) is     |
  |  (DashboardService)        |     | acceptable. Cache hit returns     |
  |                            |     | stale data immediately. User      |
  |                            |     | sees "last updated: 30s ago".     |
  |  --------------------------+-----+------------------------------------+
  |  Service Map               | AP  | Topology changes on deployment,   |
  |  (ServiceMapService)       |     | not at runtime. 5-minute stale   |
  |                            |     | map is fine. Cache aggressively.  |
  |  --------------------------+-----+------------------------------------+
  |  Metric Metadata           | AP  | Metric names/types change on      |
  |  (metric name autocomplete)|     | deployment only. 10-minute stale |
  |                            |     | metadata is acceptable.           |
  |  --------------------------+-----+------------------------------------+
  |  Time-Series Storage       | AP  | TSDB accepts writes even if some  |
  |  (TimeSeriesStore)         |     | replicas are unreachable. Data    |
  |                            |     | converges eventually. Queries     |
  |                            |     | may return slightly incomplete    |
  |                            |     | results during partition.         |
  |  --------------------------+-----+------------------------------------+
```

---

## CP for Alerting -- Must Not Miss Critical Alerts

### Why CP Is Non-Negotiable for Alerting

```
  +----------------------------------------------------------------------+
  |  THE COST OF GETTING ALERTING WRONG                                   |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Scenario 1: Stale Cache Masks Outage                                |
  |  ----------------------------------------------------------------   |
  |  Alert rule: "error_rate{service=payment} > 5% for 5 minutes"       |
  |  14:30:00 - Error rate is 2% (normal). Cache populated.             |
  |  14:30:15 - Payment gateway crashes. Error rate jumps to 80%.       |
  |  14:30:30 - Alert evaluator reads cached result: 2% (STALE!)        |
  |  14:30:45 - Alert evaluator reads cached result: 2% (STILL STALE!)  |
  |  14:31:00 - Cache TTL expires. Fresh query returns 80%.             |
  |  14:31:00 - Alert starts pending (needs 5 minutes above threshold)  |
  |  14:36:00 - Alert fires. On-call paged.                             |
  |  TOTAL DELAY: 6 minutes (30s cache + 5m pending)                    |
  |  WITHOUT CACHE: 5m 15s (15s scrape + 5m pending)                    |
  |  The 30s cache delay didn't save much, but the RISK is huge.        |
  |                                                                      |
  |  Scenario 2: Double Notification (Inconsistent State)                |
  |  ----------------------------------------------------------------   |
  |  Two alert evaluator replicas. Both check "should I notify?"        |
  |  Without CP: both read "last notified: never" from stale cache.     |
  |  Both send PagerDuty notification. On-call gets paged TWICE.        |
  |  At 3 AM. For the same alert. Rage ensues.                          |
  |                                                                      |
  |  Scenario 3: Alert Resolution Missed                                 |
  |  ----------------------------------------------------------------   |
  |  Alert is FIRING. Error rate drops back to 1%.                      |
  |  Alert evaluator reads stale cache: error rate = 15% (old data).    |
  |  Alert stays FIRING even though issue resolved.                     |
  |  On-call continues investigating a phantom problem.                  |
  |  Wasted engineer time. Alert fatigue. Trust in alerting erodes.     |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### CP Implementation for Alert Evaluation

```java
// Alert evaluation: ALWAYS reads fresh data, NEVER uses cache
public class AlertEvaluator {

    private final TsdbClient tsdb;          // direct TSDB connection
    private final AlertStateStore stateStore; // Redis, write-through
    private final NotificationService notifier;

    // Runs every 15 seconds (evaluation interval)
    public void evaluateAllRules(List<AlertRule> rules) {
        for (AlertRule rule : rules) {
            try {
                // 1. Query TSDB DIRECTLY -- NO CACHE
                // This is the CP decision: prefer fresh data even if slower
                double currentValue = tsdb.queryInstant(rule.getPromqlExpression());

                // 2. Compare to threshold
                boolean breached = rule.isBreached(currentValue);

                // 3. Update alert state (write-through to Redis)
                AlertState previousState = stateStore.getState(rule.getId());
                AlertState newState = computeNewState(previousState, breached, rule);

                // 4. Atomic state transition (Redis CAS operation)
                boolean updated = stateStore.compareAndSetState(
                    rule.getId(), previousState, newState);

                if (!updated) {
                    // Another evaluator updated state first (race condition)
                    // Skip notification, let the winner handle it
                    continue;
                }

                // 5. Notify if state changed to FIRING
                if (newState.isFiring() && !previousState.isFiring()) {
                    notifier.sendAlert(rule, newState);
                }

            } catch (TsdbUnavailableException e) {
                // TSDB is down -- this IS an alert condition
                // Meta-alert: "Monitoring system degraded"
                notifier.sendMetaAlert("TSDB unreachable during alert evaluation",
                    rule.getId());
            }
        }
    }

    // State machine: pending duration must be exceeded before firing
    private AlertState computeNewState(AlertState prev, boolean breached,
                                        AlertRule rule) {
        if (breached) {
            if (prev.isNormal()) {
                // Normal -> Pending (start the clock)
                return AlertState.pending(Instant.now());
            } else if (prev.isPending()) {
                Duration pendingDuration = Duration.between(
                    prev.getPendingSince(), Instant.now());
                if (pendingDuration.compareTo(rule.getForDuration()) >= 0) {
                    // Pending long enough -> FIRING
                    return AlertState.firing(Instant.now());
                }
                return prev; // still pending, keep waiting
            }
            return prev; // already firing, maintain state
        } else {
            // Not breached -> resolve (back to normal)
            return AlertState.normal();
        }
    }
}
```

### Fencing for Alert Notification Dedup

```
  +----------------------------------------------------------------------+
  |  NOTIFICATION DEDUP WITH REDIS CAS (CP GUARANTEE)                    |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Problem: two alert evaluator replicas both detect FIRING state      |
  |  Both try to send PagerDuty notification                             |
  |                                                                      |
  |  Solution: Redis SETNX (set-if-not-exists) as a distributed lock    |
  |                                                                      |
  |  Evaluator A:                                                        |
  |    SETNX "alert:notify:lock:rule-123" "eval-A" EX 60                |
  |    -> Returns 1 (success, lock acquired)                             |
  |    -> Sends PagerDuty notification                                   |
  |    -> Sets "alert:lastnotify:rule-123" = now()                      |
  |                                                                      |
  |  Evaluator B (0.5ms later):                                          |
  |    SETNX "alert:notify:lock:rule-123" "eval-B" EX 60                |
  |    -> Returns 0 (lock already held by eval-A)                        |
  |    -> SKIPS notification                                              |
  |                                                                      |
  |  Result: exactly one notification sent.                              |
  |                                                                      |
  +----------------------------------------------------------------------+
```

---

## AP for Metric Ingestion -- Accept Writes, Never Block Producers

### Why AP Is Non-Negotiable for Ingestion

```
  +----------------------------------------------------------------------+
  |  THE CARDINAL RULE: OBSERVABILITY MUST NOT CAUSE OUTAGES             |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Scenario: TSDB is partitioned or overloaded                         |
  |                                                                      |
  |  WRONG (CP for ingestion):                                           |
  |    1. Service calls MetricService.ingest() with CPU metric           |
  |    2. MetricService tries to write to TSDB, connection timeout       |
  |    3. MetricService retries 3 times (15 seconds blocked)             |
  |    4. Service thread is blocked for 15 seconds                       |
  |    5. Service thread pool exhausted                                   |
  |    6. Service stops responding to business requests                  |
  |    7. Load balancer marks service unhealthy                          |
  |    8. Users get 503 errors                                           |
  |    THE MONITORING SYSTEM CAUSED A PRODUCTION OUTAGE.                 |
  |                                                                      |
  |  RIGHT (AP for ingestion):                                           |
  |    1. Service calls MetricService.ingest() with CPU metric           |
  |    2. MetricService drops the metric into Kafka (async, <1ms)        |
  |    3. Service continues handling business requests immediately       |
  |    4. Kafka buffers metrics until TSDB recovers                      |
  |    5. When TSDB recovers, consumer drains Kafka backlog              |
  |    6. Metrics arrive 30 seconds late to dashboards                   |
  |    7. Dashboards show "last updated: 30s ago" (acceptable)           |
  |    SERVICES ARE HEALTHY. METRICS ARE SLIGHTLY DELAYED.               |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### AP Implementation for Metric Ingestion

```java
// Metric ingestion: fire-and-forget to Kafka, never block the caller
public class MetricIngestionService {

    private final KafkaProducer<String, MetricBatch> kafkaProducer;
    private final MeterRegistry selfMetrics; // meta-monitoring

    // Called by instrumented services (or OTel Collector)
    // MUST return in < 1ms. MUST NOT throw exceptions to caller.
    public void ingest(List<MetricPoint> points) {
        try {
            // 1. Batch points by metric name (partition key)
            Map<String, List<MetricPoint>> batches = points.stream()
                .collect(Collectors.groupingBy(MetricPoint::getMetricName));

            // 2. Send to Kafka (async, non-blocking)
            for (var entry : batches.entrySet()) {
                ProducerRecord<String, MetricBatch> record = new ProducerRecord<>(
                    "observability.metrics",
                    entry.getKey(),           // partition key = metric name
                    new MetricBatch(entry.getValue())
                );

                // Async send with callback (don't block caller)
                kafkaProducer.send(record, (metadata, exception) -> {
                    if (exception != null) {
                        // Kafka send failed -- LOG AND DROP, don't retry
                        selfMetrics.counter("metric.ingest.kafka.failures").increment();
                        // Acceptable: we lose some metrics during Kafka outage
                        // Unacceptable: blocking the caller
                    } else {
                        selfMetrics.counter("metric.ingest.success").increment();
                    }
                });
            }
        } catch (Exception e) {
            // Catch ALL exceptions -- ingestion failures must NEVER propagate
            selfMetrics.counter("metric.ingest.errors").increment();
        }
    }
}
```

### Kafka as the AP Buffer

```
  +----------------------------------------------------------------------+
  |  KAFKA PROVIDES AP GUARANTEES FOR INGESTION                          |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Normal operation:                                                    |
  |    Producer -> Kafka (acks=1, <5ms) -> Consumer -> TSDB (write)     |
  |    End-to-end latency: ~10ms                                         |
  |                                                                      |
  |  TSDB partition/overload:                                             |
  |    Producer -> Kafka (acks=1, <5ms) -> Consumer PAUSED              |
  |    Kafka accumulates messages on disk                                |
  |    Retention: 3 days (72 hours of buffer)                            |
  |    Producer is UNAFFECTED (Kafka accepts writes independently)      |
  |                                                                      |
  |  Kafka partition (some brokers down):                                 |
  |    Producer -> Kafka (acks=1, in-sync replicas still available)     |
  |    Kafka ISR set shrinks but writes continue                         |
  |    If ALL replicas for a partition are down:                          |
  |      Option A: unclean.leader.election.enable=true (AP, may lose data)|
  |      Option B: unclean.leader.election.enable=false (CP, blocks writes)|
  |      For observability: choose Option A (lose data > block producers)|
  |                                                                      |
  |  Network partition between producer and Kafka:                       |
  |    Producer: Kafka send fails. Metric is dropped.                    |
  |    This is acceptable: losing 10 seconds of metrics is fine.         |
  |    The alternative (buffering on producer side) risks OOM.           |
  |                                                                      |
  +----------------------------------------------------------------------+
```

---

## AP for Trace Collection -- Drop Before Block

### Sampling IS Lossy by Design

```
  +----------------------------------------------------------------------+
  |  TRACES ARE INHERENTLY AP -- SAMPLING = INTENTIONAL DATA LOSS        |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  At 10% head-based sampling:                                         |
  |    100K requests/sec -> 10K traces/sec retained -> 90K DROPPED       |
  |    We intentionally lose 90% of trace data.                          |
  |    This is acceptable because:                                        |
  |      - Statistical analysis is still valid at 10% sample rate        |
  |      - p99 latency is accurately estimated from 10% sample           |
  |      - Error traces are over-sampled (tail-based: always keep errors)|
  |                                                                      |
  |  If we already lose 90% intentionally, losing an additional 1%      |
  |  during a network partition is negligible.                           |
  |                                                                      |
  |  Therefore: trace collection is firmly AP.                           |
  |    - Drop spans that fail to send (don't retry, don't buffer)       |
  |    - Buffer briefly in OTel Collector (memory_limiter processor)     |
  |    - If buffer full: drop oldest spans (bounded memory)              |
  |    - Service health is ALWAYS more important than trace completeness |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Head-Based vs Tail-Based Sampling: CAP Perspective

```
  +----------------------------------------------------------------------+
  |  SAMPLING STRATEGY CAP IMPLICATIONS                                   |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  HEAD-BASED SAMPLING:                                                |
  |    Decision: at request entry point (API gateway)                    |
  |    hash(traceId) % 100 < sampleRate -> sample this trace            |
  |                                                                      |
  |    CAP: fully AP                                                     |
  |    - Each service makes the decision independently (no coordination)|
  |    - Deterministic: same traceId = same decision everywhere          |
  |    - No distributed state needed                                     |
  |    - Works during network partitions (no coordinator dependency)    |
  |                                                                      |
  |    Tradeoff: blind decision                                          |
  |    - Decides BEFORE knowing if the trace is interesting              |
  |    - May discard a rare error trace (1-in-1000 error, sampled away) |
  |                                                                      |
  |  TAIL-BASED SAMPLING:                                                |
  |    Decision: after trace completes (at OTel Collector)               |
  |    Buffer all spans for 10 seconds, then decide:                     |
  |      - Keep if: any span has ERROR status                            |
  |      - Keep if: trace duration > 1 second                           |
  |      - Keep if: random 10% probabilistic                            |
  |      - Drop: everything else                                         |
  |                                                                      |
  |    CAP: CP-leaning (requires coordination)                           |
  |    - All spans for a trace must reach the SAME collector instance   |
  |    - Requires consistent routing (traceId-based Kafka partitioning) |
  |    - Collector must buffer ALL spans for the decision window         |
  |    - If collector is down: fallback to head-based (AP degradation)  |
  |                                                                      |
  |    Tradeoff: cost + complexity                                       |
  |    - Must receive ALL spans before deciding (memory pressure)        |
  |    - Late spans (arriving after decision window) may be dropped     |
  |    - Collector becomes a stateful component (harder to scale)       |
  |                                                                      |
  |  ADAPTIVE SAMPLING (Datadog approach):                               |
  |    Decision: central controller adjusts per-service rates            |
  |    High-error services get higher sample rate automatically          |
  |                                                                      |
  |    CAP: CP for rate adjustment, AP for execution                    |
  |    - Central controller is CP (single source of truth for rates)    |
  |    - If controller unreachable: use last-known rates (AP fallback)  |
  |    - Services sample independently at the configured rate           |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Simulation Mapping for Sampling

| Simulation Class                  | CAP Choice | Production Equivalent        |
|-----------------------------------|------------|------------------------------|
| `HeadBasedSamplingStrategy`       | AP         | OTel TraceIdRatioBased sampler |
| `TailBasedSamplingStrategy`       | CP-leaning | OTel tail_sampling processor  |
| `RateLimitedSamplingStrategy`     | AP         | OTel rate_limiting sampler   |
| `SamplingEngine`                  | AP         | OTel Collector pipeline       |

---

## Push vs Pull Metrics -- CAP Implications

### Pull Model (Prometheus)

```
  +----------------------------------------------------------------------+
  |  PULL MODEL: PROMETHEUS SCRAPES SERVICES                              |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  CAP analysis:                                                        |
  |    - Prometheus CONTROLS the load (scrape interval = consistent)     |
  |    - If service is unreachable: scrape fails -> "up" gauge = 0      |
  |      -> this IS a health signal (free liveness check)               |
  |    - If Prometheus is partitioned from service:                       |
  |      -> missing data gap in TSDB (acceptable for AP dashboards)     |
  |      -> alert: "target down" fires (CP for alerting)                |
  |                                                                      |
  |  Consistency: Prometheus is the single source of truth for when to  |
  |    collect. No duplicate collection (one scrape per interval).      |
  |  Availability: if Prometheus is down, no metrics collected from      |
  |    anyone (single point of failure, mitigate with HA pair).         |
  |  Partition: Prometheus on one side of partition cannot scrape         |
  |    services on the other side. Data gap until partition heals.      |
  |                                                                      |
  |  CP-leaning: Prometheus prefers accuracy over availability.          |
  |    Missing data = gap in dashboard, NOT fake/stale data.             |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Push Model (Datadog Agent, OTel Collector)

```
  +----------------------------------------------------------------------+
  |  PUSH MODEL: SERVICES PUSH TO COLLECTOR                               |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  CAP analysis:                                                        |
  |    - Each service pushes independently (decoupled from collector)   |
  |    - If collector is unreachable:                                     |
  |      -> service buffers locally (OTel SDK batch processor)           |
  |      -> after buffer full: drop oldest data                          |
  |      -> service continues operating (AP for application health)     |
  |    - If service is overloaded:                                        |
  |      -> service may delay or drop metric pushes                     |
  |      -> collector doesn't know service is alive (no free health check)|
  |                                                                      |
  |  Consistency: multiple services may push with different timestamps   |
  |    and clock skew. Collector must handle out-of-order arrival.      |
  |  Availability: if collector is down, services buffer locally (AP).  |
  |    When collector recovers, buffered data arrives (eventual consistency).|
  |  Partition: services on one side push to local collector. Collectors |
  |    on the other side don't receive. Data converges when partition heals.|
  |                                                                      |
  |  AP-leaning: push model prioritizes availability of the SERVICE     |
  |    over completeness of the telemetry data.                          |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Pull vs Push Decision Matrix

```
  +----------------------------------------------------------------------+
  |  WHEN TO USE PULL VS PUSH                                            |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Use PULL (Prometheus) when:                                         |
  |    - Services are long-lived (not serverless)                        |
  |    - Services are reachable from Prometheus (same network/cluster)  |
  |    - You want free liveness detection (scrape failure = down)       |
  |    - You want controlled scrape load (no thundering herd)           |
  |    - You need consistent scrape intervals for rate() calculations   |
  |                                                                      |
  |  Use PUSH (OTel, Datadog) when:                                     |
  |    - Services are behind NAT/firewall (outbound-only networking)   |
  |    - Services are short-lived (Lambda, batch jobs, Kubernetes Jobs) |
  |    - Multi-cloud: services in different clouds/networks             |
  |    - Sub-second resolution needed (push at any frequency)           |
  |    - You need push-based context propagation (trace IDs with metrics)|
  |                                                                      |
  |  Hybrid (common in production):                                      |
  |    - Prometheus scrapes application /metrics endpoints (pull)        |
  |    - OTel Collector receives traces and logs (push)                 |
  |    - Prometheus remote_write pushes to Mimir (pull->push)           |
  |    - Best of both worlds for different signal types                 |
  |                                                                      |
  +----------------------------------------------------------------------+
```

---

## Storage Cost vs Query Speed Tradeoff

### The Fundamental Storage Tradeoff

```
  +----------------------------------------------------------------------+
  |  STORAGE TRADEOFF: COST vs QUERY SPEED vs RETENTION                  |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Cost                                                                |
  |  ^                                                                    |
  |  |                                                                    |
  |  |  * Raw data, full index (Elasticsearch)                           |
  |  |                                                                    |
  |  |        * Raw data, columnar (ClickHouse)                          |
  |  |                                                                    |
  |  |              * Raw data, label-indexed (Loki)                     |
  |  |                                                                    |
  |  |                    * Downsampled 5-min (TSDB)                     |
  |  |                                                                    |
  |  |                          * Downsampled 1-hour (TSDB)              |
  |  |                                                                    |
  |  +------------------------------------------------------> Query Speed|
  |  slow                                                    fast        |
  |                                                                      |
  |  The tradeoff: more indexing/structure = faster queries = more cost  |
  |                less indexing/structure = slower queries = less cost   |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Storage Tier Strategy

```
  +----------------------------------------------------------------------+
  |  TIERED STORAGE: HOT / WARM / COLD                                   |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  HOT TIER (last 24 hours):                                           |
  |    Storage: SSD / local NVMe                                         |
  |    Data: raw resolution (15-second metric points, all spans)         |
  |    Index: full (inverted index for logs, time + label index for metrics)|
  |    Query speed: <100ms for most queries                              |
  |    Cost: $$$$                                                        |
  |                                                                      |
  |  WARM TIER (1 day - 30 days):                                        |
  |    Storage: HDD / EBS gp3                                            |
  |    Data: raw resolution + 5-minute downsampled                       |
  |    Index: reduced (compress older chunks, fewer replicas)            |
  |    Query speed: 100ms - 2s depending on time range                  |
  |    Cost: $$                                                          |
  |                                                                      |
  |  COLD TIER (30 days - 2 years):                                      |
  |    Storage: Object storage (S3/GCS/Azure Blob)                      |
  |    Data: 1-hour downsampled only (raw data deleted by retention)    |
  |    Index: minimal (only time + metric name)                          |
  |    Query speed: 2-10s (object storage latency + decompression)      |
  |    Cost: $                                                           |
  |                                                                      |
  |  ARCHIVE (2+ years):                                                  |
  |    Storage: S3 Glacier / Coldline / Archive                          |
  |    Data: 1-hour downsampled, compressed                              |
  |    Query speed: minutes (retrieval latency)                          |
  |    Cost: pennies per GB                                              |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Columnar vs Row Storage for Metrics

```
  +----------------------------------------------------------------------+
  |  CLICKHOUSE (COLUMNAR) vs TIMESCALEDB (ROW)                          |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Query: "Average CPU usage per service, last 7 days, 1-hour buckets"|
  |                                                                      |
  |  ClickHouse (columnar):                                               |
  |    - Reads ONLY the 'value' and 'timestamp' columns                 |
  |    - Skips 'metric_name', 'tags', and all other columns             |
  |    - Columnar compression: ~10x compression on value column          |
  |    - Vectorized processing: SIMD on column chunks                   |
  |    - Result: scan 100M rows in <1 second                            |
  |    - Disk read: ~10 MB (compressed value + timestamp columns only)  |
  |                                                                      |
  |  TimescaleDB (row):                                                   |
  |    - Reads ENTIRE rows (including all columns)                      |
  |    - PostgreSQL compression available but row-oriented               |
  |    - Benefits from chunk pruning (skip irrelevant time chunks)      |
  |    - Result: scan 100M rows in ~5-10 seconds                        |
  |    - Disk read: ~500 MB (all columns for matching rows)             |
  |                                                                      |
  |  When to choose ClickHouse:                                           |
  |    - High-volume analytics (billions of rows)                        |
  |    - Aggregation-heavy queries (sum, avg, percentile)               |
  |    - Write-heavy workloads (millions of inserts/sec)                |
  |                                                                      |
  |  When to choose TimescaleDB:                                          |
  |    - Already running PostgreSQL (operational simplicity)            |
  |    - Need JOINs with relational data (alert rules, service metadata)|
  |    - Moderate volume (<100K metrics/sec)                             |
  |    - Need ACID transactions (alert state, rule management)          |
  |                                                                      |
  +----------------------------------------------------------------------+
```

---

## PACELC Analysis

### PACELC Framework Applied to Observability

```
  +----------------------------------------------------------------------+
  |  PACELC: WHEN NO PARTITION, LATENCY vs CONSISTENCY                   |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  CAP only addresses behavior DURING partitions.                      |
  |  PACELC extends it: what tradeoff do we make during NORMAL operation?|
  |                                                                      |
  |  PAC = Partition -> Availability vs Consistency (same as CAP)        |
  |  ELC = Else (no partition) -> Latency vs Consistency                 |
  |                                                                      |
  |  Component                 | P: A/C | ELC: L/C | Full PACELC        |
  |  --------------------------+--------+----------+--------------------+
  |  Metric Ingestion          | PA     | EL       | PA/EL              |
  |    During partition: accept writes (Available)                       |
  |    Else: minimize write latency (fire-and-forget to Kafka)           |
  |  --------------------------+--------+----------+--------------------+
  |  Alert Evaluation          | PC     | EC       | PC/EC              |
  |    During partition: prefer accuracy (Consistent)                    |
  |    Else: query TSDB directly, accept higher latency for accuracy    |
  |  --------------------------+--------+----------+--------------------+
  |  Dashboard Queries         | PA     | EL       | PA/EL              |
  |    During partition: return cached stale data (Available)            |
  |    Else: serve from cache when possible (low Latency)               |
  |  --------------------------+--------+----------+--------------------+
  |  Trace Collection          | PA     | EL       | PA/EL              |
  |    During partition: drop spans (Available, don't block services)   |
  |    Else: batch and buffer for efficient writes (low Latency)        |
  |  --------------------------+--------+----------+--------------------+
  |  TSDB Storage (ClickHouse) | PA     | EL       | PA/EL              |
  |    During partition: accept writes on available replicas            |
  |    Else: async replication for low write latency                    |
  |  --------------------------+--------+----------+--------------------+
  |  Alert State (Redis)       | PC     | EC       | PC/EC              |
  |    During partition: prefer consistency (Redis Cluster majority)    |
  |    Else: sync replication for consistent reads                      |
  |  --------------------------+--------+----------+--------------------+
  |  Service Map               | PA     | EL       | PA/EL              |
  |    During partition: return cached map (Available)                  |
  |    Else: aggressive caching for low latency (5-min TTL)            |
  |  --------------------------+--------+----------+--------------------+
  |                                                                      |
  |  Summary: almost everything is PA/EL (favor availability and latency)|
  |  EXCEPT alerting, which is PC/EC (favor consistency always)         |
  |                                                                      |
  +----------------------------------------------------------------------+
```

---

## Network Partition Scenarios

### Scenario 1: TSDB Partition

```
  +----------------------------------------------------------------------+
  |  SCENARIO: TSDB (ClickHouse/TimescaleDB) IS PARTITIONED              |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Kafka consumers cannot write to TSDB.                               |
  |                                                                      |
  |  Impact:                                                              |
  |    - Metric ingestion: Kafka buffers (up to 3-day retention)        |
  |    - Dashboard queries: return stale cached results (30s TTL)       |
  |    - Alert evaluation: FAILS (cannot query TSDB)                    |
  |      -> Meta-alert: "Monitoring degraded, TSDB unreachable"         |
  |      -> Fallback: evaluate against last-known good metric values    |
  |         (stored in Redis alert state cache)                          |
  |    - Service map: returns cached version (up to 5 minutes old)      |
  |                                                                      |
  |  Resolution:                                                          |
  |    1. TSDB partition heals                                           |
  |    2. Kafka consumers resume, drain backlog                          |
  |    3. TSDB catches up (backlog processing rate > ingestion rate)    |
  |    4. Dashboards return to real-time within minutes                  |
  |    5. Alert evaluation resumes with fresh data                      |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Scenario 2: Kafka Partition

```
  +----------------------------------------------------------------------+
  |  SCENARIO: KAFKA CLUSTER IS PARTITIONED                               |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  OTel Collectors cannot write to Kafka.                              |
  |                                                                      |
  |  Impact:                                                              |
  |    - Metric ingestion: OTel Collector buffers in memory              |
  |      (memory_limiter: 1GB, then drops oldest data)                  |
  |    - Trace collection: same buffering, then drops                   |
  |    - Log shipping: Fluent Bit buffers to local filesystem            |
  |      (filesystem buffer: up to 1GB, then drops)                     |
  |    - Alert evaluation: unaffected IF Prometheus is separate path    |
  |      (Prometheus scrapes directly, doesn't go through Kafka)        |
  |    - Dashboards: stale but cached data still available              |
  |                                                                      |
  |  Design implication:                                                  |
  |    Alerting should have a SEPARATE data path that bypasses Kafka.    |
  |    Prometheus -> TSDB direct (for alerting metrics)                  |
  |    OTel -> Kafka -> TSDB (for dashboard/analytics metrics)           |
  |    This ensures alerts work even when Kafka is down.                 |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Scenario 3: Redis Partition

```
  +----------------------------------------------------------------------+
  |  SCENARIO: REDIS CLUSTER IS PARTITIONED                               |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Dashboard query cache and alert state store are unreachable.        |
  |                                                                      |
  |  Impact:                                                              |
  |    - Dashboard queries: fall through to TSDB (50-200ms instead of 1ms)|
  |    - Service map: recomputed on every request (expensive but works)  |
  |    - Alert state: alert evaluator cannot read/write state            |
  |      -> Risk: duplicate notifications (two evaluators both fire)    |
  |      -> Mitigation: PagerDuty dedup key (idempotent at notification |
  |         service level, even if we send twice)                       |
  |    - Notification dedup: cannot enforce dedup                        |
  |      -> Accept duplicate notifications during Redis partition        |
  |      -> Better to page twice than not page at all                   |
  |                                                                      |
  |  Resolution:                                                          |
  |    Redis partition heals -> cache warms up within seconds            |
  |    Alert state reconverges from TSDB evaluation results              |
  |                                                                      |
  +----------------------------------------------------------------------+
```

---

## Industry Comparison

### Architecture Approaches

```
  +----------------------------------------------------------------------+
  |  HOW THE BIG PLAYERS HANDLE CAP                                      |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  DATADOG:                                                            |
  |    Ingestion: AP (push model, Datadog Agent buffers locally)        |
  |    Storage: custom TSDB (AP, eventually consistent replication)     |
  |    Alerting: CP (dedicated alerting pipeline, separate from ingest) |
  |    Sampling: adaptive (central controller adjusts rates)            |
  |    Percentiles: DDSketch (mergeable, relative error guarantee)      |
  |                                                                      |
  |  GRAFANA CLOUD:                                                      |
  |    Ingestion: AP (Prometheus remote_write, OTel push)               |
  |    Storage: Mimir (AP, quorum reads/writes, eventual consistency)   |
  |    Alerting: CP (Prometheus Alertmanager, gossip protocol for HA)   |
  |    Traces: Tempo (AP, object storage backend, no index on content)  |
  |    Logs: Loki (AP, label index only, chunk storage in S3)           |
  |                                                                      |
  |  NEW RELIC:                                                          |
  |    Ingestion: AP (push model, New Relic Agent)                      |
  |    Storage: NRDB (custom columnar DB, AP for writes)                |
  |    Alerting: CP (dedicated alert processing pipeline)               |
  |    Query: NRQL (SQL-like, runs on NRDB)                            |
  |                                                                      |
  |  Common pattern across all:                                          |
  |    - Ingestion is ALWAYS AP (never block instrumented services)     |
  |    - Alerting is ALWAYS CP (dedicated pipeline, fresh evaluation)   |
  |    - Dashboards are AP with caching (stale OK for visualization)   |
  |    - Separate pipelines for alerting vs dashboarding                |
  |                                                                      |
  +----------------------------------------------------------------------+
```

---

## Interview Q&A

### Q1: "How do you handle the CAP theorem in an observability platform?"

```
  +----------------------------------------------------------------------+
  |  ANSWER FRAMEWORK (3 PARTS)                                           |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  "An observability platform has a SPLIT CAP requirement."            |
  |                                                                      |
  |  Part 1: Ingestion = AP                                              |
  |  "The metric/trace/log ingestion path must be AP. The observability  |
  |   system must NEVER cause the production services it monitors to     |
  |   fail. We buffer in Kafka, use async writes, and accept eventual   |
  |   consistency. Losing 10 seconds of metrics during a partition is   |
  |   far better than cascading failures in production services."        |
  |                                                                      |
  |  Part 2: Alerting = CP                                               |
  |  "The alerting pipeline must be CP. Alert evaluation queries MUST   |
  |   hit the TSDB directly, never a cache. A stale alert evaluation    |
  |   could mean missing a critical production incident. We use Redis   |
  |   CAS for notification dedup to prevent double-paging."             |
  |                                                                      |
  |  Part 3: Dashboards = AP (with nuance)                              |
  |  "Dashboards serve cached results with 30-second TTL. Users see     |
  |   'last updated: 30s ago' which is acceptable. During an active     |
  |   incident investigation, the engineer can force-refresh for fresh  |
  |   data, which bypasses the cache."                                   |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Q2: "Push vs pull for metrics -- which do you choose and why?"

```
  +----------------------------------------------------------------------+
  |  ANSWER                                                               |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  "I'd use a hybrid approach, which is what most production systems   |
  |   do. Prometheus pull for application metrics in Kubernetes (free    |
  |   liveness check, controlled load, consistent scrape intervals),    |
  |   and OTel push for traces and logs (push model works better for    |
  |   request-scoped data). For serverless (Lambda) or multi-cloud, I'd |
  |   use push exclusively because pull can't reach behind NAT."         |
  |                                                                      |
  |  "From a CAP perspective, pull is slightly CP-leaning -- Prometheus  |
  |   controls the collection and missing scrapes create honest gaps.   |
  |   Push is AP-leaning -- services push independently and the collector|
  |   may receive out-of-order or duplicate data."                       |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Q3: "How do you handle sampling decisions in a distributed system?"

```
  +----------------------------------------------------------------------+
  |  ANSWER                                                               |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  "I'd use a two-layer approach: head-based sampling at the entry    |
  |   point for the default 10% sample rate, plus tail-based sampling   |
  |   at the collector to ensure we keep ALL error traces and all slow  |
  |   traces (>1 second). Head-based is AP -- each service decides      |
  |   independently using hash(traceId). Tail-based is CP-leaning --   |
  |   requires all spans to reach the same collector instance."          |
  |                                                                      |
  |  "The key tradeoff: head-based is cheap but blind (may discard rare |
  |   errors). Tail-based is smart but expensive (must buffer all spans |
  |   temporarily). The combination gives us bounded cost with smart    |
  |   retention of important traces."                                    |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Q4: "How do you decide what to cache in an observability system?"

```
  +----------------------------------------------------------------------+
  |  ANSWER                                                               |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  "Cache the READ side, not the WRITE side. Raw metrics, spans, and  |
  |   logs are write-heavy (100K+ per second) and read-rarely. Caching  |
  |   them would consume enormous memory with minimal benefit."          |
  |                                                                      |
  |  "What I DO cache:"                                                  |
  |  "1. Dashboard query results in Redis (30s TTL, shared across users) |
  |   2. Service map topology (5-min TTL, expensive to compute)          |
  |   3. Downsampled aggregates as persistent 'cache' in the TSDB"      |
  |                                                                      |
  |  "What I NEVER cache:"                                               |
  |  "Alert evaluation queries -- must always be fresh. A 30-second     |
  |   stale cache could mean missing a critical production incident."    |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Q5: "What happens if your monitoring system itself goes down?"

```
  +----------------------------------------------------------------------+
  |  ANSWER                                                               |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  "This is the meta-monitoring problem -- who watches the watchers?  |
  |   I handle it with three strategies:"                                |
  |                                                                      |
  |  "1. SEPARATE ALERTING PATH: Prometheus scrapes directly and        |
  |      evaluates alert rules locally. Even if Kafka, ClickHouse, and  |
  |      Grafana are all down, Prometheus + Alertmanager still fires    |
  |      alerts via PagerDuty."                                          |
  |                                                                      |
  |  "2. EXTERNAL HEALTH CHECK: a simple external service (like         |
  |      Pingdom or Route53 health checks) pings the observability      |
  |      platform itself. If it doesn't respond, alert via out-of-band  |
  |      channel (direct PagerDuty API call)."                           |
  |                                                                      |
  |  "3. DEADMAN SWITCH: an alert that fires when it STOPS receiving    |
  |      data. If the metric watchdog_heartbeat stops being reported,   |
  |      Alertmanager fires 'monitoring pipeline down' alert."           |
  |                                                                      |
  +----------------------------------------------------------------------+
```

---

## Simulation-to-Production Mapping (CAP Perspective)

```
  +----------------------------------------------------------------------+
  |  SIMULATION -> PRODUCTION CAP MAPPING                                 |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Simulation Class              | CAP  | Production Equivalent        |
  |  ------------------------------+------+-----------------------------+
  |  MetricService.ingest()        | AP   | Kafka async write, never block|
  |  TracingService.recordSpan()   | AP   | OTel batch processor, drop on OOM|
  |  LogService.ingest()           | AP   | Fluent Bit buffer + retry    |
  |  AlertService.evaluate()       | CP   | Direct TSDB query, no cache  |
  |  AlertService.notify()         | CP   | Redis CAS dedup, PagerDuty   |
  |  DashboardService.getStats()   | AP   | Redis cached, stale OK       |
  |  ServiceMapService.getMap()    | AP   | Redis cached, 5-min TTL      |
  |  TimeSeriesStore.write()       | AP   | ClickHouse async insert      |
  |  TimeSeriesStore.query()       | AP*  | ClickHouse quorum read       |
  |    * except for alert queries  | CP   | Direct TSDB, no cache        |
  |  SamplingEngine.shouldSample() | AP   | OTel sampler (no coordinator)|
  |  ObservabilityService          | AP   | OTel Collector pipeline      |
  |                                                                      |
  |  Key insight: the simulation runs in a single JVM, so there are NO  |
  |  real partitions. All data is "CP" in the simulation because there  |
  |  is no network. In production, the AP vs CP split becomes critical  |
  |  because network partitions, TSDB outages, and Redis failures ARE   |
  |  inevitable at scale.                                                |
  |                                                                      |
  +----------------------------------------------------------------------+
```
