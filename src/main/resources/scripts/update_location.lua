-- KEYS[1] = drivers:geo, KEYS[2] = drivers:seen, KEYS[3] = drivers:available,
-- KEYS[4] = driver:{id}:assignment
-- ARGV[1] = driverId, ARGV[2] = lng, ARGV[3] = lat, ARGV[4] = now (epoch ms)
-- Position and heartbeat are written together so the map and the
-- stale sweeper never see one without the other.
redis.call('GEOADD', KEYS[1], ARGV[2], ARGV[3], ARGV[1])
redis.call('ZADD', KEYS[2], ARGV[4], ARGV[1])
-- drivers:available only holds drivers who could take a ride right now, so
-- the matcher's radius search is not crowded out by busy drivers.
if redis.call('EXISTS', KEYS[4]) == 0 then
    redis.call('GEOADD', KEYS[3], ARGV[2], ARGV[3], ARGV[1])
else
    redis.call('ZREM', KEYS[3], ARGV[1])
end
return 1
