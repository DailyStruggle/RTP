-- waitlist_remove_uuid.lua
-- Point-remove the entry for `playerId` from the waitlist, regardless of
-- where it sits in the FIFO. Used by PlayerQuitEvent on the lobby and by
-- the proxy reaper for TTL expiry. Idempotent.
--
-- KEYS:
--   [1] listKey -- rtp:net:waitlist:list
--   [2] uuidKey -- rtp:net:waitlist:uuids
--   [3] cidKey  -- rtp:net:waitlist:cids
-- ARGV:
--   [1] playerId
-- Returns: 1 if a live entry was removed, 0 otherwise.

local listKey = KEYS[1]
local uuidKey = KEYS[2]
local cidKey  = KEYS[3]
local pid     = ARGV[1]

if redis.call('SISMEMBER', uuidKey, pid) == 0 then
  return 0
end

-- Find the JSON payload for this player by scanning the list. See
-- waitlist_position.lua for the substring rationale (no cjson dependency).
local entries = redis.call('LRANGE', listKey, 0, -1)
local needle = '"playerId":"' .. pid .. '"'
for i = 1, #entries do
  local envJson = entries[i]
  if string.find(envJson, needle, 1, true) then
    redis.call('LREM', listKey, 1, envJson)
    redis.call('SREM', uuidKey, pid)
    -- Extract the correlationId substring "correlationId":"<uuid>" so the
    -- cid SET stays consistent. UUIDs are 36 chars in the canonical 8-4-4-4-12
    -- string form.
    local cidStart, _ = string.find(envJson, '"correlationId":"', 1, true)
    if cidStart then
      local cid = string.sub(envJson, cidStart + 17, cidStart + 17 + 35)
      redis.call('SREM', cidKey, cid)
    end
    return 1
  end
end

-- Drift: SET claimed membership but LIST has no matching payload. Heal.
redis.call('SREM', uuidKey, pid)
return 0
