# Observability Platform (Datadog/Grafana-like) -- Cloud Service Mapping

## Component-to-Service Mapping

| Our Component | AWS | Azure | GCP | Notes |
|---------------|-----|-------|-----|-------|
| **Metric Collection** | CloudWatch Metrics + Managed Prometheus | Azure Monitor Metrics + Managed Prometheus | Cloud Monitoring + Managed Prometheus | Prometheus scrape or push model, cloud-native metrics |
| **Metric Storage (TSDB)** | Amazon Timestream / Managed Grafana (Mimir) | Azure Data Explorer (ADX) / Azure Monitor Metrics Store | Cloud Bigtable / Managed Service for Prometheus | Time-series optimized storage with retention policies |
| **Distributed Tracing** | AWS X-Ray + OTel | Azure App Insights (distributed tracing) | Cloud Trace | End-to-end request tracing across services |
| **Log Aggregation** | CloudWatch Logs + OpenSearch | Azure Log Analytics (Monitor Logs) | Cloud Logging (Stackdriver) | Centralized log ingestion, search, retention |
| **Alerting** | CloudWatch Alarms + SNS | Azure Monitor Alerts + Action Groups | Cloud Monitoring Alerting + Pub/Sub | Threshold and anomaly-based alerts with notification routing |
| **Dashboards** | CloudWatch Dashboards / Managed Grafana | Azure Dashboards / Managed Grafana | Cloud Monitoring Dashboards / Managed Grafana (Marketplace) | Unified visualization across metrics, logs, traces |
| **Telemetry Pipeline** | CloudWatch Agent + OTel Collector (ECS) | Azure Monitor Agent + OTel Collector (AKS) | Ops Agent + OTel Collector (GKE) | Collection, filtering, routing of telemetry signals |
| **Event Streaming** | Amazon Kinesis / MSK (Managed Kafka) | Azure Event Hubs (Kafka protocol) | Pub/Sub / Managed Kafka (Confluent) | Telemetry buffering, fan-out, replay |
| **Alert Notification** | SNS + SES + Lambda | Event Grid + Logic Apps + SendGrid | Pub/Sub + Cloud Functions + SendGrid | Multi-channel notification delivery |
| **Service Map** | X-Ray Service Map | App Insights Application Map | Cloud Trace Service Graph | Auto-discovered service dependency visualization |
| **Anomaly Detection** | CloudWatch Anomaly Detection | Azure Monitor Smart Detection | Cloud Monitoring MQL anomaly | ML-based anomaly alerting on metrics |
| **Caching** | ElastiCache Redis (cluster mode) | Azure Cache for Redis | Memorystore (Redis) | Dashboard query cache, service map cache, alert state |

---

## Observability Architecture on AWS (Numbered Flow)

