# Technologies Reference: Observability Platform (Datadog/Grafana-like)

## Table of Contents

1. [Overview](#1-overview)
2. [Prometheus -- Metric Collection and Storage](#2-prometheus--metric-collection-and-storage)
3. [Grafana -- Visualization and Dashboarding](#3-grafana--visualization-and-dashboarding)
4. [Jaeger / Zipkin -- Distributed Tracing](#4-jaeger--zipkin--distributed-tracing)
5. [OpenTelemetry -- Unified Telemetry Collection](#5-opentelemetry--unified-telemetry-collection)
6. [ClickHouse / TimescaleDB -- Time-Series Storage](#6-clickhouse--timescaledb--time-series-storage)
7. [Apache Kafka -- Telemetry Streaming Backbone](#7-apache-kafka--telemetry-streaming-backbone)
8. [ElasticSearch / Grafana Loki -- Log Storage and Search](#8-elasticsearch--grafana-loki--log-storage-and-search)
9. [Redis -- Caching and Real-Time Aggregations](#9-redis--caching-and-real-time-aggregations)
10. [t-digest / DDSketch -- Streaming Percentile Estimation](#10-t-digest--ddsketch--streaming-percentile-estimation)
11. [W3C TraceContext -- Distributed Trace Propagation](#11-w3c-tracecontext--distributed-trace-propagation)
12. [OpenMetrics Format -- Metric Exposition Standard](#12-openmetrics-format--metric-exposition-standard)
13. [Simulation-to-Production Mapping](#13-simulation-to-production-mapping)
14. [Technology Selection Matrix](#14-technology-selection-matrix)

---

## 1. Overview

This document catalogs the production technologies that underpin an observability
platform at scale (Datadog, Grafana Cloud, New Relic class). Each section explains
what the technology does, why it matters for observability, how it integrates with
surrounding components, and what operational considerations to expect.

The simulation in this project uses in-memory Java structures (ConcurrentHashMap,
TreeMap, ArrayList) that map directly to these production technologies. Section 13
provides an explicit mapping from every simulation class to its production
counterpart.

### Architecture at a Glance

```
                         +-------------------+
                         | Instrumented Apps |
                         | (OTel SDK)        |
                         +--------+----------+
                                  |
             metrics (pull)       |  traces/logs (push)
          +----------+            |           +----------+
          |          |            |           |          |
  +-------v---+  +--v------------v---+  +----v---------+
  | Prometheus |  |  OpenTelemetry   |  | Fluentd /    |
  | (scrape)   |  |  Collector       |  | Fluent Bit   |
  +-------+----+  +--+----------+---+  +----+---------+
          |           |          |           |
          v           v          v           v
  +-------+----+  +--+---+  +---+----+  +---+--------+
  | TimescaleDB |  | Kafka |  | Jaeger |  | Elastic /  |
  | / ClickHouse|  | (buf) |  | (trace)|  | Loki (log) |
  +-------+----+  +--+---+  +---+----+  +---+--------+
          |           |          |           |
          +-----+-----+----+----+-----+-----+
                |           |          |
          +-----v-----+  +-v----------v----+
          |  Grafana   |  |  Alert Manager  |
          | (dashboards|  | (PagerDuty,     |
          |  + explore)|  |  Slack, email)  |
          +-----------+  +-----------------+
```

### Technology Stack Summary

| Layer               | Technology              | Role                                              |
|---------------------|-------------------------|----------------------------------------------------|
| Collection          | Prometheus              | Pull-based metric scraping, local TSDB             |
| Collection          | OpenTelemetry           | Unified traces, metrics, logs collection           |
| Streaming           | Kafka                   | Telemetry buffering, fan-out, replay               |
| Trace Storage       | Jaeger / Zipkin         | Distributed trace storage and query                |
| Metric Storage      | ClickHouse / TimescaleDB| Columnar / relational time-series storage          |
| Log Storage         | ElasticSearch / Loki    | Full-text search / label-indexed log storage       |
| Caching             | Redis                   | Aggregation cache, dashboard result cache          |
| Visualization       | Grafana                 | Dashboards, alerting, data source federation       |
| Percentiles         | t-digest / DDSketch     | Streaming quantile estimation (p50, p95, p99)      |
| Trace Propagation   | W3C TraceContext        | Cross-service trace ID propagation standard        |
| Metric Format       | OpenMetrics             | Standard metric exposition format                  |

---

## 2. Prometheus -- Metric Collection and Storage

### 2.1 What It Is

Prometheus is an open-source monitoring toolkit originally built at SoundCloud,
now a CNCF graduated project. It implements a pull-based metric collection model
where the Prometheus server scrapes HTTP endpoints exposed by instrumented
applications at configured intervals.

Core components:
- **Prometheus Server** -- scrapes targets, stores time-series, evaluates rules
- **Client Libraries** -- instrument application code (Java, Go, Python, etc.)
- **Pushgateway** -- for short-lived batch jobs that cannot be scraped
- **Alertmanager** -- handles alert routing, deduplication, silencing
- **Exporters** -- adapt third-party systems (MySQL, JMX, Node) to Prometheus

### 2.2 Role in the Observability Platform

Prometheus is the metric collection backbone. Every service exposes a `/metrics`
endpoint in OpenMetrics format. Prometheus scrapes these endpoints at 15-30 second
intervals, storing the data in its local time-series database (TSDB).

```
  Instrumented Services              Prometheus Server
  +-------------------+              +------------------+
  | payment-service   |              |                  |
  |   /metrics -------->  scrape --->| TSDB (on-disk)   |
  |   port: 9100      |   every 15s |                  |
  +-------------------+              | PromQL engine    |
  | order-service     |              |   |              |
  |   /metrics -------->  scrape --->|   v              |
  |   port: 9101      |              | Recording rules  |
  +-------------------+              |   |              |
  | user-service      |              |   v              |
  |   /metrics -------->  scrape --->| Alerting rules   |
  |   port: 9102      |              |   |              |
  +-------------------+              |   v              |
                                     | Remote write     |
                                     |   -> Thanos/Mimir|
                                     +------------------+
```

### 2.3 Pull vs Push Model

```
  +-----------------------------------------------------------------------+
  |  PULL MODEL (Prometheus)                                              |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  1. Service starts, registers /metrics endpoint                       |
  |  2. Prometheus discovers service (k8s SD, Consul, static config)      |
  |  3. Prometheus scrapes /metrics every scrape_interval (15s default)   |
  |  4. If scrape fails -> "up" gauge = 0 -> immediate "target down" alert|
  |                                                                       |
  |  Advantages:                                                          |
  |    - Prometheus controls the load (no thundering herd from clients)   |
  |    - Missing scrape = immediate health signal (free liveness check)   |
  |    - No client-side buffering or retry logic needed                   |
  |    - Easy to test: curl http://service:9100/metrics                   |
  |    - Service does not need to know about Prometheus                   |
  |                                                                       |
  |  Disadvantages:                                                       |
  |    - Prometheus must be able to reach every target (network path)     |
  |    - Short-lived jobs may complete before first scrape (use Pushgateway)|
  |    - Firewalls / NAT can block inbound scrapes                        |
  |    - Scrape interval sets minimum resolution (cannot go sub-second)   |
  |                                                                       |
  +-----------------------------------------------------------------------+

  +-----------------------------------------------------------------------+
  |  PUSH MODEL (Datadog Agent, StatsD, OTEL Collector)                   |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  1. Service instruments code with SDK                                 |
  |  2. SDK periodically pushes metrics to collector/agent                |
  |  3. Collector aggregates and forwards to backend                      |
  |                                                                       |
  |  Advantages:                                                          |
  |    - Works behind NAT/firewalls (outbound only)                       |
  |    - Short-lived processes can push before exiting                    |
  |    - Client controls resolution (can push sub-second)                 |
  |    - Works for serverless (Lambda, Cloud Functions)                   |
  |                                                                       |
  |  Disadvantages:                                                       |
  |    - Client must handle retry, buffering, backpressure                |
  |    - No free liveness signal from missing pushes (must add health check)|
  |    - Thundering herd on restart (all clients push at once)            |
  |    - Collector becomes a bottleneck if not scaled                     |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 2.4 Prometheus Data Model

```
  Metric name: http_request_duration_seconds
  Labels:      {service="payment", method="POST", status="200", endpoint="/charge"}
  Timestamp:   1715212800000 (Unix millis)
  Value:       0.0342 (seconds)

  In TSDB, this becomes a time-series identified by:
    __name__="http_request_duration_seconds" + all label key-value pairs

  Time-series identity = metric name + sorted label set
  Each unique label combination = separate time-series
  This is called "cardinality"
```

**Cardinality explosion** -- the #1 operational risk with Prometheus:

```
  +-----------------------------------------------------------------------+
  |  CARDINALITY EXPLOSION EXAMPLE                                        |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Safe:   http_requests_total{service="payment", method="POST"}        |
  |          -> 2 services x 4 methods = 8 series (fine)                  |
  |                                                                       |
  |  Danger: http_requests_total{service="payment", user_id="12345"}      |
  |          -> 2 services x 1M users = 2M series (TSDB OOM)             |
  |                                                                       |
  |  Rule of thumb:                                                       |
  |    < 100K active series per Prometheus instance = comfortable         |
  |    100K - 1M = needs tuning (memory, retention, relabeling)           |
  |    > 1M = needs sharding (Thanos, Mimir, Cortex)                     |
  |                                                                       |
  |  Fix: move high-cardinality dimensions to trace/log attributes,       |
  |       not metric labels. Use histograms, not per-user gauges.         |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 2.5 PromQL -- The Query Language

PromQL is Prometheus's functional query language for time-series selection and
aggregation:

```promql
# 1. Instant vector: current value of all matching series
http_requests_total{service="payment", status="200"}

# 2. Range vector: values over last 5 minutes
http_requests_total{service="payment"}[5m]

# 3. Rate: per-second rate over 5 minutes (for counters)
rate(http_requests_total{service="payment"}[5m])

# 4. Aggregation: sum rate across all instances
sum(rate(http_requests_total{service="payment"}[5m])) by (method)

# 5. Histogram quantile: p99 latency
histogram_quantile(0.99,
  sum(rate(http_request_duration_seconds_bucket{service="payment"}[5m]))
  by (le)
)

# 6. Alerting expression: error rate > 5% for 5 minutes
(
  sum(rate(http_requests_total{status=~"5.."}[5m]))
  /
  sum(rate(http_requests_total[5m]))
) > 0.05
```

### 2.6 Prometheus TSDB Internals

```
  +-----------------------------------------------------------------------+
  |  PROMETHEUS TSDB STORAGE LAYOUT                                       |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  data/                                                                |
  |  +-- 01BKGV7JBM69T2G1BGBGM6KB12/   (block: 2h of data, immutable)  |
  |  |   +-- meta.json                   (block metadata)                |
  |  |   +-- chunks/                     (compressed time-series chunks)  |
  |  |   |   +-- 000001                  (chunk file, ~512MB max)        |
  |  |   +-- index                       (inverted index: labels->series)|
  |  |   +-- tombstones                  (deletion markers)              |
  |  +-- 01BKGW7JBM69T2G1BGBGM6KB13/   (next 2h block)                 |
  |  +-- wal/                            (write-ahead log for crash safety)|
  |      +-- 00000001                    (WAL segment, append-only)       |
  |      +-- 00000002                                                    |
  |                                                                       |
  |  Write path:                                                          |
  |    1. Scrape -> samples appended to WAL (fsync on commit)            |
  |    2. In-memory "head block" holds last 2h of data                   |
  |    3. Every 2h, head block is cut to a new immutable block on disk   |
  |    4. Background compaction merges small blocks into larger ones      |
  |                                                                       |
  |  Read path:                                                           |
  |    1. PromQL query -> select time range                              |
  |    2. Binary search across block time ranges                         |
  |    3. Use inverted index to find matching series by labels           |
  |    4. Decompress relevant chunks                                     |
  |    5. Apply PromQL functions and return result                       |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 2.7 Remote Write for Long-Term Storage

Prometheus local TSDB is designed for 15-day retention. For long-term storage,
Prometheus uses remote write to forward samples to a durable backend:

```
  Prometheus (local 15d)
       |
       | remote_write (Protobuf over HTTP)
       v
  +----+----+------+-----+
  |         |      |     |
  v         v      v     v
  Thanos   Mimir  Cortex  VictoriaMetrics
  (S3)     (S3)   (S3)    (disk)

  Each provides:
    - Horizontal scaling (sharding by metric/tenant)
    - Global PromQL query across multiple Prometheus instances
    - Downsampling (5m, 1h aggregates for old data)
    - Multi-tenant isolation
    - Deduplication of HA pairs
```

### 2.8 Simulation Mapping

| Simulation Class                  | Production Technology                       |
|-----------------------------------|---------------------------------------------|
| `MetricService.ingest()`          | Prometheus scrape or OTel push to remote write |
| `InMemoryMetricRepository`        | Prometheus TSDB / Thanos / Mimir             |
| `MetricAggregator.aggregate()`    | PromQL recording rules                       |
| `ThresholdAlertingStrategy`       | Prometheus alerting rules + Alertmanager     |
| `MetricType` enum                 | Prometheus metric types (counter, gauge, histogram, summary) |
| `MetricPoint` (timestamp + value) | Prometheus sample (timestamp + float64 value) |

---

## 3. Grafana -- Visualization and Dashboarding

### 3.1 What It Is

Grafana is the de facto open-source visualization platform for observability data.
It connects to multiple data sources (Prometheus, Elasticsearch, Loki, Jaeger,
ClickHouse, etc.) and renders unified dashboards with panels, alerts, and
annotations.

### 3.2 Role in the Observability Platform

Grafana is the single pane of glass for operators and developers. It provides:

```
  +-----------------------------------------------------------------------+
  |  GRAFANA CAPABILITIES                                                 |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  1. DASHBOARDS                                                        |
  |     - Panel types: graph, stat, gauge, table, heatmap, logs, traces  |
  |     - Template variables: $service, $environment, $timerange          |
  |     - Auto-refresh: 5s / 10s / 30s / 1m intervals                   |
  |     - JSON model: dashboards as code (version control)               |
  |                                                                       |
  |  2. DATA SOURCE FEDERATION                                            |
  |     - Prometheus/Mimir: metrics (PromQL)                             |
  |     - Loki: logs (LogQL)                                             |
  |     - Jaeger/Tempo: traces (TraceQL)                                 |
  |     - ClickHouse: analytics (SQL)                                    |
  |     - Mixed data sources in single dashboard                         |
  |                                                                       |
  |  3. ALERTING (Grafana 9+)                                             |
  |     - Multi-data-source alerting rules                               |
  |     - Alert state: Normal -> Pending -> Firing -> Resolved           |
  |     - Contact points: PagerDuty, Slack, email, webhook               |
  |     - Notification policies: routing by severity/team                 |
  |     - Silences and mute timings                                      |
  |                                                                       |
  |  4. EXPLORE MODE                                                      |
  |     - Ad-hoc querying (no pre-built dashboard needed)                |
  |     - Metric -> Trace -> Log correlation                             |
  |     - Split view: compare two queries side-by-side                   |
  |                                                                       |
  |  5. ANNOTATIONS                                                       |
  |     - Mark deployments, incidents, config changes on graphs          |
  |     - API-driven: CI/CD pipeline posts annotation on deploy          |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 3.3 Dashboard Architecture

```
  Dashboard: "Payment Service Overview"
  |
  +-- Row: "Traffic"
  |   +-- Panel: "Request Rate" (Prometheus)
  |   |     query: sum(rate(http_requests_total{service="payment"}[5m])) by (method)
  |   +-- Panel: "Error Rate" (Prometheus)
  |   |     query: sum(rate(http_requests_total{service="payment",status=~"5.."}[5m]))
  |   +-- Panel: "Active Connections" (Prometheus)
  |         query: payment_active_connections
  |
  +-- Row: "Latency"
  |   +-- Panel: "p50 / p95 / p99 Latency" (Prometheus)
  |   |     query: histogram_quantile(0.99, sum(rate(..._bucket[5m])) by (le))
  |   +-- Panel: "Latency Heatmap" (Prometheus)
  |         query: sum(rate(http_request_duration_seconds_bucket[5m])) by (le)
  |
  +-- Row: "Logs"
  |   +-- Panel: "Recent Errors" (Loki)
  |         query: {service="payment"} |= "ERROR" | logfmt | line_format "{{.msg}}"
  |
  +-- Row: "Traces"
      +-- Panel: "Slow Traces" (Jaeger/Tempo)
            query: {service="payment" && duration > 1s}
```

### 3.4 Grafana Data Source Plugins

```
  +-----------------------------------------------------------------------+
  |  DATA SOURCE QUERY FLOW                                               |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Browser (panel refresh)                                              |
  |       |                                                               |
  |       v                                                               |
  |  Grafana Server                                                       |
  |       |                                                               |
  |       +-- /api/ds/query                                               |
  |           |                                                           |
  |           +-- data source plugin (Go / TypeScript)                    |
  |               |                                                       |
  |               +-- Prometheus plugin: HTTP GET /api/v1/query_range     |
  |               +-- Loki plugin: HTTP GET /loki/api/v1/query_range     |
  |               +-- Jaeger plugin: gRPC call to jaeger-query            |
  |               +-- ClickHouse plugin: TCP connection, SQL query        |
  |                                                                       |
  |  Response: DataFrame (table of typed columns with timestamps)         |
  |                                                                       |
  |  Grafana converts DataFrame -> panel visualization                    |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 3.5 Dashboards as Code (Provisioning)

```json
{
  "dashboard": {
    "uid": "payment-overview",
    "title": "Payment Service Overview",
    "tags": ["payment", "production"],
    "templating": {
      "list": [
        {
          "name": "service",
          "type": "query",
          "datasource": "Prometheus",
          "query": "label_values(http_requests_total, service)"
        }
      ]
    },
    "panels": [
      {
        "title": "Request Rate",
        "type": "timeseries",
        "datasource": "Prometheus",
        "targets": [
          {
            "expr": "sum(rate(http_requests_total{service=\"$service\"}[5m])) by (method)",
            "legendFormat": "{{method}}"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "unit": "reqps"
          }
        }
      }
    ]
  }
}
```

### 3.6 Simulation Mapping

| Simulation Class             | Production Technology                        |
|------------------------------|----------------------------------------------|
| `DashboardService`           | Grafana dashboard rendering + query engine   |
| `ObservabilityStatsDisplay`  | Grafana panel rendering (stat, graph, table) |
| `ServiceMapService`          | Grafana Service Map plugin / Node Graph panel|

---

## 4. Jaeger / Zipkin -- Distributed Tracing

### 4.1 What It Is

Jaeger (CNCF graduated, originally from Uber) and Zipkin (originally from Twitter)
are distributed tracing systems that collect, store, and visualize request traces
across microservices. A trace represents the end-to-end journey of a request;
spans represent individual operations within that trace.

### 4.2 Core Concepts

```
  +-----------------------------------------------------------------------+
  |  TRACE AND SPAN MODEL                                                 |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Trace: a tree of spans sharing a single traceId                     |
  |                                                                       |
  |  traceId: abc123                                                     |
  |  |                                                                    |
  |  +-- Span A: "POST /api/order" (root span, entry point)             |
  |      |  service: api-gateway                                          |
  |      |  spanId: span-001                                              |
  |      |  parentSpanId: null                                            |
  |      |  start: T+0ms, duration: 350ms                                |
  |      |                                                                |
  |      +-- Span B: "validate-order"                                    |
  |      |   |  service: order-service                                    |
  |      |   |  spanId: span-002                                          |
  |      |   |  parentSpanId: span-001                                    |
  |      |   |  start: T+5ms, duration: 50ms                             |
  |      |   |                                                            |
  |      |   +-- Span C: "SELECT * FROM users"                           |
  |      |       service: order-service (DB call)                         |
  |      |       spanId: span-003                                         |
  |      |       parentSpanId: span-002                                   |
  |      |       start: T+10ms, duration: 15ms                           |
  |      |                                                                |
  |      +-- Span D: "charge-payment"                                    |
  |          |  service: payment-service                                   |
  |          |  spanId: span-004                                           |
  |          |  parentSpanId: span-001                                     |
  |          |  start: T+60ms, duration: 200ms                            |
  |          |                                                            |
  |          +-- Span E: "POST https://stripe.com/charge"                |
  |              service: payment-service (HTTP call)                      |
  |              spanId: span-005                                          |
  |              parentSpanId: span-004                                    |
  |              start: T+70ms, duration: 180ms                           |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 4.3 Jaeger Architecture

```
  +-----------------------------------------------------------------------+
  |  JAEGER ARCHITECTURE                                                  |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Instrumented Service                                                 |
  |  +------------------+                                                 |
  |  | OTel SDK         |                                                 |
  |  | (or Jaeger SDK)  |                                                 |
  |  +--------+---------+                                                 |
  |           |                                                           |
  |           | push spans (OTel Protocol / Thrift)                       |
  |           v                                                           |
  |  +--------+---------+                                                 |
  |  | Jaeger Collector |  (validates, indexes, stores)                   |
  |  | (stateless,      |  Horizontally scalable                          |
  |  |  load-balanced)  |                                                 |
  |  +--------+---------+                                                 |
  |           |                                                           |
  |           +---> Storage Backend                                       |
  |           |     +-- Cassandra (default, AP, wide-column)              |
  |           |     +-- Elasticsearch (full-text search on tags)          |
  |           |     +-- Kafka (buffer before storage)                     |
  |           |     +-- ClickHouse (columnar, fast aggregations)          |
  |           |     +-- Badger (embedded, single-node only)               |
  |           |                                                           |
  |  +--------+---------+                                                 |
  |  | Jaeger Query     |  (REST + gRPC API)                              |
  |  | (trace search,   |                                                 |
  |  |  trace detail)   |                                                 |
  |  +--------+---------+                                                 |
  |           |                                                           |
  |           v                                                           |
  |  +--------+---------+                                                 |
  |  | Jaeger UI        |  (React app, timeline visualization)            |
  |  +------------------+                                                 |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 4.4 Zipkin Architecture Differences

```
  +---------------------------+---------------------------+
  | Feature                   | Jaeger              | Zipkin              |
  +---------------------------+---------------------+---------------------+
  | Origin                    | Uber (2017)         | Twitter (2012)      |
  | CNCF status               | Graduated           | Not CNCF            |
  | Default protocol          | OTel/gRPC           | HTTP/JSON or Thrift |
  | Sampling                  | Adaptive (per-svc)  | Rate-based          |
  | Storage                   | Cassandra, ES, CK   | Cassandra, ES, MySQL|
  | UI                        | React, Gantt chart  | React, Gantt chart  |
  | Tail-based sampling       | Yes (remote sampler)| No (community ext)  |
  | Service dependency graph  | Yes (Spark job)     | Yes (built-in)      |
  | Baggage propagation       | Yes                 | Yes                 |
  +---------------------------+---------------------+---------------------+
```

### 4.5 Trace Search Patterns

```
  +-----------------------------------------------------------------------+
  |  COMMON TRACE QUERIES                                                 |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  1. Find all traces for service "payment" in last 1h                 |
  |     -> service = "payment" AND start > now() - 1h                    |
  |     -> Storage: inverted index on service name                        |
  |                                                                       |
  |  2. Find traces slower than 500ms                                     |
  |     -> duration > 500ms                                               |
  |     -> Storage: secondary index on duration                           |
  |                                                                       |
  |  3. Find traces with error spans                                      |
  |     -> span.status = ERROR OR span.tags["error"] = true              |
  |     -> Storage: inverted index on span status/tags                    |
  |                                                                       |
  |  4. Find traces for a specific user                                   |
  |     -> span.tags["user.id"] = "user-12345"                           |
  |     -> Storage: inverted index on tag values                          |
  |     -> WARNING: high-cardinality tag, may be slow in Cassandra       |
  |                                                                       |
  |  5. Find traces that touched both payment and inventory service       |
  |     -> query traces where span.service IN ("payment", "inventory")   |
  |     -> Post-filter: keep traces that have both                        |
  |     -> Expensive query: requires join across service indices          |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 4.6 Sampling Strategies

Tracing at 100% is prohibitively expensive at scale. Sampling reduces volume while
preserving observability:

```
  +-----------------------------------------------------------------------+
  |  SAMPLING STRATEGIES                                                  |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  1. HEAD-BASED SAMPLING (decision at trace start)                    |
  |     - Deterministic: hash(traceId) % 100 < sampleRate                |
  |     - Simple, consistent across services                              |
  |     - Problem: decision made before knowing if trace is interesting   |
  |     - May miss rare errors sampled away at the head                  |
  |                                                                       |
  |  2. TAIL-BASED SAMPLING (decision after trace completes)             |
  |     - Collector buffers all spans for N seconds                       |
  |     - After trace assembles: decide based on duration, errors, etc.  |
  |     - Keep: all error traces, all slow traces, random sample of rest |
  |     - Problem: requires buffering ALL spans temporarily (memory/cost)|
  |     - Problem: trace may span >N seconds (incomplete at decision)    |
  |                                                                       |
  |  3. RATE-LIMITED SAMPLING                                             |
  |     - Accept first N traces per second, drop the rest                |
  |     - Guarantees bounded cost regardless of traffic volume            |
  |     - Problem: biased toward early requests in high-traffic bursts   |
  |                                                                       |
  |  4. ADAPTIVE / PRIORITY SAMPLING (Datadog approach)                  |
  |     - Central controller adjusts per-service sample rates            |
  |     - High-error services get higher sample rate automatically       |
  |     - Low-traffic services get 100% sampling                         |
  |     - High-traffic services get reduced rate                         |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 4.7 Simulation Mapping

| Simulation Class                       | Production Technology                    |
|----------------------------------------|------------------------------------------|
| `Trace` model                          | Jaeger Trace (collection of spans)       |
| `Span` model                           | Jaeger/Zipkin Span (operation, timing)   |
| `TraceContext` model                    | W3C TraceContext (traceparent header)     |
| `TraceAssembler`                       | Jaeger Collector (span -> trace assembly)|
| `TracingService`                        | Jaeger Query API                         |
| `InMemoryTraceRepository`              | Jaeger storage backend (Cassandra/ES)    |
| `HeadBasedSamplingStrategy`            | Jaeger probabilistic sampler             |
| `TailBasedSamplingStrategy`            | Jaeger remote sampling / OTel tail sampler|
| `RateLimitedSamplingStrategy`          | Jaeger rate-limiting sampler             |
| `SpanStatus` enum                      | OpenTelemetry SpanStatus (OK, ERROR, UNSET)|

---

## 5. OpenTelemetry -- Unified Telemetry Collection

### 5.1 What It Is

OpenTelemetry (OTel) is the CNCF project that merges OpenTracing and OpenCensus into
a single, vendor-neutral telemetry framework. It provides APIs, SDKs, and the
OpenTelemetry Collector for collecting traces, metrics, and logs from applications.

### 5.2 OpenTelemetry Architecture

```
  +-----------------------------------------------------------------------+
  |  OPENTELEMETRY ARCHITECTURE                                           |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Application Code                                                     |
  |  +-------------------+                                                |
  |  | OTel API          |  (interfaces: Tracer, Meter, Logger)           |
  |  +-------------------+                                                |
  |  | OTel SDK          |  (implementation: sampling, batching, export)  |
  |  +-------------------+                                                |
  |  | Auto-instrumentation (Java agent, Python monkey-patch)             |
  |  +-------------------+                                                |
  |           |                                                           |
  |           | OTLP (OpenTelemetry Protocol, gRPC or HTTP/protobuf)      |
  |           v                                                           |
  |  +-------------------+                                                |
  |  | OTel Collector    |  (vendor-neutral pipeline)                     |
  |  |  +-- Receivers    |  (OTLP, Prometheus, Jaeger, Zipkin, Fluent)   |
  |  |  +-- Processors   |  (batch, filter, attribute, tail sampling)    |
  |  |  +-- Exporters    |  (OTLP, Prometheus, Jaeger, Datadog, etc.)   |
  |  +--------+----------+                                                |
  |           |                                                           |
  |     +-----+------+--------+                                           |
  |     |            |        |                                           |
  |     v            v        v                                           |
  |  Jaeger      Prometheus  Loki                                         |
  |  (traces)    (metrics)   (logs)                                       |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 5.3 OTel Collector Pipeline Configuration

```yaml
# otel-collector-config.yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: "0.0.0.0:4317"
      http:
        endpoint: "0.0.0.0:4318"
  prometheus:
    config:
      scrape_configs:
        - job_name: 'otel-collector'
          scrape_interval: 10s
          static_configs:
            - targets: ['0.0.0.0:8888']

processors:
  batch:
    send_batch_size: 1024
    timeout: 5s
  memory_limiter:
    check_interval: 1s
    limit_mib: 2048
    spike_limit_mib: 512
  filter:
    traces:
      span:
        - 'attributes["http.target"] == "/health"'  # drop health checks
  tail_sampling:
    decision_wait: 10s
    policies:
      - name: errors-policy
        type: status_code
        status_code: {status_codes: [ERROR]}
      - name: slow-traces
        type: latency
        latency: {threshold_ms: 1000}
      - name: probabilistic
        type: probabilistic
        probabilistic: {sampling_percentage: 10}

exporters:
  otlp/jaeger:
    endpoint: "jaeger-collector:4317"
    tls:
      insecure: true
  prometheusremotewrite:
    endpoint: "http://mimir:9009/api/v1/push"
  loki:
    endpoint: "http://loki:3100/loki/api/v1/push"

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [memory_limiter, batch, filter, tail_sampling]
      exporters: [otlp/jaeger]
    metrics:
      receivers: [otlp, prometheus]
      processors: [memory_limiter, batch]
      exporters: [prometheusremotewrite]
    logs:
      receivers: [otlp]
      processors: [memory_limiter, batch]
      exporters: [loki]
```

### 5.4 OTel Java Auto-Instrumentation

```
  +-----------------------------------------------------------------------+
  |  JAVA AUTO-INSTRUMENTATION (ZERO CODE CHANGES)                        |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  java -javaagent:opentelemetry-javaagent.jar \                       |
  |       -Dotel.service.name=payment-service \                          |
  |       -Dotel.exporter.otlp.endpoint=http://otel-collector:4317 \     |
  |       -Dotel.traces.sampler=parentbased_traceidratio \               |
  |       -Dotel.traces.sampler.arg=0.1 \                                |
  |       -jar payment-service.jar                                       |
  |                                                                       |
  |  What gets auto-instrumented:                                         |
  |    - HTTP clients (Apache HttpClient, OkHttp, java.net.HttpClient)   |
  |    - HTTP servers (Spring MVC, JAX-RS, Servlet)                      |
  |    - JDBC (MySQL, PostgreSQL, Oracle)                                |
  |    - Messaging (Kafka, RabbitMQ, SQS)                                |
  |    - gRPC (client and server)                                        |
  |    - Redis (Jedis, Lettuce)                                          |
  |    - MongoDB                                                          |
  |                                                                       |
  |  Context propagation:                                                 |
  |    Automatically injects W3C traceparent header into outbound calls   |
  |    Automatically extracts traceId from inbound requests               |
  |    Trace context flows seamlessly across HTTP, gRPC, Kafka            |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 5.5 Three Pillars Unified

```
  +-----------------------------------------------------------------------+
  |  CORRELATION: THE POWER OF UNIFIED TELEMETRY                          |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Scenario: p99 latency spike on payment-service                      |
  |                                                                       |
  |  1. METRICS: Grafana dashboard shows p99 latency spike at 14:32      |
  |     -> histogram_quantile(0.99, ...) jumps from 200ms to 2s          |
  |     -> Click "exemplar" data point on the graph                      |
  |                                                                       |
  |  2. TRACES: Exemplar links to a specific slow trace (traceId: abc123)|
  |     -> Trace waterfall shows Span "SELECT * FROM orders" took 1.8s   |
  |     -> Span tags: db.statement, db.system="postgresql"               |
  |     -> Click span -> "View Logs"                                     |
  |                                                                       |
  |  3. LOGS: Logs filtered by traceId=abc123                            |
  |     -> 14:32:05 WARN "Slow query detected: 1.8s, table=orders"      |
  |     -> 14:32:05 INFO "PostgreSQL connection pool exhausted"          |
  |     -> Root cause: connection pool starvation due to missing index   |
  |                                                                       |
  |  The traceId is the GLUE that connects metrics -> traces -> logs     |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 5.6 Simulation Mapping

| Simulation Class              | Production Technology                          |
|-------------------------------|------------------------------------------------|
| `ObservabilityService`        | OTel Collector (receives all signal types)     |
| `MetricService.ingest()`      | OTel Metrics SDK -> Collector metrics pipeline |
| `TracingService.recordSpan()` | OTel Tracing SDK -> Collector traces pipeline  |
| `LogService.ingest()`         | OTel Logs SDK -> Collector logs pipeline       |
| `SamplingStrategy` interface  | OTel Sampler interface (head/tail/rate)        |
| `TraceContext`                 | OTel Context with W3C TraceContext propagator  |

---

## 6. ClickHouse / TimescaleDB -- Time-Series Storage

### 6.1 What They Are

**ClickHouse** is an open-source columnar OLAP database built by Yandex. It excels
at real-time analytical queries over billions of rows with sub-second latency.

**TimescaleDB** is a PostgreSQL extension that adds time-series superpowers:
automatic partitioning (hypertables), time-based compression, continuous aggregates,
and data retention policies.

### 6.2 Why Specialized Time-Series Storage

```
  +-----------------------------------------------------------------------+
  |  WHY NOT JUST USE POSTGRESQL / MYSQL?                                 |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Observability data is WRITE-HEAVY and TIME-ORDERED:                 |
  |                                                                       |
  |  - 100K metrics/sec ingestion (each metric = timestamp + value + tags)|
  |  - Queries always include a time range (last 5m, last 1h, last 7d)  |
  |  - Old data can be downsampled (5m averages instead of raw 15s)     |
  |  - Data is append-mostly (rarely updated or deleted)                 |
  |                                                                       |
  |  Regular RDBMS problems:                                              |
  |  - B-tree indexes become huge and slow for time-range queries        |
  |  - No automatic partitioning by time                                 |
  |  - No built-in compression for time-series patterns                  |
  |  - No downsampling / retention policies                              |
  |  - Columnar compression not available (row-oriented storage)         |
  |                                                                       |
  |  Time-series databases solve these by:                                |
  |  - Partitioning by time (chunks in TimescaleDB, parts in ClickHouse)|
  |  - Columnar storage (ClickHouse) for high compression ratios        |
  |  - Built-in time functions (time_bucket, toStartOfMinute, etc.)     |
  |  - Automatic retention (DROP CHUNK, TTL in ClickHouse)              |
  |  - Continuous aggregates (materialized views refreshed automatically)|
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 6.3 ClickHouse for Observability

```sql
-- ClickHouse table for metric storage
CREATE TABLE metrics (
    metric_name LowCardinality(String),
    tags        Map(LowCardinality(String), String),
    timestamp   DateTime64(3),           -- millisecond precision
    value       Float64
)
ENGINE = MergeTree()
PARTITION BY toYYYYMMDD(timestamp)       -- daily partitions
ORDER BY (metric_name, timestamp)         -- sort for range queries
TTL timestamp + INTERVAL 30 DAY           -- auto-delete after 30 days
SETTINGS index_granularity = 8192;

-- Query: average CPU usage per service in last hour, 1-minute buckets
SELECT
    tags['service'] AS service,
    toStartOfMinute(timestamp) AS minute,
    avg(value) AS avg_cpu
FROM metrics
WHERE metric_name = 'cpu_usage_percent'
  AND timestamp > now() - INTERVAL 1 HOUR
GROUP BY service, minute
ORDER BY minute;

-- ClickHouse processes this query over billions of rows in <100ms because:
-- 1. Partition pruning: only reads today's partition
-- 2. Primary key: skips to metric_name='cpu_usage_percent' directly
-- 3. Columnar storage: reads only 'value' and 'timestamp' columns
-- 4. Vectorized execution: SIMD operations on column chunks
```

### 6.4 TimescaleDB for Observability

```sql
-- TimescaleDB hypertable for metric storage
CREATE TABLE metrics (
    time        TIMESTAMPTZ NOT NULL,
    metric_name TEXT NOT NULL,
    tags        JSONB,
    value       DOUBLE PRECISION
);

-- Convert to hypertable (auto-partitions by time)
SELECT create_hypertable('metrics', 'time',
    chunk_time_interval => INTERVAL '1 day');

-- Create continuous aggregate (materialized downsampled view)
CREATE MATERIALIZED VIEW metrics_5min
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('5 minutes', time) AS bucket,
    metric_name,
    tags->>'service' AS service,
    avg(value) AS avg_value,
    max(value) AS max_value,
    min(value) AS min_value,
    count(*) AS sample_count
FROM metrics
GROUP BY bucket, metric_name, service;

-- Add retention policy (auto-drop chunks older than 30 days)
SELECT add_retention_policy('metrics', INTERVAL '30 days');

-- Add compression policy (compress chunks older than 7 days)
ALTER TABLE metrics SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'metric_name',
    timescaledb.compress_orderby = 'time DESC'
);
SELECT add_compression_policy('metrics', INTERVAL '7 days');
```

### 6.5 ClickHouse vs TimescaleDB Comparison

```
  +---------------------------+---------------------------+---------------------------+
  | Feature                   | ClickHouse                | TimescaleDB               |
  +---------------------------+---------------------------+---------------------------+
  | Storage model             | Columnar                  | Row (PostgreSQL)          |
  | SQL compatibility         | ClickHouse SQL dialect    | Full PostgreSQL SQL       |
  | Write throughput          | Millions of rows/sec      | 100K+ rows/sec           |
  | Compression ratio         | 10-40x (columnar)         | 3-10x (with compression) |
  | Query performance         | Fastest for aggregations  | Good, leverages PG index |
  | JOINs                     | Limited (prefer denorm)   | Full PostgreSQL JOINs    |
  | Transactions              | No ACID transactions      | Full ACID (PostgreSQL)   |
  | Operational complexity    | Separate system           | PostgreSQL extension      |
  | Ecosystem                 | ClickHouse-specific tools | Entire PostgreSQL ecosystem|
  | Best for                  | High-volume analytics     | Moderate volume + SQL    |
  +---------------------------+---------------------------+---------------------------+
```

### 6.6 Simulation Mapping

| Simulation Class               | Production Technology                          |
|--------------------------------|------------------------------------------------|
| `TimeSeriesStore`              | ClickHouse MergeTree / TimescaleDB hypertable  |
| `MetricPoint` (timestamp+value)| Row in metrics table (time, value)             |
| `MetricAggregator.aggregate()` | Continuous aggregate / materialized view        |
| `InMemoryMetricRepository`     | ClickHouse / TimescaleDB with retention policy |
| TreeMap<Long, List<MetricPoint>> | Time-partitioned storage (partition by day)   |

---

## 7. Apache Kafka -- Telemetry Streaming Backbone

### 7.1 What It Is

Apache Kafka is a distributed event streaming platform that acts as the buffering
and fan-out layer between telemetry producers (instrumented services) and
telemetry consumers (storage backends, alerting engines, processors).

### 7.2 Role in the Observability Platform

```
  +-----------------------------------------------------------------------+
  |  KAFKA AS THE TELEMETRY BACKBONE                                      |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Without Kafka (direct push):                                         |
  |    Service -> Jaeger (traces)                                         |
  |    Service -> Prometheus (metrics)                                    |
  |    Service -> Loki (logs)                                             |
  |    Problem: if Jaeger is down, traces are LOST                       |
  |    Problem: service must know all backends (tight coupling)           |
  |    Problem: adding a new consumer requires changing producers         |
  |                                                                       |
  |  With Kafka (buffered push):                                          |
  |    Service -> Kafka topic "traces" -> Jaeger consumer                 |
  |                                    -> ClickHouse consumer (analytics)|
  |                                    -> Sampling processor             |
  |    Service -> Kafka topic "metrics" -> Prometheus remote write        |
  |                                     -> Alert evaluator               |
  |    Service -> Kafka topic "logs" -> Loki consumer                    |
  |                                  -> Anomaly detector                 |
  |                                                                       |
  |  Benefits:                                                            |
  |    1. DECOUPLING: producers don't know about consumers               |
  |    2. BUFFERING: Kafka absorbs traffic spikes (backpressure)         |
  |    3. REPLAY: reprocess data by resetting consumer offset            |
  |    4. FAN-OUT: same data consumed by multiple processors             |
  |    5. DURABILITY: data persists on disk even if consumers are down   |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 7.3 Kafka Topic Design for Observability

```
  +-----------------------------------------------------------------------+
  |  TOPIC PARTITIONING STRATEGY                                          |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Topic: observability.traces                                          |
  |  Partitions: 64                                                       |
  |  Partition key: traceId                                               |
  |  Why: all spans of a trace go to same partition (ordering guarantee) |
  |       -> trace assembler can read one partition and get full trace   |
  |  Retention: 7 days (replay window for reprocessing)                  |
  |                                                                       |
  |  Topic: observability.metrics                                         |
  |  Partitions: 32                                                       |
  |  Partition key: metric_name + service (consistent hashing)           |
  |  Why: same metric series goes to same partition (aggregation locality)|
  |  Retention: 3 days (metrics are durable in TSDB, Kafka is just buffer)|
  |                                                                       |
  |  Topic: observability.logs                                            |
  |  Partitions: 64                                                       |
  |  Partition key: service_name                                          |
  |  Why: logs from same service in same partition (ordering per service)|
  |  Retention: 3 days                                                    |
  |                                                                       |
  |  Topic: observability.alerts                                          |
  |  Partitions: 8                                                        |
  |  Partition key: alert_rule_id                                         |
  |  Why: low volume, ordering per alert rule for deduplication          |
  |  Retention: 30 days (audit trail)                                     |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 7.4 Kafka Consumer Groups for Observability

```
  Topic: observability.traces (64 partitions)
       |
       +-- Consumer Group: "trace-storage"
       |   (4 consumers, each reads 16 partitions)
       |   -> writes to Jaeger storage (Cassandra/ES)
       |
       +-- Consumer Group: "trace-sampling"
       |   (2 consumers, each reads 32 partitions)
       |   -> tail-based sampling: buffer spans, decide after 10s
       |   -> forward sampled traces to "trace-storage"
       |
       +-- Consumer Group: "service-map-builder"
       |   (1 consumer, reads all 64 partitions)
       |   -> builds service dependency graph from span parent-child
       |
       +-- Consumer Group: "trace-analytics"
           (2 consumers, each reads 32 partitions)
           -> writes to ClickHouse for aggregate analytics
```

### 7.5 Backpressure and Flow Control

```
  +-----------------------------------------------------------------------+
  |  HANDLING TRAFFIC SPIKES                                              |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Normal: 50K spans/sec -> Kafka -> Jaeger (processes at 50K/sec)    |
  |                                                                       |
  |  Spike:  500K spans/sec -> Kafka absorbs the burst                  |
  |          Kafka lag grows: 500K - 50K = 450K spans/sec accumulating   |
  |          Consumer auto-scales: 4 consumers -> 16 consumers           |
  |          Processing rate: 16 * 50K = 800K spans/sec (draining lag)  |
  |                                                                       |
  |  Emergency: Kafka disk approaching full                              |
  |    Option 1: Reduce retention (7d -> 1d, free disk)                 |
  |    Option 2: Drop low-priority data (probabilistic sampling at Kafka)|
  |    Option 3: Add Kafka brokers (rebalance partitions)               |
  |    Option 4: Enable compression (lz4/snappy, 3-5x reduction)       |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 7.6 Simulation Mapping

| Simulation Class               | Production Technology                        |
|--------------------------------|----------------------------------------------|
| `ObservabilityService` (router)| Kafka topics (routes to correct consumer)    |
| Method calls between services  | Kafka producer -> consumer pattern            |
| `SamplingEngine` (inline)      | Kafka Streams / consumer-side sampling       |
| Sequential processing          | Kafka partitioned parallel processing        |

---

## 8. ElasticSearch / Grafana Loki -- Log Storage and Search

### 8.1 What They Are

**ElasticSearch** is a distributed search and analytics engine based on Apache
Lucene. It provides full-text search, structured search, and analytics over log
data. Part of the ELK stack (Elasticsearch, Logstash, Kibana).

**Grafana Loki** is a horizontally scalable log aggregation system designed by
Grafana Labs. Unlike ElasticSearch, Loki does NOT index the log content -- it only
indexes the label set (like Prometheus labels), making it significantly cheaper
to operate.

### 8.2 ElasticSearch for Logs

```
  +-----------------------------------------------------------------------+
  |  ELASTICSEARCH LOG ARCHITECTURE                                       |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Index pattern: logs-{service}-{date}                                |
  |  Example: logs-payment-2026.05.09                                    |
  |                                                                       |
  |  Document structure:                                                  |
  |  {                                                                    |
  |    "@timestamp": "2026-05-09T14:32:05.123Z",                         |
  |    "service": "payment",                                              |
  |    "level": "ERROR",                                                  |
  |    "traceId": "abc123",                                               |
  |    "spanId": "span-004",                                              |
  |    "message": "Payment failed: insufficient funds",                   |
  |    "exception": "InsufficientFundsException",                         |
  |    "userId": "user-12345",                                            |
  |    "amount": 99.99,                                                   |
  |    "host": "payment-pod-abc",                                         |
  |    "kubernetes.namespace": "production",                              |
  |    "kubernetes.pod": "payment-7f8b9c6d4-x2k3m"                      |
  |  }                                                                    |
  |                                                                       |
  |  Inverted index:                                                      |
  |    "payment" -> [doc1, doc5, doc12, doc99, ...]                      |
  |    "ERROR"   -> [doc1, doc7, doc23, ...]                             |
  |    "abc123"  -> [doc1]    (traceId: unique, fast lookup)             |
  |    "insufficient" -> [doc1, doc45, ...]                              |
  |                                                                       |
  |  Query: find all ERROR logs for payment in last hour with "timeout"  |
  |    GET /logs-payment-2026.05.09/_search                              |
  |    {                                                                  |
  |      "query": {                                                      |
  |        "bool": {                                                     |
  |          "must": [                                                   |
  |            {"match": {"level": "ERROR"}},                            |
  |            {"match": {"message": "timeout"}},                        |
  |            {"range": {"@timestamp": {"gte": "now-1h"}}}              |
  |          ]                                                           |
  |        }                                                             |
  |      }                                                               |
  |    }                                                                  |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 8.3 Grafana Loki for Logs

```
  +-----------------------------------------------------------------------+
  |  LOKI ARCHITECTURE -- "LIKE PROMETHEUS, BUT FOR LOGS"                 |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Key insight: don't index the log CONTENT, only index LABELS         |
  |                                                                       |
  |  Label set: {service="payment", level="ERROR", namespace="prod"}     |
  |  Log line:  "2026-05-09T14:32:05 Payment failed: insufficient funds" |
  |                                                                       |
  |  Storage:                                                             |
  |    - Label index: stored in index DB (BoltDB, Cassandra, BigTable)   |
  |    - Log chunks: compressed log lines stored in object storage (S3)  |
  |    - Chunks are grouped by label set and time range                  |
  |                                                                       |
  |  Query (LogQL):                                                       |
  |    {service="payment", level="ERROR"} |= "timeout" | logfmt         |
  |                                                                       |
  |    1. Label matchers -> find chunks for {service="payment",level="ERROR"}|
  |    2. Load chunks from S3 for the time range                         |
  |    3. GREP through chunks for "timeout" (brute-force!)              |
  |    4. Parse structured fields with logfmt/json/pattern               |
  |                                                                       |
  |  Why this works:                                                      |
  |    - 99% of log queries filter by service+level+time first           |
  |    - Full-text search inside those filtered chunks is fast enough    |
  |    - Index size is tiny (just labels), so storage cost is 10-100x    |
  |      cheaper than ElasticSearch                                      |
  |    - Object storage (S3) is almost free for cold data                |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 8.4 ElasticSearch vs Loki

```
  +---------------------------+---------------------------+---------------------------+
  | Feature                   | ElasticSearch             | Grafana Loki              |
  +---------------------------+---------------------------+---------------------------+
  | Indexing                  | Full-text (every word)    | Labels only               |
  | Storage cost              | High (inverted index)     | Low (object storage)      |
  | Query speed (broad)       | Fast (indexed)            | Slow (grep through chunks)|
  | Query speed (narrow)      | Fast                      | Fast (small chunk set)    |
  | Cardinality tolerance     | Higher (indexed fields)   | Low (labels only)         |
  | Operational complexity    | High (JVM tuning, shards) | Low (stateless queriers)  |
  | Ecosystem                 | Kibana, Beats, Logstash   | Grafana, Promtail         |
  | Structured search         | Excellent                 | LogQL pipeline stages     |
  | Cost at scale             | $$$$ (compute + storage)  | $ (object storage)        |
  | Best for                  | Security analytics (SIEM) | DevOps observability      |
  +---------------------------+---------------------------+---------------------------+
```

### 8.5 Log Pipeline Architecture

```
  Application              Shipper              Buffer         Storage
  +----------+          +----------+          +-------+     +----------+
  | stdout   | -------> | Fluent   | -------> | Kafka | --> | Elastic  |
  | (JSON    |          | Bit      |          | topic |     | Search   |
  |  format) |          | (parse,  |          | "logs"|     +----------+
  +----------+          |  filter, |          +-------+     +----------+
                        |  enrich) |                   +--> | Loki     |
                        +----------+                        | (S3)     |
                                                            +----------+
  Fluent Bit enrichment:
    - Add kubernetes labels (pod, namespace, node)
    - Parse JSON log lines into structured fields
    - Add traceId from MDC (Mapped Diagnostic Context)
    - Filter out health check logs (noisy, no value)
    - Redact sensitive fields (credit card, SSN)
```

### 8.6 Simulation Mapping

| Simulation Class            | Production Technology                        |
|-----------------------------|----------------------------------------------|
| `LogEntry` model            | Elasticsearch document / Loki log line       |
| `LogLevel` enum             | Syslog severity / structured log level       |
| `LogService`                | Log ingestion pipeline (Fluent Bit -> Kafka) |
| `InMemoryLogRepository`     | Elasticsearch index / Loki chunk store       |
| `LogProcessor`              | Logstash / Fluent Bit pipeline processors    |

---

## 9. Redis -- Caching and Real-Time Aggregations

### 9.1 What It Is

Redis is an in-memory data structure store used in the observability platform for
caching pre-computed aggregations, dashboard query results, and real-time metric
windows.

### 9.2 Role in the Observability Platform

```
  +-----------------------------------------------------------------------+
  |  REDIS USE CASES IN OBSERVABILITY                                     |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  1. DASHBOARD QUERY CACHE                                             |
  |     Key:   "query:hash(promql_expr):timerange:step"                  |
  |     Value: serialized DataFrame (query result)                        |
  |     TTL:   30s - 5m (depending on dashboard refresh rate)            |
  |     Why:   multiple users viewing same dashboard hit same queries    |
  |            cache avoids repeated PromQL evaluation on TSDB            |
  |                                                                       |
  |  2. SERVICE MAP CACHE                                                 |
  |     Key:   "servicemap:v1"                                            |
  |     Value: JSON graph (nodes=services, edges=dependencies)            |
  |     TTL:   5 minutes                                                  |
  |     Why:   service map changes slowly, but rendering requires         |
  |            scanning all recent traces (expensive)                     |
  |                                                                       |
  |  3. ALERT STATE CACHE                                                 |
  |     Key:   "alert:rule:{ruleId}:state"                               |
  |     Value: {state: "FIRING", since: timestamp, lastNotified: ts}     |
  |     TTL:   None (persistent until alert resolves)                    |
  |     Why:   alert evaluator runs every 15s, needs fast state lookup   |
  |                                                                       |
  |  4. RATE COUNTER (SLIDING WINDOW)                                     |
  |     Key:   "rate:{service}:{metric}:{minute_bucket}"                 |
  |     Value: INCR counter                                               |
  |     TTL:   10 minutes                                                 |
  |     Why:   real-time request rate without querying TSDB              |
  |                                                                       |
  |  5. RECENT METRIC WINDOW                                              |
  |     Key:   "recent:{service}:{metric}"                               |
  |     Type:  Sorted Set (score=timestamp, member=value)                |
  |     TTL:   ZREMRANGEBYSCORE for sliding window (last 5 minutes)      |
  |     Why:   anomaly detection needs recent values for moving average  |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 9.3 Redis Data Structures for Observability

```
  +-----------------------------------------------------------------------+
  |  REDIS SORTED SET FOR STREAMING WINDOW                                |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  # Add metric point to sliding window                                |
  |  ZADD recent:payment:latency_ms 1715270000 "152.3"                  |
  |  ZADD recent:payment:latency_ms 1715270015 "189.7"                  |
  |  ZADD recent:payment:latency_ms 1715270030 "201.1"                  |
  |                                                                       |
  |  # Trim old data (keep last 5 minutes)                               |
  |  ZREMRANGEBYSCORE recent:payment:latency_ms 0 (now - 300)           |
  |                                                                       |
  |  # Get all values in window (for anomaly detection)                  |
  |  ZRANGEBYSCORE recent:payment:latency_ms (now - 300) +inf           |
  |                                                                       |
  |  # Get count of values in window (for rate calculation)              |
  |  ZCOUNT recent:payment:latency_ms (now - 60) +inf                   |
  |                                                                       |
  +-----------------------------------------------------------------------+

  +-----------------------------------------------------------------------+
  |  REDIS HYPERLOGLOG FOR CARDINALITY ESTIMATION                         |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  # Count unique traceIds per service (approximate)                   |
  |  PFADD unique_traces:payment "trace-001"                             |
  |  PFADD unique_traces:payment "trace-002"                             |
  |  PFADD unique_traces:payment "trace-001"  # duplicate, not counted  |
  |                                                                       |
  |  PFCOUNT unique_traces:payment  # returns ~2 (0.81% error rate)     |
  |                                                                       |
  |  Use case: dashboard "Unique Requests" widget                        |
  |  Memory: 12KB per HyperLogLog regardless of cardinality              |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 9.4 Simulation Mapping

| Simulation Class                 | Production Technology                     |
|----------------------------------|-------------------------------------------|
| ConcurrentHashMap in services    | Redis hashes and sorted sets              |
| In-memory aggregation results    | Redis cached query results                |
| `ServiceMapService` graph cache  | Redis JSON / hash for service map graph   |
| `AlertService` state tracking    | Redis hash for alert state                |

---

## 10. t-digest / DDSketch -- Streaming Percentile Estimation

### 10.1 The Problem

Computing exact percentiles (p50, p95, p99) requires sorting all values, which
means storing every single data point. At 100K metrics/sec, this is 8.64 billion
data points per day -- impossible to sort in real time.

### 10.2 What They Are

**t-digest** (Ted Dunning, 2013) and **DDSketch** (DataDog, 2019) are streaming
algorithms that estimate quantiles with bounded error using a compact in-memory
structure.

```
  +-----------------------------------------------------------------------+
  |  THE STREAMING QUANTILE PROBLEM                                       |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Input: unbounded stream of latency values                           |
  |    152ms, 189ms, 201ms, 45ms, 2001ms, 88ms, 150ms, ...              |
  |                                                                       |
  |  Query: "What is the p99 latency over the last hour?"               |
  |                                                                       |
  |  Exact answer: sort all 3.6M values, take value at position 99%     |
  |    -> requires storing all 3.6M values (28MB for float64)            |
  |    -> sorting 3.6M values every query (slow)                         |
  |                                                                       |
  |  t-digest answer: maintain a sketch of ~100 centroids (< 1KB)       |
  |    -> add each value in O(1)                                         |
  |    -> query any quantile in O(log n) where n = number of centroids  |
  |    -> error: < 1% for extreme quantiles (p99, p99.9)                |
  |    -> error: < 0.1% for median (p50)                                |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 10.3 t-digest Internals

```
  +-----------------------------------------------------------------------+
  |  t-DIGEST: CLUSTER-BASED QUANTILE ESTIMATION                         |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Data structure: sorted list of "centroids" (mean, count)            |
  |                                                                       |
  |  Centroid: { mean: 150.5, count: 42 }                                |
  |    meaning: ~42 values near 150.5                                    |
  |                                                                       |
  |  Key property: centroids near the tails (p0, p100) are SMALLER      |
  |    -> more precision at extremes (where p99/p99.9 live)              |
  |    -> less precision in the middle (acceptable for p50)              |
  |                                                                       |
  |  Visual:                                                              |
  |    |xxx|  xxxxxx  |  xxxxxxxxxxxx  |  xxxxxx  |xxx|                  |
  |    p0  p5    p25     p50       p75    p95  p99  p100                  |
  |     ^                                           ^                     |
  |     small centroids                    small centroids                |
  |     (high precision)                   (high precision)              |
  |                                                                       |
  |  Compression parameter (delta): controls accuracy vs memory          |
  |    delta=100: ~100 centroids, < 1KB, < 1% error at p99              |
  |    delta=200: ~200 centroids, < 2KB, < 0.5% error at p99            |
  |                                                                       |
  |  MERGEABLE: two t-digests can be merged into one                     |
  |    -> perfect for distributed systems (merge across shards)          |
  |    -> Prometheus histogram_quantile uses this approach                |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 10.4 DDSketch Internals

```
  +-----------------------------------------------------------------------+
  |  DDSKETCH: RELATIVE ERROR QUANTILE ESTIMATION                        |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Key property: RELATIVE error guarantee                              |
  |    t-digest: absolute rank error (good at extremes, varies elsewhere)|
  |    DDSketch: if true p99 = 200ms, DDSketch returns 200ms +/- alpha% |
  |              where alpha is configurable (typically 1-2%)            |
  |                                                                       |
  |  Data structure: array of logarithmically-spaced buckets             |
  |                                                                       |
  |  Bucket mapping (log-based):                                          |
  |    bucket_index = floor(log(value) / log(gamma))                     |
  |    where gamma = (1 + alpha) / (1 - alpha)                           |
  |                                                                       |
  |  Example with alpha=0.01 (1% relative error):                       |
  |    bucket[0]: [0.99ms, 1.01ms)    count: 5                          |
  |    bucket[1]: [1.01ms, 1.03ms)    count: 12                         |
  |    ...                                                                |
  |    bucket[50]: [100ms, 102ms)     count: 8934                        |
  |    bucket[51]: [102ms, 104ms)     count: 7621                        |
  |    ...                                                                |
  |    bucket[80]: [990ms, 1010ms)    count: 42                          |
  |                                                                       |
  |  MERGEABLE: two DDSketches can be merged by summing bucket counts    |
  |    -> distributed aggregation across services/shards                 |
  |                                                                       |
  |  Used by: Datadog (their core percentile engine)                     |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 10.5 t-digest vs DDSketch

```
  +---------------------------+---------------------------+---------------------------+
  | Feature                   | t-digest                  | DDSketch                  |
  +---------------------------+---------------------------+---------------------------+
  | Error type                | Rank error (absolute)     | Relative error (%)        |
  | Error at p99              | < 1%                      | Configurable (1-2%)       |
  | Error at p50              | < 0.1%                    | Same as p99 (uniform)     |
  | Memory                    | ~1KB (100 centroids)      | ~2KB (128 buckets)        |
  | Merge cost                | O(c1 + c2) centroids      | O(max_buckets)            |
  | Insert cost               | O(log c) amortized        | O(1)                      |
  | Negative values           | Yes                       | Requires CollapsingLowest |
  | Used by                   | Elasticsearch, Prometheus | Datadog                   |
  | Implementation maturity   | Widely adopted             | Newer, growing adoption   |
  +---------------------------+---------------------------+---------------------------+
```

### 10.6 Simulation Mapping

| Simulation Class                     | Production Technology               |
|--------------------------------------|-------------------------------------|
| `PercentileAggregationStrategy`      | t-digest / DDSketch percentile query|
| `MetricAggregator` percentile calc   | Prometheus histogram_quantile (t-digest internally) |
| Sorting all values (simulation)      | t-digest insert + query (production)|

---

## 11. W3C TraceContext -- Distributed Trace Propagation

### 11.1 What It Is

W3C TraceContext is a W3C Recommendation (standard) that defines HTTP headers for
distributed trace propagation. It ensures that a trace ID created at the entry
point of a request is carried through all downstream services, regardless of which
tracing library or vendor each service uses.

### 11.2 The Standard Headers

```
  +-----------------------------------------------------------------------+
  |  W3C TRACECONTEXT HEADERS                                            |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Header: traceparent                                                  |
  |  Format: {version}-{traceId}-{parentSpanId}-{traceFlags}            |
  |  Example: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01   |
  |           ^   ^                                ^                  ^   |
  |           |   |                                |                  |   |
  |           |   32-char hex trace ID             16-char hex span   |   |
  |           |   (128-bit, globally unique)       ID (64-bit)        |   |
  |           |                                                       |   |
  |           version (00)                              trace flags   |   |
  |                                                     01 = sampled  |   |
  |                                                     00 = not sampled  |
  |                                                                       |
  |  Header: tracestate                                                   |
  |  Format: vendor1=value1,vendor2=value2                               |
  |  Example: dd=s:1;t.dm:-1,rojo=00f067aa0ba902b7                     |
  |  Purpose: vendor-specific trace metadata (sampling priority, etc.)   |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 11.3 Propagation Flow

```
  +-----------------------------------------------------------------------+
  |  CROSS-SERVICE TRACE PROPAGATION                                      |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  1. API Gateway receives request (no traceparent header)             |
  |     -> Generate new traceId: 4bf92f3577b34da6a3ce929d0e0e4736       |
  |     -> Generate spanId for root span: 00f067aa0ba902b7               |
  |     -> Make sampling decision: sampled = true (01)                   |
  |                                                                       |
  |  2. API Gateway calls Order Service                                   |
  |     -> Inject header:                                                |
  |        traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa...-01|
  |                                                                       |
  |  3. Order Service receives request                                    |
  |     -> Extract traceparent: traceId = 4bf92f..., parentSpanId = 00f..|
  |     -> Generate new spanId for this service: 1234567890abcdef         |
  |     -> Create span: traceId=4bf92f..., spanId=12345..., parent=00f.. |
  |                                                                       |
  |  4. Order Service calls Payment Service                               |
  |     -> Inject header:                                                |
  |        traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-1234567...-01|
  |        (same traceId, new parentSpanId = this service's spanId)      |
  |                                                                       |
  |  Result: all spans share traceId, forming a tree via parentSpanId    |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 11.4 Before W3C TraceContext (The Problem)

```
  +-----------------------------------------------------------------------+
  |  VENDOR HEADER FRAGMENTATION (pre-W3C)                                |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Zipkin:     X-B3-TraceId, X-B3-SpanId, X-B3-ParentSpanId          |
  |  Jaeger:     uber-trace-id (compact format)                          |
  |  Datadog:    x-datadog-trace-id, x-datadog-parent-id               |
  |  AWS X-Ray:  X-Amzn-Trace-Id                                        |
  |                                                                       |
  |  Problem: Service A uses Zipkin, Service B uses Jaeger               |
  |    -> Service A sends X-B3-TraceId header                            |
  |    -> Service B doesn't understand it, generates NEW traceId         |
  |    -> Trace is BROKEN: two disconnected traces instead of one        |
  |                                                                       |
  |  W3C TraceContext solves this:                                        |
  |    -> ALL vendors now support traceparent header                     |
  |    -> Vendor-specific data goes in tracestate (optional)             |
  |    -> Trace context flows across heterogeneous instrumentation       |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 11.5 Simulation Mapping

| Simulation Class    | Production Technology                              |
|---------------------|----------------------------------------------------|
| `TraceContext`       | W3C traceparent + tracestate headers               |
| `Span.traceId`      | 128-bit trace ID from traceparent                  |
| `Span.spanId`       | 64-bit span ID from traceparent                    |
| `Span.parentSpanId` | parentSpanId field in traceparent                  |
| `TraceAssembler`     | Server-side trace assembly using traceId grouping  |

---

## 12. OpenMetrics Format -- Metric Exposition Standard

### 12.1 What It Is

OpenMetrics is a CNCF sandbox project that standardizes the text-based metric
exposition format originally created by Prometheus. It defines how applications
expose metrics over HTTP for scraping by monitoring systems.

### 12.2 Format Examples

```
  +-----------------------------------------------------------------------+
  |  OPENMETRICS / PROMETHEUS EXPOSITION FORMAT                           |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  # TYPE http_requests_total counter                                  |
  |  # HELP http_requests_total Total HTTP requests                      |
  |  http_requests_total{method="GET",status="200"} 1027                 |
  |  http_requests_total{method="GET",status="404"} 3                    |
  |  http_requests_total{method="POST",status="200"} 512                 |
  |  http_requests_total{method="POST",status="500"} 7                   |
  |                                                                       |
  |  # TYPE http_request_duration_seconds histogram                      |
  |  # HELP http_request_duration_seconds Request latency in seconds     |
  |  http_request_duration_seconds_bucket{le="0.005"} 24054              |
  |  http_request_duration_seconds_bucket{le="0.01"} 33444              |
  |  http_request_duration_seconds_bucket{le="0.025"} 100392            |
  |  http_request_duration_seconds_bucket{le="0.05"} 129389             |
  |  http_request_duration_seconds_bucket{le="0.1"} 133988              |
  |  http_request_duration_seconds_bucket{le="0.25"} 144320             |
  |  http_request_duration_seconds_bucket{le="0.5"} 144320              |
  |  http_request_duration_seconds_bucket{le="1"} 144320                |
  |  http_request_duration_seconds_bucket{le="+Inf"} 144320             |
  |  http_request_duration_seconds_sum 53423.3                           |
  |  http_request_duration_seconds_count 144320                          |
  |                                                                       |
  |  # TYPE payment_processing_active gauge                              |
  |  # HELP payment_processing_active Current active payment requests    |
  |  payment_processing_active{service="payment"} 42                     |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 12.3 Metric Types

```
  +-----------------------------------------------------------------------+
  |  FOUR METRIC TYPES                                                    |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  1. COUNTER -- monotonically increasing value, resets on restart      |
  |     Example: http_requests_total, errors_total                       |
  |     Operations: rate(), increase(), resets()                          |
  |     Rule: NEVER use a counter value directly; always use rate()      |
  |                                                                       |
  |  2. GAUGE -- value that goes up and down                             |
  |     Example: cpu_usage_percent, active_connections, queue_depth      |
  |     Operations: avg_over_time(), max_over_time(), deriv()            |
  |                                                                       |
  |  3. HISTOGRAM -- samples observations into configurable buckets      |
  |     Example: request_duration_seconds (with _bucket, _sum, _count)   |
  |     Operations: histogram_quantile(), rate() on _count              |
  |     Key: bucket boundaries must be chosen at instrumentation time    |
  |                                                                       |
  |  4. SUMMARY -- similar to histogram but calculates quantiles client-side|
  |     Example: rpc_duration_seconds{quantile="0.99"} 4.3              |
  |     Limitation: NOT aggregatable across instances (pre-computed)     |
  |     Recommendation: prefer histograms over summaries                 |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 12.4 Simulation Mapping

| Simulation Class   | Production Technology                               |
|--------------------|-----------------------------------------------------|
| `MetricType` enum  | OpenMetrics TYPE annotation (counter, gauge, histogram, summary) |
| `Metric` model     | OpenMetrics metric family (name + type + help)      |
| `MetricPoint`      | OpenMetrics sample (labels + timestamp + value)     |
| `RateAggregationStrategy` | PromQL rate() function on counter type        |

---

## 13. Simulation-to-Production Mapping

### 13.1 Complete Class Mapping

```
  +-----------------------------------------------------------------------+
  |  SIMULATION CLASS -> PRODUCTION TECHNOLOGY (COMPLETE)                  |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  MODEL LAYER                                                          |
  |  -----------------------------------------------------------------   |
  |  Metric              -> Prometheus metric family                      |
  |  MetricPoint         -> Prometheus sample (timestamp + float64)       |
  |  MetricType          -> OpenMetrics metric type                       |
  |  Trace               -> Jaeger trace                                  |
  |  Span                -> Jaeger/OTel span                              |
  |  SpanStatus          -> OTel SpanStatus (OK, ERROR, UNSET)           |
  |  SpanLog             -> OTel span event (structured log on span)     |
  |  TraceContext         -> W3C traceparent + tracestate headers         |
  |  LogEntry            -> Elasticsearch document / Loki log line       |
  |  LogLevel            -> Syslog severity level                        |
  |  Alert               -> Alertmanager alert instance                  |
  |  AlertRule           -> Prometheus alerting rule                      |
  |  AlertSeverity       -> PagerDuty severity / OpsGenie priority       |
  |  AlertStatus         -> Alertmanager alert state (firing/resolved)   |
  |  ServiceNode         -> Grafana service map node                     |
  |                                                                       |
  |  ENGINE LAYER                                                         |
  |  -----------------------------------------------------------------   |
  |  MetricAggregator    -> PromQL recording rules / continuous aggregates|
  |  TraceAssembler      -> Jaeger Collector / OTel tail-sampling processor|
  |  SamplingEngine      -> OTel Sampler implementations                 |
  |  LogProcessor        -> Logstash / Fluent Bit pipeline               |
  |  TimeSeriesStore     -> ClickHouse / TimescaleDB / Prometheus TSDB   |
  |                                                                       |
  |  SERVICE LAYER                                                        |
  |  -----------------------------------------------------------------   |
  |  MetricService       -> Prometheus scrape + remote write pipeline    |
  |  TracingService      -> Jaeger Query Service                         |
  |  LogService          -> Elasticsearch / Loki query API               |
  |  AlertService        -> Prometheus Alertmanager                      |
  |  DashboardService    -> Grafana dashboard query engine               |
  |  ServiceMapService   -> Grafana Service Map plugin                   |
  |  ObservabilityService-> OTel Collector (unified telemetry router)    |
  |                                                                       |
  |  REPOSITORY LAYER                                                     |
  |  -----------------------------------------------------------------   |
  |  InMemoryMetricRepository   -> ClickHouse / TimescaleDB              |
  |  InMemoryTraceRepository    -> Jaeger storage (Cassandra / ES)       |
  |  InMemoryLogRepository      -> Elasticsearch / Loki (S3)            |
  |  InMemoryAlertRepository    -> PostgreSQL (alert history + state)    |
  |                                                                       |
  |  STRATEGY LAYER (Strategy Pattern -- GoF)                             |
  |  -----------------------------------------------------------------   |
  |  SamplingStrategy (interface)          -> OTel Sampler interface      |
  |  HeadBasedSamplingStrategy             -> OTel TraceIdRatioBased     |
  |  TailBasedSamplingStrategy             -> OTel tail_sampling processor|
  |  RateLimitedSamplingStrategy           -> OTel rate_limiting sampler |
  |  AggregationStrategy (interface)       -> PromQL function            |
  |  PercentileAggregationStrategy         -> histogram_quantile / t-digest|
  |  RateAggregationStrategy               -> PromQL rate()             |
  |  AlertingStrategy (interface)          -> Alert rule evaluator       |
  |  ThresholdAlertingStrategy             -> Prometheus threshold alert |
  |  AnomalyDetectionAlertingStrategy      -> ML-based anomaly detection|
  |                                                                       |
  |  CONTROLLER / CONFIG / DISPLAY                                        |
  |  -----------------------------------------------------------------   |
  |  ObservabilityController -> REST API (Grafana data source proxy)     |
  |  AppConfig               -> Helm values / Terraform config           |
  |  ObservabilityStatsDisplay -> Grafana panel renderer                 |
  |  ObservabilityPlatformApp  -> Docker Compose / Kubernetes deployment |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

---

## 14. Technology Selection Matrix

### 14.1 Decision Matrix

```
  +-----------------------------------------------------------------------+
  |  WHEN TO USE WHICH TECHNOLOGY                                         |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Need                        | Technology        | Why                |
  |  ----------------------------+-------------------+--------------------+
  |  Metric collection (K8s)     | Prometheus        | Native K8s SD, pull|
  |  Metric collection (cloud)   | OTel + Mimir      | Push, multi-cloud  |
  |  Long-term metric storage    | Thanos / Mimir    | S3-backed, scalable|
  |  Metric analytics (ad-hoc)   | ClickHouse        | Fast SQL analytics |
  |  Distributed tracing         | Jaeger + OTel     | CNCF standard      |
  |  Trace storage (cost-aware)  | ClickHouse        | Columnar, cheap    |
  |  Trace storage (search-heavy)| Elasticsearch     | Full-text on tags  |
  |  Log storage (cost-aware)    | Loki + S3         | Label-index only   |
  |  Log storage (SIEM/security) | Elasticsearch     | Full-text index    |
  |  Dashboarding                | Grafana           | Multi-source       |
  |  Alerting                    | Alertmanager      | Dedup, routing     |
  |  Telemetry pipeline          | OTel Collector    | Vendor-neutral     |
  |  Streaming buffer            | Kafka             | Replay, fan-out    |
  |  Query caching               | Redis             | Sub-ms latency     |
  |  Percentile computation      | t-digest/DDSketch | Streaming, mergeable|
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 14.2 Scale Tiers

```
  +-----------------------------------------------------------------------+
  |  SCALE TIER RECOMMENDATIONS                                           |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  TIER 1: Small (< 10 services, < 10K metrics/sec)                   |
  |    Prometheus + Grafana + Jaeger (all-in-one) + Loki                 |
  |    Single-node, docker-compose, minimal operational burden           |
  |                                                                       |
  |  TIER 2: Medium (10-100 services, 10K-100K metrics/sec)             |
  |    Prometheus + Thanos + Grafana + Jaeger + Loki                     |
  |    Kafka for buffering, Redis for caching                            |
  |    Multi-node, Kubernetes, Helm charts                               |
  |                                                                       |
  |  TIER 3: Large (100+ services, 100K-1M metrics/sec)                 |
  |    Mimir (metrics) + Tempo (traces) + Loki (logs) + Grafana         |
  |    Kafka for all telemetry, ClickHouse for analytics                 |
  |    Multi-cluster, multi-region, dedicated SRE team                   |
  |                                                                       |
  |  TIER 4: Massive (1000+ services, 1M+ metrics/sec)                  |
  |    Datadog / New Relic / Grafana Cloud (managed)                     |
  |    Self-hosted is possible but requires dedicated infra team         |
  |    Hybrid: managed for metrics+traces, self-hosted Kafka+ClickHouse |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### 14.3 Cost Considerations

```
  +-----------------------------------------------------------------------+
  |  COST BREAKDOWN (SELF-HOSTED, ~50 SERVICES)                          |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Component              | Nodes | Memory  | Disk    | Monthly Est.   |
  |  -----------------------+-------+---------+---------+----------------+
  |  Prometheus (HA pair)   | 2     | 16GB ea | 500GB   | $400           |
  |  Thanos (compactor+store)| 3    | 8GB ea  | S3      | $300 + S3 cost |
  |  Kafka (3 broker)       | 3     | 16GB ea | 2TB ea  | $600           |
  |  ClickHouse (3 shard)   | 3     | 32GB ea | 4TB ea  | $900           |
  |  Jaeger (3 collector)   | 3     | 4GB ea  | -       | $200           |
  |  Loki (3 ingester)      | 3     | 8GB ea  | S3      | $300 + S3 cost |
  |  Grafana (HA pair)      | 2     | 4GB ea  | -       | $100           |
  |  Redis (cluster, 3 node)| 3     | 8GB ea  | -       | $300           |
  |  OTel Collector (DaemonSet)| 50 | 512MB ea| -       | $200           |
  |  -----------------------+-------+---------+---------+----------------+
  |  Total infrastructure                              | ~$3,300/month  |
  |  + S3 storage (5TB, ~$100/month)                   | ~$3,400/month  |
  |                                                                       |
  |  vs. Datadog (50 services, 100K metrics/sec)       | ~$15,000/month |
  |  vs. New Relic (50 services, similar scale)         | ~$12,000/month |
  |                                                                       |
  |  Self-hosted saves money but costs 1-2 FTE SRE engineers            |
  |  Break-even: ~$8K/month in infra = cheaper self-hosted               |
  |  Above ~$15K/month in infra = consider managed service               |
  |                                                                       |
  +-----------------------------------------------------------------------+
```
