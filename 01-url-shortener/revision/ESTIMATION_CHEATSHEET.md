# Back-of-Envelope Estimation Cheatsheet

> Memorize this once. Use it in every system design interview.

---

## 1. Data Size Table — "The Power of 10s"

### Trick: Just remember the chain → **B → KB → MB → GB → TB → PB** (each × 1000 roughly)

| Unit | Exact | Approx | Memory Trick |
|------|-------|--------|-------------|
| 1 Byte | 8 bits | 1 char | One character = one byte |
| 1 KB | 1,024 B | ~1K | A short email |
| 1 MB | 1,024 KB | ~1M | A photo / 1 min MP3 |
| 1 GB | 1,024 MB | ~1B | A movie / 1000 photos |
| 1 TB | 1,024 GB | ~1T | A small database |
| 1 PB | 1,024 TB | ~1000T | Netflix-scale data |

### Common Object Sizes — "The Anchor Table"

**Trick**: Memorize just 5 anchors, derive everything else.

| What | Size | Memory Trick |
|------|------|-------------|
| 1 char (ASCII) | 1 B | **The atom** — everything is multiples of this |
| 1 char (Unicode/UTF-8 avg) | 2-4 B | Asian chars = 3B, emoji = 4B |
| UUID / short hash | ~36 B | 36 chars = 36 bytes |
| A URL | ~100-200 B | Average ~100 chars |
| A tweet / short text | ~300 B | 280 chars + metadata |
| A JSON API response | ~1-5 KB | Small payload |
| A database row (typical) | ~200-500 B | Few columns of text + numbers |
| A profile photo (compressed) | ~200 KB | Thumbnail |
| A high-res image | ~2-5 MB | JPEG |
| A 1-min video (compressed) | ~5-10 MB | 720p |
| A 1-hour video (HD) | ~1-3 GB | Streaming quality |

### Number Storage

| Type | Size | Trick |
|------|------|-------|
| int / int32 | 4 B | "**4** for **4** billion max" (2^32 = ~4B) |
| long / int64 | 8 B | "**8** for **8** quintillion" — practically unlimited |
| float | 4 B | Same as int |
| double | 8 B | Same as long |
| boolean | 1 B | Wasteful but true |
| timestamp (epoch) | 8 B | It's a long |

---

## 2. Time Conversion Table — "The Magic Numbers"

### Trick: Memorize only 3 numbers, derive everything

```
86,400  = seconds in a day     → "86K seconds/day"
2.5M    = seconds in a month   → "2.5 million seconds/month"  
100M    = seconds in 3 years   → "100 million = ~3 years"
```

### Full Table

| Period | Seconds | Trick to Remember |
|--------|---------|-------------------|
| 1 minute | 60 | You know this |
| 1 hour | 3,600 | 60 × 60 = **~3.6K** |
| 1 day | 86,400 | **~86K** → round to **~100K** for quick math |
| 1 week | 604,800 | **~600K** → round to **~0.6M** |
| 1 month | 2,592,000 | **~2.5M** → round to **~2.5M** |
| 1 year | 31,536,000 | **~30M** → round to **~30M** |
| 5 years | 157,680,000 | **~150M** |

### Quick Conversion Trick — "Divide Down or Multiply Up"

**From monthly → per second:** Divide by **2.5M**
**From daily → per second:** Divide by **100K** (using rounded 86K → 100K)

```
Example: 100M requests/month → per second?
  100M / 2.5M = 40 req/sec  ✓

Example: 1B requests/day → per second?
  1B / 100K = 10,000 req/sec  ✓

Example: 500 req/sec → per month?
  500 × 2.5M = 1.25B req/month  ✓
```

### The Ladder — Convert Step by Step

```
per MONTH  ──÷30──▶  per DAY  ──÷24──▶  per HOUR  ──÷60──▶  per MINUTE  ──÷60──▶  per SECOND

per SECOND ──×60──▶  per MIN  ──×60──▶  per HOUR  ──×24──▶  per DAY     ──×30──▶  per MONTH
```

**Shortcut**: Month → Second = **÷ 2.5M** (just remember this one number)

---

## 3. Scale Numbers — "Powers of 1000"

### Trick: **K → M → B** (each is × 1000)

| Notation | Value | System Design Context |
|----------|-------|-----------------------|
| 1K | 1,000 | A small API's daily users |
| 10K | 10,000 | QPS of a medium service |
| 100K | 100,000 | ≈ seconds in a day |
| 1M | 1,000,000 | Daily active users of a decent app |
| 10M | 10,000,000 | Rows you can comfortably scan |
| 100M | 100,000,000 | Large table, needs indexing/sharding |
| 1B | 1,000,000,000 | Google-scale daily queries |
| 10B | 10,000,000,000 | Total URLs on the internet |

