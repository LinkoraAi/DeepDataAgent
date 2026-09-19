-- turn 租约 owner-scoped 释放（move-coordination-leases-to-redis D2，审查修复 F11）
-- GET==owner 才 DEL + SREM owner 二级索引；非持有者（租约过期被接管后）的迟到释放返回 0，
-- 不误摘接管者有效租约、不错摘接管者的 owner 索引。
-- KEYS[1] = turn 租约 key
-- KEYS[2] = owner 二级索引 set
-- ARGV[1] = owner 实例标识
-- ARGV[2] = 索引成员（sessionId）
-- 返回 1=本次删除生效；0=不存在 / 已易主
if redis.call('GET', KEYS[1]) == ARGV[1] then
    redis.call('DEL', KEYS[1])
    redis.call('SREM', KEYS[2], ARGV[2])
    return 1
end
return 0
