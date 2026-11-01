package com.amazon.ratelimiter;

/**
 * Result returned after evaluating a rate limit check.
 * <p>
 * This is a Java 'record' (introduced in modern Java). A record automatically generates:
 * - Private final fields
 * - Constructor
 * - Getters (allowed(), remainingTokens(), retryAfterNanos())
 * - equals(), hashCode(), and toString()
 * It is completely immutable and thread-safe.
 *
 * @param allowed           True if request is permitted to proceed, false if throttled (HTTP 429).
 * @param remainingTokens   Estimated number of requests remaining in current window.
 * @param retryAfterNanos   Nanoseconds the client should wait before attempting again.
 */
public record RateLimitResult(boolean allowed, long remainingTokens, long retryAfterNanos) {

    /**
     * Helper factory method for an allowed result.
     */
    public static RateLimitResult permitted(long remainingTokens) {
        return new RateLimitResult(true, remainingTokens, 0L);
    }

    /**
     * Helper factory method for a rejected result.
     */
    public static RateLimitResult throttled(long retryAfterNanos) {
        return new RateLimitResult(false, 0L, retryAfterNanos);
    }

    /**
     * Returns retry-after in milliseconds for standard HTTP headers (e.g. Retry-After).
     */
    public long retryAfterMillis() {
        return Math.max(1, retryAfterNanos / 1_000_000L);
    }
}

