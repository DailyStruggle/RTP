-- waitlist_reap.lua
-- Remove every waitlist entry whose enrolledAtMs is older than
-- (nowMs - maxAgeMs). Per the user-approved Slice 3b discussion, this is a
-- safety-net for stuck states only; the drainer's refreshAllTtl path keeps
-- live entries from ageing out under transient total-failure. Bounded by
-- waitlist size (default cap 1024); the scan walks the whole LIST once.
--
-- KEYS:
--   [1] listKey -- rtp:net:waitlist:list
--   [2] uuidKey -- rtp:net:waitlist:uuids
--   [3] cidKey  -- rtp:net:waitlist:cids
-- ARGV:
--   [1] nowMs
--   [2] maxAgeMs    -- entries with enrolledAtMs < (nowMs - maxAgeMs) are reaped
-- Returns: count of entries reaped.

local listKey = KEYS[1]
local uuidKey = KEYS[2]
local cidKey  = KEYS[3]
local now     = tonumber(ARGV[1]) or 0
local maxAge  = tonumber(ARGV[2]) or 0

if maxAge <= 0 then
  return 0
end
local cutoff = now - maxAge

local entries = redis.call('LRANGE', listKey, 0, -1)
local reaped = 0

for i = 1, #entries do
  local envJson = entries[i]
  -- Parse out enrolledAtMs via substring; field name is fixed by the
  -- WaitEnvelope record. enrolledAtMs is a JSON number, not a string.
  local fStart, fEnd = string.find(envJson, '"enrolledAtMs":', 1, true)
  if fStart then
    local valStart = fEnd + 1
    -- Walk forward to the next non-digit (covers '}' and ',')
    local valEnd = valStart
    while valEnd <= #envJson do
      local c = string.byte(envJson, valEnd)
      if c < 48 or c > 57 then break end
      valEnd = valEnd + 1
    end
    local enrolled = tonumber(string.sub(envJson, valStart, valEnd - 1))
    if enrolled ~= nil and enrolled < cutoff then
      redis.call('LREM', listKey, 1, envJson)
      -- Extract playerId for SREM
      local pStart, pEnd = string.find(envJson, '"playerId":"', 1, true)
      if pStart then
        local pid = string.sub(envJson, pEnd + 1, pEnd + 36)
        redis.call('SREM', uuidKey, pid)
      end
      local cStart, cEnd = string.find(envJson, '"correlationId":"', 1, true)
      if cStart then
        local cid = string.sub(envJson, cEnd + 1, cEnd + 36)
        redis.call('SREM', cidKey, cid)
      end
      reaped = reaped + 1
    end
  end
end

return reaped
