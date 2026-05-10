# Distributed Task Scheduler (Airflow/Celery-like) -- Cloud Service Mapping

## Component-to-Service Mapping

| Our Component | AWS | Azure | GCP | Notes |
|---------------|-----|-------|-----|-------|
| **API Gateway** | API Gateway + WAF | API Management + Front Door | Cloud Endpoints + Apigee | Task submission, DAG upload, status polling, auth, rate limiting |
| **Task Queue** | SQS (standard + FIFO) + Lambda | Service Bus (queues + topics) | Cloud Tasks + Pub/Sub | Priority queuing, delay scheduling, dead-letter queues |
| **Scheduler Service** | Step Functions + EventBridge Scheduler | Logic Apps + Durable Functions | Cloud Scheduler + Workflows | DAG orchestration, cron triggers, dependency resolution |
| **Worker Pool** | ECS Fargate (auto-scaling task groups) | Container Instances + AKS | Cloud Run + GKE Autopilot | Stateless task executors, horizontal auto-scale by queue depth |
| **Task Storage** | DynamoDB (task state) + S3 (payloads) | Cosmos DB + Blob Storage | Firestore + Cloud Storage | Task metadata, execution history, DAG definitions, large payloads |
| **Leader Election** | DynamoDB (conditional writes + TTL) | Blob Lease (60s lease) | Spanner (TrueTime locks) | Single leader per scheduler cluster, fencing token for split-brain |
| **Monitoring** | CloudWatch + X-Ray | Azure Monitor + App Insights | Cloud Monitoring + Trace | Task latency, queue depth, worker utilization, failure rates |
| **Event Bus** | EventBridge (task lifecycle events) | Event Grid (topic subscriptions) | Eventarc (Cloud Run triggers) | Task state changes, DAG completion, alerting triggers |
| **Cron Scheduling** | EventBridge Scheduler (rate/cron) | Timer Triggers (Azure Functions) | Cloud Scheduler (cron jobs) | Periodic task creation, recurring DAG triggers |
| **Cache** | ElastiCache Redis (cluster mode) | Azure Cache for Redis | Memorystore (Redis) | Task dedup cache, worker heartbeat TTLs, DAG adjacency cache |

---

## Scheduler Architecture on AWS (Numbered)

