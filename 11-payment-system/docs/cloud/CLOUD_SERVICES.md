# Payment System (Stripe/UPI) -- Cloud Service Mapping

## Component-to-Service Mapping

| Our Component | AWS | Azure | GCP | Notes |
|---------------|-----|-------|-----|-------|
| **API Gateway** | API Gateway (REST/HTTP) + WAF | API Management + Front Door | Cloud Endpoints + Cloud Armor | TLS termination, rate limiting, request auth |
| **Payment Service** | ECS/EKS (Fargate) | AKS | GKE | Core payment processing, idempotency enforcement |
| **Ledger Service** | ECS/EKS (Fargate) | AKS | GKE | Double-entry bookkeeping, append-only writes |
| **Webhook Service** | ECS/EKS (Fargate) + SQS | AKS + Service Bus | GKE + Pub/Sub | HMAC-signed merchant notifications with retry |
| **Fraud Detection Service** | ECS/EKS (Fargate) + SageMaker | AKS + Azure ML | GKE + Vertex AI | Rule engine + ML scoring pipeline |
| **Reconciliation Service** | Lambda + Step Functions | Durable Functions | Cloud Functions + Workflows | Daily batch: match ledger vs bank statements |
| **Merchant Dashboard** | S3 + CloudFront (SPA) | Blob Storage + CDN | Cloud Storage + CDN | Analytics, payout history, webhook config |
| **Relational DB (ledger, payments)** | RDS Aurora (PostgreSQL) | Azure SQL | Cloud SQL / AlloyDB | ACID for double-entry ledger, immutable append-only |
| **Idempotency Store** | ElastiCache Redis + RDS fallback | Azure Cache for Redis | Memorystore | Fast idempotency key lookup, DB as source of truth |
| **Message Queue** | SQS (point-to-point) + SNS (fan-out) | Service Bus + Event Grid | Pub/Sub + Cloud Tasks | Payment events, webhook delivery, reconciliation triggers |
| **Encryption & Key Management** | KMS + CloudHSM | Key Vault + Managed HSM | Cloud KMS + Cloud HSM | Card data encryption, PCI-DSS key management |
| **Secrets Management** | Secrets Manager | Key Vault | Secret Manager | API keys, bank credentials, merchant secrets |
| **Object Storage** | S3 (encrypted) | Blob Storage | Cloud Storage | Reconciliation reports, audit logs, PCI evidence |
| **Monitoring** | CloudWatch + X-Ray | Azure Monitor + App Insights | Cloud Monitoring + Trace | Payment latency p50/p99, success rate, fraud rate |
| **DNS** | Route 53 (failover routing) | Traffic Manager | Cloud DNS | Active-passive failover for payment availability |
| **WAF / DDoS Protection** | AWS WAF + Shield Advanced | Azure WAF + DDoS Protection | Cloud Armor | PCI requirement: protect payment endpoints |
| **Audit Logging** | CloudTrail + S3 (immutable) | Activity Log + Immutable Storage | Audit Logs + Locked Buckets | PCI-DSS: immutable audit trail for all payment operations |

---

## PCI-DSS Compliant Architecture on AWS (Numbered)

