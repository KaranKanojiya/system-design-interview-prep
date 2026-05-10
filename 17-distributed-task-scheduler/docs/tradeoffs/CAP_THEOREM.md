# CAP Theorem & Distributed Tradeoffs in the Distributed Task Scheduler (Airflow/Celery-like)

> Interview-ready reference for a Senior Java developer.
> A distributed task scheduler has a SPLIT CAP requirement: CP for task state
> (no double-execution, no lost tasks) and AP for monitoring/heartbeats
> (stale metrics are acceptable briefly). This split is THE key insight
> interviewers look for.

---

## Table of Contents

| Section | Key Point |
|---------|-----------|
| Split CAP Requirement | CP for task state + leader election, AP for monitoring + heartbeats |
| ACID for Task Execution | Exactly-once via conditional writes + idempotent processing |
| Exactly-Once Semantics | At-least-once delivery + idempotent task claiming |
| Consensus Requirements | Leader election (Bully), task claiming (CAS), DAG state transitions |
| Partition Tolerance | What happens when workers cannot reach the scheduler |
| Network Partition Scenarios | Queue isolation, split-brain, stale heartbeats |
| Split-Brain Prevention | Fencing tokens, lease TTLs, monotonic epoch counters |
| Industry Comparison | Airflow, Celery, Temporal, Prefect architectures |
| PACELC Analysis | When no partition: latency vs consistency choices |
| Interview Q&A | Ready-to-use answers |

---

## Split CAP Requirement -- The Core Insight

### The Key Argument

```
  +----------------------------------------------------------------------+
  |  THE KEY INSIGHT: A task scheduler has a SPLIT CAP requirement.       |
  |  Task State = CP. Monitoring = AP. Know which is which.              |
  +----------------------------------------------------------------------+
  |                                                                      |
  |         Consistency (C)                                              |
  |            /\                                                        |
  |           /  \                                                       |
  |          / CP \                                                      |
  |         /      \     <--- Task Claiming (no double-execution)        |
  |        / TASK   \    <--- State Transitions (QUEUED->RUNNING->DONE)  |
  |       / STATE    \   <--- Leader Election (single leader always)     |
  |      / DAG DEPS   \  <--- Dependency Resolution (correct ordering)  |
  |     /______________\                                                 |
  |  Availability (A) --- Partition Tolerance (P)                        |
  |                                                                      |
  |          AP                                                          |
  |         /  \                                                         |
  |        /    \    <--- Worker Heartbeats (stale for 30s is OK)        |
  |       / MON  \   <--- Queue Depth Metrics (delayed by seconds OK)   |
  |      /________\  <--- Execution Logs (eventual consistency fine)      |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Why CP for Task State

```
  +----------------------------------------------------------------------+
  |  THE COST OF GETTING IT WRONG -- REAL SCENARIOS                       |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Scenario 1: Double Execution                                        |
  |  ----------------------------------------------------------------   |
  |  Task "charge-customer-001" is in QUEUED state.                      |
  |  Due to network partition, two workers BOTH claim it.                 |
  |  Both execute: customer charged TWICE for the same order.            |
  |  Refund required. Customer trust lost. Regulatory risk.              |
  |                                                                      |
  |  Scenario 2: Lost Task                                                |
  |  ----------------------------------------------------------------   |
  |  Task "generate-monthly-report" is submitted.                        |
  |  Scheduler acknowledges but loses state during partition.            |
  |  Report never generated. SLA breach. Customer escalation.            |
  |                                                                      |
  |  Scenario 3: Incorrect Dependency Resolution                          |
  |  ----------------------------------------------------------------   |
  |  DAG: Extract -> Transform -> Load                                   |
  |  Partition causes stale read: Transform thinks Extract is complete.  |
  |  Transform runs on empty data. Load pushes garbage to production DB. |
  |  Data corruption. Hours to detect and fix.                           |
  |                                                                      |
  |  Scenario 4: Split-Brain Leader                                       |
  |  ----------------------------------------------------------------   |
  |  Two scheduler nodes both believe they are the leader.               |
  |  Both schedule the same cron DAG.                                    |
  |  Result: every cron job executes twice.                              |
  |  Wasted resources. Duplicate side effects. Data inconsistency.       |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Component-Level CAP Decisions

```
  +----------------------------------------------------------------------+
  |  COMPONENT-LEVEL CAP DECISIONS                                        |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Component              | CAP  | Why                                 |
  |  -----------------------+------+-------------------------------------+
  |  Task State Transitions  | CP   | QUEUED->RUNNING must be atomic.    |
  |  (TaskStatus enum)       |      | Two workers must not both claim    |
  |                          |      | the same task.                     |
  |  -----------------------+------+-------------------------------------+
  |  Leader Election         | CP   | Must have exactly ONE leader.      |
  |  (LeaderElectionService) |      | Two leaders = duplicate DAG        |
  |                          |      | scheduling. Prefer unavailability  |
  |                          |      | over dual leadership.              |
  |  -----------------------+------+-------------------------------------+
  |  DAG Dependency State    | CP   | Transform must NOT start until     |
  |  (DependencyResolver)    |      | Extract is truly COMPLETED.        |
  |                          |      | Stale read = premature execution.  |
  |  -----------------------+------+-------------------------------------+
  |  Execution History       | AP   | Missing the last 5 seconds of      |
  |  (ExecutionRepository)   |      | execution logs is acceptable.      |
  |                          |      | Logs are append-only, idempotent.  |
  |  -----------------------+------+-------------------------------------+
  |  Worker Heartbeats       | AP   | A heartbeat 10 seconds stale is    |
  |  (WorkerService)         |      | acceptable. We only act on > 30s   |
  |                          |      | stale heartbeats (ALIVE_TIMEOUT).  |
  |  -----------------------+------+-------------------------------------+
  |  Queue Depth Metrics     | AP   | Auto-scaling can tolerate 30s      |
  |  (MonitoringService)     |      | delay in queue depth metrics.      |
  |                          |      | Scale decisions are not urgent.    |
  |  -----------------------+------+-------------------------------------+
  |  Task Priority Queue     | CP   | Priority ordering must be correct. |
  |  (TaskQueue)             |      | HIGH priority task must execute    |
  |                          |      | before LOW priority task.          |
  |  -----------------------+------+-------------------------------------+
  |  Cron Schedule State     | CP   | A cron trigger must fire exactly   |
  |  (CronParser/Scheduler)  |      | once per interval. Missing a       |
  |                          |      | trigger or double-firing are both  |
  |                          |      | unacceptable for production DAGs.  |
  |  -----------------------+------+-------------------------------------+
  |                                                                      |
  +----------------------------------------------------------------------+
```

---

## AP for Monitoring -- Eventually Consistent is Fine

### Why AP Works for Monitoring and Heartbeats

```
  +----------------------------------------------------------------------+
  |  MONITORING AND HEARTBEATS: AP IS ACCEPTABLE                          |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  What operators see on the dashboard:                                |
  |                                                                      |
  |  Queue Depth: HIGH=12  MEDIUM=5  LOW=3                              |
  |  Active Workers: 8/10                                                |
  |  Failed Tasks (last hour): 3                                        |
  |  Avg Task Duration: 42s (p50), 120s (p99)                           |
  |  Leader: scheduler-1 (heartbeat: 5s ago)                            |
  |                                                                      |
  |  This data is DISPLAY-ONLY for human operators.                      |
  |  A 10-second delay in these metrics causes NO harm:                  |
  |  - Operator sees queue depth = 12 (actual = 15). No damage.         |
  |  - Auto-scaler uses approximate queue depth. 30s lag is fine.       |
  |  - Dashboard shows 8 workers (actual = 7, one just died). Fine.     |
  |                                                                      |
  |  Delay tolerance:                                                    |
  |  +-----------------------------+-----------------------------------+ |
  |  | Delay        | Acceptable?  | Why                               | |
  |  +-----------------------------+-----------------------------------+ |
  |  | < 5s         | YES          | Real-time enough for dashboards   | |
  |  | 5-30s        | YES          | Auto-scaling decisions still valid| |
  |  | 30s-2min     | Marginal     | Stale heartbeats may delay        | |
  |  |              |              | failover detection                | |
  |  | > 2min       | NO           | Dead workers appear alive. Tasks  | |
  |  |              |              | stuck in RUNNING state.           | |
  |  +-----------------------------+-----------------------------------+ |
  |                                                                      |
  |  Why AP not CP for monitoring:                                       |
  |  - Strong consistency for metrics = blocking reads on every poll     |
  |  - 100 dashboard clients * 1 poll/sec = 100 consistent reads/sec    |
  |  - This wastes DynamoDB RCU for zero operational benefit             |
  |  - Redis cache with 5s TTL is sufficient for all monitoring reads    |
  +----------------------------------------------------------------------+
```

