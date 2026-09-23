-- KEYS[1] = driver:{id}:assignment, KEYS[2] = drivers:seen, KEYS[3] = drivers:available
-- ARGV[1] = driverId, ARGV[2] = tripId, ARGV[3] = offer ttl (ms), ARGV[4] = freshness cutoff (epoch ms)
-- Returns 1 = reserved, 0 = driver already busy, -1 = driver is stale/offline.
--
-- Check-and-set in one script: Redis runs scripts one at a time, so two
-- matchers can never both see the driver as free.
if redis.call('EXISTS', KEYS[1]) == 1 then
    return 0
end
local seen = redis.call('ZSCORE', KEYS[2], ARGV[1])
if not seen or tonumber(seen) < tonumber(ARGV[4]) then
    return -1
end
-- The TTL means a reservation cleans itself up if the app dies mid-offer.
redis.call('SET', KEYS[1], 'OFFERED:' .. ARGV[2], 'PX', ARGV[3])
redis.call('ZREM', KEYS[3], ARGV[1])
return 1
