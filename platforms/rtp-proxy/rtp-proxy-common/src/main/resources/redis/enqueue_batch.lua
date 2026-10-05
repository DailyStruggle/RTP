-- rtp-proxy-common cross-server network wait queue: batch enrol.
-- Atomic per-batch: each envelope is appended exactly once to the master
-- ready FIFO, materialised into a per-correlationId env HASH, and reflected
-- in the per-player status HASH. The seen SET enforces correlation-id
-- idempotency so a retried flush is a no-op. The per-player pending marker
-- (rtp:net:wq:pending:<pid>, holds the cid, PENDING_TTL safety expiry)
-- caps each player at one undequeued envelope; a second cid for the same
-- player is skipped and counted as a duplicate.
--
-- KEYS[1] = rtp:net:wq:ready              master FIFO LIST (correlationIds)
-- KEYS[2] = rtp:net:wq:seen               correlation-id idempotency SET
--
-- ARGV layout (repeating 8-tuple per envelope):
--   ARGV[i+0] = correlationId
--   ARGV[i+1] = playerId
--   ARGV[i+2] = regionKey            ('' = absent)
--   ARGV[i+3] = serverHint           ('' = absent)
--   ARGV[i+4] = createdAtMs          (string, integer)
--   ARGV[i+5] = ttlSeconds           (string, integer; 0 = no expire)
--   ARGV[i+6] = nowMs                (string, integer; updatedAtMs seed)
--   ARGV[i+7] = hmacHex              ('' when the verifier is disabled; stored
--                                     opaquely, verified Java-side on dequeue)
--
-- Returns { accepted, duplicates }: accepted = envelopes actually enqueued;
-- duplicates = envelopes skipped because the player already had a pending
-- envelope under a different cid. Correlation-id replays count as neither.
local PENDING_TTL = 300
local seenKey = KEYS[2]
local readyKey = KEYS[1]
local accepted = 0
local duplicates = 0
local i = 1
while i <= #ARGV do
    local cid = ARGV[i+0]
    local pid = ARGV[i+1]
    local pendingKey = 'rtp:net:wq:pending:' .. pid
    local pendingCid = redis.call('GET', pendingKey)
    if pendingCid and pendingCid ~= cid then
        duplicates = duplicates + 1
    elseif redis.call('SADD', seenKey, cid) == 1 then
        local region = ARGV[i+2]
        local hint = ARGV[i+3]
        local createdAt = ARGV[i+4]
        local ttl = tonumber(ARGV[i+5])
        local nowMs = ARGV[i+6]
        local hmac = ARGV[i+7]
        if hmac == nil then hmac = '' end
        local envKey = 'rtp:net:wq:env:' .. cid
        redis.call('HSET', envKey,
            'correlationId', cid,
            'playerId', pid,
            'regionKey', region,
            'serverHint', hint,
            'createdAtMs', createdAt,
            'hmac', hmac)
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
        redis.call('SET', pendingKey, cid, 'EX', PENDING_TTL)
        redis.call('RPUSH', readyKey, cid)
        accepted = accepted + 1
    end
    i = i + 8
end
return { accepted, duplicates }
