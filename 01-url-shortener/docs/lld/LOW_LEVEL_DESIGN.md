# Low-Level Design: URL Shortener System

> Interview-prep reference for Senior Java Developer (7+ years).
> Focus: clean OOP, design patterns, concurrency awareness, extensibility.

---

## Table of Contents

1. [Core Modules Overview](#1-core-modules-overview)
2. [Package Structure](#2-package-structure)
3. [Class Diagram](#3-class-diagram)
4. [Entity Design](#4-entity-design)
5. [Interface Contracts](#5-interface-contracts)
6. [Service Layer Design](#6-service-layer-design)
7. [Strategy Pattern for Encoding](#7-strategy-pattern-for-encoding)
8. [Concurrency Considerations](#8-concurrency-considerations)
9. [Validation and Error Handling](#9-validation-and-error-handling)
10. [Sample Workflows](#10-sample-workflows)
11. [Design Patterns Used](#11-design-patterns-used)
12. [Extensibility Points](#12-extensibility-points)

---

## 1. Core Modules Overview

| Module         | Responsibility                                                        |
|----------------|-----------------------------------------------------------------------|
| **model**      | Domain entities and request/response DTOs                             |
| **service**    | Core business logic for shortening, redirection, and analytics        |
| **repository** | Data access abstraction over the storage layer                        |
| **controller** | REST API endpoints; maps HTTP to service calls                        |
| **strategy**   | Pluggable encoding algorithms for short-code generation               |
| **config**     | Application-wide configuration and bean wiring                        |
| **util**       | Stateless helpers for base62 encoding and key generation              |

---

## 2. Package Structure

```
com.systemdesign.urlshortener
│
├── model/
│   ├── Url.java                  -- Core domain entity (Builder pattern)
│   ├── UrlShortenRequest.java    -- Inbound DTO for shorten API
│   ├── UrlShortenResponse.java   -- Outbound DTO for shorten API
│   └── ClickEvent.java           -- Analytics event per redirect
│
├── service/
│   ├── UrlShortenerService.java  -- Shortening, redirection, deletion
│   └── AnalyticsService.java     -- Click tracking and stats retrieval
│
├── repository/
│   ├── UrlRepository.java        -- Interface (data access contract)
│   └── InMemoryUrlRepository.java-- ConcurrentHashMap-backed implementation
│
├── controller/
│   └── UrlShortenerController.java -- REST endpoints (/shorten, /{code}, /stats)
│
├── strategy/
│   ├── EncodingStrategy.java     -- Interface for encoding algorithms
│   ├── Base62Strategy.java       -- Numeric ID -> base62 string
│   ├── Md5Strategy.java          -- MD5 hash, first 7 chars
│   └── RandomStrategy.java       -- SecureRandom alphanumeric string
│
├── config/
│   └── AppConfig.java            -- Bean definitions, default strategy selection
│
├── util/
│   ├── Base62Encoder.java        -- Pure base62 encode/decode logic
│   └── KeyGenerator.java         -- AtomicLong-based unique ID generator (Singleton)
│
└── exception/
    ├── UrlNotFoundException.java
    ├── DuplicateAliasException.java
    ├── UrlExpiredException.java
    └── InvalidUrlException.java
```

---

## 3. Class Diagram

```
+-----------------------------------------------------------------------+
|                          <<interface>>                                 |
|                        EncodingStrategy                               |
|-----------------------------------------------------------------------|
| + encode(input: String): String                                       |
| + getStrategyName(): String                                           |
+-----------------------------------------------------------------------+
        ^                   ^                   ^
        |                   |                   |
        | implements        | implements        | implements
        |                   |                   |
+------------------+ +------------------+ +-------------------+
|  Base62Strategy  | |   Md5Strategy    | |  RandomStrategy   |
|------------------| |------------------| |-------------------|
| - keyGen: KeyGen | | - MD5_LENGTH: 7  | | - random: Secure  |
|------------------| |------------------| |     Random        |
| + encode(input)  | | + encode(input)  | | - CODE_LEN: 7     |
| + getStrategyNm  | | + getStrategyNm  | |-------------------|
+------------------+ +------------------+ | + encode(input)    |
                                          | + getStrategyName  |
                                          +-------------------+
                                                    |
                                                    | uses
                                                    v
+-----------------------------------------------------------------------+
|                        KeyGenerator  <<Singleton>>                    |
|-----------------------------------------------------------------------|
| - counter: AtomicLong                                                 |
| - INSTANCE: KeyGenerator                                              |
|-----------------------------------------------------------------------|
| + getInstance(): KeyGenerator                                         |
| + nextId(): long                                                      |
+-----------------------------------------------------------------------+

+-----------------------------------------------------------------------+
|                       Base62Encoder  <<Utility>>                      |
|-----------------------------------------------------------------------|
| - ALPHABET: char[] = [a-zA-Z0-9]  (62 chars)                         |
|-----------------------------------------------------------------------|
| + encode(num: long): String                                           |
| + decode(str: String): long                                           |
+-----------------------------------------------------------------------+

+-----------------------------------------------------------------------+
|                     <<interface>>                                      |
|                     UrlRepository                                      |
|-----------------------------------------------------------------------|
| + save(url: Url): Url                                                 |
| + findByShortCode(shortCode: String): Optional<Url>                   |
| + deleteByShortCode(shortCode: String): boolean                       |
| + existsByShortCode(shortCode: String): boolean                       |
+-----------------------------------------------------------------------+
                          ^
                          | implements
                          |
+-----------------------------------------------------------------------+
|                   InMemoryUrlRepository                               |
|-----------------------------------------------------------------------|
| - store: ConcurrentHashMap<String, Url>                               |
|-----------------------------------------------------------------------|
| + save(url: Url): Url                                                 |
| + findByShortCode(code: String): Optional<Url>                        |
| + deleteByShortCode(code: String): boolean                            |
| + existsByShortCode(code: String): boolean                            |
+-----------------------------------------------------------------------+

+-----------------------------------------------------------------------+
|                     <<interface>>                                      |
|                      CacheService                                     |
|-----------------------------------------------------------------------|
| + get(key: String): Optional<String>                                  |
| + put(key: String, value: String, ttl: Duration): void                |
| + evict(key: String): void                                            |
| + exists(key: String): boolean                                        |
+-----------------------------------------------------------------------+

+-----------------------------------------------------------------------+
|                    UrlShortenerService                                 |
|-----------------------------------------------------------------------|
| - repository: UrlRepository                                           |
| - encodingStrategy: EncodingStrategy                                  |
| - analyticsService: AnalyticsService                                  |
| - baseUrl: String                                                     |
|-----------------------------------------------------------------------|
| + shortenUrl(req: UrlShortenRequest): UrlShortenResponse              |
| + redirect(shortCode: String): String                                 |
| + getStats(shortCode: String): Url                                    |
| + deleteUrl(shortCode: String): void                                  |
| - generateShortCode(originalUrl: String): String                      |
| - validateUrl(url: String): void                                      |
| - isExpired(url: Url): boolean                                        |
+-----------------------------------------------------------------------+
        |                        |                        |
        | uses                   | uses                   | uses
        v                        v                        v
  UrlRepository         EncodingStrategy         AnalyticsService

+-----------------------------------------------------------------------+
|                     AnalyticsService                                  |
|-----------------------------------------------------------------------|
| - clickEvents: ConcurrentHashMap<String, List<ClickEvent>>            |
|-----------------------------------------------------------------------|
| + recordClick(event: ClickEvent): void                                |
| + getClickCount(shortCode: String): long                              |
| + getClickEvents(shortCode: String): List<ClickEvent>                 |
+-----------------------------------------------------------------------+

+-----------------------------------------------------------------------+
|                   UrlShortenerController                              |
|-----------------------------------------------------------------------|
| - service: UrlShortenerService                                        |
|-----------------------------------------------------------------------|
| + POST /api/shorten  -> shortenUrl(req): UrlShortenResponse           |
| + GET  /{shortCode}  -> redirect(shortCode): 302 Redirect            |
| + GET  /api/stats/{shortCode} -> getStats(shortCode): Url            |
| + DELETE /api/{shortCode}     -> deleteUrl(shortCode): void           |
+-----------------------------------------------------------------------+

+---------------------------- MODELS -----------------------------------+

+---------------------------+   +---------------------------+
|    UrlShortenRequest      |   |    UrlShortenResponse     |
|---------------------------|   |---------------------------|
| - originalUrl: String     |   | - shortUrl: String        |
| - customAlias: String?    |   | - originalUrl: String     |
| - expiresAt: LocalDateTime|   | - expiresAt: LocalDateTime|
+---------------------------+   +---------------------------+

+-----------------------------------------------------------------------+
|                        Url  <<Builder>>                                |
|-----------------------------------------------------------------------|
| - id: Long                                                            |
| - shortCode: String                                                   |
| - originalUrl: String                                                 |
| - createdAt: LocalDateTime                                            |
| - expiresAt: LocalDateTime                                            |
| - clickCount: AtomicLong                                              |
| - customAlias: String                                                 |
| - userId: String                                                      |
| - deleted: boolean                                                    |
|-----------------------------------------------------------------------|
| + builder(): UrlBuilder                                               |
| + incrementClick(): long                                              |
| + isExpired(): boolean                                                |
+-----------------------------------------------------------------------+

+---------------------------+
|        ClickEvent         |
|---------------------------|
| - shortCode: String       |
| - timestamp: LocalDateTime|
| - ipAddress: String       |
| - userAgent: String       |
+---------------------------+

RELATIONSHIP SUMMARY
====================
UrlShortenerController --uses--> UrlShortenerService
UrlShortenerService    --uses--> UrlRepository (interface)
UrlShortenerService    --uses--> EncodingStrategy (interface)
UrlShortenerService    --uses--> AnalyticsService
InMemoryUrlRepository  --implements--> UrlRepository
Base62Strategy         --implements--> EncodingStrategy
Md5Strategy            --implements--> EncodingStrategy
RandomStrategy         --implements--> EncodingStrategy
Base62Strategy         --uses--> KeyGenerator
Base62Strategy         --uses--> Base62Encoder
Url                    --built-by--> Url.UrlBuilder
```

---

## 4. Entity Design

### 4.1 Url (Core Domain Entity)

```java
public class Url {
    private Long id;
    private String shortCode;
    private String originalUrl;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private AtomicLong clickCount;
    private String customAlias;
    private String userId;
    private boolean deleted;          // soft delete flag

    // --- Builder Pattern ---
    private Url(UrlBuilder builder) {
        this.id = builder.id;
        this.shortCode = builder.shortCode;
        this.originalUrl = builder.originalUrl;
        this.createdAt = builder.createdAt != null
                         ? builder.createdAt : LocalDateTime.now();
        this.expiresAt = builder.expiresAt;
        this.clickCount = new AtomicLong(0);
        this.customAlias = builder.customAlias;
        this.userId = builder.userId;
        this.deleted = false;
    }

    public static UrlBuilder builder() {
        return new UrlBuilder();
    }

    public long incrementClick() {
        return clickCount.incrementAndGet();       // thread-safe
    }

    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }

    // --- Inner Builder ---
    public static class UrlBuilder {
        private Long id;
        private String shortCode;
        private String originalUrl;
        private LocalDateTime createdAt;
        private LocalDateTime expiresAt;
        private String customAlias;
        private String userId;

        public UrlBuilder id(Long id)                      { this.id = id;                return this; }
        public UrlBuilder shortCode(String code)            { this.shortCode = code;       return this; }
        public UrlBuilder originalUrl(String url)           { this.originalUrl = url;      return this; }
        public UrlBuilder createdAt(LocalDateTime t)        { this.createdAt = t;          return this; }
        public UrlBuilder expiresAt(LocalDateTime t)        { this.expiresAt = t;          return this; }
        public UrlBuilder customAlias(String alias)         { this.customAlias = alias;    return this; }
        public UrlBuilder userId(String uid)                { this.userId = uid;           return this; }

        public Url build() {
            Objects.requireNonNull(originalUrl, "originalUrl must not be null");
            Objects.requireNonNull(shortCode,   "shortCode must not be null");
            return new Url(this);
        }
    }
}
```

**Interview talking point:** The Builder pattern eliminates telescoping constructors and makes object creation readable. `AtomicLong` for clickCount avoids synchronized blocks during high-traffic redirects.

### 4.2 UrlShortenRequest (Inbound DTO)

```java
public class UrlShortenRequest {
    @NotBlank(message = "URL is required")
    @UrlFormat                                    // custom validator annotation
    private String originalUrl;

    @Size(min = 4, max = 20, message = "Alias must be 4-20 characters")
    @Pattern(regexp = "^[a-zA-Z0-9-_]+$")
    private String customAlias;                   // optional

    @Future(message = "Expiry must be in the future")
    private LocalDateTime expiresAt;              // optional

    // getters, setters
}
```

### 4.3 UrlShortenResponse (Outbound DTO)

```java
public class UrlShortenResponse {
    private String shortUrl;        // e.g. "https://short.ly/Ab3kX9"
    private String originalUrl;
    private LocalDateTime expiresAt;

    // all-args constructor + getters (immutable DTO)
}
```

### 4.4 ClickEvent (Analytics Event)

```java
public class ClickEvent {
    private String shortCode;
    private LocalDateTime timestamp;
    private String ipAddress;
    private String userAgent;

    // all-args constructor + getters (immutable)
}
```

---

## 5. Interface Contracts

### 5.1 UrlRepository

```java
public interface UrlRepository {

    /**
     * Persist a URL mapping. Keyed by shortCode.
     */
    Url save(Url url);

    /**
     * Lookup by short code. Returns empty if not found or soft-deleted.
     */
    Optional<Url> findByShortCode(String shortCode);

    /**
     * Soft-delete. Returns true if the entry existed.
     */
    boolean deleteByShortCode(String shortCode);

    /**
     * Existence check (non-deleted entries only).
     */
    boolean existsByShortCode(String shortCode);
}
```

**Implementation: InMemoryUrlRepository**

```java
public class InMemoryUrlRepository implements UrlRepository {

    private final ConcurrentHashMap<String, Url> store = new ConcurrentHashMap<>();

    @Override
    public Url save(Url url) {
        store.put(url.getShortCode(), url);
        return url;
    }

    @Override
    public Optional<Url> findByShortCode(String shortCode) {
        return Optional.ofNullable(store.get(shortCode))
                       .filter(url -> !url.isDeleted());
    }

    @Override
    public boolean deleteByShortCode(String shortCode) {
        Url url = store.get(shortCode);
        if (url != null) {
            url.setDeleted(true);               // soft delete
            return true;
        }
        return false;
    }

    @Override
    public boolean existsByShortCode(String shortCode) {
        return findByShortCode(shortCode).isPresent();
    }
}
```

### 5.2 EncodingStrategy

```java
public interface EncodingStrategy {

    /**
     * Generate a short code from the given input.
     * @param input typically the original URL or a numeric ID string
     * @return a short alphanumeric code (6-8 characters)
     */
    String encode(String input);

    /**
     * Human-readable name for logging and config selection.
     */
    String getStrategyName();
}
```

### 5.3 CacheService (Future Extensibility)

```java
public interface CacheService {

    /**
     * Retrieve cached original URL for the given short code.
     */
    Optional<String> get(String key);

    /**
     * Cache a shortCode -> originalUrl mapping with a TTL.
     */
    void put(String key, String value, Duration ttl);

    /**
     * Remove an entry (e.g., on URL deletion or update).
     */
    void evict(String key);

    /**
     * Check if a key is cached without retrieving the value.
     */
    boolean exists(String key);
}
```

> The CacheService interface is not wired into the MVP implementation. It exists so a Redis or Caffeine adapter can be plugged in without touching service code.

---

## 6. Service Layer Design

### 6.1 UrlShortenerService

```java
public class UrlShortenerService {

    private final UrlRepository repository;
    private final EncodingStrategy encodingStrategy;
    private final AnalyticsService analyticsService;
    private final String baseUrl;                       // e.g. "https://short.ly"

    // constructor injection

    // ---- PUBLIC API ----

    public UrlShortenResponse shortenUrl(UrlShortenRequest request) { ... }
    public String redirect(String shortCode) { ... }
    public Url getStats(String shortCode) { ... }
    public void deleteUrl(String shortCode) { ... }
}
```

### 6.2 Method: `shortenUrl(request)`

```
Step 1:  validateUrl(request.getOriginalUrl())
           |-- throw InvalidUrlException if URL is malformed

Step 2:  IF request.getCustomAlias() is present:
           |-- Check repository.existsByShortCode(alias)
           |-- If exists -> throw DuplicateAliasException
           |-- shortCode = customAlias
         ELSE:
           |-- shortCode = generateShortCode(request.getOriginalUrl())
           |-- Loop: if collision, regenerate (max 3 retries)

Step 3:  Build Url entity:
           Url url = Url.builder()
               .id(KeyGenerator.getInstance().nextId())
               .shortCode(shortCode)
               .originalUrl(request.getOriginalUrl())
               .expiresAt(request.getExpiresAt())
               .customAlias(request.getCustomAlias())
               .build();

Step 4:  repository.save(url)

Step 5:  Return new UrlShortenResponse(
               baseUrl + "/" + shortCode,
               request.getOriginalUrl(),
               url.getExpiresAt()
           );
```

### 6.3 Method: `redirect(shortCode)`

```
Step 1:  [Future] Check CacheService.get(shortCode)
           |-- If cache hit -> originalUrl found, skip to Step 3

Step 2:  Url url = repository.findByShortCode(shortCode)
           |-- If empty -> throw UrlNotFoundException
           |-- [Future] CacheService.put(shortCode, url.getOriginalUrl(), 1 hour)

Step 3:  IF url.isExpired():
           |-- throw UrlExpiredException

Step 4:  url.incrementClick()                           // AtomicLong, thread-safe

Step 5:  analyticsService.recordClick(new ClickEvent(
               shortCode,
               LocalDateTime.now(),
               extractIpAddress(),
               extractUserAgent()
           ))

Step 6:  Return url.getOriginalUrl()
```

### 6.4 Method: `getStats(shortCode)`

```
Step 1:  Url url = repository.findByShortCode(shortCode)
           |-- If empty -> throw UrlNotFoundException

Step 2:  Return url (contains clickCount, createdAt, expiresAt, etc.)
```

> In an interview, mention you could enrich this with AnalyticsService.getClickEvents(shortCode) for time-series data, geo breakdown, etc.

### 6.5 Method: `deleteUrl(shortCode)`

```
Step 1:  boolean existed = repository.deleteByShortCode(shortCode)
           |-- If !existed -> throw UrlNotFoundException

Step 2:  [Future] CacheService.evict(shortCode)

Step 3:  Log deletion event
```

> Soft delete keeps the record for audit. The `findByShortCode` filter excludes deleted entries automatically.

---

## 7. Strategy Pattern for Encoding

### 7.1 Base62Strategy

```java
public class Base62Strategy implements EncodingStrategy {

    @Override
    public String encode(String input) {
        long id = KeyGenerator.getInstance().nextId();
        return Base62Encoder.encode(id);
    }

    @Override
    public String getStrategyName() { return "BASE62"; }
}
```

**How it works:**

```
ID = 123456789
Alphabet = abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789

123456789 % 62 = 41 -> 'P'
  1991077 % 62 = 19 -> 't'
    32114 % 62 = 10 -> 'k'
      517 % 62 = 21 -> 'v'
        8 % 62 =  8 -> 'i'

Result: "ivktP" (reversed) -> 5 characters
```

**When to use:** Default choice. Compact codes, no collisions (sequential IDs), and codes grow slowly (62^7 = 3.5 trillion unique codes).

### 7.2 Md5Strategy

```java
public class Md5Strategy implements EncodingStrategy {

    private static final int MD5_CODE_LENGTH = 7;

    @Override
    public String encode(String input) {
        String hash = DigestUtils.md5Hex(input);     // 32-char hex
        return hash.substring(0, MD5_CODE_LENGTH);
    }

    @Override
    public String getStrategyName() { return "MD5"; }
}
```

**When to use:** Deterministic -- the same URL always produces the same short code. Useful for deduplication (avoid storing the same URL twice). Collision risk: 16^7 = ~268 million unique codes, so collision handling is mandatory.

### 7.3 RandomStrategy

```java
public class RandomStrategy implements EncodingStrategy {

    private static final int CODE_LENGTH = 7;
    private static final String CHARACTERS =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private final SecureRandom random = new SecureRandom();

    @Override
    public String encode(String input) {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
        }
        return sb.toString();
    }

    @Override
    public String getStrategyName() { return "RANDOM"; }
}
```

**When to use:** No sequential pattern, so codes are not guessable. Best when you do not want users to enumerate short URLs. Collision checking is required.

### 7.4 Comparison Table

| Aspect             | Base62            | MD5                  | Random              |
|--------------------|-------------------|----------------------|---------------------|
| Collision risk     | None (sequential) | Medium (hash prefix) | Low (62^7 space)    |
| Predictability     | Sequential        | Deterministic        | Unpredictable       |
| Deduplication      | No                | Yes (same hash)      | No                  |
| Performance        | O(log n)          | O(n) for hashing     | O(k) k = code len   |
| Best for           | General use       | Content-addressed    | Security-sensitive  |

---

## 8. Concurrency Considerations

### 8.1 Thread-Safe Storage

```java
// InMemoryUrlRepository
private final ConcurrentHashMap<String, Url> store = new ConcurrentHashMap<>();
```

- `ConcurrentHashMap` gives lock-striped, thread-safe reads and writes without global synchronization.
- `putIfAbsent` can be used for atomic custom-alias reservation.

### 8.2 Atomic ID Generation

```java
public class KeyGenerator {
    private static final KeyGenerator INSTANCE = new KeyGenerator();
    private final AtomicLong counter = new AtomicLong(100000L);  // start offset

    public static KeyGenerator getInstance() { return INSTANCE; }

    public long nextId() {
        return counter.incrementAndGet();     // CAS-based, lock-free
    }
}
```

- `AtomicLong.incrementAndGet()` uses CPU-level CAS (Compare-And-Swap), avoiding locks entirely.
- Starting at 100000 ensures all base62 codes are at least 3 characters.

### 8.3 Thread-Safe Click Counting

```java
// In Url entity
private final AtomicLong clickCount = new AtomicLong(0);

public long incrementClick() {
    return clickCount.incrementAndGet();
}
```

- No `synchronized` block needed. Multiple redirect threads can increment concurrently.

### 8.4 Analytics Event Collection

```java
// In AnalyticsService
private final ConcurrentHashMap<String, List<ClickEvent>> clickEvents =
    new ConcurrentHashMap<>();

public void recordClick(ClickEvent event) {
    clickEvents
        .computeIfAbsent(event.getShortCode(), k -> Collections.synchronizedList(new ArrayList<>()))
        .add(event);
}
```

- `computeIfAbsent` is atomic for the initial list creation.
- The list itself is wrapped with `Collections.synchronizedList` to handle concurrent appends.

### 8.5 Custom Alias Race Condition

```java
// In UrlShortenerService -- avoiding TOCTOU race
public UrlShortenResponse shortenUrl(UrlShortenRequest request) {
    ...
    if (request.getCustomAlias() != null) {
        Url existing = store.putIfAbsent(request.getCustomAlias(), url);
        if (existing != null) {
            throw new DuplicateAliasException("Alias already taken: " + request.getCustomAlias());
        }
    }
    ...
}
```

> **Interview point:** A naive `existsByShortCode()` + `save()` has a TOCTOU (Time Of Check to Time Of Use) bug. `putIfAbsent` is atomic in ConcurrentHashMap and eliminates the race.

---

## 9. Validation and Error Handling

### 9.1 URL Format Validation

```java
private void validateUrl(String url) {
    if (url == null || url.isBlank()) {
        throw new InvalidUrlException("URL must not be blank");
    }
    try {
        URI uri = new URI(url);
        if (uri.getScheme() == null || !List.of("http", "https").contains(uri.getScheme())) {
            throw new InvalidUrlException("URL must use http or https scheme");
        }
        if (uri.getHost() == null) {
            throw new InvalidUrlException("URL must have a valid host");
        }
    } catch (URISyntaxException e) {
        throw new InvalidUrlException("Malformed URL: " + e.getMessage());
    }
}
```

### 9.2 Custom Exception Hierarchy

```
RuntimeException
  |
  +-- UrlShortenerException (abstract base)
        |
        +-- UrlNotFoundException          HTTP 404
        +-- DuplicateAliasException       HTTP 409 Conflict
        +-- UrlExpiredException           HTTP 410 Gone
        +-- InvalidUrlException           HTTP 400 Bad Request
```

```java
public abstract class UrlShortenerException extends RuntimeException {
    private final String errorCode;
    private final HttpStatus httpStatus;

    protected UrlShortenerException(String message, String errorCode, HttpStatus status) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = status;
    }
    // getters
}

public class UrlNotFoundException extends UrlShortenerException {
    public UrlNotFoundException(String shortCode) {
        super("Short URL not found: " + shortCode, "URL_NOT_FOUND", HttpStatus.NOT_FOUND);
    }
}
```

### 9.3 Global Exception Handler (Controller Advice)

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UrlShortenerException.class)
    public ResponseEntity<ErrorResponse> handleAppException(UrlShortenerException ex) {
        ErrorResponse error = new ErrorResponse(
            ex.getErrorCode(),
            ex.getMessage(),
            LocalDateTime.now()
        );
        return ResponseEntity.status(ex.getHttpStatus()).body(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .collect(Collectors.joining(", "));
        ErrorResponse error = new ErrorResponse("VALIDATION_ERROR", message, LocalDateTime.now());
        return ResponseEntity.badRequest().body(error);
    }
}
```

### 9.4 Error Response DTO

```java
public class ErrorResponse {
    private String errorCode;
    private String message;
    private LocalDateTime timestamp;
}
```

### 9.5 Validation Summary Table

| Scenario                  | Exception                 | HTTP Status | Error Code         |
|---------------------------|---------------------------|-------------|--------------------|
| Malformed URL             | InvalidUrlException       | 400         | INVALID_URL        |
| Short code not found      | UrlNotFoundException      | 404         | URL_NOT_FOUND      |
| Custom alias taken        | DuplicateAliasException   | 409         | DUPLICATE_ALIAS    |
| Expired URL accessed      | UrlExpiredException       | 410         | URL_EXPIRED        |
| Bean validation failure   | MethodArgumentNotValid    | 400         | VALIDATION_ERROR   |

---

## 10. Sample Workflows

### 10.1 Creating a Shortened URL

```
Client                  Controller              Service                 Repository
  |                        |                       |                        |
  |-- POST /api/shorten -->|                       |                        |
  |   { originalUrl,       |                       |                        |
  |     customAlias?,      |                       |                        |
  |     expiresAt? }       |                       |                        |
  |                        |-- shortenUrl(req) --->|                        |
  |                        |                       |                        |
  |                        |                       |-- validateUrl()        |
  |                        |                       |   (throws if invalid)  |
  |                        |                       |                        |
  |                        |                       |-- IF customAlias:      |
  |                        |                       |   existsByShortCode -->|
  |                        |                       |                   <--- | true/false
  |                        |                       |   (throw if exists)    |
  |                        |                       |                        |
  |                        |                       |-- ELSE:                |
  |                        |                       |   strategy.encode() -->|
  |                        |                       |   shortCode = "Ab3kX9" |
  |                        |                       |                        |
  |                        |                       |-- Url.builder()        |
  |                        |                       |      .shortCode(code)  |
  |                        |                       |      .originalUrl(url) |
  |                        |                       |      .build()          |
  |                        |                       |                        |
  |                        |                       |-- save(url) ---------->|
  |                        |                       |                   <--- | saved
  |                        |                       |                        |
  |                        |<-- UrlShortenResponse |                        |
  |<-- 201 Created --------|   { shortUrl,         |                        |
  |    { shortUrl,          |     originalUrl,      |                        |
  |      originalUrl,       |     expiresAt }       |                        |
  |      expiresAt }        |                       |                        |
```

### 10.2 Redirecting a Short URL (Cache Hit vs Miss)

```
Client              Controller           Service            Cache         Repository
  |                     |                    |                 |               |
  |-- GET /Ab3kX9 ----->|                    |                 |               |
  |                     |-- redirect(code) ->|                 |               |
  |                     |                    |                 |               |
  |                     |                    |-- get(code) --->|               |
  |                     |                    |                 |               |
  |           +=========|== CACHE HIT ======|=================|               |
  |           |         |                    |<-- originalUrl -|               |
  |           |         |                    |                 |               |
  |           +=========|== CACHE MISS =====|=================|               |
  |           |         |                    |<-- empty -------|               |
  |           |         |                    |                 |               |
  |           |         |                    |-- findByShortCode(code) ------>|
  |           |         |                    |                 |          <--- | Url
  |           |         |                    |                 |               |
  |           |         |                    |-- put(code, url, ttl) ->|      |
  |           |         |                    |                 |               |
  |           +==========================================================-----+
  |                     |                    |                 |               |
  |                     |                    |-- isExpired()?  |               |
  |                     |                    |   (throw if yes)|               |
  |                     |                    |                 |               |
  |                     |                    |-- url.incrementClick()          |
  |                     |                    |-- analyticsService              |
  |                     |                    |     .recordClick(event)         |
  |                     |                    |                 |               |
  |                     |<-- originalUrl --- |                 |               |
  |<-- 302 Redirect ----|                    |                 |               |
  |    Location: <url>  |                    |                 |               |
```

### 10.3 Custom Alias Creation

```
1. Client sends POST /api/shorten with customAlias = "my-link"

2. Service calls repository.existsByShortCode("my-link")
     |-- If TRUE  -> throw DuplicateAliasException (HTTP 409)
     |-- If FALSE -> continue

3. Url entity built with shortCode = "my-link"

4. repository.save(url)
     |-- Internally: store.putIfAbsent("my-link", url)
     |-- If another thread raced and saved first -> throw DuplicateAliasException

5. Return response with shortUrl = "https://short.ly/my-link"
```

### 10.4 URL Expiration Handling

```
1. Client hits GET /expired-code

2. Service finds the Url entity in repository

3. Service calls url.isExpired():
     expiresAt = 2026-01-15T00:00:00
     now       = 2026-04-18T10:30:00
     now.isAfter(expiresAt) = true

4. throw UrlExpiredException("expired-code")

5. GlobalExceptionHandler catches it:
     -> HTTP 410 Gone
     -> { "errorCode": "URL_EXPIRED",
          "message": "Short URL has expired: expired-code",
          "timestamp": "2026-04-18T10:30:00" }
```

---

## 11. Design Patterns Used

### Summary Table

| Pattern           | Where Used                     | Why                                                          | Interview One-Liner                                                                 |
|-------------------|--------------------------------|--------------------------------------------------------------|-------------------------------------------------------------------------------------|
| **Strategy**      | EncodingStrategy + 3 impls     | Swap encoding algorithms without changing service code       | "Define a family of algorithms, encapsulate each one, make them interchangeable."   |
| **Builder**       | Url.UrlBuilder                 | Readable construction of Url with many optional fields       | "Separate construction of a complex object from its representation."                |
| **Repository**    | UrlRepository + InMemory impl  | Abstract data access; swap in-memory for JPA/Mongo later     | "Mediates between domain and data mapping layers using a collection-like interface."|
| **Singleton**     | KeyGenerator                   | One globally shared atomic counter for ID generation         | "Ensure a class has only one instance and provide a global point of access."        |
| **Factory Method**| Url.builder() static method    | Encapsulates builder creation; callers don't know inner class| "Define an interface for creating objects; let subclasses decide which class."       |
| **Template Method**| UrlShortenerService.shortenUrl| Fixed workflow: validate -> generate -> save -> respond       | "Define skeleton of an algorithm; subclasses redefine certain steps."               |

### Detailed Breakdown

**Strategy Pattern**

```
                  +--------------------+
                  | EncodingStrategy   |  <-- interface
                  +--------------------+
                  | + encode(input)    |
                  | + getStrategyName()|
                  +--------------------+
                   ^       ^        ^
                   |       |        |
           +-------+  +----+   +----+-------+
           |          |        |             |
    Base62Strategy  Md5Strategy  RandomStrategy

    // Service only depends on the interface
    private final EncodingStrategy encodingStrategy;

    // Injected via constructor (or config):
    new UrlShortenerService(repo, new Base62Strategy(), analytics, baseUrl);
```

**Why:** Adding a new encoding (e.g., SHA256Strategy) requires zero changes to service code. Just implement the interface and inject it.

**Builder Pattern**

```java
Url url = Url.builder()
    .id(1L)
    .shortCode("Ab3kX9")
    .originalUrl("https://example.com/very/long/path")
    .expiresAt(LocalDateTime.now().plusDays(30))
    .build();
```

**Why:** Url has 8+ fields, many optional. Telescoping constructors are unreadable. Builder makes intent clear and enforces required fields in `build()`.

**Repository Pattern**

```
Service --> UrlRepository (interface)
                  ^
                  |
        InMemoryUrlRepository  (today)
        JpaUrlRepository       (tomorrow, same interface)
        MongoUrlRepository     (day after, same interface)
```

**Why:** Complete decoupling of business logic from storage. Swap implementations without touching service code. Standard in Spring Boot and DDD.

**Singleton Pattern**

```java
public class KeyGenerator {
    private static final KeyGenerator INSTANCE = new KeyGenerator();  // eager init
    private final AtomicLong counter = new AtomicLong(100000L);

    private KeyGenerator() {}  // private constructor

    public static KeyGenerator getInstance() { return INSTANCE; }
    public long nextId() { return counter.incrementAndGet(); }
}
```

**Why:** A single atomic counter shared across all threads ensures unique IDs without coordination overhead. Eager initialization avoids double-checked locking complexity.

---

## 12. Extensibility Points

### 12.1 Add a New Encoding Strategy

```
1. Create: com.systemdesign.urlshortener.strategy.Sha256Strategy implements EncodingStrategy
2. Implement encode() and getStrategyName()
3. Register in AppConfig or make it selectable via config property
4. Zero changes to UrlShortenerService, controller, or repository
```

### 12.2 Switch to a Persistent Database (e.g., PostgreSQL + JPA)

```
1. Add spring-boot-starter-data-jpa and postgresql driver dependencies
2. Annotate Url with @Entity, @Id, @Column, etc.
3. Create: JpaUrlRepository extends JpaRepository<Url, Long> implements UrlRepository
4. Remove/disable InMemoryUrlRepository bean
5. Service layer: zero changes (depends on UrlRepository interface)
```

### 12.3 Add Redis Cache

```
1. Implement: RedisCacheService implements CacheService
     - Uses RedisTemplate internally
     - put() sets key with TTL via SETEX
     - get() returns value or Optional.empty()
2. Inject CacheService into UrlShortenerService
3. In redirect():
     - Check cache first -> cache hit returns immediately
     - Cache miss -> fetch from DB, populate cache
4. In deleteUrl(): call cacheService.evict(shortCode)
```

### 12.4 Add Rate Limiting

```
1. Create: RateLimitFilter extends OncePerRequestFilter (or HandlerInterceptor)
2. Use a token-bucket or sliding-window counter per IP/user
     - ConcurrentHashMap<String, AtomicInteger> for in-memory
     - Redis INCR + EXPIRE for distributed
3. Register filter in AppConfig with URL pattern matching
4. Return HTTP 429 Too Many Requests when limit exceeded
```

### 12.5 Add Authentication

```
1. Add spring-boot-starter-security
2. Create: JwtAuthenticationFilter extends OncePerRequestFilter
3. Extract userId from JWT and set in SecurityContext
4. Modify UrlShortenerService to associate URLs with authenticated userId
5. Add ownership check in deleteUrl() and getStats()
6. Public redirect endpoint remains unauthenticated
```

### 12.6 Extensibility Architecture at a Glance

```
+------------------------------------------------------------------+
|  CURRENT (MVP)               |  EXTENSIBLE TO                    |
|------------------------------|-----------------------------------|
|  InMemoryUrlRepository       |  JpaUrlRepository, MongoRepo      |
|  No cache                    |  RedisCacheService, CaffeineCache |
|  Base62Strategy (default)    |  Any new EncodingStrategy impl    |
|  No auth                     |  JWT / OAuth2 via Spring Security |
|  No rate limiting            |  Filter/Interceptor + token bucket|
|  Embedded analytics          |  Kafka events -> analytics service|
|  Single node                 |  Stateless service + external DB  |
+------------------------------------------------------------------+
```

---

## Quick Interview Reference Card

```
Q: "How do you generate short codes?"
A: Strategy pattern -- Base62 (sequential ID), MD5 (deterministic hash),
   or Random. Injected via interface; swappable without touching service.

Q: "How do you handle concurrent requests?"
A: ConcurrentHashMap for storage, AtomicLong for ID generation and click
   counts. putIfAbsent for race-free custom alias reservation.

Q: "What if two users pick the same custom alias simultaneously?"
A: ConcurrentHashMap.putIfAbsent is atomic. Second thread gets non-null
   return -> throw DuplicateAliasException (409 Conflict).

Q: "How would you scale this?"
A: Stateless service behind a load balancer, PostgreSQL for persistence,
   Redis for caching hot short codes, Kafka for async click analytics.

Q: "How do you handle expired URLs?"
A: Lazy expiration check on redirect. isExpired() compares expiresAt
   with current time. Returns HTTP 410 Gone. Optional: scheduled cleanup
   job for hard-deleting old entries.

Q: "Which design patterns are used?"
A: Strategy (encoding), Builder (Url entity), Repository (data access),
   Singleton (KeyGenerator), Factory Method (Url.builder()).
```

---

*End of Low-Level Design Document*
