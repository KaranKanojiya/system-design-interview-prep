# System Design Interview Prep

A comprehensive, hands-on repository for learning, revising, and practicing **16 system design interview problems** — fully implemented in **Java 21** with design docs, pattern analysis, and interview walkthroughs.

---

## What This Repo Contains

- **16 complete projects** — each a standalone system design problem with working Java code
- **614 Java source files** — interview-friendly implementations (not production-grade)
- **152 Markdown documents** — HLD, LLD, patterns, CAP analysis, caching, cloud mapping, interview walkthroughs
- **18 GoF design patterns** documented with code, UML, and cross-project comparison
- **3 root-level reference docs** — estimation cheatsheet, pattern reference (~4000 lines), UML diagrams

---

## Projects

| # | Project | Key Topics | Difficulty | Java Files |
|---|---------|-----------|-----------|-----------|
| 01 | [URL Shortener (TinyURL)](01-url-shortener/) | Strategy, Builder, Base62 encoding | Easy-Medium | 31 |
| 02 | [Rate Limiter](02-rate-limiter/) | 5 algorithms (Token Bucket, Leaky Bucket, Fixed/Sliding Window) | Medium | 29 |
| 03 | [Notification System](03-notification-system/) | Observer, Producer-Consumer, Template Method | Medium | 41 |
| 04 | [Chat System (WhatsApp)](04-chat-system/) | Mediator, Command, WebSocket simulation, presence | Hard | 40 |
| 05 | [Social Media Feed (Twitter)](05-social-media-feed/) | Composite, hybrid fan-out (celebrity problem), trending | Hard | 45 |
| 06 | [Parking Lot System](06-parking-lot/) | Facade, State, 9 patterns, SOLID, inheritance | Easy-Medium | 52 |
| 07 | [Distributed Cache (Redis)](07-distributed-cache/) | LRU/LFU O(1), consistent hashing, virtual nodes, Proxy | Hard | 38 |
| 08 | [Ride-Sharing (Uber)](08-ride-sharing/) | QuadTree, GeoHash, Haversine, surge pricing, Decorator | Hard | 52 |
| 09 | [Search Autocomplete](09-search-autocomplete/) | Trie (Standard/Compressed/TopK O(1)), time-decay ranking | Medium | 40 |
| 10 | [E-Commerce (Amazon)](10-ecommerce/) | Saga (orchestration + compensation), CQRS, inventory | Hard | 62 |
| 11 | [Payment System (Stripe/UPI)](11-payment-system/) | Double-entry ledger, idempotency, fraud detection, webhooks | Hard | 62 |
| 12 | [News Feed (Facebook/LinkedIn)](12-news-feed/) | Algorithmic ranking, cursor pagination, hybrid fan-out | Hard | 50 |
| 13 | [Video Streaming (YouTube/Netflix)](13-video-streaming/) | Transcoding pipeline, adaptive bitrate (HLS/DASH), CDN | Hard | 55 |
| 14 | [Real-time Collaboration (Google Docs)](14-realtime-collaboration/) | OT/CRDT, conflict resolution, cursor presence, versioning | Hard | 50 |
| 15 | [File Storage (Google Drive/Dropbox)](15-file-storage/) | Chunked upload, SHA-256 dedup, delta sync, Flyweight | Hard | 58 |
| 16 | [Stock Trading (Zerodha/Upstox)](16-stock-trading/) | Order matching engine, price-time priority, circuit breaker | Hard | 56 |

---

## Per-Project Structure

Every project follows the same template:

```
XX-project-name/
├── docs/
│   ├── hld/HIGH_LEVEL_DESIGN.md        — Architecture, scaling, back-of-envelope
│   ├── lld/LOW_LEVEL_DESIGN.md         — Classes, interfaces, workflows
│   ├── patterns/DESIGN_PATTERNS.md     — Every pattern used, with anti-pattern vs clean
│   ├── tradeoffs/CAP_THEOREM.md        — CP vs AP analysis per component
│   ├── tech/TECHNOLOGIES.md            — Production tech comparison (Redis, Kafka, etc.)
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
│   ├── config/AppConfig.java           — Factory (the ONLY place with "new ConcreteClass()")
│   ├── exception/                      — Domain exceptions
│   └── {Project}App.java              — Main demo with 9-12 runnable demos
├── README.md                           — 1-minute interview revision
└── build.gradle                        — Gradle subproject config
```

---

## Root-Level Docs

```
docs/
├── DESIGN_PATTERNS_REFERENCE.md   — ~4000 lines, all 18 patterns with code examples
├── DESIGN_PATTERNS_UML.md         — ASCII UML diagrams, cross-project pattern matrix
└── ESTIMATION_CHEATSHEET.md       — Universal estimation tables (not project-specific)
```

