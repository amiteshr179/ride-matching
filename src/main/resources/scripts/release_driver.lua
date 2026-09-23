-- KEYS[1] = driver:{id}:assignment
-- ARGV[1] = tripId
-- Frees the driver only if the key still belongs to this trip, so a late
-- release for an old trip can not wipe out a newer reservation.
local v = redis.call('GET', KEYS[1])
if v == 'OFFERED:' .. ARGV[1] or v == 'ASSIGNED:' .. ARGV[1] then
    redis.call('DEL', KEYS[1])
    return 1
end
return 0
