# High-Concurrency Rate Limiter & Flash Sale System (Java)

A production-grade, thread-safe Rate Limiter implementation in Java, designed specifically for high-concurrency flash sales (like Amazon's "Lightning Deals" or ticket drops) where a fixed inventory is contested by thousands of concurrent shoppers and aggressive bots.

---

## 🚀 Quick Start

### Prerequisites
- **JDK 17+** (Works with JDK 17, 21, 25+)
- **Apache Maven 3.8+**

```bash
# Verify Java and Maven
java -version
mvn -version
```

### 1. Run Unit Tests
```bash
mvn test
```

### 2. Run the Flash Sale Simulation
Simulates 900 simultaneous requests (200 normal shoppers + 10 aggressive bots firing 50 requests each) competing for 100 units of stock at $t=0$:
```bash
mvn exec:java
```

---

## 📁 Project Architecture & Package Structure

```
rate-limiter/
├── pom.xml                                 # Maven project configuration
├── README.md                               # System design, setup, & guide
├── .gitignore
└── src
    ├── main
    │   ├── java
    │   │   └── com/amazon/ratelimiter
    │   │       ├── RateLimiter.java                 # Core RateLimiter interface
    │   │       ├── RateLimitResult.java             # Immutable Java Record (verdict & metadata)
    │   │       ├── algorithm
    │   │       │   ├── TokenBucketRateLimiter.java  # Lock-free CAS Token Bucket
    │   │       │   ├── SlidingWindowCounterRateLimiter.java # Weighted sliding window counter
    │   │       │   └── FixedWindowRateLimiter.java  # Educational baseline
    │   │       ├── flashsale
    │   │       │   ├── FlashSaleManager.java        # Multi-layer defense (Rate Limit + Atomic Stock)
    │   │       │   └── FlashSaleSimulationDemo.java # Multi-threaded concurrency simulation
    │   │       └── distributed
    │   │           └── RedisSlidingWindowScript.java # Redis Lua script loader
    │   └── resources
    │       └── redis-sliding-window.lua             # Production Redis Lua script
    └── test
        └── java
            └── com/amazon/ratelimiter
                ├── TokenBucketRateLimiterTest.java
                └── SlidingWindowCounterRateLimiterTest.java
```

---

## 🧠 Why Rate Limiting Alone Is Not Enough for Flash Sales

In a flash sale, traffic is handled across **defensive tiers**:

```
[1,000,000+ Concurrent Requests]
       │
       ▼ Layer 1: Edge / WAF (Cloudflare / Akamai)
         Throttles blatant DDoS, checks TLS/JA4 fingerprints & CAPTCHA.
       │
       ▼ Layer 2: Virtual Waiting Room
         Smooths thundering herd by issuing FIFO ticket tokens.
       │
       ▼ Layer 3: API Gateway Rate Limiter (TokenBucket / Sliding Window)
         Enforces velocity limit per user (e.g. 2 checkout attempts / 5s).
         Returns HTTP 429 Too Many Requests to spammers and bot scripts.
       │
       ▼ Layer 4: Atomic Stock Reservation (Redis Atomic DECR)
         Only admits the exact number of available items (e.g., 100 items).
         Remaining users get instant HTTP 410 "Sold Out" without touching the DB.
       │
       ▼ Layer 5: Asynchronous Order Creation (Kafka -> Worker -> Database)
         Asynchronously persists orders into MySQL/Postgres in controlled micro-batches.
```

---

## 💡 Key Java Concurrency Concepts Used

1. **Lock-Free Programming via `AtomicReference` & CAS:**
   - Instead of heavy `synchronized` monitor locks that cause thread-parking and context switching, `TokenBucketRateLimiter` uses `compareAndSet(current, next)`.
   - Threads race to update state atomically at hardware CPU speed.
2. **Monotonic Clock (`System.nanoTime()`):**
   - We avoid `System.currentTimeMillis()` because wall-clock time can jump forward or backward due to NTP synchronization or daylight saving.
   - `System.nanoTime()` is strictly monotonic and counts elapsed nanoseconds accurately.
3. **Java Records (`RateLimitResult`):**
   - Clean, immutable carrier for `allowed`, `remainingTokens`, and `retryAfterNanos`.
4. **Thread-Safe Map (`ConcurrentHashMap`):**
   - In `FlashSaleManager`, each user's rate limiter instance is lazily instantiated and stored safely via `.computeIfAbsent()`.
