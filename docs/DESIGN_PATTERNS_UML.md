# Design Patterns — UML Diagrams

> Visual reference for every pattern used in this repo. ASCII UML diagrams you can sketch on a whiteboard in 30 seconds. All flows have **numbered sequences** for easy walkthrough.

---

## Pattern Categories (GoF + Enterprise)

| Category | Patterns | Purpose |
|----------|----------|---------|
| **Creational** | Builder, Factory, Singleton | How objects get **created** |
| **Structural** | Repository (Enterprise/DDD), Composite | How objects are **composed** and connected |
| **Behavioral** | Strategy, Observer, Template Method, Chain of Responsibility, Mediator, Command | How objects **communicate** and share responsibilities |
| **Concurrency** | Producer-Consumer | How objects **coordinate** across threads |

```
                    ┌─────────────────────────────────────────────┐
                    │          Design Patterns in This Repo        │
                    └─────────────────┬───────────────────────────┘
            ┌─────────────┬───────────┼──────────┬────────────────┐
            ▼             ▼           ▼          ▼                ▼
     ┌─CREATIONAL─┐ ┌─STRUCTURAL─┐ ┌──BEHAVIORAL──┐      ┌─CONCURRENCY─┐
     │            │ │            │ │              │      │             │
     │ • Builder  │ │ •Repository│ │ • Strategy   │      │ • Producer- │
     │ • Factory  │ │  (DDD)     │ │ • Observer   │      │   Consumer  │
     │ • Singleton│ │ •Composite │ │ • Template   │      │             │
     │            │ │            │ │   Method     │      │             │
     │            │ │            │ │ • Chain of   │      │             │
     │            │ │            │ │   Respons.   │      │             │
     │            │ │            │ │ • Mediator   │      │             │
     │            │ │            │ │ • Command    │      │             │
     └────────────┘ └────────────┘ └──────────────┘      └─────────────┘
```

---

# CREATIONAL PATTERNS

> *How objects get created — construction logic isolated from business logic.*

---

## 1. Builder Pattern

> "Separate the construction of a complex object from its representation."

**Category**: Creational | **Used in**: All 3 projects

### Generic UML

```
┌───────────────────┐         ┌─────────────────────────────┐
│     Client         │         │         Product              │
│───────────────────│         │─────────────────────────────│
│                   │         │ - field1: Type               │
│ Product p =       │         │ - field2: Type               │
│   Product.builder()│         │ - field3: Type (optional)    │
│     .field1(val)  │         │ - field4: Type (optional)    │
│     .field2(val)  │────────▶│─────────────────────────────│
│     .build();     │         │ - Product(Builder b)  ←PRIVATE│
│                   │         │ + getField1(): Type           │
└───────────────────┘         │ + getField2(): Type           │
                              └──────────────┬──────────────┘
                                             │ inner class
                              ┌──────────────▼──────────────┐
                              │     Product.Builder          │
                              │─────────────────────────────│
                              │ - field1: Type               │
                              │ - field2: Type               │
                              │ - field3: Type               │
                              │ - field4: Type               │
                              │─────────────────────────────│
                              │ + field1(val): Builder ←return this│
                              │ + field2(val): Builder       │
                              │ + field3(val): Builder       │
                              │ + field4(val): Builder       │
                              │ + build(): Product           │
                              └─────────────────────────────┘
```

### URL Shortener — Url.Builder

```
┌──────────────────────────────────────────┐
│                  Url                      │
│──────────────────────────────────────────│
│ - id: String                             │
│ - shortCode: String                      │
│ - originalUrl: String                    │
│ - createdAt: LocalDateTime               │
│ - expiresAt: LocalDateTime    (optional) │
│ - clickCount: long                       │
│ - customAlias: String         (optional) │
│ - userId: String              (optional) │
│──────────────────────────────────────────│
│ «private» Url(Builder b)                 │
│ + isExpired(): boolean                   │
│ + incrementClickCount(): void            │
└────────────────────┬─────────────────────┘
                     │
        ┌────────────▼─────────────────────┐
        │        Url.Builder                │
        │──────────────────────────────────│
        │ (same fields as Url)             │
        │──────────────────────────────────│
        │ + shortCode(v): Builder          │  ← returns 'this' for chaining
        │ + originalUrl(v): Builder        │
        │ + expiresAt(v): Builder          │  ← optional, skip if not needed
        │ + customAlias(v): Builder        │  ← optional
        │ + build(): Url                   │  ← creates immutable Url
        └──────────────────────────────────┘
```

### Numbered Sequence — Build Flow

```
Client                          Url.Builder                     Url
  │                                │                             │
  │ (1) new Url.Builder()          │                             │
  │───────────────────────────────▶│ all fields = defaults       │
  │                                │                             │
  │ (2) .shortCode("abc123")       │                             │
  │───────────────────────────────▶│ this.shortCode = "abc123"   │
  │  ◀── returns this (Builder)    │                             │
  │                                │                             │
  │ (3) .originalUrl("http://...") │                             │
  │───────────────────────────────▶│ this.originalUrl = "http://"│
  │  ◀── returns this (Builder)    │                             │
  │                                │                             │
  │ (4) .expiresAt(now.plusDays(30))│                             │
  │───────────────────────────────▶│ this.expiresAt = ...        │
  │  ◀── returns this (Builder)    │                             │
  │                                │                             │
  │ (5) .build()                   │                             │
  │───────────────────────────────▶│                             │
  │                                │ (6) new Url(this)           │
  │                                │────────────────────────────▶│
  │                                │                             │ (7) copies ALL
  │                                │                             │ fields from
  │  ◀──────────────────────────────── (8) returns Url (immutable)│ builder
  │                                                              │
```

**Steps explained:**
1. Client creates a new Builder — all fields start with defaults/null
2-4. Client sets only the fields it needs — each setter returns `this` for chaining
5. Client calls `build()` — triggers validation
6. Builder calls private `Url(Builder b)` constructor
7. Constructor copies every field from Builder into the Url object
8. Immutable Url is returned — no setters exist, object cannot be modified

### Notification System — Notification.Builder (15 fields)

```
Why 15 fields NEED Builder:

  UGLY (telescoping constructor):
  new Notification("id", "user1", "t1", PUSH, HIGH, PENDING,
      "Hello", "Body", map, null, null, null, 0, 5, now)
              ↑ UNREADABLE — which null is which?

  CLEAN (builder):
  Notification.builder()           // (1) create builder
      .userId("user1")             // (2) set required fields
      .channel(PUSH)               // (3)
      .priority(HIGH)              // (4)
      .subject("Hello")            // (5) set optional fields
      .body("Body")                // (6)
      .maxRetries(5)               // (7) has default, override if needed
      .build()                     // (8) validate + create immutable object
              ↑ SELF-DOCUMENTING — you know exactly what each value is
```

---

## 2. Factory Pattern

