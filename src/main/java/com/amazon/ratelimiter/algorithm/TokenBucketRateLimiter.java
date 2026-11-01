package com.amazon.ratelimiter.algorithm;

import com.amazon.ratelimiter.RateLimitResult;
import com.amazon.ratelimiter.RateLimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * High-performance, lock-free Token Bucket Rate Limiter.
 * <p>
 * <h3>How Token Bucket Works:</h3>
 * Imagine a bucket that can hold at most {@code capacity} tokens.
 * Tokens are poured into the bucket at a constant rate {@code refillTokensPerSecond}.
 * Each incoming request attempts to consume 1 (or N) tokens:
 * - If sufficient tokens exist: the request takes them and proceeds.
 * - If not enough tokens: the request is throttled immediately.
 * <p>
 * <h3>Why this is High-Performance for Java Concurrency:</h3>
 * 1. Traditional implementations use {@code synchronized} blocks. Under 50,000 requests/sec,
 *    threads queue on monitor locks, leading to heavy thread context-switching overhead.
 * 2. This implementation uses <b>Non-Blocking Compare-And-Set (CAS)</b> via {@link AtomicReference}.
 *    Threads never sleep or context switch; they race to update the state atomically.
 * 3. Uses {@link System#nanoTime()} (monotonic clock) instead of {@link System#currentTimeMillis()}
 *    (wall-clock), protecting against system clock synchronization (NTP) jumps.
 */
public class TokenBucketRateLimiter implements RateLimiter {

    private final long capacity;
    private final double refillTokensPerNano;
    private final AtomicReference<BucketState> state;

    /**
     * Immutable state holder for atomic updates.
     * All fields are 'final' to ensure visibility across CPU memory caches without locks.
     */
    private static class BucketState {
        final long availableTokens;
        final long lastRefillNanos;

        BucketState(long availableTokens, long lastRefillNanos) {
            this.availableTokens = availableTokens;
            this.lastRefillNanos = lastRefillNanos;
        }
    }

    /**
     * Creates a new TokenBucketRateLimiter.
     *
     * @param capacity     Maximum burst capacity of the bucket (e.g. 10 tokens).
     * @param refillTokens Number of tokens refilled every period (e.g. 5 tokens).
     * @param unit         Time period unit for the refill rate (e.g. 1 second).
     */
    public TokenBucketRateLimiter(long capacity, long refillTokens, TimeUnit unit) {
        if (capacity <= 0 || refillTokens <= 0) {
            throw new IllegalArgumentException("Capacity and refill tokens must be strictly positive");
        }
        this.capacity = capacity;
        this.refillTokensPerNano = (double) refillTokens / unit.toNanos(1);
        // Initially, the bucket starts full
        this.state = new AtomicReference<>(new BucketState(capacity, System.nanoTime()));
    }

    @Override
    public RateLimitResult tryAcquire(long tokens) {
        if (tokens <= 0) {
            return RateLimitResult.permitted(capacity);
        }
        if (tokens > capacity) {
            // A request requiring more tokens than bucket capacity can never succeed
            return RateLimitResult.throttled(Long.MAX_VALUE);
        }

        // Lock-free CAS retry loop
        while (true) {
            long now = System.nanoTime();
            BucketState current = state.get();

            // 1. Calculate how many tokens refilled since the last interaction
            long elapsedNanos = Math.max(0, now - current.lastRefillNanos);
            long newTokens = (long) (elapsedNanos * refillTokensPerNano);

            // 2. Cap tokens at bucket capacity to prevent overflow
            long currentTokens = Math.min(capacity, current.availableTokens + newTokens);

            // 3. Check if we have enough tokens to satisfy the request
            if (currentTokens < tokens) {
                // Calculate how long the client must wait for the missing tokens to generate
                long neededTokens = tokens - currentTokens;
                long waitNanos = (long) Math.ceil(neededTokens / refillTokensPerNano);
                return RateLimitResult.throttled(waitNanos);
            }

            // 4. If tokens were added, advance lastRefillNanos. If zero new tokens refilled,
            // keep the previous timestamp so fractional nanoseconds accumulate correctly.
            long updatedRefillNanos = (newTokens > 0) ? now : current.lastRefillNanos;
            BucketState next = new BucketState(currentTokens - tokens, updatedRefillNanos);

            // 5. Compare-And-Set: If another thread modified 'state' in the interim,
            // compareAndSet returns false, and we loop again with fresh state.
            if (state.compareAndSet(current, next)) {
                return RateLimitResult.permitted(next.availableTokens);
            }
        }
    }

    @Override
    public String getAlgorithmName() {
        return "Lock-Free Token Bucket";
    }

    public long getCapacity() {
        return capacity;
    }
}
