-- rtp-proxy-common L6 Slice D3 cross-server network wait queue: poll status.
-- Refines PROPOSAL-cross-server-rtp-L6 Section 4.3 (read-side wholesale-replace).
-- One-shot pipelined read of per-player status: for each requested playerId
-- in ARGV, returns either the flat HASH contents (as a Lua array of
-- alternating field/value strings, prefixed by the playerId) or a single
-- empty string sentinel when the player has no live entry.
--
-- KEYS: none (status keys are derived from each playerId argument).
-- ARGV[i] = playerId (uuid string), 1 <= i <= #ARGV.
--
-- Return shape: an array of length #ARGV. Each element is itself an array:
--   { playerId } when no status row exists (caller treats as UNKNOWN / evicted),
--   { playerId, 'state', S, 'regionKey', R, 'serverId', SID, 'updatedAtMs', T,
--     'correlationId', C, 'positionInQueue', P } otherwise.
-- positionInQueue is a synthetic derivation: 1-based LPOS of correlationId in
-- the ready FIFO, or 0 when the entry has already been LPOP'd.
local out = {}
local readyKey = 'rtp:net:wq:ready'
for i = 1, #ARGV do
    local pid = ARGV[i]
    local statusKey = 'rtp:net:wq:status:' .. pid
    local fields = redis.call('HGETALL', statusKey)
    if #fields == 0 then
        out[i] = { pid }
    else
        local cid = ''
        for j = 1, #fields, 2 do
            if fields[j] == 'correlationId' then
                cid = fields[j+1]
                break
            end
        end
        local pos = 0
        if cid ~= '' then
            local lpos = redis.call('LPOS', readyKey, cid)
            if lpos ~= false and lpos ~= nil then
                pos = tonumber(lpos) + 1
            end
        end
        local row = { pid }
        for j = 1, #fields do row[#row + 1] = fields[j] end
        row[#row + 1] = 'positionInQueue'
        row[#row + 1] = tostring(pos)
        out[i] = row
    end
end
return out