```
User submits a data pipeline DAG with 5 tasks:
  Extract -> Transform -> Validate -> Load -> Notify
  (Extract has no deps, Transform depends on Extract, etc.)

    1. CLIENT SUBMITS DAG (API / CLI / SDK):
       POST /api/v1/dags
       {
         dagId: "etl-pipeline-001",
         tasks: [
           { taskId: "extract",   type: "HTTP",   priority: "HIGH",   deps: [] },
           { taskId: "transform", type: "COMPUTE", priority: "HIGH",   deps: ["extract"] },
           { taskId: "validate",  type: "COMPUTE", priority: "MEDIUM", deps: ["transform"] },
           { taskId: "load",      type: "DB",      priority: "HIGH",   deps: ["validate"] },
           { taskId: "notify",    type: "HTTP",    priority: "LOW",    deps: ["load"] }
         ],
         schedule: "0 2 * * *",    // daily at 2 AM UTC
         retryPolicy: { maxRetries: 3, backoff: "EXPONENTIAL", initialDelayMs: 1000 }
       }
       |
       Client receives dagRunId immediately (async processing).
       WebSocket / polling for real-time status updates.
    |
    v
    2. API GATEWAY + WAF (entry point):
       WAF rules:
         - Rate limit: 100 DAG submissions/min per user
         - Payload size: max 1 MB (DAG definition)
         - IP reputation: block known bad actors
       |
       API Gateway:
         - JWT token validation (Cognito)
         - Request throttling (global: 10K requests/sec)
         - Route to ECS Scheduler Service
    |
    v
    3. SCHEDULER SERVICE (ECS Fargate, leader-elected):
       Only the LEADER node processes scheduling decisions.
       Other nodes are hot standby (ready to take over).
       |
       Step 3a: Validate DAG structure:
         - Parse task list and dependency edges
         - Build adjacency list: extract->transform->validate->load->notify
         - Topological sort: detect cycles (DependencyResolver)
         - If cycle detected -> reject with HTTP 400 "Cycle in DAG"
       |
       Step 3b: Persist DAG definition to DynamoDB:
         Table: dags
         PK: dagId = "etl-pipeline-001"
         Attributes: tasks, edges, schedule, retryPolicy, createdAt, owner
       |
       Step 3c: Register cron schedule with EventBridge Scheduler:
         Schedule name: "dag-etl-pipeline-001"
         Cron: "cron(0 2 * * ? *)"    // daily at 2 AM UTC
         Target: SQS queue "dag-trigger-queue"
         Payload: { dagId: "etl-pipeline-001", triggerType: "CRON" }
       |
       Step 3d: For immediate execution (or when cron fires):
         - Create dagRun record in DynamoDB:
           PK: dagRunId = "run-etl-001-20260509-020000"
           Status: RUNNING
         - Resolve root tasks (no dependencies): ["extract"]
         - Enqueue root tasks to SQS priority queue
    |
    v
    4. TASK QUEUE (SQS with priority routing):
       Three SQS queues by priority:
         - task-queue-high    (Extract, Transform, Load)
         - task-queue-medium  (Validate)
         - task-queue-low     (Notify)
       |
       Message format:
         {
           taskId: "extract",
           dagRunId: "run-etl-001-20260509-020000",
           priority: "HIGH",
           attemptNumber: 1,
           maxRetries: 3,
           payload: { url: "https://data-source.example.com/api/export" },
           idempotencyKey: "extract-run-etl-001-20260509-020000-attempt-1"
         }
       |
       Visibility timeout: 5 minutes (if worker doesn't ACK, message reappears).
       Dead-letter queue: after 3 SQS receives with no delete -> DLQ.
       Message deduplication: SQS FIFO with idempotencyKey (5-min dedup window).
    |
    v
    5. WORKER POOL (ECS Fargate, auto-scaling):
       Workers poll their assigned priority queue.
       Auto-scaling policy: scale out when ApproximateNumberOfMessagesVisible > 10.
       |
       Worker "worker-A" picks up the "extract" task:
       |
       Step 5a: Claim task (exactly-once):
         DynamoDB conditional write:
           UpdateItem taskId="extract", dagRunId="run-etl-001-..."
           SET status = "RUNNING", workerId = "worker-A", startedAt = now()
           ConditionExpression: "status = :queued"
         |
         If condition fails (another worker already claimed it):
           -> Delete SQS message, skip (dedup at worker level)
       |
       Step 5b: Execute task:
         HTTP call to https://data-source.example.com/api/export
         Response: 200 OK, payload: { rows: 50000, sizeBytes: 12000000 }
         Duration: 45 seconds
       |
       Step 5c: Record execution result:
         DynamoDB:
           UpdateItem taskId="extract"
           SET status = "COMPLETED", completedAt = now(),
               result = { rows: 50000, sizeBytes: 12000000 },
               duration = 45000
       |
       Step 5d: Delete SQS message (ACK):
         DeleteMessage(receiptHandle)
       |
       Step 5e: Notify scheduler (EventBridge):
         PutEvents:
           source: "task-scheduler"
           detailType: "TaskCompleted"
           detail: { taskId: "extract", dagRunId: "run-etl-001-...", status: "COMPLETED" }
    |
    v
    6. DEPENDENCY RESOLUTION (Scheduler reacts to TaskCompleted):
       EventBridge rule triggers Lambda function:
       |
       Step 6a: Load DAG edges from DynamoDB:
         extract -> [transform]
         transform -> [validate]
         validate -> [load]
         load -> [notify]
       |
       Step 6b: Check which downstream tasks are now unblocked:
         "extract" completed -> check dependents: ["transform"]
         "transform" depends on: ["extract"] -> all deps completed? YES
         -> "transform" is now READY
       |
       Step 6c: Enqueue "transform" to SQS task-queue-high:
         {
           taskId: "transform",
           dagRunId: "run-etl-001-20260509-020000",
           priority: "HIGH",
           attemptNumber: 1,
           payload: { inputRows: 50000, transformType: "NORMALIZE" }
         }
       |
       Step 6d: Update dagRun progress:
         DynamoDB: dagRunId -> completedTasks = 1/5, currentTask = "transform"
    |
    v
    7. TASK EXECUTION CONTINUES (Transform -> Validate -> Load -> Notify):
       Each task follows the same pattern as step 5:
         Worker claims -> executes -> records result -> notifies scheduler
         Scheduler resolves deps -> enqueues next ready tasks
       |
       Execution timeline:
         00:00 - Extract starts on worker-A
         00:45 - Extract completes, Transform enqueued
         00:46 - Transform starts on worker-B (different worker)
         02:16 - Transform completes (90 sec), Validate enqueued
         02:17 - Validate starts on worker-C
         02:47 - Validate completes (30 sec), Load enqueued
         02:48 - Load starts on worker-A (recycled)
         04:48 - Load completes (120 sec), Notify enqueued
         04:49 - Notify starts on worker-D
         04:54 - Notify completes (5 sec), DAG RUN complete
       |
       Total: ~5 minutes for 5-task pipeline
    |
    v
    8. DAG RUN COMPLETION:
       All 5 tasks completed. Scheduler updates dagRun:
         DynamoDB: dagRunId -> status = "COMPLETED", completedAt = now()
       |
       EventBridge event:
         detailType: "DagRunCompleted"
         detail: { dagId: "etl-pipeline-001", dagRunId: "run-etl-001-...",
                   status: "COMPLETED", duration: 294000, tasksCompleted: 5 }
       |
       Downstream actions triggered by EventBridge:
         - SNS notification to team Slack channel
         - CloudWatch custom metric: etl-pipeline-duration = 294s
         - S3: archive dagRun logs to s3://scheduler-logs/2026/05/09/
    |
    v
    9. FAILURE HANDLING (if Transform had failed):
       Worker detects failure:
         HTTP 500 from transformation service, or task timeout exceeded.
       |
       Step 9a: Record failure:
         DynamoDB: taskId="transform", status="FAILED",
                   error="HTTP 500: Internal Server Error",
                   attemptNumber=1
       |
       Step 9b: Retry decision (ExponentialBackoffRetryStrategy):
         shouldRetry(task, attempt=1, error)? YES (maxRetries=3)
         getRetryDelayMillis(attempt=1): 1000ms * 2^0 = 1000ms (+ jitter)
       |
       Step 9c: Schedule retry:
         SQS SendMessage with DelaySeconds = 1 (rounded up from 1000ms):
         {
           taskId: "transform",
           attemptNumber: 2,
           idempotencyKey: "transform-run-etl-001-...-attempt-2"
         }
       |
       Step 9d: If all retries exhausted (attempt 3 fails):
         DynamoDB: taskId="transform", status="FAILED" (terminal)
         Downstream tasks (validate, load, notify) -> status="CANCELLED"
         DagRun -> status="FAILED"
         EventBridge: "DagRunFailed" event -> SNS alert to on-call
    |
    v
    10. LEADER ELECTION (DynamoDB-based Bully algorithm):
        Three scheduler nodes: scheduler-1, scheduler-2, scheduler-3
        |
        DynamoDB table: scheduler-leader
          PK: "leader-lock"
          Attributes: nodeId, priority, leaseTTL, lastHeartbeat
        |
        Leader claim (conditional write):
          PutItem: { PK: "leader-lock", nodeId: "scheduler-1", priority: 10 }
          ConditionExpression: "attribute_not_exists(PK) OR leaseTTL < :now"
        |
        Heartbeat (every 10 seconds):
          UpdateItem: SET lastHeartbeat = now(), leaseTTL = now() + 30s
          ConditionExpression: "nodeId = :myNodeId"
        |
        Failover:
          If scheduler-1 dies, its lease expires after 30 seconds.
          scheduler-2 and scheduler-3 attempt conditional PutItem.
          Highest priority wins (Bully algorithm).
          Winner becomes new leader, re-reads pending DAG runs, resumes scheduling.
    |
    v
    11. MONITORING AND ALERTING (CloudWatch + X-Ray):
        |
        CloudWatch Metrics:
          - scheduler.queue.depth: messages in SQS (per priority)
          - scheduler.task.duration: p50/p95/p99 execution time per task type
          - scheduler.task.failure_rate: failures / total executions
          - scheduler.worker.utilization: active tasks / max capacity
          - scheduler.dagRun.duration: end-to-end DAG run time
          - scheduler.leader.heartbeat_age: staleness of leader heartbeat
        |
        CloudWatch Alarms:
          - queue.depth > 1000 for 5 min -> scale out workers (auto-scaling)
          - task.failure_rate > 10% for 10 min -> PagerDuty alert
          - leader.heartbeat_age > 45s -> leader failover alarm
          - dagRun.duration > 2x SLA -> SLA breach notification
        |
        X-Ray Tracing:
          Each dagRun gets a trace ID. Propagated through:
            API Gateway -> Scheduler -> SQS -> Worker -> External service
          Visualize: which task in the DAG is the bottleneck?
```

