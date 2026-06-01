-- rtp-proxy-common L2 cross-server RTP atomic redeem.
-- Refines rtp-proxy-ADR-005. Single-call CLAIMED -> CONSUMED transition for
-- the backend's join-time redeem path. Row-count-atomic: the loser of a race
-- with a peer redeem (or a TTL reap) observes a non-success return and the
-- caller's JoinTriggerSource stays silent / emits the configured message.
--
-- KEYS[1] = rtp:net:tok:<tokenId>             token HASH key
-- KEYS[2] = rtp:net:tokactive:<playerId>      active-player index
-- ARGV[1] = tokenId
-- ARGV[2] = playerId (uuid string)
-- ARGV[3] = expectedServerId
-- ARGV[4] = consumedAtEpochMs (string)
-- ARGV[5] = nowEpochMs (string, for expiry comparison)
--
-- Returns one of (strings, never nil):
--   "REDEEMED"          state transitioned CLAIMED -> CONSUMED on this call
--   "NOT_FOUND"         no row at KEYS[1] (reaped or never persisted)
--   "WRONG_SERVER"      row exists but serverId or playerId mismatch
--   "ALREADY_CONSUMED"  state was already CONSUMED or RELEASED
--   "EXPIRED"           expiresAtMs <= nowEpochMs and state not yet terminal
--   "BAD_STATE"         state is something other than the expected set
if redis.call('EXISTS', KEYS[1]) == 0 then
    return 'NOT_FOUND'
end
local serverId = redis.call('HGET', KEYS[1], 'serverId')
local playerId = redis.call('HGET', KEYS[1], 'playerId')
if serverId ~= ARGV[3] or playerId ~= ARGV[2] then
    return 'WRONG_SERVER'
end
local state = redis.call('HGET', KEYS[1], 'state')
if state == 'CONSUMED' or state == 'RELEASED' then
    return 'ALREADY_CONSUMED'
end
local expires = tonumber(redis.call('HGET', KEYS[1], 'expiresAtMs') or '0')
local now = tonumber(ARGV[5])
if expires and expires > 0 and now and expires <= now then
    return 'EXPIRED'
end
if state ~= 'CLAIMED' and state ~= 'PENDING' then
    return 'BAD_STATE'
end
redis.call('HSET', KEYS[1],
    'state', 'CONSUMED',
    'releasedAtMs', ARGV[4])
local indexed = redis.call('GET', KEYS[2])
if indexed == ARGV[1] then
    redis.call('DEL', KEYS[2])
end
return 'REDEEMED'
