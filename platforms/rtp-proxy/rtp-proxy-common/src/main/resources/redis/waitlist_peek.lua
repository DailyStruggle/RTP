-- waitlist_peek.lua
-- Return up to `limit` envelopes from the head of the waitlist without
-- mutating any state. Used by the proxy-side drainer to perform
-- region-aware per-backend allocation before issuing the atomic
-- waitlist_drain_batch removal. Cheap (LRANGE 0 limit-1).
--
-- KEYS:
--   [1] listKey -- rtp:net:waitlist:list
-- ARGV:
--   [1] limit   -- integer >= 0; 0 returns empty list
-- Returns: Lua array of envelopeJson strings in FIFO order (head first).

local listKey = KEYS[1]
local limit   = tonumber(ARGV[1]) or 0
if limit <= 0 then
  return {}
end
return redis.call('LRANGE', listKey, 0, limit - 1)