> "Centralize object creation so the rest of the code only depends on interfaces."

**Category**: Creational | **Used in**: All 3 projects

### Generic UML

```
┌────────────────────────────────────┐
│            Factory                  │
│   (AppConfig in our projects)      │
│────────────────────────────────────│
│                                    │      ┌──────────┐
│ + createService(): Service         │─────▶│ Service  │
│   {                                │      └──────────┘
│     repo = new InMemoryRepo()      │           │
│     strategy = new Base62()        │           │ uses
│     return new Service(repo, strat)│           ▼
│   }                                │      ┌──────────┐    ┌──────────┐
│                                    │      │ <<if>>   │    │ <<if>>   │
│ + createController(): Controller   │      │Repository│    │ Strategy │
│   {                                │      └──────────┘    └──────────┘
│     service = createService()      │
│     return new Controller(service) │   Only the Factory knows
│   }                                │   the concrete types!
└────────────────────────────────────┘
```

### Notification System — Complex Factory Wiring (Numbered)

```
AppConfig.createController()
  │
  │ ──── LAYER 1: DATA ────────────────────────────────────────
  │
  ├── (1)  notifRepo    = new InMemoryNotificationRepository()
  ├── (2)  prefRepo     = new InMemoryPreferenceRepository()
  ├── (3)  templateRepo = new InMemoryTemplateRepository()
  │
  │ ──── LAYER 2: SEED DATA ───────────────────────────────────
  │
  ├── (4)  seedTemplates(templateRepo)
  │          → "order-confirmation", "otp-verification",
  │            "price-drop-alert", "welcome-message"
  ├── (5)  seedPreferences(prefRepo)
  │          → alice (all ON), bob (SMS OFF), carol (quiet hrs)
  │
  │ ──── LAYER 3: STRATEGIES (Channel Handlers) ───────────────
  │
  ├── (6)  handlers = Map<Channel, NotificationHandler>
  │          PUSH   → new PushNotificationHandler()
  │          EMAIL  → new EmailNotificationHandler()
  │          SMS    → new SmsNotificationHandler()
  │          IN_APP → new InAppNotificationHandler()
  │
  │ ──── LAYER 4: SERVICES (Business Logic) ───────────────────
  │
  ├── (7)  templateEngine = new SimpleTemplateEngine()
  ├── (8)  prefService    = new PreferenceService(prefRepo)
  ├── (9)  templateService= new TemplateService(templateRepo, templateEngine)
  ├── (10) tracker        = new DeliveryTracker()
  ├── (11) queue          = new InMemoryPriorityQueue()
  ├── (12) notifService   = new NotificationService(
  │                              notifRepo, prefService, templateService,
  │                              tracker, queue, handlers)
  │
  │ ──── LAYER 5: API ─────────────────────────────────────────
  │
  └── (13) return new NotificationController(notifService)

  // The controller knows NOTHING about InMemory, Push, Email, etc.
  // It only knows: NotificationService (interface-backed)
```

**Dependency flow**: Data (1-3) → Seed (4-5) → Strategies (6) → Services (7-12) → API (13)

---

## 3. Singleton Pattern (Conceptual)

> "Ensure a class has only one instance and provide a global point of access."

**Category**: Creational | **Used in**: URL Shortener

### Generic UML

```
┌────────────────────────────────┐
│         Singleton               │
│────────────────────────────────│
│ «static» - instance: Singleton │
│ - state: SomeState             │
│────────────────────────────────│
│ «static» + getInstance()       │
│ «private» Singleton()          │
│ + doWork(): Result             │
└────────────────────────────────┘

Thread-safe approaches (interview question!):
  (1) Eager:          private static final INSTANCE = new Singleton();
  (2) Lazy + sync:    synchronized getInstance() { if null, create }
  (3) Double-checked: volatile field + synchronized block
  (4) Enum:           enum Singleton { INSTANCE; }  ← Effective Java recommends
```

### URL Shortener — ID Counter

```
┌─────────────────────────────────────┐
│      UrlShortenerService            │
│─────────────────────────────────────│
│ - counter: AtomicLong = new(100000) │  ← ONE counter for entire app
│─────────────────────────────────────│
│ + shortenUrl(request)               │
│   (1) id = counter.incrementAndGet()│  ← thread-safe, unique every time
│   (2) shortCode = strategy.encode(  │
│         String.valueOf(id))         │
└─────────────────────────────────────┘

Why ONE instance matters:
  Server A: counter = AtomicLong(0)   → codes: 1, 2, 3...
  Server B: counter = AtomicLong(0)   → codes: 1, 2, 3...  ← COLLISION!

Distributed solution:
  ┌──────────────┐
  │  ZooKeeper   │  (1) assigns counter RANGES to each server
  │──────────────│
  │ Server A:    │  (2) range 0 — 999,999
  │ Server B:    │  (3) range 1,000,000 — 1,999,999
  │ Server C:    │  (4) range 2,000,000 — 2,999,999
  └──────────────┘
  (5) Each server generates IDs locally within its range — no coordination
  (6) When range exhausted, server requests a new range from ZooKeeper
```

---

# STRUCTURAL PATTERNS

> *How objects are composed — building larger structures from smaller pieces.*

---

## 4. Repository Pattern (Enterprise/DDD)

> "Mediate between the domain and data mapping layers using a collection-like interface."

**Category**: Structural (Enterprise) | **Used in**: All 3 projects

### Generic UML

```
┌──────────────────────┐        ┌───────────────────────┐
│      Service          │        │    <<interface>>       │
│──────────────────────│        │     Repository         │
│ - repo: Repository    │───────▶│───────────────────────│
│──────────────────────│        │ + save(entity): Entity │
│ + doBusinessLogic()  │        │ + findById(id): Opt    │
│   → repo.save(...)   │        │ + delete(id): void     │
│   → repo.findById(.)│        └───────────┬───────────┘
└──────────────────────┘                    │ implements
                                ┌───────────┼───────────┐
                                │                       │
                         ┌──────▼────────┐    ┌────────▼──────────┐
                         │  InMemory     │    │    Redis           │
                         │  Repository   │    │   Repository       │
                         │──────────────│    │──────────────────│
                         │ - map:       │    │ - redisClient:   │
                         │  ConcurrentHash│  │   JedisPool      │
                         │──────────────│    │──────────────────│
                         │ + save()     │    │ + save()          │
                         │ + findById() │    │ + findById()      │
                         │ + delete()   │    │ + delete()        │
                         └──────────────┘    └──────────────────┘
                          ↑ our code           ↑ swap to this for
                          (interview demo)       production — ZERO
                                                 changes to Service
```

### URL Shortener — UrlRepository

