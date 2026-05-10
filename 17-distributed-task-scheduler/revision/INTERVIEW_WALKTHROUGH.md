# Distributed Task Scheduler -- Staff Engineer Interview Walkthrough

> **Target role:** Staff Engineer | **Time budget:** 35 minutes
> **Comparable systems:** Apache Airflow, Celery, Temporal, Google Cloud Tasks
> **Codebase reference:** `com.systemdesign.scheduler` (Project 17)

---

## TABLE OF CONTENTS

```
Phase 1 : Clarify Requirements .............. 2-3 min  (lines   40-250)
Phase 2 : High-Level Architecture ........... 5-7 min  (lines  260-650)
Phase 3 : Deep Dive -- DAG Dependencies ..... 8-10 min (lines  660-1200)
Phase 4 : Deep Dive -- Leader Election ...... 5-7 min  (lines 1210-1650)
Phase 5 : Retry & Exactly-Once .............. 3-5 min  (lines 1660-1950)
Phase 6 : Scaling & Tradeoffs ............... 3-5 min  (lines 1960-2250)
Phase 7 : Edge Cases ........................ 2-3 min  (lines 2260-2550)
Appendix A : Design Patterns Cheat Sheet .... (lines 2560-2750)
Appendix B : Complexity Cheat Sheet ......... (lines 2760-2850)
Appendix C : Quick-Fire Q&A Bank ............ (lines 2860-3050)
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
constraints.

#### Bucket 1 -- Scale & Load

```
Q1: "What's the expected task volume -- are we talking thousands
     per minute or millions per day?"
     WHY: Determines whether you need partitioned queues or a
          single priority queue is sufficient.

Q2: "What's the average task duration? Seconds, minutes, hours?"
     WHY: Short tasks (seconds) need lightweight dispatch.
          Long tasks (hours) need heartbeats and checkpointing.

Q3: "How many workers do we expect in the pool -- tens or thousands?"
     WHY: Tens = centralized assignment. Thousands = need sharding
          or consistent hashing.
```

#### Bucket 2 -- Semantics & Correctness

```
Q4: "Do tasks have dependencies on each other, or are they all
     independent?"
     WHY: Dependencies = DAG resolution, topological ordering.
          Independent = simpler queue-based dispatch.

Q5: "What execution guarantee do we need -- at-most-once,
     at-least-once, or exactly-once?"
     WHY: Exactly-once requires idempotency keys and fencing
          tokens. At-least-once is cheaper. This shapes the
          entire retry and failover design.

Q6: "Can tasks be cancelled or paused after submission?"
     WHY: Cancellation requires a task state machine and the
          ability to revoke in-flight work.
```

#### Bucket 3 -- Constraints & Non-Functionals

```
Q7: "Do we need cron/recurring task support, or only one-shot tasks?"
     WHY: Cron requires a scheduler loop, next-fire-time
          computation, and idempotent re-submission.

Q8: "What's the latency SLA from task submission to execution start?"
     WHY: <100ms = in-memory queue. <1s = Redis/SQS. >1s = can
          use a database-backed queue.
```

### Follow-up about consistency requirements

```
"One more thing -- for the task state (PENDING -> QUEUED -> RUNNING ->
COMPLETED), do we favor strong consistency so a task is never
double-dispatched, or can we tolerate brief inconsistency and rely
on idempotency?"

WHY YOU ASK THIS:
  - Strong consistency (CP) = single leader writes task state,
    fencing tokens prevent stale workers. Higher latency.
  - Eventual consistency (AP) = faster dispatch but you MUST
    have idempotent task execution. More complex retry logic.
  - Staff engineers name the CAP tradeoff explicitly.
```

### Clarified scope (write on whiteboard/doc)

After hearing answers, summarize aloud:

```
+--------------------------------------+--------------------------------------+
|            IN SCOPE                  |           OUT OF SCOPE               |
+--------------------------------------+--------------------------------------+
| Task scheduling & dispatch           | Task authoring UI / workflow editor  |
| DAG-based dependency resolution      | Multi-tenant isolation               |
| Priority queue with multiple levels  | Task result storage (blob store)     |
| Cron / recurring task support        | Authentication & authorization       |
| Retry with exponential backoff       | Rate limiting per user               |
| Worker pool management & failover    | Cross-region replication              |
| Leader election for scheduler HA     | Custom task runtime environments     |
| Monitoring & observability           | Billing / metering                   |
+--------------------------------------+--------------------------------------+
```

```
TALKING POINT:
"I'll focus on the scheduling core: how tasks flow from submission
through dependency resolution, priority queuing, worker assignment,
execution, retry, and completion. I'll design for horizontal scaling
of the scheduler itself via leader election."
```

### Common follow-up questions for Phase 1

```
Q: "What if the interviewer says 'just design whatever you think
    is right'?"
A: Default to this scope: 100K tasks/day, task durations 1s-10min,
   DAG dependencies, at-least-once with idempotency, 50 workers,
   <500ms submission-to-dispatch latency.

Q: "Should I mention Airflow/Temporal by name?"
A: Yes, briefly: "This is similar to Airflow's DAG scheduler or
   Temporal's workflow engine, but I'll design from first principles."
   Shows awareness without name-dropping.
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
ZONE 1 -- Task Intake (<100ms p99)
  Client -> API Gateway -> SchedulerController -> TaskService -> TaskRepository
  "The API accepts task submissions, validates them, persists to the
   task store, and returns a task ID. This must be fast -- under 100ms."

ZONE 2 -- Scheduling Core (<10ms per task)
  SchedulerEngine -> DependencyResolver -> TaskQueue -> AssignmentStrategy
  "The scheduler loop ticks on a configurable interval (e.g., 100ms).
   Each tick: resolve ready tasks from the DAG, feed them into the
   priority queue, then assign to workers via the selected strategy."

ZONE 3 -- Execution (variable, seconds to hours)
  Worker -> TaskExecution -> RetryStrategy -> Result Callback
  "Workers pull or receive assigned tasks, execute them, and report
   results back. If a task fails, the retry strategy computes the
   next attempt delay."

ZONE 4 -- Async Observability (<5s staleness)
  MonitoringService -> Metrics Store -> Alerting -> Audit Trail
  "Monitoring reads from the task, worker, and execution repositories.
   It computes utilization, failure rate, throughput. This is read-only
   and can tolerate 5 seconds of staleness."
```

### ASCII Architecture Diagram (draw this)

```
                         DISTRIBUTED TASK SCHEDULER
    =====================================================================

    ZONE 1: TASK INTAKE (<100ms)
    +--------+     +-------------+     +-----------+     +--------+
    | Client | --> | API Gateway | --> | Scheduler | --> |  Task  |
    |  /SDK  |     | (Controller)|     |  Service  |     |  Repo  |
    +--------+     +-------------+     +-----------+     +--------+
                                            |                 |
                                            v                 |
    =====================================================================
    ZONE 2: SCHEDULING CORE (<10ms/task)    |                 |
                                            v                 |
                                     +-----------+            |
                                     | Scheduler |            |
                                     |  Engine   |            |
                                     +-----------+            |
                                      /    |    \             |
                                     v     v     v            |
                              +------+ +------+ +----------+  |
                              | DAG  | | Task | | Cron     |  |
                              | Dep  | | Queue| | Parser   |  |
                              |Reslvr| |(PQ)  | |          |  |
                              +------+ +------+ +----------+  |
                                            |                 |
                                            v                 |
                                     +-------------+          |
                                     | Assignment  |          |
                                     | Strategy    |          |
                                     | (RR/Least)  |          |
                                     +-------------+          |
                                            |                 |
    =====================================================================
    ZONE 3: EXECUTION (variable)            |                 |
                                            v                 |
              +----------+  +----------+  +----------+        |
              | Worker 1 |  | Worker 2 |  | Worker N |        |
              +----+-----+  +----+-----+  +----+-----+       |
                   |              |              |             |
                   v              v              v             |
              +----------+  +----------+  +----------+        |
              |  Task    |  |  Task    |  |  Task    |        |
              |Execution |  |Execution |  |Execution |        |
              +----+-----+  +----+-----+  +----+-----+       |
                   |              |              |             |
                   +------+-------+------+-------+            |
                          |              |                    |
                          v              v                    v
    =====================================================================
    ZONE 4: OBSERVABILITY (<5s)
              +-----------+     +-----------+     +-----------+
              | Monitoring|     | Execution |     |   Task    |
              |  Service  | <-- |   Repo    | <-- |   Repo    |
              +-----------+     +-----------+     +-----------+
                    |
                    v
              +-----------+     +-----------+
              |  Metrics  | --> | Alerting  |
              |  Dashboard|     |  System   |
              +-----------+     +-----------+
```

### What to say while drawing

```
"Let me walk through the data flow for a single task:

 1. Client submits a task via POST /tasks with a JSON payload
    containing name, priority, type, cron expression, dependencies.

 2. The SchedulerController (Facade pattern) validates and delegates
    to SchedulerService, which persists via TaskRepository and
    enqueues in the SchedulerEngine.

 3. If the task has dependencies, it goes into a 'waiting' map.
    The engine's tick() method calls DependencyResolver.getReadyTasks()
    each cycle to check if upstream tasks have completed.

 4. Ready tasks enter the TaskQueue -- a PriorityQueue sorted by
    priority (CRITICAL > HIGH > MEDIUM > LOW) then by createdAt.

 5. The AssignmentStrategy selects a worker. We support RoundRobin
    and LeastLoaded -- both implement the Strategy pattern.

 6. The worker executes the task and reports back. On failure,
    ExponentialBackoffRetryStrategy computes the delay for the
    next attempt.

 7. MonitoringService reads task status counts, worker utilization,
    avg execution time, failure rate, and retry rate."
