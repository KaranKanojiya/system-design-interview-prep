# System Design Estimation Cheatsheet

> Memorize once. Use in every interview. No project-specific stuff — pure reference.

---

## 1. Data Size Units

| Unit | Value | Approx | Think Of It As |
|------|-------|--------|----------------|
| 1 B | 8 bits | — | 1 character |
| 1 KB | 1,024 B | ~10³ | A short email |
| 1 MB | 1,024 KB | ~10⁶ | 1 photo / 1 min MP3 |
| 1 GB | 1,024 MB | ~10⁹ | 1 movie / 1000 photos |
| 1 TB | 1,024 GB | ~10¹² | A small-medium database |
| 1 PB | 1,024 TB | ~10¹⁵ | Netflix / Google scale |

**Trick**: Each step is ×1000. B → KB → MB → GB → TB → PB.

---

## 2. Common Object Sizes

| Object | Size | Bucket |
|--------|------|--------|
| 1 ASCII char | 1 B | — |
| 1 Unicode char (avg) | 3 B | — |
| int32 | 4 B | Stores up to ~4 Billion |
| int64 / long | 8 B | Practically unlimited |
| timestamp (epoch) | 8 B | It's a long |
| boolean | 1 B | — |
| UUID | 36 B | 36 chars with hyphens |
| Short hash (7 chars) | 7 B | — |
| A URL | 100-200 B | ~100 avg |
| A tweet / SMS | 200-300 B | Text + metadata |
| A small JSON payload | 1-5 KB | API response |
| A DB row (simple) | 100-300 B | IDs + few text fields |
| A DB row (medium) | 500 B - 5 KB | With descriptions |
| A DB row (rich) | 10-100 KB | With embedded content |
| A thumbnail image | 10-50 KB | Low res |
| A profile photo | 100-500 KB | Compressed |
| A high-res image | 2-5 MB | JPEG/PNG |
| A 1-min video (720p) | 5-10 MB | Compressed |
| A 1-hour video (HD) | 1-3 GB | Streaming quality |
| A 1-hour video (4K) | 5-10 GB | Raw/high bitrate |

---

## 3. Time Conversions

### The 3 Magic Numbers

```
1 day   = 86,400 sec   → round to ~100K
1 month = 2,592,000 sec → round to ~2.5M
3 years = ~100M sec
```

### Full Table

| Period | Exact Seconds | Round To |
|--------|--------------|----------|
| 1 second | 1 | 1 |
| 1 minute | 60 | 60 |
| 1 hour | 3,600 | ~3.6K |
| 1 day | 86,400 | ~100K |
| 1 week | 604,800 | ~600K |
| 1 month (30d) | 2,592,000 | ~2.5M |
| 1 year | 31,536,000 | ~30M |
| 5 years | 157,680,000 | ~150M |
| 10 years | 315,360,000 | ~300M |

### Conversion Ladder

```
per MONTH  ─÷30─▶  per DAY  ─÷24─▶  per HOUR  ─÷60─▶  per MINUTE  ─÷60─▶  per SECOND

per SECOND ─×60─▶  per MIN  ─×60─▶  per HOUR  ─×24─▶  per DAY     ─×30─▶  per MONTH
```

### Shortcuts

| From → To | Shortcut |
|-----------|----------|
| Monthly → Per Second | **÷ 2.5M** |
| Daily → Per Second | **÷ 100K** |
| Per Second → Monthly | **× 2.5M** |
| Per Second → Daily | **× 100K** |

```
Example: 300M requests/month → per second?    300M ÷ 2.5M = 120/sec
Example: 10B requests/day → per second?        10B ÷ 100K = 100K/sec
Example: 1000 req/sec → per month?             1000 × 2.5M = 2.5B/month
```

---

## 4. Scale Reference — Powers of 1000

| Notation | Value | In System Design |
|----------|-------|-------------------|
| 1K | 1,000 | Small app's DAU |
| 10K | 10,000 | Medium service QPS |
| 100K | 100,000 | ≈ seconds in a day |
| 1M | 1,000,000 | Decent app's DAU |
| 10M | 10,000,000 | Large table (still manageable) |
| 100M | 100,000,000 | Needs indexing + sharding |
| 1B | 1,000,000,000 | Google-scale daily events |
| 10B | 10,000,000,000 | Total URLs on the internet |
| 1T | 1,000,000,000,000 | Global annual data |

---

## 5. Latency Numbers Every Developer Should Know

| Operation | Time | Bucket |
|-----------|------|--------|
| L1 cache reference | 1 ns | Instant |
| L2 cache reference | 4 ns | Instant |
| RAM reference | 100 ns | Instant |
| SSD random read | 16 μs | Fast |
| HDD random read | 2-10 ms | Slow |
| Same datacenter round trip | 0.5 ms | Fast |
| Redis / Memcached GET | 0.5-1 ms | Fast |
| DB indexed query | 1-5 ms | Fast |
| DB full table scan (small) | 10-100 ms | Noticeable |
| Same-region network call | 1-5 ms | Fast |
| Cross-region network call | 50-150 ms | Noticeable |
| Internet round trip (cross-continent) | 100-300 ms | Slow |
| Cold-start Lambda / serverless | 100-500 ms | Slow |
| Read 1 MB from SSD | 0.2 ms | Fast |
| Read 1 MB from network (1 Gbps) | 10 ms | Fast |
| Read 1 MB from HDD | 5-20 ms | Slow |

