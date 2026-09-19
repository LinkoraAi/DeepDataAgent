-- fire lease owner-scoped 释放（无索引版，move-coordination-leases-to-redis D2，审查修复 F11）
-- GET==owner 才 DEL；非持有者的迟到释放返回 0，不误摘接管者租约造成双触发窗口。无 owner 索引故不 SREM。
-- KEYS[1] = fire lease key
-- ARGV[1] = owner 实例标识
-- 返回 1=本次删除生效；0=不存在 / 已易主
if redis.call('GET', KEYS[1]) == ARGV[1] then
    redis.call('DEL', KEYS[1])
    return 1
end
return 0
