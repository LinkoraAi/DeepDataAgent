package com.linkroa.deepdataagent.shared.infrastructure.redis.port;

import java.util.Optional;

/**
 * Schema 元数据缓存端口。
 * <p>将数据源 Schema 描述文本按数据源 ID 缓存，避免同一数据源被重复连库提取元数据。
 * 该端口隔离了具体缓存实现（Redis 或内存），供 agent 网关与 datasource 服务共同使用。</p>
 */
public interface SchemaCachePort {

    /**
     * 按数据源 ID 读取缓存。
     *
     * @param datasourceId 数据源 ID
     * @return 命中且未过期的 Schema 文本；未命中或已过期返回 {@link Optional#empty()}
     */
    Optional<String> get(Long datasourceId);

    /**
     * 写入 Schema 缓存，有效期由实现决定。
     *
     * @param datasourceId 数据源 ID
     * @param schema        Schema 描述文本
     */
    void put(Long datasourceId, String schema);

    /**
     * 显式失效指定数据源的缓存。
     *
     * @param datasourceId 数据源 ID
     */
    void evict(Long datasourceId);
}
