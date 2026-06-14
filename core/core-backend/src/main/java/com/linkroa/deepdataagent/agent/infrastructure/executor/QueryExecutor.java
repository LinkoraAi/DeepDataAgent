package com.linkroa.deepdataagent.agent.infrastructure.executor;

import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceInfo;

import java.util.List;
import java.util.Map;

/**
 * 查询执行器接口
 * <p>抽象查询执行，支持不同数据源类型。
 * MVP 仅实现 JDBC，未来可扩展 ApiQueryExecutor。</p>
 */
public interface QueryExecutor {

    /**
     * 执行查询
     *
     * @param datasource 数据源信息（ACL 值对象）
     * @param query      查询语句（SQL 或 API 参数）
     * @return 查询结果
     */
    List<Map<String, Object>> execute(DatasourceInfo datasource, String query);

    /**
     * 是否支持该数据源类型
     */
    boolean supports(DatasourceInfo datasource);
}
