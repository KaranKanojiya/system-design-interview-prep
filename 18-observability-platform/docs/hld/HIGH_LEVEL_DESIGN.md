# Observability Platform -- High-Level Design

## Interview Guide

**Target Duration**: 30-45 minutes
**Difficulty**: Staff Engineer / L6+
**Format**: Structured walkthrough, whiteboard-friendly

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Scope](#2-scope)
3. [Requirements](#3-requirements)
4. [API Design](#4-api-design)
5. [Data Model](#5-data-model)
6. [High-Level Architecture](#6-high-level-architecture)
7. [Three Pillars Deep Dive](#7-three-pillars-deep-dive)
8. [Time-Series Storage Design](#8-time-series-storage-design)
9. [Sampling Strategies](#9-sampling-strategies)
10. [Alerting Pipeline](#10-alerting-pipeline)
11. [Service Dependency Mapping](#11-service-dependency-mapping)
12. [Dashboard Design](#12-dashboard-design)
13. [High-Cardinality Metric Management](#13-high-cardinality-metric-management)
14. [Scaling Strategy](#14-scaling-strategy)
15. [Database Choices](#15-database-choices)
16. [CAP Analysis](#16-cap-analysis)
17. [Cloud Mapping](#17-cloud-mapping)
18. [Failure Scenarios](#18-failure-scenarios)
19. [Interview Walkthrough Script](#19-interview-walkthrough-script)

---

## 1. Problem Statement

Design an **observability platform** (like Datadog, Grafana Cloud, or New Relic) that provides engineers with unified visibility into distributed systems through three correlated signals: metrics, traces, and logs.

### Why This Is Hard

1. **Volume**: A mid-size microservices platform generates 1M metric points/sec, 100K spans/sec, and 500K log lines/sec
2. **Correlation**: The three signals must be linked via shared IDs (traceId) so engineers can pivot from a metric anomaly to the specific trace to the relevant log lines
3. **Latency vs Cost**: Engineers want real-time dashboards (< 5s data freshness) but storing every data point at full resolution for 1 year costs millions in storage
4. **Cardinality Explosion**: Each unique combination of metric labels creates a new time series -- `api.requests{method=GET, path=/users, status=200, region=us-east, host=prod-1}` -- millions of combinations
5. **Alert Reliability**: A missed critical alert means a production outage goes undetected; a noisy alert means engineers ignore real alerts (alert fatigue)

### Real-World Scale

| Signal | Ingestion Rate | Daily Volume | 1-Year Storage (Raw) | 1-Year Storage (Downsampled) |
|--------|---------------|-------------|----------------------|------------------------------|
| Metrics | 1M points/sec | 86B points | ~860 TB | ~8.6 TB (100x compression) |
| Traces | 100K spans/sec | 8.6B spans | ~430 TB | ~43 TB (10% sampled) |
| Logs | 500K lines/sec | 43B lines | ~2.15 PB | ~215 TB (10% sampled + compressed) |

---

## 2. Scope

### In Scope

| Feature | Details |
|---------|---------|
| Metric Collection | Counters, gauges, histograms, timers with tag-based labeling |
| Distributed Tracing | Span tree assembly, context propagation, trace visualization |
| Structured Logging | Log ingestion with trace correlation (traceId + spanId) |
| Time-Series Storage | Bucketed storage, range queries, downsampling |
| Metric Aggregation | Sum, avg, min, max, percentiles (P50/P95/P99), rate |
| Trace Sampling | Head-based, tail-based, rate-limited strategies |
| Alerting | Threshold-based and anomaly detection (mean +/- k*stdDev) |
| Service Dependency Map | Auto-discovered from trace data, edge statistics |
| Dashboards | RED method (Rate, Errors, Duration) and USE method (Utilization, Saturation, Errors) |
| High-Cardinality Management | Tag dropping, bucketing, cardinality limits |

### Out of Scope

| Feature | Why |
|---------|-----|
| APM code instrumentation | SDK-level concern, not platform design |
| Synthetic monitoring | Different problem (active probing vs passive observation) |
| Incident management | Downstream system (PagerDuty, Opsgenie) |
| Cost billing/metering | Business logic, not core observability |
| Multi-tenancy isolation | Adds complexity without teaching new concepts |

---

## 3. Requirements

### 3.1 Functional Requirements (FR)

```
FR-1:  Ingest metric data points (counter, gauge, histogram, timer) with arbitrary key-value tags
FR-2:  Query metrics by name + time range; aggregate with sum, avg, min, max, percentile, rate
FR-3:  Downsample historical metrics (raw -> 1-min -> 5-min -> 1-hour averages)
FR-4:  Start distributed traces; create child spans with parent-child relationships
FR-5:  Propagate trace context (traceId, spanId, parentSpanId) across service boundaries
FR-6:  Sample traces using configurable strategies (head-based, tail-based, rate-limited)
FR-7:  Ingest structured log entries with optional trace correlation (traceId + spanId)
FR-8:  Search logs by service, severity level, time range, and trace ID
FR-9:  Define alert rules (metric name, condition, threshold, duration, severity)
FR-10: Evaluate alert rules periodically; fire/resolve alerts based on metric data
FR-11: Support anomaly detection alerting (statistical deviation from baseline)
FR-12: Build service dependency maps from trace data (caller -> callee + edge stats)
FR-13: Display dashboards combining metrics, traces, and logs (RED/USE methods)
FR-14: Handle high-cardinality metrics (tag dropping, bucketing, cardinality limits)
```

### 3.2 Non-Functional Requirements (NFR)

```
NFR-1:  Ingestion throughput: 1M metric points/sec, 100K spans/sec, 500K logs/sec
NFR-2:  Query latency: P99 < 500ms for dashboard queries over 1-hour windows
NFR-3:  Alert evaluation latency: < 60 seconds from metric ingestion to alert firing
NFR-4:  Data freshness: < 5 seconds from ingestion to dashboard visibility
NFR-5:  Storage efficiency: 100x compression for metrics via downsampling
NFR-6:  Availability: 99.9% for ingestion path (write-heavy, cannot drop data during outages)
NFR-7:  Durability: metrics/traces persisted within 30 seconds of ingestion (WAL-based)
NFR-8:  Sampling accuracy: head-based within 2% of configured rate; tail-based captures 100% of errors
NFR-9:  Cardinality limit: reject metrics exceeding 10K unique label combinations per metric name
NFR-10: Horizontal scalability: add ingestion/query nodes linearly with load
```

---

## 4. API Design

### 4.1 Metric Ingestion API

```
POST /api/v1/metrics
Content-Type: application/json

{
  "name": "http.request.duration_ms",
  "value": 45.2,
  "type": "TIMER",                              // COUNTER | GAUGE | HISTOGRAM | TIMER
  "tags": {
    "method": "GET",
    "path": "/api/users",
    "service": "user-service",
    "status": "200"
  },
  "timestamp": 1704067200000                     // epoch millis (optional, defaults to server time)
}

Response: 202 Accepted
{
  "status": "accepted",
  "pointsIngested": 1
}
```

**Batch ingestion** (production path -- reduces HTTP overhead 100x):

```
POST /api/v1/metrics/batch
Content-Type: application/json

{
  "points": [
    { "name": "http.requests.total", "value": 1, "type": "COUNTER", "tags": {...} },
    { "name": "http.requests.total", "value": 1, "type": "COUNTER", "tags": {...} },
    ...
  ]
}

Response: 202 Accepted
{
  "status": "accepted",
  "pointsIngested": 1000,
  "pointsRejected": 0
}
```

### 4.2 Metric Query API

```
GET /api/v1/metrics/query?name=http.request.duration_ms&from=1704063600&to=1704067200&aggregation=p99

Response: 200 OK
{
  "metricName": "http.request.duration_ms",
  "from": "2024-01-01T00:00:00Z",
  "to": "2024-01-01T01:00:00Z",
  "aggregation": "p99",
  "value": 245.7,
  "dataPoints": [
    { "timestamp": 1704063600, "value": 120.5 },
    { "timestamp": 1704063660, "value": 135.2 },
    ...
  ]
}
```

### 4.3 Trace API

```
POST /api/v1/traces
Content-Type: application/json

{
  "operationName": "POST /api/orders",
  "serviceName": "api-gateway"
}

Response: 201 Created
{
  "traceId": "abc123def456...",
  "spanId": "span-root-001",
  "parentSpanId": null,
  "sampled": true
}
```

```
POST /api/v1/traces/{traceId}/spans
Content-Type: application/json

{
  "parentSpanId": "span-root-001",
  "operationName": "createOrder",
  "serviceName": "order-service",
  "tags": { "db.type": "postgresql" }
}

Response: 201 Created
{
  "traceId": "abc123def456...",
  "spanId": "span-child-001",
  "parentSpanId": "span-root-001"
}
```

```
PUT /api/v1/traces/{traceId}/spans/{spanId}/finish
Content-Type: application/json

{
  "status": "OK",
  "tags": { "http.status_code": "201" }
}

Response: 200 OK
```

```
GET /api/v1/traces/{traceId}

Response: 200 OK
{
  "traceId": "abc123def456...",
  "rootSpan": { ... },
  "spans": [ ... ],
  "duration": "245ms",
  "spanCount": 4,
  "services": ["api-gateway", "order-service", "payment-service", "postgres"]
}
```

### 4.4 Log Ingestion API

```
POST /api/v1/logs
Content-Type: application/json

{
  "level": "WARN",
  "message": "Slow query: 250ms for user lookup",
  "serviceName": "user-service",
  "traceId": "abc123def456...",
  "spanId": "span-child-001",
  "attributes": {
    "query": "SELECT * FROM users WHERE id = ?",
    "duration_ms": "250"
  }
}

Response: 202 Accepted
```

```
GET /api/v1/logs?service=user-service&level=WARN&from=1704063600&to=1704067200&traceId=abc123

Response: 200 OK
{
  "entries": [
    {
      "id": "log-001",
      "timestamp": "2024-01-01T00:30:00Z",
      "level": "WARN",
      "message": "Slow query: 250ms for user lookup",
      "serviceName": "user-service",
      "traceId": "abc123def456...",
      "spanId": "span-child-001"
    },
    ...
  ],
  "totalCount": 42
}
```

### 4.5 Alert API

```
POST /api/v1/alerts/rules
Content-Type: application/json

{
  "name": "High CPU Alert",
  "metricName": "system.cpu.usage_percent",
  "condition": ">",
  "threshold": 80.0,
  "durationSeconds": 60,
  "severity": "CRITICAL"
}

Response: 201 Created
{
  "ruleId": "rule-001",
  "status": "enabled"
}
```

```
POST /api/v1/alerts/evaluate

Response: 200 OK
{
  "rulesEvaluated": 5,
  "alertsFired": 1,
  "alertsResolved": 0
}
```

```
GET /api/v1/alerts?status=FIRING

Response: 200 OK
{
  "alerts": [
    {
      "id": "alert-001",
      "ruleName": "High CPU Alert",
      "status": "FIRING",
      "currentValue": 94.0,
      "threshold": 80.0,
      "severity": "CRITICAL",
      "triggeredAt": "2024-01-01T00:45:00Z"
    }
  ]
}
```

### 4.6 Dashboard API

```
GET /api/v1/dashboard/overview

Response: 200 OK
{
  "metrics": {
    "totalDefinitions": 8,
    "topMetrics": [
      { "name": "http.request.duration_ms", "latest": 89.7 },
      { "name": "system.cpu.usage_percent", "latest": 42.3 }
    ]
  },
  "traces": {
    "recentCount": 5,
    "avgDuration": "125ms",
    "errorRate": 0.02
  },
  "logs": {
    "INFO": 450,
    "WARN": 23,
    "ERROR": 7
  },
  "alerts": {
    "firing": 1,
    "acknowledged": 0,
    "resolved": 12
  }
}
```

### 4.7 Service Map API

```
GET /api/v1/services/topology

Response: 200 OK
{
  "services": [
    {
      "name": "api-gateway",
      "dependencies": ["user-service", "order-service"],
      "dependents": [],
      "stats": { "requests": 1000, "errorRate": 0.01, "avgLatencyMs": 35.2 }
    },
    {
      "name": "order-service",
      "dependencies": ["payment-service", "inventory-service", "postgres"],
      "dependents": ["api-gateway"],
      "stats": { "requests": 500, "errorRate": 0.03, "avgLatencyMs": 78.5 }
    }
  ],
  "edges": [
    { "from": "api-gateway", "to": "order-service", "requests": 500, "errorRate": 0.03, "avgLatencyMs": 45.0 }
  ]
}
```

---

## 5. Data Model

### 5.1 Metric Data Model

```
MetricPoint (the atomic unit of time-series data)
+------------------+------------------+-----------+
| Field            | Type             | Notes     |
+------------------+------------------+-----------+
| name             | String           | e.g. "http.request.duration_ms" |
| value            | double           | the numeric measurement |
| timestamp        | Instant          | when the measurement was taken |
| metricType       | MetricType enum  | COUNTER, GAUGE, HISTOGRAM, TIMER |
| tags             | Map<String,String>| key-value labels (immutable) |
+------------------+------------------+-----------+

Metric (aggregated definition -- groups related MetricPoints)
+------------------+------------------+-----------+
| Field            | Type             | Notes     |
+------------------+------------------+-----------+
| id               | UUID             | primary key |
| name             | String           | metric name (unique + tags = series) |
| metricType       | MetricType enum  | COUNTER, GAUGE, HISTOGRAM, TIMER |
| description      | String           | human-readable description |
| unit             | String           | "ms", "bytes", "%" |
| tags             | Map<String,String>| default labels |
| dataPoints       | List<MetricPoint>| historical data |
| createdAt        | Instant          | when the metric was first seen |
+------------------+------------------+-----------+

MetricType (enum)
  COUNTER    -- monotonically increasing (requests, errors, bytes sent)
  GAUGE      -- point-in-time value, can go up/down (CPU, memory, connections)
  HISTOGRAM  -- distribution of values across buckets (response sizes)
  TIMER      -- duration measurement (request latency, query time)
```

**Key Insight -- Metric Type Semantics**:
```
COUNTER:   Only go UP. Rate queries (requests/sec) computed as delta(value) / delta(time).
           Reset on process restart -> use rate() not raw value.

GAUGE:     Snapshot value. Latest reading is the current state.
           No rate computation -- just display the value.

HISTOGRAM: Distribution. Pre-defined bucket boundaries (10ms, 50ms, 100ms, 500ms, 1s).
           Each observation increments the appropriate bucket counter.
           Percentiles estimated via linear interpolation between buckets.

TIMER:     Special case of HISTOGRAM optimized for duration measurements.
           Records value in milliseconds with automatic P50/P95/P99 computation.
```

### 5.2 Trace Data Model

```
Span (single operation within a trace)
+------------------+------------------+-----------+
| Field            | Type             | Notes     |
+------------------+------------------+-----------+
| traceId          | String (UUID)    | shared across all spans in trace |
| spanId           | String (UUID)    | unique per span |
| parentSpanId     | String (nullable)| null for root span |
| operationName    | String           | "POST /api/orders" |
| serviceName      | String           | "order-service" |
| startTime        | Instant          | when the operation started |
| endTime          | Instant          | when the operation finished |
| duration         | Duration         | endTime - startTime |
| status           | SpanStatus enum  | OK, ERROR, TIMEOUT, CANCELLED |
| tags             | Map<String,String>| metadata (http.method, db.type) |
| logs             | List<SpanLog>    | timestamped events within the span |
+------------------+------------------+-----------+

Trace (complete distributed trace -- tree of spans)
+------------------+------------------+-----------+
| Field            | Type             | Notes     |
+------------------+------------------+-----------+
| traceId          | String (UUID)    | unique trace identifier |
| rootSpan         | Span             | entry-point span (parentSpanId == null) |
| spans            | List<Span>       | all spans in the trace |
| serviceName      | String           | service that initiated the trace |
| startTime        | Instant          | earliest span start time |
| duration         | Duration         | latest end - earliest start |
+------------------+------------------+-----------+

TraceContext (propagation carrier for cross-service context)
+------------------+------------------+-----------+
| Field            | Type             | Notes     |
+------------------+------------------+-----------+
| traceId          | String           | propagated across all services |
| spanId           | String           | current active span |
| parentSpanId     | String           | the span that created this context |
| sampled          | boolean          | sampling decision (propagated) |
| baggage          | Map<String,String>| arbitrary metadata (e.g. "error"="true") |
+------------------+------------------+-----------+

SpanStatus (enum): OK, ERROR, TIMEOUT, CANCELLED
```

**Span Tree Example**:
```
Trace abc123:
  POST /api/orders (api-gateway) [250ms]        <- root span (parentSpanId = null)
  |
  +-- createOrder (order-service) [200ms]        <- parentSpanId = root.spanId
  |   |
  |   +-- processPayment (payment-service) [80ms]  <- parentSpanId = createOrder.spanId
  |   |
  |   +-- INSERT orders (postgres) [15ms]          <- parentSpanId = createOrder.spanId
  |
  +-- validateInventory (inventory-service) [30ms]  <- parentSpanId = root.spanId
```

### 5.3 Log Data Model

```
LogEntry (structured log entry with trace correlation)
+------------------+------------------+-----------+
| Field            | Type             | Notes     |
+------------------+------------------+-----------+
| id               | UUID             | primary key |
| timestamp        | Instant          | when the log was created |
| level            | LogLevel enum    | TRACE, DEBUG, INFO, WARN, ERROR, FATAL |
| message          | String           | the log message |
| serviceName      | String           | originating service |
| traceId          | String (nullable)| distributed trace correlation |
| spanId           | String (nullable)| span-level correlation |
| attributes       | Map<String,String>| structured metadata |
+------------------+------------------+-----------+

LogLevel (enum with severity ordering):
  TRACE(0) < DEBUG(1) < INFO(2) < WARN(3) < ERROR(4) < FATAL(5)

  isAtLeast(other) -> this.severity >= other.severity
  Used for level-gated filtering in the log processor pipeline.
```

### 5.4 Alert Data Model

```
AlertRule (declarative rule definition)
+------------------+------------------+-----------+
| Field            | Type             | Notes     |
+------------------+------------------+-----------+
| id               | UUID             | primary key |
| name             | String           | "High CPU Alert" |
| metricName       | String           | "system.cpu.usage_percent" |
| condition        | String           | ">" or "<" |
| threshold        | double           | 80.0 |
| durationSeconds  | int              | 60 (condition must hold this long) |
| severity         | AlertSeverity    | WARNING, CRITICAL |
| enabled          | boolean          | can be disabled without deleting |
+------------------+------------------+-----------+

Alert (fired alert instance)
+------------------+------------------+-----------+
| Field            | Type             | Notes     |
+------------------+------------------+-----------+
| id               | UUID             | primary key |
| rule             | AlertRule        | the rule that fired this alert |
| status           | AlertStatus      | FIRING, ACKNOWLEDGED, RESOLVED |
| triggeredAt      | Instant          | when the alert started firing |
| resolvedAt       | Instant (nullable)| when the condition cleared |
| currentValue     | double           | the metric value that triggered it |
| message          | String           | human-readable description |
+------------------+------------------+-----------+

Alert State Machine:
  FIRING ---------> ACKNOWLEDGED ---------> RESOLVED
    |                                          ^
    +------------------------------------------+
           (auto-resolve when condition clears)
```

### 5.5 Service Map Data Model

```
ServiceNode (vertex in the dependency graph)
+------------------+------------------+-----------+
| Field            | Type             | Notes     |
+------------------+------------------+-----------+
| serviceName      | String           | unique identifier |
| dependencies     | Set<String>      | downstream services this node calls |
| dependents       | Set<String>      | upstream services that call this node |
| requestCount     | long             | total outgoing requests |
| errorRate        | double           | fraction of failed requests (0.0-1.0) |
| avgLatencyMs     | double           | average call latency |
+------------------+------------------+-----------+

Edge Statistics (per caller->callee pair):
  "order-service->payment-service" -> [totalRequests=500, errors=15, totalLatencyMs=40000]
  Derived: errorRate = 15/500 = 3%, avgLatency = 40000/500 = 80ms
```

---

## 6. High-Level Architecture

### 6.1 System Architecture (ASCII)

```
                    ┌─────────────────────────────────────────────────────┐
                    │                   CLIENT LAYER                       │
                    │  ┌──────────┐  ┌──────────┐  ┌──────────┐          │
                    │  │ App SDK  │  │ Agent    │  │ Browser  │          │
                    │  │ (Java/   │  │ (host-   │  │ (RUM     │          │
                    │  │  Go/Py)  │  │  level)  │  │  agent)  │          │
                    │  └────┬─────┘  └────┬─────┘  └────┬─────┘          │
                    └───────┼─────────────┼─────────────┼─────────────────┘
                            │             │             │
              ┌─────────────▼─────────────▼─────────────▼──────────────────┐
              │                    INGESTION LAYER                          │
              │  ┌──────────────────────────────────────────────────────┐   │
              │  │              Load Balancer (L7)                      │   │
              │  └──────────────────┬───────────────────────────────────┘   │
              │                     │                                       │
              │  ┌──────────┬───────┴────────┬──────────┐                  │
              │  │ Metric   │ Trace          │ Log      │                  │
              │  │ Ingestor │ Ingestor       │ Ingestor │  (stateless,     │
              │  │          │                │          │   horizontally    │
              │  │ - validate│ - validate     │ - validate│  scalable)      │
              │  │ - tag    │ - sampling     │ - level  │                  │
              │  │   limits │   decision     │   filter │                  │
              │  │ - batch  │ - context      │ - enrich │                  │
              │  │          │   propagation  │   w/trace│                  │
              │  └────┬─────┘────┬───────────┘────┬─────┘                  │
              └───────┼──────────┼────────────────┼────────────────────────┘
                      │          │                │
              ┌───────▼──────────▼────────────────▼────────────────────────┐
              │                    MESSAGE QUEUE                            │
              │  ┌──────────────────────────────────────────────────────┐   │
              │  │  Kafka (partitioned by metric name / traceId)       │   │
              │  │                                                      │   │
              │  │  topic: metrics.raw    (partition by metric_name)    │   │
              │  │  topic: traces.spans   (partition by traceId)       │   │
              │  │  topic: logs.raw       (partition by serviceName)   │   │
              │  └─────┬────────────┬───────────────┬──────────────────┘   │
              └────────┼────────────┼───────────────┼──────────────────────┘
                       │            │               │
              ┌────────▼────────────▼───────────────▼──────────────────────┐
              │                    PROCESSING LAYER                         │
              │                                                             │
              │  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐       │
              │  │ Metric       │ │ Trace        │ │ Log          │       │
              │  │ Processor    │ │ Processor    │ │ Processor    │       │
              │  │              │ │              │ │              │       │
              │  │ - aggregate  │ │ - assemble   │ │ - filter     │       │
              │  │ - downsample │ │   span trees │ │   pipeline   │       │
              │  │ - cardinality│ │ - tail-sample│ │ - correlate  │       │
              │  │   check     │ │ - dep graph  │ │   traceId    │       │
              │  │ - rollup    │ │              │ │ - index      │       │
              │  └──────┬───────┘ └──────┬───────┘ └──────┬───────┘       │
              └─────────┼────────────────┼────────────────┼────────────────┘
                        │                │                │
              ┌─────────▼────────────────▼────────────────▼────────────────┐
              │                    STORAGE LAYER                            │
              │                                                             │
              │  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐       │
              │  │ Time-Series  │ │ Trace Store  │ │ Log Store    │       │
              │  │ DB           │ │              │ │              │       │
              │  │ InfluxDB /   │ │ Elasticsearch│ │ Elasticsearch│       │
              │  │ VictoriaM.   │ │ / Cassandra  │ │ / Loki       │       │
              │  │              │ │              │ │              │       │
              │  │ TreeMap<     │ │ traceId ->   │ │ Inverted     │       │
              │  │  epochSec,   │ │ List<Span>   │ │ index on     │       │
              │  │  List<Point>>│ │              │ │ traceId,     │       │
              │  │              │ │              │ │ service,     │       │
              │  │              │ │              │ │ level        │       │
              │  └──────┬───────┘ └──────┬───────┘ └──────┬───────┘       │
              └─────────┼────────────────┼────────────────┼────────────────┘
                        │                │                │
              ┌─────────▼────────────────▼────────────────▼────────────────┐
              │                    QUERY LAYER                              │
              │                                                             │
              │  ┌──────────────────────────────────────────────────────┐   │
              │  │ Query Engine (federated across all three stores)     │   │
              │  │                                                      │   │
              │  │ - MetricAggregator (sum, avg, percentile, rate)     │   │
              │  │ - TraceAssembler (build span trees, waterfall view) │   │
              │  │ - LogSearch (full-text + structured + correlation)  │   │
              │  └──────────────────────┬───────────────────────────────┘   │
              └─────────────────────────┼──────────────────────────────────┘
                                        │
              ┌─────────────────────────▼──────────────────────────────────┐
              │                    APPLICATION LAYER                        │
              │                                                             │
              │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐     │
              │  │ Dashboard │ │ Alert    │ │ Service  │ │ API      │     │
              │  │ Service   │ │ Service  │ │ Map      │ │ Gateway  │     │
              │  │           │ │          │ │ Service  │ │          │     │
              │  │ RED/USE   │ │ threshold│ │ topology │ │ REST     │     │
              │  │ method    │ │ anomaly  │ │ from     │ │ endpoints│     │
              │  │ panels    │ │ detection│ │ traces   │ │          │     │
              │  └──────────┘ └──────────┘ └──────────┘ └──────────┘     │
              └────────────────────────────────────────────────────────────┘
```

### 6.2 Component Interaction Flow

**Flow 1 -- Metric Ingestion (numbered steps)**:

```
1. App SDK emits metric: recordCounter("http.requests.total", 1, {method: "GET"})
2. Metric Ingestor validates tags, checks cardinality limits
3. If cardinality OK: write to Kafka topic `metrics.raw` (partition by metric_name hash)
4. If cardinality exceeded: drop high-card tags or reject with 429
5. Metric Processor consumes from Kafka, creates MetricPoint
6. MetricPoint stored in TimeSeriesStore (TreeMap bucketed by epochSecond)
7. Metric definition upserted in MetricRepository (if new metric name)
8. Downsampling job runs periodically: raw -> 1-min avg -> 5-min avg -> 1-hour avg
```

**Flow 2 -- Distributed Trace Lifecycle (numbered steps)**:

```
1. API Gateway creates TraceContext.newTrace() -> fresh traceId + spanId
2. SamplingStrategy.shouldSample(context) -> head-based: hash(traceId) % 100 < rate
3. Root Span created: Span.Builder(traceId, "POST /api/orders", "api-gateway").build()
4. API Gateway propagates context via HTTP header: traceparent: 00-{traceId}-{spanId}-01
5. Order Service extracts traceId from header, creates child Span with parentSpanId = root.spanId
6. Each service finishes spans: span.finish() stamps endTime, computes duration
7. Finished spans sent to TraceAssembler -> buffered in ConcurrentHashMap<traceId, List<Span>>
8. TraceAssembler.assembleTrace(traceId) -> finds root span, builds Trace, removes from buffer
9. Assembled Trace saved to TraceRepository
10. ServiceMapService.registerCall() updates dependency graph from span parent-child relationships
```

**Flow 3 -- Alert Evaluation (numbered steps)**:

```
1. AlertService.evaluateRules() runs on a 60-second schedule
2. For each enabled AlertRule:
3.   Query MetricService for last 60 seconds of metric data
4.   Pass data points to AlertingStrategy.shouldAlert(rule, points)
5.   ThresholdStrategy: compute avg(points), compare against rule.threshold with rule.condition
6.   AnomalyStrategy: compute mean + stdDev, check if latest point > mean + k*stdDev
7.   If should alert AND rule not already firing:
8.     Create Alert(rule, currentValue, message) with status=FIRING
9.     Save to AlertRepository, update firingRules map
10.    Route notification (PagerDuty, Slack, email)
11.  If should NOT alert AND rule currently firing:
12.    Resolve existing alert: status -> RESOLVED, stamp resolvedAt
```

**Flow 4 -- Cross-Pillar Investigation (the "glue")**:

```
1. Dashboard shows: error rate spike on order-service (metric: http.errors.total rate > 5/sec)
2. Click metric -> drill into traces for order-service in the same time window
3. See Trace abc123 with ERROR span on payment-service (processPayment timed out)
4. Click span -> show logs correlated with traceId=abc123, spanId=payment-span
5. Log entry: "Connection pool exhausted: max connections=50, active=50"
6. Root cause: payment-service database connection pool too small
7. Service map shows: order-service -> payment-service is the failing edge (error rate 15%)
```

---

## 7. Three Pillars Deep Dive

### 7.1 Metrics Pillar

**What Metrics Answer**: "WHAT is happening right now?"

```
Metric Types and Their Semantics:

COUNTER (monotonically increasing)
  Example: http.requests.total = 1, 2, 3, 4, 5...
  Query pattern: rate(http.requests.total[5m]) -> requests per second
  Key insight: Raw counter value is useless after restart. Always use rate().
  Implementation: recordCounter("name", increment, tags) -> MetricService -> TimeSeriesStore

GAUGE (point-in-time snapshot)
  Example: system.cpu.usage_percent = 42.3, 38.1, 55.7, 41.2...
  Query pattern: avg(system.cpu.usage_percent[5m]) -> average CPU over 5 minutes
  Key insight: Latest value IS the current state. No rate computation needed.
  Implementation: recordGauge("name", value, tags) -> MetricService -> TimeSeriesStore

HISTOGRAM (value distribution)
  Example: http.response.size_bytes = 512, 1024, 2048, 4096, 512...
  Query pattern: histogram_quantile(0.99, http.response.size_bytes) -> P99 response size
  Key insight: Pre-defined bucket boundaries (Prometheus: le="10", le="50", le="100"...)
  Implementation: recordHistogram("name", value, tags) -> MetricAggregator.histogram(points, boundaries)

TIMER (duration measurement)
  Example: http.request.duration_ms = 45.2, 120.5, 23.1, 89.7...
  Query pattern: percentile(http.request.duration_ms, 99) -> P99 latency
  Key insight: TIMER is a HISTOGRAM specialized for duration. Same storage, different semantics.
  Implementation: recordTimer("name", durationMs, tags) -> MetricAggregator.percentile(points, 99)
```

**Metric Collection Flow in This Codebase**:

```
MetricService.recordMetric(name, value, type, tags)
  |
  +-- 1. Create MetricPoint: MetricPoint.of(name, value, type, tags)  // stamps Instant.now()
  |
  +-- 2. Store in TimeSeriesStore:
  |      store.computeIfAbsent(name, TreeMap::new)
  |           .computeIfAbsent(epochSecond, ArrayList::new)
  |           .add(point)
  |
  +-- 3. Create Metric definition if new:
  |      if (metricRepo.findByName(name).isEmpty())
  |        metricRepo.save(new Metric.Builder(name, type).tags(tags).build())
  |
  +-- 4. Print: [METRIC] Recorded TIMER 'http.request.duration_ms' = 45.2
```

### 7.2 Tracing Pillar

**What Traces Answer**: "WHERE is the problem in the call chain?"

```
Distributed Trace Concepts:

TRACE = a tree of SPANS representing one end-to-end request
  - Unique traceId shared by ALL spans in the trace
  - Has exactly one root span (parentSpanId == null)
  - Duration = latest endTime - earliest startTime

SPAN = one operation within one service
  - Has a unique spanId
  - Has a parentSpanId (null for root, parent's spanId for children)
  - Carries: operationName, serviceName, startTime, endTime, status, tags, logs

CONTEXT PROPAGATION = how traceId travels across service boundaries
  - W3C TraceContext: traceparent header = "00-{traceId}-{spanId}-01"
  - Baggage: arbitrary key-value metadata propagated with the context
  - This codebase: TraceContext.createChild(newSpanId) preserves traceId, sets new parentSpanId
```

**Trace Assembly Flow in This Codebase**:

```
TracingService.startTrace("POST /api/orders", "api-gateway")
  |
  +-- 1. Create TraceContext.newTrace() -> fresh traceId + spanId
  |
  +-- 2. Sampling decision: samplingStrategy.shouldSample(context, operationName)
  |      Head-based: hash(traceId) % 100 < sampleRate * 100
  |
  +-- 3. Build root Span: new Span.Builder(traceId, operationName, serviceName).build()
  |
  +-- 4. Return rootSpan to caller

TracingService.startSpan(traceId, parentSpanId, "createOrder", "order-service")
  |
  +-- Build child Span: Span.Builder(traceId, opName, service).parentSpanId(parentId).build()

TracingService.finishSpan(span)
  |
  +-- 1. span.finish() -> stamp endTime, compute duration
  |
  +-- 2. assembler.addSpan(span) -> buffer in pendingSpans
  |
  +-- 3. assembler.assembleTrace(traceId) -> find root span among pending
  |      If root found: build Trace from all spans, remove from pending
  |      If no root yet: return Optional.empty() (keep buffering)
  |
  +-- 4. If assembled: traceRepo.save(trace), remove from activeContexts
```

**Span Tree Visualization**:

```
Trace: POST /api/orders (250ms total)
  |
  [0ms]─────────────────────────────────────────────[250ms]
  api-gateway: POST /api/orders
    |
    [10ms]──────────────────────────────[210ms]
    order-service: createOrder
      |
      [15ms]──────────[95ms]
      payment-service: processPayment
      |
      [100ms]──[115ms]
      postgres: INSERT orders
    |
    [215ms]──[245ms]
    inventory-service: validateInventory
```

### 7.3 Logging Pillar

**What Logs Answer**: "WHY did this specific thing happen?"

```
Structured Log Entry:

{
  "timestamp": "2024-01-01T00:30:00.123Z",
  "level": "WARN",
  "service": "user-service",
  "message": "Slow query: 250ms for user lookup",
  "traceId": "abc123def456...",         <-- CORRELATION: links to trace pillar
  "spanId": "span-db-query-001",        <-- CORRELATION: links to specific span
  "attributes": {
    "query": "SELECT * FROM users WHERE id = ?",
    "duration_ms": "250",
    "db.type": "postgresql"
  }
}
```

**Log Processing Pipeline in This Codebase**:

```
LogService.logWithTrace(WARN, "Slow query: 250ms", "user-service", traceId, spanId)
  |
  +-- 1. Create LogEntry(WARN, message, serviceName)
  |      Set traceId and spanId for correlation
  |
  +-- 2. LogProcessor.process(entry) -- Chain of Responsibility:
  |      |
  |      +-- Level gate: entry.getLevel().isAtLeast(minLevel)?
  |      |   WARN(3) >= INFO(2) -> YES, pass
  |      |
  |      +-- Custom filters: for each Predicate<LogEntry> -> test(entry)?
  |      |   All pass -> YES, entry survives
  |      |
  |      +-- Return Optional.of(entry)
  |
  +-- 3. logRepo.save(entry) -> persisted for querying
  |
  +-- 4. Print: [LOG] [WARN] user-service: Slow query: 250ms (traceId=abc123)
```

**Cross-Pillar Correlation -- The "Glue"**:

```
The three pillars are connected by correlation IDs:

  Metrics: http.request.duration_ms = 250ms (tags: {service: "user-service"})
                                              ^
                                              | same service name
                                              |
  Traces:  Trace abc123 -> Span "db-query" (user-service, 250ms, OK)
                                              ^
                                              | same traceId + spanId
                                              |
  Logs:    [WARN] user-service: Slow query: 250ms (traceId=abc123, spanId=db-query)

  Investigation flow:
    1. Dashboard: P99 latency spike on user-service (METRIC)
    2. Drill into traces for user-service in that time window (TRACE)
    3. Find Trace abc123 with slow span (250ms on db-query)
    4. Click span -> see correlated logs with traceId=abc123 (LOG)
    5. Log says: "Slow query: SELECT * FROM users WHERE id = ?" -- missing index!
```

---

## 8. Time-Series Storage Design

### 8.1 Bucketed TreeMap Architecture

```
TimeSeriesStore.store (the core data structure):

Map<String, TreeMap<Long, List<MetricPoint>>> store
 |           |              |
 |           |              +-- List of points recorded in this second
 |           +-- epoch second -> points (sorted by time, O(log n) seeks)
 +-- metric name -> time-bucketed data

Example state after ingesting 5 points:

store = {
  "http.request.duration_ms" -> TreeMap {
    1704067200 -> [MetricPoint(45.2, GET), MetricPoint(120.5, POST)],
    1704067201 -> [MetricPoint(23.1, GET)],
    1704067203 -> [MetricPoint(89.7, GET), MetricPoint(55.0, POST)]
  },
  "system.cpu.usage_percent" -> TreeMap {
    1704067200 -> [MetricPoint(42.3, prod-1)],
    1704067201 -> [MetricPoint(38.1, prod-1)],
    ...
  }
}
```

### 8.2 Range Query Using TreeMap.subMap()

```
query("http.request.duration_ms", from=1704067200, to=1704067203)

Implementation:
  1. Look up TreeMap for metric name: O(1) HashMap lookup
  2. TreeMap.subMap(fromEpochSec, toEpochSec + 1): O(log n) for seek
  3. Iterate over matching buckets: O(k) where k = matching buckets
  4. Flatten all points: stream().flatMap(List::stream).collect()

  Total: O(log n + k) where n = total time buckets, k = matching buckets

Why TreeMap (not HashMap)?
  - HashMap: O(1) point lookup, but range query = iterate ALL keys + filter = O(n)
  - TreeMap: O(log n) range seek via subMap(), then O(k) for iteration
  - For time-series data, range queries dominate (dashboards always query time windows)
```

### 8.3 Downsampling Strategy

```
Downsampling: compress historical data by averaging within time buckets

Raw data (1 point/sec for 1 hour):
  [45.2, 120.5, 23.1, 89.7, 55.0, 78.3, ...] = 3,600 points

After 1-minute downsampling (60 points -> 1 avg):
  [68.4, 72.1, 65.3, ...] = 60 points

After 5-minute downsampling (300 points -> 1 avg):
  [69.2, 67.8, ...] = 12 points

After 1-hour downsampling (3600 points -> 1 avg):
  [68.9] = 1 point

Storage reduction: 3600x per hour

Retention policy (typical production):
  Raw data:     keep for 1 hour    (real-time debugging)
  1-min avg:    keep for 24 hours  (recent investigation)
  5-min avg:    keep for 7 days    (weekly trends)
  1-hour avg:   keep for 1 year    (capacity planning)

Implementation in this codebase:
  TimeSeriesStore.downsample(metricName, Duration.ofMinutes(5))
    1. Iterate all points for the metric
    2. Group by bucket key: (epochSecond / bucketSeconds) * bucketSeconds
    3. Average each bucket: bucketPoints.stream().mapToDouble(getValue).average()
    4. Create representative MetricPoint with bucket start timestamp
    5. Return list of downsampled points
```

### 8.4 Production TSDB Comparison

```
+------------------+------------------+------------------+------------------+
| Feature          | InfluxDB         | VictoriaMetrics  | TimescaleDB      |
+------------------+------------------+------------------+------------------+
| Storage engine   | TSM (time-       | MergeTree with   | PostgreSQL       |
|                  |  structured      |  dedup + LZ4     |  hypertables +   |
|                  |  merge tree)     |  compression     |  chunk compress  |
+------------------+------------------+------------------+------------------+
| Write throughput | 1M pts/sec       | 10M pts/sec      | 500K pts/sec     |
+------------------+------------------+------------------+------------------+
| Compression      | 10-20x (gorilla  | 10-70x (ZSTD +   | 10-20x (native   |
|                  |  + Snappy)       |  dedup)          |  compression)    |
+------------------+------------------+------------------+------------------+
| Query language   | InfluxQL / Flux  | MetricsQL        | SQL              |
|                  |                  |  (PromQL compat) |                  |
+------------------+------------------+------------------+------------------+
| High cardinality | Poor (inverted   | Good (bitmap     | Good (SQL        |
|                  |  index bloat)    |  indexes)        |  indexes)        |
+------------------+------------------+------------------+------------------+
| Best for         | General TSDB     | Prometheus       | SQL-native       |
|                  |                  |  long-term store |  teams           |
+------------------+------------------+------------------+------------------+

This codebase uses: In-memory TreeMap (functionally equivalent to a single-node TSDB)
Production recommendation: VictoriaMetrics (handles high cardinality, PromQL-compatible)
```

---

## 9. Sampling Strategies

### 9.1 Head-Based Sampling

```
WHEN: Decision made at trace CREATION time (the "head" of the trace)
HOW:  Hash the traceId, compare against rate threshold
WHY:  Simple, consistent, zero cross-service coordination

Algorithm:
  1. hash = Math.abs(traceId.hashCode())
  2. bucket = hash % 100
  3. if bucket < (sampleRate * 100) -> SAMPLE
  4. else -> DROP

Properties:
  + Deterministic: same traceId always gets same decision
  + Consistent: all services see the same decision (propagated via sampled flag)
  + No coordination: each service independently computes the same hash
  + Low overhead: one hash computation per trace, O(1)

  - Blind: drops interesting traces (errors, slow) at the same rate as normal ones
  - Cannot retroactively keep a trace that turns out to be interesting
  - At 10% sample rate, you lose 90% of error traces

When to use:
  Baseline sampling for high-volume services. Set to 10-50% depending on budget.
  Always combine with tail-based for error retention.

Code path:
  HeadBasedSamplingStrategy.shouldSample(context)
    -> Math.abs(context.getTraceId().hashCode()) % 100 < (sampleRate * 100)
```

### 9.2 Tail-Based Sampling

```
WHEN: Decision made AFTER the trace completes (the "tail")
HOW:  Buffer ALL spans, examine completed trace for errors/latency, keep interesting ones
WHY:  Never loses error traces or slow traces

Algorithm:
  1. shouldSample(context) -> always returns TRUE (collect everything initially)
  2. After trace assembly, examine the complete trace:
     - If any span has error=true in baggage -> KEEP (errors are always interesting)
     - If total trace latency > threshold -> KEEP (slow traces matter for debugging)
     - Otherwise -> DROP (normal traces are not interesting)

Properties:
  + Never loses error traces (100% error retention)
  + Never loses slow traces (latency-based retention)
  + Post-hoc decision means full information available

  - Memory intensive: must buffer ALL spans for every trace until assembly
  - At 100K spans/sec with 30s assembly timeout: 3M spans in memory
  - Adds latency: trace data is not available until assembly completes
  - Requires a centralized collector (all spans for a trace must reach the same node)

When to use:
  Second layer after head-based. Head-based drops 90% of traces upfront,
  tail-based ensures the remaining 10% includes all errors and slow traces.

Code path:
  TailBasedSamplingStrategy.shouldSample(context) -> true (always buffer)
  TailBasedSamplingStrategy.shouldSample(context, operationName)
    -> check baggage "error" flag, check baggage "latency_ms" > threshold
```

### 9.3 Rate-Limited Sampling

```
WHEN: Decision made at ingestion time with a per-second cap
HOW:  AtomicInteger counter resets every epoch second, allow first N traces per second
WHY:  Predictable cost ceiling regardless of traffic volume

Algorithm:
  1. Read current epoch second: now = System.currentTimeMillis() / 1000
  2. If now != windowStart -> new second window:
     - CAS: windowStart.compareAndSet(window, now)
     - Reset counter to 0
  3. If counter.incrementAndGet() <= maxPerSecond -> SAMPLE
  4. Else -> DROP (rate limit exceeded)

Properties:
  + Predictable cost: exactly N traces/sec maximum, regardless of traffic spikes
  + Thread-safe: AtomicInteger + AtomicLong with CAS operations
  + No memory overhead: just two atomic variables

  - Not intelligent: first N traces per second may be all health checks
  - Traffic spike during the second means later (potentially more interesting) traces are dropped
  - Benign race condition: two threads may both reset the counter (results in slightly more samples)

When to use:
  Third layer as a safety valve. If head-based (10%) + tail-based (errors) still produces
  too many traces during a traffic spike, rate-limited caps at N/sec to prevent cost explosion.

Code path:
  RateLimitedSamplingStrategy.shouldSample(context)
    -> check/reset window, incrementAndGet(), compare to maxPerSecond
```

### 9.4 Layered Sampling Architecture (Production)

```
                    Incoming request
                          |
                          v
                 ┌─────────────────┐
                 │  Head-Based     │  First layer: reduce volume 10x
                 │  (10% rate)     │  Consistent hash of traceId
                 │                 │  90% dropped immediately
                 └────────┬────────┘
                          | 10% pass through
                          v
                 ┌─────────────────┐
                 │  Tail-Based     │  Second layer: keep interesting traces
                 │  (errors + slow)│  Buffer remaining spans
                 │                 │  Keep if: error=true OR latency > 500ms
                 │                 │  Drop: normal, fast traces
                 └────────┬────────┘
                          | ~2-5% of original (errors + slow + 10% baseline)
                          v
                 ┌─────────────────┐
                 │  Rate-Limited   │  Third layer: cost ceiling
                 │  (1000/sec max) │  Safety valve for traffic spikes
                 │                 │  Prevents cost explosion during DDoS/peak
                 └────────┬────────┘
                          | max 1000/sec
                          v
                    Store in TSDB

Result:
  - 100% of error traces retained (tail-based)
  - 100% of slow traces retained (tail-based)
  - 10% baseline sampling of normal traces (head-based)
  - Maximum 1000 traces/sec stored regardless of traffic (rate-limited)
  - Cost is predictable and bounded
```

---

## 10. Alerting Pipeline

### 10.1 Threshold-Based Alerting

```
Algorithm:
  1. For each enabled AlertRule:
  2.   Query MetricService for data in [now - 60s, now]
  3.   Compute average of all data points: points.stream().mapToDouble(getValue).average()
  4.   Parse condition from rule: ">" or "<"
  5.   Compare: average > threshold (or average < threshold for "<")
  6.   If TRUE and not already firing: create Alert(FIRING), save to repository
  7.   If FALSE and currently firing: resolve alert (status -> RESOLVED)

Example:
  Rule: "High CPU Alert" | metric=system.cpu.usage_percent | condition=> | threshold=80
  Recent data: [82, 85, 88, 91, 94, 87, 83, 86, 90, 93]
  Average: 87.9
  87.9 > 80.0 -> TRUE -> FIRE ALERT

Code path:
  AlertService.evaluateRules()
    -> for each rule: metricService.query(rule.metricName, windowStart, now)
    -> alertingStrategy.shouldAlert(rule, points)
    -> ThresholdAlertingStrategy: average(points) > threshold

Limitations:
  - Requires manual threshold tuning per metric
  - Fails for metrics with natural variation (traffic varies 10x peak/trough)
  - No awareness of seasonality (weekend vs weekday, 3am vs 3pm)
  - Alert fatigue when threshold is too sensitive
```

### 10.2 Anomaly Detection Alerting

```
Algorithm:
  1. Require at least 3 data points (need enough for meaningful stdDev)
  2. Compute mean: sum(values) / count
  3. Compute population standard deviation:
     variance = sum((value - mean)^2) / count
     stdDev = sqrt(variance)
  4. Check latest point: deviation = |latest.value - mean|
  5. If deviation > (stdDevMultiplier * stdDev) -> ANOMALY -> FIRE ALERT

Example:
  Recent data: [50, 52, 51, 53, 50, 54, 52, 51, 50, 53, 500]  <- spike!
  Mean: 51.45 (heavily influenced by the 500 spike)
  Without spike, mean would be ~51.6
  StdDev: ~131.5
  Latest value: 500
  Deviation: |500 - 51.45| = 448.55
  Threshold (k=2): 2 * 131.5 = 263.0
  448.55 > 263.0 -> ANOMALY DETECTED -> FIRE

Code path:
  AnomalyDetectionAlertingStrategy.shouldAlert(rule, recentPoints)
    -> compute mean, variance, stdDev
    -> check |latest - mean| > stdDevMultiplier * stdDev

Advantages over threshold:
  + No manual threshold tuning required
  + Adapts to changing baselines (traffic growth, seasonal patterns)
  + Catches both upward spikes AND downward drops
  + Reduces alert fatigue (only fires on true deviations)

Production improvements:
  - EWMA (Exponentially Weighted Moving Average): gives more weight to recent data
  - Holt-Winters: handles seasonality (daily, weekly patterns)
  - Prophet (Facebook): ML-based with holidays, changepoints, trend
  - DDSketch: streaming anomaly detection on percentile metrics
```

### 10.3 Alert State Machine

```
                    Rule evaluation
                    (every 60 seconds)
                          |
                          v
                   ┌──────────────┐
                   │  Condition   │
                   │  met?        │
                   └──────┬───────┘
                     yes  │   no
                ┌─────────┘   └─────────┐
                v                       v
         ┌──────────┐           ┌──────────┐
         │ FIRING   │           │ (no      │
         │          │           │  change)  │
         │ Page     │           └──────────┘
         │ operator │
         └────┬─────┘
              │ operator clicks "acknowledge"
              v
         ┌──────────────┐
         │ ACKNOWLEDGED │
         │              │
         │ Operator     │
         │ is looking   │
         └────┬─────────┘
              │ condition clears OR manual resolve
              v
         ┌──────────┐
         │ RESOLVED │
         │          │
         │ Stamp    │
         │ resolvedAt│
         └──────────┘

Implementation:
  Alert.status = FIRING | ACKNOWLEDGED | RESOLVED
  alert.acknowledge() -> status = ACKNOWLEDGED
  alert.resolve() -> status = RESOLVED, resolvedAt = Instant.now()

AlertService tracks firing rules:
  Map<String, String> firingRules = { ruleId -> alertId }
  On evaluation:
    - shouldAlert=true AND rule NOT in firingRules -> create Alert, add to map
    - shouldAlert=false AND rule IN firingRules -> remove from map, resolve alert
```

### 10.4 Alert Fatigue Prevention

```
Problem: Too many alerts -> engineers ignore all alerts -> real outages missed

Solutions:
  1. SEVERITY LEVELS:
     - WARNING: investigate when convenient (dashboard, Slack channel)
     - CRITICAL: page operator immediately (PagerDuty, phone call)
     - Rule: CRITICAL alerts should fire < 1x per week per team

  2. ALERT DEDUPLICATION:
     - Same rule fires only once (tracked via firingRules map)
     - No repeated pages for the same ongoing condition
     - This codebase: firingRules.containsKey(rule.getId()) prevents duplicates

  3. ANOMALY DETECTION:
     - Replace static thresholds with dynamic baselines
     - Metric varies naturally? Use mean +/- 2*stdDev instead of hardcoded 80%
     - This codebase: swap via setAlertingStrategy(new AnomalyDetectionAlertingStrategy(2.0))

  4. ALERT AGGREGATION:
     - 50 servers all have high CPU? -> ONE alert: "High CPU on 50/100 servers"
     - Not 50 separate pages

  5. MAINTENANCE WINDOWS:
     - Suppress alerts during planned maintenance (deployments, DB migrations)
     - Auto-resume after window closes

  6. RUNBOOKS:
     - Every alert rule MUST link to a runbook
     - Runbook: what does this alert mean? How to investigate? How to fix?
     - If you can't write a runbook, the alert is too vague
```

---

## 11. Service Dependency Mapping

### 11.1 Graph Construction from Traces

```
Every distributed trace implicitly encodes service-to-service calls:

Trace abc123:
  api-gateway (root span)
    |
    +-- order-service (child span, parentSpanId = root.spanId)
    |     |
    |     +-- payment-service (grandchild span)
    |     |
    |     +-- postgres (grandchild span)
    |
    +-- user-service (child span)

Extracted edges:
  api-gateway -> order-service
  api-gateway -> user-service
  order-service -> payment-service
  order-service -> postgres

Implementation in this codebase:
  ServiceMapService.registerCall(callerService, calleeService, latencyMs, success)
    |
    +-- 1. Get or create ServiceNode for both caller and callee
    +-- 2. caller.addDependency(calleeService)   // downstream edge
    +-- 3. callee.addDependent(callerService)     // upstream edge (reverse)
    +-- 4. Update edge stats: edgeKey = "caller->callee"
    |      stats[0]++ (total requests)
    |      if (!success) stats[1]++ (errors)
    |      stats[2] += latencyMs (cumulative latency)
    +-- 5. Recalculate node-level stats from all outgoing edges
```

### 11.2 Service Map Topology

```
=== SERVICE DEPENDENCY MAP ===

\-- api-gateway (requests=3, errorRate=11.1%, avgLatency=63.3ms)
    |-- user-service (requests=2, errorRate=0.0%, avgLatency=9.0ms)
    |   |-- postgres
    |   \-- redis
    \-- order-service (requests=1, errorRate=0.0%, avgLatency=42.3ms)
        |-- payment-service
        |   |-- stripe-api
        |   \-- redis
        |-- inventory-service
        \-- postgres

--- Edge Statistics ---
  api-gateway->user-service    | requests=1 | errorRate=0.0% | avgLatency=25.0ms
  api-gateway->order-service   | requests=2 | errorRate=50.0% | avgLatency=82.5ms
  order-service->payment-service | requests=1 | errorRate=0.0% | avgLatency=80.0ms
  order-service->postgres      | requests=1 | errorRate=0.0% | avgLatency=12.0ms
  payment-service->stripe-api  | requests=1 | errorRate=0.0% | avgLatency=200.0ms
  payment-service->redis       | requests=1 | errorRate=0.0% | avgLatency=5.0ms
  user-service->postgres       | requests=1 | errorRate=0.0% | avgLatency=15.0ms
  user-service->redis          | requests=1 | errorRate=0.0% | avgLatency=3.0ms
```

### 11.3 Service Map Use Cases

```
1. BLAST RADIUS ANALYSIS:
   Question: "If payment-service goes down, what is affected?"
   Answer: Follow dependents (upstream) recursively:
     payment-service <- order-service <- api-gateway
   Blast radius: all order creation flows are affected.

2. CASCADING FAILURE DETECTION:
   Question: "Why is api-gateway returning errors?"
   Answer: Follow dependencies (downstream) to find the failing edge:
     api-gateway -> order-service (error rate 50%)
     order-service -> payment-service (error rate 0%)
     order-service -> postgres (timeout, error rate 80%)  <- ROOT CAUSE
   Postgres is timing out, causing order-service failures, which cascade to api-gateway.

3. DEPENDENCY CYCLE DETECTION:
   Question: "Are there circular dependencies?"
   Answer: DFS traversal with visited set. If we visit an already-visited node -> cycle.
   Cycles create deadlock risk and make failure analysis harder.
   This codebase: printNode() tracks visited set, prints "(circular ref)" on detection.

4. CRITICAL PATH IDENTIFICATION:
   Question: "What is the slowest path through the system?"
   Answer: Sum latencies along each dependency chain. The chain with maximum total latency
   is the critical path. Optimizing any other path does not improve end-to-end latency.
```

---

## 12. Dashboard Design

### 12.1 RED Method (for Services)

```
RED = Rate, Errors, Duration

For each service (e.g., order-service):

  RATE:     Requests per second = rate(http.requests.total[5m])
            "How busy is this service?"

  ERRORS:   Error percentage = rate(http.errors.total[5m]) / rate(http.requests.total[5m])
            "How often does this service fail?"

  DURATION: Latency percentiles = p50, p95, p99 of http.request.duration_ms
            "How fast is this service?"

Dashboard layout:
  ┌────────────────────────────────────────────────┐
  │  ORDER-SERVICE: RED Dashboard                   │
  ├────────────┬──────────────┬────────────────────┤
  │ Rate       │ Errors       │ Duration            │
  │            │              │                     │
  │ 250 req/s  │ 2.1% error   │ P50: 45ms          │
  │ [sparkline]│ [sparkline]  │ P95: 120ms          │
  │            │              │ P99: 450ms          │
  │            │              │ [sparkline]          │
  └────────────┴──────────────┴────────────────────┘

Implementation:
  DashboardService.getMetricSummary(metricName, Duration.ofMinutes(5))
    -> returns {avg, min, max, p50, p95, p99, count, rate}
```

### 12.2 USE Method (for Resources)

```
USE = Utilization, Saturation, Errors

For each resource (e.g., CPU, memory, disk, network):

  UTILIZATION: % of capacity being used = system.cpu.usage_percent
               "How full is this resource?"

  SATURATION:  Work queued because resource is full = system.cpu.runqueue_length
               "How overloaded is this resource?"

  ERRORS:      Resource errors = system.disk.errors_total
               "Is this resource failing?"

Dashboard layout:
  ┌────────────────────────────────────────────────┐
  │  PROD-SERVER-1: USE Dashboard                   │
  ├────────────┬──────────────┬────────────────────┤
  │ CPU        │ Memory       │ Disk               │
  │            │              │                     │
  │ Util: 42%  │ Util: 68%    │ Util: 75%          │
  │ Sat: 0.2   │ Sat: 0 swap  │ Sat: 2 IO-wait    │
  │ Err: 0     │ Err: 0 OOM   │ Err: 0 bad-sector │
  │ [gauge]    │ [gauge]      │ [gauge]            │
  └────────────┴──────────────┴────────────────────┘

Implementation:
  DashboardService.getSystemOverview()
    -> combines metrics (all metric names + latest values)
    -> traces (recent traces, span counts, durations)
    -> logs (count by level: INFO, WARN, ERROR)
```

---

## 13. High-Cardinality Metric Management

### 13.1 The Cardinality Problem

```
A time series is uniquely identified by: metric_name + tag_combination

Example:
  api.requests{method=GET, path=/users, status=200}  -> 1 time series
  api.requests{method=GET, path=/users, status=404}  -> 1 time series
  api.requests{method=POST, path=/orders, status=201} -> 1 time series

  With 5 methods x 100 paths x 10 statuses x 50 hosts = 250,000 time series
  Each time series: ~3KB active memory (Prometheus head block) = 750 MB for ONE metric name

The explosion:
  Add user_id (10K unique) to tags:
  5 x 100 x 10 x 50 x 10,000 = 2.5 BILLION time series
  = 7.5 TB of active memory. Impossible.

Why it matters:
  - Memory: each active time series consumes head block memory
  - CPU: each query scans/aggregates all matching series
  - Disk: each series has its own data file/block
  - Cost: cloud TSDB (Datadog, Cloudwatch) charge per unique time series per month
```

### 13.2 Mitigation Strategies

```
Strategy 1: DROP HIGH-CARDINALITY TAGS AT INGESTION
  Before: api.requests{user_id=user-12345, endpoint=/api/data}
  After:  api.requests{endpoint=/api/data}
  Action: Remove user_id tag at the metric ingestor layer
  When:   user_id belongs in logs/traces, not metrics

Strategy 2: HASH/BUCKET HIGH-CARDINALITY TAGS
  Before: api.requests{user_id=user-12345}     -> 10K unique series
  After:  api.requests{user_bucket=42}          -> 100 unique series
  Action: user_bucket = hash(user_id) % 100
  When:   Need approximate per-user data but not exact per-user series

Strategy 3: CARDINALITY LIMITS (REJECT)
  Rule: max 10,000 unique label combinations per metric name
  When exceeded: reject new combinations with 429, alert the metric owner
  Implementation: maintain a Set<String> of seen tag combinations per metric name

Strategy 4: SEPARATE HIGH-CARDINALITY STORE
  Low-cardinality metrics -> Prometheus/InfluxDB (optimized for this)
  High-cardinality metrics -> ClickHouse (columnar, handles billions of series)
  Route at ingestion based on tag cardinality estimation

Strategy 5: ADAPTIVE SAMPLING
  If a metric name has >1000 unique label combos:
  Sample 1% of combinations, extrapolate for dashboards
  All combinations still stored in logs for exact lookup

This codebase demonstrates (Demo 11):
  - 50 data points with unique user_id tags ingested
  - Each creates a distinct label combination
  - KEY INSIGHT printed: control at ingestion, not query time
```

---

## 14. Scaling Strategy

### 14.1 Ingestion Layer Scaling

```
Challenge: 1M metric points/sec + 100K spans/sec + 500K logs/sec

Architecture:
  ┌──────────┐     ┌──────────┐     ┌──────────┐
  │ Ingestor │     │ Ingestor │     │ Ingestor │  ... N instances
  │ Node 1   │     │ Node 2   │     │ Node 3   │
  └────┬─────┘     └────┬─────┘     └────┬─────┘
       │                │                │
       v                v                v
  ┌─────────────────────────────────────────────┐
  │           Kafka (partitioned)               │
  │  metrics.raw: 64 partitions                 │
  │  traces.spans: 64 partitions                │
  │  logs.raw: 32 partitions                    │
  └─────────────────────────────────────────────┘

Scaling rules:
  - Ingestors are STATELESS -> scale horizontally behind a load balancer
  - Partition by metric_name hash (metrics) or traceId hash (traces)
  - Add ingestor nodes linearly: 4 nodes handle 4M pts/sec
  - Kafka partitions set to 2x expected max consumer count
```

### 14.2 Storage Layer Scaling

```
Metric Storage:
  - Partition by metric name (consistent hashing)
  - Node 1: metrics A-M, Node 2: metrics N-Z
  - Replication factor 3 for durability
  - Downsampling runs on each partition independently

Trace Storage:
  - Partition by traceId (consistent hashing)
  - All spans for a trace land on the same partition (required for assembly)
  - 7-day retention for raw traces, 30-day for sampled

Log Storage:
  - Partition by serviceName + date (daily rotation)
  - Index on: traceId, serviceName, level, timestamp
  - 30-day retention for full logs, 1-year for aggregated counts
```

### 14.3 Query Layer Scaling

```
Challenge: Dashboard queries scan millions of data points

Solutions:
  1. PRE-AGGREGATION:
     - Recording rules: pre-compute common queries every 60s
     - Example: avg(http.request.duration_ms[5m]) by (service)
     - Store result as a new metric: http.request.duration_ms:avg5m
     - Dashboard reads pre-computed metric instead of raw data

  2. QUERY CACHING:
     - Cache query results in Redis with TTL = query resolution
     - 1-minute resolution dashboard -> cache for 60s
     - Cache key: hash(metricName + timeRange + aggregation)

  3. FEDERATED QUERIES:
     - Split query across storage partitions
     - Each partition computes local result
     - Coordinator merges results
     - Example: P99 = merge sorted lists from all partitions, pick index

  4. QUERY TIERING:
     - Last 1 hour: query raw data (fast, small volume)
     - Last 24 hours: query 1-min rollups
     - Last 7 days: query 5-min rollups
     - Last 1 year: query 1-hour rollups
     - Automatic resolution selection based on time range
```

---

## 15. Database Choices

### 15.1 Storage Mapping

```
+------------------+------------------+------------------+------------------+
| Data Type        | Primary Store    | Query Pattern    | Why              |
+------------------+------------------+------------------+------------------+
| Metrics (raw)    | VictoriaMetrics  | Time-range       | Write-optimized  |
|                  | / InfluxDB       | aggregation      | TSDB, compression|
|                  |                  | (subMap)         | 10-70x, PromQL   |
+------------------+------------------+------------------+------------------+
| Metrics (rolled) | Same TSDB        | Pre-aggregated   | Downsampled data |
|                  |                  | dashboard reads  | uses same engine |
+------------------+------------------+------------------+------------------+
| Traces           | Elasticsearch    | By traceId,      | Full-text search |
|                  | / Jaeger         | by service,      | on operation     |
|                  | (Cassandra)      | by time range    | names + tags     |
+------------------+------------------+------------------+------------------+
| Logs             | Elasticsearch    | Full-text search | Inverted index   |
|                  | / Grafana Loki   | + traceId lookup | for message      |
|                  |                  | + level filter   | search; Loki for |
|                  |                  |                  | label-only index |
+------------------+------------------+------------------+------------------+
| Alert Rules      | PostgreSQL       | CRUD, few rules  | Relational, ACID |
|                  |                  | (100s not 1000s) | for correctness  |
+------------------+------------------+------------------+------------------+
| Alert State      | PostgreSQL       | Status queries,  | Transactional    |
|                  |                  | lifecycle mgmt   | state machine    |
+------------------+------------------+------------------+------------------+
| Service Map      | Neo4j / In-mem   | Graph traversal, | Graph DB for     |
|                  | graph            | dependency walks | topology queries |
+------------------+------------------+------------------+------------------+
| Config/Rules     | PostgreSQL       | Low-volume CRUD  | Relational, ACID |
+------------------+------------------+------------------+------------------+
```

### 15.2 This Codebase (In-Memory Equivalents)

```
+------------------+----------------------------------+---------------------------+
| Production Store | This Codebase Equivalent         | Interface                 |
+------------------+----------------------------------+---------------------------+
| VictoriaMetrics  | TimeSeriesStore (TreeMap<epoch,  | Direct class (no repo     |
|                  |   List<MetricPoint>>)            | interface for TSDB)       |
+------------------+----------------------------------+---------------------------+
| Elasticsearch    | InMemoryTraceRepository          | TraceRepository interface  |
| (traces)         | (ConcurrentHashMap)              |                           |
+------------------+----------------------------------+---------------------------+
| Elasticsearch    | InMemoryLogRepository            | LogRepository interface    |
| (logs)           | (CopyOnWriteArrayList)           |                           |
+------------------+----------------------------------+---------------------------+
| PostgreSQL       | InMemoryMetricRepository         | MetricRepository interface |
| (metric defs)    | (ConcurrentHashMap)              |                           |
+------------------+----------------------------------+---------------------------+
| PostgreSQL       | InMemoryAlertRepository          | AlertRepository interface  |
| (alerts)         | (CopyOnWriteArrayList)           |                           |
+------------------+----------------------------------+---------------------------+
| Neo4j            | ServiceMapService                | Direct class (adjacency   |
| (service map)    | (LinkedHashMap + edgeStats)       | list + edge stats)       |
+------------------+----------------------------------+---------------------------+

Swap pattern (Repository interface):
  Production: new ElasticsearchTraceRepository(esClient)
  Demo:       new InMemoryTraceRepository()
  Both implement: TraceRepository { save(), findById(), findByServiceName(), findAll() }
```

---

## 16. CAP Analysis

### 16.1 CAP Theorem Application

```
Observability Platform CAP Decisions:

METRIC INGESTION PATH: AP (Availability + Partition tolerance)
  Reasoning:
    - A 5-second gap in metrics during a network partition is acceptable
    - Losing a few data points is tolerable (dashboard shows slightly stale data)
    - But rejecting writes (unavailability) means losing observability during an outage
      -- the exact time you need it most
    - Resolution: write to local WAL, backfill when partition heals

METRIC QUERY PATH: AP (Availability + Partition tolerance)
  Reasoning:
    - Dashboard showing data from 10 seconds ago is fine (< 5s freshness NFR)
    - Eventually consistent reads across partitions are acceptable
    - Reading stale data is better than query timeout during an outage

ALERT STATE: CP (Consistency + Partition tolerance)
  Reasoning:
    - A missed critical alert = production outage goes undetected
    - A duplicate alert page = annoying but not dangerous
    - Alert state (FIRING/RESOLVED) MUST be consistent: two nodes should never
      disagree about whether an alert is firing
    - Use PostgreSQL with synchronous replication for alert state
    - Accept brief unavailability during leader failover (< 5s)

TRACE SAMPLING DECISIONS: AP (eventual consistency)
  Reasoning:
    - Head-based sampling is stateless (hash function) -- no consistency needed
    - Tail-based sampling requires all spans for a trace on the same node
    - Partition key = traceId ensures co-location
    - Brief inconsistency (two collectors both process same traceId) results
      in duplicate trace storage -- wasteful but not incorrect

SERVICE MAP: AP (eventual consistency)
  Reasoning:
    - Service map is built from trace data over time
    - A few seconds of stale topology is fine for visualization
    - Missing one edge from the graph temporarily is acceptable
    - Graph converges to accurate state as traces are processed
```

### 16.2 Consistency Model Summary

```
+------------------+--------+------------------------------------------+
| Component        | Model  | Rationale                                |
+------------------+--------+------------------------------------------+
| Metric ingestion | AP     | Cannot lose writes during outages        |
| Metric queries   | AP     | Stale data OK (< 5s freshness)           |
| Alert state      | CP     | Missed alert = correctness violation     |
| Alert rules      | CP     | Rule CRUD requires ACID                  |
| Trace storage    | AP     | Eventual consistency for trace assembly  |
| Log storage      | AP     | Tolerate brief gaps, backfill later      |
| Service map      | AP     | Eventually converges from trace data     |
| Sampling config  | CP     | Rate changes must apply consistently     |
+------------------+--------+------------------------------------------+
```

---

## 17. Cloud Mapping

### 17.1 AWS Architecture

```
+------------------+------------------+------------------------------------------+
| Component        | AWS Service      | Notes                                    |
+------------------+------------------+------------------------------------------+
| Load Balancer    | ALB              | L7, path-based routing to ingestors      |
| Metric Ingestor  | ECS Fargate      | Stateless, auto-scale on CPU/connections |
| Trace Ingestor   | ECS Fargate      | Stateless, auto-scale                    |
| Log Ingestor     | ECS Fargate      | Stateless, auto-scale                    |
| Message Queue    | Amazon MSK       | Managed Kafka, 3-AZ replication          |
| Metric Storage   | Amazon Timestream| Managed TSDB, automatic downsampling     |
| Trace Storage    | Amazon OpenSearch| Managed Elasticsearch for trace search   |
| Log Storage      | Amazon OpenSearch| Inverted index for full-text log search  |
| Alert Rules DB   | Amazon RDS (PG)  | PostgreSQL for ACID alert state          |
| Service Map      | Amazon Neptune   | Managed graph DB for topology queries    |
| Query Cache      | ElastiCache      | Redis for dashboard query caching        |
| Monitoring       | CloudWatch       | Meta-monitoring (monitor the monitor)    |
| Alerting         | SNS + Lambda     | Alert notification routing               |
+------------------+------------------+------------------------------------------+
```

### 17.2 GCP Architecture

```
+------------------+------------------+------------------------------------------+
| Component        | GCP Service      | Notes                                    |
+------------------+------------------+------------------------------------------+
| Load Balancer    | Cloud Load Bal.  | Global L7 with Cloud Armor               |
| Ingestors        | Cloud Run        | Serverless, scale to zero                |
| Message Queue    | Pub/Sub          | Managed messaging, auto-partitioning     |
| Metric Storage   | Cloud Monitoring | Managed TSDB (integrated with GKE)       |
| Trace Storage    | Cloud Trace      | Managed distributed tracing              |
| Log Storage      | Cloud Logging    | Managed log aggregation with BigQuery    |
| Alert Rules DB   | Cloud SQL (PG)   | Managed PostgreSQL                       |
| Query Cache      | Memorystore      | Managed Redis                            |
+------------------+------------------+------------------------------------------+
```

### 17.3 Open-Source Self-Hosted Stack

```
+------------------+------------------+------------------------------------------+
| Component        | Open-Source       | Notes                                   |
+------------------+------------------+------------------------------------------+
| Metric Collection| Prometheus       | Pull-based, scrapes /metrics endpoints   |
| Metric Storage   | VictoriaMetrics  | Long-term Prometheus storage, 10x comp  |
| Trace Collection | OpenTelemetry    | Vendor-neutral SDK + collector           |
| Trace Storage    | Jaeger / Tempo   | Jaeger (Cassandra), Tempo (object store) |
| Log Collection   | Fluentd/Fluent Bit| Log shipper, runs as DaemonSet         |
| Log Storage      | Grafana Loki     | Label-indexed, S3 backend, no full-text |
| Dashboards       | Grafana          | Unified dashboards for all three pillars |
| Alerting         | Alertmanager     | Alert routing, dedup, silencing          |
| Service Mesh     | Istio / Linkerd  | Auto-inject tracing headers, mTLS       |
+------------------+------------------+------------------------------------------+

The "Grafana stack": Prometheus + Loki + Tempo + Grafana = unified open-source observability
```

---

## 18. Failure Scenarios

### 18.1 Failure Analysis

```
SCENARIO 1: Kafka broker goes down
  Impact: Ingestion pipeline stalls for metrics/traces/logs on affected partitions
  Mitigation:
    - Kafka replication factor = 3 (tolerate 2 broker failures)
    - Ingestors buffer in local WAL (write-ahead log) during Kafka unavailability
    - Backfill from WAL when Kafka recovers
    - Alert: "kafka.broker.count < 3" -> CRITICAL

SCENARIO 2: TSDB storage node fails
  Impact: Queries for metrics on that node return errors/stale data
  Mitigation:
    - Replication factor 3 across nodes
    - Read from replica on primary failure (eventually consistent)
    - Re-replication backfills the replacement node
    - Alert: "tsdb.node.count < expected" -> CRITICAL

SCENARIO 3: Trace assembler OOM (too many pending spans in memory)
  Impact: Traces not assembled, tail-based sampling fails
  Mitigation:
    - Assembly timeout: discard pending spans after 60s (incomplete trace)
    - Rate-limited sampling as safety valve (cap total span volume)
    - Backpressure: reject new spans with 429 when pending count exceeds threshold
    - Alert: "trace.assembler.pending_spans > 5M" -> WARNING

SCENARIO 4: Alert service crashes during evaluation
  Impact: Alerts not fired for 1-2 minutes (until restart)
  Mitigation:
    - Run 3 alert evaluator replicas
    - Leader election: exactly one evaluates, others are hot standby
    - If leader fails, standby takes over in < 5s
    - Alert state in PostgreSQL survives evaluator restart
    - Alert: "alertservice.heartbeat.stale" -> CRITICAL (meta-alert)

SCENARIO 5: High-cardinality metric explosion
  Impact: TSDB memory exhaustion, slow queries, high cloud costs
  Mitigation:
    - Cardinality limits at ingestion (reject > 10K combos per metric name)
    - Drop known high-cardinality tags (user_id, request_id) at the ingestor
    - Rate-limit new time series creation (max 1000 new series/minute)
    - Alert: "metric.cardinality > 5000" for any metric name -> WARNING

SCENARIO 6: Network partition between ingestors and storage
  Impact: Metrics/traces/logs not persisted, dashboards show stale data
  Mitigation:
    - Kafka as buffer (decouples ingestors from storage)
    - Ingestors write to Kafka; processors read from Kafka and write to storage
    - Kafka retention = 24 hours (survive long partitions)
    - Dashboard shows "data freshness: 5 minutes ago" warning banner

SCENARIO 7: DDoS or traffic spike (10x normal ingestion volume)
  Impact: Ingestors overwhelmed, increased latency, potential data loss
  Mitigation:
    - Auto-scale ingestor fleet (ECS/K8s HPA on CPU/connection count)
    - Rate-limited sampling kicks in (cap at N traces/sec)
    - Kafka absorbs burst (producers write faster than consumers process)
    - Graceful degradation: drop DEBUG/TRACE logs, keep WARN/ERROR/FATAL
    - Alert: "ingestion.rate > 2x baseline" -> WARNING
```

### 18.2 Operational Runbook Summary

```
+------------------+------------------+------------------------------------------+
| Alert            | First Response   | Escalation                               |
+------------------+------------------+------------------------------------------+
| High CPU         | Check top-k      | Scale out if sustained; investigate      |
| (system.cpu>80%) | metrics by rate  | hotspot queries if localized             |
+------------------+------------------+------------------------------------------+
| Ingestion lag    | Check Kafka      | Scale ingestor fleet; check for          |
| (>60s behind)    | consumer lag     | serialization errors in dead-letter      |
+------------------+------------------+------------------------------------------+
| Alert evaluator  | Check pod health | Restart pod; verify leader election      |
| heartbeat stale  | and logs         | succeeded; check PG connectivity         |
+------------------+------------------+------------------------------------------+
| Cardinality      | Identify metric  | Add tag to drop-list at ingestor;        |
| limit exceeded   | name, audit tags | notify metric owner to fix SDK           |
+------------------+------------------+------------------------------------------+
| Trace assembler  | Check pending    | Increase timeout; add rate-limiting;     |
| memory high      | span count       | investigate if single trace has 10K+     |
|                  |                  | spans (fan-out query)                    |
+------------------+------------------+------------------------------------------+
| Query timeout    | Check slow query | Add pre-aggregation rule; increase       |
| (>5s)            | log; identify    | query tier (use rollups); add cache      |
|                  | metric + range   |                                          |
+------------------+------------------+------------------------------------------+
```

---

## 19. Interview Walkthrough Script

### 19.1 Opening (2 minutes)

> "I'll design an observability platform like Datadog that provides three correlated signals: metrics, traces, and logs. The key insight is that these three pillars are connected by correlation IDs -- a traceId links a metric anomaly to the specific distributed trace to the relevant log lines. I'll cover the time-series storage engine, sampling strategies for cost control, alerting pipeline, and high-cardinality management."

### 19.2 Requirements Gathering (3 minutes)

> "Let me clarify scope. We need metric ingestion at ~1M points/sec with four types: counters, gauges, histograms, timers. Distributed tracing with span trees and context propagation. Structured logging with trace correlation. Dashboard queries under 500ms P99. Alert evaluation within 60 seconds of ingestion. The system should be AP for metrics and queries but CP for alert state."

### 19.3 API Design (3 minutes)

> "The core APIs are: POST /metrics for metric ingestion (batch-optimized, 202 Accepted), POST /traces to start traces and create spans, POST /logs with optional traceId/spanId for correlation, POST /alerts/rules for rule definition, and GET /dashboard for aggregated views. All ingestion APIs are async with Kafka as the buffer."

### 19.4 Data Model (3 minutes)

> "MetricPoint is the atomic unit: name, value, timestamp, type, tags. Spans carry traceId (shared), spanId (unique), and parentSpanId (null for root). LogEntry has optional traceId and spanId for cross-pillar correlation. AlertRule is the declarative 'what to monitor'; Alert is the runtime 'something is wrong right now'."

### 19.5 Architecture Walkthrough (5 minutes)

> "The architecture has four layers. Ingestion: stateless ingestors behind ALB, validate and write to Kafka partitioned by metric name or traceId. Processing: consumers aggregate metrics, assemble span trees, filter logs, and update the service map. Storage: TSDB for metrics (TreeMap bucketed by epoch second, O(log n) range queries via subMap), Elasticsearch for traces and logs. Application: alert evaluator, dashboard aggregator, service map builder."

### 19.6 Time-Series Storage Deep Dive (5 minutes)

> "The core data structure is TreeMap<epochSecond, List<MetricPoint>> per metric name. Range queries use TreeMap.subMap(from, to+1) for O(log n) seek. Downsampling compresses 3600 raw points/hour to 1 averaged point -- 1000x reduction over a year. Retention policy: raw for 1 hour, 1-min averages for 24 hours, 5-min for 7 days, 1-hour for 1 year. In production, use VictoriaMetrics or InfluxDB with gorilla compression for 10-70x compression."

### 19.7 Sampling Strategies (5 minutes)

> "Three layers: head-based sampling at 10% rate using consistent hash of traceId -- zero cross-service coordination. Tail-based on top: buffer all remaining spans, keep traces with errors or high latency. Rate-limited as a safety valve: AtomicInteger counter with per-second window, max N traces/sec. Result: 100% error retention, 100% slow trace retention, 10% baseline, bounded cost."

### 19.8 Alerting and High-Cardinality (5 minutes)

> "Alerting uses Strategy pattern: threshold (avg > static value) for infrastructure metrics, anomaly detection (mean +/- k*stdDev) for application metrics with natural variation. Alert state machine: FIRING -> ACKNOWLEDGED -> RESOLVED. For high-cardinality: each unique label combination is a time series. Mitigate by dropping tags at ingestion, hash/bucketing (user_id mod 100), cardinality limits, or routing to ClickHouse for columnar storage."

### 19.9 Scaling and Tradeoffs (5 minutes)

> "Ingestors are stateless, scale horizontally. Kafka decouples ingestion from processing. Storage partitioned by metric name (consistent hashing). Query layer uses pre-aggregation, caching, and federated queries. Key tradeoffs: on-the-fly vs pre-computed aggregation (we use hybrid), head-based vs tail-based sampling (we layer all three), static vs dynamic thresholds (both via Strategy pattern)."

### 19.10 Closing (2 minutes)

> "The design handles 1M metrics/sec with O(log n) time-series queries, provides full distributed tracing with layered sampling for cost control, and uses anomaly detection to reduce alert fatigue. The three pillars are connected by traceId correlation -- the 'glue' that makes observability actionable, not just data collection."

---

## Design Patterns Summary

```
+---------------------------+------------------------------------------+-----------------------------+
| Pattern                   | Where Applied                            | Why                         |
+---------------------------+------------------------------------------+-----------------------------+
| Strategy (x3)            | SamplingStrategy, AggregationStrategy,   | Runtime algorithm swap      |
|                           | AlertingStrategy                         | without code changes        |
+---------------------------+------------------------------------------+-----------------------------+
| Builder (x3)             | Metric.Builder, Span.Builder,            | Complex objects with 8-14   |
|                           | AlertRule.Builder                        | fields, fluent construction |
+---------------------------+------------------------------------------+-----------------------------+
| Factory / Composition Root| AppConfig                                | Lazy wiring of 20+ objects, |
|                           |                                          | strategy swap + re-wire     |
+---------------------------+------------------------------------------+-----------------------------+
| Repository (x4)          | Metric, Trace, Log, Alert repositories   | Abstract data access,       |
|                           | (interface + InMemory impl)              | swap for production DBs     |
+---------------------------+------------------------------------------+-----------------------------+
| Facade                    | ObservabilityService                     | Single entry point for 6    |
|                           |                                          | sub-services                |
+---------------------------+------------------------------------------+-----------------------------+
| Observer                  | AlertService observes metric values      | Decouple ingestion from     |
|                           |                                          | alert evaluation            |
+---------------------------+------------------------------------------+-----------------------------+
| Decorator                 | Span.addTag(), Span.addLog()             | Enrich without modifying    |
|                           |                                          | core structure              |
+---------------------------+------------------------------------------+-----------------------------+
| Chain of Responsibility   | LogProcessor filter pipeline             | Composable, extensible      |
|                           |                                          | filter chain                |
+---------------------------+------------------------------------------+-----------------------------+
| Template Method           | MetricAggregator functions               | Same flow, different math   |
+---------------------------+------------------------------------------+-----------------------------+
| Singleton                 | AppConfig lazy initialization            | Single instance per service |
+---------------------------+------------------------------------------+-----------------------------+
```

---

## Quick Reference Card

```
+---------------------------+-----------------------------------------------+
| Topic                     | Key Phrase for Interview                       |
+---------------------------+-----------------------------------------------+
| Time-series storage       | TreeMap<epochSec, List<Point>>, subMap = O(log n) |
| Downsampling              | raw -> 1min -> 5min -> 1hr, 1000x compression |
| Three pillars             | Metrics (WHAT), Traces (WHERE), Logs (WHY)    |
| Correlation               | traceId + spanId in every log and span        |
| Head-based sampling       | hash(traceId) % 100, consistent, zero coord   |
| Tail-based sampling       | buffer all, keep errors + slow, memory-heavy  |
| Rate-limited sampling     | AtomicInteger per-second, CAS, cost ceiling   |
| Percentile                | sort values, index = ceil(P/100 * N) - 1      |
| Anomaly detection         | mean +/- k*stdDev, adaptive baseline           |
| Alert lifecycle           | FIRING -> ACKNOWLEDGED -> RESOLVED             |
| High cardinality          | unique label combo = 1 series, 10K users = 10K series |
| RED method                | Rate, Errors, Duration (for services)         |
| USE method                | Utilization, Saturation, Errors (for resources) |
| Context propagation       | W3C traceparent: 00-{traceId}-{spanId}-01     |
| Metric types              | COUNTER (up only), GAUGE (snapshot), HISTOGRAM, TIMER |
| CAP for metrics           | AP (stale OK, writes must succeed during outage) |
| CAP for alerts            | CP (missed alert = correctness violation)     |
| Service map               | adjacency list from trace spans, blast radius  |
| Production streaming P99  | t-digest, DDSketch, HDR Histogram             |
| Production TSDB           | VictoriaMetrics, InfluxDB, TimescaleDB        |
+---------------------------+-----------------------------------------------+
```
