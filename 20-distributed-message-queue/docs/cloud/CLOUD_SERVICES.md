# Cloud Services: Distributed Message Queue (Project 20)

> AWS (MSK, SQS, SNS, Kinesis, EventBridge), Azure (Event Hubs, Service Bus),
> GCP (Pub/Sub, Dataflow), Confluent Cloud, and comparison tables.

---

## Table of Contents

1. [Overview](#1-overview)
2. [AWS: Amazon MSK](#2-aws-amazon-msk)
3. [AWS: Amazon SQS](#3-aws-amazon-sqs)
4. [AWS: Amazon SNS](#4-aws-amazon-sns)
5. [AWS: Amazon Kinesis](#5-aws-amazon-kinesis)
6. [AWS: Amazon EventBridge](#6-aws-amazon-eventbridge)
7. [AWS Messaging Architecture Patterns](#7-aws-messaging-architecture-patterns)
8. [Azure: Event Hubs](#8-azure-event-hubs)
9. [Azure: Service Bus](#9-azure-service-bus)
10. [Azure Messaging Comparison](#10-azure-messaging-comparison)
11. [GCP: Pub/Sub](#11-gcp-pubsub)
12. [GCP: Dataflow](#12-gcp-dataflow)
13. [Confluent Cloud](#13-confluent-cloud)
14. [Cross-Cloud Comparison Tables](#14-cross-cloud-comparison-tables)
15. [Migration Strategies](#15-migration-strategies)
16. [Cost Analysis](#16-cost-analysis)
17. [Simulation-to-Cloud Mapping](#17-simulation-to-cloud-mapping)
18. [Interview Quick Reference](#18-interview-quick-reference)

---

## 1. Overview

### The Cloud Messaging Landscape

```
  ┌───────────────────────────────────────────────────────────────┐
  │                  Cloud Messaging Services                     │
  │                                                               │
  │  AWS                    Azure                 GCP              │
  │  ┌─────────────────┐   ┌────────────────┐   ┌──────────────┐ │
  │  │ MSK (Kafka)     │   │ Event Hubs     │   │ Pub/Sub      │ │
  │  │ SQS (Queue)     │   │ Service Bus    │   │ Dataflow     │ │
  │  │ SNS (Pub/Sub)   │   │ Event Grid     │   │ Cloud Tasks  │ │
  │  │ Kinesis (Stream)│   │ Storage Queues │   │              │ │
  │  │ EventBridge     │   │                │   │              │ │
  │  └─────────────────┘   └────────────────┘   └──────────────┘ │
  │                                                               │
  │  Managed Kafka              Managed RabbitMQ                  │
  │  ┌─────────────────┐       ┌────────────────┐                │
  │  │ Confluent Cloud │       │ CloudAMQP      │                │
  │  │ Redpanda Cloud  │       │ Amazon MQ      │                │
  │  │ Aiven for Kafka │       │                │                │
  │  └─────────────────┘       └────────────────┘                │
  └───────────────────────────────────────────────────────────────┘
```

### Choosing a Cloud Service

```
  Decision criteria:
  1. Do you need Kafka compatibility?
     → Yes: MSK, Confluent Cloud, Azure Event Hubs (Kafka endpoint)
     → No: continue...

  2. Is it a simple queue (task distribution)?
     → Yes: SQS (AWS), Service Bus (Azure), Cloud Tasks (GCP)
     → No: continue...

  3. Is it event streaming with replay?
     → Yes: Kinesis (AWS), Event Hubs (Azure), Pub/Sub (GCP)
     → No: continue...

  4. Is it event routing/integration?
     → Yes: EventBridge (AWS), Event Grid (Azure), Pub/Sub (GCP)
     → No: revisit requirements
```

---

## 2. AWS: Amazon MSK

### What It Is

Amazon Managed Streaming for Apache Kafka (MSK) is a fully managed Kafka service.
It runs open-source Apache Kafka — same APIs, same client libraries, same ecosystem.

### Architecture

```
  ┌──────────────────────────────────────────────────────────────┐
  │                    Amazon MSK Cluster                        │
  │                                                              │
  │  VPC (your account)                                          │
  │  ┌────────────────┐  ┌────────────────┐  ┌────────────────┐ │
  │  │   AZ-a         │  │   AZ-b         │  │   AZ-c         │ │
  │  │ ┌────────────┐ │  │ ┌────────────┐ │  │ ┌────────────┐ │ │
  │  │ │ Broker 1   │ │  │ │ Broker 2   │ │  │ │ Broker 3   │ │ │
  │  │ │ EBS vol    │ │  │ │ EBS vol    │ │  │ │ EBS vol    │ │ │
  │  │ └────────────┘ │  │ └────────────┘ │  │ └────────────┘ │ │
  │  │ ┌────────────┐ │  │ ┌────────────┐ │  │ ┌────────────┐ │ │
  │  │ │ Broker 4   │ │  │ │ Broker 5   │ │  │ │ Broker 6   │ │ │
  │  │ │ EBS vol    │ │  │ │ EBS vol    │ │  │ │ EBS vol    │ │ │
  │  │ └────────────┘ │  │ └────────────┘ │  │ └────────────┘ │ │
  │  └────────────────┘  └────────────────┘  └────────────────┘ │
  │                                                              │
  │  Control Plane (AWS-managed):                                │
  │  ┌──────────────────────────────────────────────────────┐   │
  │  │ ZooKeeper/KRaft (managed) | Monitoring | Patching    │   │
  │  └──────────────────────────────────────────────────────┘   │
  └──────────────────────────────────────────────────────────────┘
```

### MSK Provisioned vs. MSK Serverless

| Feature | MSK Provisioned | MSK Serverless |
|---|---|---|
| **Broker instances** | You choose type and count | AWS auto-scales |
| **Storage** | EBS volumes (provisioned) | Managed (auto-scales) |
| **Pricing** | Per broker-hour + EBS | Per data in/out (CU hours) |
| **Partition limit** | Instance-dependent | 120 partitions per topic |
| **Throughput** | Depends on instance type | Up to 200 MB/s per cluster |
| **Configuration** | Full Kafka config control | Limited config knobs |
| **Kafka Connect** | MSK Connect (separate) | Not available |
| **KRaft** | Supported (3.7+) | Always KRaft |
| **Use case** | Production, high throughput | Dev/test, variable workloads |

### MSK Instance Types

| Instance | vCPU | Memory | Network | Max Partitions | Cost (us-east-1) |
|---|---|---|---|---|---|
| kafka.t3.small | 2 | 2 GB | Low | 300 | ~$0.034/hr |
| kafka.m5.large | 2 | 8 GB | Moderate | 1,000 | ~$0.21/hr |
| kafka.m5.xlarge | 4 | 16 GB | High | 1,500 | ~$0.42/hr |
| kafka.m5.2xlarge | 8 | 32 GB | High | 2,000 | ~$0.84/hr |
| kafka.m5.4xlarge | 16 | 64 GB | High | 4,000 | ~$1.68/hr |
| kafka.m5.12xlarge | 48 | 192 GB | 25 Gbps | 4,000 | ~$5.04/hr |
| kafka.m7g.xlarge | 4 | 16 GB | High | 1,500 | ~$0.38/hr |

### MSK Security

```
  ┌──────────────────────────────────────────────────────────────┐
  │                    MSK Security Layers                       │
  │                                                              │
  │  1. Network isolation:                                       │
  │     - VPC-only access (no public endpoint by default)       │
  │     - Security groups control ingress/egress                │
  │     - VPC endpoints for AWS service integration             │
  │                                                              │
  │  2. Authentication:                                          │
  │     - IAM authentication (recommended)                      │
  │     - SASL/SCRAM (username/password via Secrets Manager)    │
  │     - Mutual TLS (client certificates)                      │
  │     - Unauthenticated (development only)                    │
  │                                                              │
  │  3. Authorization:                                           │
  │     - Kafka ACLs (with SASL/SCRAM or mTLS)                 │
  │     - IAM policies (with IAM auth)                          │
  │                                                              │
  │  4. Encryption:                                              │
  │     - TLS in transit (inter-broker and client-broker)       │
  │     - KMS encryption at rest (EBS volumes)                  │
  │                                                              │
  │  5. Monitoring:                                              │
  │     - CloudWatch metrics (basic, enhanced, topic-level)     │
  │     - Open Monitoring (Prometheus endpoint)                 │
  │     - Broker log delivery to CloudWatch/S3/Firehose        │
  └──────────────────────────────────────────────────────────────┘
```

### MSK Tiered Storage

```
  ┌──────────────────────────────────────────────────────────────┐
  │              MSK Tiered Storage                              │
  │                                                              │
  │  Local (EBS):                        Remote (S3):            │
  │  ┌──────────────────────┐           ┌──────────────────┐    │
  │  │ Recent data          │           │ Historical data  │    │
  │  │ (e.g., last 24 hrs) │  offload  │ (e.g., last 90d) │    │
  │  │ Fast reads           │──────────▶│ Slower reads     │    │
  │  │ EBS pricing          │           │ S3 pricing       │    │
  │  └──────────────────────┘           └──────────────────┘    │
  │                                                              │
  │  Benefits:                                                   │
  │  - Reduce EBS storage costs by 60-80%                       │
  │  - Extend retention to months/years                         │
  │  - Decouple storage growth from broker count                │
  │  - Faster broker recovery (less data to replicate)          │
  │                                                              │
  │  Config:                                                     │
  │    remote.storage.enable = true                              │
  │    local.retention.ms = 86400000  (24 hours local)          │
  │    retention.ms = 7776000000      (90 days total)           │
  └──────────────────────────────────────────────────────────────┘
```

---

## 3. AWS: Amazon SQS

### What It Is

Amazon Simple Queue Service is a fully managed message queuing service for decoupling
microservices. It is the simplest AWS messaging service with zero infrastructure to manage.

### Architecture

```
  ┌──────────────────────────────────────────────────────────────┐
  │                    SQS Queue                                 │
  │                                                              │
  │  Producer ──▶ ┌────────────────────────────────┐ ──▶ Consumer│
  │               │ Message Message Message ...     │            │
  │               │                                │            │
  │               │ Replicated across 3+ AZs       │            │
  │               │ Retention: 1 min to 14 days    │            │
  │               │ Max message size: 256 KB       │            │
  │               └────────────────────────────────┘            │
  │                          │                                   │
  │                          ▼                                   │
  │               ┌────────────────────┐                        │
  │               │ Dead Letter Queue  │                        │
  │               │ (after N failures) │                        │
  │               └────────────────────┘                        │
  └──────────────────────────────────────────────────────────────┘
```

### Standard vs. FIFO Queue

| Feature | Standard | FIFO |
|---|---|---|
| **Throughput** | Unlimited | 300 msg/s (3,000 with batching) |
| **Ordering** | Best-effort | Strict FIFO per message group |
| **Delivery** | At-least-once | Exactly-once (5-min dedup window) |
| **Deduplication** | Not available | Content-based or explicit dedup ID |
| **Naming** | Any name | Must end in `.fifo` |
| **Cost** | $0.40/million requests | $0.50/million requests |
| **High throughput mode** | N/A | 70,000 msg/s (per queue) |

### SQS Key Concepts

```
  Visibility Timeout:
  ┌─────────────────────────────────────────────────────────┐
  │ Message arrives → Consumer receives → INVISIBLE (30s)  │
  │                                                         │
  │ If processed:  Consumer deletes → DONE                  │
  │ If not deleted: timeout expires → VISIBLE again         │
  │                 → another consumer can receive it       │
  └─────────────────────────────────────────────────────────┘

  Long Polling:
  ┌─────────────────────────────────────────────────────────┐
  │ Short poll: returns immediately (even if no messages)   │
  │ Long poll:  waits up to 20 seconds for messages        │
  │ → Reduces empty responses and API costs                │
  └─────────────────────────────────────────────────────────┘

  Dead Letter Queue:
  ┌─────────────────────────────────────────────────────────┐
  │ maxReceiveCount = 3                                     │
  │ After 3 failed processing attempts → move to DLQ       │
  │ DLQ has its own retention period                        │
  │ → Inspect failed messages, fix bugs, redrive            │
  └─────────────────────────────────────────────────────────┘
```

### SQS API Operations

| Operation | Description | Cost |
|---|---|---|
| `SendMessage` | Send a single message | 1 request |
| `SendMessageBatch` | Send up to 10 messages | 1 request |
| `ReceiveMessage` | Receive 1-10 messages | 1 request |
| `DeleteMessage` | Delete after processing | 1 request |
| `DeleteMessageBatch` | Delete up to 10 | 1 request |
| `ChangeMessageVisibility` | Extend processing time | 1 request |
| `PurgeQueue` | Delete all messages | Free |

### SQS Extended Client

```
  For messages > 256 KB:

  Producer ──▶ S3 (payload) + SQS (pointer)
  
  ┌──────────┐     ┌─────┐
  │ Large    │────▶│ S3  │  1. Upload payload to S3
  │ Message  │     └─────┘
  │ (5 MB)   │        │
  └──────────┘        │
                      ▼
               ┌─────────────┐
               │ SQS Message │  2. Send S3 pointer as SQS message
               │ {s3Bucket,  │
               │  s3Key}     │
               └──────┬──────┘
                      │
                      ▼
               ┌──────────┐
               │ Consumer │  3. Receive pointer, fetch from S3
               │          │
               └──────────┘
```

---

## 4. AWS: Amazon SNS

### What It Is

Amazon Simple Notification Service is a fully managed pub/sub messaging service.
It pushes messages to subscribers immediately — no polling required.

### Architecture

```
  ┌──────────────────────────────────────────────────────────────┐
  │                    SNS Topic                                 │
  │                                                              │
  │  Publisher ──▶ Topic ──┬──▶ SQS Queue (buffered delivery)  │
  │                        ├──▶ Lambda Function (compute)       │
  │                        ├──▶ HTTP/S Endpoint (webhook)       │
  │                        ├──▶ Email (notification)            │
  │                        ├──▶ SMS (mobile notification)       │
  │                        ├──▶ Kinesis Firehose (analytics)    │
  │                        └──▶ Platform (iOS/Android push)     │
  │                                                              │
  │  Fan-out: one publish → all subscribers receive              │
  └──────────────────────────────────────────────────────────────┘
```

### SNS Message Filtering

```
  Topic: "orders"

  Subscription 1 (SQS: high-value-orders):
    Filter policy: {"amount": [{"numeric": [">=", 1000]}]}
    → Only receives orders >= $1000

  Subscription 2 (Lambda: us-orders):
    Filter policy: {"region": ["us-east", "us-west"]}
    → Only receives US orders

  Subscription 3 (SQS: all-orders):
    Filter policy: (none)
    → Receives all orders
```

### SNS FIFO Topics

```
  SNS FIFO Topic ──▶ SQS FIFO Queue (the only supported subscriber type)

  Features:
  - Strict ordering per message group ID
  - Exactly-once delivery (content-based dedup)
  - Up to 300 publishes/sec (3,000 with batching)
  - Must pair with SQS FIFO queues
```

### SNS vs. SQS

| Feature | SNS | SQS |
|---|---|---|
| **Model** | Pub/Sub (push) | Queue (pull) |
| **Persistence** | No (delivery attempt only) | Yes (retained in queue) |
| **Consumers** | Multiple subscribers | One consumer per message |
| **Protocols** | HTTP, Email, SMS, SQS, Lambda | HTTP API only |
| **Filtering** | Yes (attribute policies) | No |
| **Ordering** | FIFO topics | FIFO queues |
| **DLQ** | No (redrive on subscription) | Yes |
| **Common pattern** | SNS + SQS fan-out | Direct queue consumption |

---

## 5. AWS: Amazon Kinesis

### What It Is

Amazon Kinesis Data Streams is a real-time data streaming service. It is AWS's
log-based streaming service, similar in concept to Kafka.

### Architecture

```
  ┌──────────────────────────────────────────────────────────────┐
  │                Kinesis Data Stream                           │
  │                                                              │
  │  Stream: "clickstream"                                       │
  │  ┌──────────────────────────────────────────────────┐       │
  │  │ Shard 0: hash range [0, 2^128/4)                 │       │
  │  │   Seq: 0, 1, 2, 3, 4 ...                        │       │
  │  │   Capacity: 1 MB/s write, 2 MB/s read            │       │
  │  ├──────────────────────────────────────────────────┤       │
  │  │ Shard 1: hash range [2^128/4, 2^128/2)          │       │
  │  │   Seq: 0, 1, 2, 3 ...                           │       │
  │  ├──────────────────────────────────────────────────┤       │
  │  │ Shard 2: hash range [2^128/2, 3*2^128/4)        │       │
  │  │   Seq: 0, 1, 2, 3, 4, 5 ...                     │       │
  │  ├──────────────────────────────────────────────────┤       │
  │  │ Shard 3: hash range [3*2^128/4, 2^128)          │       │
  │  │   Seq: 0, 1 ...                                 │       │
  │  └──────────────────────────────────────────────────┘       │
  │                                                              │
  │  Retention: 24h default, up to 365 days                      │
  │  Replication: 3 AZs (automatic)                              │
  └──────────────────────────────────────────────────────────────┘
```

### Kinesis Consumer Types

```
  Shared Throughput (GetRecords API):
  ┌────────────────────────────────────────────────────────┐
  │ All consumers share 2 MB/s per shard                  │
  │                                                        │
  │ Shard ──┬──▶ Consumer A (gets portion of 2 MB/s)      │
  │         ├──▶ Consumer B (shares same 2 MB/s)          │
  │         └──▶ Consumer C (shares same 2 MB/s)          │
  │                                                        │
  │ 5 GetRecords calls/sec limit per shard                │
  │ Cost: $0.015 per shard-hour                           │
  └────────────────────────────────────────────────────────┘

  Enhanced Fan-Out (SubscribeToShard API):
  ┌────────────────────────────────────────────────────────┐
  │ Each consumer gets dedicated 2 MB/s per shard         │
  │                                                        │
  │ Shard ──┬──▶ Consumer A (dedicated 2 MB/s)            │
  │         ├──▶ Consumer B (dedicated 2 MB/s)            │
  │         └──▶ Consumer C (dedicated 2 MB/s)            │
  │                                                        │
  │ Push-based (HTTP/2 server push)                       │
  │ Cost: $0.015/shard-hour + $0.013/consumer-shard-hour  │
  └────────────────────────────────────────────────────────┘
```

### Kinesis vs. MSK (Kafka)

| Dimension | Kinesis | MSK (Kafka) |
|---|---|---|
| **Managed level** | Fully serverless | Semi-managed (choose instances) |
| **Pricing model** | Per shard-hour + PUT payload | Per broker-hour + EBS |
| **Throughput per unit** | 1 MB/s write per shard | Depends on instance type |
| **Retention** | 24h to 365 days | Configurable (unlimited) |
| **Log compaction** | No | Yes |
| **Consumer groups** | KCL (manual) | Native Kafka consumer groups |
| **Ecosystem** | AWS Lambda, Firehose | Full Kafka ecosystem |
| **Scaling** | Shard split/merge | Add partitions (not brokers) |
| **Protocol** | AWS SDK | Kafka protocol |
| **Schema Registry** | AWS Glue Schema Registry | Confluent or AWS Glue |
| **Best for** | AWS-native, moderate scale | High throughput, Kafka ecosystem |

### Kinesis Data Firehose

```
  ┌──────────┐     ┌───────────────┐     ┌──────────────────┐
  │ Kinesis  │────▶│ Firehose      │────▶│ Destination      │
  │ Stream   │     │ (buffering +  │     │ - S3             │
  │          │     │  transform)   │     │ - Redshift       │
  └──────────┘     │               │     │ - Elasticsearch  │
                   │ Buffer:       │     │ - Splunk         │
  ┌──────────┐     │ - Size: 1-128 MB│   │ - HTTP endpoint  │
  │ Direct   │────▶│ - Time: 60-900s│   └──────────────────┘
  │ PUT      │     │               │
  └──────────┘     │ Transform:    │
                   │ - Lambda func │
                   │ - Format conv │
                   └───────────────┘
```

---

## 6. AWS: Amazon EventBridge

### What It Is

Amazon EventBridge is a serverless event bus for building event-driven architectures.
It routes events from AWS services, SaaS applications, and custom applications to
targets based on rules.

### Architecture

```
  ┌──────────────────────────────────────────────────────────────┐
  │                    EventBridge                               │
  │                                                              │
  │  Sources:                    Event Bus:           Targets:   │
  │  ┌──────────────┐          ┌──────────┐                     │
  │  │ AWS Services │──events─▶│          │──rule──▶ Lambda     │
  │  │ (S3, EC2...) │          │  default │──rule──▶ SQS       │
  │  └──────────────┘          │   bus    │──rule──▶ Step Func  │
  │  ┌──────────────┐          │          │──rule──▶ SNS       │
  │  │ SaaS Apps    │──events─▶│          │──rule──▶ Kinesis   │
  │  │ (Zendesk...) │          │          │──rule──▶ API GW    │
  │  └──────────────┘          └──────────┘──rule──▶ ECS Task  │
  │  ┌──────────────┐          ┌──────────┐                     │
  │  │ Custom Apps  │──events─▶│ custom   │                     │
  │  │ (PutEvents)  │          │  bus     │                     │
  │  └──────────────┘          └──────────┘                     │
  └──────────────────────────────────────────────────────────────┘
```

### Event Pattern Matching

```json
{
  "source": ["com.myapp.orders"],
  "detail-type": ["OrderCreated"],
  "detail": {
    "amount": [{"numeric": [">=", 100, "<=", 10000]}],
    "region": ["us-east-1", "us-west-2"],
    "status": [{"anything-but": "CANCELLED"}]
  }
}
```

### EventBridge Pipes

```
  Source ──▶ [Filter] ──▶ [Enrich] ──▶ Target

  Example:
  SQS Queue ──▶ Filter(amount > 100) ──▶ Lambda(enrich) ──▶ Step Functions

  Supported sources:
  - SQS, Kinesis, DynamoDB Streams, Kafka (MSK/self-managed)

  Supported targets:
  - Lambda, Step Functions, SQS, SNS, Kinesis, API Gateway, EventBridge
```

### EventBridge vs. SNS vs. SQS

| Feature | EventBridge | SNS | SQS |
|---|---|---|---|
| **Model** | Event bus + rules | Pub/Sub | Queue |
| **Routing** | Content-based rules | Filter policies | None |
| **Schema** | Schema registry built-in | No | No |
| **Sources** | 200+ AWS services, SaaS | Your code | Your code |
| **Archive/Replay** | Built-in event archiving | No | No |
| **Throughput** | 10K events/sec (soft limit) | ~30M publishes/sec | Unlimited (standard) |
| **Latency** | ~500ms | <100ms | <100ms |
| **Cost** | $1.00/million events | $0.50/million publishes | $0.40/million requests |
| **Use case** | AWS integration, SaaS events | Fan-out, notifications | Task queues |

---

## 7. AWS Messaging Architecture Patterns

### Pattern 1: Fan-Out with Ordered Processing

```
  ┌──────────┐     ┌──────────┐     ┌──────────────┐     ┌──────────┐
  │ Order    │────▶│ SNS FIFO │────▶│ SQS FIFO     │────▶│ Lambda   │
  │ Service  │     │ Topic    │────▶│ Queue (email) │     │ (email)  │
  └──────────┘     └──────────┘────▶│ Queue (inv)   │     └──────────┘
                                    │ Queue (audit) │
                                    └──────────────┘
```

### Pattern 2: Event-Driven Microservices

```
  ┌────────┐   ┌─────────────┐   ┌────────┐   ┌──────────┐
  │ API GW │──▶│ EventBridge │──▶│ Lambda │──▶│ DynamoDB │
  └────────┘   │             │   └────────┘   └──────────┘
               │  Rule: type=│   ┌────────┐   ┌──────────┐
               │  "order"    │──▶│ Lambda │──▶│ SQS DLQ  │
               │             │   └────────┘   └──────────┘
               │  Rule: type=│   ┌─────────────┐
               │  "payment"  │──▶│ Step Func   │
               └─────────────┘   └─────────────┘
```

### Pattern 3: Real-Time Analytics Pipeline

```
  ┌────────┐   ┌─────────┐   ┌──────────┐   ┌──────────┐   ┌────────────┐
  │ Click  │──▶│ Kinesis │──▶│ Lambda   │──▶│ Kinesis  │──▶│ S3         │
  │ Events │   │ Stream  │   │ (enrich) │   │ Firehose │   │ (Parquet)  │
  └────────┘   └─────────┘   └──────────┘   └──────────┘   └─────┬──────┘
                                                                   │
                                                            ┌──────▼──────┐
                                                            │ Athena/     │
                                                            │ Redshift    │
                                                            └─────────────┘
```

### Pattern 4: High-Throughput Event Streaming

```
  ┌────────┐   ┌──────────┐   ┌────────┐   ┌──────────────┐
  │ IoT    │──▶│ MSK      │──▶│ Flink  │──▶│ OpenSearch  │
  │ Devices│   │ (Kafka)  │   │ on     │   │ (real-time  │
  └────────┘   │          │──▶│ Kinesis│   │  dashboard) │
               │          │   └────────┘   └──────────────┘
               │          │──▶ MSK Connect ──▶ S3 (archive)
               └──────────┘
```

---

## 8. Azure: Event Hubs

### What It Is

Azure Event Hubs is a big data streaming platform and event ingestion service.
It is Azure's answer to Kafka and provides a Kafka-compatible endpoint.

### Architecture

```
  ┌──────────────────────────────────────────────────────────────┐
  │                    Azure Event Hubs                          │
  │                                                              │
  │  Namespace: "mynamespace" (container for Event Hubs)        │
  │  ┌──────────────────────────────────────────────────┐       │
  │  │ Event Hub: "orders"                               │       │
  │  │ ┌──────────┐ ┌──────────┐ ┌──────────┐          │       │
  │  │ │Partition 0│ │Partition 1│ │Partition 2│          │       │
  │  │ └──────────┘ └──────────┘ └──────────┘          │       │
  │  └──────────────────────────────────────────────────┘       │
  │  ┌──────────────────────────────────────────────────┐       │
  │  │ Event Hub: "payments"                             │       │
  │  │ ┌──────────┐ ┌──────────┐                        │       │
  │  │ │Partition 0│ │Partition 1│                        │       │
  │  │ └──────────┘ └──────────┘                        │       │
  │  └──────────────────────────────────────────────────┘       │
  │                                                              │
  │  Consumer groups: up to 20 per Event Hub                    │
  │  Retention: 1 to 90 days (standard), unlimited (premium)    │
  └──────────────────────────────────────────────────────────────┘
```

### Event Hubs Tiers

| Feature | Basic | Standard | Premium | Dedicated |
|---|---|---|---|---|
| **Consumer groups** | 1 | 20 | 100 | 1,000 |
| **Partitions** | 32 | 32 | 100 | 1,024 |
| **Retention** | 1 day | 7 days | 90 days | 90 days |
| **Throughput** | 1 TU | 20 TU | N/A (auto) | 20 CU |
| **Kafka endpoint** | No | Yes | Yes | Yes |
| **Capture** | No | Yes | Yes | Yes |
| **Schema Registry** | No | Yes | Yes | Yes |
| **VNet integration** | No | Yes | Yes | Yes |

### Event Hubs Kafka Compatibility

```
  Kafka Client ──▶ Event Hubs Kafka Endpoint

  Connection string:
    bootstrap.servers = mynamespace.servicebus.windows.net:9093
    security.protocol = SASL_SSL
    sasl.mechanism = PLAIN
    sasl.jaas.config = "...ConnectionString..."

  Supported:
    ✅ Kafka Producer API
    ✅ Kafka Consumer API (consumer groups)
    ✅ Kafka AdminClient (create/list topics)
    ✅ Compression (gzip, snappy, lz4)

  Not supported:
    ❌ Kafka Streams
    ❌ Kafka Connect
    ❌ Log compaction
    ❌ Transactions
    ❌ Idempotent producer
```

### Event Hubs Capture

```
  Event Hub ──▶ Capture ──▶ Azure Blob Storage / Azure Data Lake
  
  ┌──────────────────────────────────────────────────────────┐
  │ Capture writes events in Avro format:                    │
  │                                                          │
  │ Blob path template:                                      │
  │ {Namespace}/{EventHub}/{PartitionId}/{Year}/{Month}/{Day}│
  │ /{Hour}/{Minute}/{Second}                                │
  │                                                          │
  │ Example:                                                 │
  │ myns/orders/0/2024/01/15/14/30/00.avro                  │
  │                                                          │
  │ Window: time (1-15 min) or size (10-500 MB)             │
  │ Cost: included in tier (no extra charge for capture)    │
  └──────────────────────────────────────────────────────────┘
```

---

## 9. Azure: Service Bus

### What It Is

Azure Service Bus is an enterprise message broker with queues and pub/sub topics.
It is Azure's equivalent of RabbitMQ/SQS — a traditional queue-based broker.

### Architecture

```
  ┌──────────────────────────────────────────────────────────────┐
  │                    Azure Service Bus                         │
  │                                                              │
  │  Namespace: "myservicebus"                                   │
  │                                                              │
  │  Queues (point-to-point):                                    │
  │  ┌──────────────────────────────────────┐                   │
  │  │ Queue: "orders"                      │                   │
  │  │ → Sender ──▶ [msg][msg][msg] ──▶ Receiver               │
  │  │ → Sessions: per-session FIFO         │                   │
  │  │ → Dead-letter: built-in sub-queue    │                   │
  │  └──────────────────────────────────────┘                   │
  │                                                              │
  │  Topics (pub/sub):                                           │
  │  ┌──────────────────────────────────────┐                   │
  │  │ Topic: "events"                      │                   │
  │  │ → Publisher ──▶ Topic                │                   │
  │  │                   ├──▶ Subscription 1 (with SQL filter)  │
  │  │                   ├──▶ Subscription 2 (with SQL filter)  │
  │  │                   └──▶ Subscription 3 (correlation filter)│
  │  └──────────────────────────────────────┘                   │
  └──────────────────────────────────────────────────────────────┘
```

### Service Bus Features

| Feature | Description |
|---|---|
| **Sessions** | Per-session FIFO ordering (like SQS message groups) |
| **Transactions** | Atomic multi-operation transactions |
| **Dead-lettering** | Built-in DLQ per queue/subscription |
| **Scheduled delivery** | Send messages with future delivery time |
| **Message deferral** | Defer processing, retrieve by sequence number |
| **Auto-forwarding** | Chain queues/topics for routing |
| **Duplicate detection** | Time-windowed deduplication |
| **TTL** | Per-message and per-queue TTL |
| **Max message size** | 256 KB (standard), 100 MB (premium) |

### Service Bus vs. SQS

| Feature | Azure Service Bus | AWS SQS |
|---|---|---|
| **Protocol** | AMQP 1.0, HTTP | HTTP (AWS SDK) |
| **Topics** | Built-in (pub/sub) | No (use SNS) |
| **Sessions** | Native | Message group ID (FIFO only) |
| **Transactions** | Yes | No |
| **Scheduled delivery** | Yes | Delay up to 15 min |
| **Message size** | 256 KB (100 MB premium) | 256 KB (2 GB with S3) |
| **Dead letter** | Built-in sub-queue | Separate DLQ |
| **Duplicate detection** | Yes (time window) | FIFO only |
| **Ordering** | Sessions (any tier) | FIFO queues only |

---

## 10. Azure Messaging Comparison

### When to Use What on Azure

```
  ┌──────────────────────────────────────────────────────────┐
  │              Azure Messaging Decision Tree                │
  │                                                          │
  │  Need event streaming (log-based)?                       │
  │  → Event Hubs                                            │
  │                                                          │
  │  Need Kafka compatibility?                               │
  │  → Event Hubs (Kafka endpoint)                           │
  │                                                          │
  │  Need enterprise messaging (queues, topics, sessions)?   │
  │  → Service Bus                                           │
  │                                                          │
  │  Need reactive event-driven (Azure service events)?      │
  │  → Event Grid                                            │
  │                                                          │
  │  Need simple, cheap queue for background jobs?           │
  │  → Storage Queues                                        │
  └──────────────────────────────────────────────────────────┘
```

| Feature | Event Hubs | Service Bus | Event Grid | Storage Queues |
|---|---|---|---|---|
| **Model** | Event stream | Queue + Topic | Event routing | Queue |
| **Ordering** | Per-partition | Per-session | None | None |
| **Retention** | 1-90 days | 14 days | 24h retry | 7 days |
| **Throughput** | Millions/sec | Thousands/sec | Millions/sec | Thousands/sec |
| **Max message** | 1 MB | 256KB/100MB | 1 MB | 64 KB |
| **Cost** | $$$  | $$ | $ | $ |
| **Replay** | Yes (offset) | No | No | No |
| **Use case** | Streaming, analytics | Enterprise integration | Event notifications | Simple tasks |

---

## 11. GCP: Pub/Sub

### What It Is

Google Cloud Pub/Sub is a fully managed, serverless messaging service. It automatically
scales and requires zero capacity planning.

### Architecture

```
  ┌──────────────────────────────────────────────────────────────┐
  │                    Google Cloud Pub/Sub                      │
  │                                                              │
  │  ┌────────┐     ┌──────────┐     ┌──────────────────────┐  │
  │  │Publisher│────▶│  Topic   │────▶│ Subscription 1 (pull)│  │
  │  └────────┘     │          │     │  → Subscriber app    │  │
  │                 │          │     └──────────────────────┘  │
  │  ┌────────┐     │          │     ┌──────────────────────┐  │
  │  │Publisher│────▶│          │────▶│ Subscription 2 (push)│  │
  │  └────────┘     │          │     │  → HTTP endpoint     │  │
  │                 │          │     └──────────────────────┘  │
  │                 │          │     ┌──────────────────────┐  │
  │                 │          │────▶│ Subscription 3       │  │
  │                 │          │     │  → BigQuery export   │  │
  │                 │          │     └──────────────────────┘  │
  │                 └──────────┘                               │
  │                                                              │
  │  Key properties:                                             │
  │  - At-least-once delivery (default)                         │
  │  - Exactly-once delivery (optional, with ordering)          │
  │  - Message retention: 10 min to 31 days                     │
  │  - Max message size: 10 MB                                  │
  │  - Ordering: per ordering key                               │
  │  - Seek: replay from timestamp or snapshot                  │
  └──────────────────────────────────────────────────────────────┘
```

### Pub/Sub Subscription Types

| Type | Description | Use Case |
|---|---|---|
| **Pull** | Subscriber polls for messages | Backend services, batch processing |
| **Push** | Pub/Sub calls HTTP endpoint | Webhooks, Cloud Run, App Engine |
| **BigQuery** | Direct write to BigQuery | Analytics, data warehousing |
| **Cloud Storage** | Direct write to GCS | Archival, data lake |

### Pub/Sub vs. Kafka

| Feature | GCP Pub/Sub | Apache Kafka |
|---|---|---|
| **Managed** | Fully serverless | Self-managed or MSK/Confluent |
| **Scaling** | Automatic | Manual (add partitions/brokers) |
| **Ordering** | Per ordering key | Per partition |
| **Retention** | 10 min to 31 days | Configurable (unlimited) |
| **Replay** | Seek to timestamp/snapshot | Seek to offset |
| **Compaction** | No | Yes |
| **Consumer model** | Pull or push | Pull only (consumer groups) |
| **Throughput** | Auto-scales | Depends on cluster size |
| **Exactly-once** | Yes (with ordering) | Yes (transactions) |
| **Message size** | 10 MB | 1 MB (configurable) |
| **Cost model** | Per message + data volume | Per infrastructure |

### Pub/Sub Lite (Cost-Optimized)

```
  ┌──────────────────────────────────────────────────────────┐
  │              Pub/Sub Lite vs. Pub/Sub                     │
  │                                                          │
  │  Pub/Sub:                                                │
  │  - Fully serverless, auto-scaling                        │
  │  - Higher per-message cost                               │
  │  - No capacity planning                                  │
  │  - Best for: variable workloads, ease of use             │
  │                                                          │
  │  Pub/Sub Lite:                                           │
  │  - Zonal (not global)                                    │
  │  - Pre-provisioned capacity (throughput + storage)       │
  │  - 5-7x cheaper than Pub/Sub                            │
  │  - Best for: high volume, predictable workloads         │
  └──────────────────────────────────────────────────────────┘
```

---

## 12. GCP: Dataflow

### What It Is

Google Cloud Dataflow is a fully managed stream and batch processing service
based on Apache Beam. It is GCP's equivalent of Kafka Streams / Flink.

### Architecture

```
  ┌──────────────────────────────────────────────────────────────┐
  │                    Dataflow Pipeline                         │
  │                                                              │
  │  Source ──▶ Transform ──▶ Transform ──▶ Sink                │
  │                                                              │
  │  Sources:               Transforms:        Sinks:            │
  │  - Pub/Sub              - Map               - BigQuery       │
  │  - Kafka                - Filter            - Cloud Storage  │
  │  - BigQuery             - GroupByKey        - Pub/Sub        │
  │  - Cloud Storage        - Window            - Bigtable       │
  │  - Custom               - Combine           - Custom         │
  │                         - Join                               │
  │                                                              │
  │  Auto-scaling:                                               │
  │  - Workers scale 1 to N based on backlog                    │
  │  - No manual capacity planning                              │
  │  - Pay per worker-minute                                    │
  └──────────────────────────────────────────────────────────────┘
```

### Dataflow vs. Kafka Streams vs. Flink

| Feature | Dataflow | Kafka Streams | Flink |
|---|---|---|---|
| **Deployment** | Fully managed (GCP) | Library (any JVM) | Cluster or managed |
| **Sources** | Many (Beam I/O) | Kafka only | Many |
| **Batch + Stream** | Unified (Beam model) | Stream only | Unified |
| **Auto-scaling** | Yes | No (manual instances) | Limited |
| **State management** | Managed | RocksDB (local) | RocksDB (managed) |
| **Exactly-once** | Yes | Yes (EOS) | Yes (checkpoints) |
| **Windowing** | Full (Beam) | Full | Full |
| **Language** | Java, Python, Go | Java, Scala | Java, Scala, Python |
| **Cost** | Per worker-minute | Your infrastructure | Your infrastructure |

---

## 13. Confluent Cloud

### What It Is

Confluent Cloud is a fully managed Kafka service by the creators of Kafka.
It offers the richest Kafka ecosystem of any managed provider.

### Architecture

```
  ┌──────────────────────────────────────────────────────────────┐
  │                    Confluent Cloud                           │
  │                                                              │
  │  ┌──────────────────────────────────────────────────────┐   │
  │  │ Kafka Cluster (multi-tenant or dedicated)             │   │
  │  │ - Basic ($): shared multi-tenant                     │   │
  │  │ - Standard ($$): single-tenant, VPC peering          │   │
  │  │ - Dedicated ($$$): dedicated hardware, private link  │   │
  │  │ - Enterprise ($$$$): SLAs, BYOK, audit logs         │   │
  │  └──────────────────────────────────────────────────────┘   │
  │                                                              │
  │  ┌──────────────────────────────────────────────────────┐   │
  │  │ Managed Components:                                   │   │
  │  │ - Schema Registry (Avro, Protobuf, JSON Schema)      │   │
  │  │ - Kafka Connect (200+ connectors)                    │   │
  │  │ - ksqlDB (SQL-based stream processing)               │   │
  │  │ - Cluster Linking (multi-cluster replication)        │   │
  │  │ - Stream Governance (lineage, quality, catalog)      │   │
  │  └──────────────────────────────────────────────────────┘   │
  │                                                              │
  │  Cloud providers: AWS, Azure, GCP                            │
  │  99.99% SLA (dedicated clusters)                             │
  └──────────────────────────────────────────────────────────────┘
```

### Confluent Cloud vs. MSK

| Feature | Confluent Cloud | Amazon MSK |
|---|---|---|
| **Kafka version** | Latest (Confluent patches) | AWS-managed versions |
| **Schema Registry** | Fully managed, built-in | AWS Glue (separate) |
| **Kafka Connect** | Fully managed (200+ connectors) | MSK Connect (separate) |
| **ksqlDB** | Fully managed | Not available |
| **Cluster Linking** | Yes (multi-region) | No (use MirrorMaker) |
| **Stream Governance** | Yes (lineage, catalog) | No |
| **Tiered storage** | Infinite Storage | MSK Tiered Storage |
| **Multi-cloud** | AWS, Azure, GCP | AWS only |
| **Pricing** | Per CKU or per throughput | Per broker-hour |
| **RBAC** | Fine-grained | Kafka ACLs + IAM |

### Confluent Cluster Linking

```
  Region 1 (us-east-1)              Region 2 (eu-west-1)
  ┌──────────────────┐              ┌──────────────────┐
  │ Source Cluster   │  Cluster     │ Destination      │
  │                  │  Linking     │ Cluster          │
  │ topic: orders   │──────────────▶│ mirror: orders   │
  │ (writable)      │  (async)     │ (read-only)      │
  │                  │              │                  │
  │ No MirrorMaker!  │              │ Byte-for-byte   │
  │ Native feature   │              │ replica          │
  └──────────────────┘              └──────────────────┘

  Benefits vs. MirrorMaker:
  - Same topic name (no prefix)
  - Consumer offsets preserved
  - No separate connector cluster needed
  - Lower latency (built into broker)
```

---

## 14. Cross-Cloud Comparison Tables

### Managed Kafka Services

| Feature | AWS MSK | Azure Event Hubs | Confluent Cloud | Redpanda Cloud |
|---|---|---|---|---|
| **Kafka protocol** | Full | Partial (no TX, no compact) | Full | Full (Redpanda) |
| **Schema Registry** | Glue (separate) | Built-in | Built-in | Built-in |
| **Connect** | MSK Connect | No | Managed | No |
| **Stream processing** | Flink on KDA | Stream Analytics | ksqlDB | No |
| **Tiered storage** | Yes | Capture (to Blob) | Infinite Storage | Tiered |
| **Multi-region** | No (MirrorMaker) | Geo-DR | Cluster Linking | No |
| **Serverless** | MSK Serverless | Standard tier | Basic tier | Yes |
| **Min cost** | ~$200/mo | ~$11/mo (basic) | ~$200/mo (basic) | ~$0 (free tier) |

### Queue Services

| Feature | AWS SQS | Azure Service Bus | GCP Pub/Sub |
|---|---|---|---|
| **Max message size** | 256 KB | 256 KB (100 MB prem) | 10 MB |
| **Ordering** | FIFO queues | Sessions | Ordering keys |
| **Exactly-once** | FIFO dedup | Duplicate detection | With ordering |
| **DLQ** | Yes | Yes (sub-queue) | Yes |
| **Transactions** | No | Yes | No |
| **Scheduled delivery** | Delay (15 min max) | Yes (scheduled enqueue) | No |
| **Protocol** | HTTP (SDK) | AMQP 1.0, HTTP | HTTP (gRPC, REST) |
| **Throughput** | Unlimited (standard) | ~2K msg/s (standard) | Auto-scales |
| **Cost** | $0.40/M requests | $0.05/M operations | $0.04/M + data |
| **Replay** | No | No | Seek to timestamp |

### Event Streaming Services

| Feature | Kinesis | Event Hubs | Pub/Sub | MSK |
|---|---|---|---|---|
| **Model** | Shard-based | Partition-based | Subscription-based | Partition-based |
| **Throughput unit** | 1 MB/s per shard | 1 MB/s per TU | Auto-scales | Per broker |
| **Retention** | 24h to 365d | 1-90d | 10min to 31d | Configurable |
| **Replay** | Yes (offset) | Yes (offset) | Yes (timestamp) | Yes (offset) |
| **Consumer model** | KCL / Lambda | Consumer groups | Pull / Push | Consumer groups |
| **Compaction** | No | No | No | Yes |
| **Exactly-once** | No | No | Yes | Yes |
| **Kafka compat** | No | Yes (partial) | No | Yes (full) |

---

## 15. Migration Strategies

### Self-Managed Kafka to MSK

```
  Phase 1: Setup
  ┌──────────────┐     ┌──────────────┐
  │ Self-Managed │     │ MSK Cluster  │
  │ Kafka        │     │ (same version│
  │              │     │  or newer)   │
  └──────────────┘     └──────────────┘

  Phase 2: MirrorMaker 2 replication
  ┌──────────────┐  MM2  ┌──────────────┐
  │ Self-Managed │──────▶│ MSK Cluster  │
  │ (source)     │       │ (destination)│
  └──────────────┘       └──────────────┘
  - Topic replication
  - Consumer offset sync
  - Config sync

  Phase 3: Consumer switchover
  ┌──────────────┐  MM2  ┌──────────────┐
  │ Self-Managed │──────▶│ MSK Cluster  │◀── consumers (switched)
  │ (source)     │       │              │
  └──────────────┘       └──────────────┘

  Phase 4: Producer switchover
                         ┌──────────────┐
  producers (switched)──▶│ MSK Cluster  │◀── consumers
                         │              │
                         └──────────────┘
  
  Phase 5: Decommission self-managed cluster
```

### SQS to Kafka Migration

```
  Step 1: Dual-write pattern
  ┌──────────┐───▶ SQS  (existing consumers unchanged)
  │ Producer │
  └──────────┘───▶ Kafka (new consumers being built)

  Step 2: Consumer migration
  Each consumer team switches from SQS to Kafka consumer group

  Step 3: Producer cutover
  ┌──────────┐───▶ Kafka only
  │ Producer │
  └──────────┘

  Challenges:
  - SQS is push-based, Kafka is pull-based (consumer rewrite)
  - SQS has visibility timeout, Kafka has offset commit
  - SQS deletes after ack, Kafka retains for retention period
  - SQS has DLQ built-in, Kafka needs manual DLQ topic
```

### Multi-Cloud Strategy

```
  ┌──────────────────────────────────────────────────────────┐
  │              Multi-Cloud Messaging Options                │
  │                                                          │
  │  Option 1: Confluent Cloud (runs on any cloud)           │
  │  + Same API on AWS/Azure/GCP                             │
  │  + Cluster Linking for cross-cloud replication           │
  │  - Vendor lock-in to Confluent                           │
  │  - Higher cost                                           │
  │                                                          │
  │  Option 2: Self-managed Kafka on each cloud              │
  │  + Full control, no managed-service lock-in              │
  │  + MirrorMaker 2 for cross-cloud replication             │
  │  - Significant operational overhead                      │
  │  - Networking complexity (VPN/peering)                   │
  │                                                          │
  │  Option 3: Cloud-native services with bridge             │
  │  + Use each cloud's native service (SQS, Service Bus)    │
  │  + Bridge via API Gateway or Lambda                      │
  │  - Different APIs per cloud                              │
  │  - No unified consumer group model                       │
  └──────────────────────────────────────────────────────────┘
```

---

## 16. Cost Analysis

### Monthly Cost Comparison (100 GB/day throughput)

```
  ┌──────────────────────────────────────────────────────────┐
  │         Monthly Cost: 100 GB/day, 30-day retention       │
  │                                                          │
  │  Self-managed Kafka (3 brokers, m5.xlarge):              │
  │    EC2: 3 * $277 = $831                                  │
  │    EBS: 3 * 500 GB * $0.10 = $150                       │
  │    Ops engineer time: $2,000-5,000 (estimated)          │
  │    Total: ~$3,000-6,000/month                           │
  │                                                          │
  │  AWS MSK Provisioned (3 brokers, kafka.m5.large):        │
  │    Broker: 3 * $0.21 * 730 = $460                       │
  │    EBS: 3 * 500 GB * $0.10 = $150                       │
  │    Data transfer: negligible (same VPC)                  │
  │    Total: ~$610/month                                    │
  │                                                          │
  │  AWS MSK Serverless:                                     │
  │    Cluster: $0.75/hr * 730 = $548                       │
  │    Partitions: ~$30                                      │
  │    Storage: 3 TB * $0.10 = $300                         │
  │    Total: ~$878/month                                    │
  │                                                          │
  │  AWS Kinesis:                                            │
  │    Shards: 12 * $0.015/hr * 730 = $131                  │
  │    PUT: 100M * $0.014/M = $1,400                        │
  │    Extended retention: 12 * $0.023/hr * 730 = $201      │
  │    Total: ~$1,732/month                                  │
  │                                                          │
  │  Confluent Cloud Basic (100 MB/s):                       │
  │    Compute: ~$500-1,500                                  │
  │    Storage: ~$100-300                                     │
  │    Total: ~$600-1,800/month (varies by usage)           │
  │                                                          │
  │  AWS SQS (100 GB/day, 1 KB messages = 100M messages):   │
  │    Requests: 200M * $0.40/M = $80                       │
  │    Total: ~$80/month (cheapest!)                         │
  │                                                          │
  │  GCP Pub/Sub (100 GB/day):                               │
  │    Message delivery: 100M * $0.04/M = $4                │
  │    Data volume: 3 TB * $0.04/GB = $122                  │
  │    Total: ~$126/month                                    │
  └──────────────────────────────────────────────────────────┘
```

### Cost Optimization Tips

```
  1. Use MSK Serverless for dev/test, Provisioned for production
  2. Enable MSK Tiered Storage to reduce EBS costs
  3. Use compression (lz4 or zstd) to reduce storage and network
  4. Right-size partitions (over-partitioning increases overhead)
  5. Set retention.ms to minimum required (less storage)
  6. Use Graviton instances (kafka.m7g.*) for 15-20% savings
  7. Reserved capacity for predictable workloads
  8. Batch API calls (SQS, Kinesis) to reduce request costs
```

---

## 17. Simulation-to-Cloud Mapping

### How Our Simulation Classes Map to Cloud Services

| Simulation Class | Cloud Equivalent |
|---|---|
| `CommitLog.java` | MSK partition log / Kinesis shard / Event Hubs partition |
| `Partition.java` | MSK partition / Kinesis shard / Pub/Sub ordering key |
| `ReplicationEngine.java` | Managed by cloud service (3-AZ replication) |
| `ConsumerGroupCoordinator.java` | Kafka consumer groups (MSK) / KCL checkpointing (Kinesis) |
| `AckMode.java` | MSK: producer `acks` config / Kinesis: N/A (always replicated) |
| `RetentionPolicy.java` | MSK: topic configs / Kinesis: stream retention / SQS: retention period |
| `LogCompactionStrategy.java` | MSK only (Kinesis, Event Hubs, SQS do not support compaction) |
| `HashPartitioningStrategy.java` | MSK: DefaultPartitioner / Kinesis: partition key hash |
| `ProducerService.java` | Kafka producer client / Kinesis PutRecord API / SQS SendMessage |
| `ConsumerService.java` | Kafka consumer client / KCL / SQS ReceiveMessage |
| `MessageRouter.java` | EventBridge rules / SNS filter policies / RabbitMQ exchanges |

### What the Cloud Adds

```
  ┌──────────────────────────────────────────────────────────┐
  │         Cloud Features Not in Our Simulation             │
  │                                                          │
  │  Infrastructure:                                         │
  │    - Multi-AZ replication (automatic)                    │
  │    - Auto-scaling (serverless offerings)                 │
  │    - Rolling upgrades and patching                       │
  │    - Backup and disaster recovery                        │
  │                                                          │
  │  Security:                                               │
  │    - IAM authentication and authorization                │
  │    - TLS encryption in transit                           │
  │    - KMS encryption at rest                              │
  │    - VPC network isolation                               │
  │    - Audit logging                                       │
  │                                                          │
  │  Monitoring:                                             │
  │    - CloudWatch / Azure Monitor / Cloud Monitoring       │
  │    - Per-topic, per-partition metrics                    │
  │    - Consumer lag alerting                               │
  │    - Throughput and latency dashboards                   │
  │                                                          │
  │  Integration:                                            │
  │    - Lambda / Azure Functions / Cloud Functions triggers │
  │    - S3 / Blob / GCS archival                           │
  │    - Data warehouse loading (Redshift, BigQuery)        │
  │    - Schema registry                                     │
  └──────────────────────────────────────────────────────────┘
```

---

## 18. Interview Quick Reference

### "Which cloud messaging service would you recommend?"

**Framework for answering:**

```
  1. Clarify requirements:
     - Throughput? (msg/sec, MB/sec)
     - Ordering? (strict, best-effort, none)
     - Replay needed? (reprocess historical data)
     - Delivery guarantee? (at-most-once, at-least-once, exactly-once)
     - Budget? (cost-sensitive vs. feature-rich)
     - Cloud? (AWS, Azure, GCP, multi-cloud)

  2. Match to service:
     - Simple queue, low cost → SQS / Cloud Tasks
     - Event streaming, replay → MSK / Kinesis / Event Hubs
     - Complex routing → EventBridge / Service Bus
     - Fan-out notifications → SNS + SQS / Pub/Sub
     - Full Kafka ecosystem → MSK / Confluent Cloud
     - Multi-cloud → Confluent Cloud
```

### "How would you migrate from SQS to Kafka?"

**Key talking points:**
1. Dual-write from producers during transition
2. Consumer rewrite (poll-based Kafka vs. receive-delete SQS)
3. Offset management replaces visibility timeout
4. Build DLQ pattern (separate topic) to replace SQS built-in DLQ
5. Test with shadow traffic before production cutover

### "What is the difference between Kinesis and MSK?"

**Key talking points:**
1. Kinesis is fully serverless (per-shard pricing); MSK requires instance selection
2. Kinesis has fixed 1 MB/s per shard; MSK throughput depends on instance type
3. Kinesis has no log compaction; MSK supports full Kafka features
4. Kinesis uses AWS SDK; MSK uses standard Kafka client libraries
5. Kinesis costs more at high throughput; MSK costs less but requires more ops

### "How does Event Hubs compare to Kafka?"

**Key talking points:**
1. Event Hubs provides a Kafka-compatible endpoint (same producer/consumer code)
2. Event Hubs lacks transactions, log compaction, and idempotent producer
3. Event Hubs has built-in Capture for archiving to Blob Storage
4. Event Hubs is fully managed with no broker selection
5. For full Kafka functionality on Azure, use Confluent Cloud

---

*This document covers cloud services for distributed message queues.*
*Each service is compared across features, cost, and suitability for different use cases.*
