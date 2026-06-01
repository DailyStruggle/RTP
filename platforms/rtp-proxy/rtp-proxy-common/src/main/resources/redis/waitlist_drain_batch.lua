-- waitlist_drain_batch.lua
-- Atomically remove a caller-allocated set of envelopes (identified by
-- correlationId) from the waitlist and return the removed JSON payloads in
-- the same order the caller requested them. Pure-FIFO semantics on the
-- transport side; per-backend / region-aware allocation happens in Java
-- before this script runs (see waitlist_peek.lua + RedisNetworkWaitlist).
--
-- KEYS:
--   [1] listKey -- rtp:net:waitlist:list
--   [2] uuidKey -- rtp:net:waitlist:uuids
--   [3] cidKey  -- rtp:net:waitlist:cids
-- ARGV:
--   pairs of (correlationId, playerId, envelopeJson) repeated; ARGV is
--   structured this way (rather than a parallel pair-of-lists) so the
--   script never has to parse JSON to learn the playerId for SREM
--   cleanup. Each triplet must match what waitlist_peek.lua returned.
-- Returns: Lua array of removed envelopeJson strings, in the same order
--          as the input triplets. An envelope whose LREM returns 0 (the
--          payload was already removed concurrently, e.g. by remove_uuid
--          for a quit) is silently skipped and not included in the return
--          array, so the caller can detect under-drain by length compare.

local listKey = KEYS[1]
local uuidKey = KEYS[2]
local cidKey  = KEYS[3]

local out = {}
local n = #ARGV
-- ARGV is in triplets; bail out gracefully on a malformed odd-length tail
local i = 1
while i + 2 <= n do
  local cid     = ARGV[i]
  local pid     = ARGV[i + 1]
  local envJson = ARGV[i + 2]
  -- LREM with count=1 removes the first matching value from head.
  local removed = redis.call('LREM', listKey, 1, envJson)
  if removed and tonumber(removed) > 0 then
    redis.call('SREM', uuidKey, pid)
    redis.call('SREM', cidKey, cid)
    out[#out + 1] = envJson
  end
  i = i + 3
end
return out
