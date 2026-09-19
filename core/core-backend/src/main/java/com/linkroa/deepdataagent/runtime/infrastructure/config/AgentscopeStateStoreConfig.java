package com.linkroa.deepdataagent.runtime.infrastructure.config;

import io.agentscope.extensions.postgresql.snapshot.PostgresSnapshotSpec;
import io.agentscope.extensions.postgresql.state.PostgresAgentStateStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * AgentScope 官方 PG 扩展装配（agentscope-extensions-postgresql）。
 * <p>必须使用 auto-create 构造：{@code PostgresAgentStateStore(dataSource, schema, table, true)}
 * 自动创建 {@code agentscope} schema 与 {@code agentscope_sessions} 表；
 * {@code PostgresSnapshotSpec(dataSource)} 经 {@code PostgresRemoteSnapshotClient(dataSource, true)}
 * 自动创建 {@code agentscope_snapshots} 表。二者仅依赖 Spring 数据源，复用到既有连接池。</p>
 *
 * <p><b>不启用状态乐观并发（upgrade-agentscope-203 D16 裁定）</b>：装配处不设置
 * {@code conflictPolicy}，框架默认 OVERWRITE，不产生
 * {@code ConcurrentSessionModificationException}——同会话并发写已由「单执行槽 +
 * Redis turn 租约互斥」在平台层杜绝，存储层再设乐观锁属重复防护且会把
 * 租约竞态泄漏为运行期异常。</p>
 */
@Configuration
public class AgentscopeStateStoreConfig {

    /**
     * 装配 AgentScope PG 状态存储（auto-create：自动建 {@code agentscope} schema 与状态表），容器关闭时经
     * {@code close} 释放。
     *
     * @param dataSource 平台数据源（复用既有连接池）
     * @param properties Agent 运行时配置（提供 schema / 表名）
     * @return PG 状态存储实例
     */
    @Bean(destroyMethod = "close")
    public PostgresAgentStateStore postgresAgentStateStore(DataSource dataSource, AgentRuntimeProperties properties) {
        return new PostgresAgentStateStore(
                dataSource,
                properties.getStateSchema(),
                properties.getStateTable(),
                true
        );
    }

    /**
     * 装配 AgentScope PG 快照规格（auto-create：自动建快照表）；刻意不设 {@code conflictPolicy}，
     * 框架默认 OVERWRITE，并发互斥由平台层「单执行槽 + Redis turn 租约」承担。
     *
     * @param dataSource 平台数据源
     * @return PG 快照规格
     */
    @Bean
    public PostgresSnapshotSpec postgresSnapshotSpec(DataSource dataSource) {
        return new PostgresSnapshotSpec(dataSource);
    }
}