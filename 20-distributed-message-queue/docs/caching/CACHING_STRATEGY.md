# Caching Strategy: Distributed Message Queue (Project 20)

> Page cache as Kafka's secret weapon, what NOT to cache, zero-copy sendfile(),
> offset caching, metadata caching, and producer batch buffers.

---

## Table of Contents

1. [Overview: Why Message Queues Don't Cache Messages](#1-overview-why-message-queues-dont-cache-messages)
2. [OS Page Cache: Kafka's Secret Weapon](#2-os-page-cache-kafkas-secret-weapon)
3. [Why NOT to Cache Message Data](#3-why-not-to-cache-message-data)
4. [Zero-Copy sendfile()](#4-zero-copy-sendfile)
5. [Consumer Offset Caching](#5-consumer-offset-caching)
6. [Topic and Partition Metadata Cache](#6-topic-and-partition-metadata-cache)
7. [Producer Batch Buffer as Cache](#7-producer-batch-buffer-as-cache)
8. [Consumer Fetch Buffer](#8-consumer-fetch-buffer)
9. [Schema Cache](#9-schema-cache)
10. [Broker-Side Caches](#10-broker-side-caches)
11. [Cache Anti-Patterns in Message Queues](#11-cache-anti-patterns-in-message-queues)
12. [Simulation-to-Production Mapping](#12-simulation-to-production-mapping)
13. [Cloud-Specific Caching Considerations](#13-cloud-specific-caching-considerations)
14. [Performance Benchmarks](#14-performance-benchmarks)
15. [Interview Deep Dive](#15-interview-deep-dive)

---

## 1. Overview: Why Message Queues Don't Cache Messages

Most distributed systems benefit from caching hot data in memory. Message queues
are the notable exception. Here is why:

```
  Traditional System (e.g., web app):
  ┌──────────┐     ┌───────────┐     ┌──────────┐
  │ Request  │────▶│   Cache   │────▶│ Database │
  │          │     │  (Redis)  │     │          │
  └──────────┘     └───────────┘     └──────────┘
  Access pattern: random reads, high locality, re-reads common
  Cache helps: YES — avoids repeated database queries

  Message Queue (e.g., Kafka):
  ┌──────────┐     ┌───────────┐     ┌──────────┐
  │ Producer │────▶│   Broker  │────▶│ Consumer │
  │          │     │  (log)    │     │          │
  └──────────┘     └───────────┘     └──────────┘
  Access pattern: sequential writes, sequential reads, each message read ~once
  Cache helps: NO — sequential I/O already saturates disk bandwidth
```

### The Key Insight

Message queues have a **sequential access pattern**. Every message is:
1. Written once (append to the end of the log)
2. Read once per consumer group (from the current offset forward)
3. Never randomly accessed in the hot path

Sequential disk I/O on modern hardware achieves **600 MB/s to 3+ GB/s** —
comparable to or faster than network bandwidth. Adding an application-level
cache between the log and the consumer would only add overhead.

### What IS Cached (and What Is Not)

```
  ┌────────────────────────────────────────────────────────────┐
  │              Message Queue Caching Strategy                │
  ├───────────────────────────┬────────────────────────────────┤
  │  CACHED (metadata/state)  │  NOT CACHED (message data)    │
  ├───────────────────────────┼────────────────────────────────┤
  │  Consumer offsets          │  Message payloads             │
  │  Topic metadata            │  Message keys                │
  │  Partition assignments     │  Message headers              │
  │  Schema definitions        │  Commit log segments          │
  │  Broker topology           │  Replication data             │
  │  ACL/quota configs         │                               │
  │  Producer batch buffers    │                               │
  └───────────────────────────┴────────────────────────────────┘
```

---

## 2. OS Page Cache: Kafka's Secret Weapon

### How It Works

Kafka delegates ALL caching to the operating system's page cache. When Kafka writes
to disk, the data first goes into the page cache (kernel memory), then is flushed
to disk asynchronously. When consumers read recent data, it is served directly
from page cache — never touching the physical disk.

```
  ┌──────────────────────────────────────────────────────────────┐
  │                     Linux Memory Layout                     │
  │                                                              │
  │  ┌────────────────────────────────────────────────────────┐  │
  │  │                    Physical RAM (64 GB)                │  │
  │  │                                                        │  │
  │  │  ┌──────────────┐  ┌──────────────────────────────┐   │  │
  │  │  │ JVM Heap     │  │     OS Page Cache             │   │  │
  │  │  │ (6 GB)       │  │     (52 GB)                   │   │  │
  │  │  │              │  │                                │   │  │
  │  │  │ Kafka broker │  │  ┌──────────────────────────┐ │   │  │
  │  │  │ metadata,    │  │  │ Segment File Pages       │ │   │  │
  │  │  │ request      │  │  │                          │ │   │  │
  │  │  │ handling     │  │  │ Page 0: offset 0-4095    │ │   │  │
  │  │  │              │  │  │ Page 1: offset 4096-8191 │ │   │  │
  │  │  │              │  │  │ Page 2: offset 8192-...  │ │   │  │
  │  │  │              │  │  │ ...                      │ │   │  │
  │  │  └──────────────┘  │  └──────────────────────────┘ │   │  │
  │  │                     │                                │   │  │
  │  │  Kernel (6 GB)      │  (LRU eviction when full)     │   │  │
  │  └─────────────────────┴────────────────────────────────┘   │  │
  │                                                              │  │
  └──────────────────────────────────────────────────────────────┘
```

### Write Path Through Page Cache

```
  Producer             Kafka Broker             OS Page Cache         Disk
     │                      │                        │                 │
     │── Produce(msg) ─────▶│                        │                 │
     │                      │                        │                 │
     │                      │── write() ────────────▶│                 │
     │                      │   (Java NIO)           │                 │
     │                      │                        │ Page marked     │
     │                      │                        │ "dirty"         │
     │                      │                        │                 │
     │                      │◀── return ─────────────│                 │
     │◀── Ack ─────────────│                        │                 │
     │                      │                        │                 │
     │                      │              (async)   │── flush ───────▶│
     │                      │              (kernel   │  (pdflush/      │
     │                      │               thread)  │   writeback)    │
     │                      │                        │                 │
```

### Read Path Through Page Cache (Recent Data)

```
  Consumer             Kafka Broker             OS Page Cache         Disk
     │                      │                        │                 │
     │── Fetch(offset) ────▶│                        │                 │
     │                      │                        │                 │
     │                      │── read() ─────────────▶│                 │
     │                      │   (Java NIO)           │                 │
     │                      │                        │ Page found!     │
     │                      │◀── data ──────────────│ (cache hit)     │
     │                      │                        │                 │
     │◀── Response ────────│                        │    (NO disk     │
     │                      │                        │     I/O!)       │
```

### Read Path (Old Data — Cache Miss)

```
  Consumer             Kafka Broker             OS Page Cache         Disk
     │                      │                        │                 │
     │── Fetch(old offset)─▶│                        │                 │
     │                      │                        │                 │
     │                      │── read() ─────────────▶│                 │
     │                      │                        │ Cache miss      │
     │                      │                        │── read ────────▶│
     │                      │                        │◀── data ───────│
     │                      │                        │ (page loaded,  │
     │                      │                        │  evicts LRU)   │
     │                      │◀── data ──────────────│                 │
     │◀── Response ────────│                        │                 │
```

### Why Page Cache Beats Application-Level Caching

| Dimension | OS Page Cache | Application Cache (e.g., HashMap) |
|---|---|---|
| **Memory management** | Kernel LRU eviction | JVM garbage collection |
| **Warm after restart** | Survives JVM restart | Lost on restart |
| **Memory efficiency** | No object headers or GC overhead | 2-3x overhead per entry |
| **Eviction** | Automatic LRU by kernel | Manual or framework-managed |
| **Concurrency** | Kernel-managed, lock-free reads | Application must synchronize |
| **Data path** | mmap → sendfile → NIC | Serialize → copy → NIC |

### Page Cache Sizing Guidelines

```
  Rule of thumb:
    Page cache should hold at least ONE full segment of active partitions.

  Example calculation:
    - 100 active partitions
    - Segment size: 1 GB (default log.segment.bytes)
    - Consumer lag: typically reading within last 30 minutes
    - Write rate: 100 MB/s aggregate

    30 minutes of data at 100 MB/s = 180 GB
    Recommendation: 192-256 GB RAM, JVM heap = 6-8 GB
    Available for page cache: ~180-250 GB
    
    With this setup, nearly ALL consumer reads hit page cache
    because consumers are reading data written in the last 30 min.
```

---

## 3. Why NOT to Cache Message Data

### The Anti-Cache Argument

```
  ┌──────────────────────────────────────────────────────────┐
  │         Why Application-Level Message Caching Hurts      │
  ├──────────────────────────────────────────────────────────┤
  │                                                          │
  │  1. DOUBLE CACHING                                       │
  │     Data is already in page cache. Caching in the JVM    │
  │     means TWO copies of the same data in memory.         │
  │                                                          │
  │  2. GC PRESSURE                                          │
  │     Message data in JVM heap creates GC pauses.          │
  │     At 1M msg/s, even a 1KB average message = 1 GB/s    │
  │     of JVM allocation → frequent GC → latency spikes.   │
  │                                                          │
  │  3. SEQUENTIAL ACCESS PATTERN                            │
  │     Messages are read in order. Page cache (OS readahead)│
  │     is optimized for exactly this pattern. Random-access │
  │     caches (HashMap, LRU) add overhead without benefit.  │
  │                                                          │
  │  4. ONE-TIME READ                                        │
  │     Each message is typically consumed once per consumer  │
  │     group. Cache hit rate would be near zero.            │
  │                                                          │
  │  5. WARM-UP COST                                         │
  │     Application cache is empty after restart. Page cache │
  │     persists across JVM restarts.                        │
  │                                                          │
  └──────────────────────────────────────────────────────────┘
```

### Quantifying the Problem

```
  Scenario: 500K msg/s, average 1 KB per message

  Application cache approach:
    Memory needed: 500K * 1KB = 500 MB/s flowing through cache
    JVM allocation rate: 500 MB/s
    GC impact: major GC every 10-20 seconds
    Latency spikes: 50-200ms during GC
    Usable throughput: ~300K msg/s (GC throttling)

  Page cache approach (Kafka's design):
    JVM allocation: near zero (messages never materialized as Java objects on broker)
    GC impact: minimal (only metadata objects in heap)
    Latency: consistent 2-5ms p99
    Usable throughput: 500K+ msg/s (disk/network limited, not GC limited)
```

### The One Exception: Compacted Topics

Log-compacted topics (like Kafka's `__consumer_offsets`) represent a **key-value store**
pattern where the latest value per key is retained. For these topics, an in-memory
cache of the compacted state can be valuable:

```
  Topic: __consumer_offsets (compacted)

  Log:   [G1-T-P0:100] [G1-T-P1:200] [G1-T-P0:150] [G2-T-P0:50]

  In-memory cache (after compaction):
  ┌──────────────┬────────┐
  │ Key          │ Offset │
  ├──────────────┼────────┤
  │ G1-T-P0      │ 150    │  (latest for this key)
  │ G1-T-P1      │ 200    │
  │ G2-T-P0      │ 50     │
  └──────────────┴────────┘
```

---

## 4. Zero-Copy sendfile()

### The Traditional Data Path (4 copies, 4 context switches)

```
  Disk → Kernel Buffer → User Buffer → Socket Buffer → NIC
  
  Step 1: read() syscall
    ┌──────┐     ┌──────────────┐     ┌──────────────┐
    │ Disk │────▶│ Kernel       │────▶│ Application  │
    │      │     │ Read Buffer  │     │ Buffer (JVM) │
    └──────┘     └──────────────┘     └──────────────┘
    (DMA copy)   (CPU copy to user space)

  Step 2: write() syscall
    ┌──────────────┐     ┌──────────────┐     ┌─────┐
    │ Application  │────▶│ Socket       │────▶│ NIC │
    │ Buffer (JVM) │     │ Buffer       │     │     │
    └──────────────┘     └──────────────┘     └─────┘
    (CPU copy to kernel)  (DMA copy to NIC)

  Total: 4 data copies, 4 user/kernel context switches
```

### The Zero-Copy Path (2 copies, 2 context switches)

```
  Disk → Kernel Buffer → NIC  (skips user space entirely)
  
  transferTo() / sendfile():
    ┌──────┐     ┌──────────────┐                ┌─────┐
    │ Disk │────▶│ Kernel       │───────────────▶│ NIC │
    │      │     │ Page Cache   │                │     │
    └──────┘     └──────────────┘                └─────┘
    (DMA copy)   (DMA scatter-gather to NIC, no CPU copy)

  Total: 2 data copies (both DMA, no CPU involvement)
         2 context switches (sendfile syscall + return)
```

### How Kafka Uses Zero-Copy

```java
  // Kafka uses Java NIO's FileChannel.transferTo()
  // which maps to the Linux sendfile() syscall

  // Conceptual code (simplified from Kafka source):
  public long writeTo(GatheringByteChannel channel, long position, int length) {
      // This single call replaces:
      //   1. read() from file to user buffer
      //   2. write() from user buffer to socket
      // With:
      //   1. sendfile() from file directly to socket (kernel space only)
      return fileChannel.transferTo(position, length, channel);
  }
```

### Performance Impact of Zero-Copy

```
  Benchmark: 1 GB transfer, single partition

  Traditional (read + write):
    CPU time: ~300ms (copying 1 GB through user space)
    Context switches: 4
    Memory bus bandwidth used: 4 GB (4 copies)
    Throughput: ~3.3 GB/s effective

  Zero-copy (sendfile):
    CPU time: ~10ms (only syscall overhead)
    Context switches: 2
    Memory bus bandwidth used: 2 GB (2 DMA copies)
    Throughput: ~6+ GB/s effective (network limited)

  Result: ~2x throughput, ~30x less CPU per byte transferred
```

### When Zero-Copy Does NOT Apply

Zero-copy only works when the broker does not need to inspect or transform the data:

```
  Zero-copy WORKS:
    ✅ Consumer fetching uncompressed data
    ✅ Consumer fetching data compressed by producer (pass-through)
    ✅ Follower replica fetching from leader (pass-through)

  Zero-copy DOES NOT WORK:
    ❌ TLS/SSL encryption (broker must encrypt in user space)
    ❌ Broker-side message transformation
    ❌ Data needs format conversion
```

### Our Simulation's Read Path

```java
// CommitLog.java — read()
// This returns Java objects from an in-memory list.
// In production Kafka, this would be:
//   1. Lookup offset in the sparse index file
//   2. sendfile() from the segment file to the consumer's socket
//   3. No Java object creation, no JVM heap allocation

public synchronized List<Message> read(long fromOffset, int maxMessages) {
    if (fromOffset < 0 || fromOffset >= log.size() || maxMessages <= 0) {
        return Collections.emptyList();
    }
    int start = (int) fromOffset;
    int end = Math.min(start + maxMessages, log.size());
    return new ArrayList<>(log.subList(start, end));
}
```

---

## 5. Consumer Offset Caching

### What Gets Cached

Consumer offsets ARE actively cached because they follow a random-access pattern:
- Consumers commit offsets frequently (every few seconds)
- Consumers read their committed offset on startup
- The coordinator looks up offsets during rebalance

### How Kafka Caches Offsets

```
  ┌──────────────────────────────────────────────────────────────┐
  │              Offset Caching Architecture                     │
  │                                                              │
  │  __consumer_offsets topic (persistent storage):              │
  │  ┌──────────────────────────────────────────────────┐       │
  │  │ [G1-orders-0:100] [G1-orders-1:200] [G2-pay-0:50]│       │
  │  │ [G1-orders-0:150] ...                             │       │
  │  └──────────────────────────────────────────────────┘       │
  │           │                                                  │
  │           │ (log compaction keeps latest per key)            │
  │           ▼                                                  │
  │  In-memory offset cache (GroupCoordinator):                  │
  │  ┌──────────────────────────────────────────────────┐       │
  │  │ Key: "G1-orders-0"  →  Offset: 150               │       │
  │  │ Key: "G1-orders-1"  →  Offset: 200               │       │
  │  │ Key: "G2-payments-0" →  Offset: 50               │       │
  │  └──────────────────────────────────────────────────┘       │
  │                                                              │
  │  Cache characteristics:                                      │
  │  - Populated on coordinator startup (read compacted topic)  │
  │  - Updated on every OffsetCommit request                     │
  │  - Read on every OffsetFetch request                         │
  │  - Evicted when consumer group expires (offsets.retention)   │
  └──────────────────────────────────────────────────────────────┘
```

### Our Simulation's Offset Cache

```java
// ConsumerGroupCoordinator.java
// This IS an in-memory cache — exactly what production Kafka does.
// The difference: production also persists to __consumer_offsets topic.

private final Map<String, Long> committedOffsets;  // key = "groupId-topic-partition"

public void commitOffset(String groupId, String topic, int partition, long offset) {
    String key = buildOffsetKey(groupId, topic, partition);
    committedOffsets.put(key, offset);
    // Production: also writes to __consumer_offsets topic partition
}

public long getCommittedOffset(String groupId, String topic, int partition) {
    String key = buildOffsetKey(groupId, topic, partition);
    return committedOffsets.getOrDefault(key, 0L);
    // Production: reads from in-memory cache (not topic)
}
```

### Offset Cache Sizing

```
  Memory per offset entry:
    Key:   ~40 bytes ("consumer-group-1-orders-0")
    Value: 8 bytes (long offset)
    Metadata: ~50 bytes (timestamp, leader epoch, metadata string)
    HashMap overhead: ~48 bytes (entry, node, references)
    Total: ~146 bytes per entry

  For a large cluster:
    1,000 consumer groups
    x 50 topics per group
    x 20 partitions per topic
    = 1,000,000 offset entries

    Memory: 1M * 146 bytes = ~140 MB
    → Fits easily in JVM heap
```

---

## 6. Topic and Partition Metadata Cache

### What Gets Cached

Every Kafka client (producer and consumer) maintains a **metadata cache** that maps:
- Topic name to partition count
- Partition to leader broker
- Partition to replica set and ISR

### Producer Metadata Cache

```
  ┌──────────────────────────────────────────────────────────┐
  │              Producer Metadata Cache                      │
  │                                                          │
  │  Topic: "orders"                                         │
  │    Partition 0: leader=broker-1, ISR=[broker-1, broker-2]│
  │    Partition 1: leader=broker-2, ISR=[broker-2, broker-0]│
  │    Partition 2: leader=broker-0, ISR=[broker-0, broker-1]│
  │                                                          │
  │  Topic: "payments"                                       │
  │    Partition 0: leader=broker-0, ISR=[broker-0, broker-2]│
  │    Partition 1: leader=broker-1, ISR=[broker-1, broker-0]│
  │                                                          │
  │  Refresh triggers:                                       │
  │    - metadata.max.age.ms (default 5 min) expires         │
  │    - NOT_LEADER_FOR_PARTITION error on produce           │
  │    - New topic accessed for the first time               │
  │    - Broker connection failure                           │
  └──────────────────────────────────────────────────────────┘
```

### Metadata Refresh Flow

```
  Producer                   Any Broker              Controller
     │                          │                       │
     │── MetadataRequest ──────▶│                       │
     │   (topics=["orders"])    │                       │
     │                          │                       │
     │                          │ (broker has metadata  │
     │                          │  from controller      │
     │                          │  propagation)         │
     │                          │                       │
     │◀── MetadataResponse ────│                       │
     │    (partition leaders,   │                       │
     │     ISR, broker list)    │                       │
     │                          │                       │
     │── (cache metadata) ─────│                       │
     │                          │                       │
     │   ... 5 minutes later ...                        │
     │                          │                       │
     │── MetadataRequest ──────▶│                       │
     │   (refresh cycle)        │                       │
```

### Our Simulation's Metadata

```java
// TopicService / TopicRepository — acts as the metadata store
// ProducerService caches nothing explicitly because it's single-process

// In production, this metadata would be:
// 1. Stored in KRaft __cluster_metadata topic
// 2. Propagated to all brokers
// 3. Cached by every producer and consumer client
// 4. Refreshed on metadata.max.age.ms or error
```

### Metadata Cache Staleness Issues

```
  Problem: Stale metadata causes wasted RPCs

  Timeline:
  t=0s    Producer caches: "orders-P0 leader = broker-1"
  t=10s   Broker-1 fails, controller elects broker-2 as new leader
  t=15s   Producer sends to broker-1 (stale cache)
          → broker-1 is down → connection error
  t=15.1s Producer refreshes metadata
          → learns broker-2 is new leader
  t=15.2s Producer sends to broker-2 → success

  Impact: 10-15 second delay for first produce after leader change
  Mitigation: metadata.max.age.ms=60000 (1 min) for faster refresh
```

---

## 7. Producer Batch Buffer as Cache

### How Producer Batching Works

The Kafka producer accumulates messages in a per-partition batch buffer before sending.
This buffer acts as a write-behind cache:

```
  ┌──────────────────────────────────────────────────────────────┐
  │                Producer RecordAccumulator                    │
  │                                                              │
  │  ┌─────────────────────────────────────────────────────────┐ │
  │  │ Partition 0 batch:                                      │ │
  │  │ [msg1][msg2][msg3]  size: 12KB / 16KB (batch.size)     │ │
  │  │ age: 3ms / 5ms (linger.ms)                             │ │
  │  └─────────────────────────────────────────────────────────┘ │
  │                                                              │
  │  ┌─────────────────────────────────────────────────────────┐ │
  │  │ Partition 1 batch:                                      │ │
  │  │ [msg4]              size: 1KB / 16KB                    │ │
  │  │ age: 1ms / 5ms                                         │ │
  │  └─────────────────────────────────────────────────────────┘ │
  │                                                              │
  │  ┌─────────────────────────────────────────────────────────┐ │
  │  │ Partition 2 batch:                                      │ │
  │  │ [msg5][msg6]        size: 8KB / 16KB                    │ │
  │  │ age: 4ms / 5ms      ← READY (will send at 5ms)        │ │
  │  └─────────────────────────────────────────────────────────┘ │
  │                                                              │
  │  Total buffer: 21KB / 32MB (buffer.memory)                   │
  │                                                              │
  │  Send triggers:                                              │
  │    - Batch reaches batch.size (16KB default)                │
  │    - linger.ms elapsed (0ms default = send immediately)     │
  │    - buffer.memory full (backpressure)                       │
  └──────────────────────────────────────────────────────────────┘
```

### Batch Buffer Configuration

| Config | Default | Effect |
|---|---|---|
| `batch.size` | 16384 (16 KB) | Maximum batch size in bytes |
| `linger.ms` | 0 | Wait time to fill batch |
| `buffer.memory` | 33554432 (32 MB) | Total producer memory budget |
| `compression.type` | none | Compression applied per batch |
| `max.block.ms` | 60000 | Block time when buffer full |

### Tuning the Batch Buffer

```
  Low latency (linger.ms=0):
    Producer send() → immediate network send → ~2ms latency
    Throughput: lower (small batches)
    CPU: higher (more network calls per message)

  High throughput (linger.ms=5, batch.size=65536):
    Producer send() → wait up to 5ms → send 64KB batch → ~7ms latency
    Throughput: 2-3x higher (fewer, larger network calls)
    CPU: lower (amortized per-batch overhead)

  Compression (linger.ms=10, compression.type=lz4):
    Larger batches compress better → even higher throughput
    CPU: moderate (compression work, offset by smaller network I/O)
```

### Our Simulation's Batching

```java
// ProducerService.java sends messages synchronously one at a time.
// Production Kafka uses the RecordAccumulator to batch before sending.

// Our simplified version:
public long send(ProducerRecord record, AckMode ackMode) {
    // resolves partition, appends to CommitLog, replicates
    // No batching — each message is a separate operation
    return offset;
}

// The MessageBatch model exists but is used for batch semantics,
// not for write-behind buffering like production Kafka.
```

---

## 8. Consumer Fetch Buffer

### How Consumer Fetching Works

Kafka consumers fetch messages in batches and buffer them locally:

```
  ┌──────────────────────────────────────────────────────────┐
  │              Consumer Fetch Buffer                       │
  │                                                          │
  │  ┌──────────────────────────────────────────────────┐   │
  │  │ Fetch Request:                                    │   │
  │  │   fetch.min.bytes = 1 (default)                   │   │
  │  │   fetch.max.bytes = 52428800 (50 MB)              │   │
  │  │   fetch.max.wait.ms = 500                         │   │
  │  │   max.partition.fetch.bytes = 1048576 (1 MB)      │   │
  │  └──────────────────────────────────────────────────┘   │
  │                                                          │
  │  ┌──────────────────────────────────────────────────┐   │
  │  │ Local Buffer (per partition):                     │   │
  │  │                                                    │   │
  │  │ P0: [msg100][msg101][msg102]...[msg599]  (500 msgs)│   │
  │  │ P1: [msg200][msg201]...[msg699]          (500 msgs)│   │
  │  │ P2: [msg300][msg301]...[msg799]          (500 msgs)│   │
  │  │                                                    │   │
  │  │ poll() returns max.poll.records=500 per call       │   │
  │  └──────────────────────────────────────────────────┘   │
  │                                                          │
  └──────────────────────────────────────────────────────────┘
```

### Fetch Timing

```
  Consumer                           Broker
     │                                  │
     │── Fetch(minBytes=1KB,           │
     │         maxWait=500ms) ────────▶│
     │                                  │
     │                                  │ If minBytes available:
     │◀── Response(records) ───────────│   respond immediately
     │                                  │
     │                                  │ If minBytes NOT available:
     │                                  │   wait up to maxWait
     │◀── Response(records or empty) ──│   then respond
     │                                  │
     │── poll(timeout=1000) ───────────│
     │   (local processing of          │
     │    buffered records)            │
```

---

## 9. Schema Cache

### Schema Registry Client Cache

Consumers and producers cache resolved schemas to avoid repeated HTTP calls
to the Schema Registry:

```
  ┌──────────────────────────────────────────────────────────┐
  │              Schema Cache (per client)                   │
  │                                                          │
  │  Schema ID Cache:                                        │
  │  ┌──────────┬───────────────────────────────────────┐   │
  │  │ ID: 1    │ Avro Schema: {"type":"record",...}    │   │
  │  │ ID: 2    │ Avro Schema: {"type":"record",...}    │   │
  │  │ ID: 42   │ Protobuf Descriptor: ...              │   │
  │  └──────────┴───────────────────────────────────────┘   │
  │                                                          │
  │  Subject-Version Cache:                                  │
  │  ┌──────────────────────┬───────────────────────────┐   │
  │  │ "orders-value" v1    │ Schema ID: 1              │   │
  │  │ "orders-value" v2    │ Schema ID: 42             │   │
  │  │ "payments-value" v1  │ Schema ID: 2              │   │
  │  └──────────────────────┴───────────────────────────┘   │
  │                                                          │
  │  Cache behavior:                                         │
  │  - Populated on first use (lazy loading)                │
  │  - Never evicted (schemas are immutable)                │
  │  - Thread-safe (ConcurrentHashMap)                      │
  │  - Survives for the lifetime of the client              │
  │  - Typically holds <100 schemas (small memory footprint)│
  └──────────────────────────────────────────────────────────┘
```

### Cache Hit Pattern

```
  First message with Schema ID 42:
    1. Check cache → miss
    2. HTTP GET /schemas/ids/42 → Schema Registry
    3. Store in cache
    4. Deserialize message

  All subsequent messages with Schema ID 42:
    1. Check cache → hit
    2. Deserialize message (no network call)

  Cache hit rate: >99.9% (schemas change rarely)
```

---

## 10. Broker-Side Caches

### Request Purgatory

Kafka brokers use a "purgatory" (delayed operation queue) that caches pending
requests waiting for conditions to be met:

```
  ┌──────────────────────────────────────────────────────────┐
  │              Broker Request Purgatory                     │
  │                                                          │
  │  Produce Purgatory (acks=all):                           │
  │  ┌──────────────────────────────────────────────────┐   │
  │  │ Request 1: waiting for ISR ack from broker-2     │   │
  │  │ Request 2: waiting for ISR ack from broker-1     │   │
  │  │ Request 3: timed out → error response            │   │
  │  └──────────────────────────────────────────────────┘   │
  │                                                          │
  │  Fetch Purgatory (fetch.min.bytes):                      │
  │  ┌──────────────────────────────────────────────────┐   │
  │  │ Fetch 1: waiting for 1KB data on partition-0     │   │
  │  │ Fetch 2: waiting for 1KB data on partition-3     │   │
  │  │ Fetch 3: fetch.max.wait.ms reached → respond now │   │
  │  └──────────────────────────────────────────────────┘   │
  │                                                          │
  │  Implementation: TimingWheel (hierarchical, O(1) insert) │
  └──────────────────────────────────────────────────────────┘
```

### ISR Tracking Cache

```
  ┌──────────────────────────────────────────────────────────┐
  │              ISR Cache (Controller)                      │
  │                                                          │
  │  Topic: orders                                           │
  │    P0: ISR=[0,1,2]  leader=0  epoch=5                   │
  │    P1: ISR=[1,2,0]  leader=1  epoch=3                   │
  │    P2: ISR=[2,0]    leader=2  epoch=7  ← broker-1 lagging│
  │                                                          │
  │  Updated when:                                           │
  │    - Follower falls behind (replica.lag.time.max.ms)     │
  │    - Follower catches up                                 │
  │    - Broker fails/recovers                               │
  │    - Unclean leader election                             │
  └──────────────────────────────────────────────────────────┘
```

---

## 11. Cache Anti-Patterns in Message Queues

### Anti-Pattern 1: Message Payload Cache

```
  ❌ DON'T: Cache individual messages in a HashMap for quick lookup

  // This is wasteful — messages are read sequentially, not randomly
  Map<Long, Message> messageCache = new ConcurrentHashMap<>();

  void onMessageAppend(Message msg) {
      messageCache.put(msg.getOffset(), msg);  // duplicate of page cache
  }

  Message getMessage(long offset) {
      return messageCache.get(offset);  // never called in practice
  }
```

### Anti-Pattern 2: Consumer-Side Message Buffer Beyond Fetch

```
  ❌ DON'T: Buffer consumed messages for "in case we need to re-read"

  // Messages are available in the commit log — re-read by seeking offset
  List<Message> processedMessages = new ArrayList<>();

  void onConsume(Message msg) {
      processedMessages.add(msg);  // growing unbounded!
      process(msg);
  }
```

### Anti-Pattern 3: JVM Heap for Log Segment Data

```
  ❌ DON'T: Load segment files into byte arrays on JVM heap

  // This defeats page cache — data is now in two places
  byte[] segmentData = Files.readAllBytes(segmentPath);
  // And creates GC pressure
```

### Anti-Pattern 4: Redis Cache in Front of Kafka

```
  ❌ DON'T: Put Redis between Kafka and consumers

  Producer → Kafka → Consumer → Redis → Application
                                  ↑
                           unnecessary hop

  ✅ DO: Read directly from Kafka — page cache handles hot data
  
  Producer → Kafka → Consumer → Application
```

### Anti-Pattern 5: Aggressive Metadata Cache TTL

```
  ❌ DON'T: Cache metadata with very short TTL

  // metadata.max.age.ms = 1000  ← 1 second refresh
  // Causes metadata request storms on large clusters
  // Each producer/consumer refreshes every second = N * 1/s requests

  ✅ DO: Use default (300000ms = 5 min) and rely on error-triggered refresh
  // Errors (NOT_LEADER_FOR_PARTITION) force immediate refresh
```

---

## 12. Simulation-to-Production Mapping

### Cache Layer Mapping

| Cache Layer | Simulation | Production Kafka |
|---|---|---|
| **Message storage** | `ArrayList<Message>` in `CommitLog` | OS page cache + disk segments |
| **Offset cache** | `ConcurrentHashMap` in `ConsumerGroupCoordinator` | In-memory cache backed by `__consumer_offsets` |
| **Metadata cache** | `InMemoryTopicRepository` | Client-side metadata cache + controller |
| **Batch buffer** | Not implemented (sync send) | RecordAccumulator with configurable batch/linger |
| **Fetch buffer** | Direct `CommitLog.read()` | Fetch purgatory + local consumer buffer |
| **Schema cache** | N/A (String payloads) | Schema Registry client cache |
| **ISR cache** | `Partition.inSyncReplicaIds` | Controller ISR tracking cache |
| **Zero-copy** | N/A (in-process) | `FileChannel.transferTo()` / `sendfile()` |
| **Dedup cache** | `ConcurrentHashMap` in `ExactlyOnceDeliveryStrategy` | Broker PID+sequence cache |

### What Our Simulation Gets Right

```
  1. Offset caching pattern:
     CommittedOffsets map in ConsumerGroupCoordinator matches
     production Kafka's in-memory offset cache exactly.

  2. No message-level caching:
     CommitLog stores messages but does not maintain a separate
     cache — mirrors Kafka's philosophy of letting the OS handle it.

  3. Metadata in memory:
     TopicRepository and BrokerRepository are in-memory stores,
     matching how Kafka clients cache metadata.

  4. ISR tracking:
     Partition.inSyncReplicaIds is the same data structure
     Kafka's controller maintains in memory.
```

---

## 13. Cloud-Specific Caching Considerations

### AWS MSK

```
  ┌──────────────────────────────────────────────────────────┐
  │              MSK Caching Considerations                  │
  │                                                          │
  │  Instance types with large memory for page cache:        │
  │  - kafka.m5.2xlarge: 32 GB RAM, ~26 GB for page cache  │
  │  - kafka.m5.4xlarge: 64 GB RAM, ~56 GB for page cache  │
  │  - kafka.m5.12xlarge: 192 GB RAM, ~180 GB page cache   │
  │                                                          │
  │  MSK Tiered Storage:                                     │
  │  - Cold data on S3 → no page cache for historical reads │
  │  - Hot data on EBS → page cache for recent reads        │
  │  - Reduces broker memory requirements for retention      │
  │                                                          │
  │  EBS volume types:                                       │
  │  - gp3: baseline 3,000 IOPS, up to 16,000 IOPS         │
  │  - io2: up to 256,000 IOPS (for extreme throughput)     │
  │  - Page cache reduces dependency on EBS IOPS             │
  └──────────────────────────────────────────────────────────┘
```

### AWS Kinesis Caching Model

```
  Kinesis is fully managed — no page cache to tune.

  GetRecords API:
  - Returns up to 10 MB per call
  - 5 reads/sec per shard (shared across consumers)
  - Enhanced Fan-Out: 2 MB/sec per consumer per shard (dedicated)

  Internal caching:
  - AWS manages caching internally
  - Data available within retention window
  - No user-controllable cache layer
```

### SQS Caching Considerations

```
  SQS has NO caching — it is a queue, not a log.

  But a common pattern combines SQS with caching:

  Producer → SQS → Consumer → Process → Cache result in DynamoDB/Redis
                                          ↑
                                   THIS is where caching helps
                                   (caching the processed result,
                                    not the raw message)
```

---

## 14. Performance Benchmarks

### Page Cache Hit Rate vs. Consumer Lag

```
  Consumer lag = how far behind the consumer is from the latest message

  Lag: 0-5 seconds (real-time consumer)
    Page cache hit rate: >99%
    Disk reads: near zero
    Throughput: limited by network, not disk

  Lag: 5-60 seconds (slightly behind)
    Page cache hit rate: 95-99%
    Disk reads: rare (only if cache evicted)
    Throughput: still network-limited

  Lag: 1-60 minutes (catching up)
    Page cache hit rate: 50-95%
    Disk reads: increasing
    Throughput: may become disk-limited

  Lag: >1 hour (replay / backfill)
    Page cache hit rate: <50%
    Disk reads: dominant
    Throughput: disk IOPS limited
    → This is when SSD vs. HDD matters most
```

### Zero-Copy Throughput Comparison

```
  Single partition, 1 MB messages, 100 GB transfer:

  With zero-copy (sendfile):
    Throughput: 620 MB/s
    CPU utilization: 12%
    Context switches: ~200K

  Without zero-copy (read+write):
    Throughput: 310 MB/s
    CPU utilization: 48%
    Context switches: ~800K

  With TLS (zero-copy disabled):
    Throughput: 280 MB/s
    CPU utilization: 62%
    Context switches: ~800K
```

### Batch Buffer Impact

```
  Producer sending 100K messages/sec, 100 bytes each:

  No batching (linger.ms=0, batch.size=1):
    Network calls: 100K/sec
    Throughput: 10 MB/s
    CPU: high (per-message overhead)

  Moderate batching (linger.ms=5, batch.size=16384):
    Network calls: 1K/sec
    Throughput: 10 MB/s
    CPU: moderate (batched overhead)
    Latency: +5ms p99

  Aggressive batching (linger.ms=50, batch.size=65536, compression=lz4):
    Network calls: 200/sec
    Throughput: 10 MB/s (but 4 MB/s on wire after compression)
    CPU: lower network, some compression CPU
    Latency: +50ms p99
```

---

## 15. Interview Deep Dive

### "What is Kafka's caching strategy?"

**Key answer:**

"Kafka intentionally does NOT cache messages at the application level. Instead, it delegates
caching entirely to the OS page cache. This is counterintuitive but brilliant for three reasons:

1. **Sequential access pattern** - Messages are written and read in order. The OS page cache
   with read-ahead is specifically optimized for sequential access.

2. **No double-caching** - If Kafka cached messages in JVM heap, the same data would exist in
   both the JVM and the page cache. This wastes memory and creates GC pressure.

3. **Survives restarts** - Page cache persists across JVM restarts. An application-level cache
   would be cold after every deployment.

What Kafka DOES cache is metadata: consumer offsets in a `ConcurrentHashMap` backed by the
`__consumer_offsets` topic, topic metadata on each client, and ISR state on the controller."

### "How does zero-copy work in Kafka?"

**Key answer:**

"When a consumer fetches data, Kafka uses Java's `FileChannel.transferTo()` which maps to the
Linux `sendfile()` syscall. This transfers data directly from the page cache to the network
socket without copying through user space. The result is 2 data copies (both DMA) instead of
4, and 2 context switches instead of 4. This roughly doubles throughput and reduces CPU usage
by 75% for data transfer operations.

There is one caveat: zero-copy does not work with TLS encryption because the broker must
encrypt data in user space before sending it to the socket."

### "Why doesn't Kafka use Redis for caching?"

**Key answer:**

"Adding Redis between Kafka and consumers would be counterproductive for several reasons:

1. Messages are consumed sequentially — cache hit rate for random access would be near zero.
2. The OS page cache already serves as a highly efficient cache for recent data.
3. Redis adds network latency (even sub-millisecond) that is unnecessary.
4. Redis doesn't understand Kafka offsets, consumer groups, or replication.

The only place caching is useful is for metadata (offsets, schemas, partition assignments)
which Kafka handles internally. Our simulation models this correctly — `ConsumerGroupCoordinator`
maintains an in-memory `ConcurrentHashMap` for committed offsets, mirroring exactly what
production Kafka does with the `__consumer_offsets` topic."

### "What SHOULD be cached in a message queue system?"

```
  Cache these (random access, small, high re-read rate):
    ✅ Consumer group offsets
    ✅ Topic/partition metadata (leader, ISR)
    ✅ Schema definitions (from Schema Registry)
    ✅ ACL/authorization rules
    ✅ Client quotas

  Don't cache these (sequential access, large, low re-read rate):
    ❌ Message payloads
    ❌ Message keys
    ❌ Log segment data
    ❌ Replication data
```

---

*This document covers the caching strategy for Project 20: Distributed Message Queue.*
*The central insight: message queues are one of the few systems where caching the core*
*data (messages) is actively harmful. Let the OS page cache handle it.*
