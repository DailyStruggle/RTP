-- rtp-proxy-common cross-server network wait queue: batch enrol.
-- Atomic per-batch: each envelope is appended exactly once to the master
-- ready FIFO, materialised into a per-correlationId env HASH, and reflected
-- in the per-player status HASH. The seen SET enforces correlation-id
-- idempotency so a retried flush is a no-op.
--
-- KEYS[1] = rtp:net:wq:ready              master FIFO LIST (correlationIds)
-- KEYS[2] = rtp:net:wq:seen               correlation-id idempotency SET
--
-- ARGV layout (repeating 7-tuple per envelope):
--   ARGV[i+0] = correlationId
--   ARGV[i+1] = playerId
--   ARGV[i+2] = regionKey            ('' = absent)
--   ARGV[i+3] = serverHint           ('' = absent)
--   ARGV[i+4] = createdAtMs          (string, integer)
--   ARGV[i+5] = ttlSeconds           (string, integer; 0 = no expire)
--   ARGV[i+6] = nowMs                (string, integer; updatedAtMs seed)
--
-- Returns the number of envelopes that were actually enqueued (duplicates
-- under the seen guard are silently skipped; the caller observes
-- EnrolOutcome.ACCEPTED for the batch as a whole).
local seenKey = KEYS[2]
local readyKey = KEYS[1]
local accepted = 0
local i = 1
while i <= #ARGV do
    local cid = ARGV[i+0]
    local addedNow = redis.call('SADD', seenKey, cid)
    if addedNow == 1 then
        local pid = ARGV[i+1]
        local region = ARGV[i+2]
        local hint = ARGV[i+3]
        local createdAt = ARGV[i+4]
        local ttl = tonumber(ARGV[i+5])
        local nowMs = ARGV[i+6]
        local envKey = 'rtp:net:wq:env:' .. cid
        redis.call('HSET', envKey,
            'correlationId', cid,
            'playerId', pid,
            'regionKey', region,
            'serverHint', hint,
            'createdAtMs', createdAt)
        if ttl > 0 then
            redis.call('EXPIRE', envKey, ttl)
        end
        local statusKey = 'rtp:net:wq:status:' .. pid
        redis.call('HSET', statusKey,
            'playerId', pid,
            'correlationId', cid,
            'state', 'QUEUED',
            'regionKey', region,
            'serverId', '',
            'updatedAtMs', nowMs)
        if ttl > 0 then
            redis.call('EXPIRE', statusKey, ttl)
        end
        redis.call('RPUSH', readyKey, cid)
        accepted = accepted + 1
    end
    i = i + 7
end
return accepted