---

## Service Selection Reasoning

### API Gateway: AWS API Gateway

```
WHY API GATEWAY (Numbered):

    1. MANAGED TLS TERMINATION AND AUTH:
       Cognito integration for JWT validation.
       No custom auth code in scheduler service.
       |
       WAF integration at the edge:
         - Block malicious DAG submissions (SQL injection in task payloads)
         - Rate limit per API key (100 DAG submissions/min)
         - Geographic blocking if needed

    2. REQUEST/RESPONSE TRANSFORMATION:
       Transform client request format to internal SQS message format.
       Map HTTP status codes to scheduler error codes.
       |
       Usage plans + API keys:
         - Free tier: 100 DAG runs/day
         - Pro tier: 10,000 DAG runs/day
         - Enterprise: unlimited + dedicated workers

    3. ALTERNATIVES CONSIDERED:
       |
       ALB (Application Load Balancer):
         Pro: Cheaper for high throughput, WebSocket support
         Con: No built-in auth, no WAF integration, no usage plans
         Verdict: Use ALB behind API Gateway for internal service mesh
       |
       AppSync (GraphQL):
         Pro: Real-time subscriptions for DAG status
         Con: Overkill for REST API, higher learning curve
         Verdict: Consider for dashboard UI, not for core API
```

### Task Queue: SQS + Lambda

```
WHY SQS (Numbered):

    1. NATIVE PRIORITY SUPPORT VIA MULTIPLE QUEUES:
       Three queues: high, medium, low priority.
       Workers poll high-priority first, then medium, then low.
       |
       Why not a single SQS queue:
         SQS does not natively support message priority within a single queue.
         Multiple queues with weighted polling achieves priority scheduling.
       |
       Configuration:
         task-queue-high:    VisibilityTimeout=300s, ReceiveWaitTimeSeconds=20
         task-queue-medium:  VisibilityTimeout=300s, ReceiveWaitTimeSeconds=20
         task-queue-low:     VisibilityTimeout=600s, ReceiveWaitTimeSeconds=20

    2. EXACTLY-ONCE WITH FIFO:
       SQS FIFO queues support MessageDeduplicationId.
       Same idempotencyKey sent twice within 5-minute window -> second is dropped.
       |
       For task scheduler: idempotencyKey = taskId + dagRunId + attemptNumber
       Prevents double-enqueue if scheduler retries after a network hiccup.

    3. DEAD-LETTER QUEUE (DLQ):
       After maxReceiveCount (3) SQS receives without deletion:
         Message moves to DLQ automatically.
       |
       DLQ alarm: CloudWatch alarm when DLQ has messages.
       Operator investigates: why did this task fail 3 times at the SQS level?
       (Distinct from application-level retries managed by RetryStrategy.)

    4. DELAY SCHEDULING:
       SQS supports DelaySeconds (0 to 900 seconds / 15 minutes).
       Used for retry backoff: re-enqueue with DelaySeconds = backoffDelay.
       |
       For delays > 15 minutes:
         Use EventBridge Scheduler to trigger a Lambda at the desired time.
         Lambda enqueues the task to SQS with DelaySeconds=0.

    5. ALTERNATIVES CONSIDERED:
       |
       Kafka (MSK):
         Pro: Ordered per partition, replay, higher throughput
         Con: No native priority queues, no per-message delay, operational overhead
         Verdict: Overkill for task scheduling; better for event streaming
       |
       Step Functions:
         Pro: Built-in DAG orchestration, retry, wait states
         Con: Cost per state transition ($0.025/1000), 25K history limit per execution
         Verdict: Use for simple DAGs; SQS+custom scheduler for complex DAGs at scale
```

### Worker Pool: ECS Fargate

