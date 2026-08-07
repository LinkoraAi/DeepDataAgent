package com.linkroa.deepdataagent.agent.infrastructure.executor;

import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceCategory;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceGateway;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * API 查询执行器
 * <p>基于 DatasourceGateway 的 API 查询能力执行数据获取，
 * query 参数对 API 类型表示 apiSchemaName。</p>
 */
@Component
public class ApiQueryExecutor implements QueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(ApiQueryExecutor.class);
    private static final int DEFAULT_LIMIT = 500;

    private final DatasourceGateway datasourceGateway;

    public ApiQueryExecutor(DatasourceGateway datasourceGateway) {
        this.datasourceGateway = datasourceGateway;
    }

    @Override
    public List<Map<String, Object>> execute(DatasourceInfo datasource, String query) {
        log.info("ApiQueryExecutor: executing API query on datasource={}, schema={}", datasource.id(), query);
        return datasourceGateway.executeApiQuery(datasource.id(), query, DEFAULT_LIMIT);
    }

    @Override
    public boolean supports(DatasourceInfo datasource) {
        return datasource.category() == DatasourceCategory.API;
    }
}