### Worker Heartbeat Architecture

```
  +----------------------------------------------------------------------+
  |  WORKER HEARTBEAT FLOW -- AP with BOUNDED STALENESS                   |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Worker-A (running task)                                             |
  |       |                                                              |
  |       | (1) Every 10 seconds: heartbeat update                       |
  |       |     DynamoDB: UpdateItem worker-A SET lastHeartbeat = now()  |
  |       v                                                              |
  |  +-------------------+                                               |
  |  | DynamoDB          |  (2) Eventually consistent reads by default   |
  |  | (worker table)    |      Strong consistent reads cost 2x RCU     |
  |  +-------------------+                                               |
  |       |                                                              |
  |       | (3) Scheduler reads heartbeats every 15 seconds              |
  |       |     Uses eventually consistent reads (half the cost)         |
  |       v                                                              |
  |  +-------------------+                                               |
  |  | Failover Service  |  (4) Check: lastHeartbeat > ALIVE_TIMEOUT?   |
  |  | (FailoverService) |      ALIVE_TIMEOUT = 30 seconds              |
  |  +-------------------+      If yes -> worker is dead -> reassign    |
  |       |                                                              |
  |       | Why 30 seconds and not 10 seconds?                           |
  |       | - Eventual consistency delay: up to 1 second                 |
  |       | - Network hiccup: heartbeat delayed by 5 seconds             |
  |       | - 30 seconds gives 2-3 missed heartbeats before declaring    |
  |       |   a worker dead. Avoids false positives.                     |
  |       |                                                              |
  |  Total worst-case detection time:                                    |
  |    Worker dies at T=0                                                |
  |    Last heartbeat was at T=-10s (10s heartbeat interval)             |
  |    Scheduler checks at T=+15s (15s polling interval)                 |
  |    Reads stale heartbeat from T=-10s (1s consistency lag)            |
  |    Staleness = 15 - (-10) = 25s < 30s -> not yet dead               |
  |    Scheduler checks again at T=+30s                                  |
  |    Staleness = 30 - (-10) = 40s > 30s -> DEAD. Reassign tasks.     |
  |                                                                      |
  |  Worst-case detection: ~30-45 seconds after worker death.            |
  |  Acceptable for a task scheduler (not a real-time trading system).   |
  +----------------------------------------------------------------------+
```

---

## ACID for Task Execution -- Exactly-Once Semantics

### The Key Design Decision

```
  +----------------------------------------------------------------------+
  |  TASK CLAIMING: CONDITIONAL WRITE = ATOMIC CAS                        |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  WHY conditional writes?                                             |
  |  - Multiple workers poll the same SQS queue                          |
  |  - SQS delivers at-least-once (same message can be delivered twice)  |
  |  - Without CAS, two workers both execute the same task               |
  |  - DynamoDB conditional write = atomic compare-and-swap               |
  |                                                                      |
  |  HOW it works:                                                       |
  |                                                                      |
  |  Worker-A receives message from SQS:                                 |
  |    { taskId: "transform", dagRunId: "run-001", status: "QUEUED" }    |
  |                                                                      |
  |  Worker-A attempts to claim:                                         |
  |    UpdateItem:                                                       |
  |      PK: "RUN#run-001", SK: "TASK#transform"                        |
  |      SET status = "RUNNING", workerId = "worker-A",                  |
  |          startedAt = "2026-05-09T02:01:00Z"                          |
  |      ConditionExpression: "status = :queued"                         |
  |                                                                      |
  |  Case 1: Worker-A is first -> condition passes -> claim succeeds     |
  |  Case 2: Worker-B already claimed -> status = "RUNNING" (not QUEUED) |
  |           -> ConditionalCheckFailedException -> Worker-A backs off    |
  |                                                                      |
  |  ACID mapping:                                                       |
  |  +-------------------+--------------------------------------------+ |
  |  | Property          | How Achieved                               | |
  |  +-------------------+--------------------------------------------+ |
  |  | Atomicity         | DynamoDB conditional write is atomic.       | |
  |  |                   | Status + workerId + startedAt all change    | |
  |  |                   | together or not at all.                     | |
  |  +-------------------+--------------------------------------------+ |
  |  | Consistency       | ConditionExpression ensures only QUEUED     | |
  |  |                   | tasks can transition to RUNNING.            | |
  |  |                   | State machine invariants preserved.         | |
  |  +-------------------+--------------------------------------------+ |
  |  | Isolation         | Conditional write acts as a mutex.          | |
  |  |                   | Two concurrent claims: exactly one wins.    | |
  |  |                   | DynamoDB resolves at the partition level.   | |
  |  +-------------------+--------------------------------------------+ |
  |  | Durability        | DynamoDB writes to 3 AZs synchronously.    | |
  |  |                   | Once the conditional write returns 200,     | |
  |  |                   | the claim is durable.                       | |
  |  +-------------------+--------------------------------------------+ |
  +----------------------------------------------------------------------+
```

### Task State Machine (Valid Transitions)

```
  +----------------------------------------------------------------------+
  |  TASK STATUS STATE MACHINE (from TaskStatus.java)                     |
  +----------------------------------------------------------------------+
  |                                                                      |
  |                  +----------+                                        |
  |                  | PENDING  |   (DAG submitted, deps not resolved)   |
  |                  +----+-----+                                        |
  |                       |                                              |
  |                       | deps resolved                                |
  |                       v                                              |
  |                  +----------+                                        |
  |                  | QUEUED   |   (in SQS, waiting for worker)         |
  |                  +----+-----+                                        |
  |                       |                                              |
  |             +---------+---------+                                    |
  |             |                   |                                    |
  |             | worker claims     | timeout / cancel                   |
  |             v                   v                                    |
  |        +----------+     +------------+                               |
  |        | ASSIGNED |     | CANCELLED  |   (terminal)                  |
  |        +----+-----+     +------------+                               |
  |             |                                                        |
  |             | execution starts                                       |
  |             v                                                        |
  |        +----------+                                                  |
  |        | RUNNING  |                                                  |
  |        +----+-----+                                                  |
  |             |                                                        |
  |    +--------+--------+--------+                                      |
  |    |        |        |        |                                      |
  |    | ok     | fail   | retry  | timeout                              |
  |    v        v        v        v                                      |
  | +------+ +------+ +--------+ +----------+                           |
  | |COMPLT| |FAILED| |RETRYING| | TIMED_OUT|  (terminal)               |
  | +------+ +------+ +---+----+ +----------+                           |
  |                        |                                             |
  |                        | re-enqueue                                  |
  |                        v                                             |
  |                   +----------+                                       |
  |                   | QUEUED   |   (back in queue for retry)           |
  |                   +----------+                                       |
  |                                                                      |
  |  CP invariants:                                                      |
  |  1. A task can only be RUNNING on ONE worker at a time               |
  |  2. QUEUED -> RUNNING requires conditional write (CAS)               |
  |  3. Terminal states are irreversible (no COMPLETED -> RUNNING)       |
  |  4. RETRYING always goes back to QUEUED (never directly to RUNNING)  |
  +----------------------------------------------------------------------+
```

