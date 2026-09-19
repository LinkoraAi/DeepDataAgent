-- fire lease 抢占（无 owner 二级索引版，move-coordination-leases-to-redis D2）
-- 调度触发为一次性窗口防重（不参与崩溃恢复扫描），故仅单 key SET NX PX，不 SADD 索引。
-- KEYS[1] = fire lease key（runtime:fire:<deploymentId>）
-- ARGV[1] = owner 实例标识
-- ARGV[2] = TTL 毫秒
-- 返回 1=获取成功；0=窗口内已有触发在途
if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then
    return 1
end
return 0
