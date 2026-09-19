-- turn 租约续约（owner-scoped，move-coordination-leases-to-redis D2/D5）
-- GET==owner 则 PEXPIRE 续命，否则返回 0（不存在 / 已过期被 Redis 自清理 / 已易主）。
-- 调用方据此二分类：返回 0=确证失权立即 fail-closed；连接 / 命令异常（非 0，抛错）=瞬断容忍重试。
-- KEYS[1] = turn 租约 key
-- ARGV[1] = owner 实例标识
-- ARGV[2] = TTL 毫秒
if redis.call('GET', KEYS[1]) == ARGV[1] then
    redis.call('PEXPIRE', KEYS[1], ARGV[2])
    return 1
end
return 0
