# System Design Interview Prep

A comprehensive, hands-on repository for learning, revising, and practicing **20 system design interview problems** — fully implemented in **Java 21**, backed by design docs, pattern analysis, interview walkthroughs, and **interactive HTML study canvases**.

---

## What This Repo Contains

- **20 complete projects** — each a standalone system-design problem with working Java code
- **823 Java source files** — interview-friendly implementations (not production-grade)
- **161 Markdown documents** — HLD, LLD, patterns, CAP analysis, caching, cloud mapping, interview walkthroughs
- **64 interactive HTML canvases** — 3 foundations + 3 canvases per use case (HLD · LLD · Deep-dive); clickable diagrams, flow-steppers, live estimators
- **19 GoF design patterns** documented with code, UML, and cross-project comparison
- **3 root-level reference docs** — universal estimation cheatsheet, ~4000-line pattern reference, ASCII UML diagrams

---

## How to Traverse This Repo — Start Here

There are **four ways in**, depending on how much time you have and what you're studying for. Pick one; they all cross-link.

### Path A — 5-minute orientation (glance the hub)

Open **[`canvas/index.html`](canvas/index.html)** in a browser (or Cursor → Live Preview). You'll see:

1. **Foundations** at the top (3 tiles) — CAP, must-know algorithms, estimation
2. **Use cases** below (20 tiles) — each links to that use case's HLD canvas

Every canvas is self-contained HTML: **no build, no server, no internet needed.** Prints to PDF via `Cmd/Ctrl+P` if you want offline study material.

### Path B — 30-minute quick tour (one use case, end-to-end)

Recommended for your first pass. Pick any use case — try **[URL Shortener (01)](01-url-shortener/)** for warm-up or **[Distributed Task Scheduler (17)](17-distributed-task-scheduler/)** for a Staff-level example.

1. Open `canvas/NN-slug/hld.html` — click components, walk both flow-steppers (write path + read path), skim the cloud table
2. Open `canvas/NN-slug/lld.html` — click classes to see the code snippets, browse strategy tabs
3. Open `canvas/NN-slug/deepdive.html` — read the CAP split table + trade-offs, jump to the 35-min interview walkthrough at the bottom
4. Optional: `./gradlew :NN-slug:run` to see the Java demo output

### Path C — Deep dive (60-90 min per use case)

For interview prep two weeks out. Same use case, all four surfaces:

1. **Canvas** — `canvas/NN-slug/{hld,lld,deepdive}.html` (visual anchor)
2. **Design docs** — `NN-slug/docs/{hld,lld,patterns,tradeoffs,tech,caching,cloud}/` (long-form)
3. **Java code** — `NN-slug/src/main/java/` (real implementations, run the demo)
4. **Interview walkthrough** — `NN-slug/revision/INTERVIEW_WALKTHROUGH.md` (phase-by-phase timing)

### Path D — Cross-cutting concepts (patterns / CAP / estimation)

Study across all projects instead of one at a time:

- **Foundations first:** [`canvas/f1-cap-consistency.html`](canvas/f1-cap-consistency.html) → [`canvas/f2-must-know-algorithms.html`](canvas/f2-must-know-algorithms.html) → [`canvas/f3-estimation-numbers.html`](canvas/f3-estimation-numbers.html)
- **All 19 GoF patterns:** [`docs/DESIGN_PATTERNS_REFERENCE.md`](docs/DESIGN_PATTERNS_REFERENCE.md) (~4000 lines with code) + [`docs/DESIGN_PATTERNS_UML.md`](docs/DESIGN_PATTERNS_UML.md) (ASCII UML + cross-project matrix)
- **Universal estimation:** [`docs/ESTIMATION_CHEATSHEET.md`](docs/ESTIMATION_CHEATSHEET.md) (the 5 numbers, latency table, powers of 1000)

### Legend for canvas navigation

Every use case has three canvases connected by a footer nav strip:

| Canvas | What's inside |
|---|---|
| **HLD** | Problem + FR/NFR · capacity estimation · **interactive architecture SVG with flow-steppers** · API design · data model · key design choices · AWS/GCP/Azure cloud table · glossary |
| **LLD** | **Clickable class map** (real Java classes from `src/`) · verbatim code walkthroughs · strategy tabs · state machine · sequence diagram · file map |
| **Deep-dive** | Algorithm/pattern tabs · correctness tabs · CAP split table · bottlenecks + mitigations · trade-offs table · **35-minute interview walkthrough with phase timing** |

**Interactive elements** on every canvas:
- Click any **component / class node** → detail popup with code
- Click **▶ Next** on a flow-stepper → walks through numbered arrows one by one
- Click a **tab** → swaps the panel (algorithm variants, strategies, correctness proofs)
- Click a **card header** → collapsibles for bottlenecks, edge cases
- Foundations F1 and F3 include **live calculators** (CAP triangle, capacity estimator, byte converter)

---

## Projects

| # | Project | Key Topics | Difficulty | Java | Canvas |
|---|---------|-----------|------------|------|:------:|
| 01 | [URL Shortener (TinyURL)](01-url-shortener/) | Strategy, Builder, Base62 encoding | Easy-Medium | 19 | [ready](canvas/01-url-shortener/hld.html) |
| 02 | [Rate Limiter](02-rate-limiter/) | 5 algorithms (Token Bucket, Leaky Bucket, Fixed/Sliding Window) | Medium | 20 | [ready](canvas/02-rate-limiter/hld.html) |
| 03 | [Notification System](03-notification-system/) | Observer, Producer-Consumer, Template Method | Medium | 32 | [ready](canvas/03-notification-system/hld.html) |
| 04 | [Chat System (WhatsApp)](04-chat-system/) | Mediator, Command, WebSocket simulation, presence | Hard | 31 | [ready](canvas/04-chat-system/hld.html) |
| 05 | [Social Media Feed (Twitter)](05-social-media-feed/) | Composite, hybrid fan-out (celebrity problem), trending | Hard | 36 | [ready](canvas/05-social-media-feed/hld.html) |
| 06 | [Parking Lot System](06-parking-lot/) | Facade, State, 9 patterns, SOLID, inheritance | Easy-Medium | 43 | [ready](canvas/06-parking-lot/hld.html) |
| 07 | [Distributed Cache (Redis)](07-distributed-cache/) | LRU / LFU O(1), consistent hashing, virtual nodes, Proxy | Hard | 28 | [ready](canvas/07-distributed-cache/hld.html) |
| 08 | [Ride-Sharing (Uber)](08-ride-sharing/) | QuadTree, GeoHash, Haversine, surge pricing, Decorator | Hard | 42 | [ready](canvas/08-ride-sharing/hld.html) |
| 09 | [Search Autocomplete](09-search-autocomplete/) | Trie (Standard / Compressed / TopK O(1)), time-decay ranking | Medium | 30 | [ready](canvas/09-search-autocomplete/hld.html) |
| 10 | [E-Commerce (Amazon)](10-ecommerce/) | **Saga (orchestration + compensation)** · 3-layer idempotency · Redis DECR flash-sale gate · SQS FIFO | Hard | 52 | [ready](canvas/10-ecommerce/hld.html) |
| 11 | [Payment System (Stripe/UPI)](11-payment-system/) | Double-entry ledger · idempotency · fraud (Rules + ML) · webhook retry · reconciliation | Hard | 52 | [ready](canvas/11-payment-system/hld.html) |
| 12 | [News Feed (Facebook/LinkedIn)](12-news-feed/) | Hybrid fan-out (push + pull for celebrities) · affinity × recency × engagement ranking · cursor pagination | Hard | 40 | [ready](canvas/12-news-feed/hld.html) |
| 13 | [Video Streaming (YouTube/Netflix)](13-video-streaming/) | Transcoding pipeline · adaptive bitrate (HLS / DASH) · CDN cache · chunked upload with resume | Hard | 45 | [ready](canvas/13-video-streaming/hld.html) |
| 14 | [Real-time Collaboration (Google Docs)](14-realtime-collaboration/) | **CRDT vs OT** (TP1 proof) · WebSocket fan-out (sticky sessions) · presence + cursor via Redis · snapshot + delta log | Hard | 40 | [ready](canvas/14-realtime-collaboration/hld.html) |
| 15 | [File Storage (Google Drive/Dropbox)](15-file-storage/) | Chunked resumable upload · SHA-256 content-addressable dedup · delta sync · copy-on-write versioning | Hard | 48 | [ready](canvas/15-file-storage/hld.html) |
| 16 | [Stock Trading (Zerodha/Upstox)](16-stock-trading/) | **Single-writer matching engine** (price-time priority) · TreeMap × 2 order book · pre-trade risk · STP · kill-switch | Hard | 56 | [ready](canvas/16-stock-trading/hld.html) |
| 17 | [Distributed Task Scheduler (Airflow/Celery)](17-distributed-task-scheduler/) | **Bully leader election** + fencing tokens · **Kahn's DAG topo sort** · exp backoff + jitter · effective exactly-once | Hard | 51 | [ready](canvas/17-distributed-task-scheduler/hld.html) |
| 18 | [Observability Platform (Datadog/Prometheus)](18-observability-platform/) | 3 pillars (metrics · traces · logs) · Gorilla TS compression · head vs tail sampling · retention tiers · cardinality limiter | Hard | 53 | [ready](canvas/18-observability-platform/hld.html) |
| 19 | [API Gateway & Service Mesh](19-api-gateway-service-mesh/) | 10-step N-S pipeline · sidecar mTLS + xDS control plane · circuit-breaker FSM · canary + auto-rollback · SPIFFE zero-trust | Hard | 53 | [ready](canvas/19-api-gateway-service-mesh/hld.html) |
| 20 | [Distributed Message Queue (Kafka)](20-distributed-message-queue/) | Commit log · partitions · ISR + acks (zero-loss recipe) · consumer groups · exactly-once · retention vs log compaction | Hard | 52 | [ready](canvas/20-distributed-message-queue/hld.html) |