```
┌────────────────────────────┐      ┌─────────────────────────────┐
│   UrlShortenerService      │      │      <<interface>>           │
│────────────────────────────│      │      UrlRepository           │
│ - repo: UrlRepository      │─────▶│─────────────────────────────│
│────────────────────────────│      │ + findByShortCode(code)     │
│ + shortenUrl(req)          │      │       : Optional<Url>       │
│   → repo.save(url)         │      │ + save(url): Url            │
│ + redirect(code)           │      │ + deleteByShortCode(code)   │
│   → repo.findByShortCode() │      │ + existsByShortCode(code)   │
│ + deleteUrl(code)          │      │       : boolean              │
│   → repo.deleteByShortCode()│     │ + count(): long             │
└────────────────────────────┘      └──────────────┬──────────────┘
                                                   │
                                    ┌──────────────▼──────────────┐
                                    │  InMemoryUrlRepository      │
                                    │─────────────────────────────│
                                    │ - store:                    │
                                    │   ConcurrentHashMap         │
                                    │   <String, Url>             │
                                    │   (key = shortCode)         │
                                    │─────────────────────────────│
                                    │ + save(url)                 │
                                    │   → store.put(code, url)    │
                                    │ + findByShortCode(code)     │
                                    │   → Optional.ofNullable(    │
                                    │       store.get(code))      │
                                    └─────────────────────────────┘
```

### Notification System — 3 Repositories

```
                    ┌────────────────────────┐
                    │  NotificationService   │
                    │────────────────────────│
                    │ - notifRepo            │──▶ NotificationRepository ──▶ InMemoryNotificationRepo
                    │ - prefService          │        (save, findById, findByUserId, findRetryable)
                    │ - templateService      │
                    └────────────────────────┘
                                │
                    ┌───────────┴───────────┐
                    │                       │
            ┌───────▼──────┐      ┌────────▼────────┐
            │ PrefService  │      │ TemplateService  │
            │──────────────│      │─────────────────│
            │ - repo       │      │ - repo           │
            └──────┬───────┘      └────────┬────────┘
                   │                       │
                   ▼                       ▼
        PreferenceRepository      TemplateRepository
                   │                       │
                   ▼                       ▼
        InMemoryPreferenceRepo   InMemoryTemplateRepo
```

### Numbered Sequence — The Swap Promise

```
How to swap storage with ZERO service changes:

  (1) TODAY (interview demo):
      UrlRepository repo = new InMemoryUrlRepository();

  (2) TOMORROW (add Redis):
      UrlRepository repo = new RedisUrlRepository(jedisPool);

  (3) NEXT WEEK (switch to Cassandra):
      UrlRepository repo = new CassandraUrlRepository(session);

  (4) Change happens in AppConfig ONLY:
      public static UrlShortenerService createDefaultService() {
          // UrlRepository repo = new InMemoryUrlRepository();  // old
          UrlRepository repo = new RedisUrlRepository(jedisPool);  // new
          return new UrlShortenerService(repo, strategy, BASE_URL);
      }

  (5) UrlShortenerService — ZERO changes. It only calls:
      repo.save(url)
      repo.findByShortCode(code)
      // Doesn't know or care if it's HashMap, Redis, or Cassandra
```

---

## 12. Composite Pattern

> "Compose objects into tree structures. Let clients treat individual objects and compositions uniformly."

**Category**: Structural | **Used in**: Social Media Feed

### Generic UML

```
┌─────────────────────┐
│      Client          │        ┌──────────────────────┐
│─────────────────────│        │   <<interface>>       │
│ - component:        │        │     Component         │
│   Component         │───────▶│──────────────────────│
│─────────────────────│        │ + operation(): void   │
│ + doWork()          │        └──────────┬───────────┘
│   → component       │                   │
│     .operation()    │        ┌──────────┼───────────┐
└─────────────────────┘        │                      │
                         ┌─────▼──────┐    ┌──────────▼──────────┐
                         │   Leaf     │    │    Composite         │
                         │────────────│    │─────────────────────│
                         │+operation()│    │ - children:          │
                         │ → do work  │    │   List<Component>    │
                         └────────────┘    │─────────────────────│
                                           │ + operation()        │
                                           │   → delegate to      │
                                           │     chosen child     │
                                           │ + add(Component)     │
                                           └─────────────────────┘

Key insight: Composite implements the SAME interface as its children.
The client cannot tell if it is talking to a Leaf or a Composite.
```

### Social Media Feed — HybridFanoutStrategy as Composite

```
┌─────────────────────┐
│     FeedService      │        ┌──────────────────────────┐
│─────────────────────│        │      <<interface>>         │
│ - fanoutStrategy:   │        │     FanoutStrategy         │
│   FanoutStrategy    │───────▶│──────────────────────────│
│─────────────────────│        │ + distribute(tweet,       │
│ + publishTweet()    │        │     poster, followers)    │
│   → fanoutStrategy  │        └────────────┬─────────────┘
│     .distribute()   │                     │
└─────────────────────┘          ┌──────────┼───────────┐
                                 │          │           │
                          ┌──────▼────┐ ┌───▼─────┐ ┌──▼──────────────────┐
                          │ FanoutOn  │ │FanoutOn │ │ HybridFanout        │
                          │ Write     │ │ Read    │ │ Strategy             │
                          │ Strategy  │ │Strategy │ │ (THE COMPOSITE)      │
                          │──────────│ │─────────│ │─────────────────────│
                          │+distribute│ │+distribute│ │ - writeStrategy:   │
                          │ → push to │ │ → no-op │ │   FanoutOnWrite     │
                          │   all     │ │ (pull   │ │ - readStrategy:     │
                          │   follower│ │  later) │ │   FanoutOnRead      │
                          │   caches  │ │         │ │─────────────────────│
                          └──────────┘ └─────────┘ │ + distribute()       │
                                 ▲                  │   → if celebrity:    │
                                 │                  │     readStrategy     │
                                 │                  │       .distribute()  │
                                 └──────────────────│   → else:            │
                                  contained inside  │     writeStrategy    │
                                                    │       .distribute()  │
                                                    └─────────────────────┘

HybridFanoutStrategy implements FanoutStrategy (same interface as its children)
AND contains references to FanoutOnWriteStrategy and FanoutOnReadStrategy.
It IS-A FanoutStrategy. It HAS-A FanoutOnWriteStrategy and FanoutOnReadStrategy.
```

### Numbered Sequence — Hybrid Fan-out Decision Flow

