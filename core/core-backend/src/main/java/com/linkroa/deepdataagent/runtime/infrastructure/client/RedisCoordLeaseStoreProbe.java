package com.linkroa.deepdataagent.runtime.infrastructure.client;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonClient;
import org.redisson.api.redisnode.RedisNodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 协调租约 Redis 存储的部署红线自检（move-coordination-leases-to-redis D4）。
 *
 * <p>启动时经 Redisson 节点管理面回显 {@code maxmemory-policy}：租约 key 的语义是「服务端 TTL
 * 自过期」，一旦内存压力触发淘汰（allkeys-lru / volatile-* 等策略），锁会在 TTL 之前随机消失，
 * 表现为<b>随机 fail-closed 中止在途轮次</b>——运维侧必须配置 {@code noeviction}。
 * 非 {@code noeviction} 时 MUST WARN（只告警不阻断启动：策略可能由托管 Redis 平台统一治理）。</p>
 *
 * <p>读取失败（受限实例禁用 {@code CONFIG} 命令等）同样降级为 WARN 并跳过自检，
 * MUST NOT 因探针异常导致应用无法启动。</p>
 *
 * <p>随协调租约 Redis 化常驻装配（PR-C4 起 Redis 为唯一租约存储，无 db 回退开关）。</p>
 */
@Component
public class RedisCoordLeaseStoreProbe {

    private static final Logger log = LoggerFactory.getLogger(RedisCoordLeaseStoreProbe.class);

    /** 被检配置项名。 */
    static final String CONFIG_MAXMEMORY_POLICY = "maxmemory-policy";

    /** 部署红线要求值：禁止淘汰（租约 key 只由 TTL 过期，绝不受内存压力淘汰）。 */
    static final String REQUIRED_POLICY = "noeviction";

    @Resource
    private RedissonClient redissonClient;

    /**
     * 启动自检：INFO 回显策略，非 {@code noeviction} / 读取失败 WARN。
     */
    @PostConstruct
    void reportMaxMemoryPolicy() {
        String policy;
        try {
            // single 节点形态专用取法（design D1：cluster / sentinel 接入时改按角色枚举节点）
            Map<String, String> config = redissonClient.getRedisNodes(RedisNodes.SINGLE)
                    .getInstance().getConfig(CONFIG_MAXMEMORY_POLICY);
            policy = config == null ? null : config.get(CONFIG_MAXMEMORY_POLICY);
        } catch (RuntimeException ex) {
            log.warn("协调租约 Redis 部署自检跳过：读取 {} 失败（受限实例可能禁用 CONFIG 命令）"
                    + "——请运维侧确认该 Redis 配置为 noeviction 且开启 AOF everysec", CONFIG_MAXMEMORY_POLICY, ex);
            return;
        }
        if (StringUtils.isBlank(policy)) {
            log.warn("协调租约 Redis 部署自检跳过：{} 返回空值，请运维侧确认配置为 {}",
                    CONFIG_MAXMEMORY_POLICY, REQUIRED_POLICY);
            return;
        }
        if (isCompliant(policy)) {
            log.info("协调租约 Redis 部署自检通过: {}={}（租约 key 不受淘汰，仅 TTL 过期）",
                    CONFIG_MAXMEMORY_POLICY, policy);
            return;
        }
        log.warn("协调租约 Redis 部署红线不满足: {}={}，期望 {}——内存压力下租约 key 会被随机淘汰，"
                + "表现为在途轮次随机 fail-closed 中止，请尽快改为 noeviction 并开启 appendfsync everysec",
                CONFIG_MAXMEMORY_POLICY, policy, REQUIRED_POLICY);
    }

    /** 策略是否满足部署红线（大小写 / 空白无关）。 */
    static boolean isCompliant(String policy) {
        return policy != null && REQUIRED_POLICY.equalsIgnoreCase(policy.trim());
    }
}