**Trick**: RAM = nanoseconds, SSD = microseconds, Network = milliseconds, HDD = milliseconds.

---

## 6. Throughput / Capacity Reference

| Component | Typical Throughput |
|-----------|-------------------|
| Single web server | 1K-10K req/sec |
| Single Redis node | 100K-200K ops/sec |
| Single MySQL/PostgreSQL | 5K-20K queries/sec (indexed) |
| Single Cassandra node | 10K-50K writes/sec |
| Single Kafka broker | 100K-200K msgs/sec |
| Load balancer (L7) | 50K-100K req/sec |
| DNS lookup | 20-50 ms |
| CDN edge cache hit | 1-5 ms |

### Storage Capacity

| Component | Typical Capacity |
|-----------|-----------------|
| Single Redis node | 25-50 GB RAM |
| Single MySQL instance | 1-5 TB |
| Single Cassandra node | 1-2 TB |
| Single S3 bucket | Unlimited (theoretically) |
| DynamoDB table | Unlimited (auto-scales) |
| Single server disk | 1-16 TB SSD |

---

## 7. Availability Table

| Availability | Downtime/Year | Downtime/Month | Downtime/Day |
|-------------|---------------|----------------|-------------|
| 99% (two 9s) | 3.65 days | 7.3 hours | 14.4 min |
| 99.9% (three 9s) | 8.76 hours | 43.8 min | 1.44 min |
| 99.99% (four 9s) | 52.6 min | 4.38 min | 8.6 sec |
| 99.999% (five 9s) | 5.26 min | 26.3 sec | 0.86 sec |

**Trick**: Each extra 9 = 10× less downtime. Most systems target **three 9s (99.9%)** or **four 9s (99.99%)**.

---

## 8. Base Encoding Reference

| Encoding | Characters | Base | Keyspace (6 chars) | Keyspace (7 chars) | Keyspace (8 chars) |
|----------|-----------|------|--------------------|--------------------|-------------------|
| Hex | 0-9, a-f | 16 | 16.7M | 268M | 4.3B |
| Base36 | 0-9, a-z | 36 | 2.2B | 78B | 2.8T |
| Base62 | 0-9, a-z, A-Z | 62 | 56.8B | 3.5T | 218T |
| Base64 | 62 + /,+ | 64 | 68.7B | 4.4T | 281T |

**Trick**: Base62 with 7 chars = **3.5 Trillion** — enough for almost any system.

---

## 9. Quick-Fire Formulas

```
QPS (from monthly)       = monthly_requests ÷ 2.5M
QPS (from daily)         = daily_requests ÷ 100K
Peak QPS                 = avg QPS × 2 to 5  (use ×3 as default)
Storage                  = total_records × avg_row_size
Bandwidth                = QPS × avg_response_size
Cache memory             = daily_requests × 0.2 × avg_response_size
Servers needed           = peak_QPS ÷ single_server_QPS
Shards needed            = total_storage ÷ per_shard_capacity
Replication storage      = raw_storage × replication_factor (usually 3)
```

---

## 10. Typical System Profiles — Anchor Points

| System | Scale | Write QPS | Read:Write | Read QPS | Row Size |
|--------|-------|-----------|------------|----------|----------|
| URL Shortener | 100M/month | ~40/sec | 100:1 | ~4K/sec | ~300 B |
| Twitter | 600M tweets/day | ~7K/sec | 10:1 | ~70K/sec | ~300 B |
| Instagram | 100M photos/day | ~1.2K/sec | 100:1 | ~120K/sec | ~2 MB (media) |
| WhatsApp | 100B msgs/day | ~1.2M/sec | 1:1 | ~1.2M/sec | ~100 B |
| YouTube | 500 hrs video/min | ~8/sec | 1000:1 | ~8K/sec | ~50 MB (video) |
| Google Search | 8.5B/day | — | Read-only | ~100K/sec | — |
| Uber | 20M rides/day | ~230/sec | 5:1 | ~1.2K/sec | ~500 B |

**Trick**: Memorize 2-3 as anchors. Interpolate for new problems during interviews.

---

## 11. The "5 Numbers" Rule

Memorize ONLY these. Derive everything else.

| # | Number | What It Means |
|---|--------|--------------|
| 1 | **100K** | Seconds in a day (≈86.4K) |
| 2 | **2.5M** | Seconds in a month |
| 3 | **300 B** | Typical small DB row |
| 4 | **80-20** | 20% data → 80% traffic (cache rule) |
| 5 | **62⁷ = 3.5T** | Base62 keyspace with 7 chars |

---

## 12. Interview Math Tips

- **Always round aggressively** — 86,400 → 100K. Nobody cares about precision. Speed matters.
- **Use powers of 10** — Don't do 365 × 24 × 3600. Just say "about 30M seconds in a year."
- **State assumptions first** — "Let me assume 100M users, 10% DAU, 5 actions each"
- **Show the formula, then plug in** — Formula first = you look structured
- **Convert to per-second early** — All capacity planning works in QPS
- **Multiply storage by 3** — Replication factor. Always mention it.
- **Peak = 3× average** — Simple rule for peak traffic estimation
- **Don't spend more than 3 minutes** — Get the order of magnitude right and move on
