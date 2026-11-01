package com.amazon.ratelimiter;

import com.amazon.ratelimiter.algorithm.SlidingWindowCounterRateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SlidingWindowCounterRateLimiterTest {

    @Test
    @DisplayName("Should throttle after window limit reached")
    void testSlidingWindowLimit() {
        // Max 3 requests per 1 second
        SlidingWindowCounterRateLimiter limiter = new SlidingWindowCounterRateLimiter(3, 1, TimeUnit.SECONDS);

        assertTrue(limiter.tryAcquire().allowed());
        assertTrue(limiter.tryAcquire().allowed());
        assertTrue(limiter.tryAcquire().allowed());

        RateLimitResult fourth = limiter.tryAcquire();
        assertFalse(fourth.allowed(), "4th request should exceed 3 req/sec limit");
        assertTrue(fourth.retryAfterNanos() > 0);
    }

    @Test
    @DisplayName("Should reject single request exceeding total capacity")
    void testExcessiveTokenRequest() {
        SlidingWindowCounterRateLimiter limiter = new SlidingWindowCounterRateLimiter(5, 1, TimeUnit.SECONDS);

        RateLimitResult result = limiter.tryAcquire(10);
        assertFalse(result.allowed(), "Requesting 10 permits from a 5-permit window must fail");
    }
}
