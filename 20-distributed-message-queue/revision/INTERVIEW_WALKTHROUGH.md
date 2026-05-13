# Distributed Message Queue -- Staff Engineer Interview Walkthrough

> **Target role:** Staff Engineer | **Time budget:** 35 minutes
> **Comparable systems:** Apache Kafka, Apache Pulsar, Amazon Kinesis, Redpanda
> **Codebase reference:** `com.systemdesign.messagequeue` (Project 20)

---

## TABLE OF CONTENTS

```
Phase 1 : Clarify Requirements .................. 2-3 min  (lines   31-190)
Phase 2 : High-Level Architecture ............... 5-7 min  (lines  192-451)
Phase 3 : Deep Dive -- Commit Log ............... 8-10 min (lines  453-960)
Phase 4 : Deep Dive -- Consumer Groups .......... 5-7 min  (lines  962-1378)
Phase 5 : Replication ........................... 3-5 min  (lines 1380-1653)
Phase 6 : Scaling ............................... 3-5 min  (lines 1655-1918)
Phase 7 : Edge Cases ............................ 2-3 min  (lines 1920-2153)
Appendix A : Design Patterns Cheat Sheet ........ (lines 2155-2182)
Appendix B : Complexity Cheat Sheet ............. (lines 2184-2214)
Appendix C : Quick-Fire Q&A Bank ................ (lines 2216-2305)
Appendix D : Kafka vs Pulsar vs Kinesis ......... (lines 2307-2358)
Appendix E : Whiteboard Drawing Order ........... (lines 2360-2403)
Appendix F : Anti-Patterns to Avoid ............. (lines 2405-2482)
Appendix G : Interview Timing Cheat Sheet ....... (lines 2484-2552)
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
checklist. Group them into three buckets: scale, semantics, and
durability.

#### Bucket 1 -- Scale & Throughput

```
Q1: "What's the expected message throughput -- are we talking
     10K messages/sec or millions of messages/sec?"
     WHY: 10K msg/s = single broker cluster with a few partitions.
          1M+ msg/s = multi-broker cluster, aggressive batching,
          and zero-copy transfer become critical. This determines
          the number of partitions and brokers on day one.

Q2: "What's the average message size -- kilobytes or megabytes?"
     WHY: Small messages (< 1 KB) = throughput is the bottleneck,
          batch aggressively. Large messages (> 1 MB) = memory and
          disk I/O become the bottleneck, need chunking or external
          blob storage with pointer messages.