```
FeedService              HybridFanoutStrategy         FanoutOnWriteStrategy    FanoutOnReadStrategy
    │                           │                            │                        │
    │ (1) tweet arrives         │                            │                        │
    │   fanoutStrategy          │                            │                        │
    │     .distribute(tweet,    │                            │                        │
    │       poster, followers)  │                            │                        │
    │──────────────────────────▶│                            │                        │
    │                           │                            │                        │
    │                           │ (2) check poster           │                        │
    │                           │     .isCelebrity()         │                        │
    │                           │                            │                        │
    │                           │── NORMAL USER ────────────▶│                        │
    │                           │ (3a) delegate to write     │                        │
    │                           │      strategy              │                        │
    │                           │                            │ (4a) push tweet to     │
    │                           │                            │      ALL follower      │
    │                           │                            │      timeline caches   │
    │                           │                            │                        │
    │                           │── CELEBRITY ──────────────────────────────────────▶│
    │                           │ (3b) delegate to read      │                       │
    │                           │      strategy              │                       │
    │                           │                            │  (4b) do nothing —    │
    │                           │                            │       followers pull  │
    │                           │                            │       at read time    │
    │                           │                            │                       │
    │◀──────────────────────────│                            │                       │
    │                           │                            │                       │

Decision logic:
  (1) Tweet arrives at FeedService, which calls fanoutStrategy.distribute()
  (2) HybridFanoutStrategy checks: is the poster a celebrity?
  (3a) NORMAL USER → delegate to FanoutOnWriteStrategy
  (4a) Write strategy pushes tweet into every follower's cached timeline
  (3b) CELEBRITY → delegate to FanoutOnReadStrategy
  (4b) Read strategy does nothing — followers will pull at read time
```

---

# BEHAVIORAL PATTERNS

> *How objects communicate — distributing responsibilities and managing algorithms.*

---

## 5. Strategy Pattern

> "Define a family of algorithms, encapsulate each one, and make them interchangeable."

**Category**: Behavioral | **Used in**: All 3 projects (THE most important pattern)

### Generic UML

```
┌─────────────────────┐
│      Context         │        ┌──────────────────────┐
│─────────────────────│        │   <<interface>>       │
│ - strategy: Strategy │───────▶│     Strategy          │
│─────────────────────│        │──────────────────────│
│ + doWork()           │        │ + execute(): Result   │
│   → strategy.execute()│       └──────────┬───────────┘
└─────────────────────┘                    │
                                ┌──────────┼───────────┐
                                │          │           │
                          ┌─────▼────┐ ┌───▼─────┐ ┌──▼──────┐
                          │StrategyA │ │StrategyB│ │StrategyC│
                          │──────────│ │─────────│ │─────────│
                          │+execute()│ │+execute()│ │+execute()│
                          └──────────┘ └─────────┘ └─────────┘
```

### URL Shortener — Encoding Strategy (Injected Once)

```
┌─────────────────────────────┐
│    UrlShortenerService      │        ┌─────────────────────────┐
│─────────────────────────────│        │     <<interface>>        │
│ - strategy: EncodingStrategy│───────▶│    EncodingStrategy      │
│ - repo: UrlRepository       │        │─────────────────────────│
│─────────────────────────────│        │ + encode(input): String  │
│ + shortenUrl(request)       │        │ + name(): String         │
│ + redirect(shortCode)       │        └────────────┬────────────┘
└─────────────────────────────┘                     │
                                         ┌──────────┼──────────┐
                                         │          │          │
                                  ┌──────▼───┐ ┌───▼────┐ ┌───▼──────┐
                                  │  Base62   │ │  MD5   │ │  Random  │
                                  │ Encoding  │ │Encoding│ │ Encoding │
                                  │ Strategy  │ │Strategy│ │ Strategy │
                                  │──────────│ │────────│ │──────────│
                                  │ ALPHABET  │ │ digest │ │ random   │
                                  │ ="0-9a-z  │ │ =MD5   │ │ =Secure  │
                                  │  A-Z"     │ │        │ │  Random  │
                                  │──────────│ │────────│ │──────────│
                                  │+encode() │ │+encode()│ │+encode() │
                                  │ counter  │ │ hash+  │ │ random   │
                                  │ →base62  │ │ trunc  │ │ chars    │
                                  └──────────┘ └────────┘ └──────────┘
```

### Numbered Sequence — URL Shortener Strategy Flow

```
AppConfig                    UrlShortenerService              Base62EncodingStrategy
    │                               │                                │
    │ (1) new Base62EncodingStrategy()                               │
    │──────────────────────────────▶│                                │
    │                               │                                │
    │ (2) new UrlShortenerService(  │                                │
    │       repo, strategy, baseUrl)│                                │
    │──────────────────────────────▶│ stores strategy as field       │
    │                               │                                │
    :      [later, a request comes] │                                │
    :                               │                                │
    : (3) shortenUrl(request)       │                                │
    :──────────────────────────────▶│                                │
    :                               │ (4) id = counter.increment()   │
    :                               │                                │
    :                               │ (5) strategy.encode("100001")  │
    :                               │───────────────────────────────▶│
    :                               │                                │ (6) convert
    :                               │                                │     to base62
    :                               │ (7) return "a3Bf7kQ"           │
    :                               │◀───────────────────────────────│
    :                               │                                │
    :                               │ (8) save to repository         │
    :                               │                                │
    : (9) return UrlShortenResponse │                                │
    :◀──────────────────────────────│                                │
```

### Rate Limiter — Algorithm Strategy (Map-based Dynamic Selection)

```
┌──────────────────────────────────┐
│       RateLimiterService         │       ┌──────────────────────────┐
│──────────────────────────────────│       │      <<interface>>        │
│ - strategies:                    │       │   RateLimiterStrategy     │
│   Map<Algorithm,                 │──────▶│──────────────────────────│
│       RateLimiterStrategy>       │       │ + tryConsume(key, rule)   │
│ - ruleRepo: RuleRepository       │       │       : RateLimitResult   │
│──────────────────────────────────│       │ + reset(key): void        │
│ + checkRateLimit(context)        │       │ + algorithm(): Algorithm  │
└──────────────────────────────────┘       └────────────┬─────────────┘
                                                        │
                                         ┌──────────────┼──────────┬───────────┬───────────┐
                                         │              │          │           │           │
                                   ┌─────▼────┐  ┌─────▼───┐ ┌───▼─────┐ ┌───▼─────┐ ┌───▼─────┐
                                   │  Token   │  │  Leaky  │ │ Fixed   │ │Sliding  │ │Sliding  │
                                   │  Bucket  │  │  Bucket │ │ Window  │ │ Window  │ │ Window  │
                                   │          │  │         │ │ Counter │ │  Log    │ │ Counter │
                                   └──────────┘  └─────────┘ └─────────┘ └─────────┘ └─────────┘
```

### Numbered Sequence — Rate Limiter Dynamic Strategy

