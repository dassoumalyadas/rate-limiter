package com.amazon.ratelimiter.algorithm;

import com.amazon.ratelimiter.RateLimitResult;
import com.amazon.ratelimiter.RateLimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Educational Baseline: Fixed Window Counter Rate Limiter.
 * <p>
 * Demonstrates the simplest form of rate limiting:
 * A single counter that resets at the boundary of every time window.
 * <p>
 * <b>Known limitation (Boundary Spike):</b> If capacity is 10 req/minute, a user can execute
 * 10 requests at 00:59 and 10 requests at 01:00, pushing 20 requests in 2 seconds.
 */
public class FixedWindowRateLimiter implements RateLimiter {

    private final long maxRequests;
    private final long windowNanos;

    private volatile long currentWindowId;
    private final AtomicLong counter = new AtomicLong(0);

    public FixedWindowRateLimiter(long maxRequests, long duration, TimeUnit unit) {
        if (maxRequests <= 0 || duration <= 0) {
            throw new IllegalArgumentException("Parameters must be strictly positive");
        }
        this.maxRequests = maxRequests;
        this.windowNanos = unit.toNanos(duration);
        this.currentWindowId = System.nanoTime() / this.windowNanos;
    }

    @Override
    public synchronized RateLimitResult tryAcquire(long tokens) {
        if (tokens <= 0) return RateLimitResult.permitted(maxRequests);
        if (tokens > maxRequests) return RateLimitResult.throttled(windowNanos);

        long now = System.nanoTime();
        long windowId = now / windowNanos;

        // Reset if we crossed into a new window
        if (windowId > currentWindowId) {
            currentWindowId = windowId;
            counter.set(0);
        }

        long currentCount = counter.get();
        if (currentCount + tokens > maxRequests) {
            long remainingNanosInWindow = windowNanos - (now % windowNanos);
            return RateLimitResult.throttled(remainingNanosInWindow);
        }

        long updated = counter.addAndGet(tokens);
        return RateLimitResult.permitted(maxRequests - updated);
    }

    @Override
    public String getAlgorithmName() {
        return "Fixed Window Counter";
    }
}