---

## Exactly-Once Semantics -- At-Least-Once + Idempotency

### The Formula

```
  +----------------------------------------------------------------------+
  |  EXACTLY-ONCE = AT-LEAST-ONCE DELIVERY + IDEMPOTENT PROCESSING        |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Layer 1: AT-LEAST-ONCE DELIVERY (SQS guarantee)                     |
  |  ----------------------------------------------------------------   |
  |  SQS guarantees every message is delivered at least once.            |
  |  If worker doesn't delete the message within VisibilityTimeout:      |
  |    -> SQS redelivers the message to another worker.                  |
  |  This ensures no task is ever lost (availability of delivery).       |
  |                                                                      |
  |  Layer 2: IDEMPOTENT CLAIMING (DynamoDB conditional write)           |
  |  ----------------------------------------------------------------   |
  |  Worker receives SQS message for task "transform".                   |
  |  Worker attempts DynamoDB conditional write:                         |
  |    SET status = RUNNING WHERE status = QUEUED                        |
  |  If another worker already claimed it:                               |
  |    -> ConditionalCheckFailedException -> skip (don't execute)        |
  |  This ensures no task is executed more than once.                    |
  |                                                                      |
  |  Combined:                                                           |
  |  - SQS delivers message to Worker-A AND Worker-B (at-least-once)    |
  |  - Worker-A's conditional write succeeds (status = RUNNING)          |
  |  - Worker-B's conditional write fails (status != QUEUED)             |
  |  - Only Worker-A executes the task                                   |
  |  - Result: EXACTLY-ONCE execution                                    |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Numbered Call Chain -- Exactly-Once Execution

```
1.  Scheduler enqueues task "transform" to SQS:
      { taskId: "transform", dagRunId: "run-001", idempotencyKey: "transform-run-001-1" }
2.  DynamoDB: task status = QUEUED (set by scheduler before enqueue)
3.  SQS delivers message to Worker-A (long polling, ReceiveMessage)
4.  SQS also delivers message to Worker-B (at-least-once redelivery)
5.  Worker-A: DynamoDB UpdateItem SET status=RUNNING WHERE status=QUEUED
      -> SUCCESS (Worker-A is the winner)
6.  Worker-B: DynamoDB UpdateItem SET status=RUNNING WHERE status=QUEUED
      -> FAIL (ConditionalCheckFailedException, status is already RUNNING)
7.  Worker-B: Delete SQS message, log "task already claimed", move on
8.  Worker-A: Execute the transform task (call external service)
9.  Worker-A: DynamoDB UpdateItem SET status=COMPLETED, result={...}
10. Worker-A: Delete SQS message (ACK)
11. Worker-A: Publish "TaskCompleted" event to EventBridge
12. Scheduler: Receives event, resolves downstream dependencies
```

### Idempotency Key Design

```
  +----------------------------------------------------------------------+
  |  IDEMPOTENCY KEY STRUCTURE                                            |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Format: {taskId}-{dagRunId}-attempt-{attemptNumber}                 |
  |                                                                      |
  |  Example:                                                            |
  |    transform-run-etl-001-20260509-attempt-1                          |
  |    transform-run-etl-001-20260509-attempt-2  (retry)                 |
  |    transform-run-etl-001-20260509-attempt-3  (second retry)          |
  |                                                                      |
  |  Why include attemptNumber:                                          |
  |  - Each retry is a DIFFERENT execution attempt                       |
  |  - Attempt 1 failed but attempt 2 should be allowed                  |
  |  - If idempotencyKey were just taskId+dagRunId:                      |
  |    -> SQS FIFO dedup would reject the retry message (within 5 min)  |
  |    -> Task would never be retried                                    |
  |                                                                      |
  |  Deduplication layers:                                               |
  |  +------------------------------------------------------------------+|
  |  | Layer          | Mechanism                    | Window           ||
  |  +----------------+------------------------------+------------------+|
  |  | SQS FIFO       | MessageDeduplicationId       | 5 minutes        ||
  |  |                | (idempotencyKey)             |                  ||
  |  +----------------+------------------------------+------------------+|
  |  | DynamoDB       | Conditional write            | Until task       ||
  |  |                | (status = QUEUED)            | completes        ||
  |  +----------------+------------------------------+------------------+|
  |  | Worker         | Check execution record       | Until TTL        ||
  |  |                | before executing             | expires          ||
  |  +----------------+------------------------------+------------------+|
  |  | Application    | Task-specific idempotency    | Task-defined     ||
  |  |                | (e.g., charge has chargeId)  |                  ||
  |  +----------------+------------------------------+------------------+|
  +----------------------------------------------------------------------+
```

### What If the Worker Crashes Mid-Execution?

```
  +----------------------------------------------------------------------+
  |  WORKER CRASH DURING TASK EXECUTION                                   |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Timeline:                                                           |
  |    T=0:   Worker-A claims task "transform" (status = RUNNING)        |
  |    T=30:  Worker-A starts executing (HTTP call to transform service) |
  |    T=45:  Worker-A CRASHES (JVM killed, node dies, OOM)              |
  |    T=60:  Worker-A's heartbeat is now 15 seconds stale               |
  |    T=90:  FailoverService detects Worker-A is dead (ALIVE_TIMEOUT=30)|
  |                                                                      |
  |  FailoverService.reassignTasks():                                    |
  |    1. Find all tasks RUNNING on Worker-A (DynamoDB query by workerId)|
  |    2. For each task: DynamoDB UpdateItem SET status = QUEUED          |
  |    3. Task is re-enqueued to SQS (attempt 2)                         |
  |    4. Worker-B picks it up, claims it (conditional write succeeds)   |
  |    5. Worker-B executes the task                                     |
  |                                                                      |
  |  BUT: was the transform service call completed before the crash?     |
  |                                                                      |
  |  Case A: Transform service call COMPLETED (HTTP 200 returned)        |
  |    Worker-A crashed before writing COMPLETED to DynamoDB.            |
  |    Worker-B re-executes. Transform service receives duplicate call.  |
  |    SOLUTION: Transform service must be idempotent.                   |
  |      If transform(taskId="transform", dagRunId="run-001"):           |
  |        Check: "Did I already transform this data?"                   |
  |        If yes -> return cached result (no side effects)              |
  |        If no -> perform transform, store result                      |
  |                                                                      |
  |  Case B: Transform service call NOT completed                        |
  |    Worker-A crashed mid-call. Service may have partial state.        |
  |    Worker-B re-executes. Service processes from scratch.             |
  |    SOLUTION: Service writes to staging area, then atomically swaps.  |
  |      Transform writes to staging_table, then renames to output_table.|
  |      If crash during staging -> staging is incomplete -> next attempt |
  |      overwrites staging cleanly.                                     |
  |                                                                      |
  |  KEY INSIGHT: Exactly-once at the SCHEDULER level, but downstream    |
  |  services must ALSO be idempotent for true end-to-end exactly-once.  |
  +----------------------------------------------------------------------+
