-- KEYS[1] = bucket key, e.g. "bucket:42"
-- ARGV[1] = max tokens (rate limit per min)
-- ARGV[2] = refill rate (tokens per second) = max_tokens / 60
-- ARGV[3] = current timestamp in ms

local bucket_key = KEYS[1]
local max_tokens = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

local bucket = redis.call("HMGET", bucket_key, "tokens", "last_refill")
local tokens = tonumber(bucket[1])
local last_refill = tonumber(bucket[2])

if tokens == nil then
    -- first request for this key: bucket starts full
    tokens = max_tokens
    last_refill = now
end

-- refill based on elapsed time since last check
local elapsed_seconds = (now - last_refill) / 1000
local refill_amount = elapsed_seconds * refill_rate
tokens = math.min(max_tokens, tokens + refill_amount)

local allowed = 0
if tokens >= 1 then
    tokens = tokens - 1
    allowed = 1
end

redis.call("HMSET", bucket_key, "tokens", tokens, "last_refill", now)
redis.call("EXPIRE", bucket_key, 3600)  -- cleanup if key goes idle for an hour

return allowed