---

## Design Patterns Tracked (18)

| Category | Patterns |
|----------|----------|
| **Creational** (3) | Builder, Factory, Singleton |
| **Structural** (7) | Repository, Composite, Facade, Proxy, Decorator, Flyweight, Memento* |
| **Behavioral** (7) | Strategy, Observer, Template Method, Chain of Responsibility, Mediator, Command, State |
| **Concurrency** (1) | Producer-Consumer |

*Memento is GoF Behavioral but tracked under structural in some categorizations in this repo.

Every pattern includes:
- Ugly anti-pattern code (what NOT to do)
- Clean pattern-based solution
- Numbered call chain diagram
- Interview one-liner
- Cross-project usage table

---

## Build Tool & Configuration

| Item | Details |
|------|---------|
| **Build Tool** | Gradle 8.10.2 (wrapper included — no global install needed) |
| **Java Version** | Java 21 (Zulu 21.0.1) |
| **Project Structure** | Multi-project Gradle build — 16 subprojects |
| **Root Config** | `build.gradle` applies Java plugin + Java 21 to all subprojects |
| **Subproject Config** | Each `XX-project/build.gradle` sets `application.mainClass` |
| **Dependencies** | **Zero runtime dependencies** — plain Java only. JUnit 5 for tests. |
| **Frameworks** | **None** — no Spring, no Quarkus, no external libs. Interview-friendly. |

### Key Files

| File | Purpose |
|------|---------|
| `settings.gradle` | Declares all 16 subprojects |
| `build.gradle` | Root config — Java 21, Maven Central for all subprojects |
| `gradle/wrapper/` | Gradle wrapper (run without installing Gradle globally) |
| `gradlew` / `gradlew.bat` | Unix / Windows wrapper scripts |

---

## How to Run

### Prerequisites

- **Java 21+** installed (`java -version` to verify)
- No other tools needed (Gradle wrapper is included)

### Run Any Project

```bash
cd /Users/kanojik/Documents/Karan/Autodesk_AI_Development/systemDesign

# Run a specific project
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

# Compile all projects
./gradlew compileJava

# Run tests (if any)
./gradlew test
```

### IntelliJ IDEA

1. Open the `systemDesign/` folder as a project
2. IntelliJ auto-detects Gradle → click **Load Gradle Project**
3. Right-click any `*App.java` → **Run**
4. If you see "outside of source root" → right-click project → **Reload Gradle Project**

---

## How to Use for Interview Prep

### Quick Revision (5 min per project)
1. Read the project's `README.md` → 1-minute interview revision bullets
2. Skim `revision/INTERVIEW_WALKTHROUGH.md` → phase-by-phase timing guide

### Deep Dive (30-60 min per project)
1. Read `docs/hld/HIGH_LEVEL_DESIGN.md` → architecture, scaling, back-of-envelope
2. Read `docs/lld/LOW_LEVEL_DESIGN.md` → classes, interfaces, workflows
3. Browse `src/` → run the demo, read the code
4. Review `docs/patterns/DESIGN_PATTERNS.md` → pattern explanations

### Cross-Project Pattern Review
1. Read `docs/DESIGN_PATTERNS_REFERENCE.md` → all 18 patterns with code
2. Read `docs/DESIGN_PATTERNS_UML.md` → ASCII UML + cross-project matrix

---

## Philosophy

- **Interview prep**, not production deployment
- **Clarity over completeness** — easy to explain in 30-45 minutes
- **Java first** — the lingua franca of system design interviews
- **Show the ugly code first** — anti-pattern before the clean solution
- **Heavy comments** — explain wiring, call chains, "why" not just "what"
- **Every project runs** — no stubs, no TODOs, every demo is executable
- **AppConfig is the only factory** — services never say `new ConcreteClass()`

---

## Tech Stack

| Layer | Technology | Purpose |
|-------|-----------|---------|
| Language | Java 21 | Primary implementation |
| Build | Gradle 8.10.2 | Multi-project build |
| Testing | JUnit 5.10.2 | Unit tests (optional per project) |
| Docs | Markdown + ASCII diagrams | All documentation |
| IDE | IntelliJ IDEA (recommended) | Development |
| Runtime | Zero dependencies | Plain Java — no Spring, no libs |

---

## Repository Stats

| Metric | Count |
|--------|-------|
| Projects | 16 |
| Java source files | 614 |
| Documentation files | 152 |
| Design patterns | 18 |
| Total lines of code | ~133,000+ |
| Demos (runnable) | ~170 across all projects |
| All projects compile | Yes |
| External dependencies | Zero (runtime) |