```

---

## Consensus Requirements

### Leader Election (Bully Algorithm)

```
  +----------------------------------------------------------------------+
  |  BULLY ALGORITHM -- CONSENSUS FOR SINGLE LEADER                       |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Our implementation (LeaderElectionService.java):                    |
  |  - N scheduler nodes, each with a priority (integer)                 |
  |  - The highest-priority ALIVE node becomes the leader                |
  |  - Ties broken by nodeId (lexicographic)                             |
  |                                                                      |
  |  Election process (from code):                                       |
  |                                                                      |
  |  Step 1: Find all alive nodes                                        |
  |    allNodes.stream().filter(node -> node.isAlive(ALIVE_TIMEOUT))     |
  |                                                                      |
  |  Step 2: Each node challenges higher-priority nodes                  |
  |    For node with priority=5:                                         |
  |      higherPriorityNodes = nodes with priority > 5                   |
  |      (or priority == 5 and nodeId > this.nodeId)                     |
  |    If no higher-priority nodes exist -> this node is the winner      |
  |                                                                      |
  |  Step 3: Highest-priority node wins                                  |
  |    max(Comparator.comparingInt(getPriority)                          |
  |        .thenComparing(getNodeId))                                    |
  |                                                                      |
  |  Step 4: Update all nodes -- only winner is leader                   |
  |    for (node : allNodes):                                            |
  |      node.setLeader(node.getNodeId().equals(leader.getNodeId()))     |
  |      nodeRepo.save(node)                                             |
  |                                                                      |
  |  CONSENSUS PROPERTY:                                                 |
  |  - Agreement: all nodes agree on the same leader                     |
  |  - Validity: the leader is an alive node with highest priority       |
  |  - Termination: election completes in O(N) steps                     |
  |  - Fault tolerance: tolerates N-1 node failures (as long as 1 alive) |
  |                                                                      |
  |  LIMITATION:                                                         |
  |  The Bully algorithm assumes reliable failure detection.             |
  |  If network partition makes a node APPEAR dead but it's alive:       |
  |    -> Two leaders possible (split-brain).                            |
  |    -> Mitigated by fencing tokens (see below).                       |
  +----------------------------------------------------------------------+
```

### Task Claiming (Distributed CAS)

```
  +----------------------------------------------------------------------+
  |  TASK CLAIMING -- DISTRIBUTED COMPARE-AND-SWAP                        |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Unlike leader election (one-time decision), task claiming happens   |
  |  thousands of times per minute. It must be:                          |
  |    - Fast (< 10ms per claim)                                         |
  |    - Correct (exactly one winner)                                    |
  |    - Scalable (1000+ workers competing for tasks)                    |
  |                                                                      |
  |  DynamoDB conditional write provides all three:                      |
  |    - Single-digit ms latency (within same region)                    |
  |    - Atomic CAS at the partition key level                           |
  |    - Scales horizontally (different tasks on different partitions)   |
  |                                                                      |
  |  Contention analysis:                                                |
  |  +------------------------------------------------------------------+|
  |  | Scenario           | Workers | Contention  | Resolution          ||
  |  +--------------------+---------+-------------+---------------------+|
  |  | Normal operation   | 10      | Low         | Each worker gets    ||
  |  |                    |         |             | different messages  ||
  |  |                    |         |             | from SQS.           ||
  |  +--------------------+---------+-------------+---------------------+|
  |  | SQS redelivery     | 2       | Moderate    | One wins CAS, one  ||
  |  |                    |         |             | backs off.          ||
  |  +--------------------+---------+-------------+---------------------+|
  |  | Failover reassign  | N       | High        | All workers try to ||
  |  | (batch of tasks    |         |             | claim reassigned   ||
  |  |  re-enqueued)      |         |             | tasks. CAS ensures ||
  |  |                    |         |             | one winner per task.||
  |  +--------------------+---------+-------------+---------------------+|
  |                                                                      |
  |  Hot partition risk:                                                  |
  |  If many workers try to claim the SAME task simultaneously:          |
  |    -> DynamoDB throttles writes to that partition key.                |
  |    -> Mitigated: SQS distributes different tasks to different workers|
  |    -> Normally each worker gets a unique message (no contention).    |
  +----------------------------------------------------------------------+
