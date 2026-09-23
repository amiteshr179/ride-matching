-- KEYS[1] = drivers:geo, KEYS[2] = drivers:seen
-- ARGV[1] = driverId, ARGV[2] = lng, ARGV[3] = lat, ARGV[4] = now (epoch ms)
-- Position and heartbeat are written together so the map and the
-- stale sweeper never see one without the other.
redis.call('GEOADD', KEYS[1], ARGV[2], ARGV[3], ARGV[1])
redis.call('ZADD', KEYS[2], ARGV[4], ARGV[1])
return 1
