package com.amazon.ratelimiter.distributed;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Loads and provides the production Redis Lua script for distributed rate limiting.
 * <p>
 * In large systems like Amazon, multiple API Gateway / backend instances process traffic.
 * Local memory cannot share state across 100 servers; therefore, rate limiting state
 * is kept in a centralized Redis Cluster.
 * <p>
 * This class loads the atomic Lua script and documents how to invoke it from Java.
 */
public class RedisSlidingWindowScript {

    private static final String SCRIPT_RESOURCE_PATH = "/redis-sliding-window.lua";
    private static final String SCRIPT_CONTENT;

    static {
        try (InputStream is = RedisSlidingWindowScript.class.getResourceAsStream(SCRIPT_RESOURCE_PATH)) {
            if (is == null) {
                throw new IllegalStateException("Could not find " + SCRIPT_RESOURCE_PATH + " on classpath");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                SCRIPT_CONTENT = reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            throw new ExceptionInInitializerError("Failed to initialize Redis script: " + e.getMessage());
        }
    }

    /**
     * Returns the raw Lua script string ready to be passed to Redis evalsha / eval.
     */
    public static String getLuaScript() {
        return SCRIPT_CONTENT;
    }
}

