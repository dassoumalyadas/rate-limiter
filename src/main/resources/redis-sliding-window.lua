-- ==============================================================================
-- REDIS LUA SCRIPT: Sliding Window Counter Rate Limiter
-- ==============================================================================
-- In production, Redis executes Lua scripts atomically on a single shard.
-- This ensures zero race conditions between checking limits and incrementing.
--
-- KEYS[1] : Base rate limit key identifier (e.g., "ratelimit:checkout:user-123")
-- ARGV[1] : Current epoch timestamp in seconds
-- ARGV[2] : Window size in seconds (e.g. 60 for 1 minute window)
-- ARGV[3] : Max allowed requests in that window (e.g. 10)
-- ARGV[4] : Cost of current operation (e.g. 1)

local base_key = KEYS[1]
local current_time = tonumber(ARGV[1])
local window_size = tonumber(ARGV[2])
local max_requests = tonumber(ARGV[3])
local cost = tonumber(ARGV[4])

-- 1. Determine current window and previous window bucket IDs
local current_window = math.floor(current_time / window_size)
local previous_window = current_window - 1

local current_key = base_key .. ":" .. current_window
local previous_key = base_key .. ":" .. previous_window

-- 2. Read counts from Redis
local current_count = tonumber(redis.call("GET", current_key) or "0")
local prev_count = tonumber(redis.call("GET", previous_key) or "0")

-- 3. Calculate sliding window weight
local time_into_current = current_time % window_size
local previous_weight = (window_size - time_into_current) / window_size

-- 4. Calculate estimated request count
local estimated_requests = math.floor(prev_count * previous_weight) + current_count

-- 5. Rate limit check
if (estimated_requests + cost) > max_requests then
    local retry_after = math.ceil(window_size - time_into_current)
    -- Return array: [0 = REJECTED, remaining_tokens = 0, retry_after_seconds]
    return {0, 0, retry_after}
end

-- 6. Allowed: increment current window and set TTL
redis.call("INCRBY", current_key, cost)
-- Set key to expire in 2 * window_size so it can be read as 'previous_window' later
redis.call("EXPIRE", current_key, window_size * 2)

local remaining = max_requests - (estimated_requests + cost)
-- Return array: [1 = ALLOWED, remaining_tokens, retry_after = 0]
return {1, remaining, 0}

