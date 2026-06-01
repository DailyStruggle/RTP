-- rtp-proxy-common Phase 2e-Redis A2 atomic release.
-- Refines rtp-proxy-ADR-005. Terminal-state transition + active-index
-- cleanup. Idempotent: re-releasing a missing or already-terminal token
-- is a no-op (returns 0) so the caller's release CompletableFuture never
-- fails on retry (REQ-RTP-S-004).
--
-- KEYS[1] = rtp:net:tok:<tokenId>             token HASH key
-- ARGV[1] = tokenId
-- ARGV[2] = terminalState ("CONSUMED" or "RELEASED")
-- ARGV[3] = releasedAtEpochMs (string)
-- ARGV[4] = activeIndexKeyPrefix (e.g. "rtp:net:tokactive:")
--
-- Returns 1 if the row was transitioned, 0 if it was missing or already
-- terminal.
if redis.call('EXISTS', KEYS[1]) == 0 then
    return 0
end
local state = redis.call('HGET', KEYS[1], 'state')
if state == 'CONSUMED' or state == 'RELEASED' then
    return 0
end
local playerId = redis.call('HGET', KEYS[1], 'playerId')
redis.call('HSET', KEYS[1],
    'state', ARGV[2],
    'releasedAtMs', ARGV[3])
if playerId and playerId ~= '' then
    local indexKey = ARGV[4] .. playerId
    local indexed = redis.call('GET', indexKey)
    if indexed == ARGV[1] then
        redis.call('DEL', indexKey)
    end
end
return 1