---

## 4. The "Per-Row → Total Storage" Formula

```
Total Storage = (number of records) × (avg row size in bytes)
```

### URL Shortener Example — Walk Through It

```
Step 1: How many records in 5 years?
  100M URLs/month × 12 months × 5 years = 6 Billion URLs

Step 2: What's one row?
  short_code (7B) + original_url (100B) + created_at (8B) + 
  expires_at (8B) + click_count (8B) + user_id (36B) + overhead (~33B)
  ≈ 200 bytes per row  (ROUND TO ~200-300B for safety)

Step 3: Total?
  6B × 300 bytes = 1.8 TB

Step 4: With replication (3x)?
  1.8 TB × 3 = 5.4 TB → "about 6 TB with replication"
```

### Quick Row Size Estimator

| Row Type | Typical Size | Examples |
|----------|-------------|----------|
| Tiny (IDs + few fields) | 50-100 B | URL mapping, key-value, session |
| Small (text + metadata) | 200-500 B | User profile, tweet, notification |
| Medium (with descriptions) | 1-5 KB | Product listing, email, comment |
| Large (with blobs) | 10-100 KB | Article with images, log entry |
| Very large | 1+ MB | Don't store in DB, use object storage |

---

## 5. Cache Sizing — "The 80-20 Rule"

### Trick: Only 20% of data causes 80% of traffic

```
Cache memory = (requests per day) × 0.2 × (response size)
```

### URL Shortener Example

```
Reads/day = 4,000/sec × 86,400 = ~350M/day
Cache for top 20% = 350M × 0.2 = 70M entries
Memory = 70M × 300 bytes = 21 GB → "About 20 GB, fits in one Redis node"
```

**Redis node capacity**: A single Redis node handles ~25-50 GB comfortably.

---

## 6. Bandwidth Estimation

```
Bandwidth = (requests per second) × (avg response size)
```

### URL Shortener Example

```
Write: 40 req/sec × 300 bytes = 12 KB/s  (negligible)
Read:  4000 req/sec × 300 bytes = 1.2 MB/s  (small)
```

**Trick**: Bandwidth is almost never the bottleneck for text-based systems. Only mention it for media-heavy systems (YouTube, Instagram).

---

## 7. QPS Estimation Patterns — Reusable Templates

| System | Users/Month | Write QPS | Read:Write | Read QPS |
|--------|------------|-----------|------------|----------|
| URL Shortener | 100M URLs | ~40/sec | 100:1 | ~4K/sec |
| Twitter | 300M MAU, 600M tweets/day | ~7K/sec | 10:1 | ~70K/sec |
| Instagram | 500M DAU, 100M photos/day | ~1.2K/sec | 100:1 | ~120K/sec |
| WhatsApp | 2B users, 100B msgs/day | ~1.2M/sec | 1:1 | ~1.2M/sec |
| Google Search | 8.5B searches/day | — | Read-only | ~100K/sec |

**Trick**: Memorize 2-3 of these as anchor points. Interpolate for new problems.

---

## 8. Interview Quick-Fire Formulas

```
Monthly → Per Second     = ÷ 2.5M
Daily → Per Second       = ÷ 100K  (use 86.4K ≈ 100K)
Storage                  = records × row_size
Cache                    = daily_reads × 0.2 × response_size
Bandwidth                = QPS × response_size
Short code space (base N, length L) = N^L possible codes
```

### Base62 Quick Reference

| Code Length | Possible Codes | Enough For |
|-------------|---------------|------------|
| 6 chars | 56 Billion | Most systems |
| 7 chars | 3.5 Trillion | More than enough |
| 8 chars | 218 Trillion | Overkill |

**Trick**: 62^6 ≈ 56B, 62^7 ≈ 3.5T — **7 chars is the sweet spot** for almost all URL shorteners.

---

## 9. Memory Tricks Summary — "The 5 Numbers"

If you remember ONLY these 5 numbers, you can derive almost everything:

| # | Number | What It Means |
|---|--------|--------------|
| 1 | **86,400** | Seconds in a day (~100K) |
| 2 | **2.5M** | Seconds in a month |
| 3 | **300 bytes** | Typical small DB row |
| 4 | **80-20** | Cache sizing rule |
| 5 | **62^7 = 3.5T** | Short code keyspace |

---

## 10. Practice — Do This Before Every Interview

1. Pick any system (Twitter, Uber, WhatsApp)
2. Assume a user count
3. Estimate writes/sec and reads/sec
4. Calculate storage for 5 years
5. Calculate cache size
6. Do it in under 2 minutes on paper

> If you can do the math in your head during the interview, it signals confidence and preparation. Interviewers notice.
