# Design Patterns Reference

A comprehensive guide to every design pattern used across the URL Shortener, Rate Limiter, Notification System, and Chat System projects. Every code example comes from real implementations in this repository, with extensive comments explaining the **why**, not just the **what**.

**Audience:** Beginner to advanced Java developers preparing for system design interviews or building production systems.

---

## GoF Pattern Categories

| Category | Patterns in This Repo | Purpose |
|----------|----------------------|---------|
| **Creational** | Builder, Factory, Singleton | How objects get **created** |
| **Structural** | Repository (Enterprise/DDD), Composite, Facade | How objects are **composed** and connected |
| **Behavioral** | Strategy, Observer, Template Method, Chain of Responsibility, Mediator, Command, State | How objects **communicate** and share responsibilities |
| **Concurrency** | Producer-Consumer | How objects **coordinate** across threads |

---

## Table of Contents

### Creational Patterns
- [2. Builder Pattern](#2-builder-pattern) — Complex object construction
- [4. Factory Pattern](#4-factory-pattern--factory-method) — Centralized object creation
- [8. Singleton Pattern](#8-singleton-pattern-conceptual) — Single shared instance

### Structural Patterns
- [3. Repository Pattern](#3-repository-pattern) — Data access abstraction
- [12. Composite Pattern](#12-composite-pattern) — Compose objects into tree structures
- [13. Facade Pattern](#13-facade-pattern) — Single entry point for complex subsystem

### Behavioral Patterns
- [1. Strategy Pattern](#1-strategy-pattern) — Swappable algorithms (THE key pattern)
- [5. Observer Pattern](#5-observer-pattern) — Event-driven notifications
- [9. Template Method Pattern](#9-template-method-pattern) — Common flow with pluggable steps
- [7. Chain of Responsibility](#7-chain-of-responsibility) — Middleware pipeline
- [10. Mediator Pattern](#10-mediator-pattern) — Central coordinator that prevents spaghetti dependencies
- [11. Command Pattern](#11-command-pattern) — Message as a self-contained command object
- [14. State Pattern](#14-state-pattern) — Object behavior changes with internal state

### Concurrency Patterns
- [6. Producer-Consumer Pattern](#6-producer-consumer-pattern) — Async queue processing

### Cross-Cutting
- [Pattern Interaction Map](#pattern-interaction-map) — How all patterns work together
- [Cross-Project Pattern Usage Table](#cross-project-pattern-usage-table)

---

## 1. Strategy Pattern

**One-line definition:** Define a family of algorithms, put each one in its own class, and make them interchangeable at runtime without changing the code that uses them.

This is the single most important pattern in this repository. It is used in **all 3 projects**, and once you understand it, the rest of the patterns click into place.

---

### The Problem: What Bad Code Looks Like

Imagine you are building a URL shortener. You need to generate short codes, and there are multiple algorithms to choose from: Base62 encoding, MD5 hashing, or random generation. Without the Strategy pattern, you would write something like this:

```java
// === THE UGLY VERSION: A giant if-else chain inside the service ===
// Every time you add a new algorithm, you have to crack open this method
// and add another branch. This violates the Open-Closed Principle:
// "classes should be open for extension but closed for modification."
public class UrlShortenerService {

    private String algorithmName; // "Base62", "MD5", or "Random"

    public String generateShortCode(String originalUrl, long counter) {
        // PROBLEM 1: This method knows the INTERNALS of every algorithm.
        // It has to import MessageDigest, SecureRandom, and base-conversion logic.
        // The service class is doing too many jobs.
        if ("Base62".equals(algorithmName)) {
            // 15 lines of base62 conversion logic...
            long number = counter;
            StringBuilder encoded = new StringBuilder();
            while (number > 0) {
                encoded.append(BASE62_CHARS.charAt((int)(number % 62)));
                number /= 62;
            }
            return encoded.reverse().toString();

        } else if ("MD5".equals(algorithmName)) {
            // 10 lines of MD5 hashing logic...
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(originalUrl.getBytes());
            // ...convert to hex, take first 7 chars

        } else if ("Random".equals(algorithmName)) {
            // 5 lines of random generation...
            SecureRandom random = new SecureRandom();
            // ...pick random chars

        } else {
            // PROBLEM 2: What if someone passes "SHA256"? Runtime crash.
            throw new IllegalArgumentException("Unknown algorithm: " + algorithmName);
        }
        // PROBLEM 3: Want to add a "Snowflake" algorithm? You MUST modify this class.
        // PROBLEM 4: Want to unit test just the MD5 logic? You can not — it is tangled
        //            inside a method that also does Base62 and Random.
        // PROBLEM 5: The string-based algorithmName is fragile. Typo = runtime error.
    }
}
```

The same ugliness appears in the Rate Limiter (imagine if-else for 5 algorithms: Token Bucket, Leaky Bucket, Fixed Window, Sliding Window Log, Sliding Window Counter) and in the Notification System (if-else for 4 channels: Push, Email, SMS, InApp).

---

### The Solution: How Strategy Fixes It

The Strategy pattern says: **extract each algorithm into its own class that implements a common interface**. The service does not know (or care) which algorithm it is using. It just calls the interface method.

Three steps:
1. **Define the contract** (the interface)
2. **Implement each algorithm** in its own class
3. **Wire the chosen strategy** into the service via constructor injection

---

### Real Code Example: URL Shortener (01-url-shortener)

#### STEP 1: Define the contract (interface)

```java
// File: 01-url-shortener/.../strategy/EncodingStrategy.java
//
// === THE STRATEGY INTERFACE ===
// This interface defines WHAT can be done (encode a string into a short code),
// but says NOTHING about HOW. That is the key insight.
//
// Any class that implements this interface becomes a "swappable algorithm."
// The service that uses this interface does not need to know which implementation
// it is talking to. It just calls encode() and gets a short code back.
//
// Think of it like a power outlet: any appliance with the right plug works.
// The outlet (service) does not care if it is a lamp or a toaster (Base62 or MD5).
public interface EncodingStrategy {

    /**
     * Generate a short code from the given input.
     * - For Base62: input is a counter value like "100001"
     * - For MD5: input is the original URL like "https://example.com"
     * - For Random: input is ignored entirely (code is purely random)
     *
     * Each implementation interprets the input differently. The service
     * does not need to know these details.
     */
    String encode(String input);

    /**
     * Human-readable name of this strategy.
     * Used for logging and debugging — lets you see which algorithm
     * is active without inspecting the object's class name.
     */
    String name();
}
```

#### STEP 2: Implement each algorithm in its own class

```java
// File: 01-url-shortener/.../strategy/Base62EncodingStrategy.java
//
// === CONCRETE STRATEGY #1: Base62 ===
// This class has ONE job: convert a number to a base-62 string.
// It knows nothing about URLs, databases, or HTTP requests.
// That separation of concerns is why Strategy is powerful.
//
// Base62 uses: 0-9 (10) + a-z (26) + A-Z (26) = 62 characters.
// With 7 chars, we get 62^7 = ~3.5 trillion unique codes.
// This is the most common approach in real URL shorteners (bit.ly uses it).
//
// Key trade-off: requires a globally unique counter (single point of
// contention in distributed systems), but GUARANTEES no collisions.
public class Base62EncodingStrategy implements EncodingStrategy {

    // The character set for base-62 encoding. Order matters:
    // digits first, then lowercase, then uppercase.
    private static final String BASE62_CHARS =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int CODE_LENGTH = 7;

    @Override
    public String encode(String input) {
        // The input for Base62 is a COUNTER VALUE like "100001".
        // We parse it to a long and convert to base-62.
        long number = Long.parseLong(input);
        StringBuilder encoded = new StringBuilder();

        // Classic base-conversion algorithm:
        // Repeatedly divide by 62, append the remainder as a character.
        // This is the same logic as converting decimal to binary,
        // but with 62 symbols instead of 2.
        while (number > 0) {
            int remainder = (int) (number % 62);
            encoded.append(BASE62_CHARS.charAt(remainder));
            number /= 62;
        }

        // Pad to ensure minimum length (short counters produce short codes)
        while (encoded.length() < CODE_LENGTH) {
            encoded.append('0');
        }

        // Reverse because we built the string least-significant-digit first
        return encoded.reverse().toString().substring(0, CODE_LENGTH);
    }

    @Override
    public String name() {
        return "Base62";
    }
}
```

```java
// File: 01-url-shortener/.../strategy/Md5EncodingStrategy.java
//
// === CONCRETE STRATEGY #2: MD5 Hash ===
// This class has ONE job: hash a URL and return the first 7 hex characters.
//
// KEY DIFFERENCE from Base62: same URL always produces the SAME code.
// This makes it "idempotent" — great for deduplication (if the same URL
// is shortened twice, it gets the same short code).
//
// Trade-off: collision risk is higher. 16^7 = ~268M unique codes from
// 7 hex chars. Not suitable for high-volume systems without collision handling.
public class Md5EncodingStrategy implements EncodingStrategy {

    private static final int CODE_LENGTH = 7;

    @Override
    public String encode(String input) {
        // For MD5, the input is the ORIGINAL URL itself.
        // We hash it and take the first 7 hex characters.
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes());

            // Convert the raw bytes to a hex string
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }

            // Take only the first 7 characters
            return hex.substring(0, CODE_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    @Override
    public String name() {
        return "MD5";
    }
}
```

```java
// File: 01-url-shortener/.../strategy/RandomEncodingStrategy.java
//
// === CONCRETE STRATEGY #3: Random ===
// This class has ONE job: generate a cryptographically random alphanumeric code.
//
// KEY DIFFERENCE from Base62 and MD5:
// - No counter dependency — works well in distributed systems where
//   coordinating a global counter is expensive
// - No determinism — same URL produces DIFFERENT codes each time
// - Collision risk requires a check-and-retry loop in the service
//
// Uses SecureRandom (not ThreadLocalRandom) so codes are unpredictable.
// This matters because predictable short codes let attackers enumerate URLs.
public class RandomEncodingStrategy implements EncodingStrategy {

    private static final String ALPHANUMERIC =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int CODE_LENGTH = 7;

    // SecureRandom is thread-safe and cryptographically strong.
    // Created once and reused (creating SecureRandom is expensive).
    private final SecureRandom random = new SecureRandom();

    @Override
    public String encode(String input) {
        // THE "AHA" MOMENT: input is IGNORED entirely.
        // This method generates a purely random code regardless of input.
        // The interface forces us to accept input (for Base62/MD5 compatibility),
        // but we simply do not use it. That is perfectly fine.
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(ALPHANUMERIC.charAt(random.nextInt(ALPHANUMERIC.length())));
        }
        return code.toString();
    }

    @Override
    public String name() {
        return "Random";
    }
}
```

#### STEP 3: The SERVICE does not know which strategy it is using

```java
// File: 01-url-shortener/.../service/UrlShortenerService.java
//
// === THE SERVICE: Uses the strategy WITHOUT knowing which one it is ===
//
// This is the "aha" moment for the Strategy pattern:
// The service receives the strategy via CONSTRUCTOR INJECTION.
// It calls strategy.encode() — and DIFFERENT code runs depending
// on which implementation was injected.
//
// The service is CLOSED for modification (you never edit this class
// to add a new algorithm) but OPEN for extension (just create a new
// class that implements EncodingStrategy). This is the Open-Closed Principle.
public class UrlShortenerService {

    private final UrlRepository repository;          // Data layer (also injected)
    private final EncodingStrategy encodingStrategy;  // THE STRATEGY — could be ANY impl
    private final String baseUrl;
    private final AtomicLong counter = new AtomicLong(100_000); // For Base62

    // === CONSTRUCTOR INJECTION ===
    // Notice: the parameter type is EncodingStrategy (the INTERFACE),
    // not Base62EncodingStrategy (a concrete class).
    // This means the service works with ANY implementation.
    // It does not import, reference, or depend on any concrete strategy class.
    public UrlShortenerService(UrlRepository repository,
                               EncodingStrategy encodingStrategy,
                               String baseUrl) {
        this.repository = repository;
        this.encodingStrategy = encodingStrategy;
        this.baseUrl = baseUrl;
    }

    public UrlShortenResponse shortenUrl(UrlShortenRequest request) {
        validateUrl(request.getOriginalUrl());

        String shortCode;
        if (request.getCustomAlias() != null && !request.getCustomAlias().isBlank()) {
            shortCode = request.getCustomAlias();
            if (repository.existsByShortCode(shortCode)) {
                throw new DuplicateAliasException(shortCode);
            }
        } else {
            shortCode = generateUniqueCode(request.getOriginalUrl());
        }

        // ... build URL entity and save ...
    }

    // === HERE IS WHERE THE MAGIC HAPPENS ===
    private String generateUniqueCode(String originalUrl) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            // Prepare input based on strategy type
            String input = switch (encodingStrategy.name()) {
                case "Base62" -> String.valueOf(counter.incrementAndGet());
                case "MD5"    -> originalUrl + (attempt > 0 ? "#" + attempt : "");
                default       -> originalUrl; // Random ignores input anyway
            };

            // === THE POLYMORPHIC CALL ===
            // strategy.encode(input) calls DIFFERENT code depending on which
            // implementation was injected:
            //   - If Base62EncodingStrategy: converts counter to base-62
            //   - If Md5EncodingStrategy: hashes the URL
            //   - If RandomEncodingStrategy: generates random chars
            //
            // The service does not know or care. It just gets a short code back.
            String code = encodingStrategy.encode(input);

            // Check for collisions (especially important for MD5 and Random)
            if (!repository.existsByShortCode(code)) {
                return code;
            }
            // Collision detected — retry with modified input
        }
        throw new RuntimeException(
            "Failed to generate unique short code after " + MAX_RETRIES + " attempts");
    }
}
```

#### STEP 4: The WIRING -- where the decision is made

```java
// File: 01-url-shortener/.../config/AppConfig.java
//
// === AppConfig IS THE ONLY CLASS THAT KNOWS CONCRETE TYPES ===
// This is the COMPOSITION ROOT — the single place in the application
// where you decide which implementations to use.
//
// Changing the encoding algorithm means changing ONE line here.
// The service class? Zero changes. The controller? Zero changes.
// The tests? Still pass (as long as the new strategy is correct).
//
// In Spring Boot, the @Configuration class does this automatically
// with @Bean methods. We are doing it manually to understand what
// Spring does under the hood.
public class AppConfig {

    public static final String BASE_URL = "https://short.url";

    /**
     * Factory method: creates a UrlShortenerService with default wiring.
     * THIS is where you swap the algorithm.
     */
    public static UrlShortenerService createDefaultService() {
        // 1. Create the repository (data layer)
        UrlRepository repository = new InMemoryUrlRepository();

        // 2. Create the strategy (algorithm)
        // *** SWAP THE ALGORITHM BY CHANGING THIS ONE LINE ***
        EncodingStrategy strategy = new Base62EncodingStrategy();
        // Want MD5?    EncodingStrategy strategy = new Md5EncodingStrategy();
        // Want Random? EncodingStrategy strategy = new RandomEncodingStrategy();

        // 3. Wire them into the service
        // The service receives INTERFACES (UrlRepository, EncodingStrategy),
        // not concrete classes. It has no idea what is behind these interfaces.
        return new UrlShortenerService(repository, strategy, BASE_URL);
    }

    /**
     * Factory method: creates a service with a SPECIFIC encoding strategy.
     * This is useful in tests or when the caller wants to choose.
     */
    public static UrlShortenerService createServiceWithStrategy(EncodingStrategy strategy) {
        UrlRepository repository = new InMemoryUrlRepository();
        return new UrlShortenerService(repository, strategy, BASE_URL);
    }
}
```

---

### Strategy in the Rate Limiter (02-rate-limiter): Map-Based Dynamic Selection

The Rate Limiter takes the Strategy pattern further. Instead of injecting **one** strategy, it injects a **Map of all strategies** and selects the right one at runtime based on the rule's algorithm field.

```java
// File: 02-rate-limiter/.../strategy/RateLimiterStrategy.java
//
// === THE STRATEGY INTERFACE FOR RATE LIMITING ===
// Same concept as EncodingStrategy, but with a richer contract:
// - tryConsume(): attempt to allow one request (returns allowed/rejected)
// - reset(): clear state for a key (used when rules change)
// - algorithm(): returns which Algorithm enum this strategy implements
public interface RateLimiterStrategy {

    /**
     * Attempt to consume one token/slot for the given key under the given rule.
     * Returns a RateLimitResult that tells the caller:
     *   - Was the request allowed or rejected?
     *   - How many requests remain in this window?
     *   - When does the window reset?
     *   - If rejected, how long should the client wait before retrying?
     */
    RateLimitResult tryConsume(String key, RateLimitRule rule);

    /** Reset all counters/state for the given key. */
    void reset(String key);

    /** Which algorithm this strategy implements (for Map lookup). */
    Algorithm algorithm();
}
```

Here is one of the 5 concrete implementations:

```java
// File: 02-rate-limiter/.../strategy/TokenBucketStrategy.java
//
// === CONCRETE STRATEGY: Token Bucket ===
// Tokens accumulate at a steady rate up to a max capacity.
// Each request consumes one token. If no tokens are left, the request is rejected.
//
// Why this is the most popular algorithm (used by AWS API Gateway, Stripe):
// - It allows BURSTS (if tokens have accumulated, a client can send many
//   requests quickly), which feels natural for real traffic patterns
// - It smoothly limits sustained throughput to the configured rate
// - It is simple to implement and reason about
//
// Per-key state: each client/endpoint combo gets its own bucket object
// stored in a ConcurrentHashMap for thread-safety.
public class TokenBucketStrategy implements RateLimiterStrategy {

    // Each key (e.g., "user123:/api/data") gets its own TokenBucket.
    // ConcurrentHashMap for safe concurrent access without global locking.
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    @Override
    public RateLimitResult tryConsume(String key, RateLimitRule rule) {
        // computeIfAbsent: atomically creates the bucket on first request.
        // After that, the same bucket object is reused for all requests from this key.
        TokenBucket bucket = buckets.computeIfAbsent(key,
                k -> new TokenBucket(
                    rule.getBurstCapacity(),  // max tokens the bucket can hold
                    rule.getMaxRequests(),     // refill rate numerator
                    rule.getWindowSizeMs()     // refill rate denominator
                ));

        // Fine-grained locking: we synchronize on THIS bucket only,
        // not on the entire map. Other keys can proceed concurrently.
        synchronized (bucket) {
            long now = System.currentTimeMillis();

            // Refill tokens based on elapsed time since last refill
            bucket.refill(now);

            if (bucket.tokens >= 1.0) {
                // ALLOWED: consume one token
                bucket.tokens -= 1.0;
                int remaining = (int) bucket.tokens;
                long resetAtMs = now + (long)
                    ((bucket.capacity - bucket.tokens) / bucket.refillRatePerMs);
                return RateLimitResult.allowed(remaining, rule.getMaxRequests(), resetAtMs);
            } else {
                // REJECTED: not enough tokens. Tell client when to retry.
                long retryAfterMs = (long) ((1.0 - bucket.tokens) / bucket.refillRatePerMs);
                long resetAtMs = now + retryAfterMs;
                return RateLimitResult.rejected(
                    rule.getMaxRequests(), retryAfterMs, resetAtMs);
            }
        }
    }

    @Override
    public void reset(String key) {
        buckets.remove(key);
    }

    @Override
    public Algorithm algorithm() {
        return Algorithm.TOKEN_BUCKET;
    }

    /**
     * Inner class: the actual bucket state for one key.
     * - tokens: current number of available tokens (fractional because refill is continuous)
     * - lastRefillTimeMs: when we last recalculated tokens
     * - capacity: maximum tokens the bucket can hold (burst capacity)
     * - refillRatePerMs: how many tokens are added per millisecond
     */
    private static class TokenBucket {
        double tokens;
        long lastRefillTimeMs;
        final int capacity;
        final double refillRatePerMs;

        TokenBucket(int capacity, int maxRequests, long windowSizeMs) {
            this.capacity = capacity;
            this.tokens = capacity;  // Start FULL — allow initial burst
            this.lastRefillTimeMs = System.currentTimeMillis();
            // If rule says "10 requests per 60000ms", refill rate = 10/60000 = 0.000167 tokens/ms
            this.refillRatePerMs = (double) maxRequests / windowSizeMs;
        }

        void refill(long now) {
            long elapsed = now - lastRefillTimeMs;
            if (elapsed > 0) {
                double newTokens = elapsed * refillRatePerMs;
                tokens = Math.min(capacity, tokens + newTokens); // Never exceed capacity
                lastRefillTimeMs = now;
            }
        }
    }
}
```

#### How the Rate Limiter wires ALL strategies into a Map

```java
// File: 02-rate-limiter/.../config/AppConfig.java
//
// === THE MAP-BASED STRATEGY REGISTRATION ===
// Instead of injecting a single strategy (like the URL shortener does),
// the Rate Limiter creates ALL 5 strategies and stores them in an
// EnumMap keyed by Algorithm enum.
//
// This allows DYNAMIC strategy selection: each rate limit rule can
// specify which algorithm to use, and the service looks it up at runtime.
//
// This is more flexible than the URL shortener approach because different
// endpoints or clients can use DIFFERENT algorithms simultaneously.
public class AppConfig {

    public static final Algorithm DEFAULT_ALGORITHM = Algorithm.TOKEN_BUCKET;

    /** Creates all 5 strategies, maps them by algorithm enum. */
    public static Map<Algorithm, RateLimiterStrategy> createStrategies() {
        // EnumMap is more efficient than HashMap for enum keys:
        // - Uses an array internally (not hash buckets)
        // - O(1) lookup with no hashing overhead
        // - Guaranteed iteration order matches enum declaration order
        Map<Algorithm, RateLimiterStrategy> strategies = new EnumMap<>(Algorithm.class);
        strategies.put(Algorithm.TOKEN_BUCKET,          new TokenBucketStrategy());
        strategies.put(Algorithm.LEAKY_BUCKET,          new LeakyBucketStrategy());
        strategies.put(Algorithm.FIXED_WINDOW,          new FixedWindowCounterStrategy());
        strategies.put(Algorithm.SLIDING_WINDOW_LOG,    new SlidingWindowLogStrategy());
        strategies.put(Algorithm.SLIDING_WINDOW_COUNTER, new SlidingWindowCounterStrategy());
        return strategies;
    }

    /** Factory: fully-wired RateLimiterService. */
    public static RateLimiterService createDefaultService() {
        RuleRepository repository = new InMemoryRuleRepository();
        Map<Algorithm, RateLimiterStrategy> strategies = createStrategies();
        RateLimitRule defaultRule = createDefaultRule();
        return new RateLimiterService(repository, strategies, defaultRule);
    }
}
```

#### How the service uses the Map for dynamic dispatch

```java
// File: 02-rate-limiter/.../service/RateLimiterService.java
//
// === DYNAMIC STRATEGY SELECTION AT RUNTIME ===
// The service holds a Map<Algorithm, RateLimiterStrategy>.
// When a request arrives, it:
//   1. Looks up the rule for this client/endpoint
//   2. Gets the algorithm from the rule
//   3. Gets the strategy from the map
//   4. Calls tryConsume() on that strategy
//
// This is Strategy + Map = dynamic dispatch without if-else.
public class RateLimiterService {

    private final RuleRepository ruleRepository;
    private final Map<Algorithm, RateLimiterStrategy> strategies; // ALL strategies
    private final RateLimitRule defaultRule;

    public RateLimiterService(RuleRepository ruleRepository,
                              Map<Algorithm, RateLimiterStrategy> strategies,
                              RateLimitRule defaultRule) {
        this.ruleRepository = ruleRepository;
        this.strategies = strategies;
        this.defaultRule = defaultRule;
    }

    /**
     * Main entry point: check if a request should be allowed or throttled.
     */
    public RateLimitResult checkRateLimit(RequestContext context) {
        String key = context.getRateLimitKey(); // e.g., "user123:/api/data"

        // 1. Look up the rule for this key (or use the default)
        RateLimitRule rule = ruleRepository.findByKey(key).orElse(defaultRule);

        // 2. Disabled rules always allow traffic through
        if (!rule.isEnabled()) {
            return RateLimitResult.allowed(rule.getMaxRequests(), rule.getMaxRequests(), 0);
        }

        // 3. Get the strategy for this rule's algorithm
        //    rule.getAlgorithm() returns an Algorithm enum like TOKEN_BUCKET
        //    strategies.get() returns the matching TokenBucketStrategy object
        RateLimiterStrategy strategy = strategies.get(rule.getAlgorithm());
        if (strategy == null) {
            throw new IllegalStateException(
                "No strategy registered for algorithm: " + rule.getAlgorithm());
        }

        // 4. Delegate to the strategy — polymorphic call
        //    The service does not know if it is calling TokenBucket, LeakyBucket,
        //    FixedWindow, etc. It just calls tryConsume() and gets a result.
        return strategy.tryConsume(key, rule);
    }
}
```

---

### Strategy in the Notification System (03-notification-system): Channel-Based Dispatch

The Notification System uses the exact same Map-based approach, but keyed by `Channel` enum instead of `Algorithm`.

```java
// File: 03-notification-system/.../handler/NotificationHandler.java
//
// === THE STRATEGY INTERFACE FOR NOTIFICATION DELIVERY ===
// Each channel (PUSH, EMAIL, SMS, IN_APP) has a handler that knows
// HOW to deliver a notification via that channel's provider.
//
// In production:
//   - PushNotificationHandler would call Firebase Cloud Messaging (FCM) or APNs
//   - EmailNotificationHandler would call AWS SES or SendGrid
//   - SmsNotificationHandler would call Twilio
//   - InAppNotificationHandler would write to a database
//
// In our demo, they simulate delivery with configurable success rates.
public interface NotificationHandler {

    /**
     * Attempt delivery and return the result.
     * The DeliveryAttempt contains: status (SENT/FAILED), provider response,
     * attempt number, and timestamp.
     */
    DeliveryAttempt send(Notification notification);

    /** The channel this handler is responsible for. */
    Channel supportedChannel();

    /** Health check — is this provider currently available? */
    boolean isAvailable();
}
```

```java
// File: 03-notification-system/.../handler/EmailNotificationHandler.java
//
// === CONCRETE STRATEGY: Email via AWS SES (simulated) ===
// Simulates a 95% success rate to demonstrate retry scenarios.
// In production, this would make an actual API call to SES/SendGrid.
public class EmailNotificationHandler implements NotificationHandler {

    private final Random random = new Random();

    @Override
    public DeliveryAttempt send(Notification notification) {
        System.out.printf("  [EMAIL] Sending to user:%s -> %s%n",
                notification.getUserId(), notification.getSubject());

        // Simulate network call with 95% success rate
        boolean success = random.nextInt(100) < 95;
        String msgId = UUID.randomUUID().toString().substring(0, 8);

        NotificationStatus status = success
                ? NotificationStatus.SENT
                : NotificationStatus.FAILED;
        String response = success
                ? "SES:message_id_" + msgId       // Simulated SES response
                : "SES:bounce_invalid_address";    // Simulated SES failure

        return new DeliveryAttempt(
                notification.getId(),
                notification.getRetryCount() + 1,
                status,
                response,
                LocalDateTime.now()
        );
    }

    @Override
    public Channel supportedChannel() {
        return Channel.EMAIL;
    }

    @Override
    public boolean isAvailable() {
        return true; // In production: check SES health endpoint
    }
}
```

```java
// File: 03-notification-system/.../config/AppConfig.java
//
// === WIRING: Map<Channel, NotificationHandler> ===
// Same approach as Rate Limiter's Map<Algorithm, RateLimiterStrategy>.
// One handler per channel, looked up at runtime.
public NotificationService createNotificationService() {
    // Create the handler map — one handler per channel
    Map<Channel, NotificationHandler> handlers = new EnumMap<>(Channel.class);
    handlers.put(Channel.PUSH,   new PushNotificationHandler());   // FCM/APNs
    handlers.put(Channel.EMAIL,  new EmailNotificationHandler());  // AWS SES
    handlers.put(Channel.SMS,    new SmsNotificationHandler());    // Twilio
    handlers.put(Channel.IN_APP, new InAppNotificationHandler());  // Database

    // Wire everything into the service
    return new NotificationService(
            notificationRepository,
            preferenceService,
            templateService,
            deliveryTracker,
            queue,
            handlers    // <-- the strategy map
    );
}
```

```java
// File: 03-notification-system/.../service/NotificationService.java
//
// === RUNTIME DISPATCH: handlers.get(notification.getChannel()) ===
// When processing a notification from the queue, the service looks up
// the right handler by channel and calls send().
public int processQueue(int maxBatch) {
    for (int i = 0; i < maxBatch; i++) {
        Notification notification = queue.dequeue();
        if (notification == null) break;

        // DYNAMIC STRATEGY SELECTION:
        // notification.getChannel() returns Channel.EMAIL, Channel.PUSH, etc.
        // handlers.get() returns the matching handler implementation.
        NotificationHandler handler = handlers.get(notification.getChannel());

        // Attempt delivery via the channel-specific handler
        DeliveryAttempt attempt = handler.send(notification);
        deliveryTracker.record(attempt);

        // Handle success/failure, retries, etc...
    }
}
```

---

### The Full Call Chain (URL Shortener, end-to-end)

```
1. UrlShortenerApp.main()
   └── AppConfig.createDefaultService()
       ├── new InMemoryUrlRepository()           // Creates data store
       ├── new Base62EncodingStrategy()           // Creates the strategy <-- DECISION POINT
       └── new UrlShortenerService(repo, strategy, baseUrl)  // Injects both

2. User calls: service.shortenUrl(request)
   └── generateUniqueCode(originalUrl)
       ├── counter.incrementAndGet()              // Get next counter value: 100001
       ├── encodingStrategy.encode("100001")      // POLYMORPHIC CALL
       │   └── Base62EncodingStrategy.encode()    // Runs base-62 conversion
       │       └── Returns "0000q91"              // The generated short code
       └── repository.existsByShortCode("0000q91") // Check for collision
           └── Returns false                       // No collision, use this code

3. Result: https://short.url/0000q91
```

---

### When to Use / When NOT to Use

**Use when:**
- You have multiple algorithms/behaviors that serve the same purpose
- The algorithm needs to be swappable at runtime or configurable
- You want to unit-test each algorithm in isolation
- You want to add new algorithms without modifying existing code

**Do NOT use when:**
- There is only one algorithm and you do not anticipate more (YAGNI)
- The algorithms are trivially simple (a single line of code)
- The "strategies" share so much state that separating them creates more duplication than clarity

### Interview One-Liner

> "Strategy Pattern lets you define a family of algorithms, encapsulate each one in its own class behind a common interface, and make them interchangeable. The client code depends on the interface, not the implementation, so you can swap algorithms without changing the client."

### Projects Using This Pattern

| Project | Interface | Implementations | Selection Mechanism |
|---------|-----------|----------------|---------------------|
| URL Shortener | `EncodingStrategy` | `Base62EncodingStrategy`, `Md5EncodingStrategy`, `RandomEncodingStrategy` | Single injection via constructor |
| Rate Limiter | `RateLimiterStrategy` | `TokenBucketStrategy`, `LeakyBucketStrategy`, `FixedWindowCounterStrategy`, `SlidingWindowLogStrategy`, `SlidingWindowCounterStrategy` | `Map<Algorithm, RateLimiterStrategy>` + runtime lookup |
| Notification System | `NotificationHandler` | `PushNotificationHandler`, `EmailNotificationHandler`, `SmsNotificationHandler`, `InAppNotificationHandler` | `Map<Channel, NotificationHandler>` + runtime lookup |

---

## 2. Builder Pattern

**One-line definition:** Construct complex objects step-by-step with a fluent API, making construction self-documenting and allowing optional fields with defaults.

---

### The Problem: Telescoping Constructors

```java
// === THE UGLY VERSION: Telescoping constructor ===
// What does each parameter mean? What is null for? What is 0?
// You cannot tell without checking the constructor signature.
// And if you add a new field, EVERY call site needs to be updated.

// URL Shortener — creating a Url object:
Url url = new Url("abc123", "code1", "https://example.com/very-long-article",
        LocalDateTime.now(), null, 0, null, null);
//      ^^^^^^^^^^^^^^        ^^^^  ^  ^^^^  ^^^^
//      What is this?         |     |  What are these two nulls?
//                     Is null the expiry? Or the alias?
//                            What is 0? Click count? Retry count?

// Rate Limiter — creating a rule:
RateLimitRule rule = new RateLimitRule("api-key-1", "api-key-1",
        10, 60000, Algorithm.TOKEN_BUCKET, 10, true);
//      ^^  ^^^^^                         ^^  ^^^^
//      Is 10 maxRequests or burstCapacity? What is true?

// Notification — creating a notification:
Notification notif = new Notification("abc", "alice", "order-confirm",
        Channel.EMAIL, Priority.HIGH, NotificationStatus.PENDING,
        "Subject", "Body", data, null, null, null, 0, 3, LocalDateTime.now());
//      15 PARAMETERS! Completely unreadable.
```

---

### The Solution: Builder with Fluent API

```java
// === THE CLEAN VERSION: Builder pattern ===
// Self-documenting, only set what you need, optional fields have defaults.
// You can READ what each value means without checking any documentation.

// URL Shortener:
Url url = Url.builder()
        .id("abc123")
        .shortCode("code1")                         // required
        .originalUrl("https://example.com/article")  // required
        .expiresAt(LocalDateTime.now().plusDays(30))  // optional — defaults to null (no expiry)
        .build();                                     // validates and creates immutable object
// Notice: clickCount defaults to 0, customAlias defaults to null.
// We did not have to specify them — the builder handles defaults.

// Rate Limiter:
RateLimitRule rule = RateLimitRule.builder("api-key-1", 10, 60_000)
        .algorithm(Algorithm.SLIDING_WINDOW_COUNTER)  // optional — defaults to TOKEN_BUCKET
        .burstCapacity(20)                             // optional — defaults to maxRequests
        .build();

// Notification:
Notification notification = new Notification.Builder()
        .userId("alice")                    // required
        .channel(Channel.EMAIL)             // required
        .templateId("order-confirmation")   // optional
        .priority(Priority.HIGH)            // optional — defaults to MEDIUM
        .data(Map.of("orderId", "12345"))   // optional — defaults to empty map
        .maxRetries(5)                      // optional — defaults to 3
        .build();                           // validates userId + channel, then creates object
```

---

### Real Code Example: The Full Builder Internals (URL Shortener)

```java
// File: 01-url-shortener/.../model/Url.java
//
// === HOW THE BUILDER PATTERN ACTUALLY WORKS, INSIDE AND OUT ===
//
// The Builder is a STATIC INNER CLASS of the object it builds.
// Why static inner class?
//   - It can access the outer class's private constructor
//   - It does not need an instance of the outer class to exist
//   - The syntax reads naturally: Url.builder() or Url.Builder
//
// The pattern has 4 parts:
//   1. The outer class (Url) with a PRIVATE constructor
//   2. The static inner Builder class with the same fields
//   3. Fluent setter methods on the Builder that return "this"
//   4. A build() method that validates and calls the private constructor
public class Url {

    // === PART 1: THE OUTER CLASS — IMMUTABLE AFTER CONSTRUCTION ===
    // All fields are final (except clickCount which is mutable by design).
    // Once build() creates this object, its state cannot change.
    // This makes it thread-safe without synchronization.
    private final String id;
    private final String shortCode;
    private final String originalUrl;
    private final LocalDateTime createdAt;
    private final LocalDateTime expiresAt;
    private long clickCount;          // Mutable — incremented on each redirect
    private final String customAlias;
    private final String userId;

    // === PRIVATE CONSTRUCTOR ===
    // WHY private? Because the ONLY way to create a Url is through the Builder.
    // This ensures the object is always properly validated and initialized.
    // No one can "accidentally" create a Url with missing fields.
    //
    // The constructor takes a Builder object and copies all its fields.
    // This is the "handoff" from the mutable Builder to the immutable Url.
    private Url(Builder builder) {
        this.id = builder.id;
        this.shortCode = builder.shortCode;
        this.originalUrl = builder.originalUrl;
        this.createdAt = builder.createdAt;
        this.expiresAt = builder.expiresAt;
        this.clickCount = builder.clickCount;
        this.customAlias = builder.customAlias;
        this.userId = builder.userId;
    }

    // === Domain logic (isExpired, incrementClickCount, getters) omitted for brevity ===

    // === ENTRY POINT: static factory method to start building ===
    // Why a static method instead of "new Url.Builder()"?
    // Reads better: Url.builder().shortCode("abc") vs new Url.Builder().shortCode("abc")
    // Both work fine; this is a stylistic choice.
    public static Builder builder() {
        return new Builder();
    }

    // === PART 2: THE STATIC INNER BUILDER CLASS ===
    public static class Builder {
        // === DUPLICATED FIELDS ===
        // Yes, the Builder has the SAME fields as the outer class.
        // This is intentional: the Builder is a MUTABLE staging area
        // where you set fields one at a time. Once build() is called,
        // the values are copied to the IMMUTABLE outer class.
        //
        // Some fields have DEFAULTS:
        private String id;
        private String shortCode;
        private String originalUrl;
        private LocalDateTime createdAt = LocalDateTime.now();  // Default: now
        private LocalDateTime expiresAt;                         // Default: null (no expiry)
        private long clickCount = 0;                             // Default: 0
        private String customAlias;                              // Default: null
        private String userId;                                   // Default: null

        // === FLUENT SETTER METHODS ===
        // Each method:
        //   1. Sets the field value
        //   2. Returns "this" (the Builder itself)
        //
        // Returning "this" enables METHOD CHAINING:
        //   builder.shortCode("abc").originalUrl("http://...").build()
        //
        // Without returning "this", you would need:
        //   builder.setShortCode("abc");
        //   builder.setOriginalUrl("http://...");
        //   Url url = builder.build();
        // That works but is less readable.
        public Builder id(String id) { this.id = id; return this; }
        public Builder shortCode(String shortCode) { this.shortCode = shortCode; return this; }
        public Builder originalUrl(String originalUrl) { this.originalUrl = originalUrl; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder expiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; return this; }
        public Builder clickCount(long clickCount) { this.clickCount = clickCount; return this; }
        public Builder customAlias(String customAlias) { this.customAlias = customAlias; return this; }
        public Builder userId(String userId) { this.userId = userId; return this; }

        // === PART 4: THE build() METHOD — VALIDATES AND CREATES ===
        // This is the final step. It:
        //   1. Validates that required fields are present
        //   2. Calls the private constructor of the outer class
        //   3. Returns the finished, immutable Url object
        //
        // After build() is called, the Builder can be discarded.
        // The Url object is self-contained.
        public Url build() {
            // Validation: catch errors at CONSTRUCTION time, not at USE time.
            // This is much better than getting a NullPointerException later
            // when you try to use the originalUrl.
            if (originalUrl == null || originalUrl.isBlank()) {
                throw new IllegalArgumentException("originalUrl is required");
            }
            if (shortCode == null || shortCode.isBlank()) {
                throw new IllegalArgumentException("shortCode is required");
            }
            // Creates the immutable Url by passing "this" (the Builder) to the private constructor
            return new Url(this);
        }
    }
}
```

---

### Real Code Example: Builder with Required Constructor Parameters (Rate Limiter)

The Rate Limiter's Builder shows a different flavor: **required fields are passed to the Builder constructor**, not via setter methods. This makes it impossible to forget them.

```java
// File: 02-rate-limiter/.../model/RateLimitRule.java
//
// === BUILDER VARIATION: Required fields in the Builder constructor ===
// Unlike Url.Builder where all fields are set via fluent methods,
// RateLimitRule.Builder forces you to provide key, maxRequests, and windowSizeMs
// UP FRONT in the constructor. You literally cannot create a Builder without them.
public class RateLimitRule {

    private final String id;
    private final String key;
    private final int maxRequests;
    private final long windowSizeMs;
    private final Algorithm algorithm;
    private final int burstCapacity;
    private final boolean enabled;

    private RateLimitRule(Builder builder) {
        this.id = builder.id;
        this.key = builder.key;
        this.maxRequests = builder.maxRequests;
        this.windowSizeMs = builder.windowSizeMs;
        this.algorithm = builder.algorithm;
        // Smart default: if burstCapacity was not set (-1 sentinel),
        // default it to maxRequests. This means "no extra burst allowed."
        this.burstCapacity = builder.burstCapacity == -1
                ? builder.maxRequests
                : builder.burstCapacity;
        this.enabled = builder.enabled;
    }

    // === ENTRY POINT: required fields in the factory method ===
    // You MUST provide key, maxRequests, and windowSizeMs.
    // The compiler enforces this — you cannot call builder() without them.
    public static Builder builder(String key, int maxRequests, long windowSizeMs) {
        return new Builder(key, maxRequests, windowSizeMs);
    }

    public static class Builder {
        // REQUIRED fields — set in constructor, final
        private final String key;
        private final int maxRequests;
        private final long windowSizeMs;

        // OPTIONAL fields — have defaults
        private String id;
        private Algorithm algorithm = Algorithm.TOKEN_BUCKET;  // Sensible default
        private int burstCapacity = -1;  // Sentinel: means "use maxRequests"
        private boolean enabled = true;  // Rules are enabled by default

        // Private constructor — only accessible via the static builder() method
        private Builder(String key, int maxRequests, long windowSizeMs) {
            this.key = key;
            this.maxRequests = maxRequests;
            this.windowSizeMs = windowSizeMs;
            this.id = key;  // Default: id = key
        }

        public Builder id(String id) { this.id = id; return this; }
        public Builder algorithm(Algorithm algorithm) { this.algorithm = algorithm; return this; }
        public Builder burstCapacity(int burstCapacity) { this.burstCapacity = burstCapacity; return this; }
        public Builder enabled(boolean enabled) { this.enabled = enabled; return this; }

        public RateLimitRule build() {
            // Validation: catch bad values early
            if (maxRequests <= 0) throw new IllegalArgumentException("maxRequests must be positive");
            if (windowSizeMs <= 0) throw new IllegalArgumentException("windowSizeMs must be positive");
            return new RateLimitRule(this);
        }
    }
}
```

Usage comparison:

```java
// REQUIRED fields only (all optional fields use defaults):
RateLimitRule rule = RateLimitRule.builder("api-key-1", 10, 60_000).build();
// Gets: algorithm=TOKEN_BUCKET, burstCapacity=10, enabled=true

// Override one optional field:
RateLimitRule rule = RateLimitRule.builder("api-key-1", 10, 60_000)
        .algorithm(Algorithm.SLIDING_WINDOW_COUNTER)
        .build();

// Override everything:
RateLimitRule rule = RateLimitRule.builder("api-key-1", 10, 60_000)
        .algorithm(Algorithm.LEAKY_BUCKET)
        .burstCapacity(20)
        .enabled(false)
        .build();
```

---

### Real Code Example: Notification Builder

```java
// File: 03-notification-system/.../model/Notification.java
//
// === BUILDER WITH auto-generated ID and Comparable for priority queuing ===
// This Builder shows two additional features:
//   1. Auto-generated fields: id and createdAt are set in the Builder's
//      field initializers, not by the caller. The caller CAN override them
//      (useful in tests), but usually does not.
//   2. The outer class implements Comparable<Notification> so it can be
//      ordered by priority in a PriorityBlockingQueue.
public class Notification implements Comparable<Notification> {

    // ... 14 fields (id, userId, templateId, channel, priority, status,
    //     subject, body, data, scheduledAt, sentAt, deliveredAt,
    //     retryCount, maxRetries, createdAt) ...

    private Notification(Builder builder) {
        // Copy all fields from builder to notification
        this.id = builder.id;
        this.userId = builder.userId;
        // ... etc ...
    }

    // Comparable: CRITICAL (0) < HIGH (1) < MEDIUM (2) < LOW (3)
    // PriorityBlockingQueue dequeues the SMALLEST value first,
    // so CRITICAL notifications are processed before LOW ones.
    @Override
    public int compareTo(Notification other) {
        return Integer.compare(this.priority.getValue(), other.priority.getValue());
    }

    public static class Builder {
        // Auto-generated defaults — the caller usually does not set these
        private String id = UUID.randomUUID().toString().substring(0, 8);
        private LocalDateTime createdAt = LocalDateTime.now();

        // Required (validated in build())
        private String userId;
        private Channel channel;

        // Optional with sensible defaults
        private Priority priority = Priority.MEDIUM;
        private NotificationStatus status = NotificationStatus.PENDING;
        private int retryCount = 0;
        private int maxRetries = 3;
        private Map<String, String> data = new HashMap<>();
        // ... other optional fields ...

        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder channel(Channel channel) { this.channel = channel; return this; }
        public Builder priority(Priority priority) { this.priority = priority; return this; }
        public Builder data(Map<String, String> data) {
            // Defensive copy — prevents the caller from mutating our internal map
            this.data = new HashMap<>(data);
            return this;
        }
        // ... other fluent setters ...

        public Notification build() {
            // Only userId and channel are truly required
            if (userId == null || channel == null) {
                throw new IllegalArgumentException("userId and channel are required");
            }
            return new Notification(this);
        }
    }
}
```

---

### When to Use / When NOT to Use

**Use when:**
- An object has more than 3-4 constructor parameters
- Some parameters are optional with sensible defaults
- You want construction to be self-documenting (named parameters via methods)
- You want to validate the object at construction time
- You want the final object to be immutable

**Do NOT use when:**
- The object has only 1-2 fields (a constructor is fine)
- All fields are required and there are no defaults (constructor works)
- The object needs to be mutable anyway (just use setters)

### Interview One-Liner

> "Builder Pattern provides a fluent API for constructing complex objects step-by-step, separating the construction logic from the representation, making it self-documenting and supporting optional parameters with defaults."

### Projects Using This Pattern

| Project | Builder Class | Required Fields | Notable Feature |
|---------|--------------|-----------------|-----------------|
| URL Shortener | `Url.Builder` | `shortCode`, `originalUrl` | Static `builder()` method, all fluent setters |
| Rate Limiter | `RateLimitRule.Builder` | `key`, `maxRequests`, `windowSizeMs` | Required fields in Builder constructor; sentinel default for `burstCapacity` |
| Notification System | `Notification.Builder` | `userId`, `channel` | Auto-generated `id` and `createdAt`; defensive copy for `data` map |

---

## 3. Repository Pattern

**One-line definition:** Abstract data access behind an interface so the business logic never knows (or cares) whether data is in a HashMap, Redis, PostgreSQL, or DynamoDB.

---

### The Problem: Tightly Coupled Data Access

```java
// === THE UGLY VERSION: Service directly uses a HashMap ===
// The service IS the data store. Business logic and storage are tangled together.
public class UrlShortenerService {

    // The service owns the HashMap directly. Problems:
    // 1. Cannot switch to Redis/PostgreSQL without rewriting the service
    // 2. Cannot test business logic without also testing storage
    // 3. Every method has HashMap operations mixed with business logic
    // 4. Thread-safety is the service's problem now
    private final Map<String, Url> store = new ConcurrentHashMap<>();

    public UrlShortenResponse shortenUrl(UrlShortenRequest request) {
        // ... business logic (validation, code generation) ...

        // Storage logic directly in the service method:
        store.put(shortCode, url);   // <-- Tightly coupled to ConcurrentHashMap

        // ... more business logic (build response) ...
    }

    public String redirect(String shortCode) {
        Url url = store.get(shortCode);  // <-- Tightly coupled
        if (url == null) {
            throw new UrlNotFoundException(shortCode);
        }
        // ... business logic ...
    }
}
// Want to switch to Redis? Rewrite EVERY method in the service.
// Want to add caching? Rewrite EVERY method in the service.
// Want to test business logic without a database? Impossible.
```

---

### The Solution: Interface + Implementation

```java
// File: 01-url-shortener/.../repository/UrlRepository.java
//
// === THE INTERFACE — A CONTRACT, NOT AN IMPLEMENTATION ===
// This is the BOUNDARY between "business logic" and "data storage."
// The service talks to THIS interface. It has NO IDEA if data is in:
//   - ConcurrentHashMap (our demo implementation)
//   - Redis (production cache layer)
//   - PostgreSQL (production persistence layer)
//   - DynamoDB (AWS serverless option)
//   - A mock object (unit tests)
//
// That is the WHOLE POINT. The interface defines WHAT operations are available,
// but says nothing about HOW they are implemented.
//
// Think of it as a contract: "I promise I can find URLs by short code,
// save URLs, delete URLs, and check if a short code exists.
// HOW I do it is none of your business."
public interface UrlRepository {

    /**
     * Find a URL by its short code.
     * Returns Optional.empty() if no URL exists with that code.
     * WHY Optional? Because "not found" is a normal case, not an error.
     * The caller decides what to do: throw an exception, return 404, etc.
     */
    Optional<Url> findByShortCode(String shortCode);

    /**
     * Save (create or update) a URL entity.
     * Returns the saved entity (may have server-generated fields).
     */
    Url save(Url url);

    /** Delete a URL by its short code. Idempotent: no error if not found. */
    void deleteByShortCode(String shortCode);

    /** Check existence without loading the full object. More efficient for validation. */
    boolean existsByShortCode(String shortCode);

    /** Count all stored URLs. Useful for metrics/monitoring. */
    long count();
}
```

```java
// File: 01-url-shortener/.../repository/InMemoryUrlRepository.java
//
// === THE IN-MEMORY IMPLEMENTATION ===
// This is our demo/dev implementation. It stores everything in a ConcurrentHashMap.
// Fast, simple, but data is lost when the JVM shuts down.
//
// In production, you would create:
//   - RedisUrlRepository implements UrlRepository (for caching)
//   - PostgresUrlRepository implements UrlRepository (for persistence)
//   - DynamoDbUrlRepository implements UrlRepository (for serverless)
//
// To switch: change ONE line in AppConfig. The service class? ZERO changes.
public class InMemoryUrlRepository implements UrlRepository {

    // ConcurrentHashMap: thread-safe without explicit synchronization.
    // - Reads are lock-free (no blocking even under contention)
    // - Writes lock only the affected hash bucket (not the entire map)
    // - Perfect for read-heavy workloads like URL shorteners
    private final ConcurrentHashMap<String, Url> store = new ConcurrentHashMap<>();

    @Override
    public Optional<Url> findByShortCode(String shortCode) {
        // Optional.ofNullable: wraps the value in Optional, or returns
        // Optional.empty() if the value is null (key not found).
        return Optional.ofNullable(store.get(shortCode));
    }

    @Override
    public Url save(Url url) {
        // put() is an upsert: creates if new, overwrites if exists.
        // The key is the shortCode because that is what we look up by.
        store.put(url.getShortCode(), url);
        return url;
    }

    @Override
    public void deleteByShortCode(String shortCode) {
        store.remove(shortCode);
    }

    @Override
    public boolean existsByShortCode(String shortCode) {
        return store.containsKey(shortCode);
    }

    @Override
    public long count() {
        return store.size();
    }
}
```

---

### How the Service Uses the Repository (Decoupled)

```java
// File: 01-url-shortener/.../service/UrlShortenerService.java
//
// === THE SERVICE DEPENDS ON THE INTERFACE, NOT THE IMPLEMENTATION ===
// Notice: this class imports UrlRepository (the interface),
// NOT InMemoryUrlRepository (the implementation).
// It has no idea what is behind the interface.
public class UrlShortenerService {

    private final UrlRepository repository; // Could be in-memory, Redis, or Postgres

    // The repository is INJECTED via constructor — Dependency Inversion Principle
    public UrlShortenerService(UrlRepository repository,
                               EncodingStrategy encodingStrategy,
                               String baseUrl) {
        this.repository = repository;
        // ...
    }

    public String redirect(String shortCode) {
        // The service calls repository methods.
        // It does not know (or care) if findByShortCode() is:
        //   - Doing store.get(shortCode) on a HashMap
        //   - Doing GET shortCode on Redis
        //   - Doing SELECT * FROM urls WHERE short_code = ? on PostgreSQL
        Url url = repository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        if (url.isExpired()) {
            throw new UrlExpiredException(shortCode);
        }

        url.incrementClickCount();
        return url.getOriginalUrl();
    }
}
```

---

### Repository Pattern Across All 3 Projects

#### Rate Limiter Repositories

```java
// File: 02-rate-limiter/.../repository/RuleRepository.java
//
// === RATE LIMIT RULE STORAGE ===
// Stores the rules that define rate limits per key.
// In production: Redis (for sub-millisecond lookups) or a config service.
public interface RuleRepository {
    Optional<RateLimitRule> findByKey(String key);
    List<RateLimitRule> findAll();
    void save(RateLimitRule rule);
    void delete(String key);
    boolean exists(String key);
}
```

#### Notification System Repositories (3 repositories)

```java
// File: 03-notification-system/.../repository/NotificationRepository.java
//
// === NOTIFICATION STORAGE ===
// Stores notifications and their delivery status.
// In production: Cassandra or DynamoDB (write-heavy, time-series-friendly).
public interface NotificationRepository {
    void save(Notification notification);
    Optional<Notification> findById(String id);
    List<Notification> findByUserId(String userId);
    void updateStatus(String id, NotificationStatus status);
    List<Notification> findRetryable(); // Find FAILED notifications eligible for retry
}

// File: 03-notification-system/.../repository/PreferenceRepository.java
//
// === USER PREFERENCE STORAGE ===
// Stores per-user notification settings (channel opt-in, quiet hours, etc.).
// In production: Redis or DynamoDB (read-heavy, user-keyed).
public interface PreferenceRepository {
    Optional<UserPreference> findByUserId(String userId);
    void save(UserPreference preference);
}

// File: 03-notification-system/.../repository/TemplateRepository.java
//
// === NOTIFICATION TEMPLATE STORAGE ===
// Stores message templates with placeholders like "Hi {{name}}, your order {{orderId}}..."
// In production: a CMS or database with versioning.
public interface TemplateRepository {
    Optional<NotificationTemplate> findById(String id);
    Optional<NotificationTemplate> findByName(String name);
    void save(NotificationTemplate template);
}
```

---

### When to Use / When NOT to Use

**Use when:**
- You want to decouple business logic from data access
- You anticipate switching storage technologies (HashMap -> Redis -> Postgres)
- You want to unit-test business logic without a real database
- Multiple parts of the codebase access the same data

**Do NOT use when:**
- The application is trivially simple (a single script)
- You are 100% certain the storage will never change (rare)
- You are using an ORM that already provides a repository abstraction (e.g., Spring Data JPA)

### Interview One-Liner

> "Repository Pattern abstracts data access behind an interface, allowing the business logic to be completely decoupled from the storage mechanism. Switching from an in-memory store to Redis or PostgreSQL requires changing only the implementation class and one line of wiring."

### Projects Using This Pattern

| Project | Interface | Implementation | What It Stores |
|---------|-----------|---------------|----------------|
| URL Shortener | `UrlRepository` | `InMemoryUrlRepository` | Shortened URL entities |
| Rate Limiter | `RuleRepository` | `InMemoryRuleRepository` | Rate limit rules per key |
| Notification System | `NotificationRepository` | `InMemoryNotificationRepository` | Notification entities + status |
| Notification System | `PreferenceRepository` | `InMemoryPreferenceRepository` | Per-user notification preferences |
| Notification System | `TemplateRepository` | `InMemoryTemplateRepository` | Message templates with placeholders |

---

## 4. Factory Pattern / Factory Method

**One-line definition:** Centralize object creation in one place so the rest of the codebase never says `new ConcreteClass()` -- they only know interfaces.

---

### The Problem: Scattered Object Creation

```java
// === THE UGLY VERSION: Every class creates its own dependencies ===
// The service creates its own repository and strategy. Problems:
//   1. The service is tightly coupled to InMemoryUrlRepository and Base62EncodingStrategy
//   2. To switch to Redis, you must EDIT the service class
//   3. To test with a mock repository, you must EDIT the service class
//   4. If 3 services all create InMemoryUrlRepository, you get 3 separate stores!
public class UrlShortenerService {
    public UrlShortenerService() {
        this.repository = new InMemoryUrlRepository(); // Hardcoded!
        this.strategy = new Base62EncodingStrategy();   // Hardcoded!
    }
}

public class AnalyticsService {
    public AnalyticsService() {
        this.repository = new InMemoryUrlRepository(); // A DIFFERENT store! Bug!
    }
}
```

---

### The Solution: AppConfig as the Factory

```java
// File: 01-url-shortener/.../config/AppConfig.java
//
// === AppConfig IS OUR FACTORY — THE ONLY CLASS THAT KNOWS CONCRETE TYPES ===
//
// In Spring Boot, the @Configuration class with @Bean methods does this
// automatically. We are doing it manually to understand what Spring does
// under the hood.
//
// THE RULE: Only AppConfig says "new ConcreteClass()".
// Services only know interfaces.
//
// WHY this matters:
//   1. Want to switch from InMemory to Redis? Change ONE line in AppConfig.
//   2. Want to switch from Base62 to MD5? Change ONE line in AppConfig.
//   3. Services never need to be modified. They depend only on interfaces.
//   4. The "dependency graph" is visible in one place — you can see how
//      everything is wired just by reading AppConfig.
public class AppConfig {

    public static final String BASE_URL = "https://short.url";

    /**
     * Factory method: creates a UrlShortenerService with default wiring.
     * This is the COMPOSITION ROOT — the single place where concrete types
     * are chosen and wired together.
     */
    public static UrlShortenerService createDefaultService() {
        // Step 1: Create the data layer (repository)
        // Only AppConfig knows it is InMemory. The service sees "UrlRepository".
        UrlRepository repository = new InMemoryUrlRepository();

        // Step 2: Create the algorithm (strategy)
        // Only AppConfig knows it is Base62. The service sees "EncodingStrategy".
        EncodingStrategy strategy = new Base62EncodingStrategy();

        // Step 3: Wire them into the service
        // The service receives INTERFACES, not concrete classes.
        // It is blissfully unaware of what is behind those interfaces.
        return new UrlShortenerService(repository, strategy, BASE_URL);
    }

    /**
     * Overloaded factory: allows the caller to choose the strategy.
     * Useful in tests or when the user selects the algorithm via config.
     */
    public static UrlShortenerService createServiceWithStrategy(EncodingStrategy strategy) {
        UrlRepository repository = new InMemoryUrlRepository();
        return new UrlShortenerService(repository, strategy, BASE_URL);
    }

    /**
     * Factory for the full controller + all its dependencies.
     * Demonstrates the "wiring chain": AppConfig -> Service -> Controller.
     */
    public static UrlShortenerController createController() {
        UrlShortenerService service = createDefaultService();
        AnalyticsService analyticsService = new AnalyticsService();
        return new UrlShortenerController(service, analyticsService);
    }
}
```

---

### Notification System: The Most Complex Factory

The Notification System's AppConfig is the most complex factory because it wires together 6 dependencies into a single service.

```java
// File: 03-notification-system/.../config/AppConfig.java
//
// === COMPLEX FACTORY: 6 Dependencies Wired Together ===
// This is the closest our code gets to what Spring's ApplicationContext does.
// It creates repositories, services, handlers, a queue, and a tracker,
// then wires them all into the NotificationService.
//
// The order matters: some dependencies depend on others.
//   - PreferenceService needs PreferenceRepository
//   - TemplateService needs TemplateRepository + SimpleTemplateEngine
//   - NotificationService needs all of the above + handlers + queue + tracker
public class AppConfig {

    // Repositories are created once and shared (if needed by multiple services).
    // This is effectively what Spring's singleton scope does.
    private final NotificationRepository notificationRepository;
    private final PreferenceRepository preferenceRepository;
    private final TemplateRepository templateRepository;

    public AppConfig() {
        // Create all repositories once during construction.
        // If multiple services need the same repository, they share the instance.
        this.notificationRepository = new InMemoryNotificationRepository();
        this.preferenceRepository = new InMemoryPreferenceRepository();
        this.templateRepository = new InMemoryTemplateRepository();
    }

    public NotificationService createNotificationService() {
        // === LAYER 1: Channel handlers (Strategy pattern) ===
        // One handler per channel. The map enables runtime dispatch.
        Map<Channel, NotificationHandler> handlers = new EnumMap<>(Channel.class);
        handlers.put(Channel.PUSH,   new PushNotificationHandler());   // 90% success
        handlers.put(Channel.EMAIL,  new EmailNotificationHandler());  // 95% success
        handlers.put(Channel.SMS,    new SmsNotificationHandler());    // 85% success
        handlers.put(Channel.IN_APP, new InAppNotificationHandler());  // 100% success

        // === LAYER 2: Supporting services ===
        PreferenceService preferenceService = new PreferenceService(preferenceRepository);
        SimpleTemplateEngine engine = new SimpleTemplateEngine();
        TemplateService templateService = new TemplateService(templateRepository, engine);
        DeliveryTracker deliveryTracker = new DeliveryTracker();
        NotificationQueue queue = new InMemoryPriorityQueue();

        // === LAYER 3: The main service — receives ALL dependencies ===
        // In Spring, this would be:
        //   @Bean
        //   public NotificationService notificationService(
        //       NotificationRepository repo, PreferenceService prefs,
        //       TemplateService templates, DeliveryTracker tracker,
        //       NotificationQueue queue, Map<Channel, NotificationHandler> handlers) {
        //       return new NotificationService(repo, prefs, templates, tracker, queue, handlers);
        //   }
        return new NotificationService(
                notificationRepository,
                preferenceService,
                templateService,
                deliveryTracker,
                queue,
                handlers
        );
    }
}
```

---

### When to Use / When NOT to Use

**Use when:**
- Object creation involves choosing between multiple implementations
- Objects have complex dependency chains (A needs B, B needs C)
- You want to centralize the "wiring" so changes happen in one place
- You want the rest of the code to depend only on interfaces

**Do NOT use when:**
- The object has no dependencies and no alternatives (just use `new`)
- You are already using a DI framework that handles this (Spring, Guice)
- Over-engineering: do not create a factory for a simple value object

### Interview One-Liner

> "Factory Pattern centralizes object creation so that the rest of the codebase depends on interfaces, not concrete classes. Changing an implementation requires editing only the factory, not every class that uses it."

### Projects Using This Pattern

| Project | Factory Class | What It Creates | Complexity |
|---------|--------------|-----------------|------------|
| URL Shortener | `AppConfig` | `UrlShortenerService`, `UrlShortenerController` | Simple: 2-3 dependencies |
| Rate Limiter | `AppConfig` | `RateLimiterService`, `RateLimiterController` | Medium: strategy map + repository + default rule |
| Notification System | `AppConfig` | `NotificationService`, `NotificationController` | Complex: 6 dependencies including handler map, multiple repositories, queue |

---

## 5. Observer Pattern

**One-line definition:** When something happens (event), automatically notify all interested parties (observers) without the event source knowing who they are.

---

### The Problem: Tightly Coupled Event Handling

```java
// === THE UGLY VERSION: Handler directly calls every interested party ===
// If you add a new observer (e.g., metrics collector, audit logger),
// you must modify the handler class. Violates Open-Closed Principle.
public class EmailNotificationHandler {
    public DeliveryAttempt send(Notification notification) {
        // ... send the email ...

        // Now manually notify everyone who cares about the delivery result:
        deliveryTracker.record(attempt);       // Tracking
        metricsCollector.recordDelivery();      // Metrics
        auditLogger.log(attempt);              // Audit
        alertService.checkForAnomalies();       // Alerting
        // Added billing? Edit this file again.
        // Added analytics? Edit this file again.
        // This list grows forever.
    }
}
```

---

### The Solution: Observation via DeliveryTracker

In our Notification System, the Observer pattern is used in a simplified form. The `DeliveryTracker` observes delivery events by having the `NotificationService` call `deliveryTracker.record()` after each delivery attempt.

```java
// File: 03-notification-system/.../service/DeliveryTracker.java
//
// === THE OBSERVER: DeliveryTracker ===
// This class is "observing" delivery events. Every time a notification
// is sent (successfully or not), the service calls record() to log
// the attempt.
//
// In our simplified implementation, the observation is explicit:
// the service calls deliveryTracker.record(attempt) directly.
// This is the "lightweight observer" approach — simple and clear.
public class DeliveryTracker {

    // Stores all delivery attempts, keyed by notification ID.
    // A single notification can have MULTIPLE attempts (retries).
    private final Map<String, List<DeliveryAttempt>> attempts = new ConcurrentHashMap<>();

    /**
     * Record a delivery attempt. This is the "notification" method —
     * it is called whenever a delivery event occurs.
     *
     * In a proper Observer pattern, this would be the update() method
     * of the Observer interface.
     */
    public void record(DeliveryAttempt attempt) {
        attempts.computeIfAbsent(attempt.getNotificationId(), k -> new ArrayList<>())
                .add(attempt);
    }

    /** Get all attempts for a specific notification (for debugging/auditing). */
    public List<DeliveryAttempt> getAttempts(String notificationId) {
        return attempts.getOrDefault(notificationId, List.of());
    }

    /** Print aggregate delivery statistics. */
    public void printStats() {
        long totalAttempts = attempts.values().stream().mapToLong(List::size).sum();
        long sent = attempts.values().stream().flatMap(List::stream)
                .filter(a -> a.getStatus() == NotificationStatus.SENT).count();
        long failed = attempts.values().stream().flatMap(List::stream)
                .filter(a -> a.getStatus() == NotificationStatus.FAILED).count();

        System.out.println("--- Delivery Statistics ---");
        System.out.printf("  Total attempts: %d, Sent: %d, Failed: %d%n",
                totalAttempts, sent, failed);
    }
}
```

```java
// File: 03-notification-system/.../service/NotificationService.java
//
// === HOW THE OBSERVER GETS NOTIFIED ===
// In processQueue(), after each delivery attempt, the service
// calls deliveryTracker.record(attempt). This is the "notification."
public int processQueue(int maxBatch) {
    for (int i = 0; i < maxBatch; i++) {
        Notification notification = queue.dequeue();
        if (notification == null) break;

        NotificationHandler handler = handlers.get(notification.getChannel());

        // Attempt delivery via the channel handler (Strategy pattern)
        DeliveryAttempt attempt = handler.send(notification);

        // === OBSERVER NOTIFICATION ===
        // The tracker is "observing" delivery events.
        // Every attempt (success or failure) is recorded.
        deliveryTracker.record(attempt);

        // Handle success/failure...
    }
}
```

---

### Observer in the Chat System (04-chat-system): Presence, Delivery Receipts, and Group Events

The Chat System uses the Observer pattern in three distinct areas, making it the richest example of event-driven notifications in this repository.

#### 1. Presence Updates (Online/Offline)

When a user goes online or offline, all connected users who have an active conversation with them are notified. The `PresenceService` tracks user status, and `ConnectionHandler` pushes updates to WebSocket connections.

```java
// === OBSERVER: Presence changes propagate to connected users ===
// When alice goes online:
//   1. PresenceService.setOnline("alice") updates her status
//   2. PresenceService notifies all "interested" users (those with active conversations)
//   3. Each interested user's ConnectionHandler pushes a presence update via WebSocket
//
// The key insight: alice does NOT know who is observing her status.
// She just changes state. The system handles fan-out automatically.
//
// In production: Presence updates are published to a Redis Pub/Sub channel
// or Kafka topic. Each chat server subscribes and pushes to its local connections.
```

#### 2. Message Delivery and Read Receipts

When a message is delivered to the recipient or marked as read, the sender receives a receipt notification. This is a classic Observer scenario: the sender is "observing" the delivery status of their messages.

```java
// === OBSERVER: Delivery/read receipts flow back to the sender ===
// When bob reads alice's message:
//   1. Bob's client sends a "read" acknowledgment
//   2. MessageService updates message status to READ
//   3. The system notifies alice that her message was read (the "double blue check")
//
// The sender (alice) registered implicit interest when she sent the message.
// She does not poll for status — the system PUSHES the receipt to her.
```

#### 3. Group Events (Join/Leave System Messages)

When a member joins or leaves a group, all group members see a system message. The group acts as the "subject" and members are the "observers."

```java
// === OBSERVER: Group membership changes notify all members ===
// When carol joins #engineering:
//   1. GroupService.addMember("engineering", "carol")
//   2. A system message "carol joined the group" is created
//   3. MessageRouter delivers this system message to ALL group members
//
// Group members do not check for membership changes — the system pushes
// notifications to them automatically. This is Observer in action.
```

### Projects Using Observer Pattern

| Project | Observable Event | Observer | Mechanism |
|---------|-----------------|----------|-----------|
| Notification System | Delivery attempt (success/failure) | `DeliveryTracker` | `deliveryTracker.record(attempt)` after each `handler.send()` |
| Chat System | User goes online/offline | Connected users with active conversations | `PresenceService` pushes status via `ConnectionHandler` WebSocket |
| Chat System | Message delivered/read | Original sender | Delivery/read receipt pushed back via `MessageRouter` |
| Chat System | Member joins/leaves group | All group members | System message broadcast via `MessageRouter` |

---

#### What a Full Observer Pattern Would Look Like

```java
// === HOW THIS WOULD LOOK WITH A PROPER OBSERVER INTERFACE ===
// In a real production system with multiple observers:

// Step 1: Define the observer interface
public interface DeliveryObserver {
    void onDeliveryAttempt(DeliveryAttempt attempt);
}

// Step 2: Multiple observers implement it
public class DeliveryTracker implements DeliveryObserver {
    public void onDeliveryAttempt(DeliveryAttempt attempt) {
        // Track the attempt for statistics
    }
}

public class MetricsCollector implements DeliveryObserver {
    public void onDeliveryAttempt(DeliveryAttempt attempt) {
        // Increment Prometheus/CloudWatch counters
    }
}

public class AuditLogger implements DeliveryObserver {
    public void onDeliveryAttempt(DeliveryAttempt attempt) {
        // Write to audit log for compliance
    }
}

// Step 3: The handler manages a list of observers
public class NotificationService {
    private final List<DeliveryObserver> observers = new ArrayList<>();

    public void addObserver(DeliveryObserver observer) {
        observers.add(observer);
    }

    public int processQueue(int maxBatch) {
        // ... dequeue, send ...
        DeliveryAttempt attempt = handler.send(notification);

        // Notify ALL observers automatically
        for (DeliveryObserver observer : observers) {
            observer.onDeliveryAttempt(attempt);
        }
    }
}

// Step 4: In a real system, this becomes Kafka events:
// handler.send(notification);
// kafkaProducer.send("delivery-events", attempt);  // Published to Kafka topic
// Multiple consumer groups subscribe independently:
//   - delivery-tracker consumer
//   - metrics consumer
//   - audit consumer
//   - alerting consumer
```

---

### When to Use / When NOT to Use

**Use when:**
- Multiple components need to react to the same event
- You want to add new observers without modifying the event source
- In distributed systems: Kafka topics with multiple consumer groups

**Do NOT use when:**
- There is only one observer (direct call is simpler)
- The event source needs a response from the observer (use request-response instead)
- Notification ordering matters (observers are notified in arbitrary order)

### Interview One-Liner

> "Observer Pattern defines a one-to-many dependency: when an event occurs, all registered observers are notified automatically. In distributed systems, this is implemented via message queues like Kafka."

---

## 6. Producer-Consumer Pattern

**One-line definition:** Decouple the component that creates work (producer) from the component that processes work (consumer) using a queue between them.

---

### The Problem: Synchronous Processing

```java
// === THE UGLY VERSION: Send and wait ===
// If sending an email takes 3 seconds, the API response takes 3+ seconds.
// If the email server is down, the entire API request fails.
// If 1000 users need notifications, the API blocks for 3000+ seconds.
public String send(NotificationRequest request) {
    for (String userId : request.getUserIds()) {
        // BLOCKING: wait for each delivery before processing the next one
        handler.send(notification); // Takes 1-3 seconds per notification!
    }
    return "done"; // Response finally comes back after ALL notifications are sent
}
```

---

### The Solution: Queue-Based Decoupling

In our Notification System, the `NotificationService.send()` method acts as the **producer** (enqueues notifications) and `processQueue()` acts as the **consumer** (dequeues and delivers).

```java
// File: 03-notification-system/.../queue/NotificationQueue.java
//
// === THE QUEUE INTERFACE ===
// Abstraction over the queue itself. Could be:
//   - PriorityBlockingQueue (our in-memory implementation)
//   - Redis list (LPUSH/RPOP)
//   - Kafka topic
//   - AWS SQS
public interface NotificationQueue {
    void enqueue(Notification notification);
    Notification dequeue();  // Returns null if empty
    int size();
    boolean isEmpty();
}
```

```java
// File: 03-notification-system/.../queue/InMemoryPriorityQueue.java
//
// === THE IN-MEMORY IMPLEMENTATION ===
// Uses Java's PriorityBlockingQueue, which provides:
//   1. THREAD-SAFETY: multiple threads can enqueue/dequeue concurrently
//   2. PRIORITY ORDERING: CRITICAL notifications are dequeued before LOW ones
//   3. NON-BLOCKING: poll() returns null immediately if the queue is empty
//      (vs take() which blocks until an item is available)
//
// In production, this would be Kafka or SQS. The interface stays the same.
// Kafka would add: persistence, consumer groups, partitioning, replay.
public class InMemoryPriorityQueue implements NotificationQueue {

    // PriorityBlockingQueue uses a binary heap internally.
    // Notifications are ordered by their compareTo() method,
    // which compares Priority values (CRITICAL=0 < HIGH=1 < MEDIUM=2 < LOW=3).
    private final PriorityBlockingQueue<Notification> queue = new PriorityBlockingQueue<>();

    @Override
    public void enqueue(Notification notification) {
        // offer() adds the notification to the queue in priority order.
        // Thread-safe: multiple producers can call this concurrently.
        queue.offer(notification);
    }

    @Override
    public Notification dequeue() {
        // poll() removes and returns the highest-priority notification.
        // Returns null if the queue is empty (non-blocking).
        // In a real consumer loop, you might use take() to block-wait.
        return queue.poll();
    }

    @Override
    public int size() { return queue.size(); }

    @Override
    public boolean isEmpty() { return queue.isEmpty(); }
}
```

```java
// File: 03-notification-system/.../service/NotificationService.java
//
// === THE PRODUCER: send() enqueues notifications ===
// The API call returns IMMEDIATELY after enqueuing. It does not wait
// for delivery. The user gets a notification ID back in milliseconds.
public String send(NotificationRequest request) {
    List<String> createdIds = new ArrayList<>();

    for (String userId : request.getUserIds()) {
        // 1. Check user preferences (is the user opted in?)
        if (!preferenceService.canSend(userId, request.getChannel(), LocalDateTime.now())) {
            continue; // Skip this user
        }

        // 2. Render the template (replace {{name}} with actual values)
        String[] rendered = templateService.renderTemplate(
                request.getTemplateId(), request.getData());

        // 3. Build the notification object (Builder pattern)
        Notification notification = new Notification.Builder()
                .userId(userId)
                .channel(request.getChannel())
                .priority(request.getPriority())
                .subject(rendered[0])
                .body(rendered[1])
                .build();

        // 4. Persist to repository (for querying later)
        repository.save(notification);

        // 5. === PRODUCER: ENQUEUE ===
        // This is the key moment: the notification goes INTO the queue.
        // The send() method returns immediately after this line.
        // Actual delivery happens LATER when processQueue() runs.
        notification.setStatus(NotificationStatus.QUEUED);
        queue.enqueue(notification);

        createdIds.add(notification.getId());
    }
    // Returns instantly — notifications are queued, not delivered yet
    return createdIds.isEmpty() ? "none:all_skipped" : createdIds.getFirst();
}

// === THE CONSUMER: processQueue() dequeues and delivers ===
// This runs on a separate thread (or is called periodically).
// It pulls notifications from the queue and sends them one by one.
public int processQueue(int maxBatch) {
    int processed = 0;

    for (int i = 0; i < maxBatch; i++) {
        // 1. === CONSUMER: DEQUEUE ===
        // Pull the highest-priority notification from the queue.
        // Returns null if the queue is empty.
        Notification notification = queue.dequeue();
        if (notification == null) break;

        // 2. Look up the handler for this channel (Strategy pattern)
        NotificationHandler handler = handlers.get(notification.getChannel());

        // 3. Attempt delivery
        notification.setStatus(NotificationStatus.SENDING);
        DeliveryAttempt attempt = handler.send(notification);
        deliveryTracker.record(attempt); // Observer pattern

        // 4. Handle result
        if (attempt.getStatus() == NotificationStatus.SENT) {
            notification.markAsSent();
            notification.markAsDelivered();
            repository.save(notification);
        } else {
            notification.markAsFailed();
            notification.incrementRetry();
            repository.save(notification);

            // 5. Re-enqueue for retry if retries remain
            if (notification.isRetryable()) {
                queue.enqueue(notification); // Back to the queue for another attempt
            }
        }

        processed++;
    }
    return processed;
}
```

---

### The Full Producer-Consumer Flow

```
API Request: "Send order confirmation to alice, bob, carol"
    │
    ├── [PRODUCER] send() method runs:
    │   ├── Check alice's preferences → OK
    │   │   └── Build notification → enqueue (priority: HIGH)
    │   ├── Check bob's preferences → SMS disabled → SKIP
    │   └── Check carol's preferences → quiet hours → SKIP
    │   └── Return "notification-id-abc" immediately (< 5ms)
    │
    │   ... time passes ... (could be milliseconds or seconds)
    │
    └── [CONSUMER] processQueue(10) runs (on a worker thread):
        ├── dequeue() → alice's notification (highest priority)
        │   ├── handlers.get(EMAIL) → EmailNotificationHandler
        │   ├── handler.send(notification) → DeliveryAttempt(SENT)
        │   ├── deliveryTracker.record(attempt) → logged
        │   └── notification.markAsDelivered()
        │
        ├── dequeue() → null (queue is empty)
        └── Return processed=1
```

---

### Production Equivalent

```
In production, replace InMemoryPriorityQueue with Kafka:

PRODUCER (API server):
    kafkaProducer.send("notifications", notification);
    // Returns immediately. Kafka persists the message.

CONSUMER (separate worker service, possibly multiple instances):
    @KafkaListener(topics = "notifications", groupId = "delivery-workers")
    public void onNotification(Notification notification) {
        handler.send(notification);
    }
    // Kafka handles: partitioning, consumer groups, offset tracking,
    // at-least-once delivery, replay, dead-letter queues.
```

---

### When to Use / When NOT to Use

**Use when:**
- You need to decouple request handling from slow operations (email, SMS, file processing)
- You need to handle bursty traffic (queue absorbs spikes)
- You need priority ordering (critical notifications before low-priority)
- You need retry logic (failed items go back to the queue)

**Do NOT use when:**
- The operation is fast enough to do synchronously (< 100ms)
- The caller needs the result immediately (queue adds latency)
- Message ordering is critical and hard to maintain with multiple consumers

### Interview One-Liner

> "Producer-Consumer Pattern decouples work creation from work processing using a queue. The producer enqueues quickly and returns; the consumer processes at its own pace. In production, Kafka or SQS replaces the in-memory queue."

### Projects Using This Pattern

| Project | Producer | Consumer | Queue |
|---------|----------|----------|-------|
| URL Shortener | -- | -- | -- |
| Rate Limiter | -- | -- | -- |
| Notification System | `NotificationService.send()` | `NotificationService.processQueue()` | `InMemoryPriorityQueue` (backed by `PriorityBlockingQueue`) |

---

## 7. Chain of Responsibility

**One-line definition:** Pass a request along a chain of handlers, where each handler can either process the request, reject it, or pass it to the next handler.

---

### The Concept: Rate Limiter as Middleware

The Rate Limiter fits naturally into a Chain of Responsibility pattern when deployed as middleware in an API gateway or web server. Each filter in the chain makes a go/no-go decision.

```java
// === CONCEPTUAL: How the Rate Limiter fits in a real middleware chain ===
//
// In a production API, every incoming HTTP request passes through a pipeline
// of "filters" or "middleware." Each filter can:
//   1. PASS the request to the next filter (request continues)
//   2. REJECT the request (return error response, stop the chain)
//   3. MODIFY the request (add headers, transform data) and pass it on
//
// The Rate Limiter is one link in this chain:
//
//   HTTP Request
//       │
//       ▼
//   ┌──────────────────┐
//   │  AuthFilter       │  → Validates JWT token. Invalid? Return 401.
//   └────────┬─────────┘
//            │ (passed)
//            ▼
//   ┌──────────────────┐
//   │  RateLimiterFilter│  → Checks rate limit. Exceeded? Return 429.
//   └────────┬─────────┘    Adds X-RateLimit-* headers to response.
//            │ (passed)
//            ▼
//   ┌──────────────────┐
//   │  ValidationFilter │  → Validates request body. Invalid? Return 400.
//   └────────┬─────────┘
//            │ (passed)
//            ▼
//   ┌──────────────────┐
//   │  BusinessLogic    │  → Processes the request. Returns 200.
//   └──────────────────┘
//
// Each filter is independent and composable. You can:
//   - Add filters without modifying existing ones
//   - Reorder filters (auth before rate limit, or vice versa)
//   - Remove filters (disable rate limiting in dev)
//   - Test each filter in isolation
```

```java
// === HOW THIS WOULD LOOK IN CODE ===
//
// Step 1: Define the handler interface
public interface RequestFilter {
    /**
     * Process the request. Either:
     *   - Return a response (rejecting the request)
     *   - Call next.handle(request) to pass to the next filter
     */
    HttpResponse handle(HttpRequest request, RequestFilter next);
}

// Step 2: The Rate Limiter filter
public class RateLimiterFilter implements RequestFilter {
    private final RateLimiterService rateLimiterService;

    @Override
    public HttpResponse handle(HttpRequest request, RequestFilter next) {
        // Build context from the HTTP request
        RequestContext context = new RequestContext(
            request.getHeader("X-API-Key"),
            request.getRemoteAddr(),
            request.getPath()
        );

        // Check rate limit
        RateLimitResult result = rateLimiterService.checkRateLimit(context);

        if (!result.isAllowed()) {
            // REJECT: Return 429 Too Many Requests
            // Chain stops here — business logic never executes
            return HttpResponse.status(429)
                    .headers(result.getHeaders())
                    .body("Rate limit exceeded. Retry after " + result.getRetryAfterMs() + "ms");
        }

        // PASS: Add rate limit headers and continue to next filter
        HttpResponse response = next.handle(request, next);
        result.getHeaders().forEach(response::addHeader);
        return response;
    }
}

// Step 3: Our RateLimiterController is a simplified version of this:
// File: 02-rate-limiter/.../controller/RateLimiterController.java
public class RateLimiterController {
    private final RateLimiterService rateLimiterService;

    public RateLimitResult handleRequest(RequestContext context) {
        // In production: this would be a filter that either passes
        // or rejects the request before it reaches business logic.
        RateLimitResult result = rateLimiterService.checkRateLimit(context);

        if (result.isAllowed()) {
            // [200 OK] Request would proceed to business logic
        } else {
            // [429 TOO MANY REQUESTS] Request is rejected
        }

        return result;
    }
}
```

---

### When to Use / When NOT to Use

**Use when:**
- A request must pass through multiple processing steps (validation, auth, rate limiting)
- Each step can independently accept or reject the request
- You want to add/remove/reorder steps without modifying others

**Do NOT use when:**
- There is only one processing step (just call it directly)
- All steps must always execute (use a pipeline, not a chain)

### Interview One-Liner

> "Chain of Responsibility passes a request along a chain of handlers where each handler can process it, reject it, or pass it to the next handler. In web applications, this is the middleware pipeline: Auth -> Rate Limit -> Validation -> Business Logic."

### Projects Using This Pattern

| Project | Role in Chain | Behavior |
|---------|--------------|----------|
| URL Shortener | -- | -- |
| Rate Limiter | Middleware filter | Allows request (200) or rejects with 429 + Retry-After header |
| Notification System | -- | -- |

---

## 8. Singleton Pattern (Conceptual)

**One-line definition:** Ensure a class has only one instance and provide a global point of access to it.

---

### The Concept: Why Single Instances Matter

In the URL Shortener, the `AtomicLong counter` acts as a singleton-like unique ID generator. It must be a single instance to avoid generating duplicate short codes.

```java
// File: 01-url-shortener/.../service/UrlShortenerService.java
//
// === THE COUNTER: A SINGLETON-LIKE ID GENERATOR ===
// AtomicLong provides thread-safe incrementing without locking.
// Starting at 100,000 produces realistic-looking short codes
// (single-digit counters produce very short base-62 strings).
private final AtomicLong counter = new AtomicLong(100_000);
```

#### Why Single Instance Matters

```java
// === THE PROBLEM: Multiple counters generate DUPLICATE codes ===
//
// If each server instance had its OWN counter starting at 0:
//
//   Server A: counter = 0 → encode(0) → "0000000"
//   Server B: counter = 0 → encode(0) → "0000000"  ← DUPLICATE!
//
//   Server A: counter = 1 → encode(1) → "0000001"
//   Server B: counter = 1 → encode(1) → "0000001"  ← DUPLICATE!
//
// Both servers generate the SAME short codes for different URLs.
// Users clicking "0000000" get redirected to the wrong page.
//
// === THE SOLUTION IN PRODUCTION: Distributed ID Generation ===
//
// Option 1: ZooKeeper assigns COUNTER RANGES to each server
//   Server A: range [0, 999,999]         → counter starts at 0
//   Server B: range [1,000,000, 1,999,999] → counter starts at 1,000,000
//   Server C: range [2,000,000, 2,999,999] → counter starts at 2,000,000
//   No overlap = no duplicates. When a range is exhausted, request a new one.
//
// Option 2: Twitter's Snowflake ID format
//   64-bit ID = [timestamp (41 bits)] + [machine ID (10 bits)] + [sequence (12 bits)]
//   Each machine generates unique IDs independently. No coordination needed.
//
// Option 3: UUID (128-bit random)
//   Statistically unique. No coordination. But 128 bits is too long for a short URL.
//
// In our demo, a single AtomicLong works because we have one JVM.
// In production, you would use one of the distributed approaches above.
```

---

### When to Use / When NOT to Use

**Use when:**
- Exactly one instance is needed (database connection pool, configuration, ID generator)
- The instance must be shared across the application (otherwise each copy diverges)

**Do NOT use when:**
- The object is stateless (just use a static method)
- Multiple instances are fine (no shared state concerns)
- You are using a DI framework (let the framework manage singleton scope)
- **Common anti-pattern:** Using Singleton as a global variable. If you find yourself passing a singleton everywhere, it is probably a dependency that should be injected instead.

### Interview One-Liner

> "Singleton ensures exactly one instance of a class exists. In URL shorteners, the ID counter must be a singleton to avoid generating duplicate short codes. In production, distributed ID generators like Snowflake or ZooKeeper-assigned ranges solve this across multiple servers."

### Projects Using This Pattern

| Project | Singleton-Like Component | Why Single Instance Matters |
|---------|-------------------------|---------------------------|
| URL Shortener | `AtomicLong counter` | Multiple counters would generate duplicate short codes |
| Rate Limiter | -- | -- |
| Notification System | -- | -- |

---

## 9. Template Method Pattern

**One-line definition:** Define the skeleton of an algorithm in a base class or orchestrator, letting subclasses or strategies override specific steps while keeping the overall flow fixed.

---

### The Concept: Fixed Flow with Variable Steps

In the Notification System, every notification follows the SAME high-level processing flow. Most steps are common (validate, check preferences, render template, enqueue, track). Only one step varies: the actual delivery mechanism (which is handled by the Strategy pattern).

```java
// === THE FIXED FLOW THAT EVERY NOTIFICATION FOLLOWS ===
//
// File: 03-notification-system/.../service/NotificationService.java
//
// This is a "Template Method" in spirit: the overall algorithm is fixed,
// but one step (delivery via channel handler) is variable.
//
// Step 1: Validate request           ← COMMON for all channels
//         (userId not null, channel not null)
//
// Step 2: Check user preferences     ← COMMON for all channels
//         (is channel enabled? quiet hours?)
//         PreferenceService.canSend(userId, channel, now)
//
// Step 3: Render template            ← COMMON for all channels
//         (replace {{name}} with "Alice", {{orderId}} with "12345")
//         TemplateService.renderTemplate(templateId, data)
//
// Step 4: Build Notification object  ← COMMON for all channels
//         (Builder pattern creates the immutable notification)
//
// Step 5: Persist to repository      ← COMMON for all channels
//         (save for querying, auditing, retry tracking)
//
// Step 6: Enqueue                    ← COMMON for all channels
//         (put in PriorityBlockingQueue)
//
// ----- Queue boundary -----
//
// Step 7: Dequeue                    ← COMMON for all channels
//         (pull from PriorityBlockingQueue)
//
// Step 8: DELIVER via handler        ← *** DIFFERENT PER CHANNEL ***
//         This is where Strategy pattern kicks in:
//           PUSH:  → FCM/APNs (PushNotificationHandler)
//           EMAIL: → AWS SES  (EmailNotificationHandler)
//           SMS:   → Twilio   (SmsNotificationHandler)
//           IN_APP:→ Database  (InAppNotificationHandler)
//
// Step 9: Track delivery attempt     ← COMMON for all channels
//         (DeliveryTracker.record() — Observer pattern)
//
// Step 10: Handle result             ← COMMON for all channels
//          (mark as delivered, or mark as failed and re-enqueue for retry)
```

```java
// === THE CODE THAT IMPLEMENTS THIS TEMPLATE ===
// The send() method handles steps 1-6 (the producer side).
// The processQueue() method handles steps 7-10 (the consumer side).
// Step 8 is the ONLY step that varies — and it is handled by
// the Strategy pattern (Map<Channel, NotificationHandler>).

// PRODUCER SIDE (steps 1-6):
public String send(NotificationRequest request) {
    for (String userId : request.getUserIds()) {
        // Step 2: Check preferences (COMMON)
        if (!preferenceService.canSend(userId, request.getChannel(), LocalDateTime.now())) {
            continue;
        }
        // Step 3: Render template (COMMON)
        String[] rendered = templateService.renderTemplate(request.getTemplateId(), request.getData());

        // Step 4: Build notification (COMMON, using Builder pattern)
        Notification notification = new Notification.Builder()
                .userId(userId)
                .channel(request.getChannel())
                .priority(request.getPriority())
                .subject(rendered[0])
                .body(rendered[1])
                .build();

        // Step 5: Persist (COMMON)
        repository.save(notification);

        // Step 6: Enqueue (COMMON)
        queue.enqueue(notification);
    }
}

// CONSUMER SIDE (steps 7-10):
public int processQueue(int maxBatch) {
    for (int i = 0; i < maxBatch; i++) {
        // Step 7: Dequeue (COMMON)
        Notification notification = queue.dequeue();
        if (notification == null) break;

        // Step 8: DELIVER — the VARIABLE step (Strategy pattern)
        NotificationHandler handler = handlers.get(notification.getChannel());
        DeliveryAttempt attempt = handler.send(notification);

        // Step 9: Track (COMMON — Observer pattern)
        deliveryTracker.record(attempt);

        // Step 10: Handle result (COMMON)
        if (attempt.getStatus() == NotificationStatus.SENT) {
            notification.markAsDelivered();
        } else {
            notification.markAsFailed();
            if (notification.isRetryable()) {
                queue.enqueue(notification); // Re-enqueue for retry
            }
        }
        repository.save(notification);
    }
}
```

#### What a Classic Template Method Would Look Like

```java
// === CLASSIC TEMPLATE METHOD (for reference) ===
// In a classic implementation, you would use an abstract class:
public abstract class NotificationProcessor {

    // The "template method" — defines the fixed skeleton.
    // Marked final so subclasses cannot override the overall flow.
    public final void process(NotificationRequest request) {
        validate(request);                    // Step 1: common
        checkPreferences(request);            // Step 2: common
        String content = renderTemplate(request); // Step 3: common
        Notification n = buildNotification(request, content); // Step 4: common
        deliver(n);                           // Step 5: *** ABSTRACT — varies per channel ***
        trackDelivery(n);                     // Step 6: common
    }

    // Concrete steps (common to all channels)
    private void validate(NotificationRequest request) { /* ... */ }
    private void checkPreferences(NotificationRequest request) { /* ... */ }

    // Abstract step — subclasses provide the implementation
    protected abstract void deliver(Notification notification);
}

// Subclasses override only the variable step:
public class EmailProcessor extends NotificationProcessor {
    protected void deliver(Notification notification) {
        // Send via AWS SES
    }
}

// In our code, we use Strategy + composition instead of inheritance,
// which is more flexible (GoF advice: "prefer composition over inheritance").
```

---

### When to Use / When NOT to Use

**Use when:**
- Multiple algorithms share the same overall flow but differ in specific steps
- You want to enforce a fixed sequence of operations
- The "variable" steps are well-defined and limited in number

**Do NOT use when:**
- The entire algorithm varies (use Strategy instead)
- The flow is not fixed (steps vary in order or presence)
- Inheritance creates more problems than it solves (use composition)

### Interview One-Liner

> "Template Method defines the skeleton of an algorithm in a method, deferring specific steps to subclasses. In our code, we achieve the same result using Strategy + composition: the notification processing flow is fixed, but the delivery step varies per channel."

### Projects Using This Pattern

| Project | Fixed Flow | Variable Step |
|---------|-----------|---------------|
| URL Shortener | -- | -- |
| Rate Limiter | -- | -- |
| Notification System | validate -> preferences -> template -> enqueue -> deliver -> track | `deliver()` via `NotificationHandler` (Strategy) |

---

## 10. Mediator Pattern

**One-line definition:** Define an object that encapsulates how a set of objects interact. Promotes loose coupling by keeping objects from referring to each other explicitly.

**GoF Category:** Behavioral

---

### The Problem: N x N Spaghetti Dependencies

Without a Mediator, every service that needs to collaborate with other services calls them directly. In the Chat System, you have `MessageService`, `GroupService`, `PresenceService`, and `MessageRouter` -- all of which need to coordinate when a message is sent.

```
// === THE UGLY VERSION: Every service calls every other service ===
// N services with direct dependencies = N x (N-1) coupling paths.
// Adding a new service means modifying ALL existing services.

    ┌────────────────┐         ┌────────────────┐
    │ MessageService │ ◄─────► │  GroupService   │
    └───┬──────┬─────┘         └──┬──────┬──────┘
        │      │                  │      │
        │      └──────────────────┘      │
        │                                │
        ▼                                ▼
    ┌────────────────┐         ┌────────────────┐
    │PresenceService │ ◄─────► │ MessageRouter  │
    └────────────────┘         └────────────────┘

    4 services = 6 direct coupling paths (4 x 3 / 2)
    Add a 5th service? 10 coupling paths.
    Add a 6th? 15. It grows as N*(N-1)/2.

    Problems:
    1. MessageService imports and calls PresenceService directly
    2. GroupService imports and calls MessageRouter directly
    3. Adding AnalyticsService means editing 4 existing services
    4. Testing MessageService requires mocking PresenceService,
       GroupService, AND MessageRouter
    5. Circular dependencies are inevitable
```

---

### The Solution: ChatService as the Central Hub (Mediator)

The Mediator pattern introduces a single coordinator -- `ChatService` -- that all subsystems talk to. Subsystems do NOT call each other directly. They only know about `ChatService`, and `ChatService` knows about all of them.

```
// === THE CLEAN VERSION: ChatService is the Mediator hub ===
// Every service talks ONLY to ChatService. N services = N coupling paths.

                    ┌──────────────┐
          ┌────────►│  ChatService │◄────────┐
          │         │  (MEDIATOR)  │         │
          │         └──┬────────┬──┘         │
          │            │        │             │
          ▼            ▼        ▼             ▼
    ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
    │ Message  │ │  Group   │ │ Presence │ │ Message  │
    │ Service  │ │ Service  │ │ Service  │ │  Router  │
    └──────────┘ └──────────┘ └──────────┘ └──────────┘

    4 services = 4 coupling paths (through the Mediator)
    Add a 5th service? 5 coupling paths. Linear growth.

    Benefits:
    1. Services do NOT import each other
    2. ChatService orchestrates the workflow across services
    3. Adding AnalyticsService means editing only ChatService
    4. Testing MessageService requires zero knowledge of other services
    5. No circular dependencies — everything points to ChatService
```

---

### Real Code Example: Chat System (04-chat-system)

```java
// File: 04-chat-system/.../service/ChatService.java
//
// === ChatService is the MEDIATOR — it knows all subsystems. ===
// Subsystems (MessageService, GroupService, etc.) do NOT know each other.
// When a user sends a message, ChatService orchestrates the entire flow
// by calling the right services in the right order.
//
// This is the Mediator pattern in action:
//   - The caller (e.g., WebSocketHandler) talks ONLY to ChatService
//   - ChatService delegates to MessageService, PresenceService, etc.
//   - None of the subsystems know about each other
public class ChatService {
    private final MessageService msgService;
    private final GroupService groupService;
    private final PresenceService presenceService;
    private final MessageRouter router;

    // sendDirectMessage orchestrates across multiple services:
    // (1) Find/create conversation (ConversationRepo)
    // (2) Send message (MessageService)
    // (3) Route to recipient (MessageRouter checks PresenceService)
    // Without Mediator, the caller would need to know all these steps.
}
```

```java
// === HOW THE MEDIATOR ORCHESTRATES A "SEND MESSAGE" FLOW ===
//
// Without Mediator (caller does everything):
//   Conversation conv = conversationRepo.findOrCreate(senderId, recipientId);
//   Message msg = messageService.createMessage(senderId, conv.getId(), content);
//   boolean online = presenceService.isOnline(recipientId);
//   if (online) {
//       messageRouter.routeToConnection(recipientId, msg);
//   } else {
//       messageRouter.storeForLaterDelivery(recipientId, msg);
//   }
//   // The caller needs to know ALL the services and the orchestration logic.
//   // Every caller that sends a message duplicates this logic.
//
// With Mediator (caller calls one method):
//   chatService.sendDirectMessage(senderId, recipientId, content);
//   // ChatService handles ALL the orchestration internally.
//   // The caller does not know about MessageService, PresenceService, etc.
```

#### How It Gets Wired: AppConfig Creates Everything

```java
// File: 04-chat-system/.../config/AppConfig.java
//
// === AppConfig is the COMPOSITION ROOT for the Chat System ===
// It creates all subsystems and passes them into ChatService.
// This is the Factory pattern working together with Mediator:
//   - Factory (AppConfig) creates all the pieces
//   - Mediator (ChatService) orchestrates them at runtime
//
// AppConfig creates:
//   1. Repositories (MessageRepository, ConversationRepository, UserRepository)
//   2. Services (MessageService, GroupService, PresenceService)
//   3. Infrastructure (MessageRouter, ConnectionHandler)
//   4. The Mediator (ChatService) — receives ALL of the above
```

---

### When to Use / When NOT to Use

**Use when:**
- Multiple objects need to communicate, and direct coupling would create a tangled web
- You want to centralize complex coordination logic in one place
- Adding new participants should not require modifying existing ones
- You want to make the interaction protocol explicit and visible in one class

**Do NOT use when:**
- There are only 2 objects communicating (direct call is simpler)
- The mediator becomes a "god object" doing too much (split into sub-mediators)
- The communication is truly one-to-one with no orchestration needed

### Interview One-Liner

> "Mediator Pattern defines a central coordinator that encapsulates how a set of objects interact, preventing N x N direct dependencies. In the Chat System, ChatService is the Mediator -- MessageService, GroupService, PresenceService, and MessageRouter never call each other directly. Everything is orchestrated through ChatService."

### Projects Using This Pattern

| Project | Mediator | Colleagues (Subsystems) | Orchestration Example |
|---------|----------|------------------------|----------------------|
| URL Shortener | -- | -- | -- |
| Rate Limiter | -- | -- | -- |
| Notification System | -- | -- | -- |
| Chat System | `ChatService` | `MessageService`, `GroupService`, `PresenceService`, `MessageRouter` | `sendDirectMessage()` coordinates conversation lookup, message creation, presence check, and routing |

---

## 11. Command Pattern

**One-line definition:** Encapsulate a request as an object, thereby letting you parameterize clients with different requests, queue requests, and log the sequence of requests.

**GoF Category:** Behavioral

---

### The Problem: Actions That Need to Travel

In a chat system, when a user sends a message, that action needs to travel through multiple stages: creation, validation, queueing, routing, delivery, and acknowledgment. If the "send message" action is just a method call, it cannot be queued, persisted, retried, or routed to a different server.

```java
// === THE UGLY VERSION: Direct method calls cannot travel ===
// The action (sending a message) is trapped in a method call.
// You cannot serialize it, queue it, retry it, or route it.
public void handleUserInput(String senderId, String recipientId, String text) {
    // This is an ephemeral action — once the method returns, it is gone.
    // If the recipient's server is down, the message is LOST.
    // If we want to retry, we have to capture all the parameters somewhere.
    // If we want to log what happened, we have to manually build a log entry.
    recipientConnection.send(text);
}
```

---

### The Solution: Message as a Command Object

In the Chat System, `Message` is a command object. It carries all the data needed for processing and flows through the entire pipeline as a self-contained unit.

```java
// === Message IS the command object ===
// It encapsulates EVERYTHING needed to process the "send message" action:
//   - Who sent it (senderId)
//   - Where it goes (conversationId, recipientId)
//   - What it contains (content, type, metadata)
//   - When it was created (timestamp)
//   - What state it is in (status: CREATED → SENT → DELIVERED → READ)
//
// Because it is an OBJECT (not a method call), it can be:
//   - Serialized to JSON and sent over WebSocket
//   - Queued in Kafka for async processing
//   - Persisted in a database for history
//   - Retried if delivery fails
//   - Routed to a different server based on recipient's location

// The command travels through the system pipeline:
//
//   User types message
//        │
//        ▼
//   ┌─────────────────────┐
//   │  MessageService      │  ← CREATES the command (Message object)
//   │  .send()             │     with Message.Builder (12+ fields)
//   └──────────┬──────────┘
//              │
//              ▼
//   ┌─────────────────────┐
//   │  Kafka (conceptual)  │  ← QUEUES the command for async processing
//   │  "messages" topic    │     Command is serialized and persisted
//   └──────────┬──────────┘
//              │
//              ▼
//   ┌─────────────────────┐
//   │  MessageRouter       │  ← ROUTES the command to the right server
//   │  .route()            │     Checks PresenceService for recipient location
//   └──────────┬──────────┘
//              │
//              ▼
//   ┌─────────────────────┐
//   │  ConnectionHandler   │  ← DELIVERS the command to the recipient
//   │  .deliverMessage()   │     Pushes over WebSocket connection
//   └──────────┬──────────┘
//              │
//              ▼
//   ┌─────────────────────┐
//   │  Acknowledgment      │  ← CONFIRMS the command was processed
//   │  (DELIVERED → READ)  │     Status update flows back to sender
//   └─────────────────────┘
```

```java
// === The Message command object is built with the Builder pattern ===
// 12+ fields make Builder essential. The command is self-contained:
// it carries ALL data needed for every stage of the pipeline.
//
// Message msg = Message.builder()
//         .id(UUID.randomUUID().toString())
//         .senderId("alice")
//         .conversationId("conv-123")
//         .type(MessageType.TEXT)
//         .content("Hey Bob, are you free for lunch?")
//         .timestamp(Instant.now())
//         .status(MessageStatus.CREATED)
//         .build();
//
// This object now flows through:
//   MessageService.send(msg)       → persists and enqueues
//   MessageRouter.route(msg)       → finds recipient, checks presence
//   ConnectionHandler.deliver(msg) → pushes to WebSocket
//
// At every stage, the command object carries its own context.
// No service needs to "look up" what to do — the Message tells them.
```

---

### When to Use / When NOT to Use

**Use when:**
- An action needs to be queued, logged, retried, or undone
- The action must travel across network boundaries (serializable)
- You want to decouple the "what" (the command) from the "how" (the handler)
- Multiple stages of processing need access to the same action data

**Do NOT use when:**
- The action is simple and synchronous (just call a method)
- There is no need for queueing, logging, or retry
- Over-engineering: not every method call needs to be wrapped in a command object

### Interview One-Liner

> "Message is a self-contained command that carries all data needed for processing. It flows through the pipeline without the sender needing to know how or where it gets delivered."

### Projects Using This Pattern

| Project | Command Object | Pipeline Stages | Key Benefit |
|---------|---------------|-----------------|-------------|
| URL Shortener | -- | -- | -- |
| Rate Limiter | -- | -- | -- |
| Notification System | `Notification` (partial — enqueued and processed) | send() → queue → processQueue() → deliver | Queueable and retryable |
| Chat System | `Message` (full command) | MessageService → Kafka (conceptual) → MessageRouter → ConnectionHandler → Acknowledgment | Serializable, queueable, routable, retryable, persistent |

---

## 12. Composite Pattern

**One-line definition:** Compose objects into tree structures. Let clients treat individual objects and compositions uniformly.

**GoF Category:** Structural

---

### The Problem: Two Fan-out Strategies That Need to Act as One

In a social media feed system, there are two fundamental approaches to distributing tweets to followers:

1. **Fan-out on write**: When a user posts a tweet, immediately push it into every follower's feed cache. Fast reads, but extremely expensive for celebrities with millions of followers.
2. **Fan-out on read**: Do nothing at write time. When a follower opens their feed, pull tweets from all the users they follow and merge them. Cheap writes, but slow reads.

Neither approach works well on its own. Normal users (hundreds of followers) should use fan-out on write for instant delivery. Celebrities (millions of followers) should use fan-out on read to avoid write amplification. You need a single strategy that combines both.

```java
// === THE UGLY VERSION: if-else inside the service ===
// The service has to know the internals of BOTH approaches.
// Adding a third approach (e.g., partial fan-out) means modifying this class.
public class FeedService {
    public void distributeTweet(Tweet tweet, User poster) {
        if (poster.isCelebrity()) {
            // Do nothing now — followers pull at read time
            // ... fan-out-on-read logic here ...
        } else {
            // Push to every follower's cache immediately
            // ... fan-out-on-write logic here ...
        }
        // PROBLEM: FeedService knows both algorithms' internals.
        // PROBLEM: Adding "partial fan-out" means cracking open this method.
        // PROBLEM: Cannot test write vs read strategy in isolation.
    }
}
```

---

### The Solution: HybridFanoutStrategy as a Composite

The Composite pattern says: **create a class that implements the same interface as its children, but internally delegates to the appropriate child**. The client (FeedService) calls one strategy; the composite routes to the right child.

This is Strategy + Composite working together -- the hybrid IS a composite of two strategies.

```java
// === STEP 1: The Strategy interface (same as always) ===
// File: 05-social-media-feed/.../strategy/FanoutStrategy.java
//
// All three strategies implement this single interface.
// FeedService depends on this interface, not on any concrete class.
public interface FanoutStrategy {

    /**
     * Distribute a tweet to the poster's followers.
     * - FanoutOnWrite: pushes to every follower's timeline cache
     * - FanoutOnRead: does nothing (followers pull at read time)
     * - Hybrid: delegates to write or read based on celebrity status
     */
    void distribute(Tweet tweet, User poster, List<User> followers);
}
```

```java
// === STEP 2: Leaf strategy — Fan-out on write ===
// File: 05-social-media-feed/.../strategy/FanoutOnWriteStrategy.java
//
// Pushes the tweet into every follower's precomputed timeline cache.
// Great for normal users (hundreds of followers).
// Terrible for celebrities (millions of writes per tweet).
public class FanoutOnWriteStrategy implements FanoutStrategy {

    private final TimelineCacheRepository timelineCache;

    public FanoutOnWriteStrategy(TimelineCacheRepository timelineCache) {
        this.timelineCache = timelineCache;
    }

    @Override
    public void distribute(Tweet tweet, User poster, List<User> followers) {
        // Push tweet to EVERY follower's cached timeline.
        // O(N) writes where N = number of followers.
        for (User follower : followers) {
            timelineCache.addToTimeline(follower.getId(), tweet);
        }
    }
}
```

```java
// === STEP 3: Leaf strategy — Fan-out on read ===
// File: 05-social-media-feed/.../strategy/FanoutOnReadStrategy.java
//
// Does nothing at write time. The tweet is stored once.
// When a follower opens their feed, FeedService pulls tweets
// from all followed users and merges them in real time.
public class FanoutOnReadStrategy implements FanoutStrategy {

    @Override
    public void distribute(Tweet tweet, User poster, List<User> followers) {
        // Intentionally empty — no fan-out at write time.
        // Followers will pull this tweet at read time.
        // This avoids write amplification for celebrity users.
    }
}
```

```java
// === STEP 4: THE COMPOSITE — HybridFanoutStrategy ===
// File: 05-social-media-feed/.../strategy/HybridFanoutStrategy.java
//
// THIS IS THE COMPOSITE PATTERN:
// - It implements FanoutStrategy (same interface as its children)
// - It CONTAINS references to FanoutOnWriteStrategy and FanoutOnReadStrategy
// - It delegates to one or the other based on the poster's celebrity status
//
// The client (FeedService) treats HybridFanoutStrategy exactly like
// any other FanoutStrategy. It does not know that internally, the hybrid
// is composed of two sub-strategies. That is the power of Composite:
// the composite and its children share the same interface.
public class HybridFanoutStrategy implements FanoutStrategy {

    // The two "children" of this composite.
    // Both implement FanoutStrategy — the same interface as this class.
    private final FanoutOnWriteStrategy writeStrategy;
    private final FanoutOnReadStrategy readStrategy;
    private final int celebrityFollowerThreshold;

    public HybridFanoutStrategy(FanoutOnWriteStrategy writeStrategy,
                                 FanoutOnReadStrategy readStrategy,
                                 int celebrityFollowerThreshold) {
        this.writeStrategy = writeStrategy;
        this.readStrategy = readStrategy;
        this.celebrityFollowerThreshold = celebrityFollowerThreshold;
    }

    @Override
    public void distribute(Tweet tweet, User poster, List<User> followers) {
        // THE COMPOSITE DECISION:
        // Celebrity? → delegate to read strategy (no write amplification)
        // Normal user? → delegate to write strategy (instant delivery)
        if (poster.isCelebrity() ||
            poster.getFollowerCount() > celebrityFollowerThreshold) {
            // Celebrity path: do NOT push to millions of timelines.
            // Followers will pull this tweet when they open their feed.
            readStrategy.distribute(tweet, poster, followers);
        } else {
            // Normal user path: push to all followers immediately.
            // Hundreds of writes is fast and keeps feeds real-time.
            writeStrategy.distribute(tweet, poster, followers);
        }
    }
}
```

```java
// === STEP 5: FeedService uses the composite like any other strategy ===
// File: 05-social-media-feed/.../service/FeedService.java
//
// FeedService does not know it is talking to a composite.
// It just calls fanoutStrategy.distribute() and the right thing happens.
public class FeedService {

    private final FanoutStrategy fanoutStrategy;  // Could be Write, Read, OR Hybrid
    // ...

    public void publishTweet(Tweet tweet) {
        User poster = userRepository.findById(tweet.getUserId());
        List<User> followers = followRepository.getFollowers(poster.getId());

        // ONE call — the composite handles the routing internally.
        fanoutStrategy.distribute(tweet, poster, followers);
    }
}
```

---

### When to Use / When NOT to Use

**Use when:**
- You have multiple strategies that need to be combined into a single unified strategy
- The composite should be transparent to the client (same interface as children)
- Selection logic between children belongs inside the composite, not in the client

**Do NOT use when:**
- A simple if-else in the service is sufficient and unlikely to grow
- There is only one strategy (no need for composition)
- The selection logic is better handled by the client (e.g., user explicitly chooses)

### Interview One-Liner

> "HybridFanoutStrategy is both a Strategy AND a Composite -- it implements the same interface as its children, but internally delegates based on user type."

### Projects Using This Pattern

| Project | Composite | Children | Selection Logic |
|---------|-----------|----------|-----------------|
| URL Shortener | -- | -- | -- |
| Rate Limiter | -- | -- | -- |
| Notification System | -- | -- | -- |
| Chat System | -- | -- | -- |
| Social Media Feed | `HybridFanoutStrategy` | `FanoutOnWriteStrategy` + `FanoutOnReadStrategy` | `poster.isCelebrity()` or follower count > threshold |

---

## 13. Facade Pattern

**One-line definition:** Provide a unified interface to a set of interfaces in a subsystem.

**GoF Category:** Structural

---

### The Problem: Too Many Subsystems to Coordinate

In the Parking Lot system, parking a vehicle requires coordinating five different subsystems: finding an available spot (ParkingStrategy), calculating the fee (PricingStrategy), processing payment (PaymentProcessor), issuing/updating a ticket (TicketRepository), and updating the display board (DisplayBoard). Without a Facade, the client (controller) has to call each subsystem in the right order and handle the interactions between them.

```java
// === THE UGLY VERSION: Controller orchestrates 5 subsystems directly ===
// The controller knows too much about the parking workflow internals.
// Every endpoint has to coordinate the same set of objects in the right sequence.
public class ParkingController {
    private final ParkingStrategy parkingStrategy;
    private final PricingStrategy pricingStrategy;
    private final PaymentProcessor paymentProcessor;
    private final TicketRepository ticketRepository;
    private final DisplayBoard displayBoard;

    public void handlePark(Vehicle vehicle) {
        // PROBLEM: Controller must know the correct orchestration order.
        ParkingSpot spot = parkingStrategy.findSpot(vehicle);      // (1)
        spot.occupy(vehicle);                                       // (2)
        ParkingTicket ticket = new ParkingTicket(vehicle, spot);    // (3)
        ticketRepository.save(ticket);                              // (4)
        displayBoard.update();                                      // (5)
        // PROBLEM: What if you add a 6th subsystem (e.g., NotificationService)?
        //          You must modify EVERY controller method.
        // PROBLEM: Duplicating this sequence in tests, CLI, and REST endpoints.
    }
}
```

---

### The Solution: ParkingService as Facade

The Facade pattern says: **create a single class that wraps the complex subsystem interactions behind simple methods**. The client calls one method; the Facade orchestrates everything internally.

```java
// === THE FACADE: ParkingService wraps 5 subsystems ===
// File: 06-parking-lot/.../service/ParkingService.java
//
// ParkingService is the single entry point for all parking operations.
// The controller (or any client) calls parkVehicle() or unparkVehicle()
// and does not need to know about strategies, repositories, or display boards.
//
// This is the Facade pattern: one simple interface hiding a complex subsystem.
public class ParkingService {

    private final ParkingStrategy parkingStrategy;      // finds available spots
    private final PricingStrategy pricingStrategy;      // calculates fees
    private final PaymentProcessor paymentProcessor;    // handles payment
    private final TicketRepository ticketRepository;    // stores tickets
    private final DisplayBoard displayBoard;            // updates availability

    // Constructor receives all subsystem dependencies
    public ParkingService(ParkingStrategy parkingStrategy,
                          PricingStrategy pricingStrategy,
                          PaymentProcessor paymentProcessor,
                          TicketRepository ticketRepository,
                          DisplayBoard displayBoard) {
        this.parkingStrategy = parkingStrategy;
        this.pricingStrategy = pricingStrategy;
        this.paymentProcessor = paymentProcessor;
        this.ticketRepository = ticketRepository;
        this.displayBoard = displayBoard;
    }

    // === FACADE METHOD: parkVehicle() ===
    // One method call orchestrates 5 subsystems in the correct order.
    // The client does not know or care about the internal steps.
    public ParkingTicket parkVehicle(Vehicle vehicle) {
        // (1) Find an available spot using the parking strategy
        ParkingSpot spot = parkingStrategy.findSpot(vehicle);

        // (2) Occupy the spot — state transitions AVAILABLE → OCCUPIED
        spot.occupy(vehicle);

        // (3) Create and persist the ticket
        ParkingTicket ticket = ParkingTicket.builder()
                .vehicle(vehicle)
                .spot(spot)
                .entryTime(LocalDateTime.now())
                .build();
        ticketRepository.save(ticket);

        // (4) Update the display board with new availability
        displayBoard.update();

        return ticket;
    }

    // === FACADE METHOD: unparkVehicle() ===
    // Again, one call handles fee calculation, payment, spot release, and display update.
    public PaymentReceipt unparkVehicle(String ticketId, PaymentMethod paymentMethod) {
        // (1) Retrieve the ticket
        ParkingTicket ticket = ticketRepository.findById(ticketId);

        // (2) Calculate fee using pricing strategy
        double fee = pricingStrategy.calculateFee(ticket);

        // (3) Process payment
        PaymentReceipt receipt = paymentProcessor.process(fee, paymentMethod);

        // (4) Release the spot — state transitions OCCUPIED → AVAILABLE
        ticket.getSpot().vacate();

        // (5) Update display board
        displayBoard.update();

        return receipt;
    }
}
```

```java
// === THE CONTROLLER: Clean and simple ===
// The controller only knows about ParkingService (the Facade).
// It does not import or reference any of the 5 subsystems.
public class ParkingController {
    private final ParkingService parkingService;  // THE FACADE

    public ParkingController(ParkingService parkingService) {
        this.parkingService = parkingService;
    }

    public void handlePark(Vehicle vehicle) {
        // ONE call instead of coordinating 5 subsystems
        ParkingTicket ticket = parkingService.parkVehicle(vehicle);
        System.out.println("Parked! Ticket: " + ticket.getId());
    }

    public void handleUnpark(String ticketId, PaymentMethod method) {
        PaymentReceipt receipt = parkingService.unparkVehicle(ticketId, method);
        System.out.println("Fee: $" + receipt.getAmount());
    }
}
```

---

### When to Use / When NOT to Use

**Use when:**
- A client needs to interact with multiple subsystem classes in a specific order
- You want to provide a simple interface to a complex subsystem
- You want to decouple clients from subsystem implementation details

**Do NOT use when:**
- The subsystem is already simple (one or two classes)
- Clients genuinely need fine-grained control over each subsystem
- The Facade would become a "god class" with too many responsibilities

### Interview One-Liner

> "ParkingService is a Facade -- one method call to park a vehicle instead of coordinating 5 subsystems."

### Projects Using This Pattern

| Project | Facade | Subsystems Wrapped | Key Methods |
|---------|--------|-------------------|-------------|
| URL Shortener | -- | -- | -- |
| Rate Limiter | -- | -- | -- |
| Notification System | -- | -- | -- |
| Chat System | -- | -- | -- |
| Social Media Feed | -- | -- | -- |
| Parking Lot | `ParkingService` | `ParkingStrategy` + `PricingStrategy` + `PaymentProcessor` + `TicketRepository` + `DisplayBoard` | `parkVehicle()`, `unparkVehicle()` |

---

## 14. State Pattern

**One-line definition:** Allow an object to alter its behavior when its internal state changes. The object will appear to change its class.

**GoF Category:** Behavioral

---

### The Problem: Status-Dependent Behavior with if-else

In the Parking Lot system, a ParkingSpot has a status that determines what operations are valid. An AVAILABLE spot can be occupied or marked out of order. An OCCUPIED spot can only be vacated. An OUT_OF_ORDER spot cannot be used at all. Without the State pattern, every method needs if-else checks on the current status.

```java
// === THE UGLY VERSION: if-else on status in every method ===
public class ParkingSpot {
    private SpotStatus status; // AVAILABLE, OCCUPIED, OUT_OF_ORDER

    public void occupy(Vehicle vehicle) {
        if (status == SpotStatus.OCCUPIED) {
            throw new IllegalStateException("Spot already occupied!");
        }
        if (status == SpotStatus.OUT_OF_ORDER) {
            throw new IllegalStateException("Spot is out of order!");
        }
        // Only AVAILABLE spots can be occupied
        this.vehicle = vehicle;
        this.status = SpotStatus.OCCUPIED;
    }

    public void vacate() {
        if (status != SpotStatus.OCCUPIED) {
            throw new IllegalStateException("Spot is not occupied!");
        }
        this.vehicle = null;
        this.status = SpotStatus.AVAILABLE;
    }
    // PROBLEM: Every method starts with status checks.
    // PROBLEM: Adding a new state (e.g., RESERVED) means modifying every method.
}
```

---

### The Solution: State Transitions Encoded in the Domain

The State pattern encodes valid transitions directly. The ParkingSpot's behavior depends on its current status, and transitions are guarded so that only valid state changes are allowed.

```java
// === STATE TRANSITIONS ===
// File: 06-parking-lot/.../model/ParkingSpot.java
//
// ParkingSpot status governs what operations are valid:
//
//   AVAILABLE ──(occupy)──→ OCCUPIED
//   OCCUPIED  ──(vacate)──→ AVAILABLE
//   AVAILABLE ──(markOutOfOrder)──→ OUT_OF_ORDER
//   OUT_OF_ORDER ──(repair)──→ AVAILABLE
//
// Each method enforces valid transitions and throws if the
// current state does not allow the requested operation.

public enum SpotStatus {
    AVAILABLE,    // Spot is free — can be occupied or marked out of order
    OCCUPIED,     // Spot has a vehicle — can only be vacated
    OUT_OF_ORDER  // Spot is broken — cannot be used until repaired
}

public abstract class ParkingSpot {
    private SpotStatus status = SpotStatus.AVAILABLE;
    private Vehicle currentVehicle;

    // === STATE TRANSITION: AVAILABLE → OCCUPIED ===
    public void occupy(Vehicle vehicle) {
        if (status != SpotStatus.AVAILABLE) {
            throw new IllegalStateException(
                "Cannot occupy spot in state: " + status);
        }
        // Guard: only AVAILABLE spots can be occupied
        this.currentVehicle = vehicle;
        this.status = SpotStatus.OCCUPIED;
    }

    // === STATE TRANSITION: OCCUPIED → AVAILABLE ===
    public void vacate() {
        if (status != SpotStatus.OCCUPIED) {
            throw new IllegalStateException(
                "Cannot vacate spot in state: " + status);
        }
        this.currentVehicle = null;
        this.status = SpotStatus.AVAILABLE;
    }

    // === STATE TRANSITION: AVAILABLE → OUT_OF_ORDER ===
    public void markOutOfOrder() {
        if (status != SpotStatus.AVAILABLE) {
            throw new IllegalStateException(
                "Cannot mark out of order in state: " + status);
        }
        this.status = SpotStatus.OUT_OF_ORDER;
    }

    // === STATE TRANSITION: OUT_OF_ORDER → AVAILABLE ===
    public void repair() {
        if (status != SpotStatus.OUT_OF_ORDER) {
            throw new IllegalStateException(
                "Cannot repair spot in state: " + status);
        }
        this.status = SpotStatus.AVAILABLE;
    }

    // State-dependent query: behavior depends on current status
    public boolean isAvailable() {
        return status == SpotStatus.AVAILABLE;
    }

    // Template Method: each subclass defines what vehicle types it can fit
    public abstract boolean canFitVehicle(Vehicle vehicle);
}
```

### State Transition Diagram

```
                ┌──────────────────┐
                │    AVAILABLE     │
                │  (initial state) │
                └──────┬───────────┘
                       │
            ┌──────────┼──────────────┐
            │ occupy() │              │ markOutOfOrder()
            ▼          │              ▼
   ┌────────────────┐  │    ┌──────────────────┐
   │   OCCUPIED     │  │    │  OUT_OF_ORDER    │
   │                │  │    │                  │
   └────────┬───────┘  │    └────────┬─────────┘
            │          │             │
            │ vacate() │             │ repair()
            └──────────┘             │
                ▲                    │
                └────────────────────┘
                    (back to AVAILABLE)
```

---

### When to Use / When NOT to Use

**Use when:**
- An object's behavior depends on its state, and it must change behavior at runtime
- Operations have complex conditional logic based on the object's state
- State transitions need to be explicit and validated

**Do NOT use when:**
- The object has only two simple states (a boolean flag is sufficient)
- State transitions are trivial and do not affect behavior
- The number of states is unlikely to grow

### Interview One-Liner

> "ParkingSpot uses the State pattern -- its behavior changes based on status (AVAILABLE, OCCUPIED, OUT_OF_ORDER), and each transition is guarded so only valid operations are allowed."

### Projects Using This Pattern

| Project | Stateful Object | States | Key Transitions |
|---------|----------------|--------|-----------------|
| URL Shortener | -- | -- | -- |
| Rate Limiter | -- | -- | -- |
| Notification System | `Notification` (partial -- status field: PENDING, SENT, DELIVERED, FAILED) | 4 statuses | PENDING → SENT → DELIVERED, PENDING → FAILED |
| Chat System | `Message` (partial -- status field: SENT, DELIVERED, READ) | 3 statuses | SENT → DELIVERED → READ |
| Social Media Feed | -- | -- | -- |
| Parking Lot | `ParkingSpot` | AVAILABLE, OCCUPIED, OUT_OF_ORDER | AVAILABLE → OCCUPIED → AVAILABLE, AVAILABLE → OUT_OF_ORDER |

---

## Pattern Interaction Map

This diagram shows how all the patterns work together when a single notification request flows through the Notification System end-to-end.

```
                    NotificationRequest arrives
                             │
                             ▼
    ┌───────────────────────────────────────────────────────────┐
    │  [FACTORY] AppConfig created all handlers, services,      │
    │  repositories, queue, and tracker during startup.         │
    │  This is where "new ConcreteClass()" lives.               │
    │  Everything else depends only on interfaces.              │
    └───────────────────────────┬───────────────────────────────┘
                                │
                                ▼
    ┌───────────────────────────────────────────────────────────┐
    │  [REPOSITORY] PreferenceRepository.findByUserId("alice")  │
    │  Checks if alice has opted out or is in quiet hours.      │
    │  Service does not know if preferences are in memory,      │
    │  Redis, or a database.                                    │
    └───────────────────────────┬───────────────────────────────┘
                                │
                                ▼
    ┌───────────────────────────────────────────────────────────┐
    │  [REPOSITORY] TemplateRepository.findById("order-confirm")│
    │  Loads the message template: "Hi {{name}}, order {{id}}..." │
    │  [TEMPLATE METHOD] Template rendering is a common step.   │
    └───────────────────────────┬───────────────────────────────┘
                                │
                                ▼
    ┌───────────────────────────────────────────────────────────┐
    │  [BUILDER] new Notification.Builder()                     │
    │      .userId("alice")                                     │
    │      .channel(Channel.EMAIL)                              │
    │      .priority(Priority.HIGH)                             │
    │      .subject("Order 12345 Confirmed")                    │
    │      .body("Hi Alice, your order...")                     │
    │      .build()                                             │
    │  Constructs an immutable Notification object.             │
    └───────────────────────────┬───────────────────────────────┘
                                │
                                ▼
    ┌───────────────────────────────────────────────────────────┐
    │  [REPOSITORY] NotificationRepository.save(notification)   │
    │  Persists for auditing, status tracking, and retries.     │
    └───────────────────────────┬───────────────────────────────┘
                                │
                                ▼
    ┌───────────────────────────────────────────────────────────┐
    │  [PRODUCER] queue.enqueue(notification)                   │
    │  PriorityBlockingQueue orders by priority.                │
    │  send() returns IMMEDIATELY. API response < 5ms.          │
    └───────────────────────────┬───────────────────────────────┘
                                │
                    ~~~~~~~~ queue boundary ~~~~~~~~
                                │
                                ▼
    ┌───────────────────────────────────────────────────────────┐
    │  [CONSUMER] notification = queue.dequeue()                │
    │  Worker thread pulls the highest-priority notification.   │
    └───────────────────────────┬───────────────────────────────┘
                                │
                                ▼
    ┌───────────────────────────────────────────────────────────┐
    │  [STRATEGY] handlers.get(notification.getChannel())       │
    │  Channel = EMAIL → EmailNotificationHandler               │
    │  Channel = PUSH  → PushNotificationHandler                │
    │  Channel = SMS   → SmsNotificationHandler                 │
    │  Channel = IN_APP→ InAppNotificationHandler               │
    │                                                           │
    │  handler.send(notification)                               │
    │  → Polymorphic call: different code runs per channel.     │
    │  → Returns DeliveryAttempt (status, provider response).   │
    └───────────────────────────┬───────────────────────────────┘
                                │
                                ▼
    ┌───────────────────────────────────────────────────────────┐
    │  [OBSERVER] deliveryTracker.record(attempt)               │
    │  Records the attempt for statistics and auditing.         │
    │  In production: publish to Kafka "delivery-events" topic. │
    │  Multiple consumers: metrics, alerting, audit, billing.   │
    └───────────────────────────────────────────────────────────┘
```

---

## Cross-Project Pattern Usage Table

| Pattern | URL Shortener (01) | Rate Limiter (02) | Notification System (03) | Chat System (04) | Social Media Feed (05) | Parking Lot (06) |
|---------|-------------------|-------------------|--------------------------|-------------------|------------------------|------------------|
| **Strategy** | `EncodingStrategy` (3 implementations: Base62, MD5, Random) | `RateLimiterStrategy` (5 implementations: TokenBucket, LeakyBucket, FixedWindow, SlidingWindowLog, SlidingWindowCounter) | `NotificationHandler` (4 implementations: Push, Email, SMS, InApp) | `MessageRouter` (online vs offline delivery strategy) | `FanoutStrategy` (3 impl: Write, Read, Hybrid) + `FeedRanker` (2 impl: Chronological, Algorithmic) -- TWO strategy interfaces! | `ParkingStrategy` (2 impl) + `PricingStrategy` (2 impl) + `PaymentProcessor` (2 impl) -- THREE strategy interfaces! |
| **Builder** | `Url.Builder` (optional fields: expiresAt, customAlias, userId) | `RateLimitRule.Builder` (required constructor params + optional algorithm, burstCapacity, enabled) | `Notification.Builder` (auto-generated id, 14 fields with defaults) | `Message.Builder` (12+ fields: senderId, conversationId, content, type, status, timestamp, etc.) | `Tweet.Builder` | `ParkingTicket.Builder` |
| **Repository** | `UrlRepository` -> `InMemoryUrlRepository` | `RuleRepository` -> `InMemoryRuleRepository` | `NotificationRepository`, `PreferenceRepository`, `TemplateRepository` (3 interfaces, 3 implementations) | `MessageRepository`, `ConversationRepository`, `UserRepository` (3 repositories) | 5 repositories | `TicketRepository` |
| **Factory** | `AppConfig.createDefaultService()` | `AppConfig.createStrategies()` + `createDefaultService()` | `AppConfig.createNotificationService()` (most complex: 6 dependencies) | `AppConfig` creates all services, repositories, and wires them into `ChatService` | `AppConfig` | `AppConfig` + `ParkingController.createVehicle()` |
| **Observer** | -- | -- | `DeliveryTracker.record()` observes delivery events | Presence updates (online/offline), message delivery/read receipts, group join/leave system messages | tweet.published → fan-out + trending (conceptual) | `DisplayBoard` updates on park/unpark (conceptual) |
| **Producer-Consumer** | -- | -- | `send()` enqueues to `PriorityBlockingQueue`; `processQueue()` dequeues and delivers | -- (conceptual with Kafka for message queueing) | -- | -- |
| **Chain of Responsibility** | -- | Rate limiter as middleware filter in request pipeline (conceptual) | -- | -- | -- | -- |
| **Singleton** | `AtomicLong counter` for unique ID generation | -- | -- | -- | -- | `ParkingLot` (double-checked locking) |
| **Template Method** | -- | -- | Fixed notification processing flow: validate -> preferences -> template -> enqueue -> deliver -> track | -- | -- | `ParkingSpot.canFitVehicle()` -- abstract method overridden per spot type |
| **Mediator** | -- | -- | -- | `ChatService` orchestrates `MessageService`, `GroupService`, `PresenceService`, and `MessageRouter` — subsystems never call each other directly | `FeedService` orchestrates cache + pull + merge + rank | -- |
| **Command** | -- | -- | -- | `Message` as command object flowing through pipeline: created -> queued -> routed -> delivered -> acknowledged | -- | -- |
| **Composite** | -- | -- | -- | -- | `HybridFanoutStrategy` composes Write + Read strategies (NEW) | -- |
| **Facade** | -- | -- | -- | -- | -- | `ParkingService` wraps ParkingStrategy + PricingStrategy + PaymentProcessor + TicketRepository + DisplayBoard (NEW) |
| **State** | -- | -- | -- | -- | -- | `ParkingSpot` status: AVAILABLE → OCCUPIED → AVAILABLE, AVAILABLE → OUT_OF_ORDER (NEW) |

---

## Key Takeaways

1. **Strategy is the foundation.** Once you understand "interface + multiple implementations + injection," every other pattern becomes easier to grasp.

2. **Builder makes construction readable.** Any time you see a constructor with more than 3 parameters, reach for the Builder pattern.

3. **Repository decouples storage.** Your business logic should never know if data is in memory, Redis, or Postgres. The interface is the boundary.

4. **Factory centralizes wiring.** Only one class in your application should say `new ConcreteClass()`. Everything else depends on interfaces.

5. **Patterns work together.** A single request flow in the Notification System uses Factory (wiring) + Builder (construction) + Repository (storage) + Strategy (delivery) + Producer-Consumer (queueing) + Observer (tracking) + Template Method (fixed flow). They are not isolated academic exercises — they compose naturally.

6. **Composition over inheritance.** Our code uses Strategy + composition instead of classical Template Method inheritance. This is more flexible and testable. The Gang of Four themselves recommended this approach.

7. **The Map<Enum, Strategy> pattern** (used in Rate Limiter and Notification System) is the most production-relevant variant of Strategy. It eliminates all if-else chains and enables runtime algorithm selection based on configuration.

8. **Mediator prevents spaghetti.** When multiple services need to collaborate, a Mediator (like ChatService) keeps them from coupling to each other. N services = N coupling paths through the Mediator, not N*(N-1)/2 direct paths.

9. **Command objects travel.** When an action needs to be queued, persisted, retried, or routed across servers, wrap it in a command object (like Message). Method calls are ephemeral; objects are durable.

10. **Composite unifies strategies.** When you need to combine two strategies into one, the Composite pattern lets you create a wrapper that implements the same interface as its children. HybridFanoutStrategy delegates to write or read fan-out based on celebrity status -- the client never knows it is talking to a composite.