```
WHY ECS FARGATE (Numbered):

    1. SERVERLESS CONTAINERS -- NO EC2 MANAGEMENT:
       Workers are Docker containers with task execution logic.
       Fargate provisions compute per task -- no idle EC2 instances.
       |
       Worker container spec:
         Image: scheduler-worker:latest
         CPU: 1 vCPU, Memory: 2 GB (configurable per task type)
         Environment: QUEUE_URL, DDB_TABLE, REGION

    2. AUTO-SCALING BY QUEUE DEPTH:
       Target tracking policy:
         Metric: SQS ApproximateNumberOfMessagesVisible
         Target: 10 messages per worker
       |
       Example:
         Queue depth = 100 messages, current workers = 5
         Target = 100 / 10 = 10 workers -> scale out 5 more
       |
         Queue depth = 2 messages, current workers = 10
         Target = 2 / 10 = 1 worker -> scale in to 1 (min capacity)

    3. TASK-SPECIFIC RESOURCE ALLOCATION:
       Different task types need different resources:
         HTTP tasks: 0.25 vCPU, 512 MB (lightweight, I/O bound)
         COMPUTE tasks: 4 vCPU, 8 GB (CPU-heavy transformations)
         DB tasks: 1 vCPU, 4 GB (moderate, network-bound to RDS)
       |
       Fargate allows per-task CPU/memory configuration.
       Worker reads task type from SQS message, selects resource tier.

    4. SPOT FARGATE FOR COST SAVINGS:
       Non-critical tasks (LOW priority, retry-safe):
         Run on Fargate Spot (up to 70% cheaper).
         If Spot interrupted, task returns to SQS, retried on on-demand.
       |
       Critical tasks (HIGH priority, latency-sensitive):
         Always on-demand Fargate. No interruption risk.

    5. ALTERNATIVES CONSIDERED:
       |
       Lambda:
         Pro: True serverless, pay-per-invocation, 0-to-1000 instant scale
         Con: 15-minute max duration, cold starts, 10 GB memory limit
         Verdict: Use for short tasks (< 15 min). SQS + Lambda is viable for
                  lightweight schedulers. Not suitable for long-running ETL tasks.
       |
       EC2 Auto Scaling Group:
         Pro: Full control, GPU instances, cheaper at steady state
         Con: Slower scale-out (minutes), AMI management, patching
         Verdict: Use for GPU/ML tasks. Fargate for general compute tasks.
       |
       EKS (Kubernetes):
         Pro: Multi-cloud portability, Kubernetes ecosystem, KEDA auto-scaling
         Con: Operational overhead (control plane, node groups, RBAC)
         Verdict: Use if already running Kubernetes. Fargate for greenfield.
```

### Task Storage: DynamoDB

```
WHY DYNAMODB (Numbered):

    1. CONDITIONAL WRITES FOR EXACTLY-ONCE TASK CLAIMING:
       Worker claims a task:
         UpdateItem: SET status = "RUNNING", workerId = "worker-A"
         ConditionExpression: "status = :queued"
       |
       Only ONE worker succeeds. Others get ConditionalCheckFailedException.
       This is the foundation of exactly-once execution at the storage level.

    2. SINGLE-TABLE DESIGN FOR TASK SCHEDULER:
       |
       PK (Partition Key)         | SK (Sort Key)                | Data
       ---------------------------+------------------------------+------------------
       DAG#etl-pipeline-001       | META                         | DAG definition
       DAG#etl-pipeline-001       | EDGE#extract#transform       | Dependency edge
       DAG#etl-pipeline-001       | EDGE#transform#validate      | Dependency edge
       RUN#run-etl-001-202605...  | META                         | DagRun metadata
       RUN#run-etl-001-202605...  | TASK#extract                 | Task state
       RUN#run-etl-001-202605...  | TASK#transform               | Task state
       RUN#run-etl-001-202605...  | EXEC#extract#1               | Execution attempt 1
       RUN#run-etl-001-202605...  | EXEC#extract#2               | Execution attempt 2
       WORKER#worker-A            | HEARTBEAT                    | Last heartbeat
       LEADER#scheduler           | LOCK                         | Leader lease
       |
       Access patterns:
         - Get all tasks for a DAG run: PK = "RUN#...", SK begins_with "TASK#"
         - Get all executions for a task: PK = "RUN#...", SK begins_with "EXEC#extract"
         - Get DAG edges: PK = "DAG#...", SK begins_with "EDGE#"
         - Get leader lock: PK = "LEADER#scheduler", SK = "LOCK"

    3. TTL FOR AUTOMATIC CLEANUP:
       Completed dagRuns: TTL = 30 days (auto-delete old runs)
       Worker heartbeats: TTL = 60 seconds (stale workers auto-removed)
       Leader lease: TTL = 30 seconds (expired lease = re-election)
       |
       No cron job needed for cleanup. DynamoDB handles it natively.

    4. GLOBAL SECONDARY INDEX (GSI):
       GSI-1: status-index
         PK: status, SK: createdAt
         Query: "Get all FAILED tasks in the last hour"
       |
       GSI-2: worker-index
         PK: workerId, SK: startedAt
         Query: "Get all tasks running on worker-A" (for failover)

    5. ALTERNATIVES CONSIDERED:
       |
       RDS PostgreSQL:
         Pro: Full SQL, JOINs, complex queries, ACID transactions
         Con: Connection pooling needed, vertical scaling limits, schema changes
         Verdict: Use for complex reporting. DynamoDB for hot-path task state.
       |
       Redis (ElastiCache):
         Pro: Sub-ms reads, pub/sub for notifications, sorted sets for priority
         Con: Volatile (not durable by default), memory-limited, no conditional writes
         Verdict: Use as cache layer on top of DynamoDB, not as primary store.
```

### Leader Election: DynamoDB Conditional Writes

```
WHY DYNAMODB FOR LEADER ELECTION (Numbered):

    1. CONDITIONAL WRITES = DISTRIBUTED LOCK:
       DynamoDB conditional write is atomic and strongly consistent.
       PutItem with ConditionExpression acts as a compare-and-swap (CAS).
       |
       Claim leadership:
         PutItem:
           PK: "LEADER#scheduler", SK: "LOCK"
           nodeId: "scheduler-1", priority: 10, leaseTTL: now + 30s
         ConditionExpression:
           "attribute_not_exists(PK) OR leaseTTL < :now"
       |
       If PK doesn't exist -> new leader. First node wins.
       If leaseTTL expired -> old leader dead. New node takes over.
       If PK exists AND leaseTTL not expired -> ConditionalCheckFailed. Not leader.

    2. HEARTBEAT RENEWAL (leader keeps its lease alive):
       UpdateItem:
         PK: "LEADER#scheduler", SK: "LOCK"
         SET lastHeartbeat = now(), leaseTTL = now + 30s
         ConditionExpression: "nodeId = :myNodeId"
       |
       If the leader crashes, it stops sending heartbeats.
       After 30 seconds, leaseTTL expires.
       Other nodes detect expired lease and attempt to claim leadership.

    3. FENCING TOKEN FOR SPLIT-BRAIN PREVENTION:
       Each leadership claim increments a fencingToken (atomic counter):
         UpdateItem: SET fencingToken = fencingToken + 1
       |
       Workers check fencingToken on every task assignment:
         If assignment came from fencingToken=5 but current leader has fencingToken=6:
           -> Reject the assignment (old leader's zombie command).
       |
       This prevents split-brain: two nodes both thinking they're leader.

    4. ALTERNATIVES CONSIDERED:
       |
       ZooKeeper:
         Pro: Purpose-built for leader election, ephemeral znodes, watches
         Con: Operational overhead (3-5 node ZK cluster), JVM tuning, split-brain risk
         Verdict: Too heavy for this use case. DynamoDB is already in the stack.
       |
       Redis (Redlock):
         Pro: Simple SET NX with TTL, fast
         Con: Redlock is debated (Martin Kleppmann's analysis), not fully CP
         Verdict: Acceptable for best-effort leader election. DynamoDB is safer.
       |
       etcd:
         Pro: Raft consensus, lease-based leader election, Kubernetes native
         Con: Additional infrastructure, Go ecosystem (we're Java)
         Verdict: Use if on Kubernetes (EKS). DynamoDB otherwise.
```

