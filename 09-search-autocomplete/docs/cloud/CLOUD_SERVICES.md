# Search Autocomplete (Typeahead) -- Cloud Service Mapping

## Component-to-Service Mapping

| Our Component | AWS | Azure | GCP | Notes |
|---------------|-----|-------|-----|-------|
| **API Gateway** | API Gateway (REST) + CloudFront | API Management + Front Door | Cloud Endpoints + Cloud CDN | CDN caches popular prefix responses |
| **Autocomplete Service** | ECS/EKS (Fargate) | AKS | GKE | Stateless, serves from in-memory Trie or cache |
| **Search Index (Trie)** | OpenSearch (Elasticsearch) | Cognitive Search | Vertex AI Search | Completion Suggester for prefix matching |
| **Managed Search** | CloudSearch | Cognitive Search | Cloud Search (Retail) | Simpler alternative to OpenSearch |
| **Prefix Cache** | ElastiCache Redis (LRU) | Azure Cache for Redis | Memorystore | prefix -> top-K results, Zipf distribution |
| **Event Streaming** | Kinesis Data Streams / MSK (Kafka) | Event Hubs / Kafka on HDInsight | Pub/Sub / Confluent on GCP | Search query events for aggregation |
| **Aggregation Pipeline** | EMR (Spark) / Glue | HDInsight (Spark) / Synapse | Dataproc (Spark) / Dataflow | Aggregate query frequencies, hourly/daily |
| **Trie Storage (snapshot)** | S3 | Blob Storage | Cloud Storage | Serialized Trie snapshots for hot-swap rebuild |
| **Trending / Real-time** | Kinesis Analytics / Lambda | Stream Analytics / Functions | Dataflow / Cloud Functions | Real-time trending query detection |
| **Object Storage** | S3 | Blob Storage | Cloud Storage | Query logs, Trie snapshots, ML training data |
| **User History DB** | DynamoDB | Cosmos DB | Bigtable / Firestore | Per-user search history for personalization |
| **Monitoring** | CloudWatch + X-Ray | Azure Monitor + App Insights | Cloud Monitoring + Trace | Latency p50/p99, cache hit rate, suggestion CTR |
| **CDN (prefix cache)** | CloudFront | Azure CDN / Front Door | Cloud CDN | Cache top-K for 1-2 char prefixes at edge |
| **DNS** | Route 53 (latency-based) | Traffic Manager | Cloud DNS | Route to nearest region |

---

## Real-Time Autocomplete Architecture on AWS (Numbered Flow)

```
User types "how t" in search bar
    |
    1. GET /autocomplete?prefix=how+t  (debounced, 100-200ms)
    |
    v
+--------------------------------------------------+
|          CloudFront (CDN Edge Cache)              |
|   Cache-Control: max-age=300 for short prefixes   |
|   Cache key: prefix + region + language           |
+------------------+-------------------------------+
                   |
    2. MISS -> forward to origin (HIT -> return cached, skip to step 8)
                   |
                   v
+--------------------------------------------------+
|          API Gateway (REST)                       |
|   Rate limiting: 50 req/sec per user             |
|   Throttle aggressive keystroke bursts           |
+------------------+-------------------------------+
                   |
    3. Route to Autocomplete Service (ECS/Fargate)
                   |
                   v
+--------------------------------------------------+
|       Autocomplete Service (ECS Fargate)          |
|   Stateless, 10-20 instances, auto-scaling       |
|   In-memory Trie loaded from S3 snapshot         |
+-------+--------------------+---------------------+
        |                    |
   4. Check Redis            |   5. (Cache MISS only)
      prefix cache           |   Query in-memory Trie
        |                    |   or OpenSearch completion
        v                    v
+------------------+   +-----------------------------+
| ElastiCache Redis|   | OpenSearch (Elasticsearch)  |
| (LRU Cache)      |   |                             |
|                  |   | Completion Suggester         |
| Key: "how t"    |   | POST /queries/_search        |
| Value: [top-10] |   | { "suggest": {               |
| TTL: 5 min      |   |     "query-suggest": {       |
|                  |   |       "prefix": "how t",     |
|                  |   |       "completion": {        |
| HIT -> return   |   |         "field": "suggest",  |
| MISS -> step 5  |   |         "size": 10           |
+------------------+   |       }                      |
                       |     }                        |
                       |   }                          |
                       | }                            |
                       +-------------+----------------+
                                     |
    6. Write result to Redis cache (SET "how t" [...] EX 300)
                                     |
    7. Log query event to Kinesis stream (async, non-blocking)
       { "prefix": "how t", "timestamp": ..., "userId": ... }
                                     |
                                     v
                       +-----------------------------+
                       |  Kinesis Data Stream         |
                       |  (query-events)              |
                       |  Shard by prefix hash        |
                       |  Retention: 7 days           |
                       +------+---------------+------+
                              |               |
               8. Consumer:   |               |  9. Consumer:
               Spark (EMR)    |               |  Trending Lambda
               aggregation    |               |  (real-time)
                              v               v
                    +----------------+  +------------------+
                    | EMR (Spark)    |  | Lambda           |
                    | Hourly: count  |  | Sliding window   |
                    | per prefix,    |  | (5 min): detect  |
                    | decay old data |  | trending queries |
                    | Rebuild Trie   |  | Inject into Trie |
                    +-------+--------+  +------------------+
                            |
               10. Write new Trie snapshot to S3
                            |
               11. ECS instances hot-swap: load new Trie from S3
                   (blue-green: old Trie serves until new is ready)
```