```
Cardholder enters card number on merchant checkout page
    |
    1. HTTPS (TLS 1.2+) -- card data never touches merchant server
       Merchant frontend calls Payment System API directly (tokenization)
    |
    v
+---------------------------------------------------------------+
|              AWS WAF + Shield Advanced                         |
|   Rule: block SQL injection, XSS, known bad IPs               |
|   Rate limit: 1,000 req/sec per merchant API key              |
+---------------------------+-----------------------------------+
                            |
    2. WAF-filtered request -> API Gateway (private VPC endpoint)
                            |
                            v
+---------------------------------------------------------------+
|              API Gateway (REST, mTLS)                          |
|   Auth: merchant API key + HMAC signature validation           |
|   mTLS: mutual TLS for bank-to-bank communication             |
|   Routes: /v1/payments, /v1/refunds, /v1/payouts              |
+---------------------------+-----------------------------------+
                            |
    3. Request enters PCI-scoped VPC (isolated network)
                            |
                            v
+===============================================================+
|              PCI-DSS VPC (Isolated Subnet)                     |
|   No internet access. No SSH. No bastion.                     |
|   All traffic via VPC endpoints (PrivateLink).                |
|                                                                |
|   4. ECS Fargate (Payment Service)                            |
|      - Receives card data                                     |
|      - Immediately tokenizes via CloudHSM                     |
|      - Stores TOKEN, never raw card number                    |
|      - Container images scanned (ECR image scanning)          |
|      - No persistent storage on container                     |
|                                                                |
|   5. CloudHSM (FIPS 140-2 Level 3)                           |
|      - Generates encryption keys                              |
|      - Encrypts card PAN -> token                             |
|      - Keys never leave HSM hardware                          |
|      - PCI-DSS Requirement 3: protect stored cardholder data  |
|                                                                |
|   6. RDS Aurora PostgreSQL (encrypted at rest + in transit)   |
|      - Stores: payment records, tokens (NOT raw card data)    |
|      - Encryption: AES-256 via KMS                            |
|      - Audit: pgAudit extension logs all queries              |
|      - Backups: encrypted, cross-region, 35-day retention     |
|                                                                |
|   7. ElastiCache Redis (in PCI VPC, encryption in transit)    |
|      - Idempotency key cache (TTL 24h)                        |
|      - Rate limiting counters                                 |
|      - No card data stored in cache -- tokens only            |
|                                                                |
+===============================================================+
                            |
    8. Payment Service calls bank/processor via VPC endpoint
       (PrivateLink to bank partner or NAT Gateway to Visa/MC)
                            |
                            v
+---------------------------------------------------------------+
|   Bank / Card Network (Visa, Mastercard, UPI)                 |
|   Authorize -> Capture -> Settle (T+1 or T+2)                |
+---------------------------------------------------------------+
                            |
    9. Response flows back: auth code, settlement reference
                            |
                            v
    10. Payment Service updates ledger (double-entry)
        Publishes event: "payment.captured" -> SNS
                            |
              +-------------+-------------+
              |             |             |
              v             v             v
        +-----------+ +-----------+ +------------+
        | SQS:      | | SQS:      | | SQS:       |
        | Webhook   | | Reconcil. | | Fraud      |
        | Delivery  | | Queue     | | Analytics  |
        +-----------+ +-----------+ +------------+
              |
    11. Webhook Service: POST to merchant URL
        Headers: X-Signature: HMAC-SHA256(payload, secret)
        Retry: exponential backoff (1s, 2s, 4s, 8s, 16s)
        Max 5 attempts -> DLQ if all fail

    12. CloudTrail + CloudWatch Logs -> S3 (immutable, 7-year retention)
        PCI-DSS Requirement 10: track all access to cardholder data
```

### PCI-DSS Key Requirements Mapped to AWS

| PCI-DSS Requirement | AWS Implementation |
|---------------------|-------------------|
| Req 1: Firewall / network segmentation | VPC with isolated PCI subnet, security groups, NACLs |
| Req 2: No vendor defaults | Custom AMIs, no default passwords, CIS benchmark hardening |
| Req 3: Protect stored cardholder data | CloudHSM tokenization, KMS AES-256 encryption at rest |
| Req 4: Encrypt transmission | TLS 1.2+ everywhere, mTLS for bank communication |
| Req 6: Secure systems and applications | ECR image scanning, CodePipeline with SAST/DAST |
| Req 7: Restrict access (need-to-know) | IAM roles, least privilege, no human access to PCI VPC |
| Req 8: Authenticate access | MFA for console, IAM roles for services, no shared credentials |
| Req 10: Track and monitor all access | CloudTrail, pgAudit, VPC Flow Logs, immutable S3 logs |
| Req 11: Regular security testing | GuardDuty, Inspector, penetration testing quarterly |
| Req 12: Information security policy | AWS Artifact for compliance reports, SOC 2 |