### Cron Scheduling: EventBridge Scheduler

```
WHY EVENTBRIDGE SCHEDULER (Numbered):

    1. NATIVE CRON EXPRESSION SUPPORT:
       EventBridge supports standard cron and rate expressions:
         cron(0 2 * * ? *)     -> daily at 2 AM UTC
         rate(1 hour)          -> every hour
         cron(0/15 * * * ? *)  -> every 15 minutes
       |
       Maps directly to our CronSchedule model:
         CronSchedule { minute, hour, dayOfMonth, month, dayOfWeek }
         -> cron(minute hour dayOfMonth month dayOfWeek ?)

    2. ONE-TIME FUTURE SCHEDULING:
       EventBridge Scheduler supports "at" expressions:
         at(2026-05-10T14:00:00)  -> fire once at this specific time
       |
       Used for: delayed task retries beyond SQS's 15-minute limit.
       Scheduler creates a one-time schedule that enqueues the retry task.

    3. FLEXIBLE TIME WINDOWS:
       Flexible time window: EventBridge fires within a window (e.g., 15 min).
       Reduces thundering herd: 1000 cron DAGs at midnight don't all fire at 00:00:00.
       |
       Strict time window: for time-sensitive DAGs (financial reports).
       Flexible time window: for batch jobs (daily ETL, log aggregation).

    4. DEAD-LETTER QUEUE FOR MISSED TRIGGERS:
       If the target (SQS) is unavailable when cron fires:
         EventBridge retries for up to 24 hours.
         After exhaustion, sends to DLQ.
         Alarm on DLQ -> operator investigates missed DAG trigger.

    5. ALTERNATIVES CONSIDERED:
       |
       CloudWatch Events (old):
         Pro: Same syntax as EventBridge
         Con: Legacy, fewer features, no one-time scheduling
         Verdict: Migrate to EventBridge Scheduler (AWS recommendation)
       |
       Self-managed cron (EC2 crontab):
         Pro: Simple, familiar
         Con: Single point of failure, no HA, no monitoring, no retry
         Verdict: Never use for production distributed scheduler
       |
       Quartz Scheduler (Java library):
         Pro: Rich cron support, clustering, job persistence
         Con: Must manage JDBC store, cluster coordination, no serverless
         Verdict: Use for embedded scheduling in monolith. EventBridge for cloud-native.
```

---

## Managed vs Self-Hosted Tradeoffs

