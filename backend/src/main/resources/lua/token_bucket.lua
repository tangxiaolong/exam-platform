-- 分布式令牌桶（原子操作）
-- KEYS[1] 桶 key
-- ARGV[1] capacity 突发上限
-- ARGV[2] refillTokens 每个周期补充的令牌数
-- ARGV[3] refillPeriodMs 补充周期（毫秒）
--
-- 返回 {allowed, remaining, retryAfterMs}
--
-- 时间一律取 Redis 服务端 TIME，而不是各实例的本机时钟：
-- 多实例时钟漂移会让同一个桶在不同节点算出不同的补充量，配额就失真了。

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refillTokens = tonumber(ARGV[2])
local periodMs = tonumber(ARGV[3])

if capacity == nil or capacity < 1 then capacity = 1 end
if refillTokens == nil or refillTokens < 1 then refillTokens = 1 end
if periodMs == nil or periodMs < 1 then periodMs = 1000 end

local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local data = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts = tonumber(data[2])

if tokens == nil or ts == nil then
  tokens = capacity
  ts = now
end

local elapsed = now - ts
if elapsed < 0 then
  -- 时钟回拨（或 key 由别处写入），以当前时间为准重新起算
  elapsed = 0
  ts = now
end

if elapsed >= periodMs then
  local periods = math.floor(elapsed / periodMs)
  local replenished = refillTokens * periods
  tokens = math.min(capacity, tokens + replenished)
  ts = ts + periods * periodMs
end

local allowed = 0
local retryAfter = 0

if tokens > 0 then
  tokens = tokens - 1
  allowed = 1
else
  retryAfter = periodMs - (now - ts)
  if retryAfter <= 0 then retryAfter = periodMs end
end

redis.call('HSET', key, 'tokens', tokens, 'ts', ts)
-- TTL 保留两个周期：空闲桶自动回收，且不会在补充边界前被误删
redis.call('PEXPIRE', key, periodMs * 2)

return {allowed, tokens, math.floor(retryAfter)}