---

## Payment Processing Architecture (Numbered)

```
Merchant integrates Payment System SDK (client-side tokenization)
    |
    1. Customer enters card: 4242-4242-4242-4242
       SDK encrypts card data client-side (RSA public key)
       Sends encrypted payload to Payment System API
    |
    v
    2. API Gateway validates:
       - Merchant API key (X-Api-Key header)
       - HMAC signature (X-Signature: HMAC-SHA256(body, secret))
       - Rate limit check (Redis counter per merchant)
       - Idempotency key present (X-Idempotency-Key header)
    |
    v
    3. Payment Service receives request:
       POST /v1/payments
       {
         "amount": 4999,           // cents (avoid floating point)
         "currency": "USD",
         "payment_method": "card",
         "card_token": "tok_abc123",
         "idempotency_key": "merchant-uuid-12345",
         "metadata": { "order_id": "ORD-789" }
       }
    |
    v
    4. IDEMPOTENCY CHECK (Redis + DB):
       Redis: GET idempotency:merchant-uuid-12345
       |
       +-- HIT: return cached response (no processing)
       |
       +-- MISS: continue to step 5
    |
    v
    5. FRAUD CHECK (chain of responsibility):
       Rule 1: Velocity -- has this card been used 5+ times in 1 hour?
       Rule 2: Amount threshold -- is amount > $10,000?
       Rule 3: Geolocation -- does IP country match card issuer country?
       Rule 4: ML model -- risk score from SageMaker endpoint
       |
       +-- risk_score > 0.8: DECLINE (auto-reject)
       +-- risk_score 0.5-0.8: 3D SECURE (step-up authentication)
       +-- risk_score < 0.5: APPROVE (continue)
    |
    v
    6. AUTHORIZATION (hold funds on card):
       Payment Service -> Bank/Card Network:
         "Hold $49.99 on card ending 4242"
       Bank responds:
         { auth_code: "AUTH-567", status: "AUTHORIZED" }
       
       Ledger entry (authorization):
         DEBIT  customer_funds_held   $49.99
         CREDIT customer_card_auth    $49.99
    |
    v
    7. CAPTURE (charge the card):
       Payment Service -> Bank/Card Network:
         "Capture $49.99 against AUTH-567"
       Bank responds:
         { capture_ref: "CAP-890", status: "CAPTURED" }
       
       Ledger entry (capture):
         DEBIT  customer_card_auth    $49.99  (release hold)
         CREDIT merchant_balance      $49.99  (merchant can withdraw)
    |
    v
    8. PERSIST + CACHE:
       DB: INSERT payment record + ledger entries (single transaction)
       Redis: SET idempotency:merchant-uuid-12345 = response (TTL 24h)
    |
    v
    9. RESPOND to merchant:
       HTTP 200
       {
         "id": "pay_abc123",
         "status": "captured",
         "amount": 4999,
         "currency": "USD",
         "created_at": "2026-04-26T10:30:00Z"
       }
    |
    v
    10. ASYNC: Publish "payment.captured" event -> SNS
        -> Webhook queue (notify merchant)
        -> Reconciliation queue (match with bank statement)
        -> Analytics (revenue dashboards)
    |
    v
    11. SETTLEMENT (T+1 or T+2, batch):
        Step Functions daily job:
          - Pull captured payments for settlement window
          - Group by acquiring bank
          - Submit batch to bank: "settle these 50,000 transactions"
          - Bank confirms: funds transferred to merchant pool
        
        Ledger entry (settlement):
          DEBIT  merchant_balance      $49.99  (owed to merchant)
          CREDIT merchant_settled      $49.99  (ready for payout)
    |
    v
    12. PAYOUT (weekly/daily per merchant config):
        Step Functions payout job:
          - Sum settled balance per merchant
          - ACH/wire transfer to merchant bank account
          - Deduct platform fee (2.9% + $0.30)
        
        Ledger entry (payout):
          DEBIT  merchant_settled      $49.99
          CREDIT platform_fee_revenue  $1.75   (2.9% + $0.30)
          CREDIT merchant_bank_account $48.24   (net payout)
```

