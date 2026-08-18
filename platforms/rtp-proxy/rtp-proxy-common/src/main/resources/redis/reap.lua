-- rtp-proxy-common reservation-token TTL reap.
-- Refines rtp-proxy-ADR-005 section Reservation Tokens and REQ-RTP-NET-011.
--
-- Scans every rtp:net:tok:<id> HASH, transitions any row whose
-- expiresAtMs <= now AND state IN (PENDING, CLAIMED) to RELEASED, removes
-- the matching rtp:net:tokactive:<playerId> index entry (only when it still
-- points at this tokenId), and returns the list of freshly-reaped tokenIds.
-- Idempotent: a row already in CONSUMED / RELEASED is skipped, so a second
-- reaper sweep against the same store returns an empty array.
--
-- KEYS: none (uses SCAN under EVALSHA - Redis treats the call as full
--       keyspace access; this is acceptable for the reaper because it runs
--       on a single coordinating proxy thread and the result is bounded by
--       the active-token count, not the global keyspace).
-- ARGV[1] = nowEpochMs (string, long)
-- ARGV[2] = tokenKeyPrefix (e.g. "rtp:net:tok:")
-- ARGV[3] = activeIndexKeyPrefix (e.g. "rtp:net:tokactive:")
-- ARGV[4] = scanCount (string, integer hint for SCAN COUNT)
--
-- Returns: array of tokenIds that were transitioned by this call.
local now      = tonumber(ARGV[1])
local prefix   = ARGV[2]
local idxPrefix = ARGV[3]
local scanHint = tonumber(ARGV[4])
if scanHint == nil or scanHint <= 0 then
    scanHint = 100
end

local reaped = {}
local cursor = '0'
repeat
    local res = redis.call('SCAN', cursor, 'MATCH', prefix .. '*', 'COUNT', scanHint)
    cursor = res[1]
    local keys = res[2]
    for i = 1, #keys do
        local key = keys[i]
        local row = redis.call('HMGET', key, 'tokenId', 'playerId', 'state', 'expiresAtMs')
        local tokenId    = row[1]
        local playerId   = row[2]
        local state      = row[3]
        local expiresStr = row[4]
        if tokenId and state and expiresStr and
                (state == 'PENDING' or state == 'CLAIMED') then
            local expiresAt = tonumber(expiresStr)
            if expiresAt and expiresAt <= now then
                redis.call('HSET', key,
                    'state', 'RELEASED',
                    'releasedAtMs', tostring(now))
                if playerId and playerId ~= '' then
                    local indexKey = idxPrefix .. playerId
                    local indexed = redis.call('GET', indexKey)
                    if indexed == tokenId then
                        redis.call('DEL', indexKey)
                    end
                end
                reaped[#reaped + 1] = tokenId
            end
        end
    end
until cursor == '0'
return reaped