---

## Per-Project Structure

Every project follows the same template:

```
NN-project-name/
├── docs/
│   ├── hld/HIGH_LEVEL_DESIGN.md        — Architecture, scaling, back-of-envelope
│   ├── lld/LOW_LEVEL_DESIGN.md         — Classes, interfaces, workflows
│   ├── patterns/DESIGN_PATTERNS.md     — Every pattern used, anti-pattern vs clean
│   ├── tradeoffs/CAP_THEOREM.md        — CP vs AP analysis per component
│   ├── tech/TECHNOLOGIES.md            — Production tech comparison
│   ├── caching/CACHING_STRATEGY.md     — What to cache, TTL, invalidation
│   └── cloud/CLOUD_SERVICES.md         — AWS / Azure / GCP service mapping
├── revision/
│   └── INTERVIEW_WALKTHROUGH.md        — Phase-by-phase interview guide with timing
├── src/main/java/com/systemdesign/{project}/
│   ├── model/                          — Domain entities, enums, builders
│   ├── strategy/                       — Strategy pattern implementations
│   ├── service/                        — Business logic (Facade pattern)
│   ├── repository/                     — Data access (Interface + InMemory)
│   ├── controller/                     — Simulated REST endpoints
│   ├── config/AppConfig.java           — Factory (the ONLY place with `new ConcreteClass()`)
│   ├── exception/                      — Domain exceptions
│   └── {Project}App.java              — Main demo with 9-12 runnable demos
├── README.md                           — 1-minute interview revision
└── build.gradle                        — Gradle subproject config

canvas/NN-project-name/
├── hld.html        — Architecture SVG + flow-steppers + capacity + API + data model + cloud table
├── lld.html        — Class-map SVG + verbatim code + strategy tabs + state machine + sequence
└── deepdive.html   — Algo tabs + correctness tabs + CAP table + bottlenecks + 35-min walkthrough
```