```
MANAGED vs SELF-HOSTED DECISION MATRIX (Numbered):

    1. TASK QUEUE: SQS (Managed) vs RabbitMQ/Kafka (Self-Hosted)
       |
       +---------------------+---------------------------+---------------------------+
       | Factor              | SQS (Managed)             | RabbitMQ (Self-Hosted)    |
       +---------------------+---------------------------+---------------------------+
       | Operations          | Zero. AWS manages scaling,| Cluster provisioning,     |
       |                     | patching, HA.             | upgrades, monitoring,     |
       |                     |                           | disk management.          |
       +---------------------+---------------------------+---------------------------+
       | Cost (1M msgs/day)  | ~$12/month                | ~$300/month (3 EC2 nodes) |
       +---------------------+---------------------------+---------------------------+
       | Latency             | 1-10ms                    | < 1ms (co-located)        |
       +---------------------+---------------------------+---------------------------+
       | Features            | No priority in single     | Native priority queues,   |
       |                     | queue. No routing.        | exchange routing, TTL.    |
       +---------------------+---------------------------+---------------------------+
       | Exactly-once        | FIFO + dedup ID (5 min)   | Publisher confirms +      |
       |                     |                           | consumer ack + dedup.     |
       +---------------------+---------------------------+---------------------------+
       | Verdict             | USE SQS: zero ops, cheap, | USE RABBIT: if you need   |
       |                     | good enough for 90% of    | sub-ms latency, complex   |
       |                     | task schedulers.          | routing, native priority. |
       +---------------------+---------------------------+---------------------------+

    2. TASK STORAGE: DynamoDB (Managed) vs PostgreSQL (Self-Hosted)
       |
       +---------------------+---------------------------+---------------------------+
       | Factor              | DynamoDB (Managed)        | PostgreSQL RDS (Managed)  |
       +---------------------+---------------------------+---------------------------+
       | Conditional writes  | Native (ConditionExpr)    | SELECT FOR UPDATE + lock  |
       +---------------------+---------------------------+---------------------------+
       | Scale               | Infinite (on-demand)      | Vertical (instance size)  |
       +---------------------+---------------------------+---------------------------+
       | Schema flexibility  | Schemaless (attribute map)| Rigid schema (migrations) |
       +---------------------+---------------------------+---------------------------+
       | Complex queries     | Limited (scan or GSI)     | Full SQL, JOINs, CTEs    |
       +---------------------+---------------------------+---------------------------+
       | Cost (10M tasks/day)| ~$150/month (on-demand)   | ~$400/month (db.r6g.xl)  |
       +---------------------+---------------------------+---------------------------+
       | Verdict             | USE DYNAMO: task state is  | USE PG: if you need      |
       |                     | key-value access. Cond.    | complex reporting,       |
       |                     | writes are perfect for     | historical analysis,     |
       |                     | task claiming.             | ad-hoc queries.          |
       +---------------------+---------------------------+---------------------------+

    3. LEADER ELECTION: DynamoDB vs ZooKeeper vs etcd
       |
       +---------------------+---------------------------+---------------------------+
       | Factor              | DynamoDB Cond. Writes     | ZooKeeper                 |
       +---------------------+---------------------------+---------------------------+
       | Operations          | Zero (serverless)         | 3-5 node cluster, JVM     |
       |                     |                           | tuning, GC pauses.        |
       +---------------------+---------------------------+---------------------------+
       | Failover speed      | ~30s (lease TTL expiry)   | ~5s (ephemeral znode)     |
       +---------------------+---------------------------+---------------------------+
       | Correctness         | CP (strongly consistent   | CP (ZAB consensus)        |
       |                     | reads + conditional write) |                          |
       +---------------------+---------------------------+---------------------------+
       | Cost                | ~$1/month (handful of     | ~$200/month (3 EC2 nodes) |
       |                     | reads/writes per second)  |                           |
       +---------------------+---------------------------+---------------------------+
       | Verdict             | USE DYNAMO: already in    | USE ZK: if you need       |
       |                     | stack, zero ops, cheap.   | sub-5s failover, watches, |
       |                     | 30s failover is acceptable| or Kafka dependency.      |
       |                     | for a task scheduler.     |                           |
       +---------------------+---------------------------+---------------------------+

    4. MONITORING: CloudWatch (Managed) vs Prometheus/Grafana (Self-Hosted)
       |
       +---------------------+---------------------------+---------------------------+
       | Factor              | CloudWatch                | Prometheus + Grafana      |
       +---------------------+---------------------------+---------------------------+
       | Operations          | Zero                      | Prometheus server, storage|
       |                     |                           | (EBS/S3), Grafana server. |
       +---------------------+---------------------------+---------------------------+
       | Custom metrics      | PutMetricData API         | /metrics endpoint scraping|
       +---------------------+---------------------------+---------------------------+
       | Dashboards          | CloudWatch Dashboards     | Grafana (richer UI)       |
       +---------------------+---------------------------+---------------------------+
       | Alerting            | CloudWatch Alarms + SNS   | Alertmanager + PagerDuty  |
       +---------------------+---------------------------+---------------------------+
       | Cost (100 metrics)  | ~$30/month                | ~$100/month (EC2 + EBS)   |
       +---------------------+---------------------------+---------------------------+
       | Verdict             | USE CW: zero ops, native  | USE PROM: if you need     |
       |                     | AWS integration, alarms   | rich PromQL queries,      |
       |                     | trigger auto-scaling.     | Grafana dashboards.       |
       +---------------------+---------------------------+---------------------------+
```

---

## Cost Optimization Strategies

### Assumptions

```
Total DAG definitions:            500
DAG runs per day:                 2,000 (mix of cron + manual)
Tasks per DAG (average):          5
Total tasks per day:              10,000
Peak tasks per second:            50 (batch jobs at midnight)
Average task duration:            60 seconds
Concurrent workers (peak):        50
Task payload size (average):      2 KB
Execution history retention:      30 days
```

### Monthly Cost Breakdown (AWS)

| Resource | Spec | Monthly Cost |
|----------|------|-------------|
| **API Gateway** | 2M requests/month (DAG submissions + status polls), REST API | ~$7 |
| **ECS Fargate (Scheduler)** | 3 tasks (leader + 2 standby), 1 vCPU / 2 GB each, 24/7 | ~$220 |
| **ECS Fargate (Workers)** | 10-50 tasks (auto-scale), 1 vCPU / 2 GB each, avg 20 tasks | ~$1,100 |
| **SQS (3 priority queues)** | 10K tasks/day * 3 messages each (enqueue, claim, ack) = 900K/month | ~$1 |
| **DynamoDB** | On-demand. 10K writes/day (tasks) + 50K reads/day. 10 GB storage. | ~$25 |
| **EventBridge Scheduler** | 2K schedules (cron DAGs), 60K invocations/month | ~$3 |
| **ElastiCache Redis** | 1 shard, cache.r6g.large, 1 replica. DAG cache, dedup, heartbeats. | ~$400 |
| **CloudWatch** | 50 custom metrics, 10 alarms, 5 dashboards, X-Ray traces | ~$50 |
| **Lambda (dep resolver)** | 300K invocations/month, 256 MB, avg 200ms | ~$2 |
| **S3 (logs + payloads)** | 30 GB/month (execution logs, large payloads) | ~$1 |
| **WAF** | 5 rules, 2M requests evaluated | ~$11 |
| **Total** | | **~$1,820/month** |

### Cost per DAG Run

| Scale | DAG Runs/Day | Monthly Cost | Cost/DAG Run |
|-------|-------------|-------------|-------------|
| Startup | 100 | ~$800 | $0.267 |
| Growth | 2,000 | ~$1,820 | $0.030 |
| Scale | 20,000 | ~$5,500 | $0.009 |
| Airflow-scale | 200,000 | ~$35,000 | $0.006 |

### Cost Optimization Strategies (Numbered)