```
User investigates a latency spike on the payment service:
  Grafana dashboard -> trace waterfall -> correlated logs -> root cause

    1. INSTRUMENTED SERVICES (ECS Fargate / EKS):
       Each microservice runs with:
         - OTel Java agent (auto-instrumentation, zero code change)
         - OTel SDK configured to export:
           - Traces: OTLP gRPC to OTel Collector
           - Metrics: Prometheus /metrics endpoint (scraped by Managed Prometheus)
           - Logs: stdout (collected by Fluent Bit DaemonSet)
       |
       Environment variables:
         OTEL_SERVICE_NAME=payment-service
         OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
         OTEL_TRACES_SAMPLER=parentbased_traceidratio
         OTEL_TRACES_SAMPLER_ARG=0.1
    |
    v
    2. OTEL COLLECTOR (ECS sidecar or DaemonSet):
       Deployed as:
         - Sidecar (ECS): one collector per task, low latency
         - DaemonSet (EKS): one collector per node, shared
       |
       Pipeline configuration:
         Receivers:
           - OTLP (gRPC :4317, HTTP :4318) -- traces + metrics from apps
           - Prometheus (scrape :9090) -- self-monitoring metrics
         Processors:
           - batch: send_batch_size=1024, timeout=5s
           - memory_limiter: limit_mib=1024, spike_limit_mib=256
           - tail_sampling:
               - errors: always keep ERROR spans
               - latency: keep traces > 1s duration
               - probabilistic: 10% of remaining traces
           - filter: drop /health and /readiness spans
         Exporters:
           - otlp/xray: AWS X-Ray OTLP endpoint (or X-Ray daemon)
           - prometheusremotewrite: Amazon Managed Prometheus (AMP)
           - awscloudwatchlogs: CloudWatch Logs (or Kinesis -> OpenSearch)
    |
    v
    3. METRIC STORAGE: Amazon Managed Service for Prometheus (AMP):
       |
       Architecture:
         - Managed Prometheus workspace (no servers to manage)
         - Prometheus remote_write API (receives from OTel Collector)
         - PromQL API for querying (Grafana connects here)
         - Storage: AWS-managed (S3-backed, multi-AZ)
       |
       Ingestion:
         - Remote write from OTel Collector (metrics pipeline)
         - Prometheus server remote_write (if running self-managed Prometheus)
         - Rate limit: 200K samples/sec per workspace
       |
       Retention:
         - Default: 150 days
         - Recording rules for downsampling (evaluated server-side)
       |
       Cost model:
         - $0.003 per 1K metric samples ingested
         - $0.01 per 1K metric samples queried
         - 100K samples/sec = ~$780K/year ingestion (EXPENSIVE at scale)
         - Consider self-managed Thanos/Mimir on EKS for high volume
    |
    v
    4. TRACE STORAGE: AWS X-Ray:
       |
       Architecture:
         - X-Ray receives spans via OTLP or X-Ray SDK
         - Stores traces for 30 days (configurable)
         - Provides trace search, service map, analytics
       |
       Service Map (auto-generated):
         - X-Ray builds service dependency graph from trace data
         - Shows: request count, error rate, average latency per edge
         - Updated in near-real-time (minutes)
       |
       X-Ray Insights:
         - Automatic anomaly detection on trace data
         - Alerts on latency/error spikes per service
       |
       Limitations:
         - Sampling: X-Ray applies its own sampling (1 req/sec + 5% after)
         - No PromQL: query API is X-Ray-specific, not standard
         - Trace storage: not ClickHouse-level analytics (no SQL)
       |
       Alternative: Grafana Tempo on EKS + S3
         - OSS trace storage, full TraceQL query language
         - Object storage backend (S3), lower cost at scale
         - Integrates natively with Grafana
    |
    v
    5. LOG STORAGE: CloudWatch Logs + OpenSearch:
       |
       Path A: CloudWatch Logs (simple, managed):
         - Fluent Bit -> CloudWatch Logs API
         - Log Insights: SQL-like query language
         - Retention: configurable (1 day to indefinite)
         - Cost: $0.50 per GB ingested + $0.03 per GB stored/month
       |
       Path B: OpenSearch (advanced search, dashboards):
         - Fluent Bit -> Kinesis Data Firehose -> OpenSearch
         - Full-text search (Lucene-based, same as Elasticsearch)
         - OpenSearch Dashboards (Kibana fork) for visualization
         - Cost: instance-based (more predictable for high volume)
       |
       Path C: Grafana Loki on EKS + S3 (cheapest at scale):
         - Fluent Bit -> Loki ingest API
         - S3 backend (object storage, $0.023/GB/month)
         - LogQL queries in Grafana (correlated with metrics/traces)
         - Best integration with Grafana stack
       |
       Recommendation:
         < 100 GB/day: CloudWatch Logs (simplest)
         100 GB - 1 TB/day: Loki + S3 (cost-effective)
         > 1 TB/day or SIEM needs: OpenSearch (full-text search)
    |
    v
    6. DASHBOARDING: Amazon Managed Grafana (AMG):
       |
       Architecture:
         - Managed Grafana workspace (no servers)
         - SSO integration via AWS IAM Identity Center
         - Data sources pre-configured:
           - Amazon Managed Prometheus (PromQL)
           - CloudWatch Logs (CloudWatch Logs Insights)
           - AWS X-Ray (trace search and service map)
           - OpenSearch (log search)
       |
       Dashboard example: "Payment Service Overview"
         Row 1: Request rate, error rate, active connections (Prometheus)
         Row 2: p50/p95/p99 latency heatmap (Prometheus)
         Row 3: Recent error traces (X-Ray, filtered by service)
         Row 4: Recent error logs (CloudWatch Logs / Loki)
       |
       Alerting:
         - Grafana Alerting rules (multi-data-source)
         - Contact points: SNS, PagerDuty, Slack, email
         - Notification policies: route by severity/team
    |
    v
    7. ALERT PIPELINE: CloudWatch Alarms + SNS:
       |
       Path A: CloudWatch Alarms (metric-based):
         - Alarm on CloudWatch metric (e.g., ALB 5xx count > 100)
         - SNS topic -> Lambda (PagerDuty API) + SES (email) + Slack webhook
         - Composite alarms: AND/OR across multiple alarms
       |
       Path B: Grafana Alerting (PromQL-based):
         - Alert rule: rate(http_requests_total{status=~"5.."}[5m]) > 0.05
         - Evaluation interval: 15 seconds
         - For duration: 5 minutes (must exceed threshold for 5m to fire)
         - Contact point: SNS -> PagerDuty + Slack
       |
       Path C: X-Ray Insights (automatic anomaly):
         - X-Ray detects latency anomaly automatically
         - Publishes to EventBridge
         - EventBridge rule -> SNS -> PagerDuty
       |
       Notification routing:
         CRITICAL (p99 > 5s, error rate > 10%):
           -> PagerDuty (wake on-call engineer)
           -> Slack #incidents channel
           -> Email: oncall@company.com
         WARNING (p99 > 1s, error rate > 5%):
           -> Slack #alerts channel
           -> Email: team@company.com
         INFO (deployment annotation, config change):
           -> Slack #deployments channel
    |
    v
    8. CACHING LAYER: ElastiCache Redis (cluster mode):
       |
       Redis cluster: 3 shards, 2 replicas each = 9 nodes
       Memory: 8 GB per node = 24 GB usable
       |
       Cache contents:
         - Dashboard query results (TTL: 30s)
         - Service map (TTL: 5 min)
         - Alert state (write-through, no TTL)
         - Metric metadata (TTL: 1 hour)
       |
       Eviction policy: allkeys-lru
       Encryption: at-rest (KMS) + in-transit (TLS)
```

---

## Observability Architecture on Azure (Numbered Flow)

