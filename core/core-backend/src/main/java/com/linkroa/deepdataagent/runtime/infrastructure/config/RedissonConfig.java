package com.linkroa.deepdataagent.runtime.infrastructure.config;

import org.apache.commons.lang3.StringUtils;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.redisson.config.ConstantDelay;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Redisson 客户端装配（move-coordination-leases-to-redis D1）。
 *
 * <p>协调租约 Redis 存储专用，<b>手动</b>构建 {@code RedissonClient}（禁强转 Lettuce 连接工厂、
 * 不引 redisson-spring-boot-starter 避免自动配置冲突）。仅取核心库能力：{@code RScript} EVALSHA 执行
 * owner-scoped Lua（见 {@code RedisCoordLeaseStore}），不使用 RLock / getLock（线程绑定身份
 * 与本系统跨线程续约 / 释放模型冲突）。</p>
 *
 * <p><b>取值形态（design D1 定稿，消除与集成测试注入的错配）</b>：优先读 {@code spring.data.redis.url}
 * ——非空即 {@code setAddress(url)}（Redisson 原生解析 {@code redis://[:password@]host:port/db}，
 * 密码内联即生效）；url 为空才回落 {@code host}/{@code port}/{@code database} 逐项组装，
 * 密码按 Redisson 4.x 口径置于全局 {@code Config.password}（服务器级 setter 已废弃）。
 * 主配置现状为 host/port 分项，集成测试基类按 {@code TEST_PG_URL} 惯例注入的是连接串（{@code TEST_REDIS_URL}
 * → {@code spring.data.redis.url}），两种形态必须同源支持。</p>
 *
 * <p><b>cluster / sentinel 不写不可达分支</b>（避免 dead-code）：多节点规划只以 {@link CoordLeaseKeys}
 * 的 hash tag 同位命名与 javadoc 点位体现。<b>接入点位</b>：届时以 {@code Config.useClusterServers()}
 * 替换下方 {@code useSingleServer()}，并补 Lua 脚本 slot 校验（turn key 与 owner set 同 hash tag）。</p>
 *
 * <p><b>随应用常驻装配</b>（move-coordination-leases-to-redis PR-C4：协调租约唯一存储为 Redis，
 * 无 db 回退开关）——Redis 不可用时应用启动即快速失败（{@code RedisConnectionException}），
 * 与「租约不可用不降级」的运行时口径一致（design D4）。</p>
 */
@Configuration(proxyBeanMethods = false)
public class RedissonConfig {

    /** Redis 连接串（优先）：{@code redis://[:password@]host:port/database}，集成测试经此注入。 */
    @Value("${spring.data.redis.url:}")
    private String redisUrl;

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Value("${spring.data.redis.database:0}")
    private int redisDatabase;

    /** 连接超时（毫秒，design D1：≤500ms 起步，Redis 不可用快速失败不拖垮续约池）。 */
    private static final int CONNECT_TIMEOUT_MS = 500;

    /** 命令超时（毫秒，design D1：≤2s 起步，防慢调用占续约池）。 */
    private static final int COMMAND_TIMEOUT_MS = 2000;

    /** 重试次数与间隔（design D5：续约当周期重试 1 次的最小承载，瞬断容忍）。 */
    private static final int RETRY_ATTEMPTS = 1;
    private static final int RETRY_INTERVAL_MS = 200;

    private static final String REDIS_SCHEME = "redis://";

    /**
     * 单节点 Redisson 客户端：StringCodec（value 全为 owner 字符串，不经对象编解码，规避
     * Redisson 自带 Jackson 与本体 Jackson 的牵连）；容器关闭时优雅 shutdown。
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        // 全局 StringCodec：所有租约 value 均为 owner 字符串，读写口径一致
        config.setCodec(StringCodec.INSTANCE);
        if (StringUtils.isBlank(redisUrl) && StringUtils.isNotBlank(redisPassword)) {
            // Redisson 4.x：服务器级 password 已废弃并上移至 Config 全局口径（本项目仅单节点形态）
            config.setPassword(redisPassword);
        }
        SingleServerConfig single = config.useSingleServer()
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                // 命令响应超时（Redisson 的 timeout 即 command timeout）：防慢调用占续约池
                .setTimeout(COMMAND_TIMEOUT_MS)
                .setRetryAttempts(RETRY_ATTEMPTS)
                // Redisson 4.x 起 retryInterval 废弃：ConstantDelay 策略等价承载「固定间隔重试」语义
                .setRetryDelay(new ConstantDelay(Duration.ofMillis(RETRY_INTERVAL_MS)));
        if (StringUtils.isNotBlank(redisUrl)) {
            // url 优先：Redisson 原生解析 redis://[:pw@]host:port/db，密码与库号内联即生效
            single.setAddress(redisUrl.trim());
        } else {
            // 回落 host/port/password/database 逐项组装（库号经 setDatabase 显式指定，
            // 不拼进 address：Redisson 地址解析不以 URI path 承载 database）
            single.setAddress(REDIS_SCHEME + redisHost + ":" + redisPort);
            single.setDatabase(redisDatabase);
        }
        return Redisson.create(config);
    }
}
