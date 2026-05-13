# Distributed Systems Fundamentals -- Interview Reference

> **Purpose**: Theoretical foundation for system design interviews. Covers the
> distributed-systems primitives that underpin all 19 projects in this repo.
> Use this doc to answer "deep-dive" follow-ups an interviewer throws after
> you sketch the high-level architecture.
>
> **How to read**: Each section is self-contained. Jump to whichever topic the
> interviewer probes. Every section ends with interview soundbites and
> cross-references to the projects (P01-P19) that demonstrate the concept.

---

## Table of Contents

1. [Consensus Protocols](#1-consensus-protocols)
2. [Clocks and Ordering](#2-clocks-and-ordering)
3. [Consistency Models](#3-consistency-models)
4. [Distributed Transactions](#4-distributed-transactions)
5. [Replication](#5-replication)
6. [Partitioning / Sharding](#6-partitioning--sharding)
7. [Failure Detection and Gossip](#7-failure-detection-and-gossip)
8. [CAP and PACELC](#8-cap-and-pacelc)
9. [Exactly-Once Semantics](#9-exactly-once-semantics)
10. [Quick Reference and Interview Cheat Sheet](#10-quick-reference-and-interview-cheat-sheet)

---
---

# 1. Consensus Protocols

Consensus is the act of getting multiple nodes to agree on a single value
(or a sequence of values) despite crashes and network delays. It is the
hardest -- and most important -- primitive in distributed systems.

**Why it matters in interviews**: Every time you say "the leader decides" or
"we replicate the write", the interviewer can ask: "How do the nodes agree
on who the leader is?" The answer is a consensus protocol.

---

## 1.1 Raft

Raft was designed explicitly to be **understandable**. It decomposes
consensus into three sub-problems:

1. Leader election
2. Log replication
3. Safety (guaranteeing that committed entries are never lost)

### 1.1.1 Terminology

| Term          | Definition                                                    |
|---------------|---------------------------------------------------------------|
| **Term**      | Monotonically increasing integer. Acts as a logical clock.    |
| **Leader**    | The single node that accepts client writes in a given term.   |
| **Follower**  | Passive node that replicates the leader's log.                |
| **Candidate** | A follower that has timed out and started an election.        |
| **Log**       | Ordered sequence of (term, index, command) entries.           |
| **Commit**    | An entry is committed once the leader knows a majority has it.|

### 1.1.2 Leader Election -- Step by Step

```
  Time ─────────────────────────────────────────────────────────>

  Node A (Follower)  ──── election timeout fires ────> Candidate
                          term = term + 1
                          votes for self

  Node A ──── RequestVote(term=2, lastLogIndex, lastLogTerm) ───>
                                                          Node B
                                                          Node C

  Node B: "term 2 > my term 1, log is at least as up-to-date"
           grants vote, resets election timer

  Node C: same logic, grants vote

  Node A receives 2 votes (self + B) = majority of 3
         ──── becomes Leader ────
         immediately sends AppendEntries heartbeats
```

**Detailed flow**:

1. Every node starts as a Follower.
2. Each Follower has a randomized **election timeout** (e.g. 150-300 ms).
3. If a Follower receives no heartbeat before timeout, it becomes a Candidate.
4. The Candidate increments its **term** number and votes for itself.
5. The Candidate sends `RequestVote` RPCs to all other nodes.
6. A node grants its vote if:
   - The candidate's term >= the voter's current term.
   - The voter has not already voted in this term.
   - The candidate's log is **at least as up-to-date** as the voter's log.
     (Compared by last log entry: higher term wins; if equal term, longer log wins.)
7. If the Candidate receives votes from a **majority** (N/2 + 1), it becomes Leader.
8. The Leader immediately sends empty `AppendEntries` (heartbeats) to assert authority.
9. If a Candidate receives an `AppendEntries` from a node with term >= its own,
   it steps down to Follower.

### 1.1.3 Split Vote Handling

```
  ┌──────────────────────────────────────────────────────────┐
  │ 5-node cluster: A, B, C, D, E                           │
  │                                                          │
  │ A timeout fires  ──> Candidate (term=2), votes for self  │
  │ C timeout fires  ──> Candidate (term=2), votes for self  │
  │                                                          │
  │ B votes for A  (first RequestVote it received)           │
  │ D votes for C                                            │
  │ E votes for C                                            │
  │                                                          │
  │ A has 2 votes (A, B)  -- not majority (need 3)           │
  │ C has 3 votes (C, D, E) -- majority!                     │
  │ C becomes Leader for term 2                              │
  └──────────────────────────────────────────────────────────┘
```

If **neither** candidate gets a majority:

1. Both candidates' election timers expire again (randomized, so unlikely
   to fire simultaneously twice in a row).
2. One fires first, increments to term 3, and usually wins.
3. The randomized timeout breaks symmetry -- this is critical.

**Key insight**: Raft's randomized timers provide probabilistic liveness,
not deterministic. In practice, elections converge in 1-2 rounds.

### 1.1.4 Log Replication -- Step by Step

```
  Client ──── "SET x=5" ──── Leader (term=3)
                                │
                                ├── AppendEntries(term=3, prevLogIndex=4,
                                │     prevLogTerm=2, entries=[{index=5, term=3,
                                │     cmd="SET x=5"}], leaderCommit=4)
                                │
                                ├──> Follower B: log matches at prevLogIndex=4
                                │    appends entry, responds SUCCESS
                                │
                                ├──> Follower C: log matches at prevLogIndex=4
                                │    appends entry, responds SUCCESS
                                │
                                ├──> Follower D: log MISMATCHES at prevLogIndex=4
                                │    responds FAILURE
                                │    Leader decrements nextIndex for D, retries
                                │
                                Leader counts: 3 successes (self + B + C)
                                = majority of 5 nodes
                                ──> entry at index 5 is COMMITTED
                                ──> leader advances commitIndex to 5
                                ──> applies "SET x=5" to state machine
                                ──> responds OK to client
                                ──> next heartbeat tells followers to advance
                                    their commitIndex
```

**Log consistency invariants**:

1. If two entries in different logs have the same index and term, they
   contain the same command.
2. If two entries in different logs have the same index and term, all
   preceding entries are identical.

These invariants are enforced by the `prevLogIndex` / `prevLogTerm` check.
If a follower's log diverges, the leader walks back `nextIndex` until it
finds the point of agreement, then overwrites everything after that.

### 1.1.5 Safety Guarantees

```
  ┌──────────────────────────────────────────────────────────┐
  │ Election Restriction (most important safety property):   │
  │                                                          │
  │ A candidate CANNOT win an election unless its log        │
  │ contains all committed entries.                          │
  │                                                          │
  │ Why? Because a committed entry exists on a majority.     │
  │ A candidate needs votes from a majority.                 │
  │ These two majorities MUST overlap in at least one node.  │
  │ That overlapping node will refuse to vote for a          │
  │ candidate with a less up-to-date log.                    │
  │                                                          │
  │   Majority that has       Majority that voted            │
  │   committed entry E:      for new leader:                │
  │   {A, B, C}               {B, D, E}                     │
  │        └── B is in both sets ──┘                         │
  │   B will only vote for a candidate whose log             │
  │   includes entry E (or a later term).                    │
  └──────────────────────────────────────────────────────────┘
```

**Other safety properties**:

| Property              | Guarantee                                              |
|-----------------------|--------------------------------------------------------|
| Leader Completeness   | A committed entry appears in all future leaders' logs. |
| State Machine Safety  | If a node applies entry at index i, no other node      |
|                       | applies a different entry at index i.                  |
| Leader Append-Only    | A leader never overwrites or deletes its own entries.  |

### 1.1.6 Heartbeats and Failure Detection

```
  Leader ──── AppendEntries(entries=[]) ──── every 100ms ────>
                                                      Followers

  If a Follower does not receive a heartbeat for 150-300ms
  (randomized election timeout), it starts an election.

  Typical configuration:
    heartbeat interval:     100 ms
    election timeout:       150 - 300 ms  (randomized)
    MTBF of server:         months

  The election timeout MUST be >> heartbeat interval
  to avoid unnecessary elections.
```

### 1.1.7 Why Raft is Easier Than Paxos

1. **Single leader**: All decisions go through one node. No dueling proposers.
2. **Strong leader**: The leader's log is authoritative. Followers simply copy it.
3. **Randomized timers**: Replace the complex view-change sub-protocol of Paxos.
4. **Joint consensus**: Raft handles membership changes via a two-phase
   configuration change, which is easier to reason about than Paxos reconfiguration.
5. **Understandability was an explicit design goal**: The Raft paper (Ongaro & Ousterhout, 2014)
   ran user studies to prove it is easier to learn than Paxos.

### 1.1.8 Raft in Production

| System      | How It Uses Raft                                  |
|-------------|---------------------------------------------------|
| **etcd**    | Core consensus for Kubernetes cluster state.      |
| **Consul**  | Service catalog and KV store replication.         |
| **TiKV**    | Per-region Raft groups (Multi-Raft) for TiDB.    |
| **CockroachDB** | Per-range Raft groups for SQL replication.   |

---

## 1.2 Paxos

Paxos (Lamport, 1998) is the original consensus algorithm. It is
**provably correct** but notoriously difficult to understand and implement.

### 1.2.1 Roles

```
  ┌───────────┐     ┌───────────┐     ┌───────────┐
  │ Proposer  │     │ Acceptor  │     │  Learner  │
  │           │     │           │     │           │
  │ Proposes  │     │ Votes on  │     │ Learns    │
  │ values    │     │ proposals │     │ chosen    │
  │           │     │           │     │ value     │
  └───────────┘     └───────────┘     └───────────┘

  In practice, a single node often plays all three roles.
```

| Role         | Responsibility                                          |
|--------------|---------------------------------------------------------|
| **Proposer** | Proposes a value with a unique proposal number.         |
| **Acceptor** | Votes to accept or reject proposals. Must be durable.   |
| **Learner**  | Learns which value was chosen (majority accepted).      |

### 1.2.2 Single-Decree Paxos -- Prepare/Accept Phases

**Phase 1: Prepare**

```
  Proposer                          Acceptors (A1, A2, A3)
     │                                   │
     │── Prepare(n=5) ─────────────────> │
     │                                   │
     │  A1: n=5 > maxPrepare(3)          │
     │       promise: will not accept    │
     │       proposals < 5               │
     │       returns: (3, "value_X")     │
     │       (highest previously         │
     │        accepted proposal)         │
     │                                   │
     │  A2: n=5 > maxPrepare(1)          │
     │       promise: will not accept    │
     │       proposals < 5               │
     │       returns: (none)             │
     │                                   │
     │  A3: n=5 > maxPrepare(4)          │
     │       promise: will not accept    │
     │       proposals < 5               │
     │       returns: (4, "value_Y")     │
     │                                   │
     │<── Promise(5) ───────────────── A1│
     │<── Promise(5) ───────────────── A2│
     │<── Promise(5) ───────────────── A3│
```

1. Proposer picks a unique, monotonically increasing proposal number `n`.
2. Proposer sends `Prepare(n)` to a majority of Acceptors.
3. Each Acceptor:
   - If `n` > any previously seen proposal number:
     - Promises not to accept any proposal with number < `n`.
     - Returns the highest-numbered proposal it has already accepted (if any).
   - If `n` <= previously promised number: ignores the Prepare (or sends NACK).
4. If the Proposer receives promises from a majority, it proceeds to Phase 2.

**Phase 2: Accept**

```
  Proposer                          Acceptors (A1, A2, A3)
     │                                   │
     │  Received promises from majority  │
     │  Highest accepted value among     │
     │  promises: (4, "value_Y")         │
     │                                   │
     │  MUST propose "value_Y"           │
     │  (constrained by the protocol)    │
     │                                   │
     │── Accept(n=5, v="value_Y") ─────> │
     │                                   │
     │  A1: 5 >= maxPromise(5) -> accept │
     │  A2: 5 >= maxPromise(5) -> accept │
     │  A3: 5 >= maxPromise(5) -> accept │
     │                                   │
     │<── Accepted(5, "value_Y") ──── A1 │
     │<── Accepted(5, "value_Y") ──── A2 │
     │<── Accepted(5, "value_Y") ──── A3 │
     │                                   │
     │  Majority accepted -> "value_Y"   │
     │  is CHOSEN                        │
     │                                   │
     │── Notify Learners ────────────>   │
```

5. The Proposer picks the value:
   - If any Acceptor returned a previously accepted value, the Proposer
     **must** use the value from the highest-numbered accepted proposal.
   - If no Acceptor returned a previously accepted value, the Proposer
     can use its own value.
6. Proposer sends `Accept(n, v)` to a majority of Acceptors.
7. Each Acceptor: if `n` >= its current promise number, it accepts `(n, v)`.
8. If a majority of Acceptors accept, the value `v` is **chosen**.

### 1.2.3 Why Step 5 Matters (The Key Insight)

The constraint that a Proposer must adopt a previously accepted value is
what makes Paxos safe. Without it, two Proposers could get different values
chosen by different majorities:

```
  WITHOUT the constraint (UNSAFE):

  Proposer P1: Prepare(1) -> majority promises -> Accept(1, "A") -> A1, A2 accept
  Proposer P2: Prepare(2) -> majority promises -> Accept(2, "B") -> A2, A3 accept

  A1 has "A", A2 has both, A3 has "B" -> no single chosen value!

  WITH the constraint (SAFE):

  Proposer P1: Prepare(1) -> Accept(1, "A") -> A1, A2 accept -> "A" is chosen
  Proposer P2: Prepare(2) -> A2 returns (1, "A") in promise
               -> P2 MUST propose "A" -> Accept(2, "A") -> "A" is confirmed
```

### 1.2.4 Multi-Paxos for Log Replication

Single-decree Paxos chooses **one** value. To replicate a log (sequence of
commands), we run one Paxos instance per log slot:

```
  Log slot:   [  1  ] [  2  ] [  3  ] [  4  ] [  5  ]
  Paxos:       inst1    inst2   inst3   inst4   inst5
  Chosen:     "SET a"  "SET b" "DEL c" "SET a" "GET d"
```

**Optimization**: Elect a **distinguished proposer** (leader). The leader
skips Phase 1 for subsequent slots because Acceptors have already promised
to its proposal numbers. This reduces message complexity from 4 messages
per slot to 2 messages per slot (just the Accept phase).

This is essentially what Raft does, but Raft bakes it into the protocol
design rather than treating it as an optimization.

### 1.2.5 Why Paxos is Hard to Implement

1. **No distinguished leader in basic Paxos**: Dueling proposers can livelock.
   ```
   P1: Prepare(1) -> accepted
   P2: Prepare(2) -> supersedes P1's prepare
   P1: Accept(1, v) -> rejected (acceptors promised 2)
   P1: Prepare(3) -> supersedes P2's prepare
   P2: Accept(2, v) -> rejected (acceptors promised 3)
   ... infinite loop
   ```
2. **Multi-Paxos is underspecified**: The original paper describes
   single-decree Paxos. Multi-Paxos is an optimization that Lamport
   sketched but never fully specified. Every implementation fills in gaps
   differently.
3. **Reconfiguration**: Changing the set of Acceptors mid-protocol is
   extremely tricky. Raft's joint consensus is cleaner.
4. **Log gaps**: In Multi-Paxos, log entries can be chosen out of order,
   requiring gap-filling before entries can be applied.

### 1.2.6 Paxos in Production

| System              | How It Uses Paxos                                   |
|---------------------|-----------------------------------------------------|
| **Google Chubby**   | Lock service, relies on Multi-Paxos internally.     |
| **Google Spanner**  | Uses Paxos groups for replication across data centers.|
| **Apache Cassandra**| Lightweight transactions use Paxos (CAS operations). |
| **Google Megastore** | Per-entity-group Paxos for cross-datacenter writes. |

---

## 1.3 ZAB (ZooKeeper Atomic Broadcast)

ZAB is the consensus protocol behind **Apache ZooKeeper**. It is
specifically designed for **primary-backup replication** with ordered
broadcast, not general consensus.

### 1.3.1 How ZAB Differs from Raft/Paxos

| Property                | ZAB                         | Raft / Paxos              |
|-------------------------|-----------------------------|---------------------------|
| Primary purpose         | Atomic broadcast            | General consensus         |
| Ordering guarantee      | FIFO + causal + total order | Total order (log)         |
| Recovery protocol       | Explicit recovery phase     | Subsumed by election      |
| Client reads            | Ordered reads from leader   | Flexible (leader/follower)|

### 1.3.2 ZAB Phases

```
  Phase 0: Leader Election
  ┌─────────────────────────────────────────────────┐
  │ Similar to Raft: nodes exchange epoch numbers.  │
  │ Node with highest epoch (and longest log) wins. │
  │ "Epoch" in ZAB = "Term" in Raft.                │
  └─────────────────────────────────────────────────┘
            │
            v
  Phase 1: Discovery
  ┌─────────────────────────────────────────────────┐
  │ Prospective leader collects followers' history. │
  │ Leader determines the most up-to-date log.      │
  │ Leader proposes a new epoch to followers.        │
  │ Followers acknowledge the new epoch.             │
  └─────────────────────────────────────────────────┘
            │
            v
  Phase 2: Synchronization
  ┌─────────────────────────────────────────────────┐
  │ Leader sends its history to followers.          │
  │ Followers sync their logs to match the leader.  │
  │ Once a quorum is synchronized, leader is ready. │
  └─────────────────────────────────────────────────┘
            │
            v
  Phase 3: Broadcast (Steady State)
  ┌─────────────────────────────────────────────────┐
  │ Client sends write to leader.                   │
  │ Leader assigns a zxid (epoch + counter).        │
  │ Leader broadcasts PROPOSAL to all followers.    │
  │ Followers persist and send ACK.                 │
  │ Leader receives ACKs from quorum.               │
  │ Leader sends COMMIT to all followers.           │
  │ All nodes apply the transaction.                │
  └─────────────────────────────────────────────────┘
```

### 1.3.3 ZXID (ZooKeeper Transaction ID)

```
  zxid (64-bit):
  ┌──────────────────────┬──────────────────────┐
  │  epoch (high 32 bits)│  counter (low 32 bits)│
  └──────────────────────┴──────────────────────┘

  epoch:   incremented each time a new leader is elected
  counter: incremented for each transaction within an epoch

  Example: epoch=3, counter=42  ->  zxid = 0x000000030000002A
```

The zxid provides a **total order** on all transactions across all leaders.

### 1.3.4 Broadcast Protocol Detail

```
  Client ── write("SET /config/db_host = prod-db-1") ──> Leader

  Leader:
    1. Assigns zxid = (epoch=5, counter=17)
    2. Creates proposal P = (zxid, "SET /config/db_host = prod-db-1")

  Leader ── PROPOSAL(P) ──> Follower F1     (TCP, FIFO ordered)
  Leader ── PROPOSAL(P) ──> Follower F2
  Leader ── PROPOSAL(P) ──> Follower F3

  F1 ── ACK(zxid) ──> Leader       (F1 persisted to disk)
  F2 ── ACK(zxid) ──> Leader       (F2 persisted to disk)
  F3 ── (slow, no ACK yet)

  Leader: received ACKs from F1, F2 + self = 3 out of 4 = quorum

  Leader ── COMMIT(zxid) ──> F1, F2, F3
  Leader applies locally

  Note: COMMIT is sent to ALL followers (including F3 which was slow).
  F3 will apply the transaction when it processes the COMMIT in order.
```

### 1.3.5 How ZooKeeper Uses ZAB

ZooKeeper uses ZAB for:

1. **Configuration management**: Storing cluster metadata for Kafka, HBase, Hadoop.
2. **Leader election**: Ephemeral znodes + watches = leader election primitive.
   When a leader crashes, its ephemeral znode is deleted, watchers are notified.
3. **Distributed locks**: Sequential znodes + watches = fair locking.
4. **Service discovery**: Ephemeral znodes represent live services.

```
  ZooKeeper Ensemble (3 or 5 nodes):

  ┌──────────┐     ┌──────────┐     ┌──────────┐
  │  ZK Node │     │  ZK Node │     │  ZK Node │
  │  (Leader)│     │(Follower)│     │(Follower)│
  │          │     │          │     │          │
  │  ZAB     │<───>│  ZAB     │<───>│  ZAB     │
  │  Engine  │     │  Engine  │     │  Engine  │
  │          │     │          │     │          │
  │  ZNode   │     │  ZNode   │     │  ZNode   │
  │  Tree    │     │  Tree    │     │  Tree    │
  └──────────┘     └──────────┘     └──────────┘
       │                │                │
       └────────────────┴────────────────┘
              All trees are identical
              (replicated via ZAB)
```

---

## 1.4 Comparison Table: Raft vs Paxos vs ZAB

| Property                | Raft                  | Paxos                 | ZAB                   |
|-------------------------|-----------------------|-----------------------|-----------------------|
| **Year**                | 2014                  | 1989 (published 1998) | 2008                  |
| **Primary goal**        | Understandability     | Correctness proof     | Ordered broadcast     |
| **Leader required?**    | Yes (always)          | No (but Multi-Paxos   | Yes (always)          |
|                         |                       | uses one)             |                       |
| **Message complexity**  | 2 RPCs per entry      | 4 RPCs (basic) or     | 2 RPCs per entry      |
| **(steady state)**      | (AppendEntries +      | 2 RPCs (Multi-Paxos   | (PROPOSAL + COMMIT)   |
|                         | response)             | with stable leader)   |                       |
| **Election mechanism**  | Randomized timeouts   | Dueling proposers     | Epoch-based           |
|                         |                       | (no built-in election)| (highest zxid wins)   |
| **Split-brain**         | Term numbers +        | Proposal numbers +    | Epoch numbers +       |
| **prevention**          | majority vote         | majority promise      | quorum                |
| **Log gaps possible?**  | No                    | Yes (Multi-Paxos)     | No                    |
| **Ordering**            | Total order           | Per-instance          | Total + FIFO + causal |
| **Membership change**   | Joint consensus       | Not specified         | Dynamic reconfig      |
| **Ease of impl.**       | Moderate              | Very hard             | Hard                  |
| **Used by**             | etcd, Consul, TiKV   | Chubby, Spanner,      | ZooKeeper             |
|                         | CockroachDB           | Cassandra LWT         |                       |

### When to Use What (Interview Guidance)

- **Raft**: Default choice for new systems. Well-documented. Use when you need
  a replicated log and want something implementable.
- **Paxos**: Mention when discussing Google systems or when the interviewer
  asks about the theoretical foundation of consensus.
- **ZAB**: Mention specifically when discussing ZooKeeper-based coordination
  (e.g., Kafka leader election, HBase region assignment).

---

## 1.5 Interview Tie-ins

| Project | Connection to Consensus                                       |
|---------|---------------------------------------------------------------|
| **P07** | Distributed cache: consistent hashing decides placement, but  |
|         | leader election picks the coordinator. Raft/ZAB would be used |
|         | for the metadata store that tracks node membership.           |
| **P17** | Distributed task scheduler uses a Bully algorithm for leader  |
|         | election. In production, this would be replaced by Raft or    |
|         | ZAB-backed leader election (e.g., via ZooKeeper).             |
| **P19** | API Gateway: service mesh control plane (like Consul) uses    |
|         | Raft for consistent service catalog replication.              |
| **P16** | Stock trading: order book state must be replicated with        |
|         | strong consistency. Raft groups per symbol.                   |

### Interview Soundbites

> "Raft guarantees safety through the Election Restriction: a candidate
> cannot win unless its log contains all committed entries. This works
> because the two majorities -- the one that committed the entry and the
> one that votes -- must overlap."

> "Paxos is provably correct but Multi-Paxos is underspecified. Every
> production implementation (Chubby, Spanner) adds its own extensions.
> Raft achieves the same guarantees with a clearer decomposition into
> leader election, log replication, and safety."

> "ZAB provides not just consensus but total-ordered atomic broadcast
> with FIFO guarantees per client. That is why ZooKeeper can offer
> linearizable writes and ordered watches."

---
---

# 2. Clocks and Ordering

In a distributed system there is no global clock. Events on different
nodes cannot be ordered by wall-clock time alone. Logical clocks provide
ordering guarantees without relying on synchronized physical clocks.

**Why it matters in interviews**: "How do you order events across services?"
is a frequent follow-up when you describe any async architecture.

---

## 2.1 Lamport Timestamps

Proposed by Leslie Lamport (1978). The simplest logical clock.

### 2.1.1 Rules

Each node maintains a counter `C`:

1. Before executing a local event: `C = C + 1`
2. When sending a message: attach `C` to the message.
3. When receiving a message with timestamp `T`: `C = max(C, T) + 1`

### 2.1.2 Example with 3 Nodes

```
  Node A          Node B          Node C
  C=0             C=0             C=0
    │               │               │
  e1 (C=1)          │               │
  "create order"    │               │
    │               │               │
    │──msg(C=1)───> │               │
    │             e2 (C=2)          │
    │             "validate"        │
    │               │               │
    │               │──msg(C=2)───> │
    │               │             e3 (C=3)
    │               │             "charge payment"
    │               │               │
    │             e4 (C=3)          │
    │             "local log"       │
    │               │               │
    │               │──msg(C=3)───> │
    │               │             e5 (C=4)
    │               │             "confirm"
    │               │               │

  Ordering: e1(1) < e2(2) < e3(3) ≤ e4(3) < e5(4)
```

### 2.1.3 Happened-Before Relation

Lamport defined the **happened-before** relation (`->`) :

- If `a` and `b` are events in the same process, and `a` occurs before `b`,
  then `a -> b`.
- If `a` is the sending of a message and `b` is the receipt of that message,
  then `a -> b`.
- If `a -> b` and `b -> c`, then `a -> c` (transitivity).

**Key property**: If `a -> b`, then `C(a) < C(b)`.

**Limitation**: The converse is **NOT** true. If `C(a) < C(b)`, we **cannot**
conclude that `a -> b`. Events `a` and `b` might be concurrent.

```
  ┌─────────────────────────────────────────────────────┐
  │ Lamport timestamps:                                 │
  │   a -> b  implies  C(a) < C(b)     (TRUE)          │
  │   C(a) < C(b)  implies  a -> b     (FALSE!)        │
  │                                                     │
  │ Cannot detect concurrency. Two events with C=5 and  │
  │ C=7 might be concurrent if they are on different    │
  │ nodes with no causal chain between them.            │
  └─────────────────────────────────────────────────────┘
```

### 2.1.4 Total Order from Lamport Timestamps

To break ties (same timestamp on different nodes), use the node ID:

```
  Total order:  (timestamp, nodeId)
  (3, A) < (3, B) < (4, C)
```

This gives a **total order** that is **consistent with causality** but
may order concurrent events arbitrarily.

---

## 2.2 Vector Clocks

Vector clocks (Fidge, Mattern, 1988) extend Lamport timestamps to
**detect concurrency**.

### 2.2.1 Rules

Each node `i` in a system of `N` nodes maintains a vector `V[0..N-1]`:

1. Before executing a local event: `V[i] = V[i] + 1`
2. When sending a message: attach a copy of `V` to the message.
3. When receiving a message with vector `W`:
   - `V[j] = max(V[j], W[j])` for all `j`
   - `V[i] = V[i] + 1`

### 2.2.2 Comparison Rules

```
  V <= W    iff  V[i] <= W[i] for all i
  V < W     iff  V <= W  and  V != W        (V happened before W)
  V || W    iff  neither V < W nor W < V    (concurrent)
```

### 2.2.3 Example with 3 Nodes

```
  Node A            Node B            Node C
  V=[0,0,0]         V=[0,0,0]         V=[0,0,0]
    │                  │                  │
  e1: V=[1,0,0]       │                  │
  "write x=1"         │                  │
    │                  │                  │
    │──msg[1,0,0]───>  │                  │
    │                e2: V=[1,1,0]        │
    │                "read x=1"           │
    │                  │                  │
    │                  │                  │
  e3: V=[2,0,0]       │               e4: V=[0,0,1]
  "write x=2"         │               "write x=3"
    │                  │                  │
    │                  │──msg[1,1,0]───>  │
    │                  │               e5: V=[1,1,2]
    │                  │               "merge: x=?"
    │                  │                  │

  Analysis:
    e1=[1,0,0] < e2=[1,1,0]     (e1 happened before e2)  ✓
    e3=[2,0,0] || e4=[0,0,1]    (CONCURRENT! neither < other)
    e2=[1,1,0] < e5=[1,1,2]     (e2 happened before e5)  ✓
    e3=[2,0,0] || e5=[1,1,2]    (CONCURRENT!)

  When node C processes msg from B at e5, it knows e4 is
  concurrent with e2 because [0,0,1] || [1,1,0].
  This triggers conflict resolution.
```

### 2.2.4 Vector Clocks vs Lamport Timestamps

| Property                  | Lamport         | Vector Clock      |
|---------------------------|-----------------|-------------------|
| Size                      | Single integer  | N integers        |
| Detects causality?        | One direction   | Both directions   |
| Detects concurrency?      | No              | Yes               |
| Scalability               | Excellent       | O(N) per message  |
| Used by                   | Most systems    | Dynamo, Riak      |

### 2.2.5 Scaling Problem and Solutions

Vector clocks grow with the number of nodes. Solutions:

1. **Dotted Version Vectors**: Track only active writers, prune old entries.
   Used by Riak.
2. **Interval Tree Clocks**: Dynamic number of participants without
   fixed vector size.
3. **In practice**: Most systems with 100+ nodes abandon vector clocks
   and use HLCs or application-level causality tracking.

---

## 2.3 Hybrid Logical Clocks (HLC)

HLCs (Kulkarni et al., 2014) combine physical time with logical counters.
They provide:

- Causality tracking (like Lamport timestamps)
- Closeness to physical time (unlike pure logical clocks)
- Bounded size (unlike vector clocks)

### 2.3.1 Structure

```
  HLC = (physical_time, logical_counter)

  physical_time:   wall clock, coarsened to milliseconds
  logical_counter: resolves events within the same millisecond
```

### 2.3.2 Rules

Node `i` with physical clock `PT_i`:

**Local event or send**:
```
  l_new  = max(l_old, PT_i)
  if l_new == l_old:
      c_new = c_old + 1     // same physical time, increment logical
  else:
      c_new = 0             // physical time advanced, reset logical
  HLC = (l_new, c_new)
```

**Receive message with HLC (l_m, c_m)**:
```
  l_new = max(l_old, l_m, PT_i)
  if l_new == l_old == l_m:
      c_new = max(c_old, c_m) + 1
  elif l_new == l_old:
      c_new = c_old + 1
  elif l_new == l_m:
      c_new = c_m + 1
  else:
      c_new = 0
  HLC = (l_new, c_new)
```

### 2.3.3 Why HLC is Practical

```
  ┌──────────────────────────────────────────────────────────┐
  │ HLC Guarantees:                                          │
  │                                                          │
  │ 1. If e1 -> e2, then HLC(e1) < HLC(e2)                  │
  │    (causality preserved, like Lamport)                   │
  │                                                          │
  │ 2. |l - PT| is bounded by max clock skew                │
  │    (HLC stays close to physical time)                    │
  │                                                          │
  │ 3. Size is O(1) regardless of number of nodes            │
  │    (unlike vector clocks)                                │
  │                                                          │
  │ 4. Can be used for snapshot reads at a physical time     │
  │    (critical for MVCC databases)                         │
  └──────────────────────────────────────────────────────────┘
```

### 2.3.4 HLC in Production

| System         | How It Uses HLC                                        |
|----------------|--------------------------------------------------------|
| **CockroachDB**| Transaction timestamps for serializable snapshot       |
|                | isolation. HLC ensures causal ordering across nodes.   |
| **TiDB**       | TSO (Timestamp Oracle) provides centralized HLC.       |
| **MongoDB**    | Cluster time uses HLC for causal consistency sessions. |
| **YugabyteDB** | Hybrid time for MVCC and consistent snapshots.         |

---

## 2.4 Clock Skew in Distributed Tracing

**Tie-in to P18 (Observability Platform)**

When collecting distributed traces (spans from multiple services), clock
skew between machines can cause spans to appear out of order:

```
  Service A (clock: 10:00:00.000)
    ├── Span: "HTTP GET /api/orders"
    │   start: 10:00:00.100
    │   end:   10:00:00.300
    │
    └── calls Service B (clock ahead by 50ms)
            ├── Span: "DB query"
            │   start: 10:00:00.120  (should be AFTER 10:00:00.100
            │                         but appears only 20ms later
            │                         due to 50ms skew)
            │   end:   10:00:00.180

  Even worse, with clock behind:
    Service C (clock behind by 200ms)
            ├── Span: "cache lookup"
            │   start: 09:59:59.950  <-- APPEARS BEFORE parent span!
            │   end:   10:00:00.010
```

**Solutions used in practice**:

1. **NTP synchronization**: Keep clock skew < 10ms. Not always achievable.
2. **Span reference-based ordering**: Use parent span ID, not timestamps,
   to determine ordering. This is what Jaeger and Zipkin do.
3. **HLC for trace timestamps**: Attach HLC to each span, preserving
   causal order regardless of wall clock skew.
4. **Clock skew detection**: Tracing systems detect when a child span
   appears to start before its parent and adjust rendering.

### 2.4.1 NTP and Its Limits

```
  ┌──────────────────────────────────────────────────────────┐
  │ NTP (Network Time Protocol):                             │
  │                                                          │
  │ - Synchronizes clocks to UTC via hierarchy of servers    │
  │ - Typical accuracy: 1-10ms on LAN, 10-100ms on WAN      │
  │ - Cannot guarantee monotonicity (clock can jump back)    │
  │ - Leap seconds cause additional complications            │
  │                                                          │
  │ For most distributed systems, NTP is "good enough" but   │
  │ NOT sufficient for ordering guarantees.                   │
  └──────────────────────────────────────────────────────────┘
```

---

## 2.5 TrueTime (Google Spanner)

Google's TrueTime is the most sophisticated clock system in production.
It provides an **uncertainty interval** rather than a point-in-time.

### 2.5.1 Architecture

```
  ┌─────────────────────────────────────────────────────────┐
  │ Each Google datacenter has:                              │
  │                                                          │
  │   ┌──────────┐  ┌──────────┐                            │
  │   │   GPS    │  │  Atomic  │                            │
  │   │ Receiver │  │  Clock   │                            │
  │   └────┬─────┘  └────┬─────┘                            │
  │        │              │                                  │
  │        └──────┬───────┘                                  │
  │               │                                          │
  │        ┌──────┴──────┐                                   │
  │        │ Time Master │  (multiple per datacenter)        │
  │        └──────┬──────┘                                   │
  │               │                                          │
  │        ┌──────┴──────┐                                   │
  │        │  Time Daemon│  (on every server)                │
  │        │  (timeslave)│                                   │
  │        └─────────────┘                                   │
  │                                                          │
  │ GPS and atomic clocks are independent failure modes.     │
  │ GPS can be jammed; atomic clocks drift but don't jump.   │
  │ Using both provides redundancy.                          │
  └─────────────────────────────────────────────────────────┘
```

### 2.5.2 API

```
  TrueTime.now()  returns  TTinterval = [earliest, latest]

  Example: TrueTime.now() = [10:00:00.001, 10:00:00.007]

  The actual time is GUARANTEED to be within this interval.
  Uncertainty (epsilon) = (latest - earliest) / 2
  Typical epsilon: 1-7 ms (average ~4ms)
```

### 2.5.3 Commit-Wait

Spanner uses TrueTime for **external consistency** (linearizability across
datacenters):

```
  Transaction T1 commits:
    1. T1 picks commit timestamp s1 = TrueTime.now().latest
    2. T1 WAITS until TrueTime.now().earliest > s1
       (This is the "commit-wait" -- typically ~7ms)
    3. T1 is now committed and visible

  Transaction T2 starts after T1 commits:
    4. T2 picks start timestamp s2 = TrueTime.now().latest
    5. Because of commit-wait: s2 > s1 (guaranteed)
    6. T2 sees T1's writes (guaranteed)

  ┌──────────────────────────────────────────────────────────┐
  │ The commit-wait ensures that any transaction that        │
  │ STARTS after T1 COMMITS will have a higher timestamp.    │
  │ This is external consistency / linearizability.          │
  │                                                          │
  │ Cost: ~7ms latency per write transaction.                │
  │ Benefit: Global consistency without coordination.         │
  └──────────────────────────────────────────────────────────┘
```

### 2.5.4 Why TrueTime is Unique

No other public system has replicated TrueTime:

- Requires GPS receivers and atomic clocks in every datacenter.
- Requires custom hardware and software stack.
- CockroachDB approximates this with HLC + bounded clock skew assumptions.
- Spanner trades a few ms of write latency for global linearizability.

---

## 2.6 Interview Tie-ins

| Project | Connection to Clocks & Ordering                              |
|---------|---------------------------------------------------------------|
| **P04** | Chat system: message ordering across participants requires    |
|         | Lamport timestamps or HLC. "Message A was sent before B"     |
|         | must be consistent across all participants.                   |
| **P14** | Real-time collaboration: OT/CRDT operations need causal      |
|         | ordering. Vector clocks detect concurrent edits.              |
| **P17** | Task scheduler: task state transitions need causal ordering   |
|         | to prevent applying stale updates.                            |
| **P18** | Observability platform: distributed traces rely on clock      |
|         | synchronization for span ordering. Clock skew is real.        |

### Interview Soundbites

> "Lamport timestamps give you a total order consistent with causality,
> but they cannot tell you if two events are concurrent. For that, you
> need vector clocks."

> "Vector clocks detect concurrency but are O(N) in size. HLCs give you
> causality tracking with O(1) size by piggybacking on physical time.
> That is why CockroachDB uses HLC."

> "Google Spanner uses TrueTime with GPS + atomic clocks to get global
> linearizability. The key trick is commit-wait: after committing, wait
> until the uncertainty interval passes before making the write visible."

---
---

# 3. Consistency Models

A consistency model defines the **contract** between a distributed data
store and its clients about what values reads can return. Stronger models
are easier to program against but harder (and slower) to implement.

**Why it matters in interviews**: "What consistency model would you choose?"
is one of the most common follow-ups. You need to name the model, explain
the tradeoff, and justify why it fits the use case.

---

## 3.1 The Consistency Spectrum

```
  Strongest ◄──────────────────────────────────── Weakest

  Linearizability
       │
  Sequential Consistency
       │
  Causal Consistency
       │
  Read-your-writes
       │
  Monotonic Reads
       │
  Eventual Consistency

  Stronger = easier to reason about, harder to scale
  Weaker   = harder to reason about, easier to scale
```

---

## 3.2 Linearizability

The **strongest** single-object consistency model. Informally: "the system
behaves as if there is only one copy of the data, and all operations are
atomic and instantaneous."

### 3.2.1 Formal Definition

A history of operations is linearizable if:

1. Each operation appears to take effect **atomically** at some point
   between its invocation and its response.
2. That point is called the **linearization point**.
3. The resulting sequence of operations is consistent with a **sequential
   specification** of the object (e.g., a register, a queue).

### 3.2.2 Visual Example

```
  Client A:  |---write(x=1)---|
  Client B:         |---read(x)---|  returns 1 ✓

  Time ──────────────────────────────────────────>

  The write appears to take effect at some point (★) during its execution.
  The read starts after ★, so it must return 1.

  Client A:  |---write(x=1)---|
  Client B:  |---read(x)---|              returns 0 ✓ (read completed before ★)

  Client A:  |---write(x=1)-----------|
  Client B:         |---read(x)---|    returns 0 or 1 (overlap, both valid)

  ┌──────────────────────────────────────────────────────────┐
  │ Non-linearizable example:                                │
  │                                                          │
  │ Client A:  |---write(x=1)---|                            │
  │ Client B:         |---read(x)---|  returns 1             │
  │ Client C:             |---read(x)---|  returns 0         │
  │                                                          │
  │ VIOLATION! C starts after B, and B saw x=1.              │
  │ C cannot see x=0 (that would mean the write "un-happened") │
  └──────────────────────────────────────────────────────────┘
```

### 3.2.3 Real-time Ordering

The critical property that distinguishes linearizability from sequential
consistency: **real-time ordering**.

If operation A completes before operation B begins (in real wall-clock time),
then A must appear before B in the linearization. This is the "real-time"
constraint.

### 3.2.4 Cost of Linearizability

```
  ┌──────────────────────────────────────────────────────────┐
  │ Linearizability requires coordination:                   │
  │                                                          │
  │ - Reads may need to contact the leader or quorum         │
  │ - Writes must be replicated to a majority before ack     │
  │ - Cross-datacenter: latency = round-trip time            │
  │ - Not available during network partitions (CAP: CP)      │
  │                                                          │
  │ Typical read latency:                                    │
  │   Same datacenter:   1-5 ms                              │
  │   Cross datacenter:  50-200 ms                           │
  └──────────────────────────────────────────────────────────┘
```

### 3.2.5 Systems That Provide Linearizability

| System       | Scope                                              |
|--------------|----------------------------------------------------|
| **etcd**     | All reads and writes (via Raft leader)              |
| **ZooKeeper**| Writes (reads can be stale unless using `sync`)     |
| **Spanner**  | All reads and writes (via TrueTime + 2PC)           |
| **DynamoDB** | Only with strongly consistent reads (costs 2x RCU)  |

---

## 3.3 Sequential Consistency

Weaker than linearizability. Drops the real-time ordering requirement.

### 3.3.1 Definition

A history is sequentially consistent if there exists a **total order** of all
operations such that:

1. Each process's operations appear in this total order in the same order
   they were issued (**program order** is preserved).
2. Every read returns the value written by the most recent write in this
   total order.

**Key difference from linearizability**: The total order does NOT need to
respect real-time ordering. Operations can be reordered across processes
as long as each process's internal order is preserved.

### 3.3.2 Visual Example

```
  Client A:  write(x=1) at T=1,  write(x=2) at T=3
  Client B:  read(x) at T=2,     read(x) at T=4

  Linearizable: read at T=2 must return 1 (write at T=1 completed before it)
                read at T=4 must return 2

  Sequentially consistent: valid total order could be:
    write(x=2), read(x)->2, write(x=1), read(x)->1
    (A's order preserved: write(x=1) before write(x=2) is VIOLATED!)

  Actually valid:
    write(x=1), write(x=2), read(x)->2, read(x)->2  ✓
    write(x=1), read(x)->1, write(x=2), read(x)->2  ✓
    Both respect A's program order (write 1 before write 2)
    and B's program order (first read before second read).

  NOT linearizable but sequentially consistent:
    Client A: write(x=1) at T=1
    Client B: write(x=2) at T=2
    Client C: read(x) at T=3 -> returns 1  (sees A's write, not B's)
    Client D: read(x) at T=3 -> returns 1  (same)

    Total order: write(x=2), write(x=1), read(x)->1, read(x)->1
    This is sequentially consistent (all agree on order: 2 then 1)
    But NOT linearizable (B's write at T=2 happened after A's at T=1,
    so B's write should be "later" in real time, but reads see A's value)
```

### 3.3.3 Where It Matters

Sequential consistency is what most multi-threaded programs assume. The
Java Memory Model provides sequential consistency for **volatile** variables.
In distributed systems, ZooKeeper provides sequential consistency for
reads from followers (writes are linearizable).

---

## 3.4 Causal Consistency

Preserves the ordering of **causally related** operations but allows
concurrent operations to be seen in different orders by different nodes.

### 3.4.1 Definition

If operation A **happened before** operation B (in the Lamport sense), then
every node must see A before B. Concurrent operations (neither happened
before the other) can be observed in any order.

### 3.4.2 Visual Example

```
  Alice posts: "I got the job!"           (event A)
  Bob sees Alice's post and replies:
    "Congratulations!"                    (event B, caused by A)

  Causal consistency guarantees:
    Every user sees Alice's post BEFORE Bob's reply.
    Nobody sees "Congratulations!" without seeing "I got the job!" first.

  Carol, concurrently, posts:
    "Beautiful weather today"             (event C, concurrent with A and B)

  User 1 might see: A, C, B  (Carol's post between Alice and Bob)
  User 2 might see: C, A, B  (Carol's post before Alice)
  Both are valid under causal consistency.

  INVALID: seeing B before A (Bob's reply before Alice's post)
```

### 3.4.3 Implementing Causal Consistency

```
  ┌──────────────────────────────────────────────────────────┐
  │ Option 1: Vector Clocks                                  │
  │   Each write carries a vector clock.                     │
  │   A replica delays applying a write until all            │
  │   causally preceding writes have been applied.           │
  │   Used by: Riak, early Dynamo prototypes.                │
  │                                                          │
  │ Option 2: Explicit Dependency Tracking                   │
  │   Each write records which prior writes it depends on.   │
  │   A replica applies a write only after all dependencies  │
  │   are satisfied.                                         │
  │   Used by: COPS (research system), MongoDB causal sessions│
  │                                                          │
  │ Option 3: Serialization through a single node            │
  │   Route all causally related writes through the same     │
  │   partition. Within a partition, order is total.          │
  │   Used by: Kafka (per-partition ordering).               │
  └──────────────────────────────────────────────────────────┘
```

### 3.4.4 Causal Consistency in Practice

| System      | How It Provides Causal Consistency                     |
|-------------|--------------------------------------------------------|
| **MongoDB** | Causal consistency sessions with cluster time (HLC).   |
|             | Within a session, reads reflect prior writes.          |
| **Kafka**   | Per-partition ordering is stronger than causal for      |
|             | messages within a partition.                           |

---

## 3.5 Eventual Consistency

The **weakest** useful consistency model. Guarantees only that if no new
writes are made, all replicas will **eventually** converge to the same value.

### 3.5.1 Definition

```
  ┌──────────────────────────────────────────────────────────┐
  │ If no new updates are made to a given data item,         │
  │ eventually all accesses to that item will return the     │
  │ last updated value.                                      │
  │                                                          │
  │ "Eventually" = there is no bound on how long.            │
  │ In practice, convergence happens in milliseconds to      │
  │ seconds, not hours.                                      │
  └──────────────────────────────────────────────────────────┘
```

### 3.5.2 What Can Go Wrong

```
  Client writes x=5 to Node A
  Client immediately reads x from Node B
  Node B has not yet received the replication -> returns x=3 (stale!)

  Timeline:
    T=0:  x=3 on all nodes
    T=1:  Client writes x=5 to Node A
    T=2:  Client reads x from Node B -> returns 3 (stale!)
    T=3:  Replication: Node A -> Node B (x=5)
    T=4:  Client reads x from Node B -> returns 5 (correct)

  Between T=1 and T=3, the system is inconsistent.
  Eventual consistency says this is ALLOWED.
```

### 3.5.3 Conflict Resolution

When multiple nodes accept writes concurrently, conflicts arise.
Resolution strategies:

```
  1. Last-Writer-Wins (LWW):
     Use timestamp to pick the "latest" write. Simple but loses data.
     Example: Cassandra default.

     Node A: write(x=5) at T=100
     Node B: write(x=7) at T=101
     Conflict resolution: x=7 wins (higher timestamp)
     Problem: if timestamps are from different clocks, "latest" is arbitrary.

  2. Application-level resolution:
     Return all conflicting versions (siblings) to the client.
     Let the application merge them.
     Example: Riak with allow_mult=true.

     Node A: write(cart={apple}) at T=100
     Node B: write(cart={banana}) at T=101
     Read: returns [{apple}, {banana}] -> app merges to {apple, banana}

  3. CRDTs (Conflict-free Replicated Data Types):
     Data structures that mathematically guarantee convergence.
     No conflicts possible.
     Example: Riak data types, Redis CRDTs.
     See P14 (real-time collaboration).
```

### 3.5.4 Systems That Provide Eventual Consistency

| System         | Default Consistency | Tunable?                      |
|----------------|--------------------|-----------------------------|
| **DynamoDB**   | Eventually consistent reads | Yes, can request strongly consistent |
| **Cassandra**  | Tunable via quorum | R=1,W=1 = eventual; R+W>N = strong |
| **S3**         | Strong read-after-write (since Dec 2020) | Was eventual |
| **DNS**        | Eventual (TTL-based) | No                         |

---

## 3.6 Practical Session Guarantees

These are weaker than linearizability but stronger than pure eventual
consistency. They provide useful guarantees within a **client session**.

### 3.6.1 Read-Your-Writes

```
  ┌──────────────────────────────────────────────────────────┐
  │ If a client writes a value, subsequent reads by the      │
  │ SAME client will see that write (or a later one).        │
  │                                                          │
  │ Implementation:                                          │
  │ - Client remembers its last write timestamp.             │
  │ - On read, client sends this timestamp to the server.    │
  │ - Server ensures it returns data at least as fresh as    │
  │   that timestamp (may route to leader or wait for        │
  │   replication).                                          │
  │                                                          │
  │ Example: After updating your profile picture, you see    │
  │ the new picture (even if other users see the old one     │
  │ for a few seconds).                                      │
  └──────────────────────────────────────────────────────────┘
```

### 3.6.2 Monotonic Reads

```
  ┌──────────────────────────────────────────────────────────┐
  │ If a client reads a value v at time T, subsequent reads  │
  │ by the same client will never return a value older than v│
  │                                                          │
  │ VIOLATION example (without monotonic reads):             │
  │   Read 1 (from replica A): x=5 (up to date)             │
  │   Read 2 (from replica B): x=3 (stale!)                 │
  │   The value appears to go BACKWARDS. Confusing!          │
  │                                                          │
  │ Implementation:                                          │
  │ - Stick client to one replica (sticky sessions).         │
  │ - Or: client tracks highest version seen, server         │
  │   ensures returned version >= tracked version.           │
  └──────────────────────────────────────────────────────────┘
```

### 3.6.3 Monotonic Writes

```
  ┌──────────────────────────────────────────────────────────┐
  │ If a client writes A then B, every replica applies       │
  │ A before B (or not at all, if B fails).                  │
  │                                                          │
  │ Without monotonic writes:                                │
  │   Replica 1 might apply B then A (reordering!)           │
  │   This can break invariants (e.g., adding $10 then       │
  │   withdrawing $8 -- if withdrawal applied first,         │
  │   balance goes negative).                                │
  └──────────────────────────────────────────────────────────┘
```

### 3.6.4 Session Consistency

Combines read-your-writes + monotonic reads within a session. Most
practical systems aim for at least session consistency:

- **DynamoDB**: Via DynamoDB Streams and session tokens.
- **Cosmos DB**: Offers "Session" consistency level explicitly.
- **MongoDB**: Causal consistency sessions provide session guarantees.

---

## 3.7 Comparison Table

| Model               | Ordering Guarantee              | Availability | Latency  | Example Systems              |
|----------------------|---------------------------------|-------------|----------|------------------------------|
| **Linearizability**  | Real-time + sequential          | Low (CP)    | High     | etcd, ZooKeeper, Spanner     |
| **Sequential**       | Per-process program order       | Medium      | Medium   | ZooKeeper reads              |
| **Causal**           | Causally related ops ordered    | Medium-High | Medium   | MongoDB sessions             |
| **Read-your-writes** | Own writes visible              | High        | Low      | Most web apps                |
| **Monotonic reads**  | No going back in time           | High        | Low      | Sticky sessions              |
| **Eventual**         | All replicas converge eventually| Highest     | Lowest   | DynamoDB, Cassandra, DNS     |

---

## 3.8 Consistency Models Across the 19 Projects

| Project | Component              | Consistency Model | Why                              |
|---------|------------------------|-------------------|----------------------------------|
| **P01** | URL mapping            | Eventual          | Read-heavy, stale reads OK       |
| **P02** | Rate limit counters    | Eventual          | Approximate counts are fine      |
| **P04** | Chat message ordering  | Causal             | Messages must be causally ordered|
| **P05** | Feed generation        | Eventual          | Stale feed is acceptable         |
| **P07** | Cache entries          | Eventual          | Cache is inherently best-effort  |
| **P10** | Inventory count        | Linearizable      | Cannot oversell                  |
| **P11** | Payment ledger         | Linearizable      | Double-spend must be prevented   |
| **P14** | Document state         | Causal             | Concurrent edits need causal order|
| **P15** | File metadata          | Linearizable      | File must not be lost            |
| **P16** | Order book             | Linearizable      | Trades must be exactly ordered   |
| **P17** | Task state             | Linearizable (CP) | Task must not be double-executed |
| **P18** | Metrics aggregation    | Eventual (AP)     | Approximate metrics are fine     |
| **P19** | Service registry       | Eventual (AP)     | Stale routing is better than none|

---

## 3.9 Interview Soundbites

> "Linearizability provides real-time ordering: if operation A completes
> before B starts, everyone sees A before B. Sequential consistency relaxes
> this -- it only preserves per-process order."

> "In practice, most systems don't need linearizability everywhere. A social
> media feed (P05, P12) is fine with eventual consistency. But a payment
> ledger (P11) or inventory counter (P10) needs linearizability to prevent
> double-spending or overselling."

> "Causal consistency is the sweet spot for many applications. It prevents
> the 'reply before the original message' problem without the latency cost
> of linearizability."

---
---

# 4. Distributed Transactions

A distributed transaction spans multiple nodes or services. The challenge:
how to ensure atomicity (all-or-nothing) when any participant can fail.

**Why it matters in interviews**: Every microservices design needs to handle
cross-service state changes. "How do you ensure consistency across services?"
demands a concrete answer: 2PC, Saga, or outbox pattern.

---

## 4.1 Two-Phase Commit (2PC)

The classic distributed commit protocol. Provides atomicity but at the
cost of availability.

### 4.1.1 Roles

```
  ┌──────────────┐
  │ Coordinator  │  (Transaction Manager)
  │              │  Decides commit or abort.
  └──────┬───────┘
         │
    ┌────┴────┬──────────┐
    │         │          │
  ┌─┴──┐   ┌─┴──┐   ┌──┴──┐
  │ P1 │   │ P2 │   │ P3  │  Participants (Resource Managers)
  └────┘   └────┘   └─────┘  Each holds a piece of the transaction.
```

### 4.1.2 Protocol -- Step by Step

```
  Phase 1: PREPARE (Voting Phase)
  ════════════════════════════════

  Coordinator                     Participants (P1, P2, P3)
      │                                │
      │── PREPARE ──────────────────> P1│
      │── PREPARE ──────────────────> P2│
      │── PREPARE ──────────────────> P3│
      │                                │
      │  P1: acquires locks, writes    │
      │      redo/undo log to disk     │
      │      responds: VOTE_COMMIT     │
      │                                │
      │  P2: acquires locks, writes    │
      │      redo/undo log to disk     │
      │      responds: VOTE_COMMIT     │
      │                                │
      │  P3: cannot acquire lock       │
      │      responds: VOTE_ABORT      │
      │                                │
      │<── VOTE_COMMIT ──────────── P1 │
      │<── VOTE_COMMIT ──────────── P2 │
      │<── VOTE_ABORT  ──────────── P3 │

  Phase 2: COMMIT/ABORT (Decision Phase)
  ════════════════════════════════════════

      │  Decision: at least one ABORT  │
      │  -> GLOBAL ABORT               │
      │                                │
      │── ABORT ────────────────────> P1│  (P1 rolls back)
      │── ABORT ────────────────────> P2│  (P2 rolls back)
      │── ABORT ────────────────────> P3│  (P3 already aborted)
      │                                │
      │<── ACK ─────────────────── all │
      │  Transaction complete (aborted)│


  ALTERNATIVE: all vote COMMIT
  ════════════════════════════

      │  Decision: all voted COMMIT    │
      │  -> GLOBAL COMMIT              │
      │                                │
      │── COMMIT ───────────────────> P1│  (P1 makes changes permanent)
      │── COMMIT ───────────────────> P2│  (P2 makes changes permanent)
      │── COMMIT ───────────────────> P3│  (P3 makes changes permanent)
      │                                │
      │<── ACK ─────────────────── all │
      │  Transaction complete (committed)│
```

### 4.1.3 The Coordinator Failure Problem

```
  ┌──────────────────────────────────────────────────────────┐
  │ CRITICAL FLAW of 2PC:                                    │
  │                                                          │
  │ If the coordinator crashes AFTER receiving votes          │
  │ but BEFORE sending the decision:                         │
  │                                                          │
  │   P1: voted COMMIT, holding locks... waiting...          │
  │   P2: voted COMMIT, holding locks... waiting...          │
  │   Coordinator: CRASHED                                   │
  │                                                          │
  │ Participants are BLOCKED:                                │
  │   - Cannot commit (don't know if all voted COMMIT)       │
  │   - Cannot abort (coordinator might have decided COMMIT) │
  │   - MUST hold locks until coordinator recovers           │
  │                                                          │
  │ This makes 2PC a BLOCKING protocol.                      │
  │ In the worst case, participants hold locks indefinitely.  │
  └──────────────────────────────────────────────────────────┘
```

### 4.1.4 Handling Coordinator Failure in Practice

1. **Timeout + Coordinator election**: Use Raft/Paxos to elect a new
   coordinator. New coordinator queries all participants for their state.
2. **Presumed abort**: If the coordinator's log does not contain a COMMIT
   record, assume ABORT. Reduces log writes.
3. **Cooperative termination**: If all participants can communicate, they
   can determine the outcome without the coordinator (unless one participant
   is also down).

### 4.1.5 2PC in Production

| System             | How It Uses 2PC                                   |
|--------------------|---------------------------------------------------|
| **MySQL XA**       | XA transactions across multiple MySQL instances.  |
| **PostgreSQL**     | `PREPARE TRANSACTION` for two-phase commit.       |
| **Google Spanner** | 2PC across Paxos groups for cross-shard txns.     |
| **Oracle RAC**     | Distributed transactions within the cluster.      |

---

## 4.2 Three-Phase Commit (3PC)

Addresses the blocking problem of 2PC by adding a **pre-commit** phase.

### 4.2.1 Protocol

```
  Phase 1: CAN-COMMIT (same as 2PC Prepare)
  ════════════════════════════════════════════

  Coordinator ── CAN_COMMIT? ──> Participants
  Participants ── YES/NO ──> Coordinator

  Phase 2: PRE-COMMIT (NEW phase)
  ═══════════════════════════════

  If all voted YES:
    Coordinator ── PRE_COMMIT ──> Participants
    Participants persist the decision, respond ACK
    (At this point, participants know the decision is COMMIT)

  If any voted NO:
    Coordinator ── ABORT ──> Participants

  Phase 3: DO-COMMIT
  ═══════════════════

  Coordinator ── DO_COMMIT ──> Participants
  Participants make changes permanent, respond ACK

  ┌──────────────────────────────────────────────────────────┐
  │ Key insight: After PRE-COMMIT, participants know the     │
  │ decision is COMMIT. If the coordinator crashes, a new    │
  │ coordinator can query participants and learn the decision.│
  │                                                          │
  │ If no participant received PRE-COMMIT, the new           │
  │ coordinator can safely ABORT.                            │
  └──────────────────────────────────────────────────────────┘
```

### 4.2.2 Why 3PC is Not Used in Practice

```
  ┌──────────────────────────────────────────────────────────┐
  │ Problem: Network partitions break 3PC.                   │
  │                                                          │
  │ Scenario:                                                │
  │   Coordinator sends PRE_COMMIT to P1, then crashes.     │
  │   P2 never received PRE_COMMIT.                         │
  │                                                          │
  │   New coordinator on P2's partition: "No one here        │
  │   received PRE_COMMIT, so ABORT."                        │
  │                                                          │
  │   P1 (on other partition): "I received PRE_COMMIT,      │
  │   timeout, so COMMIT."                                   │
  │                                                          │
  │   Result: P1 committed, P2 aborted. INCONSISTENCY!      │
  │                                                          │
  │ 3PC is non-blocking only under crash failures,           │
  │ NOT under network partitions.                            │
  │ Since network partitions are real, 3PC is impractical.   │
  └──────────────────────────────────────────────────────────┘
```

---

## 4.3 Saga Pattern

Sagas replace distributed transactions with a sequence of **local
transactions**, each with a **compensating action** for rollback.

### 4.3.1 Concept

```
  Traditional distributed transaction:
    BEGIN TRANSACTION
      deduct inventory (Service A)
      charge payment (Service B)
      create shipment (Service C)
    COMMIT

  Saga:
    T1: deduct inventory  (compensate: restore inventory)
    T2: charge payment    (compensate: refund payment)
    T3: create shipment   (compensate: cancel shipment)

    If T2 fails:
      C1: restore inventory (compensate T1)
      -> no need to compensate T2 (it failed)
      -> T3 never started
```

### 4.3.2 Choreography vs Orchestration

**Choreography**: Each service listens for events and acts. No central
coordinator.

```
  ┌──────────┐   OrderCreated   ┌──────────┐  PaymentCharged  ┌──────────┐
  │  Order   │ ──────────────>  │ Payment  │ ─────────────>   │ Shipping │
  │ Service  │                  │ Service  │                  │ Service  │
  └──────────┘                  └──────────┘                  └──────────┘
       │                              │                            │
       │   ShipmentFailed             │  PaymentRefunded           │
       │ <────────────────────────────┤ <──────────────────────────┤
       │                              │                            │
  Each service publishes events. Each service reacts to events.
  No central brain. Decentralized.
```

**Advantages**:
- No single point of failure.
- Services are loosely coupled.

**Disadvantages**:
- Hard to understand the overall flow (event spaghetti).
- Hard to add new steps or change ordering.
- Hard to implement timeout and retry logic.
- Debugging requires correlating events across services.

**Orchestration**: A central **Saga Orchestrator** tells each service
what to do and handles compensations.

```
  ┌──────────────────────────────────────────────────┐
  │               Saga Orchestrator                  │
  │  (state machine: STARTED -> PAYING -> SHIPPING)  │
  └─────┬──────────────┬──────────────┬──────────────┘
        │              │              │
        │ deductStock  │ chargeCard   │ createShipment
        v              v              v
  ┌──────────┐  ┌──────────┐  ┌──────────┐
  │ Inventory│  │ Payment  │  │ Shipping │
  │ Service  │  │ Service  │  │ Service  │
  └──────────┘  └──────────┘  └──────────┘

  Orchestrator sends commands and receives results.
  On failure, orchestrator sends compensation commands in reverse order.
```

**Advantages**:
- Clear, centralized flow logic.
- Easy to add new steps.
- Easy to implement retries, timeouts, deadlines.
- Easy to debug (orchestrator has full state).

**Disadvantages**:
- Orchestrator is a potential single point of failure (mitigate with HA).
- Risk of putting too much logic in the orchestrator.

### 4.3.3 Compensation Example (P10: E-Commerce)

```
  Saga: PlaceOrder
  ═══════════════

  Step 1: ReserveInventory
    Action:      inventory.reserve(orderId, items)
    Compensate:  inventory.release(orderId, items)

  Step 2: ProcessPayment
    Action:      payment.charge(orderId, amount)
    Compensate:  payment.refund(orderId, amount)

  Step 3: CreateShipment
    Action:      shipping.create(orderId, address)
    Compensate:  shipping.cancel(orderId)

  Step 4: ConfirmOrder
    Action:      order.confirm(orderId)
    Compensate:  order.reject(orderId)

  ┌──────────────────────────────────────────────────────────┐
  │ Happy path:                                              │
  │   Step 1 OK -> Step 2 OK -> Step 3 OK -> Step 4 OK      │
  │   Order confirmed!                                       │
  │                                                          │
  │ Failure at Step 3:                                       │
  │   Step 1 OK -> Step 2 OK -> Step 3 FAIL                  │
  │   Compensate Step 2: refund payment                      │
  │   Compensate Step 1: release inventory                   │
  │   Order rejected.                                        │
  │                                                          │
  │ Key: compensations run in REVERSE order.                 │
  └──────────────────────────────────────────────────────────┘
```

### 4.3.4 Saga Challenges

```
  1. SEMANTIC ROLLBACK:
     Cannot truly "undo" all side effects.
     Example: a notification was already sent to the user.
     Compensation might send "Sorry, order cancelled" notification.

  2. ISOLATION:
     Between T1 and T2, other transactions can see intermediate state.
     Example: inventory is reserved but payment has not been charged yet.
     Other transactions see reduced inventory.
     Mitigation: use semantic locks (mark inventory as "reserved, pending").

  3. IDEMPOTENCY:
     Compensations and actions must be idempotent (might be retried).
     Use idempotency keys (see Section 9).

  4. ORDERING:
     With choreography, events might arrive out of order.
     Mitigation: include sequence numbers or use orchestration.
```

---

## 4.4 Transactional Outbox Pattern

Solves the dual-write problem: how to atomically update a database AND
publish an event.

### 4.4.1 The Problem

```
  ┌──────────────────────────────────────────────────────────┐
  │ Dual-write problem:                                      │
  │                                                          │
  │   1. Write to database:    INSERT INTO orders ...        │
  │   2. Publish to Kafka:     producer.send("OrderCreated") │
  │                                                          │
  │   What if step 2 fails? Database has the order but       │
  │   Kafka does not. Downstream services never learn about  │
  │   the order.                                             │
  │                                                          │
  │   What if step 1 succeeds, step 2 succeeds, but the     │
  │   service crashes before acknowledging? On retry,        │
  │   step 1 might fail (duplicate key), step 2 might        │
  │   publish again (duplicate event).                       │
  └──────────────────────────────────────────────────────────┘
```

### 4.4.2 Solution: Outbox Table

```
  ┌──────────────────────────────────────────────────────────┐
  │ Instead of publishing directly, write events to an       │
  │ OUTBOX table in the SAME database transaction:           │
  │                                                          │
  │   BEGIN TRANSACTION                                      │
  │     INSERT INTO orders (id, ...) VALUES (...);           │
  │     INSERT INTO outbox (id, event_type, payload)         │
  │       VALUES (uuid, 'OrderCreated', '{...}');            │
  │   COMMIT                                                 │
  │                                                          │
  │ A separate process reads the outbox and publishes:       │
  │                                                          │
  │   Outbox Relay (polling or CDC):                         │
  │     1. Read unpublished events from outbox table         │
  │     2. Publish to Kafka                                  │
  │     3. Mark events as published (or delete them)         │
  └──────────────────────────────────────────────────────────┘
```

### 4.4.3 Architecture Diagram

```
  ┌──────────┐    BEGIN TXN      ┌──────────────────────┐
  │  Order   │ ──────────────>   │     Database          │
  │ Service  │   INSERT order    │  ┌──────────────────┐│
  │          │   INSERT outbox   │  │  orders table    ││
  │          │   COMMIT          │  ├──────────────────┤│
  └──────────┘                   │  │  outbox table    ││
                                 │  │  ┌────────────┐  ││
                                 │  │  │ id: abc123 │  ││
                                 │  │  │ type: ...  │  ││
                                 │  │  │ payload: {}│  ││
                                 │  │  │ published: │  ││
                                 │  │  │   false    │  ││
                                 │  │  └────────────┘  ││
                                 │  └──────────────────┘│
                                 └──────────┬───────────┘
                                            │
                                   ┌────────┴────────┐
                                   │  Outbox Relay   │
                                   │  (CDC / Polling)│
                                   └────────┬────────┘
                                            │
                                            v
                                   ┌─────────────────┐
                                   │     Kafka       │
                                   │  OrderCreated   │
                                   └─────────────────┘
```

### 4.4.4 CDC vs Polling

| Approach    | How It Works                         | Pros                | Cons                 |
|-------------|--------------------------------------|---------------------|----------------------|
| **Polling** | `SELECT * FROM outbox WHERE          | Simple to implement | Polling interval     |
|             | published=false` every N seconds     |                     | = delivery delay.    |
|             |                                      |                     | DB load from polling.|
| **CDC**     | Use change data capture (e.g.,       | Near real-time.     | More infrastructure. |
|             | Debezium reads MySQL binlog or       | No polling overhead.| CDC connector setup. |
|             | Postgres WAL) to capture inserts     |                     |                      |
|             | to the outbox table.                 |                     |                      |

### 4.4.5 Outbox Pattern in Practice

| System / Tool     | Role                                            |
|-------------------|-------------------------------------------------|
| **Debezium**      | CDC connector for MySQL/Postgres/MongoDB -> Kafka|
| **Kafka Connect** | Runs Debezium connectors.                       |
| **Eventuate Tram**| Java framework implementing outbox pattern.     |

---

## 4.5 Comparison Table: 2PC vs 3PC vs Saga

| Property         | 2PC                   | 3PC                   | Saga                   |
|------------------|-----------------------|-----------------------|------------------------|
| **Atomicity**    | Strong (all-or-nothing)| Strong               | Eventual (compensations)|
| **Isolation**    | Full (locks held)     | Full (locks held)     | None (intermediate     |
|                  |                       |                       | states visible)        |
| **Blocking?**    | Yes (coordinator crash)| No (under crash-stop)| No                     |
| **Network**      | Requires reliable     | Fails under           | Tolerates partitions   |
| **partitions?**  | network               | partitions            |                        |
| **Latency**      | High (synchronous)    | Higher (3 rounds)     | Low (async steps)      |
| **Complexity**   | Moderate              | High                  | High (compensations)   |
| **Scalability**  | Low (lock contention) | Low                   | High                   |
| **Use case**     | Database clusters     | Rarely used           | Microservices          |
| **Example**      | Spanner, MySQL XA     | (theoretical)         | P10 (e-commerce)       |

### When to Use What (Interview Guidance)

- **2PC**: Use when all participants are within a single database cluster
  or tightly controlled environment. E.g., Google Spanner combines 2PC with
  Paxos to get both atomicity and fault tolerance.
- **3PC**: Mention only to explain why it does not work under network
  partitions. Never recommend it.
- **Saga**: Default choice for microservices. Use orchestration for complex
  flows (P10), choreography for simple event chains.
- **Outbox**: Use alongside Sagas to solve the dual-write problem. Critical
  for reliable event-driven architectures.

---

## 4.6 Interview Tie-ins

| Project | Connection to Distributed Transactions                       |
|---------|---------------------------------------------------------------|
| **P10** | E-Commerce uses Saga (orchestration) for order placement:     |
|         | reserve inventory -> charge payment -> create shipment.       |
|         | Each step has a compensation action for rollback.             |
| **P11** | Payment system uses idempotency keys + transactional outbox   |
|         | to ensure exactly-once payment processing.                    |
| **P16** | Stock trading: order matching is a single-node operation      |
|         | (no distributed txn needed for matching), but settlement      |
|         | across exchanges would use 2PC or Saga.                       |

### Interview Soundbites

> "2PC is a blocking protocol: if the coordinator crashes after collecting
> votes but before sending the decision, participants hold locks
> indefinitely. That is why we prefer Sagas for microservices."

> "Sagas sacrifice isolation for availability. Between step 1 and step 2,
> the system is in an intermediate state. We handle this with semantic locks
> -- marking resources as 'reserved, pending confirmation'."

> "The transactional outbox pattern solves the dual-write problem. Instead
> of writing to the database AND Kafka, write to the database only (including
> an outbox table), and let a CDC connector relay events to Kafka."

---
---

# 5. Replication

Replication copies data across multiple nodes for **fault tolerance** and
**read scalability**. The fundamental tradeoff: stronger consistency
requires more coordination, which hurts latency and availability.

---

## 5.1 Single-Leader Replication

One node is the **leader** (primary/master). All writes go through the
leader. Followers (replicas/secondaries) replicate the leader's log.

### 5.1.1 Architecture

```
  ┌────────────────────────────────────────────────────┐
  │                    Clients                         │
  │         writes ↓            reads ↓  ↓  ↓         │
  │                                                    │
  │        ┌────────┐                                  │
  │        │ Leader │ ─── replication log ──>           │
  │        │ (R/W)  │                      │           │
  │        └────────┘                      │           │
  │             │                          │           │
  │     ┌───────┼───────┐                  │           │
  │     │       │       │                  │           │
  │  ┌──┴───┐ ┌─┴────┐ ┌┴──────┐          │           │
  │  │Follow│ │Follow│ │Follow │          │           │
  │  │er 1  │ │er 2  │ │er 3   │          │           │
  │  │(R)   │ │(R)   │ │(R)    │          │           │
  │  └──────┘ └──────┘ └───────┘          │           │
  │                                        │           │
  │  Reads can go to any replica           │           │
  │  (with possible replication lag)       │           │
  └────────────────────────────────────────────────────┘
```

### 5.1.2 Replication Modes

```
  Synchronous Replication:
  ════════════════════════
    Leader waits for follower ACK before confirming write to client.
    + No data loss on leader failure.
    - Higher write latency. One slow follower blocks all writes.

  Asynchronous Replication:
  ═════════════════════════
    Leader confirms write to client immediately, replicates in background.
    + Low write latency.
    - Follower may lag. If leader fails, recent writes may be lost.

  Semi-Synchronous:
  ═════════════════
    Leader waits for ACK from ONE follower (not all).
    At least one follower is always up-to-date.
    Used by: MySQL semi-sync replication, PostgreSQL synchronous_commit.
```

### 5.1.3 Failover

When the leader fails, a follower must be promoted. This is **hard**:

```
  ┌──────────────────────────────────────────────────────────┐
  │ Failover steps:                                          │
  │                                                          │
  │ 1. DETECT the leader is down (timeout-based).            │
  │ 2. ELECT a new leader (most up-to-date follower).        │
  │ 3. RECONFIGURE clients to send writes to new leader.     │
  │ 4. Handle STALE reads from other followers.              │
  │                                                          │
  │ Failure scenarios:                                        │
  │                                                          │
  │ - Split brain: old leader comes back, two leaders exist. │
  │   Solution: fencing tokens (see Section 7.3).            │
  │                                                          │
  │ - Lost writes: if async replication, last writes on old  │
  │   leader may not have replicated. Conflict with new      │
  │   leader's writes after promotion.                       │
  │   Solution: discard unreplicated writes (data loss) or   │
  │   conflict resolution.                                   │
  │                                                          │
  │ - GitHub incident (2012): MySQL failover caused          │
  │   auto-increment IDs to collide with Redis cache,        │
  │   disclosing private repos to wrong users.               │
  └──────────────────────────────────────────────────────────┘
```

### 5.1.4 Single-Leader Systems

| System          | Notes                                          |
|-----------------|------------------------------------------------|
| **MySQL**       | Default replication mode. Semi-sync available. |
| **PostgreSQL**  | Streaming replication. Sync commit optional.   |
| **MongoDB**     | Replica set with primary + secondaries.        |
| **Redis**       | Master-replica replication. Sentinel for failover.|
| **Kafka**       | Per-partition leader. ISR for durability.       |

---

## 5.2 Multi-Leader Replication

Multiple nodes accept writes. Used for multi-datacenter deployments where
a single leader would add cross-datacenter latency to every write.

### 5.2.1 Architecture

```
  Datacenter 1              Datacenter 2              Datacenter 3
  ┌──────────────┐          ┌──────────────┐          ┌──────────────┐
  │   Leader 1   │ <──────> │   Leader 2   │ <──────> │   Leader 3   │
  │   (R/W)      │  async   │   (R/W)      │  async   │   (R/W)      │
  │              │ repl.    │              │ repl.    │              │
  │  ┌────────┐  │          │  ┌────────┐  │          │  ┌────────┐  │
  │  │Follower│  │          │  │Follower│  │          │  │Follower│  │
  │  └────────┘  │          │  └────────┘  │          │  └────────┘  │
  └──────────────┘          └──────────────┘          └──────────────┘
```

### 5.2.2 Conflict Resolution

When two leaders accept concurrent writes to the same key:

```
  Leader 1: write(x=5) at T=100 (DC1 wall clock)
  Leader 2: write(x=7) at T=101 (DC2 wall clock)

  When replicated to each other, CONFLICT!

  Resolution strategies:
  ═══════════════════════

  1. Last-Writer-Wins (LWW):
     Pick the write with the higher timestamp.
     x=7 wins.
     PROBLEM: T=100 and T=101 are from different clocks.
     The "earlier" write (T=100) might actually have happened later.
     DATA LOSS: x=5 is silently discarded.

  2. Merge function (application-specific):
     Application provides a merge function.
     merge(x=5, x=7) = application-defined result.
     Example for counters: merge(+5, +7) = +12 (additive merge).

  3. CRDTs (Conflict-free Replicated Data Types):
     Data structures that have a mathematically defined merge.
     See Section 5.5 below.

  4. Custom conflict resolution:
     Store all conflicting versions. Let the user resolve.
     Example: Google Docs shows conflicting edits inline.
```

### 5.2.3 Topology

```
  Star (hub-and-spoke):         Ring:                  All-to-all (mesh):
  ┌──┐                        ┌──┐                    ┌──┐
  │L1│                        │L1│──> ┌──┐            │L1│──> ┌──┐
  └──┘                        └──┘    │L2│            └──┘    │L2│
   │  ↘                         ↑     └──┘──> ┌──┐     │  ↗   └──┘
  ┌──┐  ┌──┐                   │              │L3│    ┌──┐  ↗
  │L2│  │L3│                   └──────────────└──┘    │L3│
  └──┘  └──┘                                          └──┘

  All-to-all is most resilient (no single point of failure)
  but has the most replication traffic.
```

### 5.2.4 Multi-Leader Systems

| System        | Use Case                                            |
|---------------|-----------------------------------------------------|
| **CouchDB**   | Multi-master replication for offline-first apps.    |
| **MySQL**     | Group Replication (multi-primary mode).              |
| **Active Directory** | Multi-master replication across domain controllers.|
| **Google Docs**| Each client is effectively a "leader" for its local edits.|

---

## 5.3 Leaderless Replication

No distinguished leader. Any node can accept reads and writes. Consistency
is achieved through **quorum** operations.

### 5.3.1 Quorum Reads/Writes

```
  N = total number of replicas
  W = number of nodes that must acknowledge a write
  R = number of nodes that must respond to a read

  Quorum condition:  R + W > N

  ┌──────────────────────────────────────────────────────────┐
  │ Example: N=3, W=2, R=2                                  │
  │                                                          │
  │ Write x=5 to nodes A, B (W=2 ACKs received)             │
  │ Node C has stale data (x=3)                              │
  │                                                          │
  │ Read from nodes B, C (R=2 responses)                     │
  │ B returns x=5, C returns x=3                             │
  │ Client picks the most recent version: x=5                │
  │                                                          │
  │ Why this works: the write went to {A,B}. The read        │
  │ contacted {B,C}. They overlap at B. So the read is       │
  │ guaranteed to see the latest write.                      │
  │                                                          │
  │   Write quorum: {A, B}                                   │
  │   Read quorum:  {B, C}                                   │
  │   Overlap:      {B}  <-- has the latest value            │
  └──────────────────────────────────────────────────────────┘
```

### 5.3.2 Quorum Configurations

| Configuration     | W | R | Guarantees                           | Use Case            |
|-------------------|---|---|--------------------------------------|---------------------|
| **Strong (N=3)**  | 2 | 2 | R+W=4>3. Read always sees latest.    | Default             |
| **Write-heavy**   | 1 | 3 | Fast writes, slow reads.             | Logging, metrics    |
| **Read-heavy**    | 3 | 1 | Slow writes, fast reads.             | Read-heavy workload |
| **Weak (N=3)**    | 1 | 1 | R+W=2, NOT > 3. May read stale data. | AP: high availability|

### 5.3.3 Sloppy Quorum and Hinted Handoff

```
  ┌──────────────────────────────────────────────────────────┐
  │ Strict quorum: write MUST go to the designated W nodes.  │
  │ If a node is down, the write FAILS (unavailable).        │
  │                                                          │
  │ Sloppy quorum: if a designated node is down, write to    │
  │ ANY available node instead (a "hint" node).              │
  │ The hint node temporarily stores the data.               │
  │ When the original node recovers, the hint node sends     │
  │ the data back. This is "hinted handoff."                 │
  │                                                          │
  │ Sloppy quorum improves AVAILABILITY but weakens the      │
  │ consistency guarantee (the R+W>N overlap might not hold  │
  │ because the hint node is not one of the N designated     │
  │ nodes).                                                  │
  └──────────────────────────────────────────────────────────┘

  Normal operation:
    Write to A, B (of {A, B, C})  -> OK

  Node B is down:
    Strict quorum:  Write fails (can't reach W=2 of {A,B,C})
    Sloppy quorum:  Write to A and D (hint node)
                    D stores: "This data belongs to B"
                    When B recovers, D sends the data to B (hinted handoff)
```

### 5.3.4 Read Repair and Anti-Entropy

```
  Read Repair:
  ════════════
    During a quorum read, if one replica returns stale data,
    the client (or coordinator) sends the latest value to the stale replica.

    Read from A (x=5), B (x=5), C (x=3)
    -> Return x=5 to client
    -> Send x=5 to C (read repair)

  Anti-Entropy (Merkle Trees):
  ════════════════════════════
    Background process compares data across replicas using Merkle trees.
    Only transfers the data that differs.
    Cassandra and DynamoDB use this.

    Merkle tree:
              [hash(AB+CD)]
              /            \
        [hash(A+B)]    [hash(C+D)]
        /       \       /       \
      [A]     [B]     [C]     [D]

    Compare root hashes. If they differ, drill down to find divergent ranges.
    Efficient: O(log N) comparisons to find differences.
```

### 5.3.5 Leaderless Systems

| System          | Notes                                            |
|-----------------|--------------------------------------------------|
| **DynamoDB**    | Dynamo-style leaderless replication.              |
| **Cassandra**   | Tunable consistency (ANY to ALL).                 |
| **Riak**        | Dynamo-style with CRDTs and vector clocks.        |
| **Voldemort**   | LinkedIn's Dynamo clone (now retired).            |

---

## 5.4 Replication Lag

Even with single-leader replication, followers lag behind the leader.
This causes anomalies:

### 5.4.1 Read-After-Write Anomaly

```
  User writes profile update to Leader
  User reads profile from Follower (lagging)
  -> sees OLD profile! "Where did my update go?!"

  ┌────────┐  write  ┌────────┐       ┌────────┐
  │ Client │ ──────> │ Leader │ ────> │Follower│  (lag: 500ms)
  │        │         │ x=NEW  │       │ x=OLD  │
  │        │ read    │        │       │        │
  │        │ ──────────────────────── │ x=OLD  │  <- STALE!
  └────────┘                          └────────┘

  Solutions:
    1. Read from leader for data the user recently wrote.
    2. Track last write timestamp, read from follower only if
       follower is caught up past that timestamp.
    3. Use session consistency guarantees (Section 3.6).
```

### 5.4.2 Monotonic Read Anomaly

```
  Read 1: from Follower A (caught up) -> x=5
  Read 2: from Follower B (lagging)   -> x=3  <- went BACKWARDS!

  Solution: sticky sessions (route user to same follower).
```

### 5.4.3 Consistent Prefix Read Anomaly

```
  Causal sequence: "Is it raining?" -> "Yes, it is."

  Observer reads from two partitions:
    Partition 1 (caught up):  "Yes, it is."
    Partition 2 (lagging):    "Is it raining?"

  The observer sees the answer before the question!

  Solution: ensure causally related writes go to the same partition,
  or use causal consistency (Section 3.4).
```

---

## 5.5 CRDTs (Conflict-free Replicated Data Types)

CRDTs are data structures that can be replicated across nodes and updated
independently, with a mathematically guaranteed merge that always converges.

**Tie-in to P14 (Real-time Collaboration)**

### 5.5.1 Types of CRDTs

```
  State-based CRDTs (CvRDT):
  ═══════════════════════════
    Each replica stores the full state.
    Replicas periodically send their full state to others.
    Merge function: join (least upper bound) in a semilattice.
    Requirement: merge must be commutative, associative, idempotent.

  Operation-based CRDTs (CmRDT):
  ══════════════════════════════
    Replicas send OPERATIONS (deltas) to others.
    Operations must be commutative (order doesn't matter).
    Requires reliable broadcast (all ops eventually delivered).
```

### 5.5.2 Common CRDTs

| CRDT              | Type         | Use Case                              |
|-------------------|-------------|---------------------------------------|
| **G-Counter**     | Grow-only    | Like counts, view counts              |
| **PN-Counter**    | Pos/Neg      | Inventory counts (add and subtract)   |
| **G-Set**         | Grow-only    | Tag sets, "likes"                     |
| **OR-Set**        | Observed-remove | Shopping cart (add and remove items) |
| **LWW-Register**  | Last-writer-wins| Simple key-value (with data loss risk)|
| **RGA**           | Replicated growable array | Collaborative text editing |

### 5.5.3 G-Counter Example

```
  3-node system: A, B, C
  Each node maintains its own counter entry.

  State: {A: 0, B: 0, C: 0}

  A increments 3 times:  {A: 3, B: 0, C: 0}
  B increments 2 times:  {A: 0, B: 2, C: 0}  (B's local view)
  C increments 1 time:   {A: 0, B: 0, C: 1}  (C's local view)

  Merge (element-wise max):
    {A: max(3,0,0), B: max(0,2,0), C: max(0,0,1)}
    = {A: 3, B: 2, C: 1}

  Total count: 3 + 2 + 1 = 6

  This works because:
    - Each node only increments its own entry (no conflicts).
    - Merge is commutative, associative, idempotent.
    - Converges regardless of message ordering or duplication.
```

---

## 5.6 Interview Tie-ins

| Project | Connection to Replication                                    |
|---------|---------------------------------------------------------------|
| **P07** | Distributed cache uses consistent hashing for placement and   |
|         | replicates each key to N successor nodes for fault tolerance. |
| **P14** | Real-time collaboration uses CRDTs (RGA for text, OR-Set     |
|         | for element collections) to resolve concurrent edits.        |
| **P04** | Chat system: message history replicated across servers.       |
|         | Single-leader per conversation for ordering.                 |
| **P13** | Video streaming: video chunks replicated to CDN edge nodes.  |
|         | Eventual consistency is fine (stale CDN cache = serve old    |
|         | video for a few seconds, not a critical issue).              |
| **P18** | Observability platform: metrics replicated for durability.    |
|         | Eventual consistency is acceptable for metrics.              |

### Interview Soundbites

> "Single-leader replication is the default. It gives you strong consistency
> for writes but the leader is a bottleneck and a single point of failure.
> Failover is the hardest part."

> "Leaderless replication with quorum (R+W>N) gives you tunable consistency.
> For an AP system like a distributed cache (P07), I'd use W=1, R=1 for
> speed and accept stale reads."

> "CRDTs are the gold standard for conflict-free multi-leader replication.
> In P14 (real-time collaboration), we use an RGA CRDT for the document
> text -- concurrent inserts always converge without coordination."

---
---

# 6. Partitioning / Sharding

Partitioning (sharding) splits data across multiple nodes so that each
node stores a subset. This enables **horizontal scaling**: more nodes =
more storage and throughput.

---

## 6.1 Hash Partitioning

Assign each key to a partition based on its hash value.

### 6.1.1 Simple Hash Partitioning

```
  partition = hash(key) % N     (N = number of partitions)

  Example (N=4):
    hash("user:123") = 7429     -> 7429 % 4 = 1  -> Partition 1
    hash("user:456") = 3812     -> 3812 % 4 = 0  -> Partition 0
    hash("user:789") = 9901     -> 9901 % 4 = 1  -> Partition 1

  Problem: when N changes (add/remove node), almost ALL keys must be
  remapped. hash(key) % 5 != hash(key) % 4 for most keys.
```

### 6.1.2 Consistent Hashing

Consistent hashing maps both keys and nodes onto a **hash ring** (circular
hash space). Each key is assigned to the first node encountered when
walking clockwise from the key's position.

```
  Hash ring (0 to 2^32 - 1):

                        0 / 2^32
                         │
                    Node A (pos=100)
                    ╱
                   ╱
                  ╱
   Node D ──────         ────── Node B (pos=800)
   (pos=3000)    ╲       ╱
                  ╲     ╱
                   ╲   ╱
                    ╲ ╱
                   Node C (pos=1500)

  Key "user:123" hashes to 500 -> walk clockwise -> lands on Node B (800)
  Key "user:456" hashes to 900 -> walk clockwise -> lands on Node C (1500)
  Key "user:789" hashes to 2800 -> walk clockwise -> lands on Node D (3000)

  Adding Node E at position 1200:
    Only keys between 800 and 1200 move from Node C to Node E.
    All other keys stay put.
    Expected fraction of keys that move: 1/N  (much better than hash % N)
```

### 6.1.3 Virtual Nodes (VNodes)

```
  Problem: with few physical nodes, the ring is unbalanced.
  Node A might own 60% of the ring, Node B only 10%.

  Solution: each physical node gets M virtual nodes (VNodes),
  spread around the ring.

  ┌──────────────────────────────────────────────────────────┐
  │ Physical Node A -> VNodes: A1(100), A2(700), A3(2200)   │
  │ Physical Node B -> VNodes: B1(300), B2(1100), B3(2800)  │
  │ Physical Node C -> VNodes: C1(500), C2(1500), C3(3200)  │
  │                                                          │
  │ Ring positions:                                          │
  │ 100(A1) 300(B1) 500(C1) 700(A2) 1100(B2) 1500(C2)     │
  │ 2200(A3) 2800(B3) 3200(C1)                             │
  │                                                          │
  │ Each physical node owns ~1/3 of the ring.               │
  │ With M=100-200 vnodes per node, distribution is          │
  │ very even.                                               │
  └──────────────────────────────────────────────────────────┘
```

**Tie-in to P07 (Distributed Cache)**: The distributed cache project uses
consistent hashing with virtual nodes for key placement.

### 6.1.4 Hotspot Mitigation

Even with consistent hashing, hotspots occur when certain keys receive
disproportionate traffic (e.g., a viral tweet):

```
  Mitigation strategies:

  1. Key splitting: Append a random suffix to hot keys.
     "tweet:12345" -> "tweet:12345:0", "tweet:12345:1", ..., "tweet:12345:9"
     Spreads 1 hot key across 10 partitions.
     Reads must query all 10 and merge results.

  2. Caching: Put a cache (Redis, Memcached) in front of hot partitions.
     Cache absorbs the read traffic.

  3. Rate limiting: Throttle writes to hot keys.

  4. Application-level awareness: P05 (social media feed) handles
     celebrity accounts differently (fan-out-on-read for celebrities
     instead of fan-out-on-write).
```

---

## 6.2 Range Partitioning

Assign contiguous key ranges to partitions. Enables efficient **range scans**.

### 6.2.1 How It Works

```
  Keys are sorted. Each partition owns a range:

  Partition 0: [A - F]     (keys starting with A through F)
  Partition 1: [G - M]
  Partition 2: [N - S]
  Partition 3: [T - Z]

  Or with numeric keys:
  Partition 0: [0 - 999]
  Partition 1: [1000 - 1999]
  Partition 2: [2000 - 2999]

  Range query "SELECT * WHERE key BETWEEN 500 AND 1500":
    -> touches Partition 0 and Partition 1 only.
    (Hash partitioning would touch ALL partitions.)
```

### 6.2.2 Boundary Selection

```
  ┌──────────────────────────────────────────────────────────┐
  │ Fixed boundaries: simple but may be skewed.              │
  │   [A-F], [G-M], [N-S], [T-Z]                            │
  │   If most keys start with 'S', Partition 2 is huge.      │
  │                                                          │
  │ Adaptive boundaries: split partitions when they get too   │
  │ large. Merge when they get too small.                     │
  │   Start: [A-Z] (one partition)                           │
  │   Split: [A-M], [N-Z] (when first partition too large)   │
  │   Split: [A-F], [G-M], [N-Z]                            │
  │   Used by: HBase, Google Bigtable, CockroachDB.          │
  └──────────────────────────────────────────────────────────┘
```

### 6.2.3 Rebalancing

When adding a node:

```
  Before (3 nodes):
    Node 1: [A-I]
    Node 2: [J-R]
    Node 3: [S-Z]

  After adding Node 4:
    Node 1: [A-F]      (gave [G-I] to Node 4)
    Node 2: [J-R]
    Node 3: [S-Z]
    Node 4: [G-I]

  Only Node 1 transfers data. Other nodes unaffected.
```

### 6.2.4 Range Partitioning in Practice

| System       | How It Uses Range Partitioning                     |
|--------------|-----------------------------------------------------|
| **HBase**    | Regions are contiguous key ranges. Auto-splits at    |
|              | configurable size. Region servers host regions.      |
| **Bigtable** | Tablets are contiguous row ranges.                   |
| **CockroachDB** | Ranges (64MB default) split and merge dynamically.|
| **MongoDB**  | Range-based sharding is one of two options (the     |
|              | other is hash-based).                               |

---

## 6.3 Comparison: Hash vs Range Partitioning

| Property            | Hash Partitioning         | Range Partitioning        |
|---------------------|---------------------------|---------------------------|
| **Key distribution**| Uniform (with good hash)  | Depends on key distribution|
| **Range queries**   | Requires scatter-gather   | Efficient (single partition)|
| **Hotspots**        | Possible (popular keys)   | Possible (popular ranges) |
| **Rebalancing**     | VNodes make it smooth     | Split/merge ranges        |
| **Ordering**        | No ordering preserved     | Keys are sorted           |
| **Implementation**  | Consistent hash ring      | Sorted key ranges + index |
| **Best for**        | Point lookups, KV stores  | Range scans, time-series  |

### When to Use What (Interview Guidance)

- **Hash partitioning**: Default for key-value stores, caches, and systems
  with mostly point lookups. P07 (distributed cache), P19 (API gateway
  consistent hash load balancing).
- **Range partitioning**: Use when range scans are important. Time-series
  data (P18 observability metrics by time range), user data sorted by ID
  range.
- **Compound**: Some systems use both. Hash the partition key for placement,
  range-sort within each partition. Cassandra does this: partition key is
  hashed, clustering columns are sorted within the partition.

---

## 6.4 Secondary Indexes with Partitioned Data

```
  Challenge: if data is partitioned by primary key, how do you
  query by a secondary attribute?

  Option 1: Local secondary index (document-partitioned)
  ════════════════════════════════════════════════════════
    Each partition maintains its own secondary index covering
    only the data on that partition.
    Query by secondary key -> scatter to ALL partitions, gather results.
    Used by: MongoDB, Cassandra, Elasticsearch.

  Option 2: Global secondary index (term-partitioned)
  ═══════════════════════════════════════════════════════
    Secondary index is itself partitioned across nodes.
    All entries for index term "color=red" go to one partition.
    Query by secondary key -> single partition lookup.
    Write requires updating the remote index partition (slower writes).
    Used by: DynamoDB global secondary indexes, Google Cloud Spanner.

  ┌──────────────────────────────────────────────────────────┐
  │           Local Index          │     Global Index        │
  │  Read:  scatter-gather (slow)  │  Read:  single lookup  │
  │  Write: local only (fast)      │  Write: remote update   │
  │                                │         (slow)          │
  └──────────────────────────────────────────────────────────┘
```

---

## 6.5 Interview Tie-ins

| Project | Connection to Partitioning                                   |
|---------|---------------------------------------------------------------|
| **P07** | Distributed cache: consistent hashing with virtual nodes for  |
|         | key placement. Adding/removing cache nodes moves minimal keys.|
| **P19** | API Gateway: consistent hash load balancing ensures requests  |
|         | for the same key go to the same backend (session affinity).   |
| **P01** | URL shortener: hash the short URL to determine which database |
|         | shard stores the mapping.                                    |
| **P08** | Ride sharing: geospatial partitioning (QuadTree/GeoHash)     |
|         | is a form of range partitioning on geographic coordinates.   |
| **P18** | Observability: metrics partitioned by time range for         |
|         | efficient time-series queries.                               |

### Interview Soundbites

> "Consistent hashing with virtual nodes is the standard approach for
> distributed caches and key-value stores. When a node is added, only
> 1/N of the keys need to move, compared to almost 100% with naive
> hash-mod-N."

> "Range partitioning enables efficient range scans but is vulnerable
> to hotspots. If all writes go to the 'current time' range (time-series),
> one partition gets all the write traffic. Solutions include key salting
> or pre-splitting."

---
---

# 7. Failure Detection and Gossip

Distributed systems must detect node failures promptly and disseminate
this information to all nodes. Getting this wrong leads to split-brain,
stale routing, or unnecessary failovers.

---

## 7.1 Heartbeat-Based Failure Detection

### 7.1.1 Simple Timeout

```
  ┌────────┐  heartbeat  ┌────────┐
  │ Node A │ ──────────> │ Node B │
  │        │  (every Δ)  │        │
  └────────┘             └────────┘

  If Node B does not receive a heartbeat from Node A
  within timeout T (where T > Δ), it suspects Node A is dead.

  Trade-off:
    T too small -> false positives (slow network mistaken for crash)
    T too large -> slow detection (real crashes take long to detect)

  Typical values:
    Δ = 1 second (heartbeat interval)
    T = 5 seconds (timeout = 5 missed heartbeats)
```

### 7.1.2 Phi Accrual Failure Detector

Used by **Cassandra** and **Akka**. Instead of a binary alive/dead decision,
it computes a **suspicion level** (phi, a real number):

```
  ┌──────────────────────────────────────────────────────────┐
  │ Phi Accrual Failure Detector:                            │
  │                                                          │
  │ 1. Track the inter-arrival times of heartbeats.          │
  │ 2. Maintain a sliding window of recent intervals.        │
  │ 3. Model the distribution (assume normal distribution).  │
  │ 4. Compute phi = -log10(P(interval > observed_gap))      │
  │                                                          │
  │ phi = 1  -> 10% chance the node is alive (90% it's dead) │
  │ phi = 3  -> 0.1% chance the node is alive                │
  │ phi = 8  -> 0.000001% chance (almost certainly dead)     │
  │                                                          │
  │ Threshold: typically phi > 8 -> declare dead.            │
  │                                                          │
  │ Advantages:                                              │
  │ - Adapts to network conditions (jitter, congestion).     │
  │ - Different thresholds for different criticality levels.  │
  │ - No fixed timeout to tune.                              │
  └──────────────────────────────────────────────────────────┘
```

### 7.1.3 Heartbeat Patterns

```
  Centralized heartbeat:
  ═══════════════════════
    All nodes send heartbeats to a central monitor.
    + Simple.
    - Central monitor is a single point of failure.
    - Scales poorly (N heartbeats per interval).

  All-to-all heartbeat:
  ═════════════════════
    Every node sends heartbeats to every other node.
    + No single point of failure.
    - O(N^2) messages per interval. Does not scale.

  Gossip-based heartbeat:
  ════════════════════════
    Nodes gossip heartbeat information (see Section 7.2).
    + O(N log N) messages. Scalable.
    + No single point of failure.
    - Slower detection (information propagates in O(log N) rounds).
```

---

## 7.2 Gossip Protocol

Gossip (epidemic) protocols disseminate information through random
peer-to-peer exchanges, like how rumors spread.

### 7.2.1 Basic Gossip Algorithm

```
  Every T seconds, each node:
    1. Picks a RANDOM peer.
    2. Exchanges state with that peer.
    3. Merges the received state with its own.

  Information spreads exponentially:
    Round 0: 1 node knows
    Round 1: ~2 nodes know (1 told 1)
    Round 2: ~4 nodes know (2 told 2)
    Round 3: ~8 nodes know
    ...
    Round k: ~2^k nodes know
    After O(log N) rounds: all N nodes know.
```

### 7.2.2 SWIM (Scalable Weakly-consistent Infection-style Membership)

SWIM is a gossip-based membership protocol used by **HashiCorp Serf**
and **Consul**.

```
  ┌──────────────────────────────────────────────────────────┐
  │ SWIM Protocol:                                           │
  │                                                          │
  │ Failure Detection:                                       │
  │   1. Node A picks random node B.                         │
  │   2. A sends PING to B.                                  │
  │   3. If B responds (ACK), B is alive.                    │
  │   4. If no response within timeout:                      │
  │      a. A picks K random nodes (C, D, E).                │
  │      b. A asks C, D, E to PING B (indirect probe).      │
  │      c. If any of C, D, E get an ACK from B:            │
  │         B is alive (A's direct ping was just lost).      │
  │      d. If none get an ACK: B is suspected dead.         │
  │                                                          │
  │ Dissemination:                                           │
  │   Membership changes (join, leave, fail) are              │
  │   piggybacked on PING/ACK messages.                      │
  │   No extra messages needed for dissemination!            │
  │                                                          │
  │ A ── PING ──> B (no response)                            │
  │ A ── PING-REQ ──> C ── PING ──> B (no response)         │
  │ A ── PING-REQ ──> D ── PING ──> B (B responds!)         │
  │ D ── ACK ──> A  ("B is alive, your ping was just lost") │
  └──────────────────────────────────────────────────────────┘
```

### 7.2.3 Convergence Time

```
  For N nodes with gossip interval T:

  Expected rounds to reach all nodes: O(log N)
  Expected time: O(T * log N)

  Example: 1000 nodes, T = 1 second
  Convergence: ~10 seconds (log2(1000) ≈ 10)

  This is why gossip scales well:
  - 100 nodes: ~7 seconds
  - 1,000 nodes: ~10 seconds
  - 10,000 nodes: ~14 seconds
  - 1,000,000 nodes: ~20 seconds
```

### 7.2.4 Gossip in Production

| System        | How It Uses Gossip                                   |
|---------------|------------------------------------------------------|
| **Cassandra** | Gossip for node membership and failure detection.    |
| **Consul**    | SWIM-based gossip via Serf for cluster membership.   |
| **Redis Cluster** | Gossip for slot-to-node mapping and failure detection.|
| **Amazon S3** | Anti-entropy gossip for replica synchronization.     |

---

## 7.3 Split Brain

Split brain occurs when a network partition causes two groups of nodes
to each believe they are the sole active group. Both groups may accept
writes, leading to divergence.

### 7.3.1 The Problem

```
  Normal operation:
    [A] [B] [C] [D] [E]   (A is leader)

  Network partition:
    [A] [B] |partition| [C] [D] [E]

    Partition 1: A (leader), B
      A continues serving writes.

    Partition 2: C, D, E
      E times out on A's heartbeat.
      E starts election, wins (3 of 5 nodes in this partition).
      E becomes NEW leader.
      E starts serving writes.

    TWO leaders! Split brain!
    Both accept writes -> data diverges -> inconsistency.
```

### 7.3.2 Prevention Strategies

```
  1. Quorum-based leadership:
  ═══════════════════════════
    Leader must maintain a quorum (majority) of followers.
    If it can only reach 1 follower (A + B = 2, not a majority of 5),
    it steps down.
    Raft and Paxos do this automatically.

  2. Fencing tokens:
  ══════════════════
    When a new leader is elected, it gets a monotonically increasing
    fencing token (like a Raft term number).

    Old leader (token=5) sends write to storage.
    New leader (token=6) also sends write to storage.
    Storage rejects writes with token < current max token.
    Old leader's writes are fenced off.

    ┌────────────────────────────────────────────────┐
    │ Storage:                                        │
    │   current_token = 6                            │
    │   Write(token=5, x=old_value) -> REJECTED      │
    │   Write(token=6, x=new_value) -> ACCEPTED      │
    └────────────────────────────────────────────────┘

  3. STONITH (Shoot The Other Node In The Head):
  ══════════════════════════════════════════════
    When the new leader is elected, it forces the old leader to
    shut down (via out-of-band mechanism: IPMI, power control).
    Used in traditional HA clusters (Pacemaker/Corosync).
    Brutal but effective.
```

---

## 7.4 Interview Tie-ins

| Project | Connection to Failure Detection                              |
|---------|---------------------------------------------------------------|
| **P17** | Distributed task scheduler: heartbeat-based failure detection |
|         | for worker nodes. If a worker misses heartbeats, the         |
|         | scheduler reassigns its tasks. Must handle the case where    |
|         | the worker is slow (not dead) to avoid duplicate execution.  |
| **P07** | Distributed cache: gossip protocol for node membership.      |
|         | When a cache node fails, consistent hashing routes its keys  |
|         | to the next node on the ring.                                |
| **P19** | API Gateway: health checks (heartbeats) to backend services. |
|         | Circuit breaker pattern stops routing to unhealthy backends. |
| **P16** | Stock trading: split brain in the order matching engine is    |
|         | catastrophic (duplicate trades). Must use fencing tokens.    |

### Interview Soundbites

> "The phi accrual failure detector adapts to network conditions. Instead
> of a fixed timeout, it models heartbeat inter-arrival times and computes
> a continuous suspicion level. Cassandra uses this."

> "SWIM gossip provides O(log N) convergence with O(N) messages per round,
> compared to O(N^2) for all-to-all heartbeats. It piggybacks membership
> updates on ping/ack messages, so there is zero overhead for dissemination."

> "Split brain is prevented by requiring the leader to maintain a quorum.
> In a 5-node Raft cluster, if the leader can only reach 1 follower, it
> cannot commit writes (needs 3 nodes) and steps down."

---
---

# 8. CAP and PACELC

The CAP theorem is the most frequently referenced (and misunderstood)
result in distributed systems.

---

## 8.1 CAP Theorem

### 8.1.1 Formal Definition

```
  In a distributed data store, you can guarantee at most TWO of:

  C (Consistency):    Every read receives the most recent write or an error.
                      (This is linearizability, not eventual consistency.)

  A (Availability):   Every request (to a non-failing node) receives a
                      response, without guarantee that it is the most recent.

  P (Partition        The system continues to operate despite arbitrary
     Tolerance):      message loss between nodes.

  ┌──────────────────────────────────────────────────────────┐
  │ Since network partitions are INEVITABLE in any           │
  │ distributed system, the real choice is:                  │
  │                                                          │
  │   CP: During a partition, sacrifice availability.        │
  │       Refuse to respond if you cannot guarantee          │
  │       the response is up-to-date.                        │
  │       Example: etcd returns error if leader unreachable. │
  │                                                          │
  │   AP: During a partition, sacrifice consistency.         │
  │       Respond with potentially stale data.               │
  │       Example: DynamoDB returns data from any replica.   │
  │                                                          │
  │   "CA": Only possible on a single node (no partitions).  │
  │         A single PostgreSQL server is "CA" -- but it is   │
  │         not a distributed system.                        │
  └──────────────────────────────────────────────────────────┘
```

### 8.1.2 Why "CA" Does Not Exist in Distributed Systems

```
  Proof sketch:

  Two nodes, A and B, with a network partition between them.

  Client writes x=1 to A.
  Client reads x from B.

  If the system is CONSISTENT: B must return 1.
    But B cannot contact A (partition). B cannot know x=1.
    So B must either:
      (a) Return an error -> not AVAILABLE.
      (b) Wait forever -> not AVAILABLE.

  If the system is AVAILABLE: B must return some value.
    B has not received x=1 (partition). B returns x=0 (stale).
    -> not CONSISTENT.

  You cannot have both C and A when there is a partition.
  And partitions happen. Therefore: pick CP or AP.
```

### 8.1.3 CAP is Not Binary

```
  ┌──────────────────────────────────────────────────────────┐
  │ Common misconception: "A system is either CP or AP."     │
  │                                                          │
  │ Reality:                                                 │
  │ - CAP is per-OPERATION, not per-system.                  │
  │ - A system can be CP for writes and AP for reads.        │
  │ - A system can be CP for financial data and AP for       │
  │   social media feeds.                                    │
  │ - Different components of the same system can make       │
  │   different choices.                                     │
  │                                                          │
  │ Example (P17 - Distributed Task Scheduler):              │
  │   Task assignment: CP (cannot double-assign tasks)       │
  │   Task metrics: AP (approximate counts are fine)         │
  │                                                          │
  │ Example (P10 - E-Commerce):                              │
  │   Inventory count: CP (cannot oversell)                  │
  │   Product catalog: AP (stale description is OK)          │
  └──────────────────────────────────────────────────────────┘
```

---

## 8.2 PACELC

PACELC (Abadi, 2012) extends CAP to also consider the tradeoff during
normal operation (no partitions).

### 8.2.1 Definition

```
  if (Partition) {
      choose between Availability and Consistency    (same as CAP)
  } else {
      choose between Latency and Consistency          (new tradeoff!)
  }

  P A C E L C
  │ │ │ │ │ │
  │ │ │ │ │ └── Consistency (during normal operation)
  │ │ │ │ └──── Latency (during normal operation)
  │ │ │ └────── Else (normal operation)
  │ │ └──────── Consistency (during partition)
  │ └────────── Availability (during partition)
  └──────────── Partition
```

### 8.2.2 PACELC Classifications

| System          | During Partition | During Normal Operation | Classification |
|-----------------|-----------------|------------------------|----------------|
| **DynamoDB**    | AP              | EL (low latency)       | PA/EL          |
| **Cassandra**   | AP              | EL (tunable)           | PA/EL          |
| **MongoDB**     | CP (primary)    | EC (strong reads)      | PC/EC          |
| **Spanner**     | CP              | EC (TrueTime)          | PC/EC          |
| **etcd**        | CP              | EC (Raft leader reads) | PC/EC          |
| **Cosmos DB**   | Tunable         | Tunable                | Tunable        |

### 8.2.3 Why PACELC Matters More Than CAP

```
  ┌──────────────────────────────────────────────────────────┐
  │ Partitions are rare. Most of the time, the system is     │
  │ operating normally. The EL/EC tradeoff affects EVERY     │
  │ request, not just requests during partitions.            │
  │                                                          │
  │ Example:                                                 │
  │   DynamoDB: reads from any replica (low latency, EL)     │
  │   Spanner: reads go through Paxos leader (higher         │
  │            latency, EC)                                   │
  │                                                          │
  │ In an interview, saying "I'd choose an AP system" is     │
  │ incomplete. PACELC forces you to also state: "and during │
  │ normal operation, I prioritize latency over consistency   │
  │ because this is a read-heavy social media feed."         │
  └──────────────────────────────────────────────────────────┘
```

---

## 8.3 Per-Component CAP Analysis Across 19 Projects

| # | Project                     | Component                | CAP  | Rationale                                       |
|---|-----------------------------|--------------------------| ---- |-------------------------------------------------|
| 01| URL Shortener               | URL mapping store        | AP   | Stale reads acceptable; redirect still works    |
| 02| Rate Limiter                | Counter store            | AP   | Approximate counts sufficient                   |
| 03| Notification System         | Notification queue       | AP   | Delayed/duplicate notification > lost            |
| 04| Chat System                 | Message store            | CP   | Messages must not be lost or reordered           |
| 04| Chat System                 | Presence (online/offline)| AP   | Stale presence is acceptable                     |
| 05| Social Media Feed           | Feed cache               | AP   | Stale feed is the norm                           |
| 06| Parking Lot                 | Spot availability        | CP   | Cannot double-book a parking spot                |
| 07| Distributed Cache           | Cache entries            | AP   | Cache miss = fallback to DB, acceptable          |
| 08| Ride Sharing                | Driver location          | AP   | Slightly stale location is fine                  |
| 08| Ride Sharing                | Ride assignment          | CP   | Cannot assign same driver to two rides           |
| 09| Search Autocomplete         | Trie / suggestion index  | AP   | Stale suggestions acceptable                     |
| 10| E-Commerce                  | Inventory                | CP   | Cannot oversell                                  |
| 10| E-Commerce                  | Product catalog          | AP   | Stale description OK                             |
| 11| Payment System              | Ledger / balance         | CP   | Financial accuracy required                      |
| 12| News Feed                   | Feed generation          | AP   | Stale feed acceptable                            |
| 13| Video Streaming             | Video chunks (CDN)       | AP   | Stale chunk = serve cached version               |
| 14| Real-time Collaboration     | Document state (CRDT)    | AP   | CRDTs converge without coordination              |
| 15| File Storage                | File metadata            | CP   | File must not be lost or corrupted               |
| 15| File Storage                | File chunks (CDN)        | AP   | Chunks are content-addressed (immutable)         |
| 16| Stock Trading               | Order book               | CP   | Trades must be exactly ordered                   |
| 17| Task Scheduler              | Task assignment          | CP   | Tasks must not be double-executed                |
| 17| Task Scheduler              | Task metrics             | AP   | Approximate counts fine                          |
| 18| Observability Platform      | Metrics store            | AP   | Approximate metrics acceptable                   |
| 18| Observability Platform      | Alert state              | CP   | Must not miss or duplicate critical alerts       |
| 19| API Gateway                 | Service registry         | AP   | Stale routing > no routing                       |
| 19| API Gateway                 | Rate limit state         | AP   | Approximate rate limiting acceptable             |

---

## 8.4 Interview Framework: Analyzing CAP in 30 Seconds

```
  Step 1: Identify the data in question.
          "We're talking about the ORDER BOOK, not the whole system."

  Step 2: Ask: "What happens if a user reads stale data?"
          - Financial loss, data corruption? -> CP
          - Minor UX degradation? -> AP

  Step 3: Ask: "What happens if the system is unavailable?"
          - Revenue loss, safety risk? -> AP (availability matters more)
          - Users can retry? -> CP (consistency matters more)

  Step 4: State the PACELC tradeoff:
          "During normal operation, do we optimize for
          latency or consistency?"

  Example soundbite:
  "For the inventory service in P10, I'd choose CP because
  we cannot oversell. If a partition occurs, we'd rather
  return an error than allow a purchase we cannot fulfill.
  During normal operation, we optimize for consistency (PC/EC)
  because financial accuracy outweighs a few extra milliseconds."
```

---

## 8.5 Interview Soundbites

> "CAP says you must choose between consistency and availability during
> a partition. But partitions are rare. PACELC extends this: during
> normal operation, you choose between latency and consistency. That is
> the tradeoff you live with every day."

> "CAP is per-component, not per-system. In P17, task assignment is CP
> (double-execution is unacceptable) but task metrics is AP (approximate
> counts are fine)."

> "CA does not exist in distributed systems. A single PostgreSQL node is
> CA, but the moment you add a replica, you must handle partitions."

---
---

# 9. Exactly-Once Semantics

Achieving exactly-once processing in distributed systems is notoriously
hard. Many claims of "exactly-once" are actually "effectively-once"
(at-least-once + idempotency).

---

## 9.1 Delivery Semantics

### 9.1.1 At-Most-Once

```
  ┌──────────────────────────────────────────────────────────┐
  │ Fire-and-forget. No retry.                               │
  │                                                          │
  │ Producer ── message ──> Consumer                         │
  │                                                          │
  │ If the message is lost (network error, consumer crash),  │
  │ it is gone forever. No retry attempt.                    │
  │                                                          │
  │ Implementation: send once, do not retry, do not ack.     │
  │                                                          │
  │ Use case: UDP packets, log shipping where some loss is   │
  │ acceptable, metrics (P18 -- losing 0.01% of data points │
  │ is acceptable).                                          │
  │                                                          │
  │ Delivery count: 0 or 1                                   │
  └──────────────────────────────────────────────────────────┘
```

### 9.1.2 At-Least-Once

```
  ┌──────────────────────────────────────────────────────────┐
  │ Retry until acknowledged. May deliver duplicates.        │
  │                                                          │
  │ Producer ── message ──> Consumer                         │
  │          (no ACK received, timeout)                      │
  │ Producer ── message ──> Consumer  (retry)                │
  │          <── ACK ──                                      │
  │                                                          │
  │ But maybe the first attempt DID reach the consumer,      │
  │ and only the ACK was lost. The consumer processed the    │
  │ message TWICE.                                           │
  │                                                          │
  │ Implementation: retry with exponential backoff.          │
  │                                                          │
  │ Use case: Most messaging systems default to this.        │
  │ Kafka (at-least-once by default), SQS, RabbitMQ.        │
  │                                                          │
  │ Delivery count: 1 or more                                │
  └──────────────────────────────────────────────────────────┘
```

### 9.1.3 Exactly-Once

```
  ┌──────────────────────────────────────────────────────────┐
  │ Each message is processed exactly once.                  │
  │                                                          │
  │ TRUE exactly-once is impossible in general (requires     │
  │ solving consensus for each message).                     │
  │                                                          │
  │ What systems actually provide:                           │
  │   "Effectively once" = at-least-once + idempotent        │
  │                        processing                        │
  │                                                          │
  │ The message may be DELIVERED multiple times, but the     │
  │ EFFECT is as if it was processed once.                   │
  │                                                          │
  │ Implementation:                                          │
  │   1. At-least-once delivery (retries)                    │
  │   2. Idempotent consumer (dedup or idempotent operations)│
  │                                                          │
  │ Delivery count: 1 or more, but effect count: exactly 1  │
  └──────────────────────────────────────────────────────────┘
```

### 9.1.4 Comparison

| Semantic        | Retries? | Duplicates? | Data Loss? | Complexity  |
|-----------------|----------|-------------|------------|-------------|
| At-most-once    | No       | No          | Possible   | Lowest      |
| At-least-once   | Yes      | Possible    | No         | Low         |
| Exactly-once    | Yes      | No (dedup)  | No         | Highest     |

---

## 9.2 Idempotency

An operation is **idempotent** if applying it multiple times has the same
effect as applying it once.

### 9.2.1 Naturally Idempotent Operations

```
  ┌──────────────────────────────────────────────────────────┐
  │ Idempotent:                                              │
  │   SET x = 5          (setting to absolute value)         │
  │   DELETE WHERE id=3  (deleting a specific row)           │
  │   PUT /users/123     (upsert with full replacement)      │
  │                                                          │
  │ NOT idempotent:                                          │
  │   x = x + 1          (incrementing is NOT idempotent!)   │
  │   INSERT INTO ...    (creates duplicate rows)            │
  │   POST /orders       (creates duplicate orders)          │
  │   balance -= 100     (double deduction!)                 │
  └──────────────────────────────────────────────────────────┘
```

### 9.2.2 Idempotency Keys

For non-idempotent operations, use an **idempotency key**:

```
  Client generates a unique key (UUID) for each logical operation.
  Server tracks which keys have been processed.

  Request 1:
    POST /payments
    Idempotency-Key: abc-123
    Body: { amount: 100, currency: "USD" }

    Server: key "abc-123" not seen -> process payment -> store result
    Response: 200 OK, { payment_id: "pay_456" }

  Request 2 (retry, same key):
    POST /payments
    Idempotency-Key: abc-123
    Body: { amount: 100, currency: "USD" }

    Server: key "abc-123" already processed -> return stored result
    Response: 200 OK, { payment_id: "pay_456" }  (same result, no double charge)

  ┌──────────────────────────────────────────────────────────┐
  │ Idempotency key storage:                                 │
  │                                                          │
  │ ┌───────────────┬──────────┬─────────────┬─────────────┐ │
  │ │ idempotency_  │ status   │ response    │ created_at  │ │
  │ │ key           │          │             │             │ │
  │ ├───────────────┼──────────┼─────────────┼─────────────┤ │
  │ │ abc-123       │ COMPLETE │ {pay_456}   │ 2024-01-15  │ │
  │ │ def-456       │ STARTED  │ null        │ 2024-01-15  │ │
  │ └───────────────┴──────────┴─────────────┴─────────────┘ │
  │                                                          │
  │ TTL: expire keys after 24-48 hours.                      │
  │ Status STARTED: prevents concurrent duplicate processing. │
  └──────────────────────────────────────────────────────────┘
```

### 9.2.3 Conditional Writes (Optimistic Concurrency)

```
  Instead of blind writes, use conditional writes:

  UPDATE accounts
  SET balance = balance - 100,
      version = version + 1
  WHERE id = 123
  AND version = 5;    -- only succeed if version matches

  If the write was already applied (version is now 6),
  the WHERE clause fails -> 0 rows affected -> idempotent!

  DynamoDB: ConditionExpression
  Cassandra: IF conditions (lightweight transactions)
  Redis: WATCH + MULTI (optimistic locking)
```

---

## 9.3 Deduplication Strategies

### 9.3.1 In-Memory Dedup (Sliding Window)

```
  ┌──────────────────────────────────────────────────────────┐
  │ Keep a set of recently seen message IDs.                 │
  │ If a message ID is in the set, it is a duplicate.        │
  │                                                          │
  │ Implementation:                                          │
  │   seen_ids = LinkedHashSet (insertion-ordered)           │
  │   max_window = 10,000 messages or 5 minutes              │
  │                                                          │
  │   on_message(msg):                                       │
  │     if msg.id in seen_ids:                               │
  │       return  // duplicate, skip                         │
  │     seen_ids.add(msg.id)                                 │
  │     if seen_ids.size > max_window:                       │
  │       seen_ids.remove_oldest()                           │
  │     process(msg)                                         │
  │                                                          │
  │ Limitation: if a duplicate arrives after the window      │
  │ expires, it will be processed again. Window must be      │
  │ larger than the maximum retry delay.                     │
  └──────────────────────────────────────────────────────────┘
```

### 9.3.2 Bloom Filter Dedup

```
  ┌──────────────────────────────────────────────────────────┐
  │ Bloom filter: space-efficient probabilistic set.         │
  │                                                          │
  │ - add(id): guaranteed                                    │
  │ - contains(id):                                          │
  │   - if true: MIGHT be a duplicate (false positive ~1%)   │
  │   - if false: DEFINITELY not a duplicate                 │
  │                                                          │
  │ Use for first-pass dedup:                                │
  │   if bloom.contains(msg.id):                             │
  │     check exact dedup store (database)  // expensive     │
  │   else:                                                  │
  │     bloom.add(msg.id)                                    │
  │     process(msg)                                         │
  │                                                          │
  │ Reduces database lookups by 99%+ for non-duplicate       │
  │ messages.                                                │
  └──────────────────────────────────────────────────────────┘
```

### 9.3.3 Kafka Exactly-Once (Idempotent Producer + Transactions)

```
  Kafka's "exactly-once semantics" (EOS) since Kafka 0.11:

  1. Idempotent Producer:
     - Producer gets a PID (producer ID) from the broker.
     - Each message has a sequence number (PID + seqNo).
     - Broker deduplicates by (PID, seqNo).
     - Retried messages with same seqNo are silently ignored.

  2. Transactional Producer:
     - Producer begins a transaction.
     - Writes to multiple partitions atomically.
     - Consumer reads only committed messages
       (isolation.level=read_committed).

  3. Consumer side:
     - Consumer commits offsets IN the same transaction
       as the produced output messages.
     - If the consumer crashes, it reprocesses from the last
       committed offset, but output messages are deduped by
       the idempotent producer.

  ┌──────────────────────────────────────────────────────────┐
  │ Kafka EOS = idempotent producer + transactions +         │
  │ exactly-once consumer offset management.                 │
  │                                                          │
  │ This is "effectively once," not "true exactly-once."     │
  │ Messages may be delivered multiple times, but the         │
  │ combination of dedup + atomic offset commit ensures      │
  │ each message's effect is applied exactly once.           │
  └──────────────────────────────────────────────────────────┘
```

---

## 9.4 Interview Tie-ins

| Project | Connection to Exactly-Once                                   |
|---------|---------------------------------------------------------------|
| **P17** | Distributed task scheduler: tasks must execute exactly once.  |
|         | Uses idempotency keys + lease-based locking. If a worker     |
|         | crashes mid-execution, the lease expires and another worker   |
|         | picks up the task. The task handler must be idempotent.      |
| **P11** | Payment system: payments must charge exactly once.            |
|         | Uses idempotency keys (Stripe-style). Retry-safe API.        |
|         | Double-entry ledger provides an audit trail to detect         |
|         | duplicate charges.                                           |
| **P10** | E-Commerce: order placement must not create duplicate orders. |
|         | Saga orchestrator uses idempotency keys for each step.       |
| **P03** | Notification system: at-least-once delivery with dedup.      |
|         | Users might tolerate a duplicate push notification but NOT    |
|         | a missed critical alert.                                     |

### Interview Soundbites

> "True exactly-once delivery is impossible in the general case. What
> production systems actually provide is 'effectively once': at-least-once
> delivery combined with idempotent processing. Kafka's EOS is a great
> example -- the idempotent producer deduplicates retries, and
> transactional consumers commit offsets atomically with output."

> "For P11 (payment system), we use Stripe-style idempotency keys. The
> client generates a UUID for each payment attempt. If the server has
> already processed that key, it returns the stored result. This makes
> retries safe -- the user is never double-charged."

> "The idempotency key store needs a TTL (e.g., 48 hours) to avoid
> unbounded growth. It also needs a STARTED status to handle concurrent
> duplicate requests -- the second request waits or returns 409 Conflict."

---
---

# 10. Quick Reference and Interview Cheat Sheet

This section provides rapid-lookup definitions, decision trees, and a
concept-to-project cross-reference.

---

## 10.1 One-Liner Definitions

| Concept                     | One-Liner                                                                                                |
|-----------------------------|----------------------------------------------------------------------------------------------------------|
| **Consensus**               | Getting N nodes to agree on a single value despite failures.                                             |
| **Raft**                    | Leader-based consensus: randomized election + log replication.                                           |
| **Paxos**                   | Proposer/acceptor/learner consensus; provably correct, hard to implement.                                |
| **ZAB**                     | ZooKeeper's atomic broadcast: total-ordered, leader-based.                                               |
| **Lamport Timestamp**       | Logical clock (single integer); preserves causality but cannot detect concurrency.                       |
| **Vector Clock**            | Per-node counter vector; detects both causality and concurrency. O(N) size.                              |
| **HLC**                     | Hybrid Logical Clock; combines physical time + logical counter. O(1) size. Used by CockroachDB.          |
| **TrueTime**                | Google's GPS+atomic clock system; returns uncertainty intervals. Enables global linearizability.          |
| **Linearizability**         | Strongest consistency: reads see the most recent write, real-time ordered.                                |
| **Sequential Consistency**  | All ops in some total order, per-process order preserved. No real-time guarantee.                         |
| **Causal Consistency**      | Causally related ops ordered; concurrent ops unordered.                                                  |
| **Eventual Consistency**    | All replicas converge eventually. No ordering guarantee.                                                 |
| **2PC**                     | Two-phase commit: prepare/commit. Blocking if coordinator fails.                                         |
| **3PC**                     | Three-phase commit: adds pre-commit. Non-blocking under crashes, fails under partitions.                 |
| **Saga**                    | Sequence of local transactions with compensating actions for rollback.                                   |
| **Outbox Pattern**          | Write events to DB outbox table in same transaction; relay to message broker via CDC.                    |
| **Single-Leader**           | One writer, N readers. Simple but leader is bottleneck. Failover is hard.                                |
| **Multi-Leader**            | Multiple writers, async replication. Conflict resolution required. Multi-DC use case.                    |
| **Leaderless**              | Any node accepts reads/writes. Quorum (R+W>N) for consistency.                                           |
| **CRDT**                    | Data structure with mathematically guaranteed conflict-free merge. Used for multi-leader/offline.         |
| **Consistent Hashing**      | Keys and nodes on a hash ring. Adding a node moves only 1/N of keys.                                    |
| **Range Partitioning**      | Contiguous key ranges per partition. Enables range scans.                                                |
| **Gossip**                  | Epidemic information dissemination. O(log N) convergence. Scalable.                                      |
| **SWIM**                    | Gossip-based membership: ping, indirect-ping, piggyback dissemination.                                   |
| **Split Brain**             | Two partitions each think they are the leader. Prevented by quorum/fencing.                              |
| **CAP Theorem**             | During partition: choose consistency or availability. Cannot have both.                                  |
| **PACELC**                  | Extends CAP: during normal operation, choose latency or consistency.                                     |
| **Idempotency Key**         | Client-generated UUID ensuring retried requests have the same effect as the first.                       |
| **Bloom Filter**            | Probabilistic set membership: no false negatives, possible false positives.                              |

---

## 10.2 "Which Consistency Model?" Decision Tree

```
  START: What happens if a client reads stale data?
    │
    ├── "Financial loss, double-spend, data corruption"
    │   └── Linearizability
    │       Examples: P11 (payment ledger), P16 (order book),
    │                 P10 (inventory), P17 (task assignment)
    │
    ├── "Confusing UX but no data loss"
    │   │
    │   ├── "Causally related data (e.g., reply before original)"
    │   │   └── Causal Consistency
    │   │       Examples: P04 (chat messages), P14 (collaborative edits)
    │   │
    │   └── "Independent data (e.g., feed items)"
    │       └── Read-your-writes + Eventual Consistency
    │           Examples: P05 (social media feed), P12 (news feed)
    │
    └── "No impact (metrics, logs, caches)"
        └── Eventual Consistency
            Examples: P18 (metrics), P02 (rate limit counters),
                      P07 (cache entries), P01 (URL mappings)
```

---

## 10.3 "Which Replication Strategy?" Decision Tree

```
  START: How many datacenters need to accept writes?
    │
    ├── "One datacenter"
    │   └── Single-Leader Replication
    │       - Simple, well-understood.
    │       - Leader handles all writes.
    │       - Followers serve reads.
    │       Examples: MySQL, PostgreSQL, MongoDB, Kafka
    │
    ├── "Multiple datacenters, each needs local writes"
    │   │
    │   ├── "Can tolerate conflict resolution complexity?"
    │   │   └── Multi-Leader Replication
    │   │       - Each DC has a leader.
    │   │       - Must resolve conflicts (LWW, CRDT, app-level).
    │   │       Examples: CouchDB, Active Directory
    │   │
    │   └── "Want conflict-free by design?"
    │       └── CRDTs (a form of multi-leader)
    │           - Mathematically guaranteed convergence.
    │           - Limited data structure types.
    │           Examples: P14 (collaborative editing)
    │
    └── "Want maximum availability, no leader dependency"
        └── Leaderless Replication
            - Quorum reads/writes.
            - Tunable consistency.
            - Sloppy quorum + hinted handoff for extra availability.
            Examples: DynamoDB, Cassandra, Riak
```

---

## 10.4 Common Follow-Up Questions and Answers

### Q: "How do you handle a network partition?"
**A**: "It depends on the component. For the inventory service (P10), I choose
consistency -- return an error rather than risk overselling. For the product
catalog, I choose availability -- serve potentially stale data. CAP is
per-component."

### Q: "How do you prevent double-execution of tasks?"
**A**: "Three layers: (1) lease-based locking -- a worker acquires a lease with
a TTL; if it crashes, the lease expires and another worker picks up the task.
(2) Idempotent task handlers -- processing the same task twice produces the
same result. (3) Fencing tokens -- the lease includes a monotonic token;
storage rejects writes with stale tokens."

### Q: "How do you order events across microservices?"
**A**: "For causal ordering, I use HLCs (Hybrid Logical Clocks). Each service
attaches its HLC to outgoing messages. The consumer can determine causal
ordering from the HLC. For total ordering, I route related events through
the same Kafka partition."

### Q: "What if the Saga compensating action fails?"
**A**: "Compensating actions must be idempotent and retryable. If a compensation
fails, we retry with exponential backoff. If it exhausts retries, we alert
the operations team and the Saga enters a COMPENSATION_FAILED state for
manual resolution. We also log the full Saga state for debugging."

### Q: "Why not use 2PC for microservices?"
**A**: "2PC is a blocking protocol -- if the coordinator crashes after
collecting votes, participants hold locks indefinitely. In a microservices
architecture with dozens of services, the probability of coordinator failure
during the critical window is non-trivial. Sagas trade isolation for
availability: each step commits locally, and failures trigger compensations."

### Q: "How does consistent hashing handle hotspots?"
**A**: "Virtual nodes spread each physical node's responsibility across the ring,
preventing skew. For application-level hotspots (a viral post), I use key
splitting -- append a random suffix to distribute the key across multiple
partitions, then merge on reads. A cache layer in front absorbs most read
traffic."

### Q: "How do CRDTs handle the growing metadata problem?"
**A**: "State-based CRDTs like G-Counters grow with the number of nodes (one
entry per node). Solutions: (1) Prune entries for nodes that have left the
cluster. (2) Use dotted version vectors that track only active causal
context. (3) Periodic state compaction. For P14, the number of concurrent
editors is small (tens, not thousands), so metadata size is not a concern."

### Q: "How does Raft handle a slow follower?"
**A**: "Raft does not wait for all followers -- only a majority. A slow follower
does not block commits. The leader tracks each follower's nextIndex and
sends it the entries it needs. If a follower is very far behind, the leader
sends a snapshot instead of individual entries. The follower installs the
snapshot and resumes from there."

### Q: "What is the difference between quorum and consensus?"
**A**: "Quorum is a counting mechanism: R+W>N ensures read and write sets overlap.
Consensus (Raft, Paxos) is a protocol that ensures all nodes agree on the
order of operations. Quorum can be used without consensus (Dynamo-style
systems), but it only guarantees that reads see recent writes, not that all
nodes agree on the order. Consensus provides a total order."

---

## 10.5 Cross-Reference: Concept to Project

| Concept                    | Projects That Demonstrate It                           |
|----------------------------|--------------------------------------------------------|
| Consensus / Leader Election| P17 (Bully algorithm), P19 (Consul/Raft)               |
| Consistent Hashing         | P07 (distributed cache), P19 (API gateway LB)          |
| CRDTs                      | P14 (real-time collaboration, RGA)                     |
| Saga Pattern               | P10 (e-commerce order placement)                       |
| Idempotency Keys           | P11 (payment system)                                   |
| Rate Limiting              | P02 (5 algorithms), P19 (API gateway rate limiting)    |
| Fan-out (push/pull/hybrid) | P05 (social media feed), P12 (news feed)               |
| Pub/Sub / Event-Driven     | P03 (notification system), P04 (chat system)           |
| Circuit Breaker            | P16 (stock trading), P19 (API gateway)                 |
| Caching / Cache Invalidation| P07 (distributed cache), all projects (caching docs)   |
| Distributed Tracing        | P18 (observability platform)                           |
| Geospatial Indexing        | P08 (ride sharing, QuadTree/GeoHash)                   |
| Search / Autocomplete      | P09 (trie-based autocomplete)                          |
| CDN / Edge Caching         | P13 (video streaming), P15 (file storage)              |
| Order Matching Engine      | P16 (stock trading, price-time priority)               |
| OT / Conflict Resolution   | P14 (real-time collaboration)                          |
| Chunked Upload / Dedup     | P15 (file storage, SHA-256 content-addressed)          |
| Double-Entry Ledger        | P11 (payment system)                                   |
| CQRS                       | P10 (e-commerce, separate read/write models)           |
| Quorum Reads/Writes        | P07 (distributed cache, tunable consistency)           |
| Replication Lag             | P04 (chat), P05 (feed), P12 (news feed)                |
| Split Brain                | P16 (stock trading), P17 (task scheduler)              |
| Fencing Tokens             | P17 (task scheduler, lease-based locking)              |
| Bloom Filter               | P01 (URL shortener, collision detection)               |

---

## 10.6 Estimation Quick-Reference (Distributed Systems Context)

```
  ┌──────────────────────────────────────────────────────────┐
  │ Network latency:                                         │
  │   Same datacenter:     0.5 - 1 ms                        │
  │   Cross-datacenter:    50 - 150 ms                       │
  │   Cross-continent:     100 - 300 ms                      │
  │                                                          │
  │ Raft election:         150 - 300 ms (timeout)            │
  │ Raft commit:           1 - 5 ms (same DC)                │
  │ ZooKeeper write:       2 - 10 ms                         │
  │ Spanner commit-wait:   ~7 ms (TrueTime uncertainty)      │
  │                                                          │
  │ Gossip convergence:    O(log N) rounds                   │
  │   100 nodes:           ~7 rounds                         │
  │   1000 nodes:          ~10 rounds                        │
  │   10000 nodes:         ~14 rounds                        │
  │                                                          │
  │ Quorum (N=3):          W=2, R=2 -> overlap guaranteed    │
  │ Quorum (N=5):          W=3, R=3 -> overlap guaranteed    │
  │                                                          │
  │ NTP accuracy:          1-10 ms (LAN), 10-100 ms (WAN)   │
  │ TrueTime accuracy:     1-7 ms (GPS + atomic clock)       │
  │ HLC accuracy:          bounded by max clock skew          │
  └──────────────────────────────────────────────────────────┘
```

---

## 10.7 Further Reading

| Topic                    | Paper / Resource                                        |
|--------------------------|---------------------------------------------------------|
| Raft                     | "In Search of an Understandable Consensus Algorithm"    |
|                          | (Ongaro, Ousterhout, 2014)                              |
| Paxos                    | "The Part-Time Parliament" (Lamport, 1998)              |
| Lamport Clocks           | "Time, Clocks, and the Ordering of Events" (Lamport, 1978)|
| Vector Clocks            | (Fidge 1988, Mattern 1989)                              |
| HLC                      | "Logical Physical Clocks" (Kulkarni et al., 2014)       |
| Dynamo                   | "Dynamo: Amazon's Highly Available Key-value Store" (2007)|
| Google Spanner           | "Spanner: Google's Globally Distributed Database" (2012)|
| CAP Theorem              | "Brewer's Conjecture" proof (Gilbert, Lynch, 2002)      |
| PACELC                   | "Consistency Tradeoffs in Modern DDS" (Abadi, 2012)     |
| CRDTs                    | "A Comprehensive Study of CRDTs" (Shapiro et al., 2011) |
| Saga Pattern             | "Sagas" (Garcia-Molina, Salem, 1987)                    |
| SWIM                     | "SWIM: Scalable Weakly-consistent Infection-style       |
|                          |  Process Group Membership Protocol" (2002)              |
| Designing Data-Intensive | Martin Kleppmann (2017) -- the definitive reference     |
| Applications (DDIA)      | for all topics in this document.                        |

---

*End of Distributed Systems Fundamentals Reference.*
*Total sections: 10 | Cross-referenced projects: P01-P19 | Interview-ready.*
