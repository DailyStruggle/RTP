-- rtp-proxy-common: ownership-aware dequeue (rtp-proxy-ADR-016, REQ-RTP-NET-016).
-- Peek-don't-pop: scan up to maxScan head cids; only LREM+return one whose
-- player is owned by this proxy (rtp:net:owner:<uuid> == thisProxyId) or has
-- no live owner at all (tag missing -> legitimately disconnected from every
-- proxy, dispatcher will terminally CANCEL). Foreign-owned cids stay in their
-- FIFO slot so the owning proxy can pull them on its next pulse, preserving
-- enrolment order across the cluster. Aged-out envelope hashes are pruned
-- from the LIST in passing.
--
-- KEYS[1] = rtp:net:wq:ready                FIFO LIST of correlationIds
-- ARGV[1] = thisProxyId                     (string, non-empty)
-- ARGV[2] = nowMs                           (string, integer)
-- ARGV[3] = ttlSeconds                      (string, integer; 0 = no refresh)
-- ARGV[4] = maxScan                         (string, integer; head window cap)
--
-- Returns either {} (empty queue, or no eligible head within scan window), or
-- the same alternating field/value array dequeueReady.lua returns plus an
-- extra 'ownerProxy' field carrying the resolved owner ('' when absent).
local readyKey = KEYS[1]
local thisProxy = ARGV[1]
local nowMs = ARGV[2]
local ttl = tonumber(ARGV[3])
local maxScan = tonumber(ARGV[4])
if not maxScan or maxScan < 1 then maxScan = 16 end

local cids = redis.call('LRANGE', readyKey, 0, maxScan - 1)
if #cids == 0 then return {} end

for idx = 1, #cids do
    local cid = cids[idx]
    local envKey = 'rtp:net:wq:env:' .. cid
    local env = redis.call('HGETALL', envKey)
    if #env == 0 then
        -- Aged-out envelope hash; punt this cid from the LIST and continue.
        redis.call('LREM', readyKey, 1, cid)
    else
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
        local owner = ''
        if pid ~= '' then
            local ownerKey = 'rtp:net:owner:' .. pid
            local got = redis.call('GET', ownerKey)
            if got then owner = got end
        end
        -- Eligible iff: owner missing (player disconnected from every proxy
        -- -> dispatcher will CANCEL legitimately) OR owner == thisProxy.
        if owner == '' or owner == thisProxy then
            redis.call('LREM', readyKey, 1, cid)
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
                'dequeuedAtMs', nowMs,
                'ownerProxy', owner
            }
        end
        -- Foreign-owned: leave it in place; try next head cid.
    end
end

return {}