---

## Cost Estimation at Scale (1B Transactions/Month)

### Assumptions

```
Monthly transactions:        1,000,000,000 (1B)
Daily transactions:          ~33,000,000 (33M)
Peak TPS:                    33M / 86400 * 3 = ~1,150 TPS (3x peak)
Average transaction value:   $35
Webhook deliveries/month:    2B (2 webhooks per transaction avg)
Idempotency cache entries:   100M active (24h TTL window)
Ledger entries/month:        4B (avg 4 entries per transaction)
Reconciliation records/day:  33M (one per transaction)
Active merchants:            500,000
```

### Monthly Cost Breakdown

| Resource | Spec | Monthly Cost |
|----------|------|-------------|
| **API Gateway** | 1B requests/month, REST + mTLS | ~$35,000 |
| **WAF + Shield Advanced** | 1B inspected requests, DDoS protection | ~$15,000 |
| **ECS/Fargate (Payment Service)** | 80 tasks, 4 vCPU / 8 GB, PCI VPC | ~$35,000 |
| **ECS/Fargate (Webhook Service)** | 40 tasks, 2 vCPU / 4 GB | ~$12,000 |
| **ECS/Fargate (Fraud Service)** | 30 tasks, 4 vCPU / 8 GB | ~$15,000 |
| **RDS Aurora PostgreSQL (Ledger)** | Multi-AZ, db.r6g.4xlarge, 3 read replicas | ~$25,000 |
| **RDS Aurora PostgreSQL (Payments)** | Multi-AZ, db.r6g.2xlarge, 2 read replicas | ~$15,000 |
| **ElastiCache Redis (idempotency + rate limit)** | 6 shards, r6g.xlarge, 1 replica each | ~$12,000 |
| **CloudHSM** | 2 HSM instances (HA pair), PCI-scoped | ~$3,200 |
| **KMS** | 1B encryption operations/month | ~$3,000 |
| **SQS/SNS (events + webhooks)** | 3B messages/month (payments + webhooks + reconciliation) | ~$4,000 |
| **SageMaker (fraud ML)** | 4 ml.c5.2xlarge endpoints, real-time inference | ~$8,000 |
| **Step Functions (reconciliation + settlement)** | 33M executions/month, 3-5 states each | ~$8,000 |
| **Lambda (reconciliation workers)** | 100M invocations/month, 512MB, 500ms avg | ~$5,000 |
| **S3 (audit logs, reconciliation reports)** | 50 TB storage, immutable, 7-year retention | ~$3,000 |
| **CloudTrail + CloudWatch** | Full API logging, custom metrics, alarms | ~$6,000 |
| **Data transfer** | Cross-AZ, VPC endpoints, internet egress | ~$10,000 |
| **Total** | | **~$234,200/month** |

### Cost Optimization Strategies

1. **Reserved Instances** -- 1-year RI for RDS, ElastiCache saves 30-40% (~$15K/month saved)
2. **Spot instances** -- ECS Spot for webhook delivery workers (retry-safe, idempotent)
3. **Step Functions Express** -- Use Express Workflows for settlement batches (80% cheaper)
4. **Lambda ARM (Graviton)** -- 20% cheaper for reconciliation workers
5. **S3 Intelligent-Tiering** -- Auto-tier old audit logs to Glacier after 90 days
6. **Redis TTL tuning** -- 24h idempotency TTL (not infinite) keeps cache size bounded
7. **SQS long polling** -- Reduce empty receives, save ~30% on SQS costs
8. **Batch bank calls** -- Aggregate settlement into hourly batches (fewer API calls, lower bank fees)

