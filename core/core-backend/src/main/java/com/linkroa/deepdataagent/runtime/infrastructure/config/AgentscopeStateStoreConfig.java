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
 */
@Configuration
public class AgentscopeStateStoreConfig {

    @Bean(destroyMethod = "close")
    public PostgresAgentStateStore postgresAgentStateStore(DataSource dataSource, AgentRuntimeProperties properties) {
        return new PostgresAgentStateStore(
                dataSource,
                properties.getStateSchema(),
                properties.getStateTable(),
                true
        );
    }

    @Bean
    public PostgresSnapshotSpec postgresSnapshotSpec(DataSource dataSource) {
        return new PostgresSnapshotSpec(dataSource);
    }
}