```

### Design patterns visible in the architecture

```
| Pattern     | Where                          | Why                          |
|-------------|--------------------------------|------------------------------|
| Facade      | SchedulerService               | Single entry point           |
| Strategy x3 | Assignment, Retry, Scheduling  | Swappable algorithms         |
| Builder     | Task.Builder                   | Complex object construction  |
| Repository  | TaskRepo, WorkerRepo, etc.     | Decouple storage from logic  |
| Factory     | AppConfig                      | Centralized wiring           |
| Observer    | Task completion notifications   | Decouple event propagation   |
| State       | TaskStatus transitions          | Explicit state machine       |
```

### Common follow-up questions for Phase 2

```
Q: "Why not use a message broker like Kafka instead of an in-memory
    priority queue?"
A: "For an in-process scheduler, the PriorityQueue gives us O(log n)
   enqueue/dequeue with priority ordering. If we need durability and
   horizontal scaling, we'd swap the in-memory queue for a Redis
   sorted set (ZADD/ZPOPMIN) or a partitioned Kafka topic with
   priority headers. The Strategy pattern lets us swap this without
   changing the engine."

Q: "Where is the single point of failure?"
A: "The SchedulerEngine. We address this with leader election --
   multiple scheduler nodes run, but only the elected leader
   dispatches. If the leader dies, the Bully algorithm re-elects
   within the heartbeat timeout window (30s in our implementation)."

Q: "How do you handle backpressure?"
A: "If the TaskQueue depth exceeds a threshold, the SchedulerService
   stops accepting new tasks and returns HTTP 429. Workers also have
   a capacity field -- once currentLoad == capacity, they report
   as BUSY and the assignment strategy skips them."
```

---
---

## PHASE 3: DEEP DIVE -- DAG DEPENDENCY RESOLUTION (8-10 min)

### What this phase signals to the interviewer

```
STAFF SIGNAL: You can explain a graph algorithm clearly, draw it
step by step, analyze its complexity, and connect it to a real
production use case. This is the "depth" signal that separates
Staff from Senior.
```

### What to say to transition into this deep dive

```
"Let me deep-dive into one of the most interesting parts of this
system: how we resolve task dependencies. When tasks form a DAG --
like an ETL pipeline where Extract must finish before Transform,
and Transform must finish before Load -- we need an algorithm to
determine which tasks are ready to run at any given moment."
```

### 3.1 Adjacency List Representation

```
WHAT TO DRAW:

  Our DependencyResolver stores dependencies as an adjacency list:

  Map<String, Set<String>> dependencies:
    key   = taskId
    value = set of taskIds this task depends on

  Example ETL pipeline:

    Task Graph (visual):

      extract_users ----+
                        |---> transform_data ---> load_warehouse
      extract_orders ---+          |
                                   +---> generate_report

    Adjacency List (what we store):

      {
        "extract_users"    : [],                    // no deps, root node
        "extract_orders"   : [],                    // no deps, root node
        "transform_data"   : ["extract_users", "extract_orders"],
        "load_warehouse"   : ["transform_data"],
        "generate_report"  : ["transform_data"]
      }
```

```
WHAT TO SAY:

"We use a Map<String, Set<String>> where each key is a task ID and
the value is the set of task IDs it depends on. A task with an
empty dependency set is a root node -- it can run immediately.

This is the 'reverse adjacency list' -- we store incoming edges
rather than outgoing edges because our primary query is: 'given
this task, are all its prerequisites done?' That's a simple
containsAll check on the completed set."
```

### Code reference (DependencyResolver.java)

```
WHAT TO POINT OUT:

"In our codebase, DependencyResolver.addDependency(taskId, dependsOn)
builds this graph. The getReadyTasks(completedTaskIds) method iterates
all entries and returns tasks where completedTaskIds.containsAll(deps).

This is O(V * D) per call where V is the number of waiting tasks and
D is the average dependency count. For most DAGs, D is small (2-5),
so this is effectively O(V)."
```

### 3.2 Kahn's Algorithm Walkthrough

```
WHAT TO DRAW:

  Kahn's Algorithm -- Topological Sort via BFS

  PURPOSE: Determine a valid execution order for all tasks in the DAG
           such that every task runs after all its dependencies.

  STEPS:
    1. Compute in-degree for every node (count of incoming edges)
    2. Seed a queue with all nodes that have in-degree = 0
    3. While queue is not empty:
       a. Dequeue node, add to result order
       b. For each dependent of this node:
          - Decrement its in-degree
          - If in-degree reaches 0, enqueue it
    4. If result order has fewer nodes than the graph, there is a cycle
```

```
STEP-BY-STEP EXAMPLE (draw each step):

  Input DAG (same ETL pipeline):

    extract_users ----+
                      +---> transform_data ---> load_warehouse
    extract_orders ---+          |
                                 +---> generate_report

  Step 1: Compute in-degrees

    +-------------------+----------+
    | Task              | In-Degree|
    +-------------------+----------+
    | extract_users     |    0     |  <-- root
    | extract_orders    |    0     |  <-- root
    | transform_data    |    2     |  (depends on both extracts)
    | load_warehouse    |    1     |  (depends on transform)
    | generate_report   |    1     |  (depends on transform)
    +-------------------+----------+

  Step 2: Seed queue with in-degree 0

    Queue: [extract_users, extract_orders]
    Order: []

  Step 3a: Dequeue extract_users

    Order: [extract_users]
    Decrement dependents of extract_users:
      transform_data: in-degree 2 -> 1
    Queue: [extract_orders]

  Step 3b: Dequeue extract_orders

    Order: [extract_users, extract_orders]
    Decrement dependents of extract_orders:
      transform_data: in-degree 1 -> 0  ** enqueue! **
    Queue: [transform_data]

  Step 3c: Dequeue transform_data

    Order: [extract_users, extract_orders, transform_data]
    Decrement dependents of transform_data:
      load_warehouse:  in-degree 1 -> 0  ** enqueue! **
      generate_report: in-degree 1 -> 0  ** enqueue! **
    Queue: [load_warehouse, generate_report]

  Step 3d: Dequeue load_warehouse

    Order: [extract_users, extract_orders, transform_data, load_warehouse]
    No dependents to decrement.
    Queue: [generate_report]

  Step 3e: Dequeue generate_report

    Order: [extract_users, extract_orders, transform_data,
            load_warehouse, generate_report]
    No dependents to decrement.
    Queue: []

  DONE. All 5 nodes processed. No cycle.

  Valid execution order:
    extract_users -> extract_orders -> transform_data
        -> load_warehouse -> generate_report
```

### Code reference (DependencyResolver.getTopologicalOrder)

```java
// From DependencyResolver.java -- Kahn's algorithm implementation
public List<String> getTopologicalOrder() {
    if (hasCycle()) {
        throw new DependencyCycleException(
            "Dependency graph contains a cycle");
    }

    // 1. Build in-degree map and adjacency (dependsOn -> dependents)
    Map<String, Integer> inDegree = new HashMap<>();
    Map<String, Set<String>> dependents = new HashMap<>();
    // ... initialize all nodes to in-degree 0
    // ... for each dependency edge, increment in-degree

    // 2. Seed the queue with zero-in-degree nodes
    Queue<String> queue = new ArrayDeque<>();
    for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
        if (entry.getValue() == 0) {
            queue.add(entry.getKey());
        }
    }

    // 3. BFS -- peel off zero-in-degree nodes
    List<String> order = new ArrayList<>();
    while (!queue.isEmpty()) {
        String node = queue.poll();
        order.add(node);
        for (String dependent : dependents.getOrDefault(node, emptySet())) {
            int newDegree = inDegree.merge(dependent, -1, Integer::sum);
            if (newDegree == 0) {
                queue.add(dependent);
            }
        }
    }

    return order;
}
```

### 3.3 Cycle Detection using DFS Three-Coloring

```
WHAT TO DRAW:

  DFS Three-Coloring for Cycle Detection

  Color Scheme:
    WHITE (0) = unvisited
    GRAY  (1) = currently on the DFS stack (being explored)
    BLACK (2) = fully explored, all descendants visited

  Rule: If during DFS we encounter a GRAY node, we have found
        a back edge -> CYCLE EXISTS.

  Example with a cycle:

    A -> B -> C -> A   (cycle: A -> B -> C -> A)

  DFS from A:
    Visit A: color A = GRAY
    Visit B: color B = GRAY
    Visit C: color C = GRAY
    C depends on A: A is GRAY -> BACK EDGE -> CYCLE DETECTED!
```

```
WHAT TO SAY:

"We detect cycles before running Kahn's algorithm. The three-color
DFS is the standard approach:

  WHITE = haven't visited yet
  GRAY  = currently on the recursion stack
  BLACK = done, all descendants fully explored

If we ever visit a node that's already GRAY, we've found a back
edge, which means a cycle. This runs in O(V + E) time -- we visit
each node once and each edge once.

In our codebase, hasCycle() is called as a guard before
getTopologicalOrder(). If a cycle is detected, we throw a
DependencyCycleException rather than silently producing a partial
ordering."
```

### Code reference (DependencyResolver.hasCycle)

```java
// From DependencyResolver.java -- DFS three-coloring
private static final int WHITE = 0;
private static final int GRAY  = 1;
private static final int BLACK = 2;

public boolean hasCycle() {
    Map<String, Integer> color = new HashMap<>();
    for (String node : dependencies.keySet()) {
        color.put(node, WHITE);
    }
    for (String node : dependencies.keySet()) {
        if (color.get(node) == WHITE) {
            if (dfsCycleCheck(node, color)) {
                return true;
            }
        }
    }
    return false;
}