### Cost at Different Scales

| Scale | Monthly Txns | Monthly Cost | Cost/Txn |
|-------|-------------|-------------|---------|
| Startup | 100K | ~$4,500 | $0.045 |
| Growth | 10M | ~$18,000 | $0.0018 |
| Scale | 100M | ~$65,000 | $0.00065 |
| Stripe-scale | 1B | ~$234,200 | $0.00023 |

---

## Multi-Region for Payment Availability

```
Payments MUST have 99.999% availability (5 nines = 5.26 min downtime/year).
A single-region outage (us-east-1 goes down) cannot stop payments.

                         +-------------------------------+
                         |       Route 53 (DNS)          |
                         |  Failover routing:            |
                         |  Primary:  us-east-1          |
                         |  Secondary: us-west-2         |
                         |  Health check: /v1/health     |
                         |  Failover: 30 seconds         |
                         +------+---------------+-------+
                                |               |
              +-----------------v--+   +--------v-----------------+
              |    us-east-1       |   |    us-west-2             |
              |    (PRIMARY)       |   |    (HOT STANDBY)         |
              |                    |   |                          |
              |  WAF + API GW     |   |  WAF + API GW            |
              |  ECS (Payment,    |   |  ECS (Payment,           |
              |   Webhook, Fraud) |   |   Webhook, Fraud)        |
              |  ElastiCache Redis|   |  ElastiCache Redis       |
              |  CloudHSM (pair)  |   |  CloudHSM (pair)         |
              |                    |   |                          |
              |  RDS Aurora Global |   |  RDS Aurora Global      |
              |  (Primary Writer)  |   |  (Read Replica +        |
              |                    |   |   promote on failover)  |
              |                    |   |                          |
              |  SQS (regional)    |   |  SQS (regional)         |
              |  Step Functions    |   |  Step Functions          |
              +--------------------+   +--------------------------+
                       |                          |
                       +--- Aurora Global DB -----+
                       |   Replication lag < 1s   |
                       |   RPO < 1 second         |
                       +--- S3 cross-region ------+
                           replication (audit logs)
```

### Multi-Region Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| Payment DB | **Aurora Global Database (single primary writer)** | Payments need ACID; write to primary, promote secondary on failover |
| Ledger DB | **Aurora Global Database (same cluster as payment)** | Ledger entries and payments must be in same transaction |
| Idempotency cache | **Regional ElastiCache (independent)** | After failover, DB is source of truth; cache re-warms in minutes |
| Webhook queue | **Regional SQS (independent)** | Each region processes its own webhook deliveries |
| CloudHSM | **Regional HSM pairs** | HSM keys synced across regions via CloudHSM key export/import |
| Fraud model | **Regional SageMaker endpoints** | Same model deployed in both regions; no cross-region dependency |
| Audit logs | **S3 cross-region replication** | Compliance: logs must survive regional disaster |
| Reconciliation | **Primary region only (not replicated)** | Daily batch job; runs in primary, falls back to secondary if needed |

### Failover Sequence (Numbered)