Q3: "How many topics and partitions do we expect -- tens or
     thousands?"
     WHY: Tens = ZooKeeper-based metadata management is fine.
          Thousands = metadata overhead becomes significant, need
          to consider KRaft (Kafka's Raft-based controller) or
          a dedicated metadata store. Each partition is a file
          handle on the broker.

Q4: "How many consumer groups will read from the same topic?"
     WHY: One group = simple offset tracking. Hundreds = each
          group maintains independent offsets, and rebalancing
          storms become a real concern. Fan-out pattern matters
          for retention planning.
```

#### Bucket 2 -- Ordering & Delivery Semantics

```
Q5: "What ordering guarantees do consumers need -- total order,
     per-partition order, or no ordering?"
     WHY: Total order = single partition (kills parallelism).
          Per-partition order = standard Kafka model (partition by
          key). No ordering = maximum parallelism, round-robin
          publishing. This is THE fundamental tradeoff.

Q6: "What delivery semantics -- at-most-once, at-least-once, or
     exactly-once?"
     WHY: At-most-once = fire and forget, no acks, fastest.
          At-least-once = ack after processing, consumer may see
          duplicates (most common production choice).
          Exactly-once = idempotent producer + transactional
          consumer, significant overhead (20-30% throughput hit).

Q7: "Should the queue support publish-subscribe (fan-out) or
     point-to-point (competing consumers), or both?"
     WHY: Pub-sub = multiple consumer groups, each gets all messages.
          Point-to-point = single consumer group, messages load-
          balanced across consumers. Both = Kafka's native model.
          Determines how offsets are tracked.

Q8: "Are there priority levels for messages, or is FIFO within
     a partition sufficient?"
     WHY: Priority queues fundamentally conflict with append-only
          commit log design. If priorities are needed, we either
          use separate topics per priority or a different system
          (RabbitMQ). For Kafka-style design, FIFO is assumed.
```

#### Bucket 3 -- Durability & Retention

```
Q9: "How long should messages be retained -- hours, days, or
     indefinitely?"
     WHY: Hours = aggressive segment cleanup, less disk needed.
          Days/weeks = standard Kafka default (7 days). Indefinite
          = need log compaction (keep latest value per key) instead
          of time-based deletion. Retention drives storage capacity
          planning.

Q10: "What's the durability requirement -- can we lose messages
      if a single broker dies?"
      WHY: No loss = replication factor >= 3, acks=all, min.insync
           .replicas=2. Acceptable loss = acks=1 (leader only),
           faster writes. This determines replication strategy and
           directly impacts write latency.
```

### Clarified scope (write on whiteboard/doc)

After hearing answers, summarize aloud:

```
+--------------------------------------+--------------------------------------+
|            IN SCOPE                  |           OUT OF SCOPE               |
+--------------------------------------+--------------------------------------+
| Distributed commit log (append-only) | Priority queues                     |
| Topic-based pub/sub + consumer groups| Message transformation / routing    |
| Per-partition ordering guarantees    | Dead letter queue (mention briefly) |
| At-least-once default delivery       | Schema registry (mention briefly)   |
| Exactly-once option (idempotent +    | Multi-datacenter replication        |
|   transactional)                     | AMQP / RabbitMQ-style ack per msg   |
| ISR-based replication (RF=3)         | Tiered storage (hot/cold)           |
| Time-based retention + log compaction| Streaming SQL / KSQL                |
| Partition-based horizontal scaling   | Exactly-once across systems (Saga)  |
| Consumer group rebalancing           | Message encryption at rest          |
+--------------------------------------+--------------------------------------+
```

```
TALKING POINT:
"I'll design a distributed message queue similar to Apache Kafka.
The core abstraction is an append-only commit log partitioned
across brokers. Producers publish to topics, messages are ordered
within partitions, and consumer groups provide both fan-out and
load-balanced consumption. I'll target 1M messages/sec with
at-least-once delivery, replication factor 3, and 7-day retention.
Let me draw the high-level architecture."
```

### Common follow-up questions for Phase 1

```
Q: "What if the interviewer says 'just design whatever you think
    is right'?"
A: Default to this scope: 1M msg/s, 1 KB average message, 100
   topics with 10 partitions each, RF=3, at-least-once delivery,
   7-day retention, 5 consumer groups per topic, per-partition
   ordering. This covers 90% of real-world Kafka deployments.

Q: "Should I mention Kafka by name?"
A: Yes, briefly: "This is similar to Apache Kafka's architecture,
   but I'll design from first principles." Shows awareness
   without name-dropping.

Q: "What if they ask why not RabbitMQ or SQS?"
A: "RabbitMQ is a traditional message broker -- push-based,
   message-level acks, broker tracks what each consumer has seen.
   Great for task queues, but doesn't scale to millions of msg/s.
   SQS is managed but lacks ordering guarantees across partitions
   and doesn't support replay. Kafka's commit log model gives us
   ordered, durable, replayable streams with consumer-side offset
   tracking, which scales better for high-throughput event
   streaming."
```

---
---

## PHASE 2: HIGH-LEVEL ARCHITECTURE (5-7 min)

### What this phase signals to the interviewer

```
STAFF SIGNAL: You name every major component, draw data flow
arrows showing the write path and read path separately, and
immediately call out the role of the controller / coordinator.
You distinguish between the data plane (brokers) and the control
plane (controller, ZooKeeper/KRaft). Juniors draw "producer ->
queue -> consumer" and stop.
```

### Core Components

Present the system as five major components. Name each one and
state its responsibility in one sentence before drawing.

```
1. PRODUCER
   - Client library that serializes messages, selects a partition
     (by key hash or round-robin), and batches messages for
     network efficiency.
   - "Producers are dumb pipes -- they don't store state. They
     batch, compress, and fire."

2. BROKER (Commit Log)
   - Stateful server that stores messages in an append-only commit
     log on disk. Each broker hosts a subset of partitions.
   - "The broker is a glorified append-only file server. It never
     modifies written data -- only appends and eventually deletes
     old segments."

3. TOPIC / PARTITION
   - A topic is a logical stream. It's split into N partitions for
     parallelism. Each partition is an ordered, immutable sequence
     of messages with monotonically increasing offsets.
   - "Partitions are the unit of parallelism AND the unit of
     replication. Everything in this design revolves around
     partitions."

4. CONSUMER GROUP
   - A set of consumer instances that cooperate to consume a topic.
     Each partition is assigned to exactly one consumer in the
     group. Multiple groups can independently consume the same
     topic (fan-out).
   - "The consumer group is Kafka's killer feature. It gives you
     both pub-sub AND competing-consumer in one abstraction."

5. CONTROLLER (Coordinator)
   - A broker elected as the controller manages partition
     leadership, broker membership, and consumer group rebalancing.
     In classic Kafka: ZooKeeper. In modern Kafka: KRaft.
   - "The controller is the cluster's brain. It decides which
     broker leads each partition and triggers rebalancing when
     brokers or consumers join/leave."
```

### ASCII Architecture Diagram (draw this)

```
               DISTRIBUTED MESSAGE QUEUE (Kafka-style)
  =====================================================================

  PRODUCERS                                     CONSUMER GROUPS
  =========                                     ===============

  +----------+   +----------+   +----------+   Group A (order-service)
  | Producer |   | Producer |   | Producer |   +--------+ +--------+
  |    1     |   |    2     |   |    3     |   |Consumer| |Consumer|
  | (batch,  |   | (batch,  |   | (batch,  |   |  A1    | |  A2    |
  |  compress|   |  compress|   |  compress|   +---+----+ +---+----+
  |  select  |   |  select  |   |  select  |       |          |
  |  partition)  |  partition)  |  partition)       |          |
  +-----+----+   +-----+----+   +-----+----+       |          |
        |               |             |             |          |
        +---------------+-------------+             |          |
                        |                           |          |
                   WRITE PATH                  READ PATH       |
                        |                           |          |
  ===================== | ========================= | ======== | ===
                        v                           |          |
              BROKER CLUSTER                        |          |
  +---------------------------------------------------+       |
  |                                                   |        |
  |  +-------------+  +-------------+  +-------------+|       |
  |  |  Broker 1   |  |  Broker 2   |  |  Broker 3   ||       |
  |  |             |  |             |  |             ||        |
  |  | Topic-A     |  | Topic-A     |  | Topic-A     ||       |
  |  |  P0 (Leader)|  |  P1 (Leader)|  |  P2 (Leader)||       |
  |  |  P1 (Replica|  |  P2 (Replica|  |  P0 (Replica||       |
  |  |  P2 (Replica|  |  P0 (Replica|  |  P1 (Replica||       |
  |  |             |  |             |  |             ||        |
  |  | Commit Log: |  | Commit Log: |  | Commit Log: ||       |
  |  | [seg0][seg1]|  | [seg0][seg1]|  | [seg0][seg1]||       |
  |  +------+------+  +------+------+  +------+------+|       |
  |         |                |                |        |       |
  +---------------------------------------------------+       |
            |                |                |                |
            +----------------+----------------+                |
                             |                                 |
                     CONTROLLER (KRaft)                        |
  +---------------------------------------------------+       |
  |                                                   |        |
  |  +---------------------+  +---------------------+ |       |
  |  | Metadata:           |  | Coordination:       | |       |
  |  |  - Partition map    |  |  - Leader election  | |       |
  |  |  - Broker liveness  |  |  - Rebalancing      | |       |
  |  |  - ISR lists        |  |  - Config changes   | |       |
  |  +---------------------+  +---------------------+ |       |
  |                                                   |        |
  +---------------------------------------------------+       |
                                                               |
  Group B (analytics-service)                                  |
  +--------+ +--------+ +--------+                            |
  |Consumer| |Consumer| |Consumer| <------ reads same topic---+
  |  B1    | |  B2    | |  B3    |         at independent
  +--------+ +--------+ +--------+         offsets
  =====================================================================
```

### What to say while drawing

```
"Let me walk through the architecture by tracing a message from
producer to consumer.

WRITE PATH:
 1. The producer serializes the message, applies a partitioning
    strategy (hash of message key mod partition count, or round-
    robin if no key), and adds the message to a local batch buffer.

 2. When the batch is full OR a linger timer expires (default 5ms),
    the producer sends the batch over TCP to the LEADER broker for
    that partition. Batching amortizes network overhead -- one TCP
    round-trip for thousands of messages.

 3. The leader broker appends the batch to the commit log for that
    partition. This is a sequential disk write -- no random I/O.
    Sequential writes to modern SSDs achieve 500 MB/s easily.

 4. If acks=all, the leader waits for all in-sync replicas (ISR)
    to replicate the batch before sending an ack to the producer.
    If acks=1, it acks immediately after local write.

READ PATH:
 5. Each consumer in a group is assigned a subset of partitions
    by the group coordinator. Consumer A1 might own partitions
    P0 and P1, while Consumer A2 owns P2.

 6. The consumer sends a fetch request to the leader broker for
    each assigned partition, specifying its current offset: 'give
    me messages starting at offset 42.'

 7. The broker reads from the commit log and sends the data back.
    If the data is still in the OS page cache (hot path), this
    uses sendfile() -- zero-copy transfer from page cache to
    network socket, bypassing user-space entirely.

 8. The consumer processes the batch and commits the new offset
    back to the broker (stored in an internal __consumer_offsets
    topic). On restart, the consumer resumes from the last
    committed offset.

CONTROLLER PATH:
 9. The controller maintains a metadata log of all partition
    assignments, leader elections, and ISR changes. In KRaft
    mode, this is itself a replicated Raft log -- no external
    ZooKeeper dependency.

10. When a broker crashes, the controller detects the failure via
    heartbeat timeout, removes it from ISR lists, and elects new
    leaders for any partitions that broker was leading."
```

### Why the commit log model matters

```
TALKING POINT:
"The append-only commit log is the single most important design
decision. It gives us four properties that traditional message
brokers don't have:

 1. SEQUENTIAL I/O ONLY: Writes are always sequential appends.
    Reads are sequential scans from an offset. No random disk
    access. This is why Kafka on spinning disks outperforms
    RabbitMQ on SSDs for throughput.

 2. CONSUMER INDEPENDENCE: The broker doesn't track per-consumer
    state. Each consumer tracks its own offset. This means adding
    a new consumer group has zero impact on broker performance.
    RabbitMQ's broker must track per-consumer ack state.

 3. REPLAYABILITY: Messages aren't deleted after consumption.
    They're retained for a configured period (or indefinitely
    with compaction). Any consumer can rewind to any offset and
    reprocess. This is critical for debugging and reprocessing
    after a bug fix.

 4. SIMPLICITY: The broker is essentially a distributed filesystem
    for sequential log files. No complex routing, no priority
    queues, no per-message ack tracking. This simplicity is what
    enables millions of messages per second."
```

### Interviewer signals and transitions

```
POSITIVE SIGNALS:
 - Interviewer nods when you separate write path from read path
 - They ask "tell me more about the commit log" -- go to Phase 3
 - They ask "how do consumer groups work" -- go to Phase 4
 - They ask "what happens when a broker dies" -- go to Phase 5

NEGATIVE SIGNALS:
 - "What about message ordering?" -- you didn't mention per-partition
   ordering. Clarify immediately.
 - "How is this different from a database?" -- explain: no random
   reads, no updates, no indexes, append-only, optimized for
   sequential throughput.

TRANSITION:
"The commit log is the heart of this system. Let me dive into how
it's structured on disk -- segment files, indexes, and the zero-copy
optimization that makes this fast."
```

### Common follow-up questions for Phase 2

```
Q: "Why not use a database as the message store?"
A: "A database is optimized for random reads/writes with indexes
   and B-trees. Our workload is 100% sequential -- append on write,
   sequential scan on read. A commit log on raw disk eliminates the
   overhead of a query planner, transaction manager, and buffer
   pool. Kafka achieves 800 MB/s throughput per broker precisely
   because it bypasses all that machinery and writes directly to
   the filesystem, leveraging the OS page cache."

Q: "Where does ZooKeeper / KRaft fit?"
A: "ZooKeeper (legacy) or KRaft (modern) serves as the controller.
   It stores metadata: which broker leads which partition, ISR
   membership, topic configs, and consumer group coordination.
   It does NOT store messages. KRaft is preferred because it
   eliminates the operational burden of a separate ZooKeeper
   cluster and embeds Raft consensus directly in the broker
   process."

Q: "How many brokers do we need?"
A: "Back-of-envelope: 1M msg/s * 1 KB = 1 GB/s write throughput.
   Each broker handles ~200 MB/s sustained writes. So 5 brokers
   for writes alone. With RF=3, each message is written 3 times,
   so 3 GB/s total disk throughput across the cluster. We need
   at least 15 brokers, but I'd start with 9 and add more as
   needed. Partitions should be 3-5x the broker count for
   balanced load distribution."
```

---
---

## PHASE 3: DEEP DIVE -- COMMIT LOG (8-10 min)

### What this phase signals to the interviewer

```
STAFF SIGNAL: You understand the storage engine at the systems level.
You explain HOW data is laid out on disk, WHY sequential I/O matters,
and the specific OS-level optimizations (page cache, sendfile/zero-copy)
that make the system fast. You can reason about segment files, index
files, and the tradeoffs of segment size. This separates staff
engineers from those who only know the API.
```

### Commit Log Structure

```
"A partition's commit log is not a single file. It's a series of
segment files, each with a configurable max size (default 1 GB).
Let me draw the on-disk layout."
```

#### ASCII Diagram: On-Disk Layout

```
  PARTITION P0 ON BROKER 1 (Leader)
  ===================================

  Directory: /data/kafka-logs/topic-A-0/

  SEGMENT FILES (append-only, immutable once rolled)
  ┌─────────────────────────────────────────────────────────────┐
  │                                                             │
  │  00000000000000000000.log    <- Segment 0 (offsets 0-9999)  │
  │  00000000000000000000.index  <- Sparse offset index         │
  │  00000000000000000000.timeindex <- Time-based index          │
  │                                                             │
  │  00000000000000010000.log    <- Segment 1 (offsets 10000-...) │
  │  00000000000000010000.index                                 │
  │  00000000000000010000.timeindex                             │
  │                                                             │
  │  00000000000000020000.log    <- Segment 2 (ACTIVE segment)  │
  │  00000000000000020000.index                                 │
  │  00000000000000020000.timeindex                             │
  │                                                             │
  └─────────────────────────────────────────────────────────────┘

  SEGMENT FILE NAMING:
  - File name = base offset (first message offset in this segment)
  - Segments 0 and 1 are SEALED (read-only)
  - Segment 2 is ACTIVE (new writes appended here)
  - When active segment reaches 1 GB (or max.segment.bytes),
    it's sealed and a new active segment is created (ROLL)

  INSIDE A .log FILE:
  ┌────────────────────────────────────────────────────────┐
  │ Record Batch 1                                         │
  │ ┌──────────────────────────────────────────────────┐   │
  │ │ Base Offset: 20000                               │   │
  │ │ Batch Length: 3 messages                          │   │
  │ │ CRC32: 0xABCDEF12                                │   │
  │ │ Magic Byte: 2 (record batch format v2)           │   │
  │ │ Compression: Snappy                              │   │
  │ │ Timestamp Type: CreateTime                       │   │
  │ │ Producer ID: 42  (for idempotent dedup)          │   │
  │ │ Producer Epoch: 3                                │   │
  │ │ Base Sequence: 100 (for ordering guarantee)      │   │
  │ │ ┌──────────────────────────────────────────────┐ │   │
  │ │ │ Record 0: offset=20000, key="user-123",     │ │   │
  │ │ │   value=<bytes>, headers=[("trace-id","x")] │ │   │
  │ │ │ Record 1: offset=20001, key="user-456", ... │ │   │
  │ │ │ Record 2: offset=20002, key="user-789", ... │ │   │
  │ │ └──────────────────────────────────────────────┘ │   │
  │ └──────────────────────────────────────────────────┘   │
  │                                                        │
  │ Record Batch 2                                         │
  │ ┌──────────────────────────────────────────────────┐   │
  │ │ Base Offset: 20003                               │   │
  │ │ ...                                              │   │
  │ └──────────────────────────────────────────────────┘   │
  └────────────────────────────────────────────────────────┘
```

### What to say about segment files

```
TALKING POINT:
"Three key design decisions in the segment file structure:

 1. IMMUTABLE SEGMENTS: Once a segment is sealed, it's never
    modified. Only the active segment receives appends. This
    means sealed segments can be safely:
    - Memory-mapped for reads without locks
    - Replicated to followers without coordination
    - Deleted for retention without affecting writes

 2. FILE NAMING BY OFFSET: The file name IS the base offset.
    To find offset 15,000, I binary-search the file names to
    find segment 10000.log (since 10000 <= 15000 < 20000),
    then consult the sparse index within that segment.

 3. RECORD BATCH FORMAT: Messages are grouped into record batches
    at the producer level. The batch is the unit of compression,
    CRC validation, and replication. This means one CRC check
    per batch (not per message), and compression works across
    multiple messages (better ratio than per-message compression)."
```

### Offset Index Deep Dive

```
OFFSET INDEX (.index file)
============================

The index is a SPARSE index -- it does not contain every offset.
It stores one entry per index.interval.bytes (default 4 KB) of
log data. This keeps the index small enough to be memory-mapped.

Index File Structure:
┌────────────────────────────────────────┐
│ Relative Offset | Physical Position   │
├────────────────────────────────────────┤
│        0        |        0            │  <- offset 10000 is at byte 0
│       50        |     4096            │  <- offset 10050 is at byte 4096
│      100        |     8192            │  <- offset 10100 is at byte 8192
│      150        |    12288            │  <- offset 10150 is at byte 12288
│      ...        |     ...             │
└────────────────────────────────────────┘

LOOKUP ALGORITHM for offset 10075:
 1. Binary search file names: segment 10000.log contains it
 2. Binary search the .index: find entry (50, 4096) since
    10050 <= 10075
 3. Sequential scan from byte 4096 in the .log file until
    we find offset 10075
 4. Total: O(log N) binary search + small sequential scan

"The sparse index means the .index file for a 1 GB segment is
only ~5 MB. All index files can live in memory (mmap'd). The
actual message data stays on disk and is served from the OS
page cache when hot, or read from disk when cold."

TIME INDEX (.timeindex file)
============================

Same sparse structure, but maps timestamp -> offset.
Used for: consumer.offsetsForTimes() -- "give me the offset
of the first message after timestamp T."

┌────────────────────────────────────────┐
│    Timestamp    | Relative Offset     │
├────────────────────────────────────────┤
│  1704067200000  |        0            │  <- Jan 1 00:00 UTC
│  1704067260000  |       50            │  <- Jan 1 00:01 UTC
│  1704067320000  |      100            │  <- Jan 1 00:02 UTC
└────────────────────────────────────────┘

Use case: "Replay all messages from yesterday at 3pm" without
scanning the entire log. O(log N) lookup.
```

### Zero-Copy Transfer (sendfile)

```
"The single most important performance optimization in the
entire system. Let me draw the difference between a traditional
read and a zero-copy read."

TRADITIONAL READ PATH (4 copies, 4 context switches):
=====================================================

  Application                   Kernel
  ┌──────────┐                 ┌──────────────┐
  │          │  1. read()      │              │
  │  Broker  │ ─────────────>  │  Kernel      │
  │  Process │                 │  reads disk  │
  │          │  2. copy to     │  into page   │
  │  App     │ <─────────────  │  cache       │
  │  Buffer  │                 │              │
  │          │  3. write()     │              │
  │          │ ─────────────>  │  Copy from   │
  │          │                 │  app buffer  │
  │          │                 │  to socket   │
  │          │                 │  buffer      │
  │          │                 │              │
  └──────────┘                 │  4. DMA to   │
                               │  NIC         │
                               └──────────────┘

  COPIES: disk -> page cache -> app buffer -> socket buffer -> NIC
  = 4 copies, 4 user/kernel context switches

ZERO-COPY PATH (sendfile, 2 copies, 2 context switches):
=========================================================

  Application                   Kernel
  ┌──────────┐                 ┌──────────────┐
  │          │  1. sendfile()  │              │
  │  Broker  │ ─────────────>  │  Kernel      │
  │  Process │                 │  reads disk  │
  │          │                 │  into page   │
  │  (NO app │                 │  cache       │
  │   buffer │                 │              │
  │   needed)│                 │  2. DMA from │
  │          │                 │  page cache  │
  │          │                 │  directly to │
  │          │                 │  NIC         │
  │          │                 │              │
  └──────────┘                 └──────────────┘

  COPIES: disk -> page cache -> NIC
  = 2 copies, 2 context switches
  Data NEVER enters user-space.

PERFORMANCE IMPACT:
  - Traditional: ~400 MB/s per broker (CPU-bound on copies)
  - Zero-copy:   ~800+ MB/s per broker (disk/network-bound)
  - 2x throughput improvement, lower CPU utilization
  - Java NIO: FileChannel.transferTo() wraps sendfile()
```

### Page Cache Strategy

```
TALKING POINT:
"Kafka deliberately does NOT manage its own buffer pool or cache.
Instead, it relies entirely on the OS page cache. Here's why:

 1. JVM HEAP AVOIDANCE: A 64 GB broker with a 6 GB JVM heap
    has 58 GB available for the OS page cache. If Kafka managed
    its own cache in-heap, it would fight GC. By staying out of
    heap, all 58 GB serve as read-ahead cache.

 2. WARM RESTARTS: When a broker process restarts (JVM restart,
    rolling upgrade), the page cache survives because it's kernel
    memory. Consumers reading recent data see zero performance
    impact. With an in-process cache, a restart means cold cache.

 3. WRITE-BEHIND: Writes go to page cache first (fast), then
    the OS flushes to disk asynchronously (fsync on segment roll
    or at configurable intervals). This means writes at memory
    speed, not disk speed.

 4. READ-AHEAD: The OS detects sequential access patterns and
    pre-fetches upcoming segments into cache. Since our reads
    are always sequential (offset-based scan), the OS does
    exactly the right thing without any application hint."
```

### Segment Lifecycle

```
SEGMENT LIFECYCLE DIAGRAM:

  ACTIVE                    SEALED                  DELETED
  ┌──────┐   segment roll   ┌──────┐  retention    ┌──────┐
  │      │  (size/time hit)  │      │  policy       │      │
  │ Write│ ──────────────>   │ Read │ ──────────>   │ GC'd │
  │ + Read│                  │ Only │               │      │
  │      │                   │      │               │      │
  └──────┘                   └──────┘               └──────┘

  RETENTION POLICIES:
  ┌─────────────────────────────────────────────────────────┐
  │ Policy              │ Config                 │ Default  │
  ├─────────────────────────────────────────────────────────┤
  │ Time-based delete   │ log.retention.hours    │ 168 (7d) │
  │ Size-based delete   │ log.retention.bytes    │ -1 (none)│
  │ Log compaction      │ log.cleanup.policy     │ delete   │
  │ Segment size        │ log.segment.bytes      │ 1 GB     │
  │ Segment roll time   │ log.roll.hours         │ 168 (7d) │
  └─────────────────────────────────────────────────────────┘

  "Retention deletes entire sealed segments, not individual
  messages. This is O(1) -- just delete the file. No scanning,
  no compaction, no garbage collection. Simple and fast."
```

### Log Compaction Deep Dive

```
LOG COMPACTION (for changelog topics):
======================================

"Log compaction is an alternative to time-based deletion. Instead
of deleting old segments by age, it keeps the LATEST value for
each unique key. This is used for changelog topics -- e.g.,
__consumer_offsets, KTable changelogs, or any 'latest state' topic."

BEFORE COMPACTION:
  Offset:  0    1    2    3    4    5    6    7    8    9
  Key:     A    B    A    C    B    A    C    D    A    B
  Value:   v1   v1   v2   v1   v2   v3   v2   v1   v4   v3

AFTER COMPACTION:
  Offset:  7    8    9    6
  Key:     D    A    B    C
  Value:   v1   v4   v3   v2

  "Only the latest value for each key survives. Old values are
  removed. Offsets are preserved (not re-numbered). This gives
  us a 'materialized view' of the latest state per key."

TOMBSTONES:
  - A message with a key but NULL value is a tombstone.
  - During compaction, the tombstone deletes the key entirely.
  - Tombstones are retained for delete.retention.ms (default 24h)
    so that downstream consumers see the delete before it
    disappears.

COMPACTION THREAD:
  - Runs in the background on each broker.
  - Picks the segment with the highest 'dirty ratio' (ratio of
    duplicate keys to total keys).
  - Rewrites the segment in-place, removing old values.
  - CPU and I/O intensive -- throttled to avoid impacting
    live traffic (log.cleaner.io.max.bytes.per.second).
```

### What to say about write path performance

```
TALKING POINT:
"Let me quantify why sequential writes are fast. A modern NVMe
SSD does:
  - Random 4 KB writes: ~100K IOPS = 400 MB/s
  - Sequential writes:  ~3,000 MB/s

That's a 7.5x difference. And with spinning disks (still common
in Kafka clusters), the difference is 100x:
  - Random writes: ~200 IOPS = 0.8 MB/s
  - Sequential writes: ~200 MB/s

By restricting ourselves to sequential-only I/O, we get
database-grade durability at filesystem-grade throughput."
```

### Flush Strategy (fsync)

```
"An important subtlety: when does data become durable on disk?"

KAFKA'S DEFAULT: NO APPLICATION-LEVEL FSYNC
  - Kafka writes to the OS page cache and does NOT call fsync()
    per message or per batch (by default).
  - The OS flushes dirty pages to disk asynchronously (every
    ~30 seconds by default, tunable via vm.dirty_writeback_centisecs).
  - If the machine loses power before flush, data in page cache
    is LOST.

  "This sounds dangerous, but replication is our durability
  guarantee, not fsync. With acks=all and RF=3, a message is in
  the page cache of 3 different machines. All 3 losing power
  simultaneously is a once-in-a-decade event. Meanwhile, calling
  fsync per batch would reduce throughput from 800 MB/s to
  ~50 MB/s."

CONFIGURABLE FSYNC (for paranoid deployments):
  log.flush.interval.messages = 1000
    -> fsync every 1000 messages (still rare)
  log.flush.interval.ms = 10000
    -> fsync every 10 seconds

  "I've seen financial systems set log.flush.interval.messages=1
  with fast NVMe drives. Throughput drops to ~200 MB/s but they
  get single-machine durability on top of replication. For most
  systems, this is overkill."

BATTERY-BACKED WRITE CACHE (BBWC):
  - Enterprise servers have battery-backed RAID controllers
  - Write to controller cache = durable (survives power loss)
  - Controller flushes to disk asynchronously
  - "With BBWC, even acks=1 is effectively durable on the
    leader. The battery holds the cache through power outage."
```

### Write Path: End-to-End Trace

```
"Let me trace a single message from producer to disk, showing
every step and the associated latency."

END-TO-END WRITE TRACE:
  ┌──────────────────────────────────────────────────────────┐
  │ Step                          │ Latency  │ Where         │
  ├──────────────────────────────────────────────────────────┤
  │ 1. Serialize message (Avro)   │ ~0.01 ms │ Producer JVM  │
  │ 2. Select partition (hash)    │ ~0.001ms │ Producer JVM  │
  │ 3. Add to RecordAccumulator   │ ~0.001ms │ Producer JVM  │
  │ 4. Batch fills or linger.ms   │ 0-5 ms   │ Producer JVM  │
  │    expires                    │          │               │
  │ 5. Compress batch (LZ4)       │ ~0.1 ms  │ Producer JVM  │
  │ 6. TCP send to leader broker  │ ~0.5 ms  │ Network       │
  │ 7. Broker: validate CRC,     │ ~0.05 ms │ Broker JVM    │
  │    assign offsets              │          │               │
  │ 8. Append to page cache       │ ~0.01 ms │ Broker OS     │
  │ 9. (acks=all) Wait for ISR   │ ~1-3 ms  │ Network + IO  │
  │    followers to fetch + ack   │          │               │
  │ 10. Send ProduceResponse      │ ~0.1 ms  │ Network       │
  │    to producer                │          │               │
  ├──────────────────────────────────────────────────────────┤
  │ TOTAL (acks=1):               │ ~1-2 ms  │               │
  │ TOTAL (acks=all):             │ ~2-8 ms  │               │
  └──────────────────────────────────────────────────────────┘

  "Notice: the broker does almost no work. Serialize, compress,
  and partition are all on the producer. The broker just validates
  CRC, assigns monotonic offsets, and appends bytes to page cache.
  This is why Kafka brokers can handle millions of messages per
  second -- they're doing almost nothing per message."
```

### Read Path: End-to-End Trace

```
END-TO-END READ TRACE:
  ┌──────────────────────────────────────────────────────────┐
  │ Step                          │ Latency  │ Where         │
  ├──────────────────────────────────────────────────────────┤
  │ 1. Consumer sends FetchRequest│ ~0.5 ms  │ Network       │
  │    (partition, offset, max    │          │               │
  │     bytes)                    │          │               │
  │ 2. Broker: lookup segment    │ ~0.001ms │ Broker JVM    │
  │    file by offset             │          │               │
  │ 3. Broker: lookup position   │ ~0.01 ms │ Memory (mmap) │
  │    in sparse index            │          │               │
  │ 4a. HOT PATH: sendfile()     │ ~0.1 ms  │ Kernel (zero  │
  │     from page cache to NIC   │          │ copy)          │
  │ 4b. COLD PATH: disk read     │ ~5-50 ms │ Disk I/O      │
  │     into page cache, then    │          │               │
  │     sendfile to NIC           │          │               │
  │ 5. Consumer receives batch   │ ~0.5 ms  │ Network       │
  │ 6. Decompress (LZ4)          │ ~0.1 ms  │ Consumer JVM  │
  │ 7. Deserialize (Avro)        │ ~0.05 ms │ Consumer JVM  │
  ├──────────────────────────────────────────────────────────┤
  │ TOTAL (hot):                  │ ~1-2 ms  │               │
  │ TOTAL (cold):                 │ ~10-60ms │               │
  └──────────────────────────────────────────────────────────┘

  "Hot reads (within page cache) are 5-50x faster than cold reads.
  For real-time consumers that keep up with producers, nearly all
  reads are hot. For replay consumers reading days-old data, reads
  go through disk. This is why tailing consumers and replay
  consumers should be in separate consumer groups -- mixing them
  can evict hot data from page cache."
```

### Interviewer signals and transitions

```
POSITIVE SIGNALS:
 - "That's a good explanation of zero-copy" -- they're satisfied
 - "How does this interact with replication?" -- go to Phase 5
 - "What about consumer-side?" -- go to Phase 4

NEGATIVE SIGNALS:
 - "What's the actual byte layout?" -- they want more depth.
   Explain the record batch header: magic byte, CRC, attributes
   (compression codec), timestamp delta encoding.
 - "Why not mmap for writes?" -- explain: mmap writes go through
   page cache anyway, but writev() + explicit fsync gives the
   broker control over flush timing. mmap hides flush semantics.

TRANSITION:
"Now that we understand how data is stored, let me explain how
consumers read it -- specifically, how consumer groups coordinate
to divide partitions among themselves."
```

### Common follow-up questions for Phase 3

```
Q: "What happens if the active segment is corrupted?"
A: "Every record batch has a CRC32 checksum. On read, the broker
   validates the CRC. If corruption is detected, it truncates the
   log back to the last valid offset and fetches the missing data
   from a replica. The replica's log is the source of truth. This
   is why RF >= 2 is mandatory for production."

Q: "How big should segments be?"
A: "Default 1 GB is good for most workloads. Smaller segments
   (256 MB) mean more frequent segment rolls, more file handles,
   but faster retention cleanup (granularity of deletion is one
   segment). Larger segments (2 GB) mean fewer files but coarser
   retention. For a topic with 100 partitions on a broker, that's
   100 active segments = 100 file handles, which is fine."

Q: "Why not use RocksDB or LevelDB instead of raw files?"
A: "We don't need the features they provide. RocksDB gives you
   point lookups, range scans, and compaction for key-value data.
   Our workload is purely sequential: append at the tail, read
   from an offset forward. A raw append-only file is the fastest
   possible storage for this access pattern. Adding LSM-tree
   overhead (write amplification, compaction) would only slow
   us down."

Q: "How does compression work?"
A: "Compression is applied per record batch, not per message.
   The producer compresses the batch (Snappy, LZ4, Zstd, or Gzip),
   and the broker stores the compressed batch as-is. The broker
   NEVER decompresses during writes or replication. Only the
   consumer decompresses. This means the broker is just shuttling
   compressed bytes -- it doesn't pay the CPU cost of compression.
   LZ4 is the default choice: best throughput/ratio tradeoff for
   most message sizes."
```

---
---

## PHASE 4: DEEP DIVE -- CONSUMER GROUPS (5-7 min)

### What this phase signals to the interviewer

```
STAFF SIGNAL: You understand the consumer group protocol at a
deep level -- rebalancing algorithms, offset commit strategies,
and the exactly-once delivery chain. You can explain WHY
rebalancing is the #1 operational pain point in Kafka and how
cooperative/incremental rebalancing mitigates it.
```

### Consumer Group Model

```
"A consumer group is a set of consumer instances that collectively
consume a topic. The group coordinator (a broker elected for this
group) manages partition assignments."

PARTITION ASSIGNMENT RULE:
  - Each partition is assigned to EXACTLY ONE consumer in the group
  - A consumer can own MULTIPLE partitions
  - If consumers > partitions, some consumers are idle
  - If consumers < partitions, some consumers own multiple partitions
  - Maximum parallelism = number of partitions

EXAMPLE:
  Topic T with 6 partitions, Consumer Group G with 3 consumers:

  Consumer C1: [P0, P1]
  Consumer C2: [P2, P3]
  Consumer C3: [P4, P5]

  If C3 crashes:
  Consumer C1: [P0, P1, P4]  <- picks up P4
  Consumer C2: [P2, P3, P5]  <- picks up P5

  If C4 joins:
  Consumer C1: [P0, P1]
  Consumer C2: [P2, P3]
  Consumer C3: [P4]          <- recovers
  Consumer C4: [P5]          <- gets one partition
```

### Rebalancing Protocol

```
"Rebalancing is triggered when a consumer joins, leaves, or crashes,
or when topic metadata changes (new partitions added). There are
three rebalancing strategies."

STRATEGY 1: EAGER REBALANCING (legacy)
=======================================

  1. Group coordinator detects membership change
  2. ALL consumers REVOKE ALL partitions (stop consuming)
  3. Coordinator runs assignment algorithm
  4. ALL consumers receive new assignments
  5. ALL consumers resume consuming

  TIMELINE:
  ┌─────────────────────────────────────────────────────────┐
  │                                                         │
  │  C1: [P0,P1] ──── STOP ──── wait ──── [P0,P1] resume  │
  │  C2: [P2,P3] ──── STOP ──── wait ──── [P2,P3] resume  │
  │  C3: [P4,P5] ──── STOP ──── wait ──── [P4] resume     │
  │  C4: (joins) ──── wait ──── wait ──── [P5] resume      │
  │                                                         │
  │         ^                        ^                      │
  │     revocation               assignment                 │
  │     (ALL stop)               (ALL resume)               │
  │                                                         │
  │  DOWNTIME = revocation + assignment + startup           │
  │           = typically 5-30 seconds                      │
  └─────────────────────────────────────────────────────────┘

  PROBLEM: ALL consumers stop, even if their assignments don't
  change. C1 and C2 had the same partitions before and after,
  but they still stopped for 10+ seconds. This is called a
  "stop-the-world" rebalance.

STRATEGY 2: COOPERATIVE / INCREMENTAL REBALANCING (modern)
==========================================================

  1. Group coordinator detects membership change
  2. ONLY the affected partitions are revoked
  3. First rebalance: consumers report owned partitions
  4. Second rebalance: only the delta is reassigned
  5. Unaffected consumers NEVER stop consuming

  TIMELINE:
  ┌─────────────────────────────────────────────────────────┐
  │                                                         │
  │  C1: [P0,P1] ────────── continues ──── [P0,P1] (no change) │
  │  C2: [P2,P3] ────────── continues ──── [P2,P3] (no change) │
  │  C3: [P4,P5] ── revoke P5 only ──── [P4] continues    │
  │  C4: (joins) ────── wait ────────── [P5] assigned      │
  │                                                         │
  │  DOWNTIME = only P5 paused briefly                     │
  │           = sub-second for unaffected partitions        │
  └─────────────────────────────────────────────────────────┘

  "This is the default in Kafka 3.x+. It uses the
  CooperativeStickyAssignor. The key insight: most rebalances
  only affect 1-2 partitions, so why stop all consumers?"

STRATEGY 3: STATIC GROUP MEMBERSHIP
====================================

  - Each consumer has a fixed group.instance.id
  - On temporary disconnect (rolling restart), the coordinator
    waits session.timeout.ms before triggering rebalance
  - If the consumer reconnects within the timeout, NO rebalance
  - Used for: rolling deployments, transient network issues

  "Static membership reduces rebalance frequency by 90% in
  production. A 30-second rolling restart of a consumer pod
  doesn't trigger a rebalance if session timeout is 45 seconds."
```

### Assignment Strategies

```
RANGE ASSIGNOR:
  - Assigns partition ranges to consumers in order
  - Topic T1 with P0-P5, 3 consumers:
    C1: [P0, P1], C2: [P2, P3], C3: [P4, P5]
  - Problem: with multiple topics, C1 always gets the extra
    partition from each topic, creating imbalance

ROUND-ROBIN ASSIGNOR:
  - Assigns partitions round-robin across consumers
  - More balanced than range, but assignments change entirely
    on rebalance

STICKY ASSIGNOR:
  - Tries to preserve existing assignments, only moving
    partitions that must move
  - Minimizes partition migration on rebalance
  - Preferred for stateful consumers (local caches, etc.)

COOPERATIVE STICKY ASSIGNOR:
  - Sticky + cooperative rebalancing protocol
  - Best of all worlds: minimal migration + no stop-the-world
  - DEFAULT in Kafka 3.x+
```

### Offset Management

```
"Offsets are the consumer's bookmark. They track 'I've processed
messages up to offset X in partition P.' Let me walk through the
offset commit strategies."

OFFSET STORAGE:
  - Stored in a special internal topic: __consumer_offsets
  - This topic has 50 partitions (default)
  - Group's offsets are stored in partition =
    hash(group.id) % 50
  - The broker hosting that partition is the group coordinator

COMMIT STRATEGIES:
┌────────────────────────────────────────────────────────────┐
│ Strategy        │ Config                │ Tradeoff          │
├────────────────────────────────────────────────────────────┤
│ Auto-commit     │ enable.auto.commit=   │ Simple but can    │
│ (periodic)      │  true                 │ lose messages or  │
│                 │ auto.commit.interval  │ duplicate on crash│
│                 │  .ms=5000             │                   │
├────────────────────────────────────────────────────────────┤
│ Manual sync     │ consumer.commitSync() │ Blocks until      │
│                 │ after processing      │ broker acks.      │
│                 │                       │ Slowest, safest.  │
├────────────────────────────────────────────────────────────┤
│ Manual async    │ consumer.commitAsync()│ Non-blocking.     │
│                 │ with callback         │ Faster, but can   │
│                 │                       │ lose offset on    │
│                 │                       │ crash (retry      │
│                 │                       │ older offset).    │
├────────────────────────────────────────────────────────────┤
│ Transactional   │ isolation.level=      │ Exactly-once with │
│                 │  read_committed       │ producer txn.     │
│                 │                       │ Highest overhead. │
└────────────────────────────────────────────────────────────┘

AUTO-COMMIT FAILURE SCENARIO:
  1. Consumer polls 100 messages (offsets 500-599)
  2. Processes 60 messages (up to offset 559)
  3. Auto-commit fires: commits offset 599 (the poll offset)
  4. Consumer crashes
  5. Rebalance: new consumer starts at offset 599
  6. Messages 560-599 are LOST (never processed)

  FIX: Use manual commit after processing each batch:
  1. Consumer polls 100 messages (offsets 500-599)
  2. Processes all 100
  3. consumer.commitSync(offset=599)
  4. If crash before step 3, new consumer replays from 500
  5. Result: at-least-once (may reprocess 500-599, but no loss)
```

### Exactly-Once Semantics

```
"Exactly-once is the holy grail. It requires TWO mechanisms
working together: idempotent producer + transactional consumer."

MECHANISM 1: IDEMPOTENT PRODUCER
=================================

  Problem: Producer sends a batch, broker writes it, ack is lost
  in network. Producer retries. Broker now has a DUPLICATE batch.

  Solution: Each producer has a unique Producer ID (PID) and a
  monotonically increasing sequence number per partition.

  ┌─────────────────────────────────────────────────────────┐
  │ Producer (PID=42)                                       │
  │   sends to Partition P0:                                │
  │     Batch 1: PID=42, seq=0   -> broker writes          │
  │     Batch 2: PID=42, seq=1   -> broker writes          │
  │     Batch 2: PID=42, seq=1   -> RETRY (ack lost)       │
  │                                  broker sees seq=1      │
  │                                  already written.       │
  │                                  Returns ack without    │
  │                                  writing again.         │
  │                                  DEDUPLICATION.         │
  └─────────────────────────────────────────────────────────┘

  The broker maintains a map: (PID, partition) -> last seq number.
  If incoming seq <= last seq, it's a duplicate. Reject silently.

  Config: enable.idempotence=true (default in Kafka 3.x+)

MECHANISM 2: TRANSACTIONAL PRODUCER + CONSUMER
===============================================

  Problem: Consumer reads from topic A, processes, writes to
  topic B, and commits offsets. If the consumer crashes between
  writing to B and committing offsets, on restart it reads from
  A again and writes duplicates to B.

  Solution: Atomic transactions spanning reads, writes, and
  offset commits.

  ┌─────────────────────────────────────────────────────────┐
  │ producer.beginTransaction();                            │
  │                                                        │
  │   // 1. Read from input topic (consumer poll)          │
  │   records = consumer.poll();                           │
  │                                                        │
  │   // 2. Process and write to output topic              │
  │   for (record : records) {                             │
  │     result = process(record);                          │
  │     producer.send(outputTopic, result);                │
  │   }                                                    │
  │                                                        │
  │   // 3. Commit consumer offsets as part of transaction │
  │   producer.sendOffsetsToTransaction(offsets, groupId); │
  │                                                        │
  │ producer.commitTransaction();                          │
  │   // Atomic: EITHER all writes + offset commit happen, │
  │   //         OR none of them happen.                   │
  └─────────────────────────────────────────────────────────┘

  Under the hood:
  - Transaction coordinator (a broker) tracks transaction state
  - Two-phase commit: PREPARE -> COMMIT markers in partition logs
  - Consumer with isolation.level=read_committed only sees
    messages with a COMMIT marker (ignores uncommitted messages)

EXACTLY-ONCE OVERHEAD:
  - 20-30% throughput reduction (transaction coordination)
  - Higher latency (two-phase commit, batching to amortize)
  - More complex error handling (abort + retry on timeout)
  - "Use exactly-once only when you need it. Most systems are
    fine with at-least-once + idempotent consumers."
```

### Consumer Group Coordinator Deep Dive

```
"The group coordinator is a BROKER (not a special process). Each
consumer group is assigned to a coordinator based on a hash:

  coordinator = brokers[ hash(group.id) % __consumer_offsets partitions ]

The coordinator handles:
 1. Group membership (join/leave/heartbeat)
 2. Partition assignment (delegates to the group leader)
 3. Offset storage (writes to __consumer_offsets)

GROUP JOIN PROTOCOL (cooperative):
  ┌─────────────────────────────────────────────────────────┐
  │ Consumer                    Coordinator                 │
  │                                                         │
  │  JoinGroupRequest  ────────>                            │
  │  (member.id,                                            │
  │   group.instance.id,        waits for all members       │
  │   subscriptions)            (rebalance timeout)         │
  │                                                         │
  │                    <────────  JoinGroupResponse          │
  │                              (generation.id,            │
  │                               leader.id,                │
  │                               members[])                │
  │                                                         │
  │  if (I am leader):                                      │
  │    run assignor algorithm                               │
  │    SyncGroupRequest ────────> (with assignments)        │
  │  else:                                                  │
  │    SyncGroupRequest ────────> (empty)                   │
  │                                                         │
  │                    <────────  SyncGroupResponse          │
  │                              (my assigned partitions)   │
  │                                                         │
  │  Start consuming assigned partitions                    │
  │                                                         │
  │  HeartbeatRequest  ────────> (every heartbeat.interval  │
  │                               .ms = 3s)                 │
  │                    <────────  HeartbeatResponse          │
  │                              (or REBALANCE_IN_PROGRESS  │
  │                               to trigger rejoin)        │
  └─────────────────────────────────────────────────────────┘

KEY INSIGHT: The coordinator does NOT run the assignment algorithm.
The GROUP LEADER (first consumer to join) runs it. This is the
"smart client" pattern -- keeps the broker simple."
```

### Consumer Fetch Tuning

```
FETCH CONFIGURATION:
  ┌──────────────────────────────────────────────────────────┐
  │ Config                 │ Default    │ Purpose             │
  ├──────────────────────────────────────────────────────────┤
  │ fetch.min.bytes        │ 1 byte     │ Broker waits until  │
  │                        │            │ this much data is   │
  │                        │            │ available before     │
  │                        │            │ responding           │
  ├──────────────────────────────────────────────────────────┤
  │ fetch.max.wait.ms      │ 500 ms     │ Max time broker     │
  │                        │            │ waits for            │
  │                        │            │ fetch.min.bytes      │
  ├──────────────────────────────────────────────────────────┤
  │ max.partition.fetch    │ 1 MB       │ Max data per         │
  │  .bytes                │            │ partition per fetch  │
  ├──────────────────────────────────────────────────────────┤
  │ max.poll.records       │ 500        │ Max records per      │
  │                        │            │ poll() call          │
  ├──────────────────────────────────────────────────────────┤
  │ max.poll.interval.ms   │ 300000     │ Max time between     │
  │                        │ (5 min)    │ polls before         │
  │                        │            │ coordinator kicks    │
  │                        │            │ consumer out         │
  └──────────────────────────────────────────────────────────┘

TUNING FOR LATENCY:
  fetch.min.bytes = 1, fetch.max.wait.ms = 100
  "Broker responds immediately with whatever is available."

TUNING FOR THROUGHPUT:
  fetch.min.bytes = 64KB, fetch.max.wait.ms = 500
  "Broker batches responses, fewer network round trips."
```

### Interviewer signals and transitions

```
POSITIVE SIGNALS:
 - "Good, you mentioned cooperative rebalancing" -- they're happy
 - "Tell me about replication" -- go to Phase 5
 - "How do you scale consumers?" -- briefly answer (consumers =
   partitions), then go to Phase 6

NEGATIVE SIGNALS:
 - "What's the difference between consumer offset and producer
   offset?" -- clarify: producer doesn't have an offset. The
   broker assigns offsets on write. The consumer tracks the
   last-read offset.
 - "Can two consumers in the same group read the same partition?"
   -- No, never. One partition = one consumer per group. This is
   the fundamental invariant.

TRANSITION:
"We've covered writes (commit log) and reads (consumer groups).
The critical missing piece is: what happens when a broker crashes?
Let me walk through the replication protocol."
```

### Common follow-up questions for Phase 4

```
Q: "Why not track offsets in ZooKeeper?"
A: "Old Kafka (< 0.9) did this. Problem: ZooKeeper is a coordination
   service, not a database. Committing offsets from 10,000 consumers
   every 5 seconds overwhelms ZooKeeper. Moving offsets to an internal
   Kafka topic (__consumer_offsets) uses the same high-throughput
   commit log we already have. Self-hosting the metadata."

Q: "What happens if a consumer is too slow?"
A: "If a consumer falls behind by more than the retention period,
   those messages are deleted before the consumer reads them. The
   consumer gets an OffsetOutOfRangeException and must decide:
   reset to earliest (reprocess everything available) or latest
   (skip to current, losing unread messages). This is why monitoring
   consumer lag is critical."

Q: "How is consumer lag measured?"
A: "Lag = latest offset in partition (the high watermark) minus
   the consumer's committed offset. Summed across all partitions.
   A growing lag means the consumer can't keep up. Alert on
   lag > threshold. Tools: Burrow, consumer group describe command."
```

---
---

## PHASE 5: REPLICATION (3-5 min)

### What this phase signals to the interviewer

```
STAFF SIGNAL: You understand ISR-based replication at a deep level.
You can explain the tradeoffs between acks modes, reason about
data loss scenarios with specific failure timings, and articulate
why min.insync.replicas exists. You know what unclean leader
election is and why it's disabled by default.
```

### ISR (In-Sync Replicas)

```
"Every partition has one leader and N-1 followers (where N is the
replication factor). The ISR is the subset of replicas that are
fully caught up with the leader."

REPLICATION ARCHITECTURE:
  Topic T, Partition P0, Replication Factor = 3

  ┌──────────────────────────────────────────────────────────┐
  │                                                          │
  │  Broker 1                Broker 2            Broker 3    │
  │  ┌──────────────┐       ┌──────────────┐   ┌──────────┐ │
  │  │  P0 LEADER   │       │  P0 FOLLOWER │   │P0 FOLLOWER│ │
  │  │              │       │              │   │           │ │
  │  │  Offset: 100 │ ───>  │  Offset: 100 │   │Offset: 98│ │
  │  │              │ fetch │  (in-sync)   │   │(lagging) │ │
  │  │  LEO: 100    │       │  LEO: 100    │   │LEO: 98   │ │
  │  │  HW:  100    │       │              │   │          │ │
  │  └──────────────┘       └──────────────┘   └──────────┘ │
  │                                                          │
  │  ISR = {Broker 1 (leader), Broker 2}                    │
  │  Broker 3 is OUT of ISR (lagging by 2 offsets)          │
  │                                                          │
  └──────────────────────────────────────────────────────────┘

KEY TERMS:
  LEO (Log End Offset): The offset of the NEXT message to be
      written. LEO = 100 means offsets 0-99 exist.

  HW (High Watermark): The offset up to which ALL ISR replicas
      have replicated. Consumers can only read up to HW.
      HW <= min(LEO of all ISR members).

  "The high watermark is crucial. A consumer never sees a message
  that hasn't been replicated to all ISR members. If the leader
  crashes, no consumer has seen data that the new leader doesn't
  have."

ISR MEMBERSHIP RULES:
  - A follower is IN the ISR if it has fetched from the leader
    within replica.lag.time.max.ms (default 30s)
  - If a follower falls behind (network issue, slow disk), the
    leader removes it from the ISR
  - When the follower catches up, the leader adds it back
  - ISR shrinks and grows dynamically
  - ISR changes are recorded in the controller's metadata log
```

### Acks Modes

```
ACKS MODES:
  ┌─────────────────────────────────────────────────────────────┐
  │ Mode    │ Behavior                │ Durability │ Latency    │
  ├─────────────────────────────────────────────────────────────┤
  │ acks=0  │ Producer fires and      │ LOWEST     │ Lowest     │
  │         │ forgets. No ack from    │ May lose   │ ~0.5 ms    │
  │         │ broker.                 │ messages   │            │
  ├─────────────────────────────────────────────────────────────┤
  │ acks=1  │ Leader writes to local  │ MEDIUM     │ Low        │
  │         │ log and acks. Followers │ Lose data  │ ~2 ms      │
  │         │ replicate async.        │ if leader  │            │
  │         │                         │ crashes    │            │
  │         │                         │ before     │            │
  │         │                         │ replication│            │
  ├─────────────────────────────────────────────────────────────┤
  │ acks=all│ Leader waits for ALL    │ HIGHEST    │ Higher     │
  │ (-1)    │ ISR members to write.   │ No data    │ ~5-10 ms   │
  │         │ Then acks producer.     │ loss (with │            │
  │         │                         │ min.insync │            │
  │         │                         │ .replicas  │            │
  │         │                         │ >= 2)      │            │
  └─────────────────────────────────────────────────────────────┘

DATA LOSS SCENARIO WITH acks=1:
  1. Producer sends message M to leader (Broker 1)
  2. Leader writes M to local log, sends ack to producer
  3. Broker 1 crashes BEFORE followers fetch M
  4. Controller elects Broker 2 as new leader
  5. Message M is LOST -- it was only on Broker 1's disk

  "With acks=all, step 2 would wait for Broker 2 to also write M.
  The ack is delayed but M survives the crash."
```

### min.insync.replicas

```
"acks=all has a subtle trap. If ISR shrinks to just the leader
(all followers are slow), acks=all means 'ack after just the
leader writes' -- which is identical to acks=1!"

SOLUTION: min.insync.replicas (MISR)
  - Requires at least MISR replicas (including leader) to be
    in the ISR for the partition to accept writes
  - If ISR < MISR, the partition returns NotEnoughReplicasException
    to the producer (rejects the write)

RECOMMENDED PRODUCTION CONFIG:
  replication.factor = 3
  min.insync.replicas = 2
  acks = all

  "This means: at least 2 out of 3 replicas must be alive and
  in-sync for writes to succeed. If 2 brokers crash, the
  partition becomes read-only (refuses writes). This is the
  right tradeoff: availability of reads over availability of
  writes, because losing writes is recoverable (producer
  retries) but losing data is not."

SCENARIOS:
  ┌─────────────────────────────────────────────────────────┐
  │ RF=3, MISR=2, acks=all                                 │
  ├─────────────────────────────────────────────────────────┤
  │ 3 brokers alive: writes succeed, ack after 2 write     │
  │ 2 brokers alive: writes succeed, ack after 2 write     │
  │ 1 broker alive:  writes REJECTED (ISR=1 < MISR=2)     │
  │                  reads still work                       │
  │ 0 brokers alive: partition offline                     │
  └─────────────────────────────────────────────────────────┘
```

### Leader Election

```
LEADER ELECTION:
  1. Broker heartbeats to controller every broker.heartbeat
     .interval.ms (default 10s in KRaft)
  2. Controller detects broker failure after broker.session
     .timeout.ms (default 18s in KRaft)
  3. Controller picks a new leader from the ISR
     - Preference: first replica in the ISR list
     - Must be in-sync to avoid data loss
  4. Controller updates metadata and notifies all brokers
  5. Producers/consumers discover new leader via metadata refresh

ELECTION TIMELINE:
  ┌─────────────────────────────────────────────────────────┐
  │ T=0s    : Leader broker sends last heartbeat           │
  │ T=18s   : Controller declares broker dead              │
  │ T=18.1s : Controller selects new leader from ISR       │
  │ T=18.2s : Controller broadcasts metadata update        │
  │ T=18.5s : Producers retry with new leader              │
  │                                                        │
  │ TOTAL UNAVAILABILITY: ~18-20 seconds per partition     │
  │ (Only affects partitions that broker was leading)       │
  └─────────────────────────────────────────────────────────┘
```

### Unclean Leader Election

```
UNCLEAN LEADER ELECTION:
  - What if ALL ISR members crash, but a non-ISR replica is alive?
  - That replica is behind -- it's missing some committed messages
  - Should we elect it as leader?

  unclean.leader.election.enable=false (DEFAULT):
    - Partition stays offline until an ISR member recovers
    - NO DATA LOSS, but reduced availability
    - Recommended for financial, healthcare, audit systems

  unclean.leader.election.enable=true:
    - Non-ISR replica becomes leader immediately
    - SOME MESSAGES MAY BE LOST (the ones it hadn't replicated)
    - Higher availability, but data loss risk
    - Acceptable for metrics, logs, analytics pipelines

  "This is the classic AP vs CP tradeoff from CAP theorem applied
  to a single partition. Default is CP (consistency over
  availability). Enable unclean election only if you'd rather
  lose a few messages than have a partition go offline."
```

### Replication Protocol Detail

```
FOLLOWER FETCH PROTOCOL:
  1. Followers send FetchRequest to leader with their LEO
  2. Leader responds with messages from follower's LEO to
     leader's LEO
  3. Follower appends messages to local log
  4. Follower sends next FetchRequest with updated LEO
  5. When follower's LEO reaches leader's LEO, leader
     advances the high watermark

  "Followers pull from the leader, not push. This simplifies
  the protocol -- the leader doesn't track each follower's
  state. Each follower is responsible for keeping up."

FOLLOWER FETCH DIAGRAM:
  ┌──────────────┐        ┌──────────────┐
  │   Follower   │        │    Leader    │
  │   (LEO=95)   │        │   (LEO=100)  │
  │              │        │              │
  │ FetchReq     │ ────>  │              │
  │ (offset=95)  │        │              │
  │              │ <────  │ FetchResp    │
  │              │        │ (msgs 95-99) │
  │ Append to    │        │              │
  │ local log    │        │              │
  │ (LEO=100)    │        │              │
  │              │        │ HW advances  │
  │ FetchReq     │ ────>  │ to 100       │
  │ (offset=100) │        │              │
  │              │ <────  │ FetchResp    │
  │              │        │ (empty, up   │
  │              │        │  to date)    │
  └──────────────┘        └──────────────┘
```

### Interviewer signals and transitions

```
POSITIVE SIGNALS:
 - "Good tradeoff analysis on acks modes" -- they like your depth
 - "How do you handle partition hot spots?" -- go to Phase 6
 - "What about exactly-once with replication?" -- explain:
   idempotent producer handles retries across leader failover
   because the new leader has the same PID->seq mapping

NEGATIVE SIGNALS:
 - "What if the ISR is empty?" -- explain unclean leader election
 - "How long is a partition unavailable?" -- quantify: ~18-20s
   with default KRaft timeouts

TRANSITION:
"Now that we have durability covered, let me discuss how we scale
this system horizontally -- partitioning strategy and consumer
parallelism."
```

### Common follow-up questions for Phase 5

```
Q: "Why not use Raft for replication like etcd?"
A: "Raft requires a majority quorum for every write (2 out of 3).
   Kafka's ISR model is more flexible: with min.insync.replicas=2
   and RF=3, you need 2 replicas, but they don't have to be a
   fixed quorum. Any 2 of the 3 will do. Raft's fixed-quorum
   leader election is slower to reconfigure when membership
   changes. However, KRaft (Kafka's controller) does use Raft
   for metadata consensus -- just not for data replication."

Q: "What about rack-aware replication?"
A: "broker.rack config ensures replicas are spread across racks
   or availability zones. With RF=3 and 3 AZs, each AZ gets one
   replica. A full AZ failure loses at most 1 of 3 replicas,
   and min.insync.replicas=2 ensures writes continue."

Q: "How does follower fetching interact with zero-copy?"
A: "Follower fetch uses the same sendfile() path as consumer
   fetch. The leader reads from page cache and zero-copies to
   the follower's socket. No difference in the I/O path -- the
   broker doesn't distinguish between a follower fetch and a
   consumer fetch at the I/O level."
```

---
---

## PHASE 6: SCALING (3-5 min)

### What this phase signals to the interviewer

```
STAFF SIGNAL: You understand that partitions are the unit of
scaling and can reason about the relationship between partition
count, consumer count, and broker count. You know the tradeoffs
of too few vs too many partitions and can do back-of-envelope
capacity math.
```

### Partitioning Strategies

```
"Partitioning determines how messages are distributed across
brokers. The partition key determines ordering guarantees."

STRATEGY 1: HASH PARTITIONING (by message key)
  - partition = hash(key) % num_partitions
  - All messages with the same key go to the same partition
  - GUARANTEES per-key ordering
  - Use case: user events (key=userId), order events (key=orderId)
  - Risk: hot keys (a celebrity user generates 100x more events)

STRATEGY 2: ROUND-ROBIN (no key)
  - Messages distributed evenly across partitions
  - NO ordering guarantees
  - Maximum throughput (perfect load balance)
  - Use case: metrics, logs, any orderless stream

STRATEGY 3: CUSTOM PARTITIONER
  - Application-defined logic
  - Example: geo-based partitioning (US -> P0-P3, EU -> P4-P7)
  - Use case: data locality, compliance (EU data stays in EU)

PARTITIONING DIAGRAM:
  ┌───────────────────────────────────────────────────────────┐
  │  Producer Message Stream:                                  │
  │    (key=A, v1) (key=B, v1) (key=A, v2) (key=C, v1)       │
  │    (key=B, v2) (key=A, v3) (key=C, v2) (key=D, v1)       │
  │                                                            │
  │  hash(A) % 3 = 0    hash(B) % 3 = 1    hash(C) % 3 = 2  │
  │                      hash(D) % 3 = 1                      │
  │                                                            │
  │  Partition 0:  (A,v1) (A,v2) (A,v3)     <- A is ordered  │
  │  Partition 1:  (B,v1) (B,v2) (D,v1)     <- B is ordered  │
  │  Partition 2:  (C,v1) (C,v2)             <- C is ordered  │
  │                                                            │
  │  ORDERING: A's events are ordered. B's events are ordered. │
  │  But there is NO ordering between A and B events.          │
  └───────────────────────────────────────────────────────────┘
```

### Horizontal Scaling

```
"There are three dimensions of scaling: brokers, partitions,
and consumers. They're tightly coupled."

SCALING DIMENSION 1: ADD BROKERS
  - New broker joins the cluster
  - Existing partitions are NOT automatically moved
  - New topics' partitions are spread across all brokers
  - To rebalance existing topics: kafka-reassign-partitions tool
  - "Adding a broker is safe but manual. Confluent's Auto Data
    Balancer automates this."

SCALING DIMENSION 2: ADD PARTITIONS
  - Increases parallelism for both writes and reads
  - CAUTION: Adding partitions breaks key-based ordering!
    hash(key) % 6 != hash(key) % 8
    Messages with the same key will go to different partitions
    after repartitioning.
  - "This is why you should over-provision partitions on day one.
    Going from 6 to 8 partitions re-shuffles all keys. Going from
    100 to 100 (no change) is a no-op."
  - Cannot reduce partition count (ever).

SCALING DIMENSION 3: ADD CONSUMERS
  - Maximum useful consumers = number of partitions
  - Adding more consumers than partitions = idle consumers
  - "This is why partition count is the parallelism ceiling.
    If you need 20 consumers for throughput, you need at least
    20 partitions."

SCALING TABLE:
  ┌──────────────────────────────────────────────────────────┐
  │ Want to...          │ Action          │ Tradeoff          │
  ├──────────────────────────────────────────────────────────┤
  │ More write          │ Add brokers +   │ More file handles │
  │ throughput          │ partitions      │ per broker        │
  ├──────────────────────────────────────────────────────────┤
  │ More read           │ Add consumers   │ Max = partition   │
  │ throughput          │ (up to P count) │ count             │
  ├──────────────────────────────────────────────────────────┤
  │ More storage        │ Add brokers     │ Need partition    │
  │                     │                 │ reassignment      │
  ├──────────────────────────────────────────────────────────┤
  │ More consumer       │ Add partitions  │ Breaks key        │
  │ parallelism         │                 │ ordering          │
  ├──────────────────────────────────────────────────────────┤
  │ Lower latency       │ Reduce RF or    │ Durability        │
  │                     │ use acks=1      │ tradeoff          │
  └──────────────────────────────────────────────────────────┘
```

### Partition Count Sizing

```
"How many partitions should a topic have? This is one of the most
common interview questions."

FORMULA (rough):
  P = max(T/Tp, T/Tc)

  Where:
    P  = number of partitions
    T  = target throughput (msg/s or MB/s)
    Tp = throughput per partition from a single producer (~10 MB/s)
    Tc = throughput per partition for a single consumer (~25 MB/s)

  Example: T = 500 MB/s
    P = max(500/10, 500/25) = max(50, 20) = 50 partitions

GUIDELINES:
  ┌──────────────────────────────────────────────────────────┐
  │ Rule of Thumb                                           │
  ├──────────────────────────────────────────────────────────┤
  │ Small topic (< 10 MB/s):     6-12 partitions            │
  │ Medium topic (10-100 MB/s):  30-50 partitions           │
  │ Large topic (100+ MB/s):     100-200 partitions         │
  │ Max per broker:              ~4,000 partitions           │
  │ Max per cluster:             ~200,000 partitions         │
  │ Over-provision by 2x for future growth                  │
  └──────────────────────────────────────────────────────────┘

TOO FEW PARTITIONS:
  - Consumer parallelism limited
  - Individual partition log files get very large
  - Single partition becomes a throughput bottleneck

TOO MANY PARTITIONS:
  - More file handles per broker (OS limit)
  - Longer leader election time (each partition elected separately)
  - More memory for producer batching (one batch buffer per partition)
  - Higher end-to-end latency (more partitions = smaller batches
    per partition = less batching benefit)
  - Controller metadata overhead
```

### Producer Batching and Throughput

```
PRODUCER BATCHING:
  - batch.size: max bytes per batch (default 16 KB)
  - linger.ms: max time to wait for batch to fill (default 0)
  - compression.type: none, snappy, lz4, zstd, gzip

  THROUGHPUT TUNING:
  ┌──────────────────────────────────────────────────────────┐
  │ Config              │ Low Latency    │ High Throughput   │
  ├──────────────────────────────────────────────────────────┤
  │ batch.size          │ 16 KB          │ 256 KB - 1 MB     │
  │ linger.ms           │ 0 (send now)   │ 5-50 ms           │
  │ compression.type    │ none           │ lz4 or zstd       │
  │ buffer.memory       │ 32 MB          │ 128 MB            │
  │ acks                │ 1              │ all               │
  │ max.in.flight.      │ 1              │ 5                 │
  │   requests.per.     │                │                   │
  │   connection         │                │                   │
  └──────────────────────────────────────────────────────────┘

  "Batching is the single biggest throughput lever. Going from
  batch.size=16KB + linger.ms=0 to batch.size=256KB + linger.ms=10
  can increase throughput by 10x with only 10ms additional latency."
```

### Back-of-Envelope Capacity Planning

```
"Let me size a cluster for our requirements."

REQUIREMENTS:
  - 1M messages/sec
  - 1 KB average message size
  - Replication factor = 3
  - 7-day retention
  - At-least-once delivery (acks=all)

THROUGHPUT:
  Write throughput = 1M msg/s * 1 KB = 1 GB/s (ingress)
  With RF=3:        1 GB/s * 3 = 3 GB/s (total disk write)
  Consumer read:    assume 3 consumer groups = 3 GB/s (egress)
  Total I/O:        6 GB/s across the cluster

  Per-broker throughput: ~200 MB/s sustained writes (conservative)
  Brokers needed for write: 3 GB/s / 200 MB/s = 15 brokers

STORAGE:
  Daily ingress:     1 GB/s * 86400 s = 86.4 TB/day
  With RF=3:         86.4 * 3 = 259.2 TB/day (total stored)
  7-day retention:   259.2 * 7 = 1,814.4 TB (~1.8 PB)
  Per broker (15):   1,814.4 / 15 = 121 TB per broker
  Disk provisioning: 150 TB per broker (1.25x headroom)

MEMORY:
  Page cache target: last 30 minutes of data in RAM
  30 min ingress:    1 GB/s * 1800 s = 1.8 TB
  With 15 brokers:   1.8 TB / 15 = 120 GB per broker
  Total RAM:         128 GB per broker (6 GB heap + 122 GB page cache)

NETWORK:
  Per-broker network: (200 MB/s write + 200 MB/s replication +
                       200 MB/s consumer read) = ~600 MB/s
                     = ~5 Gbps per broker
  NIC requirement:    10 Gbps NIC (50% utilization target)

PARTITIONS:
  Target parallelism: 50 consumers per group
  Partitions needed:  at least 50 per topic
  With 15 brokers:    50 partitions / 15 = ~3 leader partitions
                      per broker per topic (well balanced)

SUMMARY:
  ┌──────────────────────────────────────────────────┐
  │ Component        │ Sizing                        │
  ├──────────────────────────────────────────────────┤
  │ Brokers          │ 15 (20 for headroom)          │
  │ RAM per broker   │ 128 GB                        │
  │ Disk per broker  │ 150 TB (NVMe or JBOD HDD)    │
  │ NIC per broker   │ 10 Gbps                       │
  │ CPU per broker   │ 16-24 cores                   │
  │ Partitions/topic │ 50                            │
  │ JVM heap         │ 6 GB (-Xmx6g)                │
  │ Controller nodes │ 3 (KRaft quorum)              │
  └──────────────────────────────────────────────────┘

  "This is a large cluster but not exceptional. LinkedIn runs
  Kafka clusters with 1000+ brokers handling 7 trillion messages
  per day. Our 15-broker cluster is modest by comparison."
```

### Interviewer signals and transitions

```
POSITIVE SIGNALS:
 - "Good capacity planning" -- they like the math
 - Interviewer asks about specific failure modes -- go to Phase 7

NEGATIVE SIGNALS:
 - "What if one partition is much hotter than others?" -- cover
   hot spots in Phase 7
 - "Can you reduce partition count?" -- No, only increase. This
   is a fundamental limitation. Explain why: reducing would
   require re-hashing all keys and moving data.

TRANSITION:
"Let me close with some edge cases and failure modes that are
critical to get right in production."
```

---
---

## PHASE 7: EDGE CASES (2-3 min)

### What this phase signals to the interviewer

```
STAFF SIGNAL: You've operated this system in production. You know
where the bodies are buried. Naming specific failure modes with
mitigations shows battle-tested experience, not just textbook
knowledge.
```

### Edge Case 1: Consumer Rebalance Storm

```
SCENARIO:
  A consumer takes slightly longer than max.poll.interval.ms
  (default 5 min) to process a batch. The coordinator considers
  it dead and triggers a rebalance. The consumer finishes
  processing, tries to commit, gets "member unknown" error,
  and rejoins -- triggering ANOTHER rebalance. This cascades
  across the entire group.

  ┌─────────────────────────────────────────────────────────┐
  │ C1 processing takes 5.1 min                             │
  │   -> coordinator: "C1 is dead"                         │
  │   -> rebalance: revoke C1's partitions, assign to C2   │
  │   -> C1 finishes, tries to commit                      │
  │   -> "member unknown" -> C1 rejoins                    │
  │   -> rebalance again: reassign partitions               │
  │   -> C2 is now processing C1's partitions...           │
  │   -> C2 takes too long because of extra load...        │
  │   -> cascade continues                                 │
  └─────────────────────────────────────────────────────────┘

MITIGATIONS:
  1. Increase max.poll.interval.ms to match processing time
  2. Reduce max.poll.records to get smaller batches
  3. Use cooperative rebalancing (less disruption per rebalance)
  4. Use static group membership (session.timeout.ms > processing
     time, group.instance.id set)
  5. Offload processing to a thread pool, poll on the main thread
     to send heartbeats (decouple polling from processing)
```

### Edge Case 2: Partition Hot Spots

```
SCENARIO:
  Key-based partitioning with a Zipfian distribution. User
  "celebrity-X" generates 1,000x more events than average users.
  Partition hash("celebrity-X") % N is overwhelmed while other
  partitions are nearly idle.

  Partition Load:
  P0: ████████████████████████████████  (98% capacity)  <- hot!
  P1: ██                                (2% capacity)
  P2: ███                               (3% capacity)
  P3: ██                                (2% capacity)

MITIGATIONS:
  1. SALTED KEYS: Append a random suffix to hot keys:
     "celebrity-X-0", "celebrity-X-1", ..., "celebrity-X-9"
     Spreads the hot key across 10 partitions.
     Tradeoff: lose per-key ordering. Consumer must aggregate
     across 10 partitions for this key.

  2. DEDICATED TOPIC: Route known hot keys to a separate topic
     with more partitions and dedicated consumers.

  3. WEIGHTED PARTITIONER: Custom partitioner that monitors
     partition sizes and steers traffic away from overloaded
     partitions (sticky partitioning in Kafka 2.4+).

  4. BACKPRESSURE: Producer-side rate limiting for hot keys.
     Delay or batch more aggressively for known hot senders.
```

### Edge Case 3: Message Ordering Across Partitions

```
SCENARIO:
  An application needs global ordering (e.g., bank transactions
  must be processed in exact chronological order). But global
  ordering requires a single partition, which limits throughput
  to one consumer.

SOLUTIONS:
  1. SINGLE PARTITION (simple, limited throughput):
     - Works for low-throughput use cases (< 10K msg/s)
     - One partition, one consumer, total order guaranteed

  2. CAUSAL ORDERING WITH VECTOR CLOCKS:
     - Each producer maintains a vector clock
     - Consumer merges streams using timestamps
     - NOT total order, but causal order (sufficient for most)

  3. SEQUENCE NUMBER + CONSUMER-SIDE REORDER BUFFER:
     - Producer assigns a global sequence number
     - Consumer from multiple partitions, reorder buffer merges
       by sequence number
     - Adds latency (must wait for messages from all partitions)

  4. PARTITION BY ENTITY + PROCESS PER ENTITY:
     - Partition by account_id
     - Each account's events are totally ordered within partition
     - Cross-account events don't need ordering (usually)
     - "Most real-world ordering requirements are per-entity,
       not global. Design the key accordingly."
```

### Edge Case 4: Exactly-Once Overhead

```
SCENARIO:
  Team enables exactly-once semantics (EOS) for a high-throughput
  pipeline. Throughput drops from 1M msg/s to 600K msg/s.
  Latency increases by 50%.

ROOT CAUSES:
  1. Transaction coordinator adds a round-trip per transaction
  2. Two-phase commit markers are written to every partition
  3. Consumer with read_committed must wait for COMMIT markers
     before delivering messages (adds latency)
  4. Smaller effective batches (transaction boundaries limit
     batch size)

MITIGATIONS:
  1. LARGE TRANSACTION BATCHES: Accumulate 1000+ messages per
     transaction instead of 1. Amortize the overhead.
     transaction.timeout.ms sets the max batch window.

  2. IDEMPOTENT CONSUMER (alternative to EOS):
     - Use at-least-once delivery
     - Consumer deduplicates using a message ID + state store
     - Example: upsert to database with message_id as unique key
     - "At-least-once + idempotent consumer achieves effectively
       exactly-once semantics without the transaction overhead."

  3. SELECTIVE EOS: Enable EOS only for financial/critical
     topics. Use at-least-once for logs, metrics, analytics.
```

### Edge Case 5: Log Compaction Tombstones

```
SCENARIO:
  A compacted topic uses tombstone messages (null value) to
  delete keys. But the tombstone itself is retained for
  delete.retention.ms (default 24h). A consumer that joins
  after the tombstone expires never sees the delete.

  TIMELINE:
  T=0h  : message (key=X, value="active") written
  T=1h  : tombstone (key=X, value=null) written
  T=25h : tombstone expires and is removed by compaction
  T=26h : new consumer joins, reads compacted log
          sees: (key=X, value="active") -- the delete is LOST

MITIGATIONS:
  1. INCREASE delete.retention.ms to longer than max consumer
     downtime (e.g., 7 days if consumers might be down for days)

  2. FULL SYNC PROTOCOL: New consumers don't rely on compaction
     alone. They first load a snapshot from a database, then
     apply changes from the compacted log. The database has the
     correct current state.

  3. TOMBSTONE FORWARDING: If a consumer is down, a sidecar
     stores tombstones in a separate "catch-up" topic that
     the consumer reads on restart.
```

### Edge Case 6: Broker Disk Full

```
SCENARIO:
  A broker's disk fills up due to retention misconfiguration
  or unexpected traffic spike. The broker cannot append new
  messages to any partition it hosts.

IMPACT:
  - Leader partitions on this broker reject writes
  - Producer receives "disk full" error
  - Retention cleaner cannot delete old segments (needs temp
    space for compaction)

MITIGATIONS:
  1. MONITORING: Alert at 80% disk usage. Kafka exposes
     kafka.log.Log.Size metric per partition.

  2. EMERGENCY RETENTION: Temporarily reduce log.retention.hours
     or log.retention.bytes to delete old segments faster.

  3. PARTITION REASSIGNMENT: Move partitions from the full
     broker to brokers with available disk.

  4. DISK PROVISIONING: Rule of thumb: 3x the expected daily
     write volume * retention days * replication factor.
     Example: 100 MB/s * 86400 s/day * 7 days * 3 RF = 181 TB
```

### Edge Case 7: Split Brain During Network Partition

```
SCENARIO:
  Network partition splits the cluster. Broker 1 (leader for P0)
  is isolated from the controller. Controller elects Broker 2 as
  the new leader. But Broker 1 doesn't know it's been deposed and
  continues accepting writes from producers that can still reach it.

  Result: Two leaders for P0, divergent logs.

KAFKA'S DEFENSE:
  1. EPOCH NUMBERS: Each leader has a leader epoch (monotonically
     increasing). Broker 2's epoch = Broker 1's epoch + 1.

  2. FENCING: When Broker 1 reconnects, it discovers a higher
     epoch exists. It truncates its log back to the high watermark
     at the time of the epoch change, then fetches from the new
     leader to catch up.

  3. PRODUCER FENCING: Producers that sent to the old leader get
     a "not leader" response on their next metadata refresh. They
     switch to the new leader. Any messages written to the old
     leader after the epoch change are lost (truncated).

  "This is why acks=all + min.insync.replicas=2 matters. Messages
  acknowledged under the old leader's epoch were replicated to at
  least 2 brokers. The new leader has them. Only unacknowledged
  messages (in-flight during the partition) are lost."
```

---
---

## APPENDIX A: DESIGN PATTERNS CHEAT SHEET

```
┌─────────────────────────────────────────────────────────────────┐
│ Pattern                 │ Where Used                            │
├─────────────────────────────────────────────────────────────────┤
│ Append-Only Log         │ Commit log storage (core abstraction) │
│ Segment Files           │ Log split into 1 GB immutable files   │
│ Sparse Index            │ Offset index (.index file)            │
│ Zero-Copy Transfer      │ sendfile() for consumer/follower fetch│
│ Page Cache Delegation   │ OS page cache instead of JVM heap     │
│ Write-Ahead Log (WAL)   │ Commit log IS the WAL                │
│ ISR Replication          │ Flexible quorum replication           │
│ Leader-Follower         │ One leader per partition, N followers  │
│ Consumer Group           │ Cooperative consumption + fan-out     │
│ Cooperative Rebalancing │ Incremental partition reassignment    │
│ Idempotent Producer     │ PID + sequence number deduplication   │
│ Two-Phase Commit        │ Transactional exactly-once delivery   │
│ Log Compaction           │ Changelog topics (latest per key)     │
│ Epoch Fencing           │ Leader epoch for split-brain defense  │
│ Backpressure            │ Producer buffer.memory limits         │
│ Batching                │ Producer batch.size + linger.ms       │
│ Partitioning            │ Hash/round-robin for parallelism      │
│ Static Membership       │ group.instance.id avoids rebalance    │
└─────────────────────────────────────────────────────────────────┘
```

---

## APPENDIX B: COMPLEXITY CHEAT SHEET

```
┌─────────────────────────────────────────────────────────────────┐
│ Operation                      │ Time Complexity               │
├─────────────────────────────────────────────────────────────────┤
│ Produce (append to log)        │ O(1) amortized (sequential)   │
│ Consume (read from offset)     │ O(log S) segment lookup +     │
│                                │ O(log E) index lookup +       │
│                                │ O(1) sequential read           │
│                                │ (S=segments, E=index entries)  │
│ Offset lookup by timestamp     │ O(log S) + O(log E) timeindex │
│ Segment deletion (retention)   │ O(1) per segment (file delete)│
│ Log compaction                 │ O(N) scan + O(N) rewrite      │
│ Leader election                │ O(1) per partition             │
│ Consumer rebalance             │ O(P) where P = partitions     │
│ Partition lookup by key        │ O(1) hash                     │
│ Metadata refresh               │ O(T * P) topics * partitions  │
│ ISR update                     │ O(1) per replica              │
└─────────────────────────────────────────────────────────────────┘

SPACE COMPLEXITY:
  - Per partition: O(retention_bytes) for log data
  - Per partition index: O(log_size / index_interval_bytes)
  - Per broker metadata: O(total_partitions_in_cluster)
  - Per consumer group: O(num_partitions) for offset tracking
  - __consumer_offsets topic: O(groups * partitions * 2)
    (2 = key + value per committed offset)
```

---

## APPENDIX C: QUICK-FIRE Q&A BANK

```
Q: "What's the maximum message size in Kafka?"
A: "Default max.message.bytes = 1 MB per message. Can be increased
   but must also increase replica.fetch.max.bytes and consumer
   fetch.max.bytes. For large payloads (> 1 MB), the pattern is to
   store the payload in S3/blob storage and send a reference
   message to Kafka (claim check pattern)."

Q: "How does Kafka handle backpressure?"
A: "Producer-side: buffer.memory (default 32 MB) limits how much
   unsent data the producer buffers. When full, the producer blocks
   for max.block.ms (60s), then throws an exception. Consumer-side:
   backpressure is natural -- consumer polls at its own pace. If it
   slows down, lag increases, but the broker is unaffected."

Q: "What happens if a consumer processes a message but crashes
   before committing the offset?"
A: "On restart, the consumer re-reads from the last committed offset.
   It will reprocess some messages. This is at-least-once delivery.
   The consumer must be idempotent to handle duplicates safely."

Q: "Can Kafka guarantee exactly-once delivery to an external
   database?"
A: "Not natively. Exactly-once in Kafka is scoped to Kafka-to-Kafka
   operations (read from topic, process, write to topic). For
   Kafka-to-database, you need an idempotent sink (upsert with
   dedup key) or the outbox pattern with a transactional database."

Q: "How does Kafka compare to Amazon SQS?"
A: "SQS is a managed queue: push-based, per-message ack, at-least-
   once delivery, no ordering (standard) or FIFO (limited throughput).
   Kafka is a distributed log: pull-based, offset-based consumption,
   per-partition ordering, unlimited consumers via consumer groups,
   message replay. SQS is simpler; Kafka is more powerful for
   streaming. SQS maxes at ~3K msg/s per FIFO queue; Kafka handles
   millions."

Q: "How does Kafka compare to RabbitMQ?"
A: "RabbitMQ: smart broker, dumb consumer. Broker tracks delivery
   state per consumer, routes messages via exchanges and bindings,
   supports complex routing (topic, fanout, headers). Kafka: dumb
   broker, smart consumer. Broker just appends to a log; consumer
   tracks its own offset. RabbitMQ is better for complex routing
   and low-latency task queues. Kafka is better for high-throughput
   event streaming and replay."

Q: "What is KRaft and why does it replace ZooKeeper?"
A: "KRaft embeds the Raft consensus protocol into Kafka brokers for
   metadata management. Benefits: no separate ZooKeeper cluster to
   operate, faster controller failover (~5s vs ~30s), support for
   more partitions per cluster (millions vs hundreds of thousands),
   single security model (Kafka SASL instead of ZK ACLs)."

Q: "How do you monitor a Kafka cluster?"
A: "Key metrics: (1) Under-replicated partitions (ISR < RF), (2)
   Consumer lag (growing = consumer can't keep up), (3) Request
   rate and latency (produce, fetch, metadata), (4) Disk usage
   per broker, (5) Network utilization, (6) GC pause time. Tools:
   Kafka JMX metrics + Prometheus + Grafana, Burrow for lag
   monitoring, Cruise Control for rebalancing."

Q: "What is tiered storage?"
A: "Kafka's tiered storage (KIP-405) offloads old log segments
   from local disk to cheaper remote storage (S3, HDFS). Hot data
   stays on local NVMe for fast reads. Cold data on S3 for
   long-term retention. Reduces storage cost by 5-10x for
   long-retention topics. Available in Confluent Platform and
   community builds."

Q: "How does Schema Registry work with Kafka?"
A: "Schema Registry (Confluent) stores Avro/Protobuf/JSON schemas
   with compatibility checks. Producers register schemas and embed
   a schema ID in each message header. Consumers fetch the schema
   by ID on first encounter and cache it. Benefits: backward/forward
   compatibility enforcement, no need to embed schema in every
   message (saves bandwidth), schema evolution without breaking
   consumers."

Q: "What is MirrorMaker and when would you use it?"
A: "MirrorMaker 2 replicates topics across Kafka clusters for
   disaster recovery or geo-distributed deployments. It's a Kafka
   Connect source connector that reads from a source cluster and
   writes to a destination cluster. Preserves offsets, topic
   configs, and consumer group state. Latency: typically 10-100ms
   depending on network distance."
```

---

## APPENDIX D: KAFKA VS PULSAR VS KINESIS

```
┌──────────────────────────────────────────────────────────────────────┐
│ Feature             │ Kafka            │ Pulsar           │ Kinesis  │
├──────────────────────────────────────────────────────────────────────┤
│ Storage             │ Broker-local     │ BookKeeper       │ Managed  │
│                     │ commit log       │ (separate layer) │ (S3)     │
├──────────────────────────────────────────────────────────────────────┤
│ Compute/Storage     │ Coupled (broker  │ Decoupled (broker│ Decoupled│
│ Separation          │ stores data)     │ is stateless)    │ (managed)│
├──────────────────────────────────────────────────────────────────────┤
│ Ordering            │ Per-partition    │ Per-partition    │ Per-shard│
├──────────────────────────────────────────────────────────────────────┤
│ Consumer Model      │ Pull (poll)      │ Push + Pull      │ Pull     │
├──────────────────────────────────────────────────────────────────────┤
│ Multi-Tenancy       │ Limited (topic   │ Native (tenants, │ Per-     │
│                     │ ACLs)            │ namespaces)      │ account  │
├──────────────────────────────────────────────────────────────────────┤
│ Exactly-Once        │ Yes (EOS)        │ Yes (txn)        │ No       │
├──────────────────────────────────────────────────────────────────────┤
│ Max Throughput       │ Millions msg/s   │ Millions msg/s   │ ~1K/s   │
│ (per shard/topic)   │ (per partition)  │ (per partition)  │ per shard│
├──────────────────────────────────────────────────────────────────────┤
│ Scaling Partitions  │ Manual (add, no │ Manual           │ Merge/   │
│                     │ remove)          │                  │ split    │
├──────────────────────────────────────────────────────────────────────┤
│ Geo-Replication     │ MirrorMaker 2    │ Built-in         │ Cross-   │
│                     │ (separate tool)  │ (native)         │ region   │
├──────────────────────────────────────────────────────────────────────┤
│ Tiered Storage      │ KIP-405 (newer)  │ Native (BK +     │ Always   │
│                     │                  │ offload to S3)   │ managed  │
├──────────────────────────────────────────────────────────────────────┤
│ Operational         │ High (brokers +  │ Higher (brokers +│ Zero     │
│ Complexity          │ KRaft/ZK)        │ BK + ZK)         │ (managed)│
├──────────────────────────────────────────────────────────────────────┤
│ Best For            │ Event streaming, │ Multi-tenant     │ Low-ops  │
│                     │ high throughput  │ messaging, geo-  │ streaming│
│                     │                  │ distributed      │ on AWS   │
└──────────────────────────────────────────────────────────────────────┘

WHEN TO CHOOSE:
  Kafka:  Default choice for event streaming. Largest ecosystem
          (Connect, Streams, KSQL). Best community support.
  Pulsar: Multi-tenant environments, need geo-replication out of
          box, want compute-storage separation without tiered
          storage hacks.
  Kinesis: AWS-native, zero ops, small-to-medium scale, team
           doesn't want to manage infrastructure.
```

---

## APPENDIX E: WHITEBOARD DRAWING ORDER

```
Draw in this order for maximum clarity:

STEP 1: Draw three boxes left to right: Producer, Broker, Consumer
  "These are the three actors."

STEP 2: Inside Broker, draw partitions as stacked rectangles
  "Each topic is split into partitions. Each partition is an
  append-only log."

STEP 3: Draw arrows: Producer -> Broker (write path), Broker -> Consumer (read path)
  "Write path: producer batches + sends. Read path: consumer polls
  by offset."

STEP 4: Add replication arrows between brokers
  "Each partition has a leader and followers. Followers fetch from
  the leader."

STEP 5: Add the controller box below/above the brokers
  "Controller manages metadata: leader elections, ISR lists, topic
  configs."

STEP 6: Zoom into one partition -- draw segment files
  "Each partition is a sequence of segment files. Each segment has
  a .log (data) and .index (sparse offset index)."

STEP 7: Draw the consumer group assignment
  "Consumer group G has 3 consumers. Each consumer owns a subset
  of partitions. Maximum parallelism = partition count."

STEP 8: Draw the replication protocol (leader + 2 followers with HW/LEO)
  "Leader writes locally, followers fetch. High watermark advances
  when all ISR members have the data."

TIMING:
  Steps 1-5: Phase 2 (HLD) -- 5-7 minutes
  Step 6:    Phase 3 (Commit Log deep dive) -- 2 minutes
  Step 7:    Phase 4 (Consumer Groups) -- 2 minutes
  Step 8:    Phase 5 (Replication) -- 2 minutes
```

---

## APPENDIX F: ANTI-PATTERNS TO AVOID

```
Anti-Pattern 1: "Single partition for ordering"
  DON'T: use one partition per topic for total ordering
  DO: partition by entity key (userId, orderId) for per-entity order
  WHY: single partition = one consumer max. Throughput ceiling of
  ~10-25 MB/s. Per-entity ordering handles 99% of real use cases.

Anti-Pattern 2: "Auto-commit with processing logic"
  DON'T: enable auto-commit and process messages after poll
  DO: use manual commit after successful processing
  WHY: auto-commit acks messages before they're processed. A crash
  between poll and processing = data loss. This is the #1 Kafka
  bug in production systems.

Anti-Pattern 3: "One consumer per message"
  DON'T: treat Kafka like SQS with per-message ack/nack
  DO: process batches and commit offsets periodically
  WHY: Kafka's offset model is designed for batch processing.
  Per-message commits overwhelm __consumer_offsets and reduce
  throughput by 100x.

Anti-Pattern 4: "Huge messages in Kafka"
  DON'T: send 10 MB payloads through Kafka
  DO: store large payloads in S3/blob storage, send a reference
  (claim check pattern) through Kafka
  WHY: large messages bloat the commit log, slow down replication,
  and blow up consumer memory. Kafka is optimized for high volume
  of small-to-medium messages.

Anti-Pattern 5: "Ignoring consumer lag"
  DON'T: deploy consumers without lag monitoring
  DO: alert when lag exceeds a threshold (e.g., 10K messages)
  WHY: unbounded lag means the consumer is falling behind. If lag
  exceeds retention, messages are deleted before consumption =
  silent data loss. Lag monitoring is the #1 operational metric.

Anti-Pattern 6: "Changing partition count on a live topic"
  DON'T: increase partitions on a topic with key-based routing
  DO: create a new topic with the desired partition count and
  migrate consumers
  WHY: hash(key) % old_N != hash(key) % new_N. Repartitioning
  breaks key affinity. All keys get shuffled. Stateful consumers
  lose their partition locality.

Anti-Pattern 7: "acks=0 for important data"
  DON'T: use fire-and-forget for data that matters
  DO: use acks=all + min.insync.replicas=2 for critical data
  WHY: acks=0 can lose messages if the broker is down, the network
  drops packets, or the producer buffer overflows. There's no
  retry because the producer doesn't know the message was lost.

Anti-Pattern 8: "Synchronous produce calls in a hot path"
  DON'T: call producer.send().get() in a request handler
  DO: use async send with a callback, or fire-and-forget for
  non-critical events
  WHY: synchronous send blocks the request thread until the broker
  acks. With acks=all, that's 5-10ms of blocking per message.
  At 10K requests/sec, you need 100 threads just for Kafka sends.

Anti-Pattern 9: "No dead letter topic"
  DON'T: silently drop or infinite-retry poison pill messages
  DO: after N retries, send the message to a dead letter topic
  (DLT) for manual inspection
  WHY: a malformed message can block a partition's consumer
  indefinitely. The DLT pattern quarantines bad messages without
  stopping the pipeline. Process the DLT offline.

Anti-Pattern 10: "Replication factor = 1 in production"
  DON'T: run with RF=1 for any production topic
  DO: RF=3 minimum, with min.insync.replicas=2
  WHY: RF=1 means a single broker failure = data loss AND
  partition unavailability. There's no replica to failover to.
  This is fine for dev/test, never for production.
```

---

## APPENDIX G: INTERVIEW TIMING CHEAT SHEET

```
┌─────────────────────────────────────────────────────────────────┐
│                    35-MINUTE TIMELINE                           │
├──────────┬──────────────────────────────────────────────────────┤
│  0:00    │  Phase 1: Clarify requirements                      │
│          │  - Ask 6-8 targeted questions (3 buckets)            │
│          │  - Write scope table on whiteboard                   │
│          │  - State assumptions aloud                           │
│  2:30    │  TRANSITION: "Let me draw the high-level architecture"│
├──────────┼──────────────────────────────────────────────────────┤
│  2:30    │  Phase 2: High-Level Architecture                   │
│          │  - Draw Producer -> Broker -> Consumer               │
│          │  - Draw partitions inside brokers                    │
│          │  - Draw replication arrows + controller              │
│          │  - Name all 5 core components                        │
│  9:00    │  TRANSITION: "Let me dive into the commit log"      │
├──────────┼──────────────────────────────────────────────────────┤
│  9:00    │  Phase 3: Deep Dive -- Commit Log                    │
│          │  - Draw segment files (.log, .index, .timeindex)    │
│          │  - Explain offset lookup (binary search + scan)     │
│          │  - Zero-copy transfer diagram                       │
│          │  - Page cache strategy                              │
│          │  - Log compaction (if time)                          │
│ 19:00    │  TRANSITION: "Now the consumer group protocol"      │
├──────────┼──────────────────────────────────────────────────────┤
│ 19:00    │  Phase 4: Deep Dive -- Consumer Groups               │
│          │  - Partition assignment rule                         │
│          │  - Eager vs cooperative rebalancing                  │
│          │  - Offset commit strategies (auto vs manual)         │
│          │  - Exactly-once chain (idempotent + transactional)  │
│ 25:00    │  TRANSITION: "Let me cover replication"             │
├──────────┼──────────────────────────────────────────────────────┤
│ 25:00    │  Phase 5: Replication                                │
│          │  - ISR model (LEO, HW, membership)                  │
│          │  - acks modes tradeoff table                        │
│          │  - min.insync.replicas rationale                    │
│          │  - Leader election + unclean election                │
│ 29:00    │  TRANSITION: "A few scaling considerations"         │
├──────────┼──────────────────────────────────────────────────────┤
│ 29:00    │  Phase 6: Scaling                                    │
│          │  - Hash vs round-robin partitioning                 │
│          │  - Partition count sizing formula                   │
│          │  - Consumer parallelism = partition count            │
│          │  - Producer batching tuning table                   │
│ 33:00    │  TRANSITION: "Let me name some edge cases"          │
├──────────┼──────────────────────────────────────────────────────┤
│ 33:00    │  Phase 7: Edge Cases                                 │
│          │  - Consumer rebalance storm (mitigation)            │
│          │  - Partition hot spots (salted keys)                │
│          │  - Ordering across partitions (per-entity key)      │
│          │  - Exactly-once overhead (idempotent consumer alt)  │
│          │  - Log compaction tombstone expiry                   │
│          │  - Split brain defense (epoch fencing)              │
│ 35:00    │  END                                                 │
└──────────┴──────────────────────────────────────────────────────┘

PACING TIPS:
 - If Phase 3 runs long, skip log compaction detail (cover in
   edge cases if asked).
 - If the interviewer deep-dives on replication in Phase 2,
   compress Phase 5 and fold acks/ISR into Phase 2.
 - Always leave 2 minutes for edge cases -- naming rebalance
   storms and hot spots is high-signal for Staff evaluation.
 - If you finish early, offer: "I can also discuss tiered
   storage, schema evolution, or multi-datacenter replication
   with MirrorMaker 2."
```
