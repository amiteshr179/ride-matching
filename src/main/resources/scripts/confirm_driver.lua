-- KEYS[1] = driver:{id}:assignment
-- ARGV[1] = tripId
-- Turns an offer into a firm assignment, but only if this trip still holds
-- the offer. If the offer expired (key gone) or belongs to another trip, 0.
if redis.call('GET', KEYS[1]) ~= 'OFFERED:' .. ARGV[1] then
    return 0
end
redis.call('SET', KEYS[1], 'ASSIGNED:' .. ARGV[1])
return 1
