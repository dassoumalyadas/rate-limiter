package com.amazon.ratelimiter;

/**
 * Common contract for all Rate Limiter algorithms.
 * <p>
 * In Java, an 'interface' defines a set of methods that implementing classes must fulfill.
 * This allows polymorphism: our application can switch between TokenBucket, SlidingWindow,
 * or Redis-based limiters without altering the calling code.
 */
public interface RateLimiter {

    /**
     * Attempts to acquire 1 token/permit for a single operation.
     *
     * @return {@link RateLimitResult} detailing whether the request was allowed,
     *         tokens remaining, and retry wait time if rejected.
     */
    default RateLimitResult tryAcquire() {
        return tryAcquire(1);
    }

    /**
     * Attempts to acquire a specified number of tokens.
     *
     * @param tokens Number of permits required (cost of the operation).
     * @return {@link RateLimitResult} containing admission verdict and metadata.
     */
    RateLimitResult tryAcquire(long tokens);

    /**
     * Returns the name of the rate limiting algorithm in use.
     */
    String getAlgorithmName();
}