```
Client              RateLimiterService          RuleRepo          TokenBucketStrategy
  │                        │                       │                      │
  │ (1) checkRateLimit(    │                       │                      │
  │      context)          │                       │                      │
  │───────────────────────▶│                       │                      │
  │                        │ (2) key = context     │                      │
  │                        │   .getRateLimitKey()  │                      │
  │                        │   → "alice:/api/orders"                      │
  │                        │                       │                      │
  │                        │ (3) findByKey(key)    │                      │
  │                        │──────────────────────▶│                      │
  │                        │                       │                      │
  │                        │ (4) return rule       │                      │
  │                        │◀──────────────────────│                      │
  │                        │   {max=5, window=10s, │                      │
  │                        │    algo=TOKEN_BUCKET}  │                      │
  │                        │                       │                      │
  │                        │ (5) strategy =        │                      │
  │                        │   strategies.get(     │                      │
  │                        │     TOKEN_BUCKET)     │                      │
  │                        │   → TokenBucketStrategy                      │
  │                        │                       │                      │
  │                        │ (6) tryConsume(key, rule)                     │
  │                        │─────────────────────────────────────────────▶│
  │                        │                       │                      │ (7) refill
  │                        │                       │                      │     tokens
  │                        │                       │                      │ (8) check
  │                        │                       │                      │     tokens>=1
  │                        │ (9) return RateLimitResult{allowed=true, remaining=4}
  │                        │◀─────────────────────────────────────────────│
  │                        │                       │                      │
  │ (10) return result     │                       │                      │
  │◀───────────────────────│                       │                      │

Key: Strategy is selected at step (5) via Map.get() — changes per request!
     URL Shortener injects strategy ONCE. Rate Limiter looks it up EACH TIME.
```

### Notification System — Channel Handler Strategy

```
┌──────────────────────────────────┐
│       NotificationService        │       ┌──────────────────────────┐
│──────────────────────────────────│       │      <<interface>>        │
│ - handlers:                      │       │   NotificationHandler     │
│   Map<Channel,                   │──────▶│──────────────────────────│
│       NotificationHandler>       │       │ + send(notification)      │
│──────────────────────────────────│       │       : DeliveryAttempt   │
│ + processQueue()                 │       │ + supportedChannel()      │
│   → handler = handlers           │       │       : Channel           │
│        .get(notif.channel)       │       │ + isAvailable(): boolean  │
│   → handler.send(notif)         │       └────────────┬─────────────┘
└──────────────────────────────────┘                    │
                                            ┌───────────┼──────────┬──────────┐
                                            │           │          │          │
                                      ┌─────▼────┐ ┌───▼────┐ ┌───▼───┐ ┌───▼────┐
                                      │  Push    │ │ Email  │ │  SMS  │ │ InApp  │
                                      │ Handler  │ │ Handler│ │Handler│ │Handler │
                                      │──────────│ │────────│ │───────│ │────────│
                                      │ FCM/APNs │ │SES/Send│ │Twilio │ │ Store  │
                                      │ 90% rate │ │Grid    │ │85%rate│ │ 100%   │
                                      │──────────│ │95% rate│ │───────│ │────────│
                                      │ +send()  │ │────────│ │+send()│ │+send() │
                                      └──────────┘ │+send() │ └───────┘ └────────┘
                                                   └────────┘
```

### Strategy Pattern Comparison Across Projects

| Aspect | URL Shortener | Rate Limiter | Notification |
|--------|--------------|-------------|--------------|
| Interface | `EncodingStrategy` | `RateLimiterStrategy` | `NotificationHandler` |
| Implementations | 3 (Base62, MD5, Random) | 5 (Token Bucket, etc.) | 4 (Push, Email, SMS, InApp) |
| Selection | Injected once at construction | Map lookup per request | Map lookup per notification |
| Selection key | N/A (fixed) | `Algorithm` enum | `Channel` enum |
| Switch cost | Change AppConfig line | Change rule in DB | Change channel in request |

---

## 6. Observer Pattern

> "When one object changes state, all its dependents are notified automatically."

**Category**: Behavioral | **Used in**: Notification System

### Generic UML

```
┌─────────────────┐          ┌──────────────────────┐
│    Subject       │          │     <<interface>>     │
│─────────────────│          │      Observer          │
│ - observers:    │          │──────────────────────│
│   List<Observer>│─────────▶│ + onEvent(event): void│
│─────────────────│          └──────────┬───────────┘
│ + addObserver() │                     │
│ + notify()      │          ┌──────────┼──────────┐
│   → for each:   │          │                     │
│     o.onEvent() │   ┌──────▼──────┐    ┌────────▼────────┐
└─────────────────┘   │   Logger    │    │  MetricsTracker  │
                      │─────────────│    │─────────────────│
                      │ + onEvent() │    │ + onEvent()      │
                      │   → log it  │    │   → count it     │
                      └─────────────┘    └─────────────────┘
```

### Notification System — Delivery Tracking

```
┌──────────────────────┐       ┌──────────────────────┐
│ NotificationService  │       │   DeliveryTracker     │
│  (Subject / Producer)│       │   (Observer)          │
│──────────────────────│       │──────────────────────│
│ - tracker:           │──────▶│ - attempts:           │
│   DeliveryTracker    │       │   Map<String,         │
│──────────────────────│       │     List<Attempt>>    │
│ + processQueue()     │       │ - sentCount: AtomicInt│
│                      │       │ - failCount: AtomicInt│
│                      │       │──────────────────────│
│                      │       │ + record(attempt)     │
│                      │       │ + printStats()        │
└──────────────────────┘       └──────────────────────┘
```

### Numbered Sequence — Delivery Observation

```
NotificationService               EmailHandler               DeliveryTracker
       │                               │                          │
       │ (1) dequeue notification      │                          │
       │     from PriorityQueue        │                          │
       │                               │                          │
       │ (2) handler = handlers        │                          │
       │       .get(EMAIL)             │                          │
       │                               │                          │
       │ (3) handler.send(notification)│                          │
       │──────────────────────────────▶│                          │
       │                               │ (4) simulate SES call   │
       │                               │ (5) 95% → SENT          │
       │ (6) return DeliveryAttempt    │                          │
       │     {status=SENT,             │                          │
       │      response="SES:msg_123"} │                          │
       │◀──────────────────────────────│                          │
       │                               │                          │
       │ (7) tracker.record(attempt)   │                          │
       │─────────────────────────────────────────────────────────▶│
       │                               │                          │ (8) add to
       │                               │                          │   attempts list
       │                               │                          │ (9) sentCount
       │                               │                          │   .incrementAndGet()
       │                               │                          │
       │ (10) update notification      │                          │
       │      status → DELIVERED       │                          │

In production (proper Observer with multiple listeners):
  (7) notifyObservers(attempt)
       ├── (8a) DeliveryTracker.record()     → store attempt
       ├── (8b) MetricsService.record()      → Prometheus counter
       ├── (8c) AuditLogger.log()            → compliance log
       └── (8d) WebhookNotifier.notify()     → callback to client
```

---

## 7. Template Method Pattern

> "Define the skeleton of an algorithm, deferring some steps to subclasses."

**Category**: Behavioral | **Used in**: Notification System

### Generic UML

