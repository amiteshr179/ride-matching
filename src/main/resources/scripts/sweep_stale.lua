-- KEYS[1] = drivers:geo, KEYS[2] = drivers:seen
-- ARGV[1] = cutoff (epoch ms), ARGV[2] = max drivers to remove per call
-- Redis GEO members cannot have their own TTL, so a sorted set of
-- last-seen timestamps tells us who went quiet. Doing the read and the
-- removal in one script means a driver that pings between the two steps
-- can not be removed by mistake.
local ids = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', '(' .. ARGV[1], 'LIMIT', 0, tonumber(ARGV[2]))
if #ids == 0 then
    return 0
end
redis.call('ZREM', KEYS[2], unpack(ids))
redis.call('ZREM', KEYS[1], unpack(ids))
return #ids