---

## Root-Level Docs

```
docs/
├── DESIGN_PATTERNS_REFERENCE.md   — ~4000 lines, all 19 patterns with code examples
├── DESIGN_PATTERNS_UML.md         — ASCII UML diagrams, cross-project pattern matrix
└── ESTIMATION_CHEATSHEET.md       — Universal estimation tables (not project-specific)

canvas/
├── index.html                     — Interactive hub (foundations + all 20 use cases)
├── f1-cap-consistency.html        — CAP triangle, PACELC, consistency models, quorum
├── f2-must-know-algorithms.html   — Consistent hashing, bloom, HLL, rate-limit, geohash, Snowflake
├── f3-estimation-numbers.html     — Live capacity estimator + byte converter, latency table
└── NN-slug/{hld,lld,deepdive}.html — 20 use cases × 3 canvases each
```

---

## Design Patterns Tracked (19)

| Category | Patterns |
|----------|----------|
| **Creational** (3) | Builder, Factory, Singleton |
| **Structural** (7) | Repository, Composite, Facade, Proxy, Decorator, Flyweight, Memento |
| **Behavioral** (7) | Strategy, Observer, Template Method, Chain of Responsibility, Mediator, Command, State |
| **Concurrency** (2) | Producer-Consumer, Saga |

Every pattern includes:
- Ugly anti-pattern code (what NOT to do)
- Clean pattern-based solution
- Numbered call-chain diagram
- Interview one-liner
- Cross-project usage table

---

## Build Tool & Configuration

| Item | Details |
|------|---------|
| **Build Tool** | Gradle 8.10.2 (wrapper included — no global install needed) |
| **Java Version** | Java 21 (Zulu 21.0.1) |
| **Project Structure** | Multi-project Gradle build — 20 subprojects |
| **Dependencies** | **Zero runtime dependencies** — plain Java only. JUnit 5 for tests. |
| **Frameworks** | **None** — no Spring, no Quarkus, no external libs. Interview-friendly. |

---

## How to Run

### Prerequisites

- **Java 21+** installed (`java -version` to verify)
- No other tools needed (Gradle wrapper is included)

### Run Any Project

```bash
cd /Users/kanojik/Documents/Karan/Autodesk_AI_Development/systemDesign

# Run a specific project (replace NN-slug)
./gradlew :17-distributed-task-scheduler:run

# All 20 use cases
./gradlew :01-url-shortener:run
./gradlew :02-rate-limiter:run
./gradlew :03-notification-system:run
./gradlew :04-chat-system:run
./gradlew :05-social-media-feed:run
./gradlew :06-parking-lot:run
./gradlew :07-distributed-cache:run
./gradlew :08-ride-sharing:run
./gradlew :09-search-autocomplete:run
./gradlew :10-ecommerce:run
./gradlew :11-payment-system:run
./gradlew :12-news-feed:run
./gradlew :13-video-streaming:run
./gradlew :14-realtime-collaboration:run
./gradlew :15-file-storage:run
./gradlew :16-stock-trading:run
./gradlew :17-distributed-task-scheduler:run
./gradlew :18-observability-platform:run
./gradlew :19-api-gateway-service-mesh:run
./gradlew :20-distributed-message-queue:run

# Compile everything
./gradlew compileJava

# Run tests
./gradlew test
```

### Open the Canvas Hub

```bash
open canvas/index.html            # macOS default browser
# or in Cursor / VS Code: right-click canvas/index.html → "Open with Live Preview"
```

