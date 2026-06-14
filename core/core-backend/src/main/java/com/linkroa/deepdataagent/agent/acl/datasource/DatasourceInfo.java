package com.linkroa.deepdataagent.agent.acl.datasource;

/**
 * 数据源信息（ACL 值对象）
 * <p>隔离 agent 模块对 datasource 模块 DatasourceConnection 的直接依赖。
 * 仅携带 agent 模块所需的字段，屏蔽 datasource 模块的领域细节。</p>
 */
public record DatasourceInfo(
    Long id,
    String name,
    DatasourceCategory category,
    JdbcCategory jdbcCategory,
    boolean enabled,
    JdbcConnectionInfo jdbcConfig,
    ApiConnectionInfo apiConfig
) {
    public DatasourceInfo {
        if (id == null) {
            throw new IllegalArgumentException("数据源 ID 不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("数据源名称不能为空");
        }
        if (category == null) {
            throw new IllegalArgumentException("数据源分类不能为空");
        }
    }
}
