package com.amazon.ratelimiter.flashsale;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/**
 * Multi-threaded simulation of an Amazon-scale Flash Sale.
 * <p>
 * Simulates a realistic drop scenario:
 * - Product: Limited Edition Gaming Console (100 units available)
 * - 200 Normal Users (each making 1-2 normal clicks)
 * - 10 Aggressive Bot Users (each hammering the checkout API 50 times continuously)
 * - All threads align at a starting gate (CountDownLatch) to strike simultaneously at t=0.
 */
public class FlashSaleSimulationDemo {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("======================================================================");
        System.out.println("      AMAZON FLASH SALE CONCURRENCY & RATE LIMITER SIMULATION         ");
        System.out.println("======================================================================\n");

        final int INITIAL_STOCK = 100;
        final FlashSaleManager saleManager = new FlashSaleManager("PROD-PS5-LIMITED", INITIAL_STOCK);

        final int NORMAL_USER_COUNT = 200;
        final int BOT_USER_COUNT = 10;
        final int BOT_SPAM_PER_USER = 50;

        // Total requests = 200 * 2 + 10 * 50 = 400 + 500 = 900 requests
        int totalRequests = (NORMAL_USER_COUNT * 2) + (BOT_USER_COUNT * BOT_SPAM_PER_USER);

        System.out.printf("Starting Flash Sale for [%s]%n", saleManager.getProductId());
        System.out.printf("Initial Stock: %d units%n", INITIAL_STOCK);
        System.out.printf("Traffic Profile: %d normal shoppers + %d aggressive bots%n", NORMAL_USER_COUNT, BOT_USER_COUNT);
        System.out.printf("Total incoming concurrent requests: %d%n%n", totalRequests);

        ExecutorService executor = Executors.newFixedThreadPool(64);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(totalRequests);

        List<String> requestAuditLog = Collections.synchronizedList(new ArrayList<>());

        // 1. Prepare Normal Users (1-2 attempts each)
        for (int i = 1; i <= NORMAL_USER_COUNT; i++) {
            final String userId = "user-" + i;
            for (int attempt = 1; attempt <= 2; attempt++) {
                final int attemptNum = attempt;
                executor.submit(() -> {
                    try {
                        startGate.await(); // Wait for sale to open
                        FlashSaleManager.CheckoutResponse response = saleManager.processCheckout(userId);
                        if (response.status() == FlashSaleManager.CheckoutStatus.SUCCESS_ORDER_PLACED) {
                            requestAuditLog.add(String.format("[ORDER CONFIRMED] %s (Attempt %d) got item! Remaining: %d", 
                                    userId, attemptNum, response.remainingStock()));
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        completionLatch.countDown();
                    }
                });
            }
        }

        // 2. Prepare Aggressive Bots (50 rapid calls each)
        for (int b = 1; b <= BOT_USER_COUNT; b++) {
            final String botId = "bot-" + b;
            for (int r = 1; r <= BOT_SPAM_PER_USER; r++) {
                executor.submit(() -> {
                    try {
                        startGate.await(); // Strike simultaneously
                        FlashSaleManager.CheckoutResponse response = saleManager.processCheckout(botId);
                        if (response.status() == FlashSaleManager.CheckoutStatus.SUCCESS_ORDER_PLACED) {
                            requestAuditLog.add(String.format("[ORDER CONFIRMED] %s grabbed an item! Remaining: %d", 
                                    botId, response.remainingStock()));
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        completionLatch.countDown();
                    }
                });
            }
        }

        System.out.println(">>> 3, 2, 1... Flash sale gates OPEN! Thundering herd hitting backend...");
        long startTime = System.currentTimeMillis();
        startGate.countDown(); // Release the gate!

        // Wait for all threads to finish processing
        boolean finishedInTime = completionLatch.await(10, TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - startTime;
        executor.shutdown();

        System.out.println("\n======================================================================");
        System.out.println("                         SIMULATION RESULTS                           ");
        System.out.println("======================================================================");
        System.out.printf("Execution Time         : %d ms%n", duration);
        System.out.printf("Total Requests Handled : %d%n", saleManager.getTotalRequests());
        System.out.printf("Rate-Limited (HTTP 429): %d (Throttled by Token Bucket)%n", saleManager.getThrottledRequests());
        System.out.printf("Successful Orders (200): %d (Inventory reserved)%n", saleManager.getSuccessfulOrders());
        System.out.printf("Sold Out Hits (HTTP 410): %d (Deflected before DB)%n", saleManager.getSoldOutRejections());
        System.out.printf("Remaining Stock in DB  : %d%n", saleManager.getRemainingStock());
        System.out.println("----------------------------------------------------------------------");

        if (saleManager.getSuccessfulOrders() == INITIAL_STOCK && saleManager.getRemainingStock() == 0) {
            System.out.println(">>> [SUCCESS] Zero overselling detected! Exactly 100 units sold.");
            System.out.println(">>> Rate limiter suppressed bot spam, and inventory stayed consistent.");
        } else {
            System.err.println(">>> [ERROR] Inconsistency detected! Check concurrency controls.");
        }
        System.out.println("======================================================================\n");
    }
}

