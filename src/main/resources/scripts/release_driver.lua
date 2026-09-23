-- KEYS[1] = driver:{id}:assignment, KEYS[2] = drivers:geo, KEYS[3] = drivers:available
-- ARGV[1] = tripId, ARGV[2] = driverId
-- Frees the driver only if the key still belongs to this trip, so a late
-- release for an old trip can not wipe out a newer reservation.
local v = redis.call('GET', KEYS[1])
if v == 'OFFERED:' .. ARGV[1] or v == 'ASSIGNED:' .. ARGV[1] then
    redis.call('DEL', KEYS[1])
    -- Put the driver back in the searchable set at their last known spot.
    local pos = redis.call('GEOPOS', KEYS[2], ARGV[2])[1]
    if pos then
        redis.call('GEOADD', KEYS[3], pos[1], pos[2], ARGV[2])
    end
    return 1
end
return 0