private boolean dfsCycleCheck(String node, Map<String, Integer> color) {
    color.put(node, GRAY);
    for (String dep : dependencies.getOrDefault(node, emptySet())) {
        Integer depColor = color.getOrDefault(dep, WHITE);
        if (depColor == GRAY) return true;   // back edge = cycle
        if (depColor == WHITE && dfsCycleCheck(dep, color)) return true;
    }
    color.put(node, BLACK);
    return false;
}
```

### 3.4 How Tasks Become "Ready"

```
WHAT TO DRAW:

  Task Readiness Flow (real-time, not batch)

  Unlike the full topological sort (which gives the complete order
  upfront), at runtime we use an incremental approach:

  completedTaskIds = {extract_users, extract_orders}

  For each waiting task:
    transform_data deps: [extract_users, extract_orders]
      -> completedTaskIds.containsAll(deps) = TRUE  -> READY!

    load_warehouse deps: [transform_data]
      -> completedTaskIds.containsAll(deps) = FALSE -> WAIT

  This is the getReadyTasks() method -- called on each tick().

  TIMELINE:

    t=0   submit all 5 tasks
          completedIds = {}
          ready = {extract_users, extract_orders}  (no deps)

    t=1   extract_users completes
          completedIds = {extract_users}
          ready = {}  (transform_data still needs extract_orders)

    t=2   extract_orders completes
          completedIds = {extract_users, extract_orders}
          ready = {transform_data}  ** both deps satisfied **

    t=3   transform_data completes
          completedIds = {extract_users, extract_orders, transform_data}
          ready = {load_warehouse, generate_report}  ** both unlocked **

    t=4   load_warehouse and generate_report complete
          completedIds = {all 5}
          ready = {}  ** DAG fully executed **
```

```
WHAT TO SAY:

"At runtime, we don't need the full topological sort. We use an
incremental readiness check: on each scheduler tick, we iterate
the waiting tasks and check if all their dependencies appear in
the completed set. This is O(W * D) where W is waiting tasks and
D is average dependency count.

The key insight is that tasks can run in parallel. In our ETL
example, extract_users and extract_orders run concurrently because
they have no dependencies on each other. Then load_warehouse and
generate_report also run concurrently once transform_data finishes.

The topological sort is useful for visualization and validation
(showing the user the planned execution order), but the actual
dispatch uses the incremental getReadyTasks() approach."
```

### 3.5 Complexity Analysis

```
+----------------------------+------------------+---------------------------+
| Operation                  | Time Complexity  | Space Complexity          |
+----------------------------+------------------+---------------------------+
| addDependency(t, d)        | O(1) amortized   | O(E) total edges         |
| removeDependency(t, d)     | O(1)             | --                        |
| getReadyTasks(completed)   | O(V * D)         | O(V) for result set      |
| getTopologicalOrder()      | O(V + E)         | O(V + E) for data structs|
| hasCycle()                 | O(V + E)         | O(V) for color map       |
+----------------------------+------------------+---------------------------+

Where:
  V = number of tasks (vertices)
  E = number of dependency edges
  D = average dependencies per task (usually small, 2-5)
```

### 3.6 Real Example: ETL Pipeline

```
WHAT TO SAY:

"Let me make this concrete with a real-world example. Imagine a
nightly ETL pipeline at a company:

  1. extract_users      -- pull from Users MySQL replica (5 min)
  2. extract_orders     -- pull from Orders PostgreSQL (10 min)
  3. transform_data     -- join and clean datasets (15 min)
  4. load_warehouse     -- write to Snowflake (5 min)
  5. generate_report    -- build executive dashboard (3 min)

Tasks 1 and 2 have no dependencies, so they run in parallel.
Task 3 depends on both 1 and 2.
Tasks 4 and 5 both depend on 3 and can run in parallel.

Without the DAG resolver, you'd have to run them sequentially:
  5 + 10 + 15 + 5 + 3 = 38 minutes

With parallel execution via DAG resolution:
  max(5, 10) + 15 + max(5, 3) = 10 + 15 + 5 = 30 minutes

That's a 21% improvement, and it gets much larger with bigger DAGs."
```

### Common follow-up questions for Phase 3

```
Q: "What happens if a dependency task fails?"
A: "Two options, configurable per task:
    1. FAIL_FAST: Mark all downstream tasks as FAILED immediately.
       We traverse the DAG forward from the failed node and cancel
       all reachable tasks.
    2. RETRY_THEN_FAIL: Retry the failed task per its RetryPolicy.
       Downstream tasks remain in WAITING state. If all retries
       exhaust, then cascade the failure."

Q: "How do you handle dynamic dependencies -- tasks that add new
    dependencies at runtime?"
A: "Our DependencyResolver supports addDependency() and
   removeDependency() at any time. A task in the WAITING state
   can have dependencies added. However, we must check for cycles
   after each addition -- O(V+E) -- which could be expensive for
   large DAGs. In practice, we validate the full graph on
   submission and only allow pre-declared dependencies."

Q: "What if the DAG has thousands of nodes?"
A: "Kahn's algorithm is O(V+E) which handles thousands easily.
   The bottleneck would be the getReadyTasks() call on each tick
   if there are many waiting tasks. We can optimize by maintaining
   an 'almost ready' index -- tasks with only one unmet dependency --
   and only checking those when a task completes."

Q: "Can you support conditional branching in the DAG?"
A: "Yes -- this is how Airflow's BranchPythonOperator works. We'd
   add a 'condition' field to each dependency edge. When a task
   completes, we evaluate the condition and only propagate readiness
   if the condition is met. Failed branches get pruned."
```

---
---

## PHASE 4: DEEP DIVE -- LEADER ELECTION & FAILOVER (5-7 min)

### What this phase signals to the interviewer

```
STAFF SIGNAL: You understand distributed consensus, can explain
why a simple algorithm like Bully works for this use case, and
know when to escalate to Raft or ZooKeeper. You also think about
failure modes (split-brain, network partitions) proactively.
```

### What to say to transition

```
"Now let me talk about high availability. The SchedulerEngine is the
brain of the system -- if it goes down, no tasks get dispatched. We
need multiple scheduler nodes with only one active leader at a time."
```

### 4.1 Bully Algorithm Step-by-Step

```
WHAT TO DRAW:

  Bully Algorithm -- Leader Election

  Setup: 3 scheduler nodes, each with a priority
    Node A (priority=1)
    Node B (priority=2)
    Node C (priority=3)  <-- highest priority = current leader

  NORMAL OPERATION:
    Node C is leader, dispatching tasks.
    Nodes A and B are standby (hot standby, receiving state updates).
    All nodes send heartbeats every 10 seconds.

  LEADER FAILURE (Node C goes down):

    Step 1: Node A and Node B detect missing heartbeat from C
            (no heartbeat for 30 seconds = ALIVE_TIMEOUT)

    Step 2: Node A starts election
            - Sends ELECTION message to all nodes with higher priority
            - Node B responds with ALIVE (B has higher priority than A)
            - Node A backs down

    Step 3: Node B starts election
            - Sends ELECTION message to Node C (higher priority)
            - No response from C (it's dead)
            - No other node has higher priority than B

    Step 4: Node B declares itself leader
            - Sends COORDINATOR message to all nodes
            - Node B begins dispatching tasks

    Step 5: Node C comes back online
            - Detects it has the highest priority
            - Starts a new election
            - All lower-priority nodes back down
            - Node C becomes leader again
```

```
WHAT TO SAY:

"The Bully algorithm is simple: the node with the highest priority
wins. When a node detects the leader is dead (via heartbeat timeout),
it challenges all higher-priority nodes. If none respond, it becomes
the leader. If a higher-priority node is alive, it takes over.

Key property: the highest-priority alive node ALWAYS becomes the
leader. This means when a previously-dead high-priority node comes
back, it 'bullies' the current leader and takes over. This is
actually desirable for us because the highest-priority node is
typically the one with the most resources."
```

### Code reference (LeaderElectionService.java)

```java
// Key method from LeaderElectionService.java
public SchedulerNode electLeader() {
    List<SchedulerNode> allNodes = nodeRepo.findAll();

    // Step 1: Filter to alive nodes (heartbeat within ALIVE_TIMEOUT)
    List<SchedulerNode> aliveNodes = allNodes.stream()
            .filter(node -> node.isAlive(ALIVE_TIMEOUT))
            .toList();

    // Step 2: Bully algorithm -- pick highest priority, break ties by nodeId
    SchedulerNode leader = aliveNodes.stream()
            .max(Comparator.comparingInt(SchedulerNode::getPriority)
                    .thenComparing(SchedulerNode::getNodeId))
            .orElseThrow();

    // Step 3: Update all nodes -- only the winner is leader
    for (SchedulerNode node : allNodes) {
        node.setLeader(node.getNodeId().equals(leader.getNodeId()));
        nodeRepo.save(node);
    }
    return leader;
}
```

### 4.2 Heartbeat-Based Failure Detection

```
WHAT TO DRAW:

  Heartbeat Flow

    +--------+    heartbeat (every 10s)    +---------+
    |Worker 1| ------- UDP/TCP ----------> |Scheduler|
    +--------+                             | (Leader)|
                                           +---------+
    +--------+    heartbeat (every 10s)        |
    |Worker 2| ------- UDP/TCP ---------->     |
    +--------+                                 |
                                               v
                                         +-----------+
                                         | Heartbeat |
                                         | Monitor   |
                                         +-----------+
                                               |
                                    no heartbeat for 30s?
                                               |
                                    +----------v----------+
                                    | Mark worker DEAD    |
                                    | Reassign its tasks  |
                                    +---------------------+

  ALIVE_TIMEOUT = 30 seconds (3 missed heartbeats)

  Why 30 seconds?
    - Too short (5s): false positives during GC pauses or network blips
    - Too long (120s): tasks sit idle on dead workers for 2 minutes
    - 30s is a good balance for most workloads
```

### 4.3 Worker Failure -> Task Reassignment Flow

```
WHAT TO DRAW:

  Failover Flow (numbered steps)

    1. Worker 2 stops sending heartbeats
    2. FailoverService.detectDeadWorkers(30s) marks Worker 2 as DEAD
    3. Find all RUNNING executions assigned to Worker 2
    4. For each execution:
       a. Set task status back to QUEUED
       b. Call AssignmentStrategy.assignTask() with available workers
       c. If a worker is available, set status to ASSIGNED
       d. If no workers available, task remains QUEUED for next tick

  TIMELINE:
    t=0    Worker 2 sends last heartbeat
    t=10   Worker 2 misses first heartbeat (scheduler notes it)
    t=20   Worker 2 misses second heartbeat
    t=30   ALIVE_TIMEOUT exceeded -- Worker 2 marked DEAD
    t=30.1 Tasks from Worker 2 reassigned to Worker 1 or Worker 3
    t=30.2 Reassigned tasks resume execution (may restart from beginning
           or from checkpoint, depending on task implementation)
```

### Code reference (FailoverService.java)

```java
// Key methods from FailoverService.java
public void performFailover(Duration timeout) {
    // 1. Detect dead workers
    List<Worker> deadWorkers = detectDeadWorkers(timeout);

    // 2. For each dead worker, find RUNNING executions and reassign
    for (Worker deadWorker : deadWorkers) {
        List<TaskExecution> running = execRepo
            .findByWorkerId(deadWorker.getId())
            .stream()
            .filter(exec -> exec.getStatus() == TaskStatus.RUNNING)
            .toList();

        for (TaskExecution exec : running) {
            taskService.updateTaskStatus(exec.getTaskId(), TaskStatus.QUEUED);
            List<Worker> available = workerService.getAvailableWorkers();
            Optional<Worker> newWorker =
                assignmentStrategy.assignTask(task, available);
            // ... assign if worker found
        }
    }
}
```

### 4.4 Split-Brain Prevention

```
WHAT TO SAY:

"Split-brain is the main risk with leader election. Imagine a
network partition where Node A thinks Node C is dead, but Node C
is still alive and dispatching on the other side of the partition.
Now you have two leaders dispatching the same tasks -- disaster.

Three defenses:

  1. FENCING TOKENS: Each leader election increments a monotonic
     epoch number. Workers only accept tasks with an epoch >= their
     last seen epoch. A stale leader's tasks get rejected.

  2. QUORUM WRITES: Task state changes (QUEUED -> RUNNING) require
     a majority quorum of scheduler nodes to agree. A partitioned
     leader without quorum can't dispatch.

  3. LEASE-BASED LEADERSHIP: The leader holds a time-bounded lease
     (e.g., 60 seconds). If it can't renew the lease (because it's
     partitioned from the lease store), it must stop dispatching.

