package com.linkroa.deepdataagent.agent.acl.datasource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 数据源网关（ACL 接口）
 * <p>定义 agent 模块访问 datasource 模块的边界接口，
 * 遵循 DDD 防腐层模式，隔离跨限界上下文的直接依赖。</p>
 *
 * <p>应用层和领域层仅依赖此接口，不依赖 datasource 模块的任何类。
 * 具体实现在 infrastructure 层的 DatasourceGatewayAdapter 中。</p>
 */
public interface DatasourceGateway {

    /**
     * 查找数据源信息
     *
     * @param id 数据源 ID
     * @return 数据源信息，不存在时返回空
     */
    Optional<DatasourceInfo> findDatasource(Long id);

    /**
     * 提取数据源的 Schema 信息
     *
     * @param datasourceId 数据源 ID
     * @return Schema 描述文本（供 LLM 使用）
     */
    String extractSchema(Long datasourceId);

    /**
     * 执行 API 数据源查询
     *
     * @param datasourceId    数据源 ID
     * @param apiSchemaName   API Schema 名称（即"表名"）
     * @param limit           返回数据行数限制
     * @return 查询结果
     */
    List<Map<String, Object>> executeApiQuery(Long datasourceId, String apiSchemaName, int limit);
}
