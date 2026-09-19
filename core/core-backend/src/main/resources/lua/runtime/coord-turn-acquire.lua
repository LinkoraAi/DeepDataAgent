-- turn 租约抢占（move-coordination-leases-to-redis D2）
-- 原子表达 owner-scoped 获取：SET NX PX 成功（同键无有效租约 / 既有已过期被 Redis TTL 自清理）后，
-- 同写 owner 二级索引 SADD，二者单脚本内完成（等价 PG 单语句 CAS）。
-- KEYS[1] = turn 租约 key（runtime:turn:<sessionId>）
-- KEYS[2] = owner 二级索引 set（runtime:turn-owners:<instanceId>）
-- ARGV[1] = owner 实例标识
-- ARGV[2] = TTL 毫秒
-- ARGV[3] = 索引成员（sessionId）
-- 返回 1=获取成功；0=同键存在未过期租约（并发单赢家败方）
if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then
    redis.call('SADD', KEYS[2], ARGV[3])
    return 1
end
return 0