In our implementation, the Bully algorithm is a starting point.
For production, I'd recommend option 3 (lease-based) using a
coordination service like ZooKeeper or etcd."
```

### 4.5 When to Use Raft/ZooKeeper Instead

```
WHAT TO DRAW:

  Decision Matrix: Which Election Algorithm?

  +-------------------+---------+----------+-----------+-----------+
  | Criteria          | Bully   | Raft     | ZooKeeper | etcd      |
  +-------------------+---------+----------+-----------+-----------+
  | Nodes             | < 10    | 3-7      | 3-7       | 3-7       |
  | Consistency       | Weak    | Strong   | Strong    | Strong    |
  | Split-brain safe  | No      | Yes      | Yes       | Yes       |
  | Complexity        | Low     | High     | Medium*   | Medium*   |
  | Latency overhead  | Low     | Medium   | Medium    | Medium    |
  | External deps     | None    | None     | ZK JVM    | etcd bin  |
  +-------------------+---------+----------+-----------+-----------+
  * Medium because you use a client library, not implement yourself

WHAT TO SAY:

"Bully works for our interview design because it's simple and
demonstrates the concept. In production:

  - If you control all scheduler nodes and need strong consistency,
    use Raft (embedded, no external dependencies).
  - If you already run ZooKeeper (e.g., Kafka cluster), use ZK's
    ephemeral nodes for leader election -- it's battle-tested.
  - If you're on Kubernetes, use etcd's lease API since etcd is
    already running in the cluster.

The key insight: leader election is a solved problem. Don't
reinvent it. Pick the coordination service that already exists
in your infrastructure."
```

### Common follow-up questions for Phase 4

```
Q: "What happens if ALL scheduler nodes go down?"
A: "No tasks get dispatched, but no tasks are lost. Tasks remain
   in the persistent TaskRepository with status QUEUED. When any
   scheduler node comes back up, it reads all QUEUED tasks and
   resumes dispatching. This is crash recovery, not data loss."

Q: "How do you prevent a recovering leader from re-dispatching
    tasks that are already running on workers?"
A: "Two mechanisms:
    1. The task state in the repository shows RUNNING, so the
       scheduler skips it during dispatch.
    2. Fencing tokens: the new leader's epoch is higher, so even
       if there's a race, the worker will reject duplicate dispatch
       from the old epoch."

Q: "What's the blast radius of a leader election?"
A: "During election (typically 1-3 seconds), no new tasks are
   dispatched. Tasks already running on workers continue normally --
   they don't depend on the leader. The only impact is a brief
   pause in NEW task dispatch. Completed task callbacks are buffered
   and processed once the new leader is active."
```

---
---

## PHASE 5: RETRY & EXACTLY-ONCE EXECUTION (3-5 min)

### What this phase signals to the interviewer

```
STAFF SIGNAL: You understand the subtlety of distributed execution
guarantees. You know that "exactly-once" is really "at-least-once
plus idempotency" and can explain the math behind backoff with jitter.
```

### 5.1 Exponential Backoff with Jitter Formula

```
WHAT TO DRAW:

  Retry Delay Formula:

    base_delay = initial_delay * multiplier ^ (attempt - 1)
    capped_delay = min(base_delay, max_delay)
    jitter_factor = random(0.9, 1.1)          // +/- 10%
    actual_delay = capped_delay * jitter_factor

  With our defaults (initial=1000ms, multiplier=2.0, max=30000ms):

    Attempt 1: 1000 * 2^0 = 1000ms  * jitter = 900-1100ms
    Attempt 2: 1000 * 2^1 = 2000ms  * jitter = 1800-2200ms
    Attempt 3: 1000 * 2^2 = 4000ms  * jitter = 3600-4400ms
    Attempt 4: 1000 * 2^3 = 8000ms  * jitter = 7200-8800ms
    Attempt 5: 1000 * 2^4 = 16000ms * jitter = 14400-17600ms
    Attempt 6: 1000 * 2^5 = 32000ms -> capped at 30000ms * jitter

  VISUAL (time axis, not to scale):

    |--1s--|----2s----|--------4s--------|
    X      X          X                  X ...
    fail   retry1     retry2             retry3

  WHY JITTER MATTERS:

    Without jitter: If 1000 tasks fail at t=0, all 1000 retry at
    t=1s, overwhelming the downstream service again (thundering herd).

    With +/-10% jitter: retries spread across [900ms, 1100ms],
    reducing peak load by ~5x.

    For even better distribution, use "full jitter":
      actual_delay = random(0, capped_delay)
    This spreads retries across the entire interval.
```

### Code reference (ExponentialBackoffRetryStrategy.java)

```java
// From ExponentialBackoffRetryStrategy.java
@Override
public long getRetryDelayMillis(int attemptNumber) {
    // 1. Compute base delay: initialDelay * multiplier^(attempt-1)
    double baseDelay = initialDelayMs * Math.pow(multiplier, attemptNumber - 1);

    // 2. Cap at maxDelay
    long cappedDelay = (long) Math.min(baseDelay, maxDelayMs);

    // 3. Apply +/-10% jitter to prevent thundering herd
    double jitterFactor = 0.9 + ThreadLocalRandom.current().nextDouble() * 0.2;
    return (long) (cappedDelay * jitterFactor);
}
```

### 5.2 Idempotency Key Pattern

```
WHAT TO DRAW:

  Idempotency Key Flow

    +--------+     task_id + idempotency_key     +--------+
    |Scheduler| -------------------------------->| Worker |
    +--------+                                   +--------+
                                                      |
                                                      v
                                               +-------------+
                                               | Check: has   |
                                               | this key been|
                                               | processed?   |
                                               +------+------+
                                                 /          \
                                               Yes           No
                                               /              \
                                              v                v
                                        +----------+    +-----------+
                                        | Return   |    | Execute   |
                                        | cached   |    | task, then|
                                        | result   |    | store key |
                                        +----------+    | + result  |
                                                        +-----------+

  The idempotency key is:
    key = hash(task_id + attempt_number + epoch)

  Stored in a fast lookup table (Redis SET or DB unique constraint).

WHAT TO SAY:

"Every task execution carries an idempotency key composed of the
task ID, attempt number, and the leader epoch. Before executing,
the worker checks if this key already exists in the idempotency
store. If yes, it returns the cached result. If no, it executes
and atomically stores the key with the result.

This handles the case where a task completes but the acknowledgment
is lost -- the scheduler retries, but the worker recognizes it as
a duplicate and returns the existing result."
```

### 5.3 Fencing Tokens for Stale Worker Prevention

```
WHAT TO DRAW:

  Fencing Token Flow

  Scenario: Worker A receives task T1 with epoch=5.
  Worker A slows down (GC pause). Scheduler thinks A is dead.
  Task T1 is reassigned to Worker B with epoch=6.

  WITHOUT fencing tokens:
    Worker A wakes up and writes T1 result -> CORRUPTS Worker B's work!

  WITH fencing tokens:
    Worker A wakes up, tries to write T1 result with epoch=5
    Storage layer rejects: "current epoch for T1 is 6, not 5"
    Worker A's stale write is blocked.

  +----------+                +----------+            +---------+
  | Worker A |  write(T1,     | Task     |            | Worker B|
  | (stale)  |  epoch=5) -->  | Store    | <-- write  | (active)|
  +----------+     |          +----------+   (T1,     +---------+
                   |               |         epoch=6)
                   v               v
              REJECTED!       ACCEPTED!
              epoch 5 < 6     epoch 6 = current
```

```
WHAT TO SAY:

"Fencing tokens are monotonically increasing epoch numbers assigned
during leader election. Each task dispatch carries the current epoch.
The storage layer enforces that writes must have an epoch >= the
last written epoch for that task. This prevents stale workers from
overwriting results produced by the correctly-assigned worker."
```

### 5.4 At-Least-Once + Idempotent = Exactly-Once

```
WHAT TO SAY:

"True exactly-once delivery is impossible in a distributed system
(proven by the Two Generals Problem). What we actually implement is:

  EXACTLY-ONCE SEMANTICS =
      at-least-once delivery (via retry)
    + idempotent execution   (via idempotency keys)
    + fencing tokens         (via epoch numbers)

  - Retry guarantees that a task WILL eventually execute (at-least-once).
  - Idempotency keys guarantee that duplicate executions produce
    the same result and don't have duplicate side effects.
  - Fencing tokens guarantee that stale executions are rejected.