### IntelliJ IDEA (for Java code)

1. Open the `systemDesign/` folder as a project
2. IntelliJ auto-detects Gradle → click **Load Gradle Project**
3. Right-click any `*App.java` → **Run**
4. If you see "outside of source root" → right-click project → **Reload Gradle Project**

---

## How to Use for Interview Prep

Structured plan for someone with an interview 2–4 weeks out.

### Week 1 — Foundations + easy warm-up (5 use cases)

- Read all 3 foundation canvases (F1 · F2 · F3)
- Use cases 01, 02, 06, 09, 07 (Easy → Medium → one Hard)
- For each: open all 3 canvases, run the Java demo, read the interview walkthrough

### Week 2 — Distributed systems classics (5 use cases)

- 03 Notification · 04 Chat · 05 Social Feed · 12 News Feed · 20 Message Queue
- Focus on: fan-out patterns, WebSocket, producer-consumer, ordering guarantees

### Week 3 — Data-heavy + geo + streaming (5 use cases)

- 08 Ride-Sharing · 13 Video Streaming · 15 File Storage · 18 Observability · 19 API Gateway
- Focus on: sharding, geospatial, CDN, time-series compression, service mesh

### Week 4 — Correctness-critical (5 use cases)

- 10 E-Commerce · 11 Payment · 14 Real-time Collab · 16 Stock Trading · 17 Task Scheduler
- Focus on: Saga, ledger + idempotency, CRDT/OT, matching engine, exactly-once semantics

### Daily rhythm (per use case, ~45 min)

1. 5 min — glance `canvas/NN-slug/hld.html`, walk both flow-steppers
2. 10 min — read `canvas/NN-slug/deepdive.html` trade-offs + interview walkthrough sections
3. 10 min — skim `canvas/NN-slug/lld.html` class map, read one verbatim code snippet
4. 10 min — run the Java demo (`./gradlew :NN-slug:run`), read the output
5. 10 min — write a 30-second pitch for this system (whiteboard test)

---

## Philosophy

- **Interview prep**, not production deployment
- **Clarity over completeness** — easy to explain in 30–45 minutes
- **Java first** — the lingua franca of system design interviews
- **Show the ugly code first** — anti-pattern before the clean solution
- **Heavy comments** — explain wiring, call chains, "why" not just "what"
- **Every project runs** — no stubs, no TODOs, every demo is executable
- **AppConfig is the only factory** — services never say `new ConcreteClass()`
- **Interactive canvases > static PDFs** — click, step, explore; still prints to PDF on demand

---

## Tech Stack

| Layer | Technology | Purpose |
|-------|-----------|---------|
| Language | Java 21 | Primary implementation |
| Build | Gradle 8.10.2 | Multi-project build |
| Testing | JUnit 5.10.2 | Unit tests (optional per project) |
| Docs | Markdown + ASCII diagrams | Long-form documentation |
| Canvas | HTML + SVG + vanilla JS + CSS | Interactive study surface (zero deps) |
| IDE | IntelliJ IDEA + Cursor | Java code + canvas Live Preview |
| Runtime | Zero dependencies | Plain Java — no Spring, no libs |

---

## Repository Stats

| Metric | Count |
|--------|-------|
| Projects | 20 |
| Java source files | 823 |
| Documentation files | 161 |
| Interactive canvases | 64 (3 foundations + 20 use cases × 3) |
| Design patterns | 19 |
| Demos (runnable) | ~200 across all projects |
| All projects compile | Yes |
| External dependencies | Zero (runtime) |

---

## For AI Assistants (Cursor / Claude / etc.)

Project-level orientation lives in **[`AGENTS.md`](AGENTS.md)** (read this first).
Canvas building recipe + layout hard-rules: **[`.cursor/rules/canvas.mdc`](.cursor/rules/canvas.mdc)**.
Behavioural preferences: `.memory/PREFERENCES.md` (gitignored, local).
