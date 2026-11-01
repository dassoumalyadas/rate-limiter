package com.amazon.ratelimiter.flashsale;

import com.amazon.ratelimiter.RateLimitResult;
import com.amazon.ratelimiter.RateLimiter;
import com.amazon.ratelimiter.algorithm.TokenBucketRateLimiter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Orchestrates the end-to-end Flash Sale checkout workflow.
 * <p>
 * Combines two critical defensive layers:
 * 1. <b>Per-User Velocity Rate Limiting:</b> Stops aggressive bots and rapid clickers.
 * 2. <b>Atomic Stock Reservation:</b> Emulates a high-speed Redis DECR operation to protect
 *    downstream databases from lock contention.
 */
public class FlashSaleManager {

    private final String productId;
    private final AtomicInteger remainingStock;

    // Per-user rate limiters kept in a thread-safe ConcurrentHashMap
    private final ConcurrentHashMap<String, RateLimiter> userLimiters = new ConcurrentHashMap<>();

    // Metrics for observability
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong throttledRequests = new AtomicLong(0);
    private final AtomicLong successfulOrders = new AtomicLong(0);
    private final AtomicLong soldOutRejections = new AtomicLong(0);

    public enum CheckoutStatus {
        SUCCESS_ORDER_PLACED,
        RATE_LIMITED_429,
        SOLD_OUT_410
    }

    public record CheckoutResponse(
        CheckoutStatus status,
        String message,
        long remainingStock,
        long retryAfterMillis
    ) {}

    public FlashSaleManager(String productId, int initialStock) {
        this.productId = productId;
        this.remainingStock = new AtomicInteger(initialStock);
    }

    /**
     * Executes the checkout pipeline for a given user.
     *
     * @param userId The unique identifier for the customer
     * @return {@link CheckoutResponse} indicating success or rejection reason
     */
    public CheckoutResponse processCheckout(String userId) {
        totalRequests.incrementAndGet();

        // -------------------------------------------------------------
        // LAYER 1: Per-User Rate Limiting (Token Bucket)
        // Rule: Each user can burst up to 2 attempts, refilling 1 per 3 seconds.
        // -------------------------------------------------------------
        RateLimiter limiter = userLimiters.computeIfAbsent(userId, 
            k -> new TokenBucketRateLimiter(2, 1, TimeUnit.SECONDS));

        RateLimitResult rateResult = limiter.tryAcquire();
        if (!rateResult.allowed()) {
            throttledRequests.incrementAndGet();
            return new CheckoutResponse(
                CheckoutStatus.RATE_LIMITED_429,
                "HTTP 429 Too Many Requests: Please slow down.",
                remainingStock.get(),
                rateResult.retryAfterMillis()
            );
        }

        // -------------------------------------------------------------
        // LAYER 2: Atomic Stock Reservation
        // Mimics Redis atomic DECR script. If stock > 0, decrement and grant permit.
        // Zero database locks needed at this stage!
        // -------------------------------------------------------------
        int updatedStock = remainingStock.decrementAndGet();
        if (updatedStock >= 0) {
            successfulOrders.incrementAndGet();
            return new CheckoutResponse(
                CheckoutStatus.SUCCESS_ORDER_PLACED,
                "Order confirmed for product " + productId,
                updatedStock,
                0L
            );
        } else {
            // Revert decrement so counter doesn't spiral into negative values
            remainingStock.incrementAndGet();
            soldOutRejections.incrementAndGet();
            return new CheckoutResponse(
                CheckoutStatus.SOLD_OUT_410,
                "Item is sold out.",
                0L,
                0L
            );
        }
    }

    // Getters for inspection & metrics
    public long getTotalRequests() { return totalRequests.get(); }
    public long getThrottledRequests() { return throttledRequests.get(); }
    public long getSuccessfulOrders() { return successfulOrders.get(); }
    public long getSoldOutRejections() { return soldOutRejections.get(); }
    public int getRemainingStock() { return remainingStock.get(); }
    public String getProductId() { return productId; }
}