Together, these three mechanisms give us exactly-once semantics
from the perspective of the external world, even though internally
the task may be dispatched multiple times."
```

### Common follow-up questions for Phase 5

```
Q: "How do you make a task idempotent if it sends an email?"
A: "The idempotency check happens BEFORE the side effect. Store
   the idempotency key and mark the task as 'in-progress' atomically.
   If the worker crashes after sending the email but before recording
   success, the retry will find the key, see 'in-progress', and
   check if the email was actually sent (via a delivery receipt or
   message ID from the email provider). This is 'idempotency at
   the application level' -- the task author must design for it."

Q: "What's the retry limit?"
A: "Configurable per task via maxRetries (default 3 in our Builder).
   After exhausting retries, the task moves to FAILED status and
   triggers an alert. A manual intervention workflow can re-submit
   the task or escalate to an on-call engineer."

Q: "How do you handle poison pills -- tasks that always fail?"
A: "After maxRetries failures, the task goes to a dead-letter queue
   (DLQ). The DLQ is a separate repository that ops engineers can
   inspect. We also track consecutive failures per task type -- if
   a type has >50% failure rate, we circuit-break and stop scheduling
   that type until manually re-enabled."
```

---
---

## PHASE 6: SCALING & TRADEOFFS (3-5 min)

### What this phase signals to the interviewer

```
STAFF SIGNAL: You think about operational reality -- not just "does
it work" but "does it work at 10x scale" and "what breaks first."
You name specific CAP tradeoffs rather than hand-waving.
```

### 6.1 Horizontal Scaling of Scheduler Nodes

```
WHAT TO DRAW:

  Scaling the Scheduler

  Single scheduler (baseline):
    +----------+     +-------+     +-------+
    | Scheduler| --> |Worker1| ... |WorkerN|
    | (leader) |     +-------+     +-------+
    +----------+

  Horizontally scaled (HA):
    +----------+     +----------+     +----------+
    | Scheduler|     | Scheduler|     | Scheduler|
    | Node 1   |     | Node 2   |     | Node 3   |
    | (LEADER) |     | (standby)|     | (standby)|
    +-----+----+     +----------+     +----------+
          |
          v
    +-----+-----+-----+-----+
    |W1   |W2   |W3   |W4   |
    +-----+-----+-----+-----+

  Active-passive: Only the leader dispatches.
  Standby nodes receive replicated state and are ready to take over.

  For ACTIVE-ACTIVE (higher throughput):
    Partition tasks by type or tenant:
    +----------+     +----------+     +----------+
    | Scheduler|     | Scheduler|     | Scheduler|
    | (ETL     |     | (Email   |     | (Report  |
    |  tasks)  |     |  tasks)  |     |  tasks)  |
    +----------+     +----------+     +----------+

  Each scheduler node owns a partition and is the leader for
  that partition. No coordination needed across partitions.
```

### 6.2 Task Sharding by Type/Tenant

```
WHAT TO SAY:

"There are two sharding strategies:

  1. SHARD BY TASK TYPE:
     - ETL tasks go to Scheduler A
     - Notification tasks go to Scheduler B
     - Report tasks go to Scheduler C
     - Pro: Each scheduler can be tuned for its workload
     - Con: Uneven load if one type dominates

  2. SHARD BY TENANT (multi-tenant):
     - hash(tenant_id) % num_schedulers = partition
     - Pro: Even distribution, tenant isolation
     - Con: Need consistent hashing for rebalancing when
            schedulers are added/removed

  For workers, we use tags (Set<String> on the Worker model).
  A task can require specific tags (e.g., 'gpu', 'high-memory'),
  and the AssignmentStrategy only considers workers with matching
  tags. This gives us affinity-based routing."
```

### 6.3 Queue Depth Monitoring and Backpressure

```
WHAT TO DRAW:

  Backpressure Flow

    Task submission rate: 1000/sec
    Worker processing rate: 500/sec
    Queue depth growing by 500/sec!

    +----------+     +----------+     +----------+
    | API      | --> | Task     | --> | Workers  |
    | Gateway  |     | Queue    |     | (500/sec)|
    | (1000/s) |     | (growing)|     |          |
    +----------+     +----------+     +----------+
                     depth: 10K
                         |
                    depth > 5K?
                         |
                    +----v----+
                    | TRIGGER |
                    | backpres|
                    +---------+
                         |
              +----------+----------+
              |                     |
              v                     v
        +---------+           +---------+
        | Reject  |           | Scale   |
        | new     |           | up      |
        | tasks   |           | workers |
        | (429)   |           | (auto)  |
        +---------+           +---------+

  Monitoring thresholds:
    queue_depth > 1K   -> WARN  (log + alert)
    queue_depth > 5K   -> SCALE (autoscale workers)
    queue_depth > 10K  -> REJECT (return HTTP 429)
```

```
WHAT TO SAY:

"Backpressure is critical. Our MonitoringService tracks queue depth
and worker utilization. When queue depth exceeds thresholds, we
have three responses:

  1. WARN: Alert ops team via the monitoring dashboard
  2. SCALE: Trigger worker autoscaling (add more workers)
  3. REJECT: Return HTTP 429 Too Many Requests to the client

The client should implement retry with backoff when receiving 429.
This creates an end-to-end backpressure chain: clients slow down,
queue stabilizes, workers catch up."
```

### 6.4 CP vs AP Tradeoff

```
WHAT TO DRAW:

  CAP Tradeoff Matrix for Our Components

  +--------------------+------+--------+---------------------------+
  | Component          | CAP  | Why    | Consequence               |
  +--------------------+------+--------+---------------------------+
  | Task State Store   | CP   | Tasks  | Writes go through leader  |
  | (TaskRepository)   |      | must   | only. If leader is down,  |
  |                    |      | not be | writes block until new    |
  |                    |      | double-| leader elected. Reads can |
  |                    |      | dispatd| go to replicas (stale OK).|
  +--------------------+------+--------+---------------------------+
  | Worker Registry    | CP   | Worker | Same as above. Stale      |
  | (WorkerRepository) |      | state  | worker info could cause   |
  |                    |      | must be| dispatch to dead workers. |
  |                    |      | fresh  |                           |
  +--------------------+------+--------+---------------------------+
  | Metrics / Monitoring| AP  | Metrics| Can tolerate 5-second     |
  | (MonitoringService) |     | can be | staleness. Availability   |
  |                    |      | stale  | more important than       |
  |                    |      |        | precision for dashboards. |
  +--------------------+------+--------+---------------------------+
  | Execution History  | AP   | Audit  | Eventually consistent is  |
  | (ExecutionRepo)    |      | trail  | fine for historical data. |
  |                    |      | is not | Writes are append-only.   |
  |                    |      | realtime|                          |
  +--------------------+------+--------+---------------------------+

WHAT TO SAY:

"I split the system by consistency needs:

  CP for task state and worker registry -- these are on the critical
  dispatch path. A task must not be double-dispatched, so we need
  strong consistency for QUEUED -> RUNNING transitions.

  AP for metrics and execution history -- these are observational.
  A dashboard showing 5-second-old data is fine. We favor availability
  so monitoring never goes down even during leader election."
```

### Common follow-up questions for Phase 6

```
Q: "What's the bottleneck at 10x scale?"
A: "The single-leader scheduler dispatch loop. At 10x, the tick()
   method processes too many tasks per cycle. Solution: shard by
   task type so each scheduler node handles a subset. The DAG
   resolver is also a bottleneck if DAGs are large -- we'd move
   to an event-driven model where task completions trigger downstream
   readiness checks (push) instead of polling (pull)."

Q: "How would you migrate from in-memory to persistent queues?"
A: "The Repository pattern makes this clean. We swap
   InMemoryTaskRepository for a PostgresTaskRepository that
   implements the same interface. The TaskQueue could be backed
   by a Redis sorted set (ZADD with priority score). The
   SchedulerEngine doesn't change because it only talks to
   interfaces."

Q: "What database would you use for task state?"
A: "PostgreSQL with SKIP LOCKED for the task queue. The query:
   SELECT * FROM tasks WHERE status='QUEUED'
   ORDER BY priority DESC, created_at ASC
   LIMIT 10 FOR UPDATE SKIP LOCKED;
   This gives you a distributed, persistent priority queue with
   no double-dispatch. It's what Temporal uses internally."
```

---
---

## PHASE 7: EDGE CASES (2-3 min)

### What this phase signals to the interviewer

```
STAFF SIGNAL: You proactively raise failure modes the interviewer
hasn't asked about. This demonstrates operational maturity and
shows you've run systems in production.
```

### Edge Case 1: Circular Dependencies in DAG

```
PROBLEM:
  Task A depends on B, B depends on C, C depends on A.
  No task can ever become "ready" -- deadlock.

DETECTION:
  hasCycle() via DFS three-coloring, O(V+E).
  Run at submission time, not at dispatch time.

RESPONSE:
  Throw DependencyCycleException immediately on submission.
  Return HTTP 400 to the client with the cycle path:
    "Cycle detected: A -> B -> C -> A"

