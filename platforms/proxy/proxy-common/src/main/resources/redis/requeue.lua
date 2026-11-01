-- rtp-proxy-common: return a popped envelope to the ready FIFO (rtp-proxy-ADR-016).
-- A proxy that pops an envelope for a player it does not hold hands it back
-- instead of cancelling it, so the proxy holding the session can dispatch it.
-- Conditional: only when the envelope hash still exists and the player's
-- status row still names this cid in ROUTING (the state set at dequeue). A
-- cancel, terminal transition, or newer enrolment in between makes this a
-- no-op, so a stale hand-back never resurrects or duplicates a request.
--
-- KEYS[1] = rtp:net:wq:ready                FIFO LIST of correlationIds
-- ARGV[1] = correlationId
-- ARGV[2] = playerId
-- ARGV[3] = nowMs                           (string, integer)
-- ARGV[4] = ttlSeconds                      (string, integer; 0 = no refresh)
--
-- Returns 1 when requeued, 0 otherwise.
local PENDING_TTL = 300
local readyKey = KEYS[1]
local cid = ARGV[1]
local pid = ARGV[2]
local nowMs = ARGV[3]
local ttl = tonumber(ARGV[4])

if redis.call('EXISTS', 'rtp:net:wq:env:' .. cid) == 0 then return 0 end
local statusKey = 'rtp:net:wq:status:' .. pid
local status = redis.call('HMGET', statusKey, 'correlationId', 'state')
if status[1] ~= cid or status[2] ~= 'ROUTING' then return 0 end

redis.call('HSET', statusKey, 'state', 'QUEUED', 'updatedAtMs', nowMs)
if ttl and ttl > 0 then
    redis.call('EXPIRE', statusKey, ttl)
end
local pendingKey = 'rtp:net:wq:pending:' .. pid
local pendingCid = redis.call('GET', pendingKey)
if not pendingCid or pendingCid == cid then
    redis.call('SET', pendingKey, cid, 'EX', PENDING_TTL)
end
redis.call('RPUSH', readyKey, cid)
return 1
