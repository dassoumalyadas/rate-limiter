package com.amazon.ratelimiter;

import com.amazon.ratelimiter.algorithm.TokenBucketRateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    @Test
    @DisplayName("Should allow burst requests up to capacity")
    void testCapacityBurst() {
        // Capacity: 5 tokens, refills 1 token per second
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 1, TimeUnit.SECONDS);

        for (int i = 0; i < 5; i++) {
            RateLimitResult result = limiter.tryAcquire();
            assertTrue(result.allowed(), "Request " + (i + 1) + " should be allowed");
        }

        // 6th request should fail
        RateLimitResult throttled = limiter.tryAcquire();
        assertFalse(throttled.allowed(), "6th request should exceed capacity and be throttled");
        assertTrue(throttled.retryAfterNanos() > 0, "Retry wait time should be positive");
    }

    @Test
    @DisplayName("Should refill tokens over time")
    void testTokenRefill() throws InterruptedException {
        // 2 tokens capacity, refills 10 tokens per second (1 token per 100ms)
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, 10, TimeUnit.SECONDS);

        assertTrue(limiter.tryAcquire().allowed());
        assertTrue(limiter.tryAcquire().allowed());
        assertFalse(limiter.tryAcquire().allowed(), "Should be empty now");

        // Wait 250 milliseconds (should refill at least 2 tokens)
        Thread.sleep(250);

        assertTrue(limiter.tryAcquire().allowed(), "Should have refilled at least 1 token");
    }

    @Test
    @DisplayName("Should remain thread-safe under massive concurrent load")
    void testConcurrentThreadSafety() throws InterruptedException {
        int capacity = 50;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, 1, TimeUnit.MINUTES);

        int threadCount = 20;
        int requestsPerThread = 10; // Total 200 requests
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger allowedCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    for (int j = 0; j < requestsPerThread; j++) {
                        if (limiter.tryAcquire().allowed()) {
                            allowedCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ignored) {}
            });
        }

        latch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Exactly 50 permits can be consumed from the initial bucket!
        assertEquals(capacity, allowedCount.get(), "Must permit exactly bucket capacity under race conditions");
    }
}