---

## Elasticsearch Completion Suggester vs Custom Trie

| Aspect | Elasticsearch Completion Suggester | Custom Trie (in-memory) |
|--------|-------------------------------------|--------------------------|
| **Latency** | 1-5ms (network hop + FST lookup) | Sub-ms (in-process, no network) |
| **Storage** | FST (Finite State Transducer) on disk | Java heap memory (Trie nodes) |
| **Memory** | Off-heap, managed by Elasticsearch | On-heap, managed by JVM GC |
| **Scaling** | Shard-based, horizontal scaling | Replicate full Trie to each instance |
| **Custom ranking** | Weights, contexts, categories | Full control: frequency, recency, personalization |
| **Fuzzy matching** | Built-in fuzzy completion | Must implement Levenshtein distance |
| **Update frequency** | Near real-time (index refresh) | Batch rebuild + hot-swap (minutes) |
| **Operational cost** | Managed OpenSearch cluster ($$$) | Just application memory (cheaper) |
| **Best for** | Large vocabulary (100M+ terms), fuzzy | Moderate vocabulary (1-10M), low latency |
| **Interview answer** | "Production choice for large-scale" | "Implement this to show data structure knowledge" |

### When to Use Which

```
Custom Trie (in-memory):
  - Vocabulary < 10M terms
  - Sub-millisecond latency required
  - Full control over ranking algorithm needed
  - Want to demonstrate data structure knowledge in interview

Elasticsearch Completion Suggester:
  - Vocabulary > 10M terms (too big for JVM heap)
  - Fuzzy matching required (typo tolerance)
  - Multi-language support needed
  - Team doesn't want to maintain custom Trie code
  - Already using Elasticsearch for full-text search

Hybrid (Best of Both):
  - Trie for hot prefixes (top 1M terms) -> sub-ms, in-memory
  - Elasticsearch for long-tail queries  -> 2-5ms, FST-based
  - Redis cache in front of both          -> 0.5ms for repeated queries
```

---

## CDN Caching for Popular Prefixes

### Why CDN for Autocomplete?

```
Observation: Autocomplete queries follow Zipf distribution.
  - 1-char prefixes (26 options):   ~40% of all queries
  - 2-char prefixes (676 options):  ~30% of all queries
  - 3-char prefixes (~17K options): ~20% of all queries
  - 4+ char prefixes:               ~10% of all queries

Key insight: 70% of autocomplete traffic is for the same ~700 prefixes.
             These can be served from CDN edge locations.

Cache strategy:
  Prefix length 1-2: Cache at CDN edge, TTL = 1 hour
  Prefix length 3:   Cache at CDN edge, TTL = 15 min
  Prefix length 4+:  Cache at Redis only, TTL = 5 min
  Personalized:      Never cache at CDN (user-specific)
```

### CDN Architecture

```
User types "h"
    |
    1. GET /autocomplete?prefix=h
    |
    v
+--------------------------------------------------+
|       CloudFront Edge Location (50+ POPs)         |
|                                                    |
|   Cache key: /autocomplete?prefix=h&lang=en       |
|   TTL: 3600s (1 hour)                             |
|                                                    |
|   HIT rate for 1-char: ~99%                        |
|   HIT rate for 2-char: ~95%                        |
|   HIT rate for 3-char: ~60%                        |
|   HIT rate for 4+char: ~10% (too many variations) |
+--------------------------------------------------+
    |
    2. MISS -> origin (API Gateway -> ECS -> Redis/Trie)
    |
    3. Response headers:
       Cache-Control: public, max-age=3600   (for prefix "h")
       Vary: Accept-Language
       X-Cache-Prefix-Length: 1
```