```
PRIMARY REGION FAILURE (us-east-1 goes down)
    |
    1. Route 53 health check fails (3 consecutive failures, 10s interval)
       Total detection time: ~30 seconds
    |
    v
    2. Route 53 failover: DNS points to us-west-2
       TTL = 60s -> clients switch within 90 seconds total
    |
    v
    3. Aurora Global Database: promote us-west-2 replica to primary writer
       Automatic with Aurora Global: ~60 seconds
       RPO < 1 second (async replication lag)
       In-flight transactions in us-east-1 may be lost (retry via idempotency key)
    |
    v
    4. us-west-2 ECS services now handle all traffic:
       - Payment Service: processes new payments (idempotency key prevents duplicates)
       - Webhook Service: retries any undelivered webhooks (SQS messages survive)
       - Fraud Service: ML model already deployed and warmed
    |
    v
    5. ElastiCache in us-west-2: cache is cold for some keys
       Impact: first request for each idempotency key hits DB (cache miss)
       DB has the record -> cache is populated -> subsequent requests are fast
       Self-healing: cache warms within 5-10 minutes
    |
    v
    6. CloudHSM in us-west-2: keys pre-synced, no action needed
       Tokenization continues without interruption
    |
    v
    7. Total RTO (Recovery Time Objective): ~2 minutes
       Total RPO (Recovery Point Objective): < 1 second

    POST-RECOVERY:
    8. When us-east-1 recovers:
       - Aurora: us-east-1 rejoins as read replica (auto)
       - Promote us-east-1 back to primary during maintenance window
       - Verify zero data loss via reconciliation job
    |
    v
    9. Reconciliation: compare us-east-1 ledger vs us-west-2 ledger
       Any in-flight transactions during failover:
       - If completed in us-west-2: already in DB (idempotency key match)
       - If lost: merchant retries with same idempotency key -> processed in us-west-2
       - Net result: zero double-charges, zero lost payments
```

---

## Disaster Recovery for Financial Systems

### DR Strategy: Active-Passive with Hot Standby

```
Financial systems require:
  RPO (Recovery Point Objective): < 1 second  -- cannot lose transactions
  RTO (Recovery Time Objective): < 2 minutes   -- payments must resume fast
  Auditability: 100% of transactions recoverable from logs

DR Tier Classification:
  Tier 1 (< 2 min RTO): Payment processing, ledger writes, idempotency
  Tier 2 (< 15 min RTO): Webhook delivery, merchant dashboard
  Tier 3 (< 1 hour RTO): Reconciliation, analytics, reporting
```

### Backup Strategy (Numbered)

```
    1. REAL-TIME REPLICATION (RPO < 1 second)
       Aurora Global Database:
         us-east-1 (writer) --> us-west-2 (reader)
         Replication lag: < 1 second (typically 200-500ms)
         Every ledger entry, every payment record replicated in real-time

    2. POINT-IN-TIME RECOVERY (RPO = any point in last 35 days)
       Aurora automated backups:
         Continuous backup to S3 (every 5 minutes)
         Restore to any second within 35-day window
         Use case: "Show me the ledger state at 3:47 PM yesterday"

    3. CROSS-REGION SNAPSHOTS (daily)
       Aurora: daily snapshot copied to us-west-2 and eu-west-1
       ElastiCache: daily RDB snapshot to S3, cross-region replicated
       CloudHSM: key material backed up to S3 (encrypted, cross-region)

    4. IMMUTABLE AUDIT LOG (7-year retention)
       Every payment operation logged:
         CloudTrail -> S3 (Object Lock, WORM)
         pgAudit -> CloudWatch Logs -> S3 (immutable)
         Application logs -> S3 (immutable)
       Cannot be deleted or modified (PCI-DSS + SOX compliance)

    5. TRANSACTION LOG SHIPPING
       Aurora WAL (Write-Ahead Log) shipped to S3 continuously
       Can replay WAL to reconstruct DB state at any point
       Use case: forensic investigation of disputed transaction

    6. RECONCILIATION AS DR VALIDATION
       Daily reconciliation job is also a DR validation:
         - Compares internal ledger vs bank statement
         - If they match: system is consistent
         - If mismatch: alert, investigate, correct
         - Proves backups are valid (not just "backed up" but "recoverable")
```

### DR Runbook: Complete Regional Failure