```
┌───────────────────────────────────┐
│     <<abstract>> BaseProcessor    │
│───────────────────────────────────│
│ + process()          ← FINAL     │
│   {                              │
│     step1_validate()  ← common   │
│     step2_prepare()   ← common   │
│     step3_execute()   ← ABSTRACT │
│     step4_cleanup()   ← common   │
│   }                              │
│                                  │
│ # step1_validate()    ← concrete │
│ # step2_prepare()     ← concrete │
│ # step3_execute()     ← ABSTRACT │
│ # step4_cleanup()     ← concrete │
└─────────────┬─────────────────────┘
              │
    ┌─────────┼──────────┐
    │                    │
┌───▼──────────┐  ┌─────▼────────┐
│ EmailProcess │  │ SmsProcess   │
│──────────────│  │──────────────│
│ #step3       │  │ #step3       │
│  → send via  │  │  → send via  │
│    SES       │  │    Twilio    │
└──────────────┘  └──────────────┘
```

### Notification System — Processing Flow (Numbered)

```
NotificationService.send(request) + processQueue()

    (1) ── Validate request ──────────────────────── COMMON
    │       Is templateId present? Is channel valid?
    │       Throw NotificationException if invalid
    │
    (2) ── Check user preferences ────────────────── COMMON
    │       prefService.canSend(userId, channel, now)
    │       → Is channel enabled for this user?
    │       → Is it quiet hours (10pm-8am)?
    │       → If NO: skip with "[SKIP] user opted out"
    │
    (3) ── Render template ───────────────────────── COMMON
    │       templateService.renderTemplate(templateId, data)
    │       → Load template from repository
    │       → Replace {{name}} with "Alice", {{orderId}} with "ORD-1234"
    │       → Return [subject, body]
    │
    (4) ── Build Notification ────────────────────── COMMON
    │       Notification.builder()
    │         .userId("alice").channel(EMAIL)
    │         .subject(rendered[0]).body(rendered[1])
    │         .priority(HIGH).maxRetries(5)
    │         .build()
    │
    (5) ── Enqueue to priority queue ─────────────── COMMON
    │       queue.enqueue(notification)
    │       → PriorityBlockingQueue orders by priority
    │
    ════════════════════════════════════════════════════════
    ║  processQueue() picks up from here                   ║
    ════════════════════════════════════════════════════════
    │
    (6) ── DELIVER ───────────────────────────────── ★ VARIES PER CHANNEL ★
    │       handler = handlers.get(notification.channel)
    │       attempt = handler.send(notification)
    │       │
    │       ├── PushHandler.send()    → FCM/APNs (90% success)
    │       ├── EmailHandler.send()   → SES/SendGrid (95% success)
    │       ├── SmsHandler.send()     → Twilio (85% success)
    │       └── InAppHandler.send()   → Store in DB (100% success)
    │
    (7) ── Track delivery ────────────────────────── COMMON
    │       tracker.record(attempt)
    │       notification.markAsSent() or markAsFailed()
    │
    (8) ── Handle failure (if any) ───────────────── COMMON
            if (failed && retryable):
              notification.incrementRetry()
              queue.enqueue(notification)  → re-enqueue for retry
            if (failed && !retryable):
              notification → Dead Letter Queue (conceptual)

    Steps 1-5, 7-8 = IDENTICAL for all channels (Template Method)
    Step 6 = DIFFERENT per channel (Strategy Pattern)
    These two patterns work TOGETHER.
```

---

## 8. Chain of Responsibility

> "Pass a request along a chain of handlers. Each handler decides to process it or pass it on."

**Category**: Behavioral | **Used in**: Rate Limiter (conceptual)

### Generic UML

```
┌─────────────────┐     ┌────────────────────────┐
│    Client        │     │    <<interface>>         │
│─────────────────│     │      Handler             │
│ + sendRequest() │────▶│────────────────────────│
│                 │     │ - next: Handler          │
└─────────────────┘     │────────────────────────│
                        │ + handle(request): Resp  │
                        │ + setNext(h): Handler    │
                        └────────────┬─────────────┘
                                     │
                          ┌──────────┼──────────┬──────────┐
                          │          │          │          │
                    ┌─────▼───┐ ┌───▼─────┐ ┌──▼──────┐ ┌▼─────────┐
                    │  Auth   │→│  Rate   │→│  Log    │→│ Business │
                    │ Filter  │ │ Limiter │ │ Filter  │ │  Logic   │
                    └─────────┘ └─────────┘ └─────────┘ └──────────┘
```

### Rate Limiter — Middleware Pipeline (Numbered)

```
HTTP Request arrives
       │
       ▼
  (1) ┌──────────────────┐         (1a) 401 Unauthorized
      │  Auth Middleware  │─── FAIL ──────────────────▶ Response
      │  (check JWT/key) │
      └──────┬───────────┘
             │ (1b) ✓ authenticated
             ▼
  (2) ┌──────────────────┐         (2a) 429 Too Many Requests
      │  Rate Limiter    │─── FAIL ──────────────────▶ Response
      │  Middleware      │                            + X-RateLimit headers
      │  (our project!)  │                            + Retry-After
      └──────┬───────────┘
             │ (2b) ✓ within limit
             ▼
  (3) ┌──────────────────┐
      │  Request Logger  │──▶ (3a) log method, path, IP → pass through
      └──────┬───────────┘
             │ (3b) always passes
             ▼
  (4) ┌──────────────────┐         (4a) 200 OK
      │  Business Logic  │──────────────────────────▶ Response
      │  (your API code) │
      └──────────────────┘

Decision at each step:
  (1) Auth:         PASS (valid token) or BLOCK (401)
  (2) Rate Limiter: PASS (within limit) or BLOCK (429)
  (3) Logger:       ALWAYS PASS (side effect: log)
  (4) Business:     PROCESS and RESPOND

// In Spring Boot:
//   @Component @Order(1) AuthFilter implements Filter
//   @Component @Order(2) RateLimiterFilter implements Filter
//   @Component @Order(3) LoggingFilter implements Filter
//   Spring calls them in @Order sequence automatically
```

---

## 9. Mediator Pattern

> "Define an object that encapsulates how a set of objects interact. Promotes loose coupling by keeping objects from referring to each other explicitly."

**Category**: Behavioral | **Used in**: Chat System

### Generic UML

```
                        ┌──────────────────┐
                        │    Mediator       │
                        │  (ChatService)    │
                        │──────────────────│
               ┌───────▶│ + sendDirect()   │◀────────┐
               │        │ + sendGroup()    │         │
               │        │ + userConnects() │         │
               │        │ + userDisconnects│         │
               │        └────────┬─────────┘         │
               │                 │                    │
    ┌──────────┴──┐    ┌────────┴───────┐   ┌──────┴────────┐
    │MessageService│    │PresenceService │   │  GroupService  │
    │─────────────│    │───────────────│   │──────────────│
    │ +sendMessage│    │ +heartbeat()  │   │ +createGroup │
    │ +getHistory │    │ +disconnect() │   │ +addMember   │
    └─────────────┘    └───────────────┘   └──────────────┘
    
    Without Mediator (N×N):        With Mediator (hub):
    MsgSvc ←→ PresenceSvc          MsgSvc → ChatService
    MsgSvc ←→ GroupSvc             PresenceSvc → ChatService  
    MsgSvc ←→ Router               GroupSvc → ChatService
    PresenceSvc ←→ GroupSvc        Router → ChatService
    PresenceSvc ←→ Router          
    GroupSvc ←→ Router             5 connections vs 4 connections
    = 6 connections                + scales better with more services
```