```
Same scenario: investigating a latency spike on the payment service.

    1. INSTRUMENTED SERVICES (AKS / Container Apps):
       Each microservice runs with:
         - OTel Java agent (auto-instrumentation)
         - OTel SDK exporting via OTLP
         - Or: Application Insights Java agent (auto-instrumentation)
           - Auto-collects: HTTP, JDBC, Redis, Kafka, gRPC spans
           - Connection string: APPLICATIONINSIGHTS_CONNECTION_STRING
       |
       Azure-specific: Application Insights is the native APM solution.
       It provides unified metrics, traces, and logs out of the box.
       For Grafana-based stack, use OTel + Azure Managed Prometheus/Grafana.
    |
    v
    2. AZURE MONITOR DATA PLATFORM:
       |
       Azure Monitor is the unified observability platform:
         +-- Azure Monitor Metrics (time-series, 93-day retention)
         +-- Azure Monitor Logs / Log Analytics (KQL query, configurable retention)
         +-- Application Insights (APM: traces, dependencies, exceptions)
         +-- Azure Managed Prometheus (PromQL-compatible metric store)
         +-- Azure Managed Grafana (Grafana as a service)
       |
       Key difference from AWS:
         Azure Monitor is a UNIFIED platform (not separate services).
         Application Insights handles traces, metrics, AND logs together.
         Single query language (KQL) across all signal types.
    |
    v
    3. METRIC STORAGE: Azure Managed Prometheus + Azure Monitor Metrics:
       |
       Option A: Azure Monitor Metrics (native):
         - Platform metrics auto-collected (VM, AKS, App Service, etc.)
         - Custom metrics via Application Insights SDK or OTel
         - 93-day retention (standard metrics)
         - Metric queries via Azure Monitor REST API or Grafana
         - Cost: first 10 standard metrics free, $0.258 per additional metric/month
       |
       Option B: Azure Managed Prometheus (AMProm):
         - PromQL-compatible API (standard Prometheus remote_write)
         - Grafana connects natively
         - 18-month retention
         - Recording rules evaluated server-side
         - Cost: $0.003 per 1K samples ingested, $0.01 per 1K queried
       |
       Recommendation:
         Use both: Azure Monitor Metrics for platform metrics (free tier),
         Azure Managed Prometheus for application metrics (PromQL dashboards).
    |
    v
    4. TRACE STORAGE: Application Insights (Distributed Tracing):
       |
       Architecture:
         - Application Insights receives spans via OTel or AI SDK
         - Stores in Log Analytics workspace (same as logs!)
         - Query with KQL: "dependencies | where duration > 1s"
       |
       Distributed Tracing features:
         - End-to-end transaction search (by operation ID = traceId)
         - Application Map (auto-generated service dependency graph)
         - Failure analysis (automatic error grouping)
         - Performance analysis (automatic slow dependency detection)
         - Smart Detection (ML-based anomaly alerting)
       |
       Sampling:
         - Adaptive sampling (default): adjusts rate to stay within budget
         - Fixed-rate sampling: configurable percentage
         - Ingestion sampling: server-side sampling at AI endpoint
       |
       Limitations:
         - KQL, not TraceQL (not Jaeger/Tempo compatible query language)
         - Retention: 90 days default (configurable up to 730 days)
         - Cost: $2.76 per GB ingested into Log Analytics workspace
       |
       Alternative: Grafana Tempo on AKS + Azure Blob Storage
    |
    v
    5. LOG STORAGE: Azure Log Analytics:
       |
       Architecture:
         - All Azure services can send diagnostic logs to Log Analytics
         - Application logs via OTel Logs or Application Insights
         - Container logs via Container Insights (AKS integration)
       |
       KQL query examples:
         // Find payment errors in last hour
         AppTraces
         | where TimeGenerated > ago(1h)
         | where Properties.service == "payment"
         | where SeverityLevel == 3  // Error
         | where Message contains "timeout"
         | order by TimeGenerated desc
         | take 100

         // Correlate with trace
         AppDependencies
         | where OperationId == "abc123"
         | order by TimeGenerated asc
       |
       Retention: 30 days free, configurable up to 730 days
       Cost: $2.76 per GB ingested (same as Application Insights)
       |
       Alternative: Grafana Loki on AKS + Azure Blob Storage
    |
    v
    6. DASHBOARDING: Azure Managed Grafana:
       |
       - Managed Grafana workspace (integrated with Azure AD for SSO)
       - Pre-built data source plugins:
         - Azure Monitor (metrics, logs, Application Insights)
         - Azure Managed Prometheus (PromQL)
         - Azure Data Explorer (KQL for analytics)
       |
       Azure-native alternative: Azure Dashboards
         - Pin Azure Monitor charts, Log Analytics queries, App Insights maps
         - Limited customization compared to Grafana
         - Free (included with Azure Monitor)
    |
    v
    7. ALERT PIPELINE: Azure Monitor Alerts + Action Groups:
       |
       Alert types:
         - Metric alerts: threshold on Azure Monitor or Prometheus metrics
         - Log alerts: KQL query returns results (e.g., error count > 10)
         - Activity log alerts: Azure resource events (deployment, scaling)
         - Smart Detection alerts: ML anomaly detection (App Insights)
       |
       Action Groups (notification routing):
         - Email/SMS/Push notification
         - Azure Functions (custom webhook logic)
         - Logic Apps (workflow automation: create Jira ticket, etc.)
         - Event Hubs (stream to external SIEM)
         - PagerDuty / Slack via webhook
       |
       Severity mapping:
         Sev 0 (Critical): PagerDuty + SMS + Slack #incidents
         Sev 1 (Error): Slack #alerts + email
         Sev 2 (Warning): Slack #monitoring
         Sev 3 (Informational): Log only
         Sev 4 (Verbose): Suppress
    |
    v
    8. CACHING LAYER: Azure Cache for Redis:
       |
       Tier: Premium (P1, 6 GB, clustering support)
       Cluster: 3 shards = 18 GB usable
       |
       Same caching strategy as AWS:
         - Dashboard query results (TTL: 30s)
         - Service map (TTL: 5 min)
         - Alert state (write-through)
         - Private endpoint for VNet isolation
```

---

## Observability Architecture on GCP (Numbered Flow)

