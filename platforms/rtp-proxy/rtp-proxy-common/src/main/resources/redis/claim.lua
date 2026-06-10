-- rtp-proxy-common Phase 2e-Redis A2 atomic claim.
-- Refines rtp-proxy-ADR-005 Atomic Claim block. Single-call create-and-lock
-- so the observable contract matches SqlNetworkStateBinding.claimSync:
-- the winner sees the token row materialised in CLAIMED state; the loser
-- sees 0 and the caller raises IllegalStateException.
--
-- KEYS[1] = rtp:net:tok:<tokenId>          token HASH key
-- KEYS[2] = rtp:net:tokactive:<playerId>   active-player index (string holding tokenId)
-- ARGV[1] = tokenId
-- ARGV[2] = serverId
-- ARGV[3] = playerId (uuid string)
-- ARGV[4] = expiresEpochMs (string)
-- ARGV[5] = createdEpochMs (string)
-- ARGV[6] = ttlSeconds (string, integer)
-- ARGV[7] = hmacHex (string, optional - empty string when verifier disabled).
--           Phase 2e-Redis A3 token envelope: Java pre-computes the HMAC
--           over the canonical token payload (tokenId|serverId|playerId|
--           expiresAtMs|createdAtMs|state=CLAIMED) and passes the hex
--           digest in here; Lua stores it opaquely as the 'hmac' HSET
--           field. Verification is Java-side on read paths.
-- ARGV[8] = regionKey (string, optional - empty string when the request
--           carried no region constraint). Stored opaquely as the
--           'regionKey' HSET field for cross-server
--           /rtp region=<server>:<region> support.
--
-- Returns 1 on win, 0 on race-loss (another active token already indexed).
local existing = redis.call('GET', KEYS[2])
if existing then
    return 0
end
local ttl = tonumber(ARGV[6])
local hmac = ARGV[7]
if hmac == nil then hmac = '' end
local regionKey = ARGV[8]
if regionKey == nil then regionKey = '' end
redis.call('HSET', KEYS[1],
    'tokenId', ARGV[1],
    'serverId', ARGV[2],
    'playerId', ARGV[3],
    'expiresAtMs', ARGV[4],
    'createdAtMs', ARGV[5],
    'state', 'CLAIMED',
    'releasedAtMs', '',
    'hmac', hmac,
    'regionKey', regionKey)
if ttl > 0 then
    redis.call('EXPIRE', KEYS[1], ttl)
end
redis.call('SET', KEYS[2], ARGV[1])
if ttl > 0 then
    redis.call('EXPIRE', KEYS[2], ttl)
end
return 1
