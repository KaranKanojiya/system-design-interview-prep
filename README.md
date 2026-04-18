# System Design Interview Prep

A hands-on repository for learning, revising, and practicing the **top 10 system design interview problems**.

Each project includes:
- **HLD** — High-Level Design with architecture, scaling, and tradeoffs
- **LLD** — Low-Level Design with classes, interfaces, and patterns
- **Code** — Interview-friendly Java implementation (not production-grade)
- **Design Patterns** — Deep explanations of every pattern used
- **CAP Theorem** — Consistency vs Availability analysis
- **Caching** — What to cache, TTL, invalidation strategies
- **Cloud Mapping** — AWS / GCP / Azure service mapping
- **Quick Revision** — 1-minute interview refresher per project

## Projects

| # | Project | Difficulty | Status |
|---|---------|-----------|--------|
| 01 | [URL Shortener (TinyURL)](01-url-shortener/) | Easy-Medium | Done |
| 02 | [Rate Limiter](02-rate-limiter/) | Medium | Done |
| 03 | [Notification System](03-notification-system/) | Medium | Done |
| 04 | [Chat System (WhatsApp)](04-chat-system/) | Hard | Done |
| 05 | [Social Media Feed (Twitter)](05-social-media-feed/) | Hard | Done |
| 06 | [Parking Lot System](06-parking-lot/) | Easy-Medium | Done |
| 07 | Distributed Cache (Redis) | Hard | Planned |
| 08 | Ride-Sharing (Uber) | Hard | Planned |
| 09 | Search Autocomplete | Medium | Planned |
| 10 | E-Commerce (Amazon) | Hard | Planned |

## Philosophy

- **Interview prep**, not production deployment
- **Clarity over completeness** — easy to explain in 30-45 minutes
- **Java first**, Python only where it genuinely helps
- **Conceptually strong** — design choices are explained, not just implemented

## Stack

- **Primary**: Java 17+, Gradle
- **Secondary**: Python (simulation, benchmarking — only where beneficial)
- **Docs**: Markdown with ASCII diagrams

## How to Use

1. Pick a project folder (e.g., `01-url-shortener/`)
2. Read the **README** for a 1-minute revision
3. Deep-dive into `docs/hld/` and `docs/lld/` for full design
4. Browse `src/` for interview-ready code
5. Review `docs/patterns/` before discussing design choices