```
Same scenario: investigating a latency spike on the payment service.

    1. INSTRUMENTED SERVICES (GKE Autopilot / Cloud Run):
       Each microservice runs with:
         - OTel Java agent (auto-instrumentation)
         - OTel SDK configured to export to OTel Collector
         - Or: Google Cloud client libraries (auto-export to Cloud Trace/Monitoring)
       |
       GCP-specific: Cloud Operations suite (formerly Stackdriver) provides
       native integration for GKE workloads. No agent needed for GKE --
       platform metrics are collected automatically.
    |
    v
    2. GCP CLOUD OPERATIONS SUITE:
       |
       Unified observability:
         +-- Cloud Monitoring (metrics, dashboards, alerting)
         +-- Cloud Trace (distributed tracing, latency analysis)
         +-- Cloud Logging (log ingestion, search, routing)
         +-- Managed Service for Prometheus (PromQL, Grafana compatible)
         +-- Error Reporting (automatic error grouping, notifications)
       |
       Key advantage:
         GKE workloads automatically emit metrics and logs to Cloud Operations.
         Zero configuration for platform metrics (CPU, memory, network, disk).
         Application-level telemetry requires OTel instrumentation.
    |
    v
    3. METRIC STORAGE: Google Managed Prometheus + Cloud Monitoring:
       |
       Option A: Cloud Monitoring (native):
         - Platform metrics: auto-collected (GCE, GKE, Cloud Run, etc.)
         - Custom metrics: via OTel or Monitoring client library
         - 24-month retention (free for GCP resource metrics)
         - MQL (Monitoring Query Language) for dashboards and alerts
         - Cost: $0.258 per 1K custom metric samples/month
       |
       Option B: Google Managed Prometheus (GMP):
         - Prometheus-compatible (PromQL, remote_write)
         - Deploys as GKE Managed Collection (collector built into GKE)
         - Monarch backend (Google's internal TSDB -- same as Borgmon)
         - Grafana connects via Prometheus data source
         - Recording rules: evaluated in GMP (server-side)
         - Cost: $0.15 per 1M samples ingested, $0.09 per 1M queried
           (significantly cheaper than AWS AMP at scale)
       |
       GCP advantage:
         Google Managed Prometheus uses the same Monarch TSDB that powers
         Google's internal monitoring. It handles billions of time-series
         natively. This is arguably the most scalable managed Prometheus.
    |
    v
    4. TRACE STORAGE: Cloud Trace:
       |
       Architecture:
         - Receives spans via OTel (OTLP), Cloud Trace API, or Zipkin format
         - Auto-analysis: latency distributions, bottleneck detection
         - Trace search by service, latency, status, custom labels
       |
       Features:
         - Latency distribution: visual histogram of trace durations
         - Auto-detected bottleneck: highlights slowest span in trace
         - Trace scatter plot: duration vs time (spot anomalies visually)
         - Cross-project traces: traces across GCP projects
       |
       Limitations:
         - No TraceQL (query API is Cloud Trace-specific)
         - Retention: 30 days
         - Cost: $0.20 per 1M spans ingested (first 2.5M spans/month free)
       |
       Alternative: Grafana Tempo on GKE + Cloud Storage (GCS)
         - S3-compatible backend (GCS)
         - TraceQL for rich trace querying
         - Integrates with Grafana stack
    |
    v
    5. LOG STORAGE: Cloud Logging:
       |
       Architecture:
         - GKE stdout/stderr auto-collected (zero config)
         - Cloud Logging API for custom log entries
         - Log Router: routes logs to multiple sinks
       |
       Log Router sinks:
         - Cloud Logging storage (default, query with Logs Explorer)
         - BigQuery (SQL analytics on logs, long-term retention)
         - Cloud Storage (GCS, archival, cheapest at scale)
         - Pub/Sub (real-time log streaming to external systems)
       |
       Query (Logs Explorer):
         resource.type="k8s_container"
         resource.labels.container_name="payment-service"
         severity>=ERROR
         textPayload:"timeout"
         timestamp>="2026-05-09T14:00:00Z"
       |
       Log-based metrics:
         - Create a metric from log entries matching a filter
         - Example: count of ERROR logs per service per minute
         - Available in Cloud Monitoring for dashboards and alerts
         - Zero-code way to get metrics from logs
       |
       Retention: 30 days (_Default bucket), configurable per bucket
       Cost: $0.50 per GiB ingested (first 50 GiB/project/month free)
       |
       Alternative: Grafana Loki on GKE + GCS
    |
    v
    6. DASHBOARDING: Cloud Monitoring Dashboards + Managed Grafana:
       |
       Option A: Cloud Monitoring Dashboards (native):
         - Built-in dashboard builder (drag-and-drop)
         - MQL for custom charts
         - SLO monitoring dashboards (built-in SLI/SLO support!)
         - Free (included with Cloud Monitoring)
       |
       Option B: Grafana (self-managed on GKE or marketplace):
         - Google Managed Prometheus as Prometheus data source
         - Cloud Logging as Loki data source (via plugin)
         - Cloud Trace as Jaeger data source (via plugin)
         - Full Grafana feature set (template variables, annotations, etc.)
       |
       GCP advantage:
         Cloud Monitoring has built-in SLO monitoring:
           - Define SLIs (availability, latency)
           - Set SLO targets (99.9% availability)
           - Error budget tracking with burn-rate alerts
           - This is a killer feature for SRE teams
    |
    v
    7. ALERT PIPELINE: Cloud Monitoring Alerting + Pub/Sub:
       |
       Alert policy types:
         - Metric threshold: metric > value for duration
         - Metric absence: no data for duration (target down)
         - Log-based: log entry matches filter
         - Process health: expected process not running
         - Uptime check: HTTP/TCP probe fails
       |
       Notification channels:
         - PagerDuty (native integration)
         - Slack (native integration)
         - Email
         - SMS
         - Pub/Sub topic (for custom processing)
         - Webhook
         - Cloud Functions (via Pub/Sub trigger)
       |
       Incident management:
         - Cloud Monitoring auto-creates incidents for firing alerts
         - Incident timeline: alert -> acknowledge -> resolve
         - Integrates with PagerDuty incident management
       |
       Alert routing by severity:
         CRITICAL: PagerDuty (page on-call) + Slack #incidents
         WARNING: Slack #alerts + email
         INFO: Slack #monitoring
    |
    v
    8. CACHING LAYER: Memorystore (Redis):
       |
       Tier: Standard (HA with replica)
       Memory: 10 GB instance
       |
       Same caching strategy as AWS/Azure:
         - Dashboard query results (TTL: 30s)
         - Service map (TTL: 5 min)
         - Alert state (write-through)
         - Private service access (VPC peering)
```

---

## Cross-Cloud Comparison Matrix

### Metric Collection and Storage

```
  +---------------------------+---------------------------+---------------------------+---------------------------+
  | Feature                   | AWS                       | Azure                     | GCP                       |
  +---------------------------+---------------------------+---------------------------+---------------------------+
  | Native metrics            | CloudWatch Metrics        | Azure Monitor Metrics     | Cloud Monitoring          |
  | Managed Prometheus        | Amazon Managed Prometheus | Azure Managed Prometheus  | Google Managed Prometheus |
  |                           | (AMP)                     | (AMProm)                  | (GMP)                     |
  | PromQL support            | Yes (via AMP)             | Yes (via AMProm)          | Yes (via GMP)             |
  | Max retention             | AMP: no limit (pay/store) | AMProm: 18 months         | GMP: 24 months            |
  | Custom metrics cost       | $0.30/metric/month (CW)   | $0.258/metric/month       | $0.258/1K samples/month   |
  | Prometheus cost model     | $0.003/1K ingested        | $0.003/1K ingested        | $0.15/1M ingested         |
  |                           | $0.01/1K queried          | $0.01/1K queried          | $0.09/1M queried          |
  | Scale advantage           | Good                      | Good                      | Best (Monarch backend)    |
  | Time-series DB (alt)      | Amazon Timestream         | Azure Data Explorer (ADX) | Cloud Bigtable            |
  +---------------------------+---------------------------+---------------------------+---------------------------+
```

### Distributed Tracing