### Cost Impact of CDN Caching

```
Without CDN:
  100M autocomplete requests/day
  All hit API Gateway + ECS + Redis
  Cost: ~$15,000/month

With CDN (CloudFront):
  100M requests/day
  70% served from CDN edge (~70M requests)
  30% hit origin (~30M requests)

  CloudFront: 70M requests * $0.0075/10K = ~$53/day = ~$1,600/month
  Origin cost reduced by 70%: ~$4,500/month
  Total: ~$6,100/month (60% savings)
```

---

## Cost Estimation at Scale

### Assumptions

```
Daily searches:           50,000,000 (50M full searches)
Autocomplete requests:    500,000,000 (500M -- ~10 keystrokes per search)
Peak multiplier:          3x (during business hours)
Peak autocomplete QPS:    500M / 86400 * 3 ~ 17,000 req/sec
Vocabulary size:          50,000,000 terms (50M unique queries)
Trie size in memory:      ~2-4 GB (compressed, with top-K per node)
Cache hit rate (Redis):   85% (after CDN absorbs short prefixes)
Cache hit rate (CDN):     70% of total traffic
```

### Monthly Cost Breakdown

| Resource | Spec | Monthly Cost |
|----------|------|-------------|
| **CloudFront (CDN)** | 500M requests/day, 70% cache hit, ~150M to origin | ~$5,000 |
| **API Gateway (REST)** | 150M requests/day (after CDN), throttled | ~$4,500 |
| **ECS/Fargate (service)** | 15 tasks, 4 vCPU / 8 GB each (holds Trie in memory) | ~$6,000 |
| **ElastiCache Redis** | 3 shards, r6g.xlarge, 1 replica each (LRU cache) | ~$6,000 |
| **OpenSearch (Elasticsearch)** | 3 data nodes, r6g.xlarge (completion suggester) | ~$5,500 |
| **Kinesis Data Streams** | 20 shards (query event stream) | ~$1,500 |
| **EMR (Spark)** | 5 c5.2xlarge, hourly aggregation job (runs 2 hrs/day) | ~$1,200 |
| **S3 (Trie snapshots + logs)** | 2 TB storage, 10M requests/month | ~$100 |
| **DynamoDB (user history)** | 5K WCU, 10K RCU (on-demand, personalization) | ~$2,500 |
| **CloudWatch + X-Ray** | Latency, cache hit rate, suggestion CTR metrics | ~$500 |
| **Data transfer** | Cross-AZ, NAT Gateway, internet egress | ~$1,500 |
| **Total** | | **~$34,300/month** |

### Cost Optimization Strategies

1. **CDN caching** -- Serves 70% of traffic from edge, biggest single optimization
2. **Client-side debouncing** -- 100-200ms debounce reduces requests by 50-70%
3. **Reserved Instances** -- 1-year for Redis and OpenSearch saves 30-40%
4. **Trie in-memory vs OpenSearch** -- For < 10M terms, skip OpenSearch entirely, save $5,500/month
5. **Spot instances for EMR** -- Spark aggregation on Spot saves 60-70%
6. **Redis TTL tuning** -- Shorter TTL for rare prefixes, longer for popular ones
7. **Request collapsing** -- Identical in-flight requests share a single backend call

### Cost at Different Scales

| Scale | Daily Searches | Monthly Cost | Cost/1K Searches |
|-------|---------------|-------------|-----------------|
| Startup | 100K | ~$2,500 | $0.83 |
| Growth | 5M | ~$8,000 | $0.053 |
| Scale | 50M | ~$34,300 | $0.023 |
| Enterprise | 500M | ~$200,000 | $0.013 |

---

## Multi-Region Deployment

