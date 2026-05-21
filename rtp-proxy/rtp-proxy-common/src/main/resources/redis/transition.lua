-- rtp-proxy-common L6 Slice D3 cross-server network wait queue: state transition.
-- Refines PROPOSAL-cross-server-rtp-L6 Section 4.4 (single-writer transitions).
-- Atomically writes the next QueueState into the player's status HASH. On a
-- terminal state (COMPLETED / FAILED / CANCELLED) the per-correlationId env
-- HASH is deleted and the seen-correlation entry is scrubbed so a fresh
-- enrolment under a new correlationId can run cleanly. Non-terminal
-- transitions only refresh the status row.
--
-- KEYS[1] = rtp:net:wq:status:<playerId>     status HASH
-- KEYS[2] = rtp:net:wq:seen                  correlation-id idempotency SET
-- ARGV[1] = nextState                        (string; QueueState enum name)
-- ARGV[2] = reason                           ('' = no reason)
-- ARGV[3] = serverId                         ('' = absent / unchanged)
-- ARGV[4] = nowMs                            (string, integer)
-- ARGV[5] = ttlSeconds                       (string, integer; 0 = no refresh)
--
-- Returns the resulting HASH contents as a flat alternating field/value
-- array (empty when no status row exists, mirroring pollStatus's evicted
-- sentinel).
local statusKey = KEYS[1]
local exists = redis.call('EXISTS', statusKey)
if exists == 0 then
    return {}
end
local nextState = ARGV[1]
local reason = ARGV[2]
local serverId = ARGV[3]
local nowMs = ARGV[4]
local ttl = tonumber(ARGV[5])
redis.call('HSET', statusKey,
    'state', nextState,
    'updatedAtMs', nowMs)
if reason ~= '' then
    redis.call('HSET', statusKey, 'reason', reason)
end
if serverId ~= '' then
    redis.call('HSET', statusKey, 'serverId', serverId)
end
local terminal = (nextState == 'COMPLETED'
        or nextState == 'FAILED'
        or nextState == 'CANCELLED')
if terminal then
    local cid = redis.call('HGET', statusKey, 'correlationId')
    if cid then
        redis.call('DEL', 'rtp:net:wq:env:' .. cid)
        redis.call('SREM', KEYS[2], cid)
    end
end
if ttl > 0 then
    redis.call('EXPIRE', statusKey, ttl)
end
return redis.call('HGETALL', statusKey)