```
  +---------------------------+---------------------------+---------------------------+---------------------------+
  | Feature                   | AWS                       | Azure                     | GCP                       |
  +---------------------------+---------------------------+---------------------------+---------------------------+
  | Native tracing            | AWS X-Ray                 | App Insights Distributed  | Cloud Trace               |
  |                           |                           | Tracing                   |                           |
  | OTel support              | Yes (OTLP receiver)       | Yes (OTLP + AI SDK)       | Yes (OTLP receiver)       |
  | Service map               | X-Ray Service Map         | App Insights App Map      | Cloud Trace Graph         |
  | Query language             | X-Ray filter expressions  | KQL                       | Cloud Trace filters       |
  | Retention                 | 30 days                   | 90 days (default)         | 30 days                   |
  | Sampling                  | 1/sec + 5% (default)      | Adaptive (auto)           | All spans stored          |
  | Anomaly detection         | X-Ray Insights            | Smart Detection           | Auto-analysis             |
  | Cost                      | $5.00/1M traces recorded  | $2.76/GB (Log Analytics)  | $0.20/1M spans            |
  | OSS alternative           | Grafana Tempo + S3        | Grafana Tempo + Blob      | Grafana Tempo + GCS       |
  +---------------------------+---------------------------+---------------------------+---------------------------+
```

### Log Aggregation

```
  +---------------------------+---------------------------+---------------------------+---------------------------+
  | Feature                   | AWS                       | Azure                     | GCP                       |
  +---------------------------+---------------------------+---------------------------+---------------------------+
  | Native logging            | CloudWatch Logs           | Log Analytics (Azure Mon) | Cloud Logging             |
  | Query language             | Logs Insights (SQL-like)  | KQL                       | Logging query language    |
  | Full-text search          | Partial (Logs Insights)   | Yes (KQL)                 | Partial (structured only) |
  | Advanced search           | OpenSearch (Elasticsearch)| Log Analytics (KQL)       | BigQuery (SQL on logs)    |
  | Auto-collection (K8s)     | Fluent Bit -> CW Logs     | Container Insights (auto) | Auto (GKE built-in)      |
  | Log routing               | Subscription filters      | Diagnostic settings       | Log Router (sinks)        |
  | Retention                 | Configurable (1d-indef)   | 30d free, up to 730d      | 30d (default bucket)      |
  | Ingestion cost            | $0.50/GB                  | $2.76/GB                  | $0.50/GiB                 |
  | Storage cost              | $0.03/GB/month            | $0.12/GB/month (first 31d)| $0.01/GiB/month           |
  | OSS alternative           | Grafana Loki + S3         | Grafana Loki + Blob       | Grafana Loki + GCS        |
  +---------------------------+---------------------------+---------------------------+---------------------------+
```

### Alerting and Notification

```
  +---------------------------+---------------------------+---------------------------+---------------------------+
  | Feature                   | AWS                       | Azure                     | GCP                       |
  +---------------------------+---------------------------+---------------------------+---------------------------+
  | Alert engine              | CloudWatch Alarms         | Azure Monitor Alerts      | Cloud Monitoring Alerts   |
  | PromQL alerts             | Via Grafana Alerting      | Via Grafana Alerting      | Via Grafana Alerting      |
  | Notification routing      | SNS topics                | Action Groups             | Notification channels     |
  | PagerDuty integration     | Via SNS + Lambda          | Native + Action Group     | Native channel            |
  | Slack integration         | Via SNS + Lambda          | Logic Apps webhook        | Native channel            |
  | Composite alerts          | Composite alarms (AND/OR) | Log alerts (KQL join)     | MQL multi-condition       |
  | Anomaly detection         | CW Anomaly Detection      | Smart Detection           | MQL anomaly function      |
  | SLO monitoring            | Manual (custom metrics)   | Manual                    | Built-in SLO monitoring!  |
  | Incident management       | Via AWS Systems Manager   | Via Azure Sentinel        | Built-in incidents        |
  | Cost                      | $0.10/alarm/month         | Free (with Monitor)       | Free (with Monitoring)    |
  +---------------------------+---------------------------+---------------------------+---------------------------+
```

---

## Managed Grafana Stack (All Clouds)

```
  +-----------------------------------------------------------------------+
  |  THE GRAFANA STACK -- VENDOR-NEUTRAL OBSERVABILITY                    |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  All three clouds now offer managed Grafana services that can run    |
  |  the same Grafana stack across providers:                            |
  |                                                                       |
  |  Component       | AWS Managed      | Azure Managed    | GCP          |
  |  ----------------+------------------+------------------+--------------+
  |  Grafana         | Amazon Managed   | Azure Managed    | Marketplace  |
  |                  | Grafana (AMG)    | Grafana           | deployment   |
  |  Prometheus      | AMP              | AMProm           | GMP          |
  |  Loki (logs)     | Self-hosted (EKS)| Self-hosted (AKS)| Self-hosted  |
  |  Tempo (traces)  | Self-hosted (EKS)| Self-hosted (AKS)| Self-hosted  |
  |  Alertmanager    | Included in AMG  | Included in AMG  | Self-hosted  |
  |                                                                       |
  |  Advantages of Grafana stack across clouds:                          |
  |    1. Same dashboards work on any cloud (PromQL, LogQL, TraceQL)    |
  |    2. Engineers learn one toolset, not three cloud-specific UIs      |
  |    3. Multi-cloud observability in a single pane of glass           |
  |    4. Portable: if you switch clouds, dashboards and alerts migrate  |
  |    5. Open-source: no vendor lock-in on the visualization layer     |
  |                                                                       |
  |  Disadvantages:                                                       |
  |    1. Loki/Tempo are self-managed on K8s (operational overhead)     |
  |    2. Some cloud-native features not available (X-Ray Insights,     |
  |       Smart Detection, GCP SLO monitoring)                          |
  |    3. Log querying in Loki is slower than Elasticsearch for broad   |
  |       full-text searches                                             |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

---

## Cost Comparison (50 Services, ~100K Events/sec)

```
  +-----------------------------------------------------------------------+
  |  MONTHLY COST ESTIMATE: 50 SERVICES, 100K METRICS/SEC, 50K SPANS/SEC|
  |  100K LOGS/SEC                                                        |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Component              | AWS          | Azure        | GCP           |
  |  -----------------------+--------------+--------------+---------------+
  |  Metric storage         | $3,500 (AMP) | $3,500 (AMP) | $1,500 (GMP) |
  |  Trace storage          | $2,500 (XRay)| $2,000 (AI)  | $500 (Trace) |
  |  Log storage            | $1,500 (CW)  | $4,000 (LA)  | $1,500 (CL)  |
  |  Dashboarding           | $500 (AMG)   | $500 (AMG)   | $200 (self)  |
  |  Alerting               | $50 (CW)     | Free         | Free          |
  |  Caching (Redis)        | $300 (EC)    | $300 (ACR)   | $300 (MS)    |
  |  Compute (collectors)   | $800 (ECS)   | $800 (AKS)   | $800 (GKE)   |
  |  -----------------------+--------------+--------------+---------------+
  |  Total managed          | ~$9,150      | ~$11,100     | ~$4,800       |
  |                                                                       |
  |  Self-hosted (Grafana stack on K8s):                                 |
  |  Prometheus+Thanos+Loki+Tempo+Grafana                                |
  |  Infrastructure (K8s nodes, S3/GCS, Redis)                           |
  |  Total:                 | ~$3,500      | ~$3,500      | ~$3,000       |
  |  + 1-2 SRE FTE:        | +$15K-25K/mo | +$15K-25K/mo | +$15K-25K/mo |
  |                                                                       |
  |  vs. Datadog (50 services):                                          |
  |  Infra monitoring + APM + Logs + Synthetics                          |
  |  Total:                 | ~$15,000-25,000/month                      |
  |                                                                       |
  |  Verdict:                                                             |
  |    GCP is cheapest for managed services (Monarch/GMP pricing)       |
  |    Self-hosted is cheapest on paper but requires dedicated SRE       |
  |    Managed services win for teams < 5 engineers (no SRE bandwidth)  |
  |    Self-hosted wins for teams > 10 engineers (SRE cost amortized)   |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

