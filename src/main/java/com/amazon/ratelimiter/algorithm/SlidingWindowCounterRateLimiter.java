package com.amazon.ratelimiter.algorithm;

import com.amazon.ratelimiter.RateLimitResult;
import com.amazon.ratelimiter.RateLimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * High-performance Sliding Window Counter Rate Limiter.
 * <p>
 * <h3>Why Sliding Window Counter?</h3>
 * Fixed Window counters suffer from the <b>Boundary Burst Issue</b>: if a limit is 100 req/min,
 * a user could fire 100 requests at 00:59 and another 100 requests at 01:01, totaling 200 requests
 * in a 2-second span (violating the spirit of rate limiting).
 * <p>
 * Sliding Window Counter solves this by weighting the previous window's count:
 * <pre>
 *   EstimatedRequests = (PreviousWindowCount * (1 - CurrentWindowProgress)) + CurrentWindowCount
 * </pre>
 * Memory footprint is minimal ($O(1)$) compared to Sliding Window Log ($O(N)$).
 */
public class SlidingWindowCounterRateLimiter implements RateLimiter {

    private final long maxRequests;
    private final long windowNanos;

    private long currentWindowId;
    private long previousWindowCount;
    private long currentWindowCount;

    // A lightweight lock for updating the two internal window counters
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * @param maxRequests Maximum requests permitted across the sliding window.
     * @param duration    Window duration magnitude (e.g., 10).
     * @param unit        Time unit (e.g., TimeUnit.SECONDS).
     */
    public SlidingWindowCounterRateLimiter(long maxRequests, long duration, TimeUnit unit) {
        if (maxRequests <= 0 || duration <= 0) {
            throw new IllegalArgumentException("Parameters must be strictly positive");
        }
        this.maxRequests = maxRequests;
        this.windowNanos = unit.toNanos(duration);
        this.currentWindowId = System.nanoTime() / this.windowNanos;
        this.previousWindowCount = 0;
        this.currentWindowCount = 0;
    }

    @Override
    public RateLimitResult tryAcquire(long tokens) {
        if (tokens <= 0) return RateLimitResult.permitted(maxRequests);
        if (tokens > maxRequests) return RateLimitResult.throttled(windowNanos);

        lock.lock();
        try {
            long now = System.nanoTime();
            long nowWindowId = now / windowNanos;

            // Slide window forward if time has moved beyond the current window
            if (nowWindowId > currentWindowId) {
                long windowJump = nowWindowId - currentWindowId;
                if (windowJump == 1) {
                    // Exactly 1 window elapsed: current becomes previous
                    previousWindowCount = currentWindowCount;
                } else {
                    // More than 1 window elapsed: previous expired completely
                    previousWindowCount = 0;
                }
                currentWindowCount = 0;
                currentWindowId = nowWindowId;
            }

            // Calculate progress through current window (0.0 to 1.0)
            long timeIntoWindow = now % windowNanos;
            double previousWeight = (double) (windowNanos - timeIntoWindow) / windowNanos;

            // Estimated request count in the last sliding window
            long estimatedRequests = (long) (previousWindowCount * previousWeight) + currentWindowCount;

            if (estimatedRequests + tokens > maxRequests) {
                long waitNanos = windowNanos - timeIntoWindow;
                return RateLimitResult.throttled(waitNanos);
            }

            // Permitted: increment current window counter
            currentWindowCount += tokens;
            long remaining = Math.max(0, maxRequests - (estimatedRequests + tokens));
            return RateLimitResult.permitted(remaining);

        } finally {
            lock.unlock();
        }
    }

    @Override
    public String getAlgorithmName() {
        return "Sliding Window Counter";
    }

    public long getMaxRequests() {
        return maxRequests;
    }
}