```
                         +-------------------------------+
                         |       Route 53 (DNS)          |
                         |  Latency-based routing        |
                         |  US users  -> us-east-1       |
                         |  EU users  -> eu-west-1       |
                         |  APAC users -> ap-northeast-1 |
                         +------+---------------+-------+
                                |               |
              +-----------------v--+   +--------v-----------------+
              |    us-east-1       |   |    eu-west-1             |
              |                    |   |                          |
              |  CloudFront (CDN)  |   |  CloudFront (CDN)       |
              |  API Gateway       |   |  API Gateway             |
              |  ECS (Trie in mem) |   |  ECS (Trie in mem)      |
              |  Redis (prefix     |   |  Redis (prefix           |
              |    cache)          |   |    cache)                |
              |  OpenSearch        |   |  OpenSearch              |
              |    (completion)    |   |    (completion)          |
              |  Kinesis (queries) |   |  Kinesis (queries)      |
              |                    |   |                          |
              |  S3 (Trie         <----+- S3 Cross-Region        |
              |    snapshots)      |   |   Replication            |
              |  (PRIMARY Trie     |   |  (Same Trie snapshot,   |
              |    build here)     |   |   regional language      |
              |                    |   |   overlay)               |
              +--------------------+   +--------------------------+
```

### Key Multi-Region Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| Trie data | **Replicated (same base Trie)** | Users search for similar global terms; regional overlay for local terms |
| Trie build | **Single-region primary, replicate snapshot** | Build once on primary EMR, copy S3 snapshot to other regions |
| Query aggregation | **Regional** | Aggregate locally, merge daily for global frequency ranking |
| Prefix cache (Redis) | **Regional (independent)** | Cache warms naturally per region's traffic patterns |
| CDN | **Per-region CloudFront distribution** | Edge locations serve short prefixes locally |
| User history | **DynamoDB Global Tables** | Users travel; personalization follows them |
| Trending detection | **Regional + global merge** | Local trends (regional news) + global trends (world events) |
| Language support | **Regional Trie overlay** | Base Trie (English) + regional Trie (local language) merged at query time |

### Language-Aware Multi-Region

```
US Region:    Base Trie (English, 50M terms)
EU Region:    Base Trie (English) + French overlay (10M) + German overlay (8M)
APAC Region:  Base Trie (English) + Japanese overlay (15M) + Korean overlay (7M)

Query routing:
  Accept-Language: en    -> search English Trie only
  Accept-Language: fr    -> search French Trie first, fallback to English
  Accept-Language: ja    -> search Japanese Trie first, fallback to English

Each overlay Trie is ~1-2 GB additional memory per ECS instance.
Language detection from Accept-Language header or user settings.
```

---

## Interview Tip

> "For a search autocomplete system on AWS, I'd use a **custom Trie** loaded in-memory on ECS for sub-millisecond prefix lookup, backed by **ElastiCache Redis** as an LRU cache for the top-K results per prefix. **CloudFront** caches 70% of requests at the edge -- short prefixes (1-2 chars) follow Zipf distribution and are highly cacheable. For the data pipeline: **Kinesis** streams query events, **EMR Spark** aggregates frequencies hourly, rebuilds the Trie, and writes a snapshot to **S3**. ECS instances hot-swap the new Trie with zero downtime. For large-scale vocabulary (100M+ terms), I'd add **OpenSearch Completion Suggester** as a fallback for long-tail queries. The system is AP -- stale suggestions are fine; we rebuild the Trie periodically and serve from cache."

This shows you understand **Trie data structures, caching at multiple layers (CDN + Redis), and the offline pipeline** -- the three pillars of autocomplete infrastructure.

---

## Quick Reference: Which Service When

| Decision Point | Service | Config | Why |
|---------------|---------|--------|-----|
| Prefix -> top-K lookup | ECS (in-memory Trie) | 4 vCPU, 8 GB, Trie loaded from S3 | Sub-ms, no network hop |
| Prefix cache | ElastiCache Redis (LRU) | 3 shards, TTL 5 min | 0.5ms cache hit, offloads Trie |
| Short prefix cache | CloudFront (CDN edge) | TTL 1 hour for 1-2 char | 70% traffic at edge, Zipf distribution |
| Full-text completion | OpenSearch Completion Suggester | 3 data nodes, FST index | Fuzzy matching, 100M+ vocabulary |
| Query event stream | Kinesis Data Streams | 20 shards, key = prefix hash | Ordered events for aggregation |
| Frequency aggregation | EMR (Spark) | 5 nodes, hourly batch job | Count per prefix, time-decay weighting |
| Trie snapshot storage | S3 | Versioned bucket, cross-region | Hot-swap Trie, rollback on bad build |
| User search history | DynamoDB | Partition = userId, sort = timestamp | Personalized suggestions |
| Trending detection | Lambda + Kinesis Analytics | Sliding window, 5 min | Inject trending terms into Trie |
