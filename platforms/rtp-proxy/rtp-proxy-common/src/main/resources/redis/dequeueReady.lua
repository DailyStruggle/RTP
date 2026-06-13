-- rtp-proxy-common cross-server network wait queue: dequeue head.
-- Atomic LPOP of the head correlationId off the ready FIFO, paired with an
-- HGETALL of its envelope HASH and a one-shot transition of the player's
-- status row to ROUTING. The envelope HASH is preserved for the dispatch
-- pipeline; the transition script (transition.lua) deletes it on terminal
-- states. Java callers do their own BLPOP loop on the LIST and only invoke
-- this script after BLPOP returns a non-empty cid.
--
-- KEYS[1] = rtp:net:wq:ready             master FIFO LIST
-- ARGV[1] = nowMs                        (string, integer)
-- ARGV[2] = ttlSeconds                   (string, integer; 0 = no refresh)
--
-- Returns either an empty array {} when the FIFO is empty (race against
-- another worker after BLPOP), or a flat Lua array of alternating field/value
-- strings consisting of: { 'correlationId', C, 'playerId', P, 'regionKey', R,
-- 'serverHint', H, 'createdAtMs', T, 'dequeuedAtMs', N }.
local cid = redis.call('LPOP', KEYS[1])
if not cid then
    return {}
end
local envKey = 'rtp:net:wq:env:' .. cid
local env = redis.call('HGETALL', envKey)
if #env == 0 then
    -- Envelope hash aged out (TTL) between BLPOP and now; treat as empty.
    return {}
end
local pid = ''
local region = ''
local hint = ''
local createdAt = ''
for j = 1, #env, 2 do
    local k = env[j]
    local v = env[j+1]
    if k == 'playerId' then pid = v
    elseif k == 'regionKey' then region = v
    elseif k == 'serverHint' then hint = v
    elseif k == 'createdAtMs' then createdAt = v
    end
end
local nowMs = ARGV[1]
local ttl = tonumber(ARGV[2])
if pid ~= '' then
    local statusKey = 'rtp:net:wq:status:' .. pid
    redis.call('HSET', statusKey,
        'state', 'ROUTING',
        'updatedAtMs', nowMs)
    if ttl > 0 then
        redis.call('EXPIRE', statusKey, ttl)
    end
end
return {
    'correlationId', cid,
    'playerId', pid,
    'regionKey', region,
    'serverHint', hint,
    'createdAtMs', createdAt,
    'dequeuedAtMs', nowMs
}