```

---

## Partition Tolerance -- What Happens When Things Break

### Partition Scenario 1: Workers Cannot Reach Scheduler

```
  +----------------------------------------------------------------------+
  |  PARTITION: WORKERS <-> SCHEDULER NETWORK SPLIT                       |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Scheduler (us-east-1a)         Workers (us-east-1b)                 |
  |       |                              |                               |
  |       | SQS is in us-east-1          | Workers can still reach SQS   |
  |       | (regional service)           | (SQS is a regional service)   |
  |       |                              |                               |
  |  CASE A: SQS accessible to both                                      |
  |  --------------------------------                                    |
  |  Workers continue processing tasks from SQS.                         |
  |  Workers write completion to DynamoDB (regional, accessible).        |
  |  Scheduler cannot directly communicate with workers (no heartbeat).  |
  |  BUT: scheduler reads task state from DynamoDB (sees COMPLETED).     |
  |  Effect: Dependency resolution continues. System works.              |
  |                                                                      |
  |  CASE B: SQS accessible to scheduler but not workers                 |
  |  ----------------------------------------------------------------   |
  |  Scheduler enqueues tasks to SQS. Messages pile up.                  |
  |  Workers cannot poll SQS. Queue depth grows.                         |
  |  CloudWatch alarm fires: queue depth > threshold for 5 minutes.      |
  |  Auto-scaler tries to add workers (but new workers also can't reach) |
  |  Effect: Tasks accumulate. No execution. Alerts fire.                |
  |  Recovery: When partition heals, workers drain the backlog.           |
  |                                                                      |
  |  CASE C: DynamoDB accessible to workers but not scheduler            |
  |  ----------------------------------------------------------------   |
  |  Workers claim and execute tasks (DynamoDB CAS works).               |
  |  Scheduler cannot read task state (DynamoDB unreachable).            |
  |  Scheduler cannot resolve dependencies (doesn't know what completed).|
  |  Cron triggers fire but scheduler cannot enqueue root tasks.         |
  |  Effect: Running tasks complete but no NEW tasks are scheduled.      |
  |  Recovery: Scheduler reads current state on reconnect, resumes.      |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Partition Scenario 2: Scheduler Nodes Split

```
  +----------------------------------------------------------------------+
  |  PARTITION: SCHEDULER CLUSTER SPLIT (SPLIT-BRAIN RISK)                |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Partition:                                                          |
  |    Partition A: scheduler-1 (current leader, priority=10)            |
  |    Partition B: scheduler-2 (priority=8), scheduler-3 (priority=6)   |
  |                                                                      |
  |  SCENARIO:                                                           |
  |  1. scheduler-1 is isolated in Partition A                           |
  |  2. scheduler-2 and scheduler-3 cannot reach scheduler-1             |
  |  3. scheduler-2/3 see scheduler-1's heartbeat as stale (> 30s)      |
  |  4. scheduler-2/3 trigger re-election:                               |
  |     scheduler-2 (priority=8) wins -> new leader in Partition B       |
  |  5. scheduler-1 in Partition A still thinks it's the leader          |
  |     (it hasn't received any "you're not leader" signal)              |
  |                                                                      |
  |  SPLIT-BRAIN: TWO LEADERS                                            |
  |    scheduler-1: scheduling cron DAGs, enqueuing tasks                |
  |    scheduler-2: ALSO scheduling cron DAGs, enqueuing tasks           |
  |    Result: every cron DAG gets triggered TWICE                       |
  |                                                                      |
  |  MITIGATION: FENCING TOKENS (see next section)                       |
  |    Every leadership claim increments a monotonic epoch counter.      |
  |    scheduler-1 had epoch=5. scheduler-2 gets epoch=6.                |
  |    Workers reject any command from epoch < current max epoch.        |
  |    scheduler-1's commands (epoch=5) are rejected.                    |
  |    Only scheduler-2's commands (epoch=6) are accepted.               |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Partition Scenario 3: DynamoDB Partition

```
  +----------------------------------------------------------------------+
  |  PARTITION: DYNAMODB TEMPORARILY UNAVAILABLE                          |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  DynamoDB is designed for 99.999% availability (five nines).         |
  |  But it CAN have transient errors (ProvisionedThroughputExceeded,    |
  |  InternalServerError, ServiceUnavailable).                           |
  |                                                                      |
  |  Impact on each component:                                           |
  |                                                                      |
  |  Task Claiming:                                                      |
  |    Worker cannot write conditional update.                           |
  |    Worker retries with exponential backoff (AWS SDK default).        |
  |    SQS message remains invisible (VisibilityTimeout protects it).    |
  |    After VisibilityTimeout: SQS redelivers to another worker.       |
  |    Effect: Task delayed by VisibilityTimeout, not lost.              |
  |                                                                      |
  |  Leader Election:                                                    |
  |    Cannot write heartbeat or claim leadership.                       |
  |    Existing leader keeps operating with stale lease.                 |
  |    If existing leader's lease expires and no one can claim:          |
  |      -> NO leader. Scheduling paused.                                |
  |    Effect: New tasks not scheduled. Running tasks continue.          |
  |    Recovery: First node to successfully write becomes leader.        |
  |                                                                      |
  |  Dependency Resolution:                                              |
  |    Scheduler cannot read task completion state.                      |
  |    Downstream tasks not unblocked.                                   |
  |    Effect: DAG progress paused. No data corruption.                  |
  |    Recovery: On reconnect, read current state, resume unblocking.    |
  |                                                                      |
  |  Design decision: PREFER PAUSING over INCORRECT STATE.               |
  |  A task scheduler that pauses for 30 seconds is far better than      |
  |  one that executes tasks out of order or double-executes.            |
  +----------------------------------------------------------------------+
```

---

## Split-Brain Prevention in Leader Election

### Fencing Tokens

```
  +----------------------------------------------------------------------+
  |  FENCING TOKENS -- PREVENTING ZOMBIE LEADER COMMANDS                  |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Problem: Old leader's commands arrive after new leader is elected.  |
  |                                                                      |
  |  Timeline:                                                           |
  |    T=0:   scheduler-1 is leader (epoch=5)                            |
  |    T=10:  scheduler-1 sends command: "enqueue task-X" (epoch=5)      |
  |    T=11:  Network partition isolates scheduler-1                      |
  |    T=42:  scheduler-2 elected as new leader (epoch=6)                |
  |    T=45:  scheduler-1's command (epoch=5) arrives at SQS             |
  |                                                                      |
  |  WITHOUT fencing tokens:                                             |
  |    Worker picks up task-X. Executes it. But scheduler-2 also         |
  |    scheduled it (epoch=6). Task executes TWICE.                      |
  |                                                                      |
  |  WITH fencing tokens:                                                |
  |    Every SQS message includes: { ..., epoch: 5 }                     |
  |    Worker reads current epoch from DynamoDB: epoch=6                  |
  |    Worker sees message epoch (5) < current epoch (6) -> REJECT.      |
  |    Worker deletes the stale SQS message without executing.           |
  |                                                                      |
  |  Implementation (DynamoDB atomic counter):                           |
  |    UpdateItem:                                                       |
  |      PK: "LEADER#scheduler", SK: "LOCK"                             |
  |      SET epoch = epoch + 1, nodeId = "scheduler-2"                   |
  |      ConditionExpression: "attribute_not_exists(PK) OR leaseTTL < :now"|
  |    Returns: { epoch: 6 }                                             |
  |    All subsequent commands from scheduler-2 carry epoch=6.           |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Lease-Based Leadership

```
  +----------------------------------------------------------------------+
  |  LEASE-BASED LEADERSHIP -- TIME-BOUNDED LOCKS                         |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  A lease is a time-bounded lock. Unlike a mutex (held until release),|
  |  a lease AUTOMATICALLY EXPIRES if not renewed.                        |
  |                                                                      |
  |  Our implementation:                                                 |
  |    Lease duration: 30 seconds (ALIVE_TIMEOUT in code)                |
  |    Heartbeat interval: 10 seconds (renew lease every 10s)            |
  |    Grace period: 30 - 10 = 20 seconds before lease expires           |
  |                                                                      |
  |  Lease lifecycle:                                                    |
  |    T=0:  scheduler-1 acquires lease (leaseTTL = T+30s)               |
  |    T=10: scheduler-1 renews lease (leaseTTL = T+40s)                 |
  |    T=20: scheduler-1 renews lease (leaseTTL = T+50s)                 |
  |    T=25: scheduler-1 CRASHES                                         |
  |    T=50: Lease expires (leaseTTL = T+50s, now > T+50s)               |
  |    T=51: scheduler-2 acquires lease (new epoch)                      |
  |                                                                      |
  |  WHY 30-second lease (not 5 seconds or 5 minutes)?                   |
  |  +------------------------------------------------------------------+|
  |  | Lease Duration | Pro                    | Con                    ||
  |  +----------------+------------------------+------------------------+|
  |  | 5 seconds      | Fast failover (5s)     | Frequent renewals      ||
  |  |                |                        | (every 2s). More DDB   ||
  |  |                |                        | writes. False positives ||
  |  |                |                        | during GC pauses.      ||
  |  +----------------+------------------------+------------------------+|
  |  | 30 seconds     | Low renewal overhead   | 30s failover delay.    ||
  |  |                | (every 10s). Tolerates | Acceptable for task    ||
  |  |                | GC pauses & network    | scheduler (not trading)||
  |  |                | hiccups.               |                        ||
  |  +----------------+------------------------+------------------------+|
  |  | 5 minutes      | Very low overhead      | 5-minute failover is   ||
  |  |                |                        | too slow. Tasks pile up||
  |  |                |                        | in queue during outage.||
  |  +----------------+------------------------+------------------------+|
  |                                                                      |
  |  30 seconds balances fast failover with low false-positive rate.     |
  +----------------------------------------------------------------------+
```

---

## Industry Comparison -- Airflow, Celery, Temporal

### Architecture Comparison

```
  +----------------------------------------------------------------------+
  |  INDUSTRY ARCHITECTURE COMPARISON                                     |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Component      | Airflow          | Celery          | Temporal       |
  |  ---------------+------------------+-----------------+---------------  |
  |  Language        | Python           | Python          | Go (server),   |
  |                  |                  |                 | Java/Go/Python |
  |                  |                  |                 | (workers)      |
  |  ---------------+------------------+-----------------+---------------  |
  |  Scheduler       | Airflow Scheduler| Celery Beat     | Temporal Server|
  |                  | (single process, |  (single-node   | (multi-node,   |
  |                  | HA with DB lock) |  cron scheduler) | Cassandra-     |
  |                  |                  |                 |  backed)       |
  |  ---------------+------------------+-----------------+---------------  |
  |  Task Queue      | Celery + Redis/  | Redis / RabbitMQ| Internal queue |
  |                  | RabbitMQ         |                 | (Cassandra-    |
  |                  |                  |                 |  based)        |
  |  ---------------+------------------+-----------------+---------------  |
  |  Workers         | Celery workers   | Celery workers  | Temporal       |
  |                  | (Python)         | (Python)        | workers (any   |
  |                  |                  |                 | language)      |
  |  ---------------+------------------+-----------------+---------------  |
  |  State Store     | PostgreSQL /     | Redis (result   | Cassandra /    |
  |                  | MySQL            | backend)        | MySQL / PG     |
  |  ---------------+------------------+-----------------+---------------  |
  |  DAG Support     | YES (native,     | NO (chain/group | YES (Workflow  |
  |                  | Python DAG       | /chord are      | definitions,   |
  |                  | definitions)     | primitive)      | native)        |
  |  ---------------+------------------+-----------------+---------------  |
  |  Exactly-Once    | NO (at-least-    | NO (at-least-   | YES (built-in  |
  |                  | once, DB lock    | once, ack/nack) | event sourcing |
  |                  | for scheduler)   |                 | + replay)      |
  |  ---------------+------------------+-----------------+---------------  |
  |  Leader Election | DB row lock      | None (Beat is   | Raft / paxos   |
  |                  | (SELECT FOR      | single point    | (membership    |
  |                  | UPDATE)          | of failure)     | service)       |
  |  ---------------+------------------+-----------------+---------------  |
  |  Retry           | Task-level retry | Task-level retry| Automatic,     |
  |                  | (max_retries,    | (max_retries,   | built-in,      |
  |                  | retry_delay)     | countdown)      | configurable   |
  |  ---------------+------------------+-----------------+---------------  |
  |  Scale           | ~1000 tasks/min  | ~10,000 tasks/  | ~100,000 tasks/|
  |                  | (scheduler is    | min (Redis      | min (Cassandra |
  |                  | bottleneck)      | bottleneck)     | scales)        |
  |  ---------------+------------------+-----------------+---------------  |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Airflow Deep Dive -- CAP Analysis

```
  +----------------------------------------------------------------------+
  |  APACHE AIRFLOW -- CAP ANALYSIS                                       |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Architecture:                                                       |
  |  +-------------------+                                               |
  |  | Airflow Scheduler |  Single process (or HA pair with DB lock)     |
  |  +-------------------+                                               |
  |       |                                                              |
  |       | (1) Reads DAG files from disk (parsed every 30s)             |
  |       | (2) Writes task instances to PostgreSQL                       |
  |       | (3) Pushes tasks to Celery (Redis/RabbitMQ)                  |
  |       v                                                              |
  |  +-------------------+    +-------------------+                      |
  |  | PostgreSQL        |    | Redis / RabbitMQ  |                      |
  |  | (metadata DB)     |    | (task broker)     |                      |
  |  +-------------------+    +-------------------+                      |
  |       |                        |                                     |
  |       |                        | (4) Workers poll for tasks          |
  |       v                        v                                     |
  |  +-------------------+                                               |
  |  | Celery Workers    |  (5) Execute tasks, update PostgreSQL         |
  |  +-------------------+                                               |
  |                                                                      |
  |  CAP decisions in Airflow:                                           |
  |  +------------------------------------------------------------------+|
  |  | Component         | CAP | How                                    ||
  |  +-------------------+-----+----------------------------------------+|
  |  | Scheduler HA      | CP  | Two schedulers use SELECT FOR UPDATE   ||
  |  |                   |     | on a DB row. Only one acquires the     ||
  |  |                   |     | lock. Other is standby.                ||
  |  +-------------------+-----+----------------------------------------+|
  |  | Task Instance     | CP  | PostgreSQL ACID. Task state transitions||
  |  | State             |     | are serialized through DB transactions.||
  |  +-------------------+-----+----------------------------------------+|
  |  | DAG Parsing       | AP  | DAG files are read from NFS/S3.        ||
  |  |                   |     | If file is stale, scheduler uses       ||
  |  |                   |     | old version until next parse cycle.    ||
  |  +-------------------+-----+----------------------------------------+|
  |  | XCom (inter-task  | CP  | Stored in PostgreSQL. Read with        ||
  |  | communication)    |     | strong consistency.                    ||
  |  +-------------------+-----+----------------------------------------+|
  |                                                                      |
  |  WEAKNESS: Scheduler is a single point of bottleneck.                |
  |  PostgreSQL row-level locks limit scheduler throughput.              |
  |  At ~1000 tasks/minute, Airflow Scheduler starts to fall behind.    |
  |  Our design: DynamoDB conditional writes scale horizontally.         |
  +----------------------------------------------------------------------+
```

### Celery Deep Dive -- CAP Analysis

```
  +----------------------------------------------------------------------+
  |  CELERY -- CAP ANALYSIS                                               |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Architecture:                                                       |
  |  +-------------------+                                               |
  |  | Celery Beat       |  Single-node cron scheduler (NO HA!)          |
  |  +-------------------+                                               |
  |       |                                                              |
  |       | (1) Fires periodic tasks on schedule                         |
  |       v                                                              |
  |  +-------------------+                                               |
  |  | Redis / RabbitMQ  |  (2) Task broker (message queue)              |
  |  +-------------------+                                               |
  |       |                                                              |
  |       | (3) Workers consume tasks (AMQP ack/nack)                    |
  |       v                                                              |
  |  +-------------------+                                               |
  |  | Celery Workers    |  (4) Execute, store result in Redis           |
  |  +-------------------+                                               |
  |                                                                      |
  |  CAP decisions in Celery:                                            |
  |  +------------------------------------------------------------------+|
  |  | Component         | CAP | Analysis                               ||
  |  +-------------------+-----+----------------------------------------+|
  |  | Celery Beat       | N/A | SINGLE NODE. No distributed consensus.||
  |  |                   |     | If Beat dies, no cron tasks fire.      ||
  |  |                   |     | This is Celery's biggest weakness.     ||
  |  +-------------------+-----+----------------------------------------+|
  |  | Redis Broker      | AP  | Redis is AP by default (async replic). ||
  |  |                   |     | If Redis master fails, messages can be ||
  |  |                   |     | lost before replication completes.     ||
  |  +-------------------+-----+----------------------------------------+|
  |  | RabbitMQ Broker   | CP  | RabbitMQ with quorum queues is CP.     ||
  |  |                   |     | Messages persist and replicate before  ||
  |  |                   |     | ack. Slower but no message loss.       ||
  |  +-------------------+-----+----------------------------------------+|
  |  | Task Ack          | AP  | Celery uses ack_late=False by default. ||
  |  |                   |     | Task is acked when received, not when  ||
  |  |                   |     | completed. Worker crash = task lost.   ||
  |  |                   |     | Fix: ack_late=True (ack after success).||
  |  +-------------------+-----+----------------------------------------+|
  |                                                                      |
  |  WEAKNESS: No native exactly-once. No DAG support.                   |
  |  chain(task_A, task_B, task_C) is primitive compared to Airflow DAGs.|
  |  Our design: DependencyResolver with topological sort + conditional  |
  |  writes gives us both DAG support AND exactly-once.                  |
  +----------------------------------------------------------------------+
```

### Temporal Deep Dive -- CAP Analysis

```
  +----------------------------------------------------------------------+
  |  TEMPORAL -- CAP ANALYSIS (the gold standard)                         |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Architecture:                                                       |
  |  +-------------------+                                               |
  |  | Temporal Server   |  Multi-node, Cassandra/MySQL-backed           |
  |  | (Frontend, History|  History service: event sourced, replay-based |
  |  |  Matching, Worker)|                                               |
  |  +-------------------+                                               |
  |       |                                                              |
  |       | (1) Workflow started via gRPC                                 |
  |       | (2) Events persisted to Cassandra (event sourcing)           |
  |       | (3) Tasks dispatched to task queues (internal)               |
  |       v                                                              |
  |  +-------------------+                                               |
  |  | Temporal Workers  |  (4) Poll task queues, execute activities     |
  |  | (any language)    |  (5) Report completion back to server         |
  |  +-------------------+                                               |
  |                                                                      |
  |  CAP decisions in Temporal:                                          |
  |  +------------------------------------------------------------------+|
  |  | Component         | CAP | Analysis                               ||
  |  +-------------------+-----+----------------------------------------+|
  |  | Workflow History   | CP  | Event sourced. Every state change is  ||
  |  |                   |     | an immutable event in Cassandra.       ||
  |  |                   |     | Replay guarantees deterministic state. ||
  |  +-------------------+-----+----------------------------------------+|
  |  | Task Dispatch      | CP  | Task queue uses Cassandra conditional ||
  |  |                   |     | writes. Exactly-once dispatch.         ||
  |  +-------------------+-----+----------------------------------------+|
  |  | Membership         | CP  | Ring membership via Cassandra.         ||
  |  |                   |     | Consistent hashing for shard ownership.||
  |  +-------------------+-----+----------------------------------------+|
  |  | Visibility (search)| AP  | Elasticsearch integration for search. ||
  |  |                   |     | Eventually consistent. Search results  ||
  |  |                   |     | may lag actual workflow state.          ||
  |  +-------------------+-----+----------------------------------------+|
  |                                                                      |
  |  WHY TEMPORAL IS THE GOLD STANDARD:                                  |
  |  1. Event sourcing: every state change is an event. Replay = rebuild.|
  |  2. Exactly-once: built into the core (not an afterthought).         |
  |  3. Multi-language: workers in Java, Go, Python, TypeScript.         |
  |  4. Scales: Cassandra backend scales horizontally.                   |
  |  5. Versioning: workflow code can be updated without breaking running |
  |     workflows (via versioning API).                                  |
  |                                                                      |
  |  HOW OUR DESIGN COMPARES:                                            |
  |  - We use DynamoDB conditional writes (similar to Cassandra CAS)     |
  |  - We use explicit state machine (similar to Temporal's event replay)|
  |  - We lack event sourcing (our state is mutable, not event-logged)  |
  |  - We lack workflow versioning (Temporal's killer feature)           |
  |  - Our design is simpler but less resilient than Temporal            |
  +----------------------------------------------------------------------+
```

---

## PACELC Analysis -- When No Partition

### Latency vs Consistency Trade-offs

```
  +----------------------------------------------------------------------+
  |  PACELC: What happens when there is NO partition?                     |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  PACELC = if Partition -> AP or CP; Else -> Latency or Consistency   |
  |                                                                      |
  |  Component              | If P  | Else (normal) | Classification     |
  |  -----------------------+-------+---------------+-------------------  |
  |  Task State Transitions  | CP    | C over L      | PC/EC              |
  |  (DynamoDB conditional   |       | Wait for DDB  | (always consistent |
  |   writes, CAS claim)     |       | ACK before    |  even at cost of   |
  |                          |       | proceeding.   |  write latency)    |
  |  -----------------------+-------+---------------+-------------------  |
  |  Leader Election         | CP    | C over L      | PC/EC              |
  |  (DynamoDB lease,        |       | 30s lease TTL | (consistent leader |
  |   fencing tokens)        |       | adds latency  |  even if failover  |
  |                          |       | to failover.  |  is slow)          |
  |  -----------------------+-------+---------------+-------------------  |
  |  Dependency Resolution   | CP    | L over C*     | PC/EL              |
  |  (DependencyResolver     |       | Read from     | (consistent under  |
  |   checks task state)     |       | DDB cache for |  partition; fast   |
  |                          |       | known-complete|  when normal via   |
  |                          |       | tasks.        |  caching)          |
  |  -----------------------+-------+---------------+-------------------  |
  |  Worker Heartbeats       | AP    | L over C      | PA/EL              |
  |  (eventually consistent  |       | Read from     | (availability over |
  |   DDB reads, cached)     |       | cache. Stale  |  consistency for   |
  |                          |       | is acceptable.|  liveness checks)  |
  |  -----------------------+-------+---------------+-------------------  |
  |  Monitoring Metrics      | AP    | L over C      | PA/EL              |
  |  (CloudWatch, Redis      |       | Serve from    | (always available, |
  |   dashboard cache)       |       | Redis cache.  |  even if slightly  |
  |                          |       | 5s TTL.       |  stale)            |
  |  -----------------------+-------+---------------+-------------------  |
  |  Execution Logs          | AP    | L over C      | PA/EL              |
  |  (ExecutionRepository,   |       | Append-only,  | (logs are append-  |
  |   write-behind to S3)    |       | buffered      |  only and idem-    |
  |                          |       | writes OK.    |  potent)           |
  |  -----------------------+-------+---------------+-------------------  |
  |                                                                      |
  |  KEY INSIGHT:                                                        |
  |  Task claiming and leader election are ALWAYS PC/EC -- they never    |
  |  sacrifice consistency. The latency cost (DynamoDB conditional write  |
  |  ~5ms) is negligible compared to task execution time (seconds to     |
  |  minutes). Monitoring is PA/EL -- always prioritize speed.           |
  +----------------------------------------------------------------------+
```

---

## When to Sacrifice Consistency vs Availability

### Decision Framework

```
  +----------------------------------------------------------------------+
  |  DECISION FRAMEWORK: WHEN TO SACRIFICE WHAT                           |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Rule 1: If the operation has SIDE EFFECTS -> CP                     |
  |  ----------------------------------------------------------------   |
  |  Examples:                                                           |
  |  - Charging a customer (side effect: money moved)                    |
  |  - Sending an email (side effect: email in inbox)                    |
  |  - Writing to production DB (side effect: data changed)              |
  |  - Triggering a downstream service (side effect: action taken)       |
  |  |                                                                   |
  |  Why: A duplicate side effect is worse than a delayed execution.     |
  |  User would rather wait 30 seconds than be charged twice.            |
  |                                                                      |
  |  Rule 2: If the operation is READ-ONLY or IDEMPOTENT -> AP           |
  |  ----------------------------------------------------------------   |
  |  Examples:                                                           |
  |  - Dashboard metrics (read-only, no side effects)                    |
  |  - Worker heartbeat check (stale read causes delayed detection, not  |
  |    incorrect behavior)                                               |
  |  - Queue depth monitoring (stale count is better than no count)      |
  |  - Execution log queries (append-only, eventually consistent OK)     |
  |  |                                                                   |
  |  Why: A stale read causes no harm. Better to show something than     |
  |  nothing. Availability > consistency for monitoring.                  |
  |                                                                      |
  |  Rule 3: If CORRECTNESS is BUSINESS-CRITICAL -> CP                   |
  |  ----------------------------------------------------------------   |
  |  Examples:                                                           |
  |  - DAG dependency resolution (wrong order = data corruption)         |
  |  - Task priority ordering (HIGH must run before LOW)                 |
  |  - Cron trigger deduplication (double-trigger = duplicate work)      |
  |  |                                                                   |
  |  Why: The entire purpose of a scheduler is correct execution order.  |
  |  If it can't guarantee that, it's useless.                           |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Graceful Degradation Strategy

```
  +----------------------------------------------------------------------+
  |  GRACEFUL DEGRADATION -- WHAT STOPS AND WHAT CONTINUES                |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Severity Level 1: DynamoDB THROTTLED (not down, just slow)          |
  |  ----------------------------------------------------------------   |
  |  Impact: Conditional writes take 50ms instead of 5ms.               |
  |  Action: Continue operating. Workers retry with backoff.             |
  |  User impact: Tasks take slightly longer to start. Acceptable.       |
  |                                                                      |
  |  Severity Level 2: DynamoDB DOWN for task state                      |
  |  ----------------------------------------------------------------   |
  |  Impact: Cannot claim tasks. Cannot update task state.               |
  |  Action:                                                             |
  |    STOP: New task scheduling, dependency resolution.                 |
  |    CONTINUE: Already-running tasks (they don't need DynamoDB).       |
  |    CONTINUE: Monitoring dashboard (Redis cache serves stale data).   |
  |  User impact: No new tasks start. Running tasks complete normally.   |
  |                                                                      |
  |  Severity Level 3: SQS DOWN                                         |
  |  ----------------------------------------------------------------   |
  |  Impact: Cannot enqueue or dequeue tasks.                            |
  |  Action:                                                             |
  |    STOP: Task dispatch (no queue to push to).                        |
  |    CONTINUE: Scheduler DAG resolution (prepares tasks but can't send)|
  |    BUFFER: Tasks buffered in scheduler memory (limited, risky).      |
  |  User impact: All new task execution halted. Backlog builds.         |
  |  Recovery: Flush buffered tasks to SQS on reconnect.                 |
  |                                                                      |
  |  Severity Level 4: Leader LOST (all scheduler nodes down)            |
  |  ----------------------------------------------------------------   |
  |  Impact: No scheduling decisions made.                               |
  |  Action:                                                             |
  |    STOP: Cron triggers (no one to process them).                     |
  |    STOP: Dependency resolution (no one to unblock tasks).            |
  |    CONTINUE: Workers processing already-queued tasks from SQS.       |
  |  User impact: SQS backlog drains (good). No new tasks enqueued.     |
  |  Recovery: New scheduler node starts, elects leader, resumes.        |
  |                                                                      |
  |  DESIGN PRINCIPLE: Fail NARROW, not WIDE.                            |
  |  Each failure should affect the smallest possible scope.             |
  |  DynamoDB down should not stop SQS processing.                       |
  |  Scheduler down should not stop already-dispatched tasks.            |
  +----------------------------------------------------------------------+
```

---

## Industry Best Practices

### Best Practices Summary

```
  +----------------------------------------------------------------------+
  |  INDUSTRY BEST PRACTICES FOR DISTRIBUTED TASK SCHEDULERS              |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  1. IDEMPOTENCY AT EVERY LAYER                                       |
  |  ----------------------------------------------------------------   |
  |  - SQS message deduplication (idempotencyKey)                        |
  |  - DynamoDB conditional writes (CAS for task claiming)               |
  |  - Worker-level execution check (already running? skip)              |
  |  - Application-level idempotency (downstream services must also be   |
  |    idempotent for true exactly-once end-to-end)                      |
  |                                                                      |
  |  2. BOUNDED STALENESS FOR AP COMPONENTS                              |
  |  ----------------------------------------------------------------   |
  |  - Heartbeats: 30-second ALIVE_TIMEOUT                               |
  |  - Monitoring: 5-second cache TTL                                    |
  |  - Queue depth: CloudWatch reports every 60 seconds                  |
  |  - Never UNBOUNDED staleness. Always set an upper bound.             |
  |                                                                      |
  |  3. FENCING TOKENS FOR LEADER ELECTION                               |
  |  ----------------------------------------------------------------   |
  |  - Monotonic epoch counter incremented on each election              |
  |  - Workers reject commands from stale epoch                          |
  |  - Prevents split-brain damage even if two leaders exist briefly     |
  |                                                                      |
  |  4. EXPONENTIAL BACKOFF WITH JITTER FOR RETRIES                      |
  |  ----------------------------------------------------------------   |
  |  - Initial delay: 1 second                                          |
  |  - Multiplier: 2x                                                   |
  |  - Max delay: 5 minutes                                             |
  |  - Jitter: +/- 10% (from ExponentialBackoffRetryStrategy.java)      |
  |  - Prevents thundering herd when many tasks fail simultaneously     |
  |                                                                      |
  |  5. DEAD-LETTER QUEUES FOR UNPROCESSABLE TASKS                       |
  |  ----------------------------------------------------------------   |
  |  - SQS DLQ: messages received 3+ times without deletion             |
  |  - Application DLQ: tasks that fail all retry attempts               |
  |  - ALWAYS alarm on DLQ depth. Unprocessable tasks = bugs.            |
  |                                                                      |
  |  6. HEALTH CHECKS AND CIRCUIT BREAKERS                               |
  |  ----------------------------------------------------------------   |
  |  - Worker health: heartbeat every 10 seconds                         |
  |  - Scheduler health: leader heartbeat every 10 seconds               |
  |  - External service health: circuit breaker (fail fast if service    |
  |    is down, don't waste retries)                                     |
  |                                                                      |
  |  7. SEPARATE CP AND AP DATA PATHS                                    |
  |  ----------------------------------------------------------------   |
  |  - CP path: DynamoDB strongly consistent reads for task state        |
  |  - AP path: Redis cache for monitoring, dashboards, metrics          |
  |  - Never mix: don't use the AP cache for CP decisions                |
  |  - Example: worker heartbeat check uses eventually consistent read   |
  |    (AP), but task claiming uses conditional write (CP)               |
  |                                                                      |
  |  8. EVENT-DRIVEN DEPENDENCY RESOLUTION (not polling)                  |
  |  ----------------------------------------------------------------   |
  |  - Task completes -> EventBridge event -> Lambda -> resolve deps     |
  |  - NOT: scheduler polls DynamoDB every 5 seconds for completed tasks |
  |  - Event-driven: O(1) per completion. Polling: O(N) per tick.       |
  |  - Polling acceptable for small scale. Events required at scale.     |
  |                                                                      |
  +----------------------------------------------------------------------+
```

---

## Interview Q&A -- Ready-to-Use Answers

### Q: "Is a distributed task scheduler CP or AP?"

> "It's a SPLIT. Task state transitions are strictly CP -- you cannot double-execute
> a task or skip a dependency. I'd use DynamoDB conditional writes as a CAS to ensure
> exactly one worker claims each task. Leader election is also CP -- I'd rather have
> no leader for 30 seconds than two leaders scheduling duplicate work. Monitoring
> and worker heartbeats are AP -- a 10-second stale dashboard is acceptable because
> it has no side effects. The key insight is separating the CP data path (task state,
> leader lease) from the AP data path (metrics, heartbeats, logs)."

### Q: "How do you ensure exactly-once task execution?"

> "Exactly-once equals at-least-once delivery plus idempotent processing. SQS gives
> me at-least-once -- if a worker crashes, the message reappears after visibility
> timeout. DynamoDB conditional writes give me idempotent claiming -- only one worker
> can transition a task from QUEUED to RUNNING. The ConditionExpression 'status = QUEUED'
> acts as an atomic compare-and-swap. But true end-to-end exactly-once also requires
> downstream services to be idempotent -- if a worker crashes after the external call
> but before recording completion, the retry will call the service again."

### Q: "How do you prevent split-brain in leader election?"

> "Three mechanisms: (1) DynamoDB lease with 30-second TTL -- if the leader stops
> renewing, its lease expires and another node can claim leadership. (2) Fencing tokens --
> each new leader gets a monotonically increasing epoch number. Workers reject commands
> from stale epochs. So even if an old leader's message arrives late, workers ignore it.
> (3) The Bully algorithm ensures the highest-priority alive node wins. If two partitions
> each elect a leader, the fencing token from the newer election takes precedence."

### Q: "What happens during a network partition between scheduler and workers?"

> "Workers are decoupled from the scheduler via SQS. If the scheduler can't reach workers
> directly, it doesn't matter -- SQS is a regional service accessible from any AZ. Workers
> continue polling SQS, claiming tasks via DynamoDB conditional writes, and executing.
> The scheduler reads task completion state from DynamoDB. The only impact is on NEW
> scheduling decisions -- if the scheduler itself is partitioned from DynamoDB, it pauses
> scheduling but running tasks complete normally. I'd rather pause new work than corrupt
> dependency ordering."

### Q: "How does your design compare to Airflow vs Temporal?"

> "Airflow uses PostgreSQL row locks for scheduler HA and Celery+Redis for task dispatch.
> Its weakness is the scheduler bottleneck -- about 1000 tasks per minute before it falls
> behind. My design uses DynamoDB conditional writes which scale horizontally. Temporal
> is the gold standard -- it uses event sourcing with Cassandra, so every state change is
> an immutable event that can be replayed. My design is simpler but less resilient: I use
> mutable state in DynamoDB instead of event sourcing. If I were building for production
> at massive scale, I'd consider Temporal. For a system design interview, my approach
> demonstrates the key concepts: exactly-once via CAS, DAG resolution, leader election,
> exponential backoff, and split CAP analysis."

### Q: "Why 30-second lease for leader election instead of 5 seconds?"

> "30 seconds balances three concerns: (1) False positives -- a 5-second lease would cause
> false leader failovers during GC pauses or brief network hiccups. (2) DynamoDB write cost --
> a 5-second lease requires heartbeats every 2 seconds (10x more writes than 30-second lease
> with 10-second heartbeats). (3) Failover speed -- 30 seconds is acceptable for a task
> scheduler where tasks take minutes to execute. For a real-time trading system, I'd use
> 5-second leases because every second of leader downtime means missed trades. For a task
> scheduler, 30 seconds of no new scheduling is a minor inconvenience."

### Q: "What's the worst-case scenario and how do you handle it?"

> "Worst case: DynamoDB is down AND the leader crashes simultaneously. No new tasks can be
> scheduled and no tasks can be claimed. But already-running tasks on workers continue to
> completion (they don't need DynamoDB after claiming). SQS messages remain safe (SQS is
> independent of DynamoDB). When DynamoDB recovers, a new leader is elected, reads the
> current state, and resumes. Any tasks stuck in RUNNING state from the crashed leader's
> workers are detected by the failover service (stale heartbeats) and reassigned. The
> system self-heals. The design principle is: fail narrow, not wide."