```
    1. FARGATE SPOT FOR LOW-PRIORITY WORKERS:
       Low-priority tasks (notify, cleanup, archival) run on Fargate Spot.
       Up to 70% cheaper than on-demand.
       If Spot interrupted: task returns to SQS, retried on on-demand worker.
       |
       Savings estimate: 30% of tasks are low-priority -> 30% * 70% = 21% worker savings.
       Monthly savings: ~$230/month at growth scale.

    2. DYNAMODB RESERVED CAPACITY (for predictable workloads):
       If daily task volume is stable:
         Reserved capacity: 100 WCU + 500 RCU = ~$73/month (vs ~$150 on-demand).
         Savings: 50% on DynamoDB costs.
       |
       Keep on-demand for unpredictable burst workloads.

    3. WORKER RIGHT-SIZING:
       Monitor actual CPU/memory usage with CloudWatch Container Insights.
       Most HTTP tasks use < 256 MB and 0.25 vCPU.
       Reduce from 1 vCPU / 2 GB to 0.25 vCPU / 512 MB for HTTP tasks.
       |
       Savings: 75% per HTTP task execution.
       At scale: HTTP tasks = 40% of workload -> 40% * 75% = 30% worker savings.

    4. TIERED EXECUTION HISTORY RETENTION:
       Hot (DynamoDB): last 7 days of execution records.
       Warm (S3 + Athena): 7-30 days, query with Athena when needed.
       Cold (S3 Glacier): 30+ days, for compliance/audit.
       |
       DynamoDB TTL auto-deletes records after 7 days.
       Lambda streams expired records to S3 before deletion.
       Savings: 80% on DynamoDB storage for historical data.

    5. BATCH TASK COALESCING:
       Instead of 100 separate "send-email" tasks:
         Coalesce into 1 "batch-send-email" task with 100 recipients.
       |
       Reduces: SQS messages, DynamoDB writes, worker spin-up/down.
       Requires: task type to support batching (not all tasks can batch).

    6. EVENTBRIDGE SCHEDULER vs SQS DELAY:
       For retries < 15 minutes: use SQS DelaySeconds (free, no extra service).
       For retries > 15 minutes: use EventBridge one-time schedule ($0.000001/invocation).
       |
       Avoid: polling DynamoDB every minute to check "is it time to retry?"
       That wastes read capacity and costs money.
```

---

## Multi-Cloud Comparison: Architecture Variants

### Azure Architecture

```
AZURE DISTRIBUTED TASK SCHEDULER (Numbered):

    1. TASK SUBMISSION:
       Azure API Management (APIM) -> Azure Service Bus queue
       |
       APIM features: OAuth 2.0 validation, rate limiting, request transformation.
       Service Bus: native priority property on messages (Priority 1-10).
       Advantage over AWS: single queue with priority, no need for 3 separate queues.

    2. SCHEDULER SERVICE:
       Azure Durable Functions (orchestrator function)
       |
       Durable Functions support:
         - Fan-out/fan-in: DAG execution pattern built-in
         - Sub-orchestrations: nested DAG support
         - Eternal orchestrations: cron-like recurring DAGs
         - Automatic checkpointing: state persisted to Azure Storage
       |
       Advantage: No need to build custom DAG resolver -- Durable Functions
       natively support dependency resolution via activity chaining.

    3. WORKER POOL:
       Azure Container Instances (ACI) for burst, AKS for steady state.
       KEDA auto-scaler: scales AKS pods based on Service Bus queue depth.
       |
       ACI: serverless containers, cold start ~5 seconds. Good for burst.
       AKS + KEDA: warm pods, sub-second dispatch. Good for steady workload.

    4. LEADER ELECTION:
       Azure Blob Lease: acquire a 60-second lease on a blob.
       Only the lease holder is the leader. Renew every 15 seconds.
       If holder dies, lease expires, another node acquires it.
       |
       Advantage: simpler than DynamoDB conditional writes.
       Disadvantage: no fencing token natively (must implement separately).

    5. CRON SCHEDULING:
       Azure Timer Triggers (Azure Functions):
         [TimerTrigger("0 0 2 * * *")] -> fires daily at 2 AM
       |
       Or Azure Logic Apps for visual workflow orchestration.
       Logic Apps provide a designer UI for non-technical DAG builders.
```

### GCP Architecture

```
GCP DISTRIBUTED TASK SCHEDULER (Numbered):

    1. TASK SUBMISSION:
       GCP API Gateway / Apigee -> Cloud Tasks queue
       |
       Cloud Tasks: managed task queue with HTTP/gRPC dispatch to Cloud Run.
       Native features: task deduplication, rate limiting, retry configuration.
       Advantage: Cloud Tasks dispatches directly to Cloud Run (no polling needed).

    2. SCHEDULER SERVICE:
       Cloud Scheduler (cron) + Cloud Workflows (DAG orchestration)
       |
       Cloud Workflows:
         - YAML/JSON-based workflow definition
         - Steps with conditions, loops, parallel branches
         - Built-in retry with exponential backoff
         - Connectors to GCP services (BigQuery, Cloud Run, Pub/Sub)
       |
       Advantage: declarative DAG definition, no custom scheduler code.
       Disadvantage: less flexible than custom scheduler for complex logic.

    3. WORKER POOL:
       Cloud Run (auto-scaling, scale-to-zero):
         Min instances: 0 (scale to zero when no tasks)
         Max instances: 100 (burst capacity)
         Concurrency: 1 (one task per instance for isolation)
       |
       Cloud Tasks dispatches HTTP request to Cloud Run endpoint.
       Cloud Run processes task, returns 200 OK (ACK) or 5xx (retry).
       |
       Advantage: true scale-to-zero. No cost when no tasks are running.
       AWS Fargate always has minimum 1 task running.

    4. LEADER ELECTION:
       Cloud Spanner (TrueTime-based locks):
         Spanner provides externally consistent reads/writes.
         Leader election via conditional INSERT with TTL.
       |
       Or Chubby (internal Google lock service, not publicly available).
       For GCP users: Spanner or custom implementation on Firestore.

    5. CRON SCHEDULING:
       Cloud Scheduler:
         POST https://scheduler-service.run.app/trigger
         Schedule: "0 2 * * *"
         Retry: 3 attempts with exponential backoff
       |
       Advantage: fully managed, HTTP target, Pub/Sub target, App Engine target.
       Integrates directly with Cloud Run and Cloud Functions.
```

---

## Disaster Recovery and Multi-Region

