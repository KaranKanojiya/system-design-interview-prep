# High-Level Design: Distributed Task Scheduler (Airflow / Celery / Temporal)

> Interview-optimized system design document.
> Target: 30-45 minute system design discussion.

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Scope](#2-scope)
3. [Assumptions](#3-assumptions)
4. [Functional Requirements](#4-functional-requirements)
5. [Non-Functional Requirements](#5-non-functional-requirements)
6. [API Design](#6-api-design)
7. [Data Model](#7-data-model)
8. [High-Level Architecture](#8-high-level-architecture)
9. [Component Deep Dive](#9-component-deep-dive)
10. [Task Lifecycle (State Machine)](#10-task-lifecycle-state-machine)
11. [DAG-Based Dependency Resolution](#11-dag-based-dependency-resolution)
12. [Leader Election (Bully Algorithm)](#12-leader-election-bully-algorithm)
13. [Worker Assignment Strategies](#13-worker-assignment-strategies)
14. [Retry and Backoff](#14-retry-and-backoff)
15. [Exactly-Once Execution Semantics](#15-exactly-once-execution-semantics)
16. [Cron and Delayed Scheduling](#16-cron-and-delayed-scheduling)
17. [Worker Failover and Task Reassignment](#17-worker-failover-and-task-reassignment)
18. [Task Groups and Workflow Orchestration](#18-task-groups-and-workflow-orchestration)
19. [Monitoring and Observability](#19-monitoring-and-observability)
20. [Scaling Strategies](#20-scaling-strategies)
21. [Database Choice](#21-database-choice)
22. [CAP Theorem](#22-cap-theorem)
23. [Cloud Services Mapping](#23-cloud-services-mapping)
24. [Failure Scenarios and Mitigation](#24-failure-scenarios-and-mitigation)
25. [Tradeoffs Summary](#25-tradeoffs-summary)
26. [Interview Talking Points](#26-interview-talking-points)

---

## 1. Problem Statement

Design a **Distributed Task Scheduler** (like Apache Airflow, Celery, or Temporal) that accepts task submissions from multiple clients, schedules them based on priority and dependencies, and dispatches them to a pool of heterogeneous workers for execution. The system must provide reliable task delivery with exactly-once semantics, handle worker failures with automatic task reassignment, support DAG-based dependency resolution for complex workflows, and scale horizontally to process millions of tasks per day.

**Why is it needed?**

- Every large-scale system needs background job processing: sending emails, generating reports, ETL pipelines, ML model training, payment processing, image/video transcoding.
- A naive approach (cron jobs on a single machine) fails at scale: no priority ordering, no dependency management, single point of failure, no retry logic, no observability.
- Distributed schedulers are a foundational infrastructure service. Airflow processes petabytes of data at Airbnb. Celery handles millions of tasks at Instagram. Temporal orchestrates microservice workflows at Netflix.
- The core technical challenges -- leader election, exactly-once semantics, DAG resolution, worker failover -- are the exact topics that Staff Engineer interviews test.

**Core Workflow:**

```
User submits a task: "Process payment for Order #12345"

(1)  Client --> API Gateway: POST /api/v1/tasks
       {name: "Process payment", priority: CRITICAL,
        payload: {order_id: "12345", amount: "99.99", idempotency_key: "idem-abc-123"}}
(2)  API Gateway: authenticate JWT, rate limit (100 tasks/sec per client)
(3)  API Gateway --> Scheduler Service: validate task fields, check idempotency key
(4)  Scheduler Service --> Task Repository: persist task (status=PENDING)
(5)  Scheduler Service --> Task Queue: enqueue task (status -> QUEUED)
       PriorityQueue sorts: CRITICAL before HIGH before MEDIUM before LOW
       Within same priority: FIFO by createdAt
(6)  Scheduler Engine tick():
       - Check CRON tasks: any fire times passed? Re-enqueue.
       - Check dependency-waiting tasks: all upstream complete? Move to queue.
       - Drain queue: get next batch of ready tasks.
(7)  Scheduler Service --> Worker Assignment Strategy:
       assignTask(task, availableWorkers)
       Round-Robin: counter.getAndIncrement() % workers.size()
       Least-Loaded: min(currentLoad / capacity)
(8)  Strategy returns Worker: "worker-alpha" (load=1/4, status=ACTIVE)
(9)  Scheduler Service: task.updateStatus(ASSIGNED)
(10) Execution Service --> Worker: dispatch task to worker-alpha
       Worker.incrementLoad() -> load = 2/4
(11) Worker: begins execution (status -> RUNNING)
       - Create TaskExecution record (attempt #1)
       - Execute task logic (call payment service)
(12a) SUCCESS: payment processed
       - TaskExecution.markCompleted(result)
       - Task.updateStatus(COMPLETED)
       - Worker.decrementLoad() -> load = 1/4
       - Notify: dependent tasks may now be ready
(12b) FAILURE: payment service timeout
       - TaskExecution.markFailed("Connection timeout")
       - RetryStrategy.shouldRetry(task, attempt=1, error)?
         YES (attempt 1 <= maxRetries 3)
       - Task.updateStatus(RETRYING)
       - Compute retry delay: 1000ms * 2^0 = 1000ms +/- 10% jitter
       - Re-enqueue task after delay
(13) Monitoring Service: update dashboard
       - Task counts by status
       - Worker utilization
       - Failure rate, retry rate, throughput
```

### Why This Is Asked in Interviews

This is a **tier-1 system design** interview question, rated **Hard**. It appears at Google, Amazon, Meta, Uber, Stripe, and every infrastructure team because it tests distributed systems fundamentals under correctness constraints:

| Skill Tested                     | What Interviewers Look For                                              |
|----------------------------------|-------------------------------------------------------------------------|
| **Priority Scheduling**          | PriorityQueue data structure, comparator design, starvation prevention  |
| **DAG Resolution**               | Topological sort (Kahn's algorithm), cycle detection (DFS coloring)     |
| **Leader Election**              | Bully/Raft algorithm, split-brain prevention, failover timing           |
| **Exactly-Once Semantics**       | Idempotency keys, fencing tokens, at-least-once + idempotent processing |
| **Worker Management**            | Heartbeat-based liveness, load balancing, failover and reassignment     |
| **Retry Strategies**             | Exponential backoff, jitter, dead-letter queues, circuit breakers       |
| **State Machine Design**         | Task lifecycle: PENDING -> QUEUED -> RUNNING -> COMPLETED/FAILED        |
| **Distributed Consensus**        | Why a single coordinator is needed, how to elect one safely             |
| **Scaling Strategy**             | Horizontal scaling of workers, queue partitioning, sharding             |
| **Observability**                | Metrics, alerting, dashboards for distributed systems                   |

> **Interview tip**: Start by clarifying scope -- is this a simple job queue (Celery) or a full workflow orchestrator (Airflow/Temporal)? Draw the task lifecycle state machine first. Then explain the priority queue, walk through a DAG resolution example, and discuss leader election. The "aha moment" is explaining why exactly-once is impossible in distributed systems and how you achieve effective exactly-once via idempotent processing.

---

## 2. Scope

### In Scope

| Feature                          | Description                                                              |
|----------------------------------|--------------------------------------------------------------------------|
| Task Submission                  | Submit tasks via API with priority, payload, retry policy, dependencies   |
| Priority Scheduling              | CRITICAL > HIGH > MEDIUM > LOW, FIFO within same priority               |
| DAG Dependencies                 | Tasks can depend on other tasks; Kahn's algorithm for topological order  |
| Worker Pool Management           | Register/deregister workers, heartbeat monitoring, load tracking         |
| Task Assignment                  | Round-robin, least-loaded, and tag-based affinity strategies             |
| Task Execution                   | Execute tasks on workers, record execution history (per attempt)         |
| Retry with Backoff               | Exponential backoff with jitter, configurable maxRetries                 |
| Cron Scheduling                  | 5-field cron expressions for recurring task schedules                    |
| Delayed Execution                | Tasks that should execute after a specified delay                        |
| Leader Election                  | Bully algorithm for scheduler coordinator election                      |
| Worker Failover                  | Detect dead workers, reassign their tasks to healthy workers             |
| Exactly-Once Semantics           | Idempotency keys, terminal state guards, fencing tokens                  |
| Task Groups                      | Parallel and sequential task groups for batch operations                 |
| Monitoring Dashboard             | Task counts, worker utilization, failure/retry rates, throughput         |
| Task Cancellation                | Cancel pending/queued tasks (not already completed/failed)               |

### Out of Scope

| Feature                          | Reason                                                                   |
|----------------------------------|--------------------------------------------------------------------------|
| Multi-region deployment          | Cross-region consensus adds significant latency -- separate deep dive    |
| Task versioning / migration      | Schema evolution is an extension on top of the core scheduler            |
| Fine-grained ACL / RBAC         | Authorization is a cross-cutting concern, not core scheduling            |
| Resource quota management        | CPU/memory limits per task are a container orchestration concern          |
| Visual DAG editor (UI)          | Frontend concern, outside the scope of backend system design             |
| Plugin system                    | Extensibility framework is an implementation detail                      |
| Billing / metering               | Usage tracking is a separate business domain                             |
| Log aggregation                  | Centralized logging (ELK/Datadog) is infrastructure, not scheduling      |

---

## 3. Assumptions

### Platform Scale

| Parameter                        | Value                                    |
|----------------------------------|------------------------------------------|
| Total registered clients         | 10,000                                   |
| Daily task submissions           | 10 million                               |
| Tasks per second (average)       | 10M / 86,400 = ~116 TPS                 |
| Tasks per second (peak)          | 10x avg = ~1,160 TPS                    |
| Tasks per second (burst)         | 50x avg = ~5,800 TPS                    |
| Concurrent workers               | 1,000 (across multiple clusters)         |
| Average task execution time      | 30 seconds                               |
| Maximum task execution time      | 1 hour                                   |
| DAG depth (max dependency chain) | 50 levels                                |
| DAG width (max parallel tasks)   | 500 tasks per level                      |
| Cron tasks                       | 100,000 scheduled recurring tasks        |
| Scheduler nodes                  | 3-5 (leader + followers)                 |
| Task retention period            | 30 days (execution history)              |

### Back-of-Envelope Calculations

```
Storage per day:
  Tasks: 10M tasks * 1 KB avg = 10 GB/day
  Executions: 10M executions * 500 bytes = 5 GB/day
  (With retries: ~12M executions * 500 bytes = 6 GB/day)
  Total: ~16 GB/day, ~480 GB/month

Task Queue Size (steady state):
  Average tasks in queue: TPS * avg_execution_time = 116 * 30 = 3,480 tasks
  Peak: 1,160 * 30 = 34,800 tasks
  Burst: 5,800 * 30 = 174,000 tasks
  At 1 KB per task: 174 MB max queue memory (fits in RAM easily)

Worker Capacity:
  1,000 workers * avg 4 concurrent tasks each = 4,000 concurrent executions
  At 30s avg execution: 4,000 / 30 = 133 tasks/sec throughput
  Peak demand: 1,160 TPS -> need 1,160 * 30 / 4 = 8,700 workers for no-queue
  With queuing: 1,000 workers + queue absorbs burst -> acceptable latency

Network Bandwidth:
  Task dispatch: 1,160 TPS * 2 KB (task + metadata) = 2.3 MB/sec
  Heartbeats: 1,000 workers * 1 heartbeat/5sec * 200 bytes = 40 KB/sec
  Monitoring: 1,000 metrics/sec * 500 bytes = 500 KB/sec
  Total: < 5 MB/sec (negligible)
```

### Latency Targets

| Operation                        | Target                                   |
|----------------------------------|------------------------------------------|
| Task submission (API to ack)     | p99 < 50ms                               |
| Task queuing (ack to queued)     | p99 < 10ms                               |
| Task dispatch (queued to worker) | p99 < 100ms (depends on tick interval)   |
| Task status query                | p99 < 20ms                               |
| Worker heartbeat processing      | p99 < 5ms                                |
| Leader election (failover)       | p99 < 5 seconds                          |
| Worker failover (detection)      | p99 < 30 seconds (heartbeat timeout)     |
| Monitoring dashboard refresh     | p99 < 500ms                              |

---

## 4. Functional Requirements

### FR-1: Submit Task

```
POST /api/v1/tasks

User can submit:
  - One-time task: execute once, immediately or after delay
  - Recurring task: cron expression for periodic execution
  - Dependent task: specify upstream task IDs that must complete first

Required fields: name, priority
Optional fields: description, payload, cronExpression, delayMillis,
                 maxRetries, timeoutMillis, dependsOn[], groupId

Response: task_id, status=PENDING, timestamp
```

### FR-2: Submit Task with Dependencies

```
POST /api/v1/tasks/with-deps

Submit a task that depends on other tasks completing first.
  - DAG validation: cycle detection before accepting
  - Status: task stays PENDING until all dependencies resolve to COMPLETED
  - If any dependency FAILS, dependent task can optionally fail or wait

Required fields: name, priority, dependsOn (list of task IDs)
Response: task_id, status=PENDING, dependencies acknowledged
```

### FR-3: Cancel Task

```
DELETE /api/v1/tasks/{task_id}

  - Cancel a PENDING, QUEUED, or ASSIGNED task
  - Cannot cancel RUNNING tasks (use separate force-cancel with timeout)
  - Cannot cancel COMPLETED, FAILED, or CANCELLED tasks
  - Cancellation cascades to dependent tasks (optional)
```

### FR-4: Get Task Status

```
GET /api/v1/tasks/{task_id}/status

Returns:
  task_id, name, priority, status, created_at, updated_at,
  assigned_worker (if applicable), attempt_count, last_error
```

### FR-5: Register Worker

```
POST /api/v1/workers

Register a new worker node in the pool.
  - hostname, port, capacity (max concurrent tasks)
  - tags for affinity routing (e.g., "gpu", "high-memory")
  - Status starts as ACTIVE
```

### FR-6: Worker Heartbeat

```
PUT /api/v1/workers/{worker_id}/heartbeat

  - Workers send heartbeats every 5 seconds
  - Missing heartbeat for > 30 seconds triggers failover
  - Heartbeat includes current load, status, resource utilization
```

### FR-7: Trigger Scheduling Cycle

```
POST /api/v1/scheduler/dispatch

  - Trigger one scheduling round (useful for testing/manual override)
  - In production: scheduler runs tick() on a fixed interval (1-5 seconds)
```

### FR-8: Get Monitoring Dashboard

```
GET /api/v1/monitoring/dashboard

Returns:
  task_counts_by_status: {COMPLETED: 8000, FAILED: 100, RUNNING: 50, QUEUED: 200}
  worker_utilization: [{worker_id, load, capacity, utilization_pct}]
  execution_metrics: {avg_time_ms, failure_rate, retry_rate, throughput}
```

### FR-9: Task Group Operations

```
POST /api/v1/task-groups
  Create a task group (parallel or sequential).
  Add tasks to the group.

GET /api/v1/task-groups/{group_id}
  Get group status and progress.
```

---

## 5. Non-Functional Requirements

| Requirement          | Target                        | Rationale                                           |
|----------------------|-------------------------------|-----------------------------------------------------|
| Task Durability      | Zero data loss                | Every submitted task must be persisted before ack    |
| Exactly-Once         | No duplicate execution        | Double-charging a payment is a correctness violation |
| Availability         | 99.99% (52 min downtime/year) | Infrastructure service; downstream systems depend on it |
| Throughput           | 10M tasks/day, 5K TPS peak   | Handle burst traffic during batch job submissions    |
| Scheduling Latency   | < 100ms from queue to worker  | Tasks should not wait unnecessarily in the queue     |
| Failover Time        | < 30s for worker, < 5s leader | Minimize task stalling during failures               |
| Retry Correctness    | Backoff with jitter           | Prevent thundering herd on downstream recovery       |
| Horizontal Scale     | Add workers without restart   | Worker pool scales elastically with demand           |
| Observability        | Real-time metrics + alerting  | Ops team must detect anomalies within 1 minute       |
| Data Retention       | 30 days execution history     | Debugging and audit trail for failed tasks           |

---

## 6. API Design

### 6.1 REST APIs

#### Submit Task

```
POST /api/v1/tasks
Authorization: Bearer <jwt_token>
Idempotency-Key: <uuid>

Request:
{
    "name": "Process payment for order #12345",
    "description": "Charge customer credit card and update order status",
    "task_type": "ONE_TIME",
    "priority": "CRITICAL",
    "payload": {
        "order_id": "12345",
        "amount": "99.99",
        "currency": "USD",
        "idempotency_key": "pay-12345-v1"
    },
    "max_retries": 3,
    "timeout_millis": 30000,
    "delay_millis": 0
}

Response (201 Created):
{
    "task_id": "task-a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "name": "Process payment for order #12345",
    "status": "PENDING",
    "priority": "CRITICAL",
    "created_at": "2026-05-09T10:30:00.000Z",
    "message": "Task submitted successfully"
}

Error (400 Bad Request):
{
    "error": "INVALID_TASK",
    "message": "Task name must not be null or empty"
}

Error (409 Conflict):
{
    "error": "DUPLICATE_IDEMPOTENCY_KEY",
    "message": "Task with idempotency key 'pay-12345-v1' already exists",
    "existing_task_id": "task-xxxxxxxx"
}
```

#### Submit Task with Dependencies

```
POST /api/v1/tasks/with-deps
Authorization: Bearer <jwt_token>

Request:
{
    "name": "Load transformed data to warehouse",
    "priority": "HIGH",
    "depends_on": [
        "task-extract-001",
        "task-transform-001",
        "task-validate-001"
    ],
    "payload": {
        "target_table": "analytics.daily_metrics",
        "partition_date": "2026-05-09"
    }
}

Response (201 Created):
{
    "task_id": "task-load-001",
    "status": "PENDING",
    "dependencies": {
        "total": 3,
        "completed": 1,
        "pending": 2
    },
    "message": "Task submitted with 3 dependencies"
}

Error (400 Bad Request):
{
    "error": "DEPENDENCY_CYCLE_DETECTED",
    "message": "Adding this dependency would create a cycle: A -> B -> C -> A"
}
```

#### Cancel Task

```
DELETE /api/v1/tasks/{task_id}
Authorization: Bearer <jwt_token>

Response (200 OK):
{
    "task_id": "task-a1b2c3d4",
    "status": "CANCELLED",
    "message": "Task cancelled successfully"
}

Error (409 Conflict):
{
    "error": "TASK_ALREADY_COMPLETED",
    "message": "Cannot cancel task task-a1b2c3d4: status is COMPLETED"
}
```

#### Get Task Status

```
GET /api/v1/tasks/{task_id}/status
Authorization: Bearer <jwt_token>

Response (200 OK):
{
    "task_id": "task-a1b2c3d4",
    "name": "Process payment for order #12345",
    "status": "RUNNING",
    "priority": "CRITICAL",
    "assigned_worker": "worker-alpha",
    "attempt_number": 1,
    "created_at": "2026-05-09T10:30:00.000Z",
    "updated_at": "2026-05-09T10:30:01.234Z",
    "execution_start": "2026-05-09T10:30:01.000Z"
}
```

#### Register Worker

```
POST /api/v1/workers
Authorization: Bearer <service_token>

Request:
{
    "hostname": "worker-alpha.cluster.local",
    "port": 8080,
    "capacity": 4,
    "tags": ["gpu", "high-memory"]
}

Response (201 Created):
{
    "worker_id": "worker-001",
    "hostname": "worker-alpha.cluster.local",
    "status": "ACTIVE",
    "capacity": 4,
    "current_load": 0,
    "registered_at": "2026-05-09T10:00:00.000Z"
}
```

#### Worker Heartbeat

```
PUT /api/v1/workers/{worker_id}/heartbeat
Authorization: Bearer <service_token>

Request:
{
    "current_load": 2,
    "cpu_usage_pct": 45.2,
    "memory_usage_pct": 62.8
}

Response (200 OK):
{
    "worker_id": "worker-001",
    "status": "ACTIVE",
    "heartbeat_recorded_at": "2026-05-09T10:30:05.000Z"
}
```

#### Get Monitoring Dashboard

```
GET /api/v1/monitoring/dashboard
Authorization: Bearer <jwt_token>

Response (200 OK):
{
    "task_status_counts": {
        "PENDING": 150,
        "QUEUED": 200,
        "ASSIGNED": 50,
        "RUNNING": 300,
        "COMPLETED": 8000,
        "FAILED": 45,
        "RETRYING": 20,
        "CANCELLED": 10,
        "TIMED_OUT": 5
    },
    "worker_utilization": [
        {"worker_id": "worker-001", "hostname": "alpha", "load": 3, "capacity": 4, "utilization": 0.75},
        {"worker_id": "worker-002", "hostname": "beta", "load": 1, "capacity": 3, "utilization": 0.33},
        {"worker_id": "worker-003", "hostname": "gamma", "load": 4, "capacity": 5, "utilization": 0.80}
    ],
    "execution_metrics": {
        "avg_execution_time_ms": 12500,
        "failure_rate": 0.005,
        "retry_rate": 0.023,
        "throughput_completed": 8000,
        "p50_latency_ms": 8000,
        "p99_latency_ms": 45000
    },
    "queue_depth": 200,
    "generated_at": "2026-05-09T10:30:00.000Z"
}
```

---

## 7. Data Model

### Entity Relationship

```
+-----------+       +---------------+       +--------+
|   Task    |1----*| TaskExecution  |*----1| Worker |
+-----------+       +---------------+       +--------+
     |                                          |
     |*                                         |
     |                                          |*
+----------+                             +--------------+
|TaskGroup |                             |SchedulerNode |
+----------+                             +--------------+
     |
     |*
+-----------+
|CronSchedule|
+-----------+
```

### Task

```
+------------------+------------------+--------------------------------------+
| Column           | Type             | Description                          |
+------------------+------------------+--------------------------------------+
| id               | UUID (PK)        | Unique task identifier               |
| name             | VARCHAR(255)     | Human-readable task name             |
| description      | TEXT             | Detailed description                 |
| task_type        | ENUM             | ONE_TIME, RECURRING, CRON, DELAYED   |
| priority         | ENUM             | LOW(0), MEDIUM(1), HIGH(2), CRITICAL(3) |
| status           | ENUM             | PENDING, QUEUED, ASSIGNED, RUNNING,  |
|                  |                  | COMPLETED, FAILED, RETRYING,         |
|                  |                  | CANCELLED, TIMED_OUT                 |
| payload          | JSONB            | Task-specific data (key-value pairs) |
| cron_expression  | VARCHAR(100)     | 5-field cron expression (nullable)   |
| delay_millis     | BIGINT           | Delay before execution (0 = immediate)|
| max_retries      | INT              | Maximum retry attempts (default 3)   |
| timeout_millis   | BIGINT           | Execution timeout (default 60000)    |
| group_id         | UUID (FK)        | References TaskGroup (nullable)      |
| created_at       | TIMESTAMP        | Task creation time                   |
| updated_at       | TIMESTAMP        | Last status update time              |
| scheduled_at     | TIMESTAMP        | Scheduled execution time (nullable)  |
+------------------+------------------+--------------------------------------+

Indexes:
  - PRIMARY KEY (id)
  - INDEX idx_task_status (status)
  - INDEX idx_task_priority_created (priority DESC, created_at ASC)
  - INDEX idx_task_group (group_id)
  - INDEX idx_task_type (task_type)
  - UNIQUE INDEX idx_idempotency_key (payload->>'idempotency_key') WHERE payload->>'idempotency_key' IS NOT NULL
```

### TaskExecution

```
+------------------+------------------+--------------------------------------+
| Column           | Type             | Description                          |
+------------------+------------------+--------------------------------------+
| id               | UUID (PK)        | Unique execution identifier          |
| task_id          | UUID (FK)        | References Task                      |
| worker_id        | UUID (FK)        | References Worker                    |
| attempt_number   | INT              | 1-based attempt counter              |
| status           | ENUM             | PENDING, RUNNING, COMPLETED, FAILED  |
| start_time       | TIMESTAMP        | Execution start time                 |
| end_time         | TIMESTAMP        | Execution end time (nullable)        |
| result_output    | TEXT             | Success/failure output message       |
| error_message    | TEXT             | Error details on failure (nullable)  |
| duration_ms      | BIGINT           | Wall-clock execution duration        |
+------------------+------------------+--------------------------------------+

Indexes:
  - PRIMARY KEY (id)
  - INDEX idx_exec_task (task_id)
  - INDEX idx_exec_worker (worker_id)
  - INDEX idx_exec_status (status)
  - UNIQUE INDEX idx_exec_task_attempt (task_id, attempt_number)
```

### Worker

```
+------------------+------------------+--------------------------------------+
| Column           | Type             | Description                          |
+------------------+------------------+--------------------------------------+
| id               | UUID (PK)        | Unique worker identifier             |
| hostname         | VARCHAR(255)     | Worker hostname / DNS name           |
| port             | INT              | Worker listening port                |
| capacity         | INT              | Maximum concurrent task slots        |
| current_load     | INT              | Currently active tasks (0..capacity) |
| status           | ENUM             | ACTIVE, BUSY, DEAD, OFFLINE          |
| last_heartbeat   | TIMESTAMP        | Last heartbeat received              |
| registered_at    | TIMESTAMP        | Worker registration time             |
| tags             | TEXT[]           | Affinity tags (e.g., "gpu", "high-memory") |
+------------------+------------------+--------------------------------------+

Indexes:
  - PRIMARY KEY (id)
  - INDEX idx_worker_status (status)
  - INDEX idx_worker_heartbeat (last_heartbeat)
```

### SchedulerNode

```
+------------------+------------------+--------------------------------------+
| Column           | Type             | Description                          |
+------------------+------------------+--------------------------------------+
| node_id          | VARCHAR(50) (PK) | Unique scheduler node identifier     |
| hostname         | VARCHAR(255)     | Scheduler node hostname              |
| priority         | INT              | Bully algorithm priority (higher wins)|
| is_leader        | BOOLEAN          | Whether this node is the current leader |
| last_heartbeat   | TIMESTAMP        | Last heartbeat from this node        |
| started_at       | TIMESTAMP        | Node startup time                    |
+------------------+------------------+--------------------------------------+

Indexes:
  - PRIMARY KEY (node_id)
  - INDEX idx_scheduler_leader (is_leader) WHERE is_leader = true
```

### TaskDependency

```
+------------------+------------------+--------------------------------------+
| Column           | Type             | Description                          |
+------------------+------------------+--------------------------------------+
| task_id          | UUID (FK)        | The dependent task                   |
| depends_on       | UUID (FK)        | The upstream task that must complete  |
+------------------+------------------+--------------------------------------+

Indexes:
  - PRIMARY KEY (task_id, depends_on)
  - INDEX idx_dep_upstream (depends_on)
```

### CronSchedule

```
+------------------+------------------+--------------------------------------+
| Column           | Type             | Description                          |
+------------------+------------------+--------------------------------------+
| task_id          | UUID (PK, FK)    | References the CRON task             |
| expression       | VARCHAR(100)     | Raw cron expression string           |
| minute           | VARCHAR(10)      | Minute field (* or 0-59)            |
| hour             | VARCHAR(10)      | Hour field (* or 0-23)              |
| day_of_month     | VARCHAR(10)      | Day of month (* or 1-31)            |
| month            | VARCHAR(10)      | Month (* or 1-12)                   |
| day_of_week      | VARCHAR(10)      | Day of week (* or 0-7, 0/7=Sunday)  |
| last_fired       | TIMESTAMP        | Last time this cron task fired       |
| next_fire_time   | TIMESTAMP        | Pre-computed next fire time          |
+------------------+------------------+--------------------------------------+
```

### TaskGroup

```
+------------------+------------------+--------------------------------------+
| Column           | Type             | Description                          |
+------------------+------------------+--------------------------------------+
| id               | UUID (PK)        | Unique group identifier              |
| name             | VARCHAR(255)     | Group name                           |
| parallel         | BOOLEAN          | true = parallel execution, false = sequential |
| created_at       | TIMESTAMP        | Group creation time                  |
+------------------+------------------+--------------------------------------+
```

---

## 8. High-Level Architecture

```
                              ┌─────────────────────────────────────────┐
                              │              CLIENTS                    │
                              │   (API clients, cron triggers, CLI)     │
                              └─────────────────┬───────────────────────┘
                                                |
                                                v
                              ┌─────────────────────────────────────────┐
                              │            API GATEWAY                  │
                              │   Auth, Rate Limiting, Load Balancing   │
                              └─────────────────┬───────────────────────┘
                                                |
                    ┌───────────────────────────┼───────────────────────────┐
                    |                           |                           |
                    v                           v                           v
          ┌─────────────────┐       ┌─────────────────┐       ┌─────────────────┐
          │  Scheduler       │       │  Scheduler       │       │  Scheduler       │
          │  Node 1          │       │  Node 2          │       │  Node 3          │
          │  (FOLLOWER)      │       │  (LEADER)        │       │  (FOLLOWER)      │
          │  priority=10     │       │  priority=30     │       │  priority=20     │
          └─────────────────┘       └────────┬──────────┘       └─────────────────┘
                                             |
                                    Leader Election
                                    (Bully Algorithm)
                                             |
                    ┌────────────────────────┼────────────────────────┐
                    |                        |                        |
                    v                        v                        v
          ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
          │  Task Service    │    │  Scheduler       │    │  Worker Service  │
          │  (lifecycle)     │    │  Engine          │    │  (pool mgmt)    │
          │  - create        │    │  (coordinator)   │    │  - register     │
          │  - cancel        │    │  - priority queue│    │  - heartbeat    │
          │  - status query  │    │  - dependency    │    │  - liveness     │
          └─────────────────┘    │    resolver      │    └─────────────────┘
                    |            │  - cron parser   │              |
                    v            └────────┬─────────┘              v
          ┌─────────────────┐            |              ┌─────────────────┐
          │  Task            │            |              │  Worker          │
          │  Repository      │    ┌──────┴──────┐       │  Repository      │
          │  (PostgreSQL)    │    |             |       │  (PostgreSQL)    │
          └─────────────────┘    v             v       └─────────────────┘
                         ┌───────────┐  ┌───────────┐
                         │Assignment │  │Scheduling │
                         │Strategy   │  │Strategy   │
                         │(RR/LL/CH) │  │(Immed/    │
                         └─────┬─────┘  │Cron/Delay)│
                               |        └───────────┘
                               v
          ┌────────────────────────────────────────────────────────┐
          │                    WORKER POOL                         │
          │                                                        │
          │  ┌──────────┐  ┌──────────┐  ┌──────────┐            │
          │  │ Worker 1  │  │ Worker 2  │  │ Worker 3  │   ...    │
          │  │ alpha     │  │ beta      │  │ gamma     │           │
          │  │ cap=4     │  │ cap=3     │  │ cap=5     │           │
          │  │ load=2    │  │ load=1    │  │ load=3    │           │
          │  │ [gpu,mem] │  │ [general] │  │ [cpu]     │           │
          │  └──────────┘  └──────────┘  └──────────┘            │
          │                                                        │
          │  Heartbeats every 5s ──────────────────────────────>   │
          │  Task results  <────────────────────────────────────   │
          └────────────────────────────────────────────────────────┘
                    |                        |
                    v                        v
          ┌─────────────────┐    ┌─────────────────┐
          │  Execution       │    │  Failover        │
          │  Service         │    │  Service          │
          │  - execute task  │    │  - detect dead    │
          │  - handle result │    │  - reassign tasks │
          │  - retry logic   │    │  - mark workers   │
          └─────────────────┘    └─────────────────┘
                    |
                    v
          ┌─────────────────┐    ┌─────────────────┐
          │  Execution       │    │  Monitoring       │
          │  Repository      │    │  Service          │
          │  (PostgreSQL)    │    │  - task counts    │
          └─────────────────┘    │  - utilization    │
                                 │  - failure rate   │
                                 │  - dashboard      │
                                 └─────────────────┘
```

### Request Flow Summary

```
1. Client submits task via API Gateway
2. API Gateway authenticates, rate limits, routes to any Scheduler Node
3. Scheduler Node forwards to Leader (or handles directly if it is the leader)
4. Leader's Scheduler Service:
   a. TaskService.createTask() -> validate + persist to TaskRepository
   b. SchedulerEngine.submitTask() -> enqueue in PriorityQueue (status=QUEUED)
5. On next tick() (every 1-5 seconds):
   a. Check CRON tasks: fire any due recurring tasks
   b. Check dependency-waiting tasks: move ready ones to queue
   c. Drain queue: get batch of tasks sorted by priority
6. For each ready task:
   a. WorkerService.getAvailableWorkers() -> filter ACTIVE, load < capacity
   b. AssignmentStrategy.assignTask(task, workers) -> pick worker
   c. Task status -> ASSIGNED, Worker.incrementLoad()
7. ExecutionService.executeTask(task, worker):
   a. Create TaskExecution record (attempt #N)
   b. Dispatch to worker, status -> RUNNING
   c. On success: COMPLETED, decrementLoad, notify dependents
   d. On failure: check RetryStrategy, RETRYING or FAILED
8. FailoverService (background):
   a. Every 10s: scan workers for stale heartbeats
   b. Dead workers: mark DEAD, reassign RUNNING tasks
9. MonitoringService: aggregate metrics for dashboard
```

---

## 9. Component Deep Dive

### 9.1 Scheduler Engine

The **SchedulerEngine** is the central coordinator -- the "brain" of the distributed task scheduler. It manages three concerns:

1. **Priority Queue**: Tasks enter via `submitTask()` and are sorted by priority (CRITICAL > HIGH > MEDIUM > LOW) with FIFO tie-breaking. The `tick()` method drains the queue into a dispatch batch.

2. **Dependency Resolution**: Tasks submitted with dependencies via `submitTaskWithDependencies()` are parked in a `waitingOnDeps` map. On each `tick()`, the engine checks if all upstream dependencies are in the completed set. When they are, the task moves to the priority queue.

3. **CRON Scheduling**: CRON tasks are registered with their `CronSchedule`. On each `tick()`, the engine checks if the current time matches the cron expression. If it does and the last fire time is before the current window, the task is re-enqueued.

```
SchedulerEngine.tick() {
    // Step 1: Check CRON tasks
    for each (taskId, cronSchedule) in cronTasks:
        nextFire = cronParser.getNextFireTime(schedule, lastFired)
        if nextFire <= now:
            cronLastFired[taskId] = now
            // signal readiness for service layer to re-create

    // Step 2: Check dependency-waiting tasks
    for each (taskId, task) in waitingOnDeps:
        if dependencyResolver.getReadyTasks(completedSet).contains(taskId):
            waitingOnDeps.remove(taskId)
            task.status = QUEUED
            taskQueue.enqueue(task)

    // Step 3: Drain queue into dispatch batch
    readyToDispatch = []
    while !taskQueue.isEmpty():
        readyToDispatch.add(taskQueue.dequeue())
    return readyToDispatch
}
```

### 9.2 Task Queue (Priority Queue)

The TaskQueue is a `PriorityQueue<Task>` with a custom `Comparator`:

```java
Comparator.comparingInt((Task t) -> t.getPriority().getValue())
    .reversed()                    // CRITICAL(3) before LOW(0)
    .thenComparing(Task::getCreatedAt)  // FIFO within same priority
```

**Why PriorityQueue?**
- O(log n) enqueue and dequeue
- Constant-factor fast (array-backed binary heap)
- Perfect for single-threaded scheduler loop (no thread-safety needed)

**Production alternatives:**
- **Redis Sorted Set**: `ZADD queue score member` with score = `(priority * 10^18) + createdAt_epoch_nanos`. `ZPOPMIN` to dequeue. Distributed, persistent, O(log n).
- **Kafka Priority Topics**: Separate topics per priority (critical-tasks, high-tasks, etc.). Consumer reads from highest-priority topic first. Durable, scalable, but coarser priority granularity.

### 9.3 Scheduler Service (Facade)

The **SchedulerService** is the Facade pattern implementation -- a single entry point that orchestrates:

| Internal Component       | Responsibility                                    |
|--------------------------|--------------------------------------------------|
| TaskService              | Task lifecycle (create, cancel, status query)     |
| WorkerService            | Worker pool (register, heartbeat, available list) |
| ExecutionService         | Execute tasks, record results, handle retries     |
| SchedulerEngine          | Priority queue + dependency resolution + CRON     |
| TaskAssignmentStrategy   | Worker selection algorithm                        |
| SchedulingStrategy       | When to schedule (immediate, cron, delayed)       |

The main dispatch loop:

```
SchedulerService.scheduleAndDispatch() {
    1. readyTasks = engine.tick()           // get tasks from queue + deps + cron
    2. schedulable = filter by schedulingStrategy.shouldScheduleNow()
    3. workers = workerService.getAvailableWorkers()
    4. for each task in schedulable:
         worker = assignmentStrategy.assignTask(task, workers)
         task.status = ASSIGNED
         executionService.executeTask(task, worker)
}
```

### 9.4 Execution Service

The **ExecutionService** handles the actual task execution lifecycle:

1. **Start**: Create `TaskExecution` record, set status RUNNING, increment worker load
2. **Success**: Mark execution COMPLETED, update task status, decrement worker load
3. **Failure**: Mark execution FAILED, check RetryStrategy:
   - If `shouldRetry(task, attempt, error)` returns true: status -> RETRYING, compute delay, re-enqueue
   - If false: status -> FAILED permanently

The retry decision is delegated to the `RetryStrategy` (Strategy pattern), allowing different strategies for different task types.

### 9.5 Failover Service

The **FailoverService** runs as a background process:

1. **Detection**: Scan all workers. If `Duration.between(lastHeartbeat, now) > timeout`, mark worker DEAD.
2. **Reassignment**: Find all RUNNING TaskExecution records assigned to dead workers. Reset task status to QUEUED. Use the assignment strategy to assign to a healthy worker.

```
FailoverService.performFailover(timeout) {
    deadWorkers = detectDeadWorkers(timeout)  // heartbeat staleness
    for each deadWorker:
        runningExecs = execRepo.findByWorkerId(deadWorker.id)
                       .filter(status == RUNNING)
        for each exec:
            task.status = QUEUED              // reset for reassignment
            newWorker = assignmentStrategy.assignTask(task, availableWorkers)
            task.status = ASSIGNED
            reassignedCount++
}
```

---

## 10. Task Lifecycle (State Machine)

```
                        submitTask()
                            |
                            v
                       +---------+
                       | PENDING |  (task created, not yet queued)
                       +---------+
                            |
                    enqueue to priority queue
                            |
                            v
                       +---------+
              +------->| QUEUED  |  (in priority queue, waiting for dispatch)
              |        +---------+
              |             |
              |     assignTask(task, worker)
              |             |
              |             v
              |       +----------+
              |       | ASSIGNED |  (worker selected, not yet running)
              |       +----------+
              |             |
              |      worker starts execution
              |             |
              |             v
              |       +---------+
              |       | RUNNING |  (executing on worker)
              |       +---------+
              |        /    |    \
              |       /     |     \
              |      v      v      v
              | +------+ +------+ +----------+
              | |COMPLT| |FAILED| | TIMED_OUT|
              | +------+ +------+ +----------+
              |           |
              |    shouldRetry()?
              |    YES:   |
              |           v
              |     +----------+
              +-----| RETRYING |  (waiting for retry delay, then re-queued)
                    +----------+

    At any non-terminal state:
                    cancelTask()
                        |
                        v
                  +-----------+
                  | CANCELLED |
                  +-----------+

    Terminal states: COMPLETED, FAILED, CANCELLED, TIMED_OUT
    Active states:   RUNNING, ASSIGNED, RETRYING
    isTerminal():    COMPLETED || FAILED || CANCELLED || TIMED_OUT
    isActive():      RUNNING || ASSIGNED || RETRYING
```

### State Transition Rules

| From       | To         | Trigger                                            |
|------------|------------|-----------------------------------------------------|
| PENDING    | QUEUED     | Task enqueued in priority queue                     |
| QUEUED     | ASSIGNED   | Worker selected by assignment strategy              |
| ASSIGNED   | RUNNING    | Worker begins execution                             |
| RUNNING    | COMPLETED  | Task execution succeeds                             |
| RUNNING    | FAILED     | Task fails and maxRetries exhausted                 |
| RUNNING    | RETRYING   | Task fails but retries remaining                    |
| RUNNING    | TIMED_OUT  | Execution exceeds timeoutMillis                     |
| RETRYING   | QUEUED     | Retry delay expires, task re-enters queue           |
| Any*       | CANCELLED  | cancelTask() called (* except terminal states)      |

### Why State Machine Matters

In a distributed system, multiple components may try to update a task's status concurrently (e.g., worker reports success while failover service marks it for reassignment). The state machine enforces **valid transitions only** -- you cannot move from COMPLETED back to RUNNING, and you cannot cancel an already-completed task. In production, implement with optimistic locking (version column) or CAS (compare-and-swap) operations.

---

## 11. DAG-Based Dependency Resolution

### The Problem

Complex workflows have task dependencies:

```
ETL Pipeline:

  Extract ──→ Transform ──→ Load
     |                       ^
     └────→ Validate ────────┘

  Rules:
    - Transform cannot start until Extract completes
    - Validate cannot start until Extract completes
    - Load cannot start until BOTH Transform AND Validate complete
    - Transform and Validate can run in parallel
```

### Data Structure

The dependency graph is stored as an adjacency list:

```
Map<String, Set<String>> dependencies:
  "transform" -> {"extract"}
  "validate"  -> {"extract"}
  "load"      -> {"transform", "validate"}
  "extract"   -> {}  (no dependencies, root node)
```

### Kahn's Algorithm (Topological Sort)

Kahn's algorithm computes a valid execution order for the DAG:

```
Step 1: Compute in-degree for every node
  extract=0, transform=1, validate=1, load=2

Step 2: Seed queue with zero-in-degree nodes
  Queue: [extract]

Step 3: BFS peel
  Dequeue "extract" -> result: [extract]
    Decrement in-degree of dependents: transform=0, validate=0
    Enqueue transform, validate
  Dequeue "transform" -> result: [extract, transform]
    Decrement: load=1
  Dequeue "validate" -> result: [extract, transform, validate]
    Decrement: load=0
    Enqueue load
  Dequeue "load" -> result: [extract, transform, validate, load]

Step 4: If result.size() < totalNodes, there's a cycle (not all nodes processed)

Final order: extract -> transform -> validate -> load
(Transform and validate can run in parallel since they're at the same level)
```

**Complexity**: O(V + E) where V = number of tasks, E = number of dependency edges.

### Cycle Detection (DFS Three-Coloring)

Before accepting a dependency, we check for cycles:

```
Colors: WHITE (unvisited), GRAY (in current DFS path), BLACK (fully explored)

DFS from each WHITE node:
  Visit node: color = GRAY
  For each dependency:
    If GRAY -> GRAY: BACK EDGE found = CYCLE!
    If WHITE: recurse
  After exploring all deps: color = BLACK

Example cycle: A -> B -> C -> A
  Visit A (GRAY), visit B (GRAY), visit C (GRAY)
  C depends on A, but A is GRAY = back edge = CYCLE DETECTED
  Throw DependencyCycleException
```

### getReadyTasks() -- The Runtime Check

On each scheduler tick, we need to know which tasks can be dispatched:

```java
Set<String> getReadyTasks(Set<String> completedTaskIds) {
    Set<String> ready = new HashSet<>();
    for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
        String taskId = entry.getKey();
        if (completedTaskIds.contains(taskId)) continue;  // already done
        if (completedTaskIds.containsAll(entry.getValue())) {
            ready.add(taskId);  // all dependencies satisfied
        }
    }
    return ready;
}
```

After "extract" completes: `completedSet = {"extract"}`
- transform: deps = {"extract"}, completedSet contains all -> READY
- validate: deps = {"extract"}, completedSet contains all -> READY
- load: deps = {"transform", "validate"}, missing both -> NOT READY

After "extract" + "transform" + "validate" complete:
- load: deps = {"transform", "validate"}, completedSet contains all -> READY

---

## 12. Leader Election (Bully Algorithm)

### Why Leader Election?

In a distributed scheduler cluster with N nodes, we need exactly **one coordinator** to:
- Own the task queue and dispatch decisions
- Prevent duplicate task assignments (two nodes assigning the same task to different workers)
- Coordinate cron evaluations (only one node should fire cron tasks)
- Manage worker failover (one node detects dead workers and reassigns)

Without a leader, every node would need to coordinate via distributed consensus on every operation -- expensive and slow.

### Bully Algorithm

The Bully algorithm selects the node with the **highest priority** as leader:

```
Setup: 3 scheduler nodes
  scheduler-1 (priority=10)
  scheduler-2 (priority=20)
  scheduler-3 (priority=30)

Election Process:

Step 1: Each node challenges all higher-priority nodes
  scheduler-1: "Are scheduler-2 and scheduler-3 alive?"
  scheduler-2: "Is scheduler-3 alive?"
  scheduler-3: "No one to challenge -- I'm the highest priority."

Step 2: If a higher-priority node responds, defer to it
  scheduler-1: gets responses from scheduler-2 and scheduler-3 -> defer
  scheduler-2: gets response from scheduler-3 -> defer
  scheduler-3: no challengers

Step 3: The unchallenged node declares itself leader
  scheduler-3 broadcasts: "I am the COORDINATOR"
  All nodes update: scheduler-3 is the leader

Message Flow:
  ┌─────────┐    ELECTION     ┌─────────┐    ELECTION     ┌─────────┐
  │ Node-1  │ ──────────────> │ Node-2  │ ──────────────> │ Node-3  │
  │ pri=10  │    ALIVE(ACK)   │ pri=20  │    ALIVE(ACK)   │ pri=30  │
  │         │ <────────────── │         │ <────────────── │         │
  └─────────┘                 └─────────┘                 └─────────┘
                                                               |
                              COORDINATOR (broadcast)          |
  ┌─────────┐ <──────────────────────────────────────────────────
  │ Node-1  │                 ┌─────────┐ <────────────────────
  └─────────┘                 │ Node-2  │
                              └─────────┘
```

### Leader Failure and Re-Election

```
Normal state: scheduler-3 is leader

Event: scheduler-3 crashes (heartbeat stops)

Detection: scheduler-1 and scheduler-2 detect stale heartbeat
  Last heartbeat from scheduler-3: 45 seconds ago
  Timeout threshold: 30 seconds
  -> scheduler-3 is DEAD

Re-election triggered:
  scheduler-1: challenges scheduler-2 -> scheduler-2 responds (ALIVE)
  scheduler-2: no higher-priority alive nodes -> declares COORDINATOR
  -> scheduler-2 is the new leader

Result: scheduler-2 takes over all coordinator duties
  - Resume dispatch from persisted task queue
  - Continue cron evaluations
  - Run failover detection
  - Downtime: < 5 seconds (heartbeat timeout + election)
```

### Bully Algorithm Analysis

| Property           | Value                                              |
|--------------------|----------------------------------------------------|
| **Correctness**    | Guaranteed: highest-priority alive node always wins |
| **Messages**       | O(n^2) worst case (every node challenges every other) |
| **Time**           | O(1) election rounds (single round suffices)       |
| **Partition safety**| NOT partition-safe (split-brain risk)             |
| **Production use** | Interviews only; use Raft/ZAB/etcd in production  |

### Production Alternative: Raft Consensus

```
For production, replace Bully with Raft (implemented by etcd, Consul, ZooKeeper):
  - Partition-tolerant: majority quorum prevents split-brain
  - Log replication: leader replicates state to followers
  - Leader lease: time-bounded leadership prevents stale leaders
  - Complexity: more messages, but safer under network partitions

Example with etcd:
  - 3 scheduler nodes register as candidates in etcd
  - etcd runs Raft internally, elects one as leader
  - Leader holds a lease (TTL=10s), refreshed on heartbeat
  - If leader dies, lease expires, etcd elects new leader
  - Clients discover leader via etcd key watch
```

---

## 13. Worker Assignment Strategies

### 13.1 Round-Robin Assignment

**How it works**: Cycle through available workers in order using an atomic counter.

```java
int index = Math.abs(counter.getAndIncrement() % availableWorkers.size());
Worker selected = availableWorkers.get(index);
```

**Characteristics**:

| Property           | Value                                              |
|--------------------|----------------------------------------------------|
| Distribution       | Even across all workers (over time)                |
| Complexity         | O(1) per assignment                                |
| Load-awareness     | None (ignores current load and capacity)           |
| Best for           | Homogeneous clusters (all workers identical)       |
| Weakness           | Overloads small workers when capacity varies        |

**Example**: 3 workers, 6 tasks:

```
Worker-1 (cap=4): Task 1, Task 4  -> load = 2/4
Worker-2 (cap=3): Task 2, Task 5  -> load = 2/3
Worker-3 (cap=5): Task 3, Task 6  -> load = 2/5

Even distribution (2 each), but Worker-2 at 67% utilization
while Worker-3 at only 40%.
```

### 13.2 Least-Loaded Assignment

**How it works**: Select the worker with the lowest current load, breaking ties by age (prefer older, more stable workers).

```java
Worker selected = availableWorkers.stream()
    .filter(Worker::isAvailable)
    .min(Comparator.comparingInt(Worker::getCurrentLoad)
        .thenComparing(Worker::getRegisteredAt))
    .orElseThrow();
```

**Characteristics**:

| Property           | Value                                              |
|--------------------|----------------------------------------------------|
| Distribution       | Proportional to remaining capacity                 |
| Complexity         | O(n) per assignment (scan all workers)             |
| Load-awareness     | Full (respects current load and capacity)          |
| Best for           | Heterogeneous clusters (different capacities)      |
| Weakness           | Higher overhead per assignment (linear scan)        |

**Example**: 3 workers with pre-existing load, 4 tasks:

```
Initial:  Worker-1 (0/4), Worker-2 (2/3), Worker-3 (0/5)

Task 1 -> Worker-3 (0/5, least loaded, bigger capacity)
Task 2 -> Worker-1 (0/4, tied with Worker-3 at load=0, but older)
Task 3 -> Worker-3 (1/5, load=1 vs Worker-1 load=1, Worker-3 older)
Task 4 -> Worker-1 (1/4, load=1 vs Worker-3 load=2)

Final: Worker-1 (2/4=50%), Worker-2 (2/3=67%), Worker-3 (2/5=40%)
More balanced utilization!
```

### 13.3 Consistent Hashing (Production Extension)

**How it works**: Hash the task's type or key onto a ring. Each worker occupies positions on the ring (virtual nodes). Task is assigned to the nearest worker clockwise.

```
Benefits:
  - Cache locality: same task type always goes to same worker
  - Minimal disruption on worker add/remove (only 1/N tasks reassigned)
  - Stateful tasks benefit from worker affinity

Tradeoff:
  - Doesn't respect current load (hot keys overload one worker)
  - Must combine with load-based rebalancing
```

### Strategy Pattern Summary

```
interface TaskAssignmentStrategy {
    Optional<Worker> assignTask(Task task, List<Worker> availableWorkers);
    String getStrategyName();
}

class RoundRobinAssignmentStrategy   implements TaskAssignmentStrategy { ... }
class LeastLoadedAssignmentStrategy  implements TaskAssignmentStrategy { ... }
// Future: ConsistentHashAssignmentStrategy, TagAffinityStrategy

// Runtime swap via AppConfig:
config.setAssignmentStrategy(new LeastLoadedAssignmentStrategy());
// All services automatically use the new strategy (dependents re-created)
```

---

## 14. Retry and Backoff

### The Problem

Tasks fail. Networks partition. Services go down. Databases hit capacity. Without retry logic, every transient failure becomes a permanent failure. But naive retry (retry immediately, forever) causes thundering herd and amplifies outages.

### Exponential Backoff with Jitter

**Formula:**

```
delay = min(initialDelay * multiplier^(attempt - 1), maxDelay) * jitterFactor

Where:
  initialDelay = 1000ms (1 second)
  multiplier   = 2.0 (double each attempt)
  maxDelay     = 30000ms (30 seconds cap)
  jitterFactor = 0.9 + random(0, 0.2)  (±10% randomness)
```

**Schedule for 5 attempts:**

```
Attempt 1: 1000ms * 2^0 = 1,000ms  * jitter -> ~900ms  - 1,100ms
Attempt 2: 1000ms * 2^1 = 2,000ms  * jitter -> ~1,800ms - 2,200ms
Attempt 3: 1000ms * 2^2 = 4,000ms  * jitter -> ~3,600ms - 4,400ms
Attempt 4: 1000ms * 2^3 = 8,000ms  * jitter -> ~7,200ms - 8,800ms
Attempt 5: 1000ms * 2^4 = 16,000ms * jitter -> ~14,400ms - 17,600ms

Total wait before giving up: ~30 seconds
```

### Why Jitter?

Without jitter, 1000 tasks that all fail at the same time will all retry at exactly 1s, then exactly 2s, then exactly 4s -- creating synchronized retry storms that spike load on the already-struggling downstream service.

```
Without jitter (thundering herd):
  t=0:    1000 tasks fail
  t=1s:   1000 tasks retry simultaneously -> downstream overloaded -> all fail
  t=3s:   1000 tasks retry simultaneously -> same problem
  -> Cascading failure loop

With ±10% jitter (spread retries):
  t=0:    1000 tasks fail
  t=0.9s: ~50 tasks retry
  t=1.0s: ~900 tasks retry (spread over 200ms window)
  t=1.1s: ~50 tasks retry
  -> Downstream handles gradual load increase
  -> Some succeed, reducing retry volume each round
```

### Retry Strategy Interface

```java
interface RetryStrategy {
    boolean shouldRetry(Task task, int attemptNumber, String errorMessage);
    long getRetryDelayMillis(int attemptNumber);
    String getStrategyName();
}

// Implementations:
// ExponentialBackoffRetryStrategy: delay doubles each attempt, capped at maxDelay
// FixedIntervalRetryStrategy: constant delay between attempts
// Future: LinearBackoffRetryStrategy, CustomPerErrorRetryStrategy
```

### Dead-Letter Queue (Production Extension)

After `maxRetries` exhausted, the task moves to FAILED. In production, push failed tasks to a **dead-letter queue** (DLQ):

```
Task fails permanently
  -> Push to DLQ topic (Kafka: "tasks.dead-letter")
  -> Alert ops team (PagerDuty, Slack)
  -> DLQ consumer: manual review + replay
  -> Admin can: retry, skip, or escalate
```

---

## 15. Exactly-Once Execution Semantics

### The Impossibility

True exactly-once delivery is **impossible** in distributed systems (Two Generals Problem, FLP impossibility). What we achieve is **effective exactly-once**: at-least-once delivery + idempotent processing.

### Three Layers of Protection

**Layer 1: Idempotency Key (Client-Side Deduplication)**

```
Client submits task with idempotency_key in payload:
  POST /api/v1/tasks
  {payload: {idempotency_key: "pay-order-12345-v1", amount: "99.99"}}

Server checks:
  SELECT id FROM tasks WHERE payload->>'idempotency_key' = 'pay-order-12345-v1'
  If exists: return existing task (409 Conflict or 200 OK with original)
  If not: create new task

This prevents duplicate task creation even if the client retries the submission
(e.g., network timeout, client-side retry, duplicate webhook).
```

**Layer 2: Terminal State Guard (Server-Side Deduplication)**

```java
// Before executing a task, check its current state
Optional<Task> existing = taskService.getTask(task.getId());
if (existing.isPresent() && existing.get().getStatus().isTerminal()) {
    // Task already COMPLETED, FAILED, or CANCELLED
    // DO NOT re-execute
    return;
}

// Also check: unique constraint on (task_id, attempt_number)
// Prevents two workers from executing the same attempt
```

**Layer 3: Fencing Token (Stale Worker Prevention)**

```
Scenario:
  1. Worker-A is assigned task T1 (fencing_token = 42)
  2. Worker-A becomes slow (GC pause, network delay)
  3. Failover service detects Worker-A as dead
  4. Worker-B is assigned task T1 (fencing_token = 43)
  5. Worker-B completes task T1, writes result with token 43
  6. Worker-A recovers, tries to write result with token 42

Resolution:
  - Storage accepts writes only if token >= current_token
  - Worker-A's write (token 42) is REJECTED (42 < 43)
  - Worker-B's result stands
  - No double-write, no duplicate execution effect

Implementation:
  UPDATE task_executions
  SET result = ?, status = 'COMPLETED'
  WHERE task_id = ? AND fencing_token >= ?
  -- Returns 0 rows updated if token is stale
```

### Summary Table

| Layer               | Protects Against               | Mechanism                        |
|---------------------|-------------------------------|----------------------------------|
| Idempotency Key     | Duplicate task submissions     | Unique index on key              |
| Terminal State Guard | Re-execution of done tasks    | Status check before execution    |
| Fencing Token       | Stale worker writes           | Monotonic token comparison       |
| DB Constraint       | Concurrent same-attempt runs  | UNIQUE(task_id, attempt_number)  |

---

## 16. Cron and Delayed Scheduling

### Cron Scheduling

The **CronParser** handles 5-field cron expressions:

```
Format: minute hour dayOfMonth month dayOfWeek

Examples:
  "0 * * * *"     -> Every hour at minute 0
  "*/5 * * * *"   -> Every 5 minutes (production extension: step values)
  "30 2 * * 1"    -> Every Monday at 2:30 AM
  "0 0 1 * *"     -> First day of every month at midnight
  "0 9 * * 1-5"   -> Weekdays at 9:00 AM (production extension: ranges)
```

**How it works:**

```
1. CronParser.parse("0 * * * *") -> CronSchedule object
   minute=0, hour=*, dayOfMonth=*, month=*, dayOfWeek=*

2. On each tick():
   CronParser.getNextFireTime(schedule, lastFired) -> Optional<Instant>
   - Scans minute-by-minute from lastFired
   - Returns the next time that matches ALL 5 fields
   - Capped at 24-hour lookahead

3. If nextFireTime <= now:
   - Re-enqueue the task
   - Update lastFired = now
```

**Production alternative**: Use Quartz Scheduler (Java) or database-backed cron with `SELECT ... WHERE next_fire_time <= NOW() FOR UPDATE SKIP LOCKED` for distributed cron evaluation.

### Delayed Scheduling

Tasks with `delayMillis > 0` are scheduled for future execution:

```
Task submitted at 10:00:00 with delayMillis = 5000
  scheduledAt = 10:00:05

SchedulingStrategy.shouldScheduleNow(task, now):
  - ImmediateSchedulingStrategy: always returns true
  - DelayedSchedulingStrategy: returns now >= task.scheduledAt
  - CronSchedulingStrategy: returns true if cron expression matches now

On tick() at 10:00:03: shouldScheduleNow = false, skip
On tick() at 10:00:05: shouldScheduleNow = true, dispatch
```

**Production implementation**: Use Redis sorted set with `scheduledAt` as score. `ZRANGEBYSCORE queue -inf now` returns all due tasks. Or use Kafka with delayed message support (Kafka Streams state store with punctuator).

---

## 17. Worker Failover and Task Reassignment

### Failure Detection

Workers send heartbeats every 5 seconds. The FailoverService runs a detection scan every 10 seconds:

```
For each worker in workerPool:
  timeSinceLastHeartbeat = now - worker.lastHeartbeat
  if timeSinceLastHeartbeat > heartbeatTimeout (30s):
    worker.status = DEAD
    deadWorkers.add(worker)
```

### Reassignment Process

```
1. Find dead workers with stale heartbeats
2. For each dead worker:
   a. Find all TaskExecution records with status=RUNNING on this worker
   b. For each running execution:
      - Reset task status to QUEUED (available for reassignment)
      - Use assignment strategy to pick a new healthy worker
      - Create new TaskExecution (attempt + 1) on the new worker
      - Task status -> ASSIGNED
3. Report: "Reassigned N tasks from M dead workers"
```

### Timeline Example

```
t=0:00   Worker-B receives Task T5, starts execution (status=RUNNING)
t=0:05   Worker-B sends heartbeat (healthy)
t=0:10   Worker-B sends heartbeat (healthy)
t=0:12   Worker-B crashes (process killed, hardware failure)
t=0:15   Worker-B misses heartbeat (no alarm yet)
t=0:20   Worker-B misses heartbeat (no alarm yet)
t=0:25   Worker-B misses heartbeat (no alarm yet)
t=0:30   Failover scan: Worker-B last heartbeat = t=0:10, age = 20s
         20s < 30s threshold -> still alive (false negative)
t=0:40   Failover scan: Worker-B last heartbeat = t=0:10, age = 30s
         30s >= 30s threshold -> DEAD
         Find T5 running on Worker-B -> reset to QUEUED
         Assign T5 to Worker-C (least loaded)
         Task T5 status: RUNNING -> QUEUED -> ASSIGNED -> RUNNING (on Worker-C)
t=0:42   Worker-C starts executing T5 (attempt #2)
t=1:12   Worker-C completes T5 -> COMPLETED

Total disruption: ~30 seconds (heartbeat timeout)
```

### Gossip Protocol (Production Enhancement)

For faster failure detection, replace heartbeat-to-coordinator with gossip:

```
Gossip protocol (SWIM / Serf):
  - Each worker pings K random peers every T seconds
  - If peer doesn't respond, ask M other peers to try
  - If peer still unresponsive after M probes, mark as SUSPECT
  - After SUSPECT timeout, mark as DEAD
  - Detection time: O(log N) propagation, ~5-10 seconds

Advantage over heartbeat-to-coordinator:
  - No single point of detection (coordinator crash = no detection)
  - O(1) messages per node per interval (scalable)
  - Faster detection (multiple observers detect simultaneously)
```

---

## 18. Task Groups and Workflow Orchestration

### Task Groups

A **TaskGroup** is a logical collection of tasks that execute together:

```
Parallel Group: All tasks dispatch simultaneously
  TaskGroup("Image Processing", parallel=true)
    ├── Resize images       -> Worker-1
    ├── Apply watermarks    -> Worker-2
    └── Compress images     -> Worker-3
  All 3 tasks run concurrently. Group completes when ALL tasks complete.

Sequential Group: Tasks execute one after another
  TaskGroup("Data Pipeline", parallel=false)
    ├── Extract data        -> runs first
    ├── Transform data      -> runs after Extract completes
    └── Load data           -> runs after Transform completes
  Implicit dependency chain: task[N+1] depends on task[N].
```

### Workflow Orchestration with DAGs

For complex workflows, combine task groups with DAG dependencies:

```
ML Training Pipeline:

  ┌─── Data Prep Group (parallel) ───┐
  │  Fetch training data              │
  │  Fetch validation data            │
  │  Fetch test data                  │
  └──────────────┬───────────────────┘
                 │
  ┌─── Feature Eng Group (sequential) ─┐
  │  Clean data                         │
  │  Generate features                  │
  │  Normalize features                 │
  └──────────────┬─────────────────────┘
                 │
  ┌─── Training Group (parallel) ─────┐
  │  Train model A (random forest)     │
  │  Train model B (gradient boost)    │
  │  Train model C (neural net)        │
  └──────────────┬────────────────────┘
                 │
  ┌─── Eval Group (sequential) ───────┐
  │  Evaluate all models               │
  │  Select best model                 │
  │  Deploy to staging                 │
  └───────────────────────────────────┘
```

---

## 19. Monitoring and Observability

### Key Metrics

| Metric                       | Description                                | Alert Threshold      |
|------------------------------|--------------------------------------------|----------------------|
| **Queue Depth**              | Number of tasks in QUEUED state            | > 10,000 for > 5min  |
| **Task Throughput**          | Tasks completed per minute                 | < 50% of baseline    |
| **Failure Rate**             | Failed tasks / total tasks (sliding window)| > 5%                 |
| **Retry Rate**               | Retried tasks / total tasks                | > 20%                |
| **Avg Execution Time**       | Mean wall-clock time per task (completed)  | > 2x baseline        |
| **P99 Execution Time**       | 99th percentile execution latency          | > 5x baseline        |
| **Worker Utilization**       | currentLoad / capacity per worker          | Any worker > 95%     |
| **Dead Worker Count**        | Number of workers in DEAD status           | > 0                  |
| **Scheduling Latency**       | Time from QUEUED to ASSIGNED               | > 1 second           |
| **Leader Election Count**    | Number of elections in last hour            | > 3 (flapping)       |

### Dashboard Layout

```
========================================
       SCHEDULER MONITORING DASHBOARD
========================================

  Task Status Breakdown:
    COMPLETED    : 8,234
    RUNNING      : 312
    QUEUED       : 156
    FAILED       : 45
    RETRYING     : 23
    CANCELLED    : 12
    TIMED_OUT    : 5

  Worker Utilization:
    worker-001 (alpha) : [||||||||..] 75.0%  (3/4)
    worker-002 (beta)  : [|||.......] 33.3%  (1/3)
    worker-003 (gamma) : [||||||||..] 80.0%  (4/5)

  Execution Metrics:
    Avg Execution Time : 12,500ms
    Failure Rate       : 0.5%
    Retry Rate         : 2.3%
    Throughput         : 8,234 completed tasks
    Queue Depth        : 156 tasks waiting

========================================
```

### Production Monitoring Stack

```
Metrics:   Prometheus + Grafana (or Datadog)
  - Expose /metrics endpoint with task_count{status}, worker_utilization, etc.
  - Grafana dashboards with time-series graphs

Logging:   Structured JSON logs -> ELK (Elasticsearch + Logstash + Kibana)
  - Every state transition logged: {taskId, from, to, timestamp, worker}
  - Correlation ID for tracing task through scheduler -> worker -> result

Tracing:   OpenTelemetry (Jaeger / Zipkin)
  - Span: task_submission -> scheduling -> dispatch -> execution -> completion
  - Trace through: API Gateway -> Scheduler -> Worker -> Downstream Service

Alerting:  PagerDuty / OpsGenie
  - P1: Dead workers, leader election failure, queue depth > threshold
  - P2: Elevated failure rate, degraded throughput, scheduling latency spike
```

---

## 20. Scaling Strategies

### Horizontal Worker Scaling

```
Problem: Peak traffic exceeds worker capacity
Solution: Auto-scale worker pool

Approach 1: Queue-depth-based auto-scaling
  if queueDepth > highWaterMark (1000 tasks):
    scale up workers by 20%
  if queueDepth < lowWaterMark (100 tasks) for 10 minutes:
    scale down workers by 10% (gradual to avoid thrashing)

Approach 2: Kubernetes HPA (Horizontal Pod Autoscaler)
  apiVersion: autoscaling/v2
  kind: HorizontalPodAutoscaler
  spec:
    scaleTargetRef:
      kind: Deployment
      name: task-worker
    minReplicas: 10
    maxReplicas: 100
    metrics:
    - type: External
      external:
        metric:
          name: scheduler_queue_depth
        target:
          type: AverageValue
          averageValue: 50  # 50 tasks per worker
```

### Queue Partitioning

```
Problem: Single priority queue becomes bottleneck at > 100K TPS
Solution: Partition by task type or priority

Option A: Priority-based partitioning
  critical-queue -> dedicated workers (guaranteed capacity)
  high-queue     -> shared workers
  medium-queue   -> shared workers
  low-queue      -> best-effort workers (preemptable)

Option B: Task-type-based partitioning (Kafka topics)
  payment-tasks  -> partition by merchant_id
  email-tasks    -> partition by recipient_hash
  etl-tasks      -> partition by pipeline_id

Option C: Tenant-based partitioning (multi-tenant)
  tenant-A-queue -> isolated worker pool (SLA guarantee)
  tenant-B-queue -> shared worker pool
```

### Scheduler Node Scaling

```
Problem: Single scheduler node is SPOF
Solution: 3-5 scheduler nodes with leader election

  - Leader handles all dispatch
  - Followers are hot standby (receive replicated state)
  - On leader failure: re-election in < 5 seconds
  - No horizontal scaling of schedulers needed (leader does all work)
  - If throughput exceeds single scheduler: partition queues, one scheduler per partition
```

### Database Scaling

```
Problem: Task table grows to billions of rows
Solution: Time-based partitioning + archival

  - Partition tasks table by created_at (monthly partitions)
  - Hot partition (current month): on SSD, fully indexed
  - Cold partitions (> 30 days): compressed, archived to S3
  - Active tasks (non-terminal) in a separate table for fast queries
  - Read replicas for monitoring/dashboard queries
```

---

## 21. Database Choice

### Primary Database: PostgreSQL

| Consideration      | Why PostgreSQL                                          |
|--------------------|---------------------------------------------------------|
| **Task State**     | ACID transactions for status transitions (no lost updates) |
| **Concurrency**    | MVCC handles concurrent status updates without locks    |
| **JSONB**          | Task payload stored as JSONB (flexible schema, indexed) |
| **Partitioning**   | Native table partitioning by date range                 |
| **Locking**        | `SELECT ... FOR UPDATE SKIP LOCKED` for distributed queue |
| **Constraints**    | UNIQUE(task_id, attempt_number) prevents duplicate executions |

### Cache Layer: Redis

| Use Case             | Redis Feature                                         |
|----------------------|-------------------------------------------------------|
| **Priority Queue**   | Sorted Set (ZADD/ZPOPMIN) for distributed queue      |
| **Worker Registry**  | Hash map with TTL for heartbeat-based liveness        |
| **Idempotency**      | SET key NX EX 86400 (deduplicate for 24 hours)       |
| **Rate Limiting**    | Sliding window counter (INCR + EXPIRE)               |
| **Cron Lock**        | RedLock for distributed cron evaluation               |
| **Task Caching**     | Cache hot task metadata (GET/SET with TTL)            |

### Message Queue: Kafka (Production)

| Use Case             | Kafka Configuration                                   |
|----------------------|-------------------------------------------------------|
| **Task Events**      | Topic: task-events, partitioned by task_id            |
| **Worker Events**    | Topic: worker-heartbeats, partitioned by worker_id    |
| **Dead Letters**     | Topic: tasks-dead-letter (permanently failed tasks)   |
| **Retry Delays**     | Topics: tasks-retry-1s, tasks-retry-5s, tasks-retry-30s |
| **Durability**       | acks=all, min.insync.replicas=2                       |
| **Ordering**         | Per-partition ordering guarantees per-task ordering    |

### Database Decision Matrix

| Data                | Storage         | Why                                         |
|---------------------|-----------------|---------------------------------------------|
| Task definitions    | PostgreSQL      | ACID, complex queries, joins                |
| Task executions     | PostgreSQL      | ACID, audit trail, partitioned by date      |
| Active task queue   | Redis Sorted Set| Low-latency dequeue, in-memory speed        |
| Worker registry     | Redis Hash      | Fast heartbeat updates, TTL-based expiry    |
| Task events         | Kafka           | Durable, ordered, replayable event log      |
| Execution logs      | Elasticsearch   | Full-text search, aggregation, dashboards   |
| Archived tasks      | S3 + Athena     | Cost-effective cold storage, ad-hoc queries |

---

## 22. CAP Theorem

### CP for Task State (Consistency + Partition Tolerance)

```
Task state (PENDING -> QUEUED -> RUNNING -> COMPLETED) must be consistent:
  - A task must NEVER be assigned to two workers simultaneously
  - A completed task must NEVER be re-executed
  - A failed task's retry count must be accurate

What happens during partition?
  - Scheduler cannot reach worker: task stays ASSIGNED (not dispatched)
  - Worker cannot reach scheduler: task completes locally, result buffered
  - Two scheduler nodes disagree on leader: STOP all dispatch (prefer correctness)
  - Wait for partition to heal, then reconcile

Why CP?
  - Double-execution of a payment task = double-charge customer
  - Lost task = missed SLA, broken pipeline
  - Incorrect retry count = infinite retry loop or premature failure
```

### AP for Monitoring (Availability + Partition Tolerance)

```
Monitoring metrics can be eventually consistent:
  - Dashboard shows "99.1% success" instead of "99.2%" for 30 seconds: OK
  - Worker utilization refreshes every 5 seconds: stale data acceptable
  - Queue depth metric lags by 1 tick: no operational impact

Why AP?
  - Monitoring should always be available (even during partitions)
  - Stale metrics are better than no metrics
  - Ops team needs visibility even when the system is degraded
```

### CP/AP Split Summary

| Component            | CP or AP | Rationale                                        |
|----------------------|----------|--------------------------------------------------|
| Task status          | **CP**   | Incorrect status = double execution or lost task |
| Task assignment      | **CP**   | Two workers executing same task = correctness bug|
| Worker registry      | **CP**   | Wrong worker list = dispatch to dead nodes       |
| Leader election      | **CP**   | Two leaders = split-brain, duplicate dispatch    |
| Monitoring metrics   | **AP**   | Stale dashboard acceptable, always available     |
| Execution logs       | **AP**   | Log delay OK, search availability more important |
| Cron evaluation      | **CP**   | Double-fire = duplicate task execution           |

---

## 23. Cloud Services Mapping

### AWS Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        AWS Architecture                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  API Gateway          → AWS API Gateway + Lambda authorizer      │
│  Scheduler Service    → ECS Fargate (3 tasks, leader election)   │
│  Worker Pool          → ECS Fargate auto-scaling group           │
│  Task Queue           → Amazon SQS (standard + FIFO queues)     │
│  Priority Queue       → ElastiCache Redis (sorted sets)          │
│  Task Database        → RDS Aurora PostgreSQL (multi-AZ)         │
│  Event Streaming      → Amazon MSK (Managed Kafka)               │
│  Worker Registry      → ElastiCache Redis (hash + TTL)           │
│  Dead Letter Queue    → SQS DLQ + SNS alerting                  │
│  Cron Scheduling      → EventBridge Scheduler                    │
│  Monitoring           → CloudWatch Metrics + Grafana             │
│  Logging              → CloudWatch Logs + OpenSearch             │
│  Tracing              → AWS X-Ray                                │
│  Alerting             → CloudWatch Alarms -> SNS -> PagerDuty   │
│  Task Archive         → S3 + Athena (cold storage)              │
│  Secrets              → AWS Secrets Manager                      │
│  Load Balancer        → ALB (Application Load Balancer)          │
│                                                                  │
│  Managed Alternative: AWS Step Functions                          │
│    - Serverless workflow orchestration                            │
│    - Built-in retry, error handling, state machine               │
│    - Visual workflow editor                                      │
│    - Pay-per-state-transition                                    │
│    - Limitation: 25,000 state transitions/sec per account        │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### GCP Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        GCP Architecture                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  API Gateway          → Cloud Endpoints / Apigee                 │
│  Scheduler Service    → GKE (Kubernetes) with leader election    │
│  Worker Pool          → GKE auto-scaling node pool               │
│  Task Queue           → Cloud Tasks (managed task queue)         │
│  Task Database        → Cloud SQL (PostgreSQL) or AlloyDB        │
│  Event Streaming      → Cloud Pub/Sub                            │
│  Worker Registry      → Memorystore Redis                        │
│  Cron Scheduling      → Cloud Scheduler                          │
│  Monitoring           → Cloud Monitoring + Grafana               │
│  Logging              → Cloud Logging                            │
│  Tracing              → Cloud Trace                              │
│  Alerting             → Cloud Monitoring Alerting                │
│                                                                  │
│  Managed Alternative: Cloud Workflows                            │
│    - Serverless orchestration with YAML/JSON DSL                 │
│    - Built-in connectors to GCP services                         │
│    - Pay-per-step execution                                      │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Azure Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Azure Architecture                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  API Gateway          → Azure API Management                     │
│  Scheduler Service    → AKS (Kubernetes) with leader election    │
│  Worker Pool          → AKS auto-scaling                         │
│  Task Queue           → Azure Service Bus (queues + topics)      │
│  Task Database        → Azure Database for PostgreSQL            │
│  Event Streaming      → Azure Event Hubs (Kafka-compatible)      │
│  Worker Registry      → Azure Cache for Redis                    │
│  Cron Scheduling      → Azure Logic Apps (timer trigger)         │
│  Monitoring           → Azure Monitor + Grafana                  │
│                                                                  │
│  Managed Alternative: Azure Durable Functions                    │
│    - Serverless workflow orchestration                            │
│    - Fan-out/fan-in pattern for parallel tasks                   │
│    - Durable timers for delayed execution                        │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 24. Failure Scenarios and Mitigation

### Scenario 1: Worker Crashes Mid-Execution

```
Event:    Worker-B crashes while executing Task T5 (attempt #1)
Impact:   T5 is stuck in RUNNING state, never completes
Detection: Heartbeat timeout (30 seconds)
Mitigation:
  1. FailoverService detects Worker-B is DEAD (heartbeat stale > 30s)
  2. Find T5 with status=RUNNING on Worker-B
  3. Reset T5 status to QUEUED
  4. Assign T5 to Worker-C (least loaded available worker)
  5. Create TaskExecution (attempt #2) on Worker-C
  6. Worker-C completes T5 successfully
Recovery time: ~30-45 seconds (heartbeat timeout + reassignment)
Prevention: Faster heartbeat interval (2s), lower timeout (10s) -- tradeoff with false positives
```

### Scenario 2: Scheduler Leader Crashes

```
Event:    Scheduler-3 (leader, priority=30) process exits
Impact:   No dispatch, no cron evaluation, no dependency resolution
Detection: Follower nodes detect stale heartbeat from leader
Mitigation:
  1. Scheduler-2 (priority=20) detects Scheduler-3 heartbeat > timeout
  2. Scheduler-2 initiates Bully election
  3. Scheduler-2 is highest-priority alive node -> becomes leader
  4. Scheduler-2 loads task queue from database
  5. Scheduler-2 resumes tick() loop
  6. Tasks that were QUEUED during downtime get dispatched
Recovery time: < 5 seconds (heartbeat check + election)
Prevention: Run 3+ scheduler nodes across AZs; use Raft for faster failover
```

### Scenario 3: Database Becomes Unavailable

```
Event:    PostgreSQL primary goes down
Impact:   Cannot persist new tasks, cannot update task status
Detection: Connection timeout on first failed query
Mitigation:
  1. In-memory queue continues to hold tasks (no data loss for queued tasks)
  2. New task submissions return 503 Service Unavailable
  3. Aurora automatic failover promotes read replica to primary (~30s)
  4. After failover: flush in-memory state to new primary
  5. Resume normal operations
Recovery time: 30 seconds (Aurora failover)
Prevention: Multi-AZ deployment, read replicas, circuit breaker on DB calls
```

### Scenario 4: Thundering Herd (Mass Task Failure)

```
Event:    Payment service goes down, 10,000 payment tasks fail simultaneously
Impact:   All 10,000 tasks retry at the same time, creating a load spike
Detection: Sudden spike in failure rate (monitoring alert)
Mitigation:
  1. Exponential backoff spreads retries over time (1s, 2s, 4s, 8s, 16s...)
  2. Jitter adds ±10% randomness to prevent synchronized retries
  3. Circuit breaker on payment service: after 50% failure rate, stop sending
  4. Queue up tasks, wait for circuit breaker to half-open
  5. Gradually resume with rate limiting
Recovery time: Automatic via backoff; full recovery when downstream recovers
Prevention: Circuit breaker pattern, bulkhead isolation, rate limiting on retry
```

### Scenario 5: Network Partition (Split Brain)

```
Event:    Network partition between AZ-1 (Scheduler-1, Worker-1) and
          AZ-2 (Scheduler-2, Scheduler-3, Worker-2, Worker-3)
Impact:   Both sides might elect a leader -> duplicate task dispatch
Detection: Scheduler-1 cannot reach Scheduler-2 and Scheduler-3
Mitigation (Bully):
  - Scheduler-1 cannot reach higher-priority nodes, declares itself leader
  - Scheduler-3 (in AZ-2) is also leader
  - SPLIT BRAIN: both dispatch tasks -> potential double execution
  - Resolution: NOT handled by Bully algorithm (this is its weakness)

Mitigation (Raft/etcd - production):
  - AZ-1 has 1 node, AZ-2 has 2 nodes
  - Raft quorum requires majority: 2 out of 3
  - AZ-1 cannot form quorum -> Scheduler-1 steps down
  - AZ-2 maintains quorum -> Scheduler-3 remains leader
  - No split brain. AZ-1 stops dispatch until partition heals.

Prevention: Use Raft/ZAB consensus; deploy odd number of nodes (3 or 5)
```

### Scenario 6: Poison Pill Task (Infinite Failure)

```
Event:    Task T99 has a bug that causes it to fail every time, regardless of retries
Impact:   T99 consumes retry slots, wastes worker capacity, blocks dependent tasks
Detection: Task has failed maxRetries times -> status = FAILED permanently
Mitigation:
  1. After maxRetries (3 by default): task moves to FAILED
  2. Push to dead-letter queue for manual investigation
  3. Alert ops team via monitoring (PagerDuty/Slack)
  4. Dependent tasks: configurable behavior
     a. FAIL_FAST: mark all dependents as FAILED immediately
     b. WAIT: dependents stay PENDING indefinitely
     c. SKIP: skip failed dependency, proceed with remaining
  5. Admin reviews DLQ, fixes bug, manually retries from DLQ
Prevention: Task validation before submission, canary execution, integration tests
```

### Scenario 7: Cyclic Dependency Deadlock

```
Event:    User accidentally submits: A depends on B, B depends on C, C depends on A
Impact:   All three tasks wait forever (deadlock)
Detection: DependencyResolver.hasCycle() detects cycle at submission time
Mitigation:
  1. Before accepting dependency: run DFS cycle detection
  2. DFS three-coloring: WHITE -> GRAY -> BLACK
  3. GRAY -> GRAY back edge detected: "A -> B -> C -> A"
  4. Throw DependencyCycleException immediately
  5. Return 400 Bad Request: "Adding this dependency would create a cycle"
  6. Task NOT submitted; client must fix dependency graph
Recovery time: Immediate (fail-fast at submission)
Prevention: Cycle detection on every addDependency() call; DAG validation in API
```

---

## 25. Tradeoffs Summary

| Decision                 | Chose                     | Alternative               | Rationale                                        |
|--------------------------|---------------------------|---------------------------|--------------------------------------------------|
| Queue implementation     | In-memory PriorityQueue   | Database polling          | O(log n) vs O(n), zero I/O overhead              |
| Dependency resolution    | Kahn's algorithm          | Naive polling             | O(V+E) vs O(n*m), handles large DAGs             |
| Leader election          | Bully algorithm           | Raft consensus            | Simple for interview; Raft for production         |
| Worker assignment        | Strategy pattern (both)   | Single algorithm          | Flexibility for different cluster types           |
| Retry strategy           | Exponential + jitter      | Fixed interval            | Prevents thundering herd, degrades gracefully     |
| Exactly-once             | Idempotent processing     | True exactly-once         | Theoretically impossible; practical alternative   |
| Scheduler coordination   | Leader-follower (active-passive) | All-active with distributed lock | Simpler, single writer avoids conflicts |
| Task persistence         | PostgreSQL (CP)           | DynamoDB (AP)             | ACID for task state correctness                   |
| Queue persistence        | Redis sorted set          | Kafka topic               | Lower latency for priority dequeue                |
| Monitoring               | Pull-based (Prometheus)   | Push-based (StatsD)       | Pull is simpler, Prometheus is industry standard  |
| Worker detection         | Heartbeat-based           | Gossip protocol           | Simpler; gossip for > 1000 workers                |
| Task timeout             | Configurable per task     | Global timeout            | Different tasks have different SLAs               |

---

## 26. Interview Talking Points

### What to Highlight First (Minutes 0-5)

1. **Task lifecycle state machine**: PENDING -> QUEUED -> ASSIGNED -> RUNNING -> COMPLETED/FAILED. Draw it immediately.
2. **Priority queue**: PriorityQueue with Comparator. CRITICAL > HIGH > MEDIUM > LOW. FIFO within same priority.
3. **Why a coordinator**: Single writer for task queue prevents duplicate assignment. Leader election ensures exactly one coordinator.

### Deep Dive Topics (Minutes 5-25)

4. **DAG dependencies**: Kahn's algorithm for topological sort. DFS three-coloring for cycle detection. `getReadyTasks()` for runtime dispatch decisions.
5. **Worker assignment strategies**: Round-robin (simple, homogeneous) vs Least-loaded (capacity-aware, heterogeneous). Strategy pattern for runtime swapping.
6. **Retry with backoff**: `min(1s * 2^(attempt-1), 30s) +/- 10% jitter`. Prevents thundering herd. Dead-letter queue for permanent failures.
7. **Exactly-once semantics**: Idempotency key + terminal state guard + fencing token. At-least-once + idempotent = effective exactly-once.
8. **Leader election**: Bully algorithm for interview simplicity. Mention Raft for production. Explain split-brain risk.

### Scaling Discussion (Minutes 25-35)

9. **Horizontal worker scaling**: Auto-scale based on queue depth. Kubernetes HPA with custom metric.
10. **Queue partitioning**: By priority level (dedicated CRITICAL queue) or by task type (Kafka topic per type).
11. **Database scaling**: Time-partition task table, separate active tasks from history, archive to S3.
12. **CAP positioning**: CP for task state (no lost/duplicate tasks), AP for monitoring (stale metrics OK).

### Staff-Level Signals (Minutes 35-45)

13. **Failure scenarios**: Walk through worker crash, leader crash, DB failure, thundering herd, split-brain.
14. **Operational concerns**: Monitoring, alerting, dead-letter queues, runbook for common failures.
15. **Production alternatives**: "For interview I showed Bully; in production I'd use Raft via etcd. For queue I'd use Redis sorted sets. For exactly-once I'd add transactional outbox."
16. **Comparison with existing systems**: "Airflow uses DAGs but has a single scheduler bottleneck. Temporal solves this with workflow-level addressing and deterministic replay. Celery uses Redis/RabbitMQ but lacks DAG dependencies."

### Key Sentences to Memorize

- "The priority queue is the central data structure. CRITICAL tasks preempt LOW tasks. Within same priority, FIFO by creation time."
- "Kahn's algorithm gives us topological order in O(V+E). DFS three-coloring catches cycles at submission time -- fail fast, not at execution time."
- "Exactly-once is impossible in distributed systems. We achieve effective exactly-once via at-least-once delivery plus idempotent processing."
- "Exponential backoff with jitter prevents thundering herd. Without jitter, 1000 failed tasks all retry at exactly 2 seconds."
- "The Bully algorithm is simple but not partition-safe. In production, use Raft consensus via etcd or Consul."
- "Worker failover: heartbeat timeout -> detect dead worker -> reassign RUNNING tasks to healthy workers. Total disruption: ~30 seconds."
