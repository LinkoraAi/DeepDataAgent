package com.linkroa.deepdataagent.runtime.infrastructure.config;

/**
 * 协调租约 Redis key 集中构造（move-coordination-leases-to-redis D2）。
 *
 * <p>两类租约在 Redis 侧的 key 布局（standalone，当前唯一实现形态）：</p>
 * <ul>
 *   <li>turn 租约：{@code runtime:turn:<sessionId>} —— string，值 = 持有者实例标识，TTL 10min；</li>
 *   <li>turn owner 二级索引：{@code runtime:turn-owners:<instanceId>} —— set，成员 = sessionId
 *       （无 TTL，启动恢复惰性对账清理残留成员）；</li>
 *   <li>fire lease：{@code runtime:fire:<deploymentId>} —— string，值 = 持有者实例标识，TTL 30s，
 *       无 owner 二级索引（调度触发为一次性防重，不参与崩溃恢复扫描）。</li>
 * </ul>
 *
 * <p><b>cluster 预留（当前不实现，仅以命名体现规划）</b>：acquire / release-owned 的 Lua 需在同槽内
 * 原子操作 turn key 与 owner set，接 cluster 时应让二者共享以 sessionId 为同位的 hash tag
 * （如 {@code runtime:{<sessionId>}:turn} 与按 sessionId 分片的 owner 索引），并在
 * {@link RedissonConfig} 补 {@code useClusterServers()} 装配 + 脚本槽校验。standalone 下 hash tag
 * 不生效，故当前 key 不带花括号 hash tag；owner 索引以 instanceId 维度聚合的枚举语义（SMEMBERS）
 * 在 cluster 多槽下的改造点为「按 sessionId 同位」的二级索引重建，属后续接入点位。</p>
 */
public final class CoordLeaseKeys {

    /** turn 租约 key 前缀。 */
    private static final String TURN_PREFIX = "runtime:turn:";

    /** turn owner 二级索引 set key 前缀。 */
    private static final String TURN_OWNERS_PREFIX = "runtime:turn-owners:";

    /** fire lease key 前缀。 */
    private static final String FIRE_PREFIX = "runtime:fire:";

    private CoordLeaseKeys() {
    }

    /**
     * turn 租约 key（{@code runtime:turn:<sessionId>}）。
     *
     * @param sessionId 会话业务 ID
     */
    public static String turn(String sessionId) {
        return TURN_PREFIX + sessionId;
    }

    /**
     * turn owner 二级索引 set key（{@code runtime:turn-owners:<instanceId>}）。
     *
     * @param instanceId 持有者实例标识
     */
    public static String turnOwners(String instanceId) {
        return TURN_OWNERS_PREFIX + instanceId;
    }

    /**
     * fire lease key（{@code runtime:fire:<deploymentId>}）。
     *
     * @param deploymentId 调度器业务 ID
     */
    public static String fire(String deploymentId) {
        return FIRE_PREFIX + deploymentId;
    }
}