```
DR STRATEGY: ACTIVE-PASSIVE WITH RPO < 5 MINUTES (Numbered):

    1. WHY RPO < 5 MINUTES (not zero):
       Unlike financial trading, a task scheduler can tolerate brief data loss.
       A missed cron trigger can be re-fired. A failed task can be retried.
       |
       RPO (Recovery Point Objective) = 5 minutes: up to 5 min of task state lost.
       RTO (Recovery Time Objective) = 10 minutes: time to resume scheduling.
       |
       Tradeoff: RPO=0 requires synchronous cross-region replication.
         DynamoDB Global Tables: async (< 1 second), not synchronous.
         To get RPO=0: use DynamoDB Streams + cross-region Lambda (complex).
         Not worth the complexity for a task scheduler.

    2. ACTIVE-PASSIVE ARCHITECTURE:
       Primary: us-east-1 (all scheduling happens here)
       DR: us-west-2 (warm standby, data replicated)
       |
       Primary:
         - Scheduler service (leader-elected, 3 nodes)
         - Worker pool (auto-scaling, 10-50 tasks)
         - DynamoDB (task state, DAG definitions)
         - SQS (task queues)
         - EventBridge Scheduler (cron triggers)
       |
       DR:
         - Scheduler service (3 nodes, idle, pre-provisioned)
         - Worker pool (min 2 tasks, warm)
         - DynamoDB Global Table (async replica)
         - SQS (empty, created but unused)
         - EventBridge Scheduler (disabled, cloned from primary)

    3. FAILOVER PROCEDURE (RTO = 10 minutes):
       Trigger: CloudWatch alarm detects primary unhealthy
         - Scheduler leader heartbeat missing > 60 seconds
         - SQS queue depth growing but no tasks completing
         - DynamoDB write failures > 50% for 2 minutes
       |
       Automated failover:
         Minute 0: Alarm triggers failover Lambda
         Minute 1: Route 53 health check fails, DNS switches to DR
         Minute 3: DR scheduler service elects leader
         Minute 5: DR EventBridge Schedulers enabled (cron DAGs resume)
         Minute 7: DR workers start processing tasks from DR SQS
         Minute 10: Fully operational in DR region
       |
       Post-failover reconciliation:
         - Scan DynamoDB for tasks in RUNNING state (from primary workers)
         - Reset to QUEUED (they were likely interrupted)
         - Re-enqueue to SQS for DR workers to pick up
         - Rebuild cron schedule state from DynamoDB DAG definitions

    4. TESTING:
       Monthly DR drill: failover to DR during low-traffic period
       Chaos engineering: kill scheduler leader during peak load
       Runbook: documented manual failover steps if automation fails
```

---

## Interview Tip

> "For a distributed task scheduler on AWS, I'd use **DynamoDB** as the task store with **conditional writes** for exactly-once task claiming -- only one worker can transition a task from QUEUED to RUNNING. Tasks flow through **API Gateway** to the **Scheduler Service** (ECS Fargate, leader-elected via DynamoDB lease), which resolves **DAG dependencies** using topological sort and enqueues ready tasks to **SQS priority queues** (high/medium/low). Workers are **ECS Fargate** with auto-scaling based on SQS queue depth -- scale out when backlog grows, scale in when idle. Cron DAGs are triggered by **EventBridge Scheduler**, which fires at the cron time and enqueues the root tasks. Retry uses **exponential backoff** with jitter (initial 1s, max 5 min, multiplier 2x) to prevent thundering herd. Leader election uses **DynamoDB conditional PutItem** with a 30-second lease TTL -- if the leader dies, its lease expires and another node claims leadership. For failover, I'd use **DynamoDB Global Tables** to replicate to a DR region with RPO < 5 minutes. The system is **CP for task state** (no double execution) and **AP for monitoring** (stale metrics are acceptable). This mirrors how **Apache Airflow** works, but with managed AWS services instead of self-hosted Celery + Redis + PostgreSQL."

This shows you understand **DAG dependency resolution, exactly-once semantics, priority queuing, leader election, exponential backoff, worker auto-scaling, and managed-vs-self-hosted tradeoffs** -- the key pillars of distributed task scheduler design.

---

## Quick Reference: Which Service When

| Decision Point | Service | Config | Why |
|---------------|---------|--------|-----|
| Task submission | API Gateway + WAF | Rate limit 100/min/user, JWT auth, payload validation | Security + throttling at edge |
| Task queuing | SQS (3 priority queues) | FIFO + dedup ID, visibility timeout 5 min, DLQ | Priority routing, exactly-once dequeue |
| DAG orchestration | ECS Fargate (Scheduler) | 3 nodes, leader-elected, 1 vCPU / 2 GB | DAG resolution, task dispatch, failover coordination |
| Task execution | ECS Fargate (Workers) | Auto-scale 10-50, queue depth target tracking | Stateless execution, horizontal scaling |
| Task state | DynamoDB | On-demand, single-table, conditional writes, TTL | Exactly-once claiming, auto-cleanup, infinite scale |
| Cron triggers | EventBridge Scheduler | cron() expressions, DLQ for missed triggers | Managed cron, no self-hosted crontab |
| Leader election | DynamoDB conditional writes | 30s lease TTL, fencing token, heartbeat every 10s | Zero-ops, CP, cheap |
| Retry backoff | SQS DelaySeconds + EventBridge | SQS for < 15 min, EventBridge for > 15 min | Native delay, no polling loop |
| Monitoring | CloudWatch + X-Ray | Custom metrics, alarms, distributed tracing | Native AWS integration, auto-scaling triggers |
| Cache | ElastiCache Redis | DAG adjacency cache, dedup window, heartbeat TTL | Sub-ms reads for hot-path data |
| Event notifications | EventBridge | Task/DAG lifecycle events, fan-out to SNS/Lambda | Decoupled event-driven architecture |
| DR | DynamoDB Global Tables + Route 53 | Active-passive, RPO < 5 min, RTO 10 min | Managed replication, DNS failover |
