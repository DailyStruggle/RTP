-- waitlist_enrol.lua
-- Park a single WaitEnvelope at the tail of the shared waitlist.
-- KEYS:
--   [1] listKey   -- rtp:net:waitlist:list   (LIST<json>)
--   [2] uuidKey   -- rtp:net:waitlist:uuids  (SET of playerId strings)
--   [3] cidKey    -- rtp:net:waitlist:cids   (SET of correlationId strings)
-- ARGV:
--   [1] playerId       -- string UUID
--   [2] correlationId  -- string UUID
--   [3] envelopeJson   -- serialized WaitEnvelope (Jackson)
--   [4] maxSize        -- integer; 0 disables cap
-- Returns one of:
--   "ACCEPTED"           -- envelope inserted at tail
--   "REJECTED_FULL"      -- list at maxSize
--   "REJECTED_DUPLICATE" -- playerId already present (different correlationId)
--   "ACCEPTED_IDEMPOTENT"-- correlationId replay, no insert

local listKey  = KEYS[1]
local uuidKey  = KEYS[2]
local cidKey   = KEYS[3]
local pid      = ARGV[1]
local cid      = ARGV[2]
local envJson  = ARGV[3]
local maxSize  = tonumber(ARGV[4]) or 0

-- 1. correlationId replay (idempotent)
if redis.call('SISMEMBER', cidKey, cid) == 1 then
  return 'ACCEPTED_IDEMPOTENT'
end

-- 2. duplicate playerId
if redis.call('SISMEMBER', uuidKey, pid) == 1 then
  return 'REJECTED_DUPLICATE'
end

-- 3. capacity
if maxSize > 0 then
  local len = redis.call('LLEN', listKey)
  if len >= maxSize then
    return 'REJECTED_FULL'
  end
end

-- 4. insert
redis.call('RPUSH', listKey, envJson)
redis.call('SADD', uuidKey, pid)
redis.call('SADD', cidKey, cid)
return 'ACCEPTED'