---

## Simulation-to-Cloud Mapping

```
  +-----------------------------------------------------------------------+
  |  SIMULATION CLASS -> CLOUD SERVICE MAPPING                            |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Simulation Class              | AWS             | Azure           | GCP             |
  |  ------------------------------+-----------------+-----------------+-----------------+
  |  MetricService                 | AMP / CW Metrics| AMProm / AzMon  | GMP / Cloud Mon |
  |  TracingService                | X-Ray           | App Insights    | Cloud Trace     |
  |  LogService                    | CW Logs / OS    | Log Analytics   | Cloud Logging   |
  |  AlertService                  | CW Alarms + SNS | AzMon Alerts    | CM Alerts       |
  |  DashboardService              | AMG (Grafana)   | AMG (Grafana)   | Grafana (GKE)   |
  |  ServiceMapService             | X-Ray Svc Map   | AI App Map      | Trace Graph     |
  |  ObservabilityService          | OTel Collector  | OTel Collector  | OTel Collector  |
  |  TimeSeriesStore               | AMP / Timestream| ADX / AMProm    | GMP / Bigtable  |
  |  InMemoryMetricRepository      | AMP storage     | AMProm storage  | GMP (Monarch)   |
  |  InMemoryTraceRepository       | X-Ray storage   | AI storage (LA) | Trace storage   |
  |  InMemoryLogRepository         | CW Logs / S3    | LA workspace    | CL / GCS        |
  |  InMemoryAlertRepository       | DynamoDB (state)| Cosmos DB       | Firestore       |
  |  SamplingEngine                | OTel tail samp  | AI adaptive samp| OTel tail samp  |
  |  MetricAggregator              | CW Math / PromQL| KQL / PromQL    | MQL / PromQL    |
  |  ObservabilityController       | API GW + Grafana| APIM + Grafana  | Endpoints + Graf|
  |  AppConfig                     | Param Store     | App Config      | Secret Manager  |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

---

## Specialized Time-Series Database Services

### Amazon Timestream

```
  +-----------------------------------------------------------------------+
  |  AMAZON TIMESTREAM                                                    |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  What it is:                                                          |
  |    - Serverless time-series database purpose-built for IoT and ops   |
  |    - Automatic tiering: hot (in-memory) -> cold (magnetic/S3)       |
  |    - SQL-compatible query interface                                   |
  |                                                                       |
  |  Architecture:                                                        |
  |    Write path:                                                        |
  |      OTel Collector -> Timestream WriteRecords API                   |
  |      -> In-memory store (hot, last 1-24 hours)                      |
  |      -> Magnetic store (cold, days to years)                         |
  |      -> Auto-transition based on retention policy                    |
  |                                                                       |
  |    Query path:                                                        |
  |      Grafana -> Timestream Query API (SQL)                           |
  |      SELECT bin(time, 1m) AS minute,                                 |
  |             avg(measure_value::double) AS avg_cpu                    |
  |      FROM "observability"."metrics"                                  |
  |      WHERE time > ago(1h)                                            |
  |        AND measure_name = 'cpu_usage'                                |
  |        AND service = 'payment'                                       |
  |      GROUP BY bin(time, 1m)                                          |
  |      ORDER BY minute                                                 |
  |                                                                       |
  |  Strengths:                                                           |
  |    - Serverless (no capacity planning, auto-scales)                  |
  |    - Automatic data tiering (hot/cold with zero config)              |
  |    - Scheduled queries (continuous aggregates equivalent)            |
  |    - Magnetic store: $0.03/GB/month (very cheap for cold data)      |
  |                                                                       |
  |  Limitations:                                                         |
  |    - Not PromQL compatible (SQL only, no direct Prometheus integration)|
  |    - Write throughput: 1M records/sec per table (may need sharding) |
  |    - No JOINs across tables                                          |
  |    - Newer service, smaller community than ClickHouse/TimescaleDB   |
  |                                                                       |
  |  Cost:                                                                |
  |    - Write: $0.50 per 1 million records (1KB each)                  |
  |    - Query: $10.00 per GB scanned                                   |
  |    - Memory store: $0.036 per GB-hour                               |
  |    - Magnetic store: $0.03 per GB/month                             |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### Azure Data Explorer (ADX / Kusto)