### Chat System — ChatService as Mediator (Numbered)

```
(1) ChatController.handleSendDirectMessage("alice", "bob", "Hey!")
         │
(2) ChatService.sendDirectMessage("alice", "bob", "Hey!", TEXT)
         │
         ├── (3) convRepo.findOneToOne("alice", "bob")
         │        → finds or creates 1:1 conversation
         │
         ├── (4) messageService.sendMessage("alice", convId, "Hey!", TEXT)
         │        → assigns sequence, saves, returns Message
         │
         ├── (5) [inside MessageService] router.routeToUser(msg, "bob", "Alice")
         │        │
         │        ├── (6) registry.isOnline("bob") → true
         │        │
         │        └── (7) connectionHandler.deliverMessage(msg, "Alice")
         │                 → [WS → bob] Alice: Hey! ✓✓
         │
         └── (8) return Message with status DELIVERED
```

---

## 10. Command Pattern

> "Encapsulate a request as an object, thereby letting you parameterize clients with different requests, queue them, and log them."

**Category**: Behavioral | **Used in**: Chat System

### Generic UML

```
┌──────────┐    ┌────────────────┐    ┌──────────┐    ┌──────────┐
│  Sender  │───▶│   Command      │───▶│  Queue   │───▶│ Executor │
│ (Client) │    │  (Message obj) │    │ (Kafka)  │    │ (Router) │
└──────────┘    │────────────────│    └──────────┘    └──────────┘
                │ - receiver     │
                │ - data         │
                │ - metadata     │
                └────────────────┘
```

### Chat System — Message as Command Object (Numbered)

```
(1) Alice creates message         → Message object born (Command created)
(2) MessageService persists       → Command saved to store
(3) MessageService routes         → Command dispatched to Router
(4) Router checks presence        → Router decides execution strategy
(5) ConnectionHandler delivers    → Command executed (delivered to Bob)
(6) Bob's client ACKs            → Command acknowledged
(7) Read receipt sent back        → Response command flows back to Alice
```

---

# CONCURRENCY PATTERNS

> *How objects coordinate across threads — managing shared resources safely.*

---

## 11. Producer-Consumer Pattern

> "Decouple work production from work consumption via a shared queue."

**Category**: Concurrency | **Used in**: Notification System

### Generic UML

```
┌──────────────┐     ┌─────────────────────────┐     ┌──────────────┐
│   Producer   │     │    <<interface>>          │     │   Consumer   │
│──────────────│     │   BlockingQueue           │     │──────────────│
│ + produce()  │────▶│─────────────────────────│◀────│ + consume()  │
│   → queue    │     │ + enqueue(item): void    │     │   → queue    │
│     .enqueue()│    │ + dequeue(): Item        │     │     .dequeue()│
└──────────────┘     │ + size(): int            │     └──────────────┘
                     └─────────────────────────┘
                                  │
                     ┌────────────▼────────────┐
                     │  PriorityBlockingQueue   │
                     │─────────────────────────│
                     │ Orders by Priority:      │
                     │ CRITICAL → first out     │
                     │ LOW → last out           │
                     └─────────────────────────┘
```

### Notification System — Numbered Sequence

```
   PRODUCER SIDE                     QUEUE                      CONSUMER SIDE
┌──────────────────┐     ┌──────────────────────┐     ┌──────────────────────┐
│ NotificationService│    │ InMemoryPriorityQueue │     │ NotificationService  │
│  .send(request)   │    │──────────────────────│     │  .processQueue()     │
│──────────────────│    │ PriorityBlockingQueue │     │──────────────────────│
│ (1) validate     │    │   <Notification>      │     │ (6) dequeue()        │
│ (2) check prefs  │    │                      │     │ (7) get handler      │
│ (3) render       │    │  ┌─CRITICAL──────┐  │     │     for channel      │
│ (4) build Notif  │    │  │ OTP for alice  │  │     │ (8) handler.send()   │
│ (5) enqueue() ───│───▶│  ├─HIGH──────────┤  │────▶│ (9) track delivery   │
│                  │    │  │ Order confirm  │  │     │ (10) retry if failed │
└──────────────────┘    │  ├─MEDIUM────────┤  │     └──────────────────────┘
                        │  │ Social update  │  │
                        │  ├─LOW───────────┤  │
                        │  │ Marketing     │  │
                        │  └───────────────┘  │
                        └──────────────────────┘

Steps:
  PRODUCER:
    (1) Validate request fields
    (2) Check if user allows this channel + not quiet hours
    (3) Render template: "Hi {{name}}" → "Hi Alice"
    (4) Build Notification with Builder (priority=CRITICAL)
    (5) Enqueue → PriorityBlockingQueue sorts by priority

  QUEUE:
    Notification implements Comparable<Notification>
    → compareTo() uses Priority.value
    → CRITICAL(0) < HIGH(1) < MEDIUM(2) < LOW(3)
    → PriorityBlockingQueue automatically dequeues lowest value first
    → OTP (CRITICAL) always comes out before marketing (LOW)

  CONSUMER:
    (6)  Dequeue → gets highest priority notification
    (7)  Look up handler: handlers.get(notification.channel)
    (8)  Call handler.send() → actual delivery
    (9)  Record delivery attempt in DeliveryTracker
    (10) If failed + retryable → re-enqueue with incremented retryCount

In production (replace with Kafka):
    ┌──────────────────────────┐
    │   Kafka Cluster           │
    │  ┌──────────────────┐    │
    │  │ topic: critical   │←── (1) 32 partitions, 16 consumers (fastest)
    │  │ topic: high       │←── (2) 64 partitions, 8 consumers
    │  │ topic: medium     │←── (3) 128 partitions, 4 consumers
    │  │ topic: low        │←── (4) 128 partitions, 2 consumers (slowest OK)
    │  └──────────────────┘    │
    └──────────────────────────┘
    (5) Each priority level scales independently
    (6) CRITICAL gets 8× more consumer throughput than LOW
```

---

# PATTERNS WORKING TOGETHER

## Full Interaction Map — Notification System Request (Numbered)