```
SCENARIO: us-east-1 completely unavailable (fire, network, AWS outage)

MINUTE 0:00 -- DETECTION
  - Route 53 health check fails (/v1/health returns non-200)
  - CloudWatch alarm fires: "Payment API 5xx rate > 50%"
  - PagerDuty alert to on-call payment engineer

MINUTE 0:30 -- AUTOMATIC FAILOVER
  - Route 53 DNS failover to us-west-2 (automatic)
  - Aurora Global: promote us-west-2 to writer (automatic)

MINUTE 1:00 -- TRAFFIC SHIFT
  - DNS TTL expires, clients route to us-west-2
  - us-west-2 Payment Service begins processing
  - Idempotency keys in DB prevent any double-processing

MINUTE 2:00 -- STABLE (RTO MET)
  - All new payments processed in us-west-2
  - Webhook delivery resumes (SQS regional, independent)
  - Fraud detection active (SageMaker endpoint warmed)
  - Cache warming: 5-10 min to reach steady-state hit rate

MINUTE 15:00 -- TIER 2 RECOVERY
  - Merchant dashboard DNS updated (CloudFront + S3 regional)
  - Webhook replay: re-send any webhooks from last 5 minutes
    (merchants built idempotent webhook handlers -- safe to replay)

MINUTE 60:00 -- TIER 3 RECOVERY
  - Reconciliation job runs in us-west-2
  - Analytics pipeline redirected to us-west-2 Kinesis
  - Reporting dashboards reconnected

POST-INCIDENT:
  - Run full reconciliation: compare ledger vs bank for failover window
  - Verify zero money lost, zero double-charges
  - Publish incident report with timeline
  - Update DR runbook with lessons learned
```

---

## Interview Tip

> "For a Stripe-scale payment system on AWS, I'd build a **PCI-DSS compliant architecture** with an isolated VPC, **CloudHSM** for card tokenization, and **KMS** for encryption at rest. The core is a **double-entry ledger** on Aurora PostgreSQL -- every payment creates balanced debit/credit entries, append-only, immutable. **Idempotency** is enforced at two layers: Redis for fast lookup, Aurora with a unique constraint as source of truth. Payments flow through a **fraud detection pipeline** (velocity checks + ML scoring) before authorization. **Webhooks** are delivered via SQS with HMAC signatures and exponential backoff retry. For availability, I use **Aurora Global Database** with active-passive failover -- RTO under 2 minutes, RPO under 1 second. Daily **reconciliation** via Step Functions matches our ledger against bank statements to catch discrepancies."

This shows you understand **PCI compliance, double-entry accounting, idempotency, fraud detection, and financial-grade disaster recovery** -- the five pillars of payment infrastructure.

---

## Quick Reference: Which Service When

| Decision Point | Service | Config | Why |
|---------------|---------|--------|-----|
| Card tokenization | CloudHSM (FIPS 140-2 Level 3) | HA pair in PCI VPC | PCI Req 3: protect stored cardholder data |
| Payment ledger | RDS Aurora PostgreSQL | Multi-AZ, 3 read replicas, encrypted | ACID for double-entry, append-only |
| Idempotency check | ElastiCache Redis + Aurora unique constraint | Redis TTL 24h, DB as fallback | Sub-ms dedup, DB as source of truth |
| Fraud detection | ECS + SageMaker real-time endpoint | Chain: rules first, ML if needed | Rules catch 80%, ML catches the rest |
| Webhook delivery | SQS + ECS workers | FIFO queue, 5 retries, exponential backoff | Guaranteed delivery with HMAC signatures |
| Settlement batch | Step Functions + Lambda | Daily at T+1, group by bank | Batch reduces bank API calls and fees |
| Reconciliation | Step Functions + Lambda + S3 | Daily: match ledger vs bank CSV | Catch missing/mismatched transactions |
| Audit trail | CloudTrail + pgAudit + S3 Object Lock | 7-year WORM retention | PCI + SOX compliance, forensic investigation |
| DR failover | Aurora Global + Route 53 failover | RTO < 2 min, RPO < 1 sec | Payment availability is non-negotiable |
| Multi-currency | Application-level exchange rate service | Rates cached in Redis, refreshed every 60s | Convert at authorization time, settle in merchant currency |