```
  +-----------------------------------------------------------------------+
  |  AZURE DATA EXPLORER (ADX)                                            |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  What it is:                                                          |
  |    - Fast, fully managed big data analytics service                  |
  |    - Columnar storage optimized for log and telemetry analytics      |
  |    - KQL (Kusto Query Language): extremely powerful for observability|
  |                                                                       |
  |  Why ADX for observability:                                           |
  |    - Designed for high-volume telemetry ingestion (millions of rows/s)|
  |    - Sub-second queries on billions of rows                          |
  |    - Built-in anomaly detection and time-series functions            |
  |    - Native Grafana plugin                                           |
  |                                                                       |
  |  KQL query examples:                                                  |
  |    // Percentile latency with anomaly detection                      |
  |    metrics                                                           |
  |    | where timestamp > ago(1h)                                       |
  |    | where metric_name == "http_request_duration"                    |
  |    | where tags.service == "payment"                                 |
  |    | summarize p99=percentile(value, 99) by bin(timestamp, 1m)      |
  |    | extend anomaly = series_decompose_anomalies(p99)               |
  |                                                                       |
  |    // Service map from traces                                        |
  |    traces                                                            |
  |    | where timestamp > ago(15m)                                      |
  |    | extend source = tostring(tags.source_service)                   |
  |    | extend dest = tostring(tags.dest_service)                       |
  |    | summarize calls=count(), errors=countif(status == "ERROR"),     |
  |              avg_duration=avg(duration_ms) by source, dest           |
  |                                                                       |
  |  Strengths:                                                           |
  |    - KQL is extremely powerful (auto-complete, intellisense in portal)|
  |    - Built-in ML functions (anomaly detection, forecasting)          |
  |    - Streaming ingestion (sub-second data availability)              |
  |    - Native integration with Azure Monitor and App Insights          |
  |                                                                       |
  |  Cost:                                                                |
  |    - Cluster-based pricing (Dev/Standard/Premium tiers)             |
  |    - Dev: ~$200/month (2 cores, 8GB RAM, 100GB disk)               |
  |    - Standard: ~$2,000/month (8 cores, 64GB RAM, 4TB SSD)          |
  |    - Auto-scale available (scale based on CPU/ingestion rate)       |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### Google Cloud Bigtable (for Time-Series)

```
  +-----------------------------------------------------------------------+
  |  GOOGLE CLOUD BIGTABLE FOR TIME-SERIES                                |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  What it is:                                                          |
  |    - Google's NoSQL wide-column database (HBase-compatible API)      |
  |    - Same technology that powers Google's internal monitoring         |
  |    - Designed for massive scale: petabytes, millions of ops/sec      |
  |                                                                       |
  |  Row key design for time-series:                                      |
  |    Row key: {metric_name}#{service}#{reverse_timestamp}              |
  |    Example: cpu_usage#payment#9999999999-1715270000                  |
  |    (Reverse timestamp ensures recent data is co-located)             |
  |                                                                       |
  |    Column family: "d" (data)                                          |
  |    Columns: "value", "min", "max", "count"                          |
  |                                                                       |
  |  Strengths:                                                           |
  |    - Horizontal scaling (add nodes for more throughput)              |
  |    - Low latency at any scale (single-digit ms reads/writes)        |
  |    - Row-level atomicity (sufficient for metric writes)              |
  |    - Native integration with GMP (Google Managed Prometheus)        |
  |    - Monarch (Google's internal TSDB) is built on Bigtable           |
  |                                                                       |
  |  Limitations:                                                         |
  |    - No SQL (scan/filter API, not query language)                    |
  |    - Row key design is critical (bad design = hot spots)             |
  |    - No built-in aggregation (must aggregate in application code)   |
  |    - Minimum 3 nodes per cluster (~$1,400/month)                    |
  |                                                                       |
  |  When to use:                                                         |
  |    - Very high throughput (>1M writes/sec)                          |
  |    - Google Cloud native workloads                                   |
  |    - Already using GMP (which uses Bigtable internally)             |
  |    - For most teams: GMP abstracts away Bigtable complexity         |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

---

## Observability-Specific Cloud Features

### SLO Monitoring

```
  +-----------------------------------------------------------------------+
  |  SERVICE LEVEL OBJECTIVE (SLO) MONITORING                             |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  GCP (BEST-IN-CLASS):                                                 |
  |    Cloud Monitoring has BUILT-IN SLO monitoring:                     |
  |    - Define SLI: availability (% successful requests)                |
  |    - Set SLO target: 99.9% over 30-day rolling window               |
  |    - Error budget: 0.1% = 43.2 minutes of downtime allowed          |
  |    - Burn-rate alerts: alert when consuming error budget too fast    |
  |    - Dashboard: remaining error budget, burn rate trend              |
  |                                                                       |
  |    gcloud monitoring slo create \                                     |
  |      --service=payment-service \                                      |
  |      --display-name="Payment Availability SLO" \                     |
  |      --sli-type=request-based \                                      |
  |      --good-filter='resource.type="k8s_container" AND                |
  |        metric.type="prometheus.googleapis.com/http_requests_total/counter" AND |
  |        metric.labels.status!~"5.."' \                                |
  |      --total-filter='resource.type="k8s_container" AND               |
  |        metric.type="prometheus.googleapis.com/http_requests_total/counter"' \  |
  |      --goal=0.999 \                                                   |
  |      --rolling-period=30d                                             |
  |                                                                       |
  |  AWS:                                                                 |
  |    No native SLO monitoring. Build manually:                         |
  |    - CloudWatch Math expression for SLI calculation                  |
  |    - CloudWatch Dashboard for error budget visualization             |
  |    - Or use Amazon Managed Grafana with SLO plugin                   |
  |                                                                       |
  |  Azure:                                                               |
  |    No native SLO monitoring. Build manually:                         |
  |    - Application Insights availability tests                         |
  |    - Azure Monitor workbooks for SLI calculation                     |
  |    - Or use Azure Managed Grafana with SLO plugin                    |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### Auto-Instrumentation Support

```
  +-----------------------------------------------------------------------+
  |  AUTO-INSTRUMENTATION ACROSS CLOUDS                                   |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  AWS (EKS / ECS):                                                     |
  |    - OTel Java agent: add as init container / sidecar               |
  |    - AWS Distro for OTel (ADOT): AWS-optimized OTel distribution    |
  |    - X-Ray SDK: AWS-specific, auto-instruments AWS SDK calls        |
  |    - CloudWatch Agent: collects system-level metrics                 |
  |                                                                       |
  |  Azure (AKS / Container Apps):                                       |
  |    - Application Insights Java agent: single line to enable         |
  |      JAVA_TOOL_OPTIONS="-javaagent:applicationinsights-agent.jar"   |
  |    - Auto-collects: HTTP, SQL, Redis, Kafka, gRPC spans             |
  |    - Auto-collects: JVM metrics (heap, GC, threads)                 |
  |    - Auto-collects: dependency calls and failure rates               |
  |    - Codeless attach for AKS (no container image changes needed)    |
  |                                                                       |
  |  GCP (GKE / Cloud Run):                                              |
  |    - OTel Java agent: standard OTel auto-instrumentation            |
  |    - Cloud Trace auto-instrumentation (Spring Cloud GCP)            |
  |    - GKE Managed Collection: auto-scrapes Prometheus metrics        |
  |    - Cloud Run: built-in trace header propagation                   |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

### Infrastructure as Code for Observability

```
  +-----------------------------------------------------------------------+
  |  TERRAFORM MODULES FOR OBSERVABILITY STACK                            |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  AWS:                                                                 |
  |    module "observability" {                                           |
  |      source = "./modules/observability"                              |
  |                                                                       |
  |      # Amazon Managed Prometheus                                      |
  |      amp_workspace_alias  = "production"                             |
  |      amp_retention_days   = 150                                      |
  |                                                                       |
  |      # Amazon Managed Grafana                                         |
  |      amg_workspace_name   = "prod-dashboards"                        |
  |      amg_auth_providers   = ["AWS_SSO"]                              |
  |      amg_data_sources     = ["PROMETHEUS", "CLOUDWATCH", "XRAY"]    |
  |                                                                       |
  |      # CloudWatch Logs                                                |
  |      log_retention_days   = 30                                       |
  |      log_group_prefix     = "/app/production"                        |
  |                                                                       |
  |      # ElastiCache Redis (query cache)                               |
  |      redis_node_type      = "cache.r6g.large"                       |
  |      redis_num_shards     = 3                                        |
  |      redis_replicas       = 2                                        |
  |                                                                       |
  |      # SNS for alerting                                               |
  |      alert_sns_topic      = "observability-alerts"                   |
  |      pagerduty_endpoint   = var.pagerduty_integration_url           |
  |      slack_webhook        = var.slack_alerts_webhook                 |
  |    }                                                                  |
  |                                                                       |
  |  Azure:                                                               |
  |    module "observability" {                                           |
  |      source = "./modules/observability"                              |
  |                                                                       |
  |      # Azure Managed Prometheus                                       |
  |      prometheus_workspace = "prod-metrics"                           |
  |                                                                       |
  |      # Azure Managed Grafana                                          |
  |      grafana_name         = "prod-dashboards"                        |
  |      grafana_sku          = "Standard"                               |
  |      grafana_aad_admin    = var.grafana_admin_group_id              |
  |                                                                       |
  |      # Log Analytics                                                  |
  |      log_workspace_name   = "prod-logs"                              |
  |      log_retention_days   = 90                                       |
  |                                                                       |
  |      # Application Insights                                           |
  |      app_insights_name    = "prod-apm"                               |
  |      sampling_percentage  = 10                                       |
  |                                                                       |
  |      # Azure Cache for Redis                                          |
  |      redis_sku            = "Premium"                                |
  |      redis_capacity       = 1  # P1 (6 GB)                         |
  |    }                                                                  |
  |                                                                       |
  |  GCP:                                                                 |
  |    module "observability" {                                           |
  |      source = "./modules/observability"                              |
  |                                                                       |
  |      # Google Managed Prometheus (via GKE Managed Collection)        |
  |      gke_cluster          = google_container_cluster.prod.name       |
  |      enable_managed_prometheus = true                                |
  |                                                                       |
  |      # Cloud Monitoring                                               |
  |      notification_channels = [                                       |
  |        { type = "pagerduty", labels = { service_key = var.pd_key }} |
  |        { type = "slack",     labels = { channel = "#alerts" }}      |
  |      ]                                                               |
  |                                                                       |
  |      # Cloud Logging                                                  |
  |      log_bucket_name      = "prod-logs"                              |
  |      log_retention_days   = 30                                       |
  |      log_bigquery_sink    = true  # archive to BigQuery              |
  |                                                                       |
  |      # Memorystore Redis                                              |
  |      redis_tier           = "STANDARD_HA"                            |
  |      redis_memory_gb      = 10                                       |
  |    }                                                                  |
  |                                                                       |
  +-----------------------------------------------------------------------+
```

---

## Migration Paths Between Clouds

```
  +-----------------------------------------------------------------------+
  |  PORTABLE OBSERVABILITY: MINIMIZE CLOUD LOCK-IN                       |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Portable components (move freely between clouds):                   |
  |    - OpenTelemetry SDK + Collector (same config, any cloud)          |
  |    - Prometheus (PromQL works on AMP, AMProm, GMP)                   |
  |    - Grafana dashboards (JSON model, same on all managed Grafana)   |
  |    - Alertmanager rules (Grafana Alerting, portable)                 |
  |    - Kafka (MSK, Event Hubs, Confluent on GCP)                      |
  |                                                                       |
  |  Non-portable (cloud-specific, must rewrite):                        |
  |    - CloudWatch Logs Insights queries -> KQL -> Cloud Logging syntax |
  |    - X-Ray traces -> App Insights -> Cloud Trace (different APIs)   |
  |    - CloudWatch Alarms -> Azure Monitor Alerts -> CM Alerts          |
  |    - Timestream SQL -> ADX KQL -> Bigtable scan API                 |
  |                                                                       |
  |  Migration strategy:                                                  |
  |    1. Use OTel Collector as the universal ingestion layer            |
  |       (change exporters, not instrumentation)                        |
  |    2. Use Grafana for dashboards (portable JSON model)               |
  |    3. Use PromQL for metrics (standard across all managed Prometheus)|
  |    4. Accept that tracing and log query languages differ per cloud  |
  |    5. For multi-cloud: deploy OTel Collector in each cloud,          |
  |       forward all telemetry to a single Grafana Cloud instance      |
  |                                                                       |
  +-----------------------------------------------------------------------+
```