```
A notification request arrives. All patterns cooperate:

    NotificationRequest
            │
  ┌─────────────────────────────────────────────────────────────────┐
  │ STARTUP (happens once):                                          │
  │  (0) [FACTORY] AppConfig.createController()                      │
  │       → creates all repos, handlers, services, wires everything  │
  └─────────────────────────────────────────────────────────────────┘
            │
  (1)  NotificationController.handleSend(request)
            │
  (2)  NotificationService.send(request)
            │
  (3)  [REPOSITORY] prefRepo.findByUserId("alice")
  │         → checks if channel enabled, quiet hours
            │
  (4)  [TEMPLATE] templateService.render("order-confirm", data)
  │         → "Hi {{name}}" → "Hi Alice"
            │
  (5)  [BUILDER] Notification.builder()
  │         .userId("alice").channel(EMAIL).priority(HIGH)
  │         .subject("Order Confirmed").body("Hi Alice...")
  │         .build()   → immutable Notification created
            │
  (6)  [PRODUCER] queue.enqueue(notification)
  │         → PriorityBlockingQueue sorts by Priority value
  │
  ══════════════════════════════════════════════════════════
  ║  processQueue() — runs later (async in production)    ║
  ══════════════════════════════════════════════════════════
            │
  (7)  [CONSUMER] notification = queue.dequeue()
  │         → gets highest priority notification first
            │
  (8)  [STRATEGY] handler = handlers.get(notification.channel)
  │         → EMAIL → EmailNotificationHandler
            │
  (9)  DeliveryAttempt attempt = handler.send(notification)
  │         → simulates SES call, returns SENT/FAILED
            │
  (10) [OBSERVER] tracker.record(attempt)
  │         → logs attempt, increments sent/failed counters
            │
  (11) [REPOSITORY] notifRepo.updateStatus(id, DELIVERED)
            │
  (12) Return result to caller
```

---

## Cross-Project Pattern Usage Table

### By GoF Category

| Category | Pattern | URL Shortener (01) | Rate Limiter (02) | Notification (03) | Chat System (04) | Social Media Feed (05) |
|----------|---------|-------------------|-------------------|-------------------|-------------------|------------------------|
| **Creational** | Builder | `Url.Builder` (8 fields) | `RateLimitRule.Builder` (7 fields) | `Notification.Builder` (15 fields) | `Message.Builder` (12 fields) | `Tweet.Builder` |
| **Creational** | Factory | `AppConfig` | `AppConfig` | `AppConfig` (complex) | `AppConfig` (complex) | `AppConfig` |
| **Creational** | Singleton | `AtomicLong` counter | — | — | — | — |
| **Structural** | Repository | `UrlRepository` (1) | `RuleRepository` (1) | 3 repositories | 3 repositories | 5 repositories |
| **Structural** | Composite | — | — | — | — | `HybridFanoutStrategy` (Write + Read) |
| **Behavioral** | Strategy | `EncodingStrategy` (3) | `RateLimiterStrategy` (5) | `NotificationHandler` (4) | `MessageRouter` (online/offline) | `FanoutStrategy` (3 impl) + `FeedRanker` (2 impl) |
| **Behavioral** | Observer | — | — | `DeliveryTracker` | Presence + delivery + read receipts | tweet.published → fan-out + trending (conceptual) |
| **Behavioral** | Template Method | — | — | Notification flow | — | — |
| **Behavioral** | Chain of Resp. | — | Middleware pipeline | — | — | — |
| **Behavioral** | Mediator | — | — | — | **ChatService orchestrator** | `FeedService` orchestrates cache + pull + merge + rank |
| **Behavioral** | Command | — | — | — | **Message as command object** | — |
| **Concurrency** | Producer-Consumer | — | — | `PriorityQueue` | — (Kafka conceptual) | — |

### Count by Project

| Project | Creational | Structural | Behavioral | Concurrency | Total |
|---------|-----------|-----------|-----------|-------------|-------|
| URL Shortener | 3 (Builder, Factory, Singleton) | 1 (Repository) | 1 (Strategy) | 0 | **5** |
| Rate Limiter | 2 (Builder, Factory) | 1 (Repository) | 2 (Strategy, CoR) | 0 | **5** |
| Notification | 2 (Builder, Factory) | 1 (Repository) | 3 (Strategy, Observer, Template) | 1 (Prod-Con) | **7** |
| Chat System | 2 (Builder, Factory) | 1 (Repository) | 4 (Strategy, Observer, Mediator, Command) | 0 | **7** |
| Social Feed | 2 (Builder, Factory) | 2 (Repository, Composite) | 3 (Strategy x2, Observer, Mediator) | 0 | **7** |

---

## Quick Interview Reference

### Creational Patterns

| Pattern | When Interviewer Asks... | Your One-Liner |
|---------|------------------------|----------------|
| Builder | "How do you construct complex objects?" | "Static inner Builder class. Method chaining. Call `.build()` for an immutable object. Avoids telescoping constructors." |
| Factory | "How do you wire everything together?" | "One factory class creates all concrete objects and injects interfaces. Services never say `new ConcreteClass()`." |
| Singleton | "How do you ensure one instance?" | "Private constructor + static factory. For counters, use AtomicLong. At scale, ZooKeeper assigns ranges." |

### Structural Patterns

| Pattern | When Interviewer Asks... | Your One-Liner |
|---------|------------------------|----------------|
| Repository | "How do you decouple from the database?" | "Interface for data access. Service depends on interface. Swap InMemory for Redis — change one line in config." |
| Composite | "How do you combine multiple strategies into one?" | "HybridFanoutStrategy implements the same FanoutStrategy interface as its children, but internally delegates to write or read strategy based on celebrity status. Strategy + Composite together." |

### Behavioral Patterns

| Pattern | When Interviewer Asks... | Your One-Liner |
|---------|------------------------|----------------|
| Strategy | "How do you handle multiple algorithms?" | "Interface + implementations. Inject via constructor or lookup by enum. Swap without touching service code." |
| Observer | "How do you track events without coupling?" | "Observer registers with subject. State changes notify all observers. Decouples source from consumers." |
| Template Method | "How do you reuse a common flow?" | "Define the skeleton in the base. Only the variable step differs per subclass. Common + pluggable." |
| Chain of Resp. | "How do you handle middleware?" | "Each handler processes or passes to next. Auth → Rate Limit → Business Logic. Like Servlet Filters." |
| Mediator | "How do you manage complex interactions?" | "One orchestrator class (ChatService) mediates all subsystem calls. Services don't know each other — reduces N×N coupling to N." |
| Command | "How do you decouple sender from receiver?" | "Message is a self-contained command object. Created by sender, flows through queue/router, executed by handler. Sender doesn't know where or how it's delivered." |

### Concurrency Patterns

| Pattern | When Interviewer Asks... | Your One-Liner |
|---------|------------------------|----------------|
| Producer-Consumer | "How do you handle async processing?" | "Producer enqueues, consumer dequeues. Queue decouples speed. In production: Kafka topics per priority." |
