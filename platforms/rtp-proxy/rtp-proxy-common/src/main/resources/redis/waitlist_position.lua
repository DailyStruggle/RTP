-- waitlist_position.lua
-- Look up the 1-indexed FIFO rank of a playerId on the waitlist. O(N) scan;
-- N is bounded by the configured waitlist maxSize (default 1024).
--
-- KEYS:
--   [1] listKey -- rtp:net:waitlist:list
--   [2] uuidKey -- rtp:net:waitlist:uuids
-- ARGV:
--   [1] playerId
-- Returns: 1-indexed integer rank, or 0 if the player has no live entry.
--          0 (rather than nil) keeps the return type stable across Jedis
--          versions; the Java caller maps 0 -> Optional.empty().

local listKey = KEYS[1]
local uuidKey = KEYS[2]
local pid     = ARGV[1]

if redis.call('SISMEMBER', uuidKey, pid) == 0 then
  return 0
end

local entries = redis.call('LRANGE', listKey, 0, -1)
for i = 1, #entries do
  -- Embed the playerId as a JSON field "playerId":"<uuid>" so a simple
  -- substring match is sufficient; Lua has no JSON parser and cjson is
  -- not enabled on all Redis builds. The Java serializer always writes
  -- this field first per WaitEnvelope record order.
  if string.find(entries[i], '"playerId":"' .. pid .. '"', 1, true) then
    return i
  end
end

-- Fell off the end despite SISMEMBER claiming membership: the SET and LIST
-- have drifted (e.g. an aborted drain). Heal the SET so future enrolments
-- aren't rejected as duplicates.
redis.call('SREM', uuidKey, pid)
return 0
