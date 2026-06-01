-- waitlist_refresh_ttl.lua
-- Rewrite every live entry's enrolledAtMs to `nowMs`, preserving FIFO
-- order. Used by the proxy-side drainer when an entire drain pulse fails
-- to place its envelopes (every qualifying backend rejected or the
-- network is partitioned) so waitlist_reap does not silently sweep
-- entries that aged only because the network could not serve them.
--
-- KEYS:
--   [1] listKey -- rtp:net:waitlist:list
-- ARGV:
--   [1] nowMs   -- new enrolledAtMs value to splice in
-- Returns: count of entries refreshed.

local listKey = KEYS[1]
local nowMs   = ARGV[1]

local entries = redis.call('LRANGE', listKey, 0, -1)
local refreshed = 0

for i = 1, #entries do
  local envJson = entries[i]
  -- Locate the enrolledAtMs field and splice in the new value. The field
  -- is always present (record component); enrolledAtMs is a JSON number.
  local fStart, fEnd = string.find(envJson, '"enrolledAtMs":', 1, true)
  if fStart then
    local valStart = fEnd + 1
    local valEnd = valStart
    while valEnd <= #envJson do
      local c = string.byte(envJson, valEnd)
      if c < 48 or c > 57 then break end
      valEnd = valEnd + 1
    end
    if valEnd > valStart then
      local rebuilt = string.sub(envJson, 1, valStart - 1)
                    .. tostring(nowMs)
                    .. string.sub(envJson, valEnd)
      -- LSET uses 0-indexed positions; LRANGE indices above are 1-based,
      -- so subtract 1.
      redis.call('LSET', listKey, i - 1, rebuilt)
      refreshed = refreshed + 1
    end
  end
end

return refreshed