PREVENTION:
  Validate the entire DAG on each addDependency() call.
  For large DAGs, maintain an incremental cycle detector
  (track SCCs using Tarjan's algorithm).

WHAT TO SAY:
"We validate for cycles at submission time using DFS three-coloring.
If a cycle is detected, we reject the submission immediately with
the cycle path in the error message. We never let a cyclic DAG
enter the system."
```

### Edge Case 2: All Workers Down

```
PROBLEM:
  Every worker has missed its heartbeat. FailoverService marks
  all workers as DEAD. No available workers for assignment.

RESPONSE:
  1. Tasks remain in QUEUED status -- no data loss.
  2. SchedulerService.scheduleAndDispatch() logs
     "No available workers -- tasks remain queued"
  3. MonitoringService detects 0% worker utilization -> CRITICAL alert
  4. Autoscaler spins up new workers.
  5. When first worker comes online, pending tasks dispatch
     in priority order.

WHAT TO SAY:
"This is a total worker outage. The key design decision is that
tasks are never lost -- they stay QUEUED. The scheduler keeps
ticking but finds no workers. Once workers recover, the backlog
drains in priority order. We'd fire a PagerDuty alert immediately
when available_workers drops to zero."
```

### Edge Case 3: Scheduler Leader Crash During Dispatch

```
PROBLEM:
  Leader is midway through dispatching a batch of 10 tasks.
  It has dispatched 5 and crashes before dispatching the remaining 5.

ANALYSIS:
  - The 5 dispatched tasks: Workers have them and will execute.
    Results will be written with the current epoch.
  - The 5 undispatched tasks: Still in QUEUED state in the repository.

RESPONSE:
  1. Bully algorithm elects a new leader (within 30s).
  2. New leader reads all QUEUED tasks from the repository.
  3. New leader dispatches the remaining 5 tasks.
  4. The first 5 tasks may report completion to the new leader.
     The new leader can accept these because the task ID matches.

RISK:
  What if 3 of the 5 dispatched tasks were set to ASSIGNED status
  but the leader crashed before persisting? Then the new leader
  sees them as QUEUED and re-dispatches -> DOUBLE EXECUTION.

MITIGATION:
  Use a transactional batch: set all 10 tasks to ASSIGNED in a
  single transaction BEFORE dispatching to workers. If the leader
  crashes after the transaction but before dispatch, the new leader
  sees them as ASSIGNED and can re-dispatch (at-least-once).
  Idempotency keys prevent duplicate side effects.

WHAT TO SAY:
"The key insight is separating the STATE TRANSITION (QUEUED -> ASSIGNED)
from the DISPATCH (sending to worker). We do the state transition
first, in a transaction. If the leader crashes after the transition
but before dispatch, the new leader sees ASSIGNED tasks with no
heartbeat from a worker and re-dispatches. Idempotency handles
any duplicates."
```

### Edge Case 4: Task Timeout While Worker is Alive

```
PROBLEM:
  A task has timeoutMillis=60000 (1 minute). The worker is alive
  and sending heartbeats, but the task has been RUNNING for 5 minutes.
  The worker is stuck (infinite loop, resource contention).

DETECTION:
  FailoverService checks: is the task RUNNING for longer than
  its timeoutMillis? If yes, the task has timed out even though
  the worker is healthy.

RESPONSE:
  1. Send a CANCEL signal to the worker for this specific task.
  2. Set task status to TIMED_OUT.
  3. If the task has retries remaining, re-queue it.
  4. If retries exhausted, move to FAILED + DLQ.
  5. The worker should have a watchdog thread that kills
     long-running tasks.

WHAT TO SAY:
"Worker-alive-but-task-stuck is different from worker-dead. We need
per-task timeout enforcement, not just per-worker heartbeat checks.
The FailoverService tracks task start time and compares it to the
configured timeout. The worker itself should also run a watchdog
thread that interrupts tasks exceeding their timeout."
```

### Edge Case 5: Priority Inversion / Starvation

```
PROBLEM:
  A continuous stream of CRITICAL priority tasks arrives.
  LOW priority tasks sit in the queue forever -- starvation.

DETECTION:
  MonitoringService tracks per-priority queue wait time.
  Alert if any task has waited more than a threshold (e.g., 1 hour).

RESPONSE (three strategies):

  1. AGING: Gradually increase the effective priority of a task
     based on how long it has waited.
       effective_priority = base_priority + (wait_time / aging_factor)
     After waiting long enough, a LOW task becomes equivalent to HIGH.

  2. FAIR-SHARE: Reserve a percentage of worker capacity for each
     priority level:
       CRITICAL: 50% of workers
       HIGH:     30% of workers
       MEDIUM:   15% of workers
       LOW:       5% of workers

  3. SEPARATE QUEUES: Maintain separate queues per priority.
     Scheduler round-robins across queues but with weighted
     probability (80% CRITICAL, 15% HIGH, 4% MEDIUM, 1% LOW).

WHAT TO SAY:
"Priority inversion is a classic scheduling problem. Our TaskQueue
uses strict priority ordering, which can starve low-priority tasks.
The fix is aging -- we increment effective priority based on wait
time. In our PriorityQueue comparator, we'd add a bonus based on
the duration since createdAt."
```

### Bonus Edge Case: Duplicate Task Submission

```
PROBLEM:
  Client submits the same task twice due to a network retry.

RESPONSE:
  The Task.Builder generates a UUID for each task. If the client
  provides its own idempotency key (e.g., in the payload), we
  check for duplicates in the TaskRepository before creating.
  If found, return the existing task ID (HTTP 200, not 201).
```

---
---

## APPENDIX A: DESIGN PATTERNS CHEAT SHEET

Quick reference for all 15 design pattern instances in the codebase.
Cite these naturally during the interview -- do not list them all at once.

```
+------------------------+------+------------------------------------+
| Pattern                | GoF  | Where in Codebase                  |
+========================+======+====================================+
| Strategy               | Yes  | TaskAssignmentStrategy             |
|                        |      |   RoundRobinAssignmentStrategy     |
|                        |      |   LeastLoadedAssignmentStrategy    |
+------------------------+------+------------------------------------+
| Strategy               | Yes  | RetryStrategy                      |
|                        |      |   ExponentialBackoffRetryStrategy  |
|                        |      |   FixedIntervalRetryStrategy       |
+------------------------+------+------------------------------------+
| Strategy               | Yes  | SchedulingStrategy                 |
|                        |      |   ImmediateSchedulingStrategy      |
|                        |      |   DelayedSchedulingStrategy        |
|                        |      |   CronSchedulingStrategy           |
+------------------------+------+------------------------------------+
| Builder                | Yes  | Task.Builder                       |
|                        |      |   Fluent API for task construction  |
+------------------------+------+------------------------------------+
| Factory                | Yes  | AppConfig                          |
|                        |      |   Lazy initialization + wiring     |
+------------------------+------+------------------------------------+
| Repository             | DDD  | TaskRepository                     |
|                        |      | WorkerRepository                   |
|                        |      | ExecutionRepository                |
|                        |      | SchedulerNodeRepository            |
+------------------------+------+------------------------------------+
| Facade                 | Yes  | SchedulerService                   |
|                        |      |   Unifies Task/Worker/Execution    |
|                        |      |   services behind a single API     |
+------------------------+------+------------------------------------+
| State                  | Yes  | TaskStatus enum                    |
|                        |      |   PENDING->QUEUED->RUNNING->...    |
+------------------------+------+------------------------------------+
| Observer               | Yes  | Task completion notifications      |
|                        |      |   notifyTaskCompletion() triggers  |
|                        |      |   downstream dependency checks     |
+------------------------+------+------------------------------------+
| Chain of Resp.         | Yes  | SchedulerEngine.tick()             |
|                        |      |   CRON check -> Dep resolve ->     |
|                        |      |   Queue drain -> Dispatch          |
+------------------------+------+------------------------------------+
| Command                | Yes  | Task as a command object            |
|                        |      |   Encapsulates execution request   |
|                        |      |   with payload, can be queued      |
+------------------------+------+------------------------------------+
| Singleton              | Yes  | AppConfig (composition root)       |
|                        |      |   Single instance wires all deps   |
+------------------------+------+------------------------------------+
```

### How to cite patterns naturally in the interview

```
GOOD: "The SchedulerService acts as a Facade -- it's the single
       entry point that coordinates TaskService, WorkerService,
       and the SchedulerEngine behind a unified API."

BAD:  "I'm using 15 design patterns: Strategy, Builder, Factory,
       Repository, Facade, State, Observer..." (sounds rehearsed)

RULE: Name the pattern when you introduce the component, then
      move on. Don't catalog them.
```

---

## APPENDIX B: COMPLEXITY CHEAT SHEET

```
+-------------------------------+-------------+-------------+
| Operation                     | Time        | Space       |
+===============================+=============+=============+
| Task submission               | O(1)        | O(1)        |
| Priority queue enqueue        | O(log n)    | O(n) total  |
| Priority queue dequeue        | O(log n)    | --          |
| DAG cycle detection (DFS)     | O(V + E)    | O(V)        |
| Topological sort (Kahn's)     | O(V + E)    | O(V + E)    |
| Ready task check (per tick)   | O(W * D)    | O(W)        |
| Leader election (Bully)       | O(N^2) msgs | O(N)        |
| Worker assignment (RR)        | O(W)        | O(1)        |
| Worker assignment (Least-Load)| O(W log W)  | O(W)        |
| Retry delay computation       | O(1)        | O(1)        |
| Monitoring dashboard          | O(T + W + E)| O(1)        |
+-------------------------------+-------------+-------------+

Where:
  n = number of tasks in queue
  V = vertices (tasks) in DAG
  E = edges (dependencies) in DAG
  W = number of waiting tasks or workers (context-dependent)
  D = average dependency count per task
  N = number of scheduler nodes
  T = total tasks, E = total executions
```

---

## APPENDIX C: QUICK-FIRE Q&A BANK

Rapid-fire answers for common interviewer follow-ups. Practice
answering each in under 30 seconds.

### Architecture Questions

```
Q: "Why not use Kafka for the task queue?"
A: "Kafka doesn't natively support priority ordering or DAG-based
   dequeuing. We'd need a separate priority router in front of
   Kafka. For <100K tasks/day, an in-memory PriorityQueue or
   Redis sorted set is simpler and lower latency."

Q: "How would you add multi-tenancy?"
A: "Partition by tenant_id using consistent hashing. Each scheduler
   node handles a set of tenants. Tasks include a tenant_id field.
   Queue depth limits are enforced per tenant to prevent noisy
   neighbor problems."

Q: "How do you handle task versioning?"
A: "Each task type has a version field. When a task type is updated,
   existing running tasks continue with the old version. New
   submissions use the new version. This avoids mid-execution
   version conflicts."

Q: "What monitoring would you add on day one?"
A: "Five metrics: (1) queue depth per priority, (2) task dispatch
   latency p50/p99, (3) worker utilization, (4) failure rate per
   task type, (5) end-to-end task duration. All pushed to Prometheus
   with Grafana dashboards."
```

### Distributed Systems Questions

```
Q: "What happens during a network partition?"
A: "Depends on which side of the partition. The side with the leader
   continues dispatching. The side without the leader can't dispatch
   but tasks already running on workers there continue. When the
   partition heals, fencing tokens prevent stale state from the
   minority side."

Q: "How do you handle clock skew between nodes?"
A: "For heartbeats, we use relative durations (time since last
   heartbeat) rather than absolute timestamps. For cron scheduling,
   we use a single authoritative clock (the leader's clock). NTP
   synchronization keeps skew under 100ms which is sufficient."

Q: "What's your disaster recovery plan?"
A: "Task state is persisted to durable storage (PostgreSQL with
   streaming replication). Worker state is ephemeral (re-registers
   on restart). Execution history is append-only. To recover from
   a total datacenter loss: bring up PostgreSQL replica in another
   region, start scheduler and workers, and resume from QUEUED tasks."

Q: "How would you implement task checkpointing?"
A: "Long-running tasks periodically write their progress to a
   checkpoint store (key: task_id, value: serialized state).
   On retry, the worker checks for a checkpoint before starting
   fresh. This is how Spark handles task recovery."
```

### Java-Specific Questions

```
Q: "Why use a PriorityQueue instead of a TreeSet?"
A: "PriorityQueue is O(log n) insert/remove-min with lower constant
   factor than TreeSet. We don't need ordered iteration or contains
   checks (those are O(n) on PriorityQueue). We only ever dequeue
   the highest-priority element."

Q: "Why AtomicInteger for round-robin instead of synchronized?"
A: "AtomicInteger uses CAS (compare-and-swap) which is lock-free.
   Under contention from multiple dispatcher threads, CAS avoids
   the context-switch overhead of synchronized. For a single-
   threaded scheduler, it doesn't matter, but it's good practice."

Q: "Why ThreadLocalRandom instead of Math.random()?"
A: "Math.random() uses a shared static Random with a synchronized
   seed. Under concurrent access, it becomes a contention point.
   ThreadLocalRandom gives each thread its own RNG -- no locking."

Q: "Why Instant instead of LocalDateTime for timestamps?"
A: "Instant represents a point on the UTC timeline -- no timezone
   ambiguity. LocalDateTime has no timezone and can cause bugs
   when comparing across nodes in different timezones. For a
   distributed system, always use Instant or epoch millis."
```

### Behavioral / Staff-Level Questions

```
Q: "How would you roll this out incrementally?"
A: "Phase 1: Single scheduler, single queue, no DAG -- just
   priority-based dispatch. Ship in 2 weeks.
   Phase 2: Add DAG dependency resolution. Ship in 2 more weeks.
   Phase 3: Add leader election for HA. Ship in 3 weeks.
   Phase 4: Add monitoring and alerting. Ship in 1 week.
   Phase 5: Performance testing and hardening. Ship in 2 weeks.
   Total: ~10 weeks from zero to production-ready."

Q: "What would you build vs. buy?"
A: "If the company already runs Airflow or Temporal, use that.
   Build a custom scheduler only if: (1) you need sub-second
   dispatch latency (Airflow can't do this), (2) you need tight
   integration with internal systems, or (3) you need to avoid
   the operational burden of running Airflow/ZooKeeper."

Q: "How would you test this system?"
A: "Four levels:
   1. Unit tests: DependencyResolver cycle detection, RetryStrategy
      delay computation, TaskQueue ordering.
   2. Integration tests: Full submit-dispatch-execute-complete flow.
   3. Chaos tests: Kill workers mid-execution, partition the network,
      crash the leader during dispatch.
   4. Load tests: Submit 100K tasks, verify all complete with no
      duplicates and correct ordering."

Q: "What's the hardest bug you'd expect in this system?"
A: "A subtle race condition where two scheduler nodes both think
   they're the leader during an election window, and both dispatch
   the same task. The fencing token mechanism prevents data
   corruption, but the task runs twice and consumes resources.
   Detection: monitor for tasks with multiple RUNNING executions.
   Fix: tighter lease timeouts and quorum-based leadership."
```

---
---

## INTERVIEW TIMING GUIDE

```
+-------+--------------------------------------------+--------+
| Phase | Content                                    | Clock  |
+=======+============================================+========+
|   1   | Clarify requirements (6-8 questions)       | 0:00   |
|       | Confirm in/out scope on whiteboard          | 2:30   |
+-------+--------------------------------------------+--------+
|   2   | Draw 4-zone architecture                   | 2:30   |
|       | Walk through data flow for 1 task          | 5:00   |
|       | Name key components and patterns            | 8:00   |
+-------+--------------------------------------------+--------+
|   3   | Deep dive: DAG + Kahn's algorithm          | 8:00   |
|       | Draw step-by-step example                  | 12:00  |
|       | Cycle detection explanation                 | 14:00  |
|       | Incremental readiness at runtime            | 16:00  |
|       | ETL pipeline real-world example             | 18:00  |
+-------+--------------------------------------------+--------+
|   4   | Deep dive: Leader election + failover       | 18:00  |
|       | Bully algorithm walkthrough                 | 20:00  |
|       | Heartbeat + dead worker detection           | 22:00  |
|       | Split-brain prevention                      | 24:00  |
+-------+--------------------------------------------+--------+
|   5   | Retry + exactly-once semantics              | 24:00  |
|       | Backoff formula + jitter                    | 26:00  |
|       | Idempotency + fencing tokens                | 28:00  |
+-------+--------------------------------------------+--------+
|   6   | Scaling + CAP tradeoffs                     | 28:00  |
|       | Sharding strategies                        | 30:00  |
|       | Backpressure                               | 31:00  |
|       | CP vs AP per component                      | 32:00  |
+-------+--------------------------------------------+--------+
|   7   | Edge cases (rapid fire)                    | 32:00  |
|       | Cyclic DAG, all workers down, leader crash  | 33:30  |
|       | Task timeout, priority inversion            | 35:00  |
+-------+--------------------------------------------+--------+
```

---

## REHEARSAL CHECKLIST

Before the interview, practice these until fluent:

```
[ ] Can draw the 4-zone architecture from memory in under 2 minutes
[ ] Can explain Kahn's algorithm with the ETL example in under 3 minutes
[ ] Can explain DFS three-coloring for cycle detection in under 1 minute
[ ] Can derive the exponential backoff formula with jitter from memory
[ ] Can explain why exactly-once = at-least-once + idempotency in 30 seconds
[ ] Can name 3 split-brain prevention strategies without hesitation
[ ] Can articulate CP vs AP tradeoff for each component
[ ] Can walk through the worker failure -> task reassignment flow
[ ] Can answer "Why not Kafka?" and "Why not Airflow?" concisely
[ ] Can name the incremental rollout plan (5 phases, 10 weeks)
```

---

## KEY JAVA CLASS REFERENCES

For quick lookup during revision. All under `com.systemdesign.scheduler`:

```
engine/
  DependencyResolver.java    -- Kahn's algo + DFS cycle detection
  SchedulerEngine.java       -- Central tick() loop: cron + deps + queue
  TaskQueue.java             -- PriorityQueue (CRITICAL > HIGH > MEDIUM > LOW)
  CronParser.java            -- Cron expression parser

service/
  SchedulerService.java      -- Facade: submit, dispatch, cancel
  LeaderElectionService.java -- Bully algorithm leader election
  FailoverService.java       -- Dead worker detection + task reassignment
  MonitoringService.java     -- Metrics: utilization, failure rate, throughput
  TaskService.java           -- Task CRUD + status transitions
  WorkerService.java         -- Worker pool management
  ExecutionService.java      -- Task execution + retry

strategy/
  assignment/TaskAssignmentStrategy.java  -- Strategy interface
  assignment/RoundRobinAssignmentStrategy.java
  assignment/LeastLoadedAssignmentStrategy.java
  retry/RetryStrategy.java               -- Strategy interface
  retry/ExponentialBackoffRetryStrategy.java
  retry/FixedIntervalRetryStrategy.java
  scheduling/SchedulingStrategy.java      -- Strategy interface
  scheduling/ImmediateSchedulingStrategy.java
  scheduling/DelayedSchedulingStrategy.java
  scheduling/CronSchedulingStrategy.java

model/
  Task.java           -- Builder pattern, core domain entity
  Worker.java         -- Capacity + tags + heartbeat
  TaskStatus.java     -- State enum: PENDING/QUEUED/RUNNING/COMPLETED/FAILED
  TaskPriority.java   -- CRITICAL/HIGH/MEDIUM/LOW
  SchedulerNode.java  -- Scheduler cluster node with priority + leader flag

config/
  AppConfig.java      -- Factory/Composition Root, lazy wiring

controller/
  SchedulerController.java -- REST facade (Facade pattern)
```

---

## APPENDIX D: TASK STATE MACHINE

Draw this if the interviewer asks about task lifecycle.

```
                        TASK STATE MACHINE
  ================================================================

                          +----------+
                          |  PENDING |  (just created, not yet queued)
                          +----+-----+
                               |
                     submitTask() / submitTaskWithDependencies()
                               |
                +--------------+--------------+
                |                             |
                v                             v
          +----------+                 +------------+
          |  QUEUED  |                 |  WAITING   |  (has unmet dependencies)
          +----+-----+                 +------+-----+
               |                              |
               |              all dependencies completed
               |                              |
               +<-----------------------------+
               |
         assignTask() via strategy
               |
               v
          +----------+
          | ASSIGNED |  (worker selected, dispatch in progress)
          +----+-----+
               |
         worker begins execution
               |
               v
          +----------+
          | RUNNING  |  (worker actively executing)
          +----+-----+
               |
      +--------+--------+--------+
      |        |        |        |
   success   failure  timeout  cancelled
      |        |        |        |
      v        v        v        v
  +-------+ +------+ +-------+ +----------+
  |COMPLTD| |FAILED| |TIMED_ | |CANCELLED |
  +-------+ +--+---+ |OUT    | +----------+
                |     +---+---+
                |         |
           retries left?  retries left?
                |         |
                v         v
           +----------+
           |  QUEUED  |  (re-queued for retry)
           +----------+
                |
           retries exhausted
                |
                v
           +----------+
           |  DEAD    |  (moved to dead-letter queue)
           | LETTER   |
           +----------+

  Transitions enforced by TaskService.updateTaskStatus():
    PENDING   -> QUEUED, WAITING, CANCELLED
    WAITING   -> QUEUED (when deps met), CANCELLED
    QUEUED    -> ASSIGNED, CANCELLED
    ASSIGNED  -> RUNNING, CANCELLED
    RUNNING   -> COMPLETED, FAILED, TIMED_OUT, CANCELLED
    FAILED    -> QUEUED (retry), DEAD_LETTER (exhausted)
    TIMED_OUT -> QUEUED (retry), DEAD_LETTER (exhausted)

  TERMINAL STATES: COMPLETED, CANCELLED, DEAD_LETTER
```

```
WHAT TO SAY:

"The task lifecycle follows a strict state machine. The key
transitions are:

  1. PENDING to QUEUED: task accepted and ready for dispatch.
  2. QUEUED to ASSIGNED: a worker has been selected.
  3. ASSIGNED to RUNNING: the worker has acknowledged and started.
  4. RUNNING to COMPLETED/FAILED/TIMED_OUT: execution outcome.
  5. FAILED back to QUEUED: retry if attempts remain.

Each transition is validated -- you can't go from COMPLETED back
to RUNNING. The state machine prevents invalid transitions and
makes the system's behavior predictable."
```

---

## APPENDIX E: DATA FLOW FOR ONE COMPLETE TASK

End-to-end trace showing every component a single task touches.
Practice narrating this in 90 seconds.

```
  TRACE: Submit ETL task "extract_users" -> Execute -> Complete

  Step  | Component                | Action                        | State
  ------+--------------------------+-------------------------------+--------
    1   | Client                   | POST /tasks {name, priority}  | --
    2   | SchedulerController      | Validates request, delegates  | --
    3   | SchedulerService         | Calls taskService.createTask  | PENDING
    4   | TaskService              | Saves to TaskRepository       | PENDING
    5   | TaskRepository           | InMemoryTaskRepository.save() | PENDING
    6   | SchedulerEngine          | submitTask() -> enqueue       | QUEUED
    7   | TaskQueue                | PriorityQueue.offer()         | QUEUED
    8   | SchedulerEngine.tick()   | Checks cron, deps, drains PQ | QUEUED
    9   | SchedulerService         | Calls assignmentStrategy      | QUEUED
   10   | RoundRobinAssignment     | Selects Worker 2              | ASSIGNED
   11   | ExecutionService         | Creates TaskExecution record  | RUNNING
   12   | Worker 2                 | Executes task payload         | RUNNING
   13   | Worker 2                 | Task completes successfully   | RUNNING
   14   | ExecutionService         | Marks execution COMPLETED     | COMPLETED
   15   | TaskService              | Updates task status           | COMPLETED
   16   | SchedulerService         | notifyTaskCompletion()        | COMPLETED
   17   | DependencyResolver       | getReadyTasks() rechecked     | --
   18   | MonitoringService        | Throughput counter incremented| --

  LATENCY BREAKDOWN:
    Steps  1-7  : <100ms  (API intake + queue insertion)
    Steps  8-10 : <10ms   (scheduler tick + assignment)
    Steps 11-13 : variable (actual task execution)
    Steps 14-18 : <50ms   (completion bookkeeping)
```

---

## APPENDIX F: COMPARISON WITH REAL SYSTEMS

Use this to answer "how does your design compare to X?" questions.

```
+-------------------+-------------------+-------------------+-------------------+
| Feature           | Our Design        | Apache Airflow    | Temporal          |
+===================+===================+===================+===================+
| DAG support       | Yes, Kahn's algo  | Yes, native       | Workflow-based    |
|                   | adjacency list    | Python DAG DSL    | not DAG-centric   |
+-------------------+-------------------+-------------------+-------------------+
| Scheduling        | Tick-based loop   | Scheduler loop    | Event-driven      |
| model             | (configurable     | (default 5s       | (no polling,      |
|                   | interval)         | interval)         | push-based)       |
+-------------------+-------------------+-------------------+-------------------+
| Leader election   | Bully algorithm   | HA via DB locks   | Membership ring   |
|                   |                   | (no true HA in    | (Temporal Server  |
|                   |                   | OSS pre-2.0)      | uses Ringpop)     |
+-------------------+-------------------+-------------------+-------------------+
| Worker model      | Push (scheduler   | Pull (workers     | Pull (workers     |
|                   | assigns tasks)    | claim tasks via   | poll task queues)  |
|                   |                   | Celery/K8s)       |                   |
+-------------------+-------------------+-------------------+-------------------+
| Retry             | Exponential       | Configurable per  | Built-in retry    |
|                   | backoff + jitter  | task via retries  | policies per       |
|                   |                   | parameter         | activity          |
+-------------------+-------------------+-------------------+-------------------+
| Exactly-once      | Idempotency keys  | At-least-once     | Built-in via      |
|                   | + fencing tokens  | (no native        | workflow history   |
|                   |                   | exactly-once)     | + event sourcing  |
+-------------------+-------------------+-------------------+-------------------+
| Queue backend     | In-memory PQ      | Redis/RabbitMQ    | Internal task     |
|                   | (swappable via    | via Celery        | queue backed by   |
|                   | Repository)       |                   | Cassandra/MySQL   |
+-------------------+-------------------+-------------------+-------------------+
| Monitoring        | MonitoringService | Flower + built-in | Temporal Web UI   |
|                   | + dashboard       | Airflow UI        | + metrics export  |
+-------------------+-------------------+-------------------+-------------------+

WHAT TO SAY:

"Our design is closest to Airflow's architecture -- a central scheduler
with DAG resolution and a priority queue feeding workers. The key
difference is that Airflow's scheduler polls the database for new DAG
runs, while ours uses an in-memory tick loop for lower latency.

Temporal takes a fundamentally different approach -- it's event-sourced
and workflow-centric rather than DAG-centric. Tasks (activities) are
pulled by workers, not pushed by a scheduler. Temporal's model gives
you better exactly-once guarantees out of the box but has higher
operational complexity."
```

---

## APPENDIX G: WHITEBOARD DRAWING ORDER

The exact sequence to draw on the whiteboard during the interview.
Practice this order so your drawing builds up naturally.

```
DRAWING 1 (Phase 2, minute 3):
  Four zones as horizontal bands.
  Start with Zone 1 (intake) at the top.
  Draw the arrow flow: Client -> API -> Service -> Repo.
  Then Zone 2 below it: Engine -> DAG -> Queue -> Assignment.
  Then Zone 3: Workers.
  Then Zone 4: Monitoring.
  Total drawing time: 2 minutes.

DRAWING 2 (Phase 3, minute 9):
  On a fresh area, draw the ETL DAG:
    extract_users ----+
                      +---> transform_data ---> load_warehouse
    extract_orders ---+          |
                                 +---> generate_report
  Then write the in-degree table next to it.
  Walk through Kahn's steps, crossing off nodes as you go.
  Total drawing time: 3 minutes.

DRAWING 3 (Phase 4, minute 19):
  Three boxes: Node A, Node B, Node C.
  Draw arrows showing heartbeats.
  Cross out Node C.
  Show the election message flow.
  Circle Node B as new leader.
  Total drawing time: 1.5 minutes.

DRAWING 4 (Phase 5, minute 25):
  Exponential curve with jitter bands.
  X-axis: attempt number. Y-axis: delay.
  Show the retry intervals getting wider.
  Write the formula above the curve.
  Total drawing time: 1 minute.

DRAWING 5 (Phase 7, minute 33):
  Task state machine (simplified).
  Just the main path: PENDING -> QUEUED -> RUNNING -> COMPLETED
  With a branch: RUNNING -> FAILED -> QUEUED (retry loop)
  Total drawing time: 30 seconds.
```

---

## APPENDIX H: ANTI-PATTERNS TO AVOID

Things that will hurt your interview score.

```
ANTI-PATTERN 1: "Let me just use Kafka for everything."
  WHY BAD: Shows you reach for tools without analyzing requirements.
  INSTEAD: "Let me think about what queue semantics we need first."

ANTI-PATTERN 2: Drawing 20 boxes before explaining any of them.
  WHY BAD: Interviewer loses context. Drawing without narration = noise.
  INSTEAD: Draw 3-4 boxes, explain, then add more iteratively.

ANTI-PATTERN 3: "We can solve this with microservices."
  WHY BAD: Vague. Microservices is an architecture style, not a solution.
  INSTEAD: Name the specific service, its responsibility, and its API.

ANTI-PATTERN 4: Not mentioning failure modes until asked.
  WHY BAD: Looks like you only think about the happy path.
  INSTEAD: For each component, say "and if this fails, here's what
  happens" proactively.

ANTI-PATTERN 5: Spending 10+ minutes on one phase.
  WHY BAD: You run out of time and miss other phases.
  INSTEAD: Set a mental timer. If the interviewer is engaged in a
  deep dive, that's fine -- let them lead. But if not, move on.

ANTI-PATTERN 6: "I would use a library for that."
  WHY BAD: Shows you can't explain the underlying algorithm.
  INSTEAD: "In production I'd use a library, but let me explain how
  it works under the hood" -- then explain Kahn's / Bully / etc.

ANTI-PATTERN 7: Ignoring the interviewer's hints.
  WHY BAD: They're trying to guide you toward what they want to discuss.
  INSTEAD: When they say "tell me more about X", IMMEDIATELY pivot to X.
  Their hints are gift-wrapped points -- take them.
```

---

*End of walkthrough. Total rehearsal time: ~45 minutes for first read,
~20 minutes for subsequent reviews. Target: can deliver any phase
from memory after 3 rehearsal passes.*
