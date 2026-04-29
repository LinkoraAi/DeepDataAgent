package com.linkroa.deepdataagent.datasource.application.command;

import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.JdbcType;

/**
 * 更新数据源命令
 */
public record UpdateDatasourceCommand(
        Long id,
        String name,
        DatasourceType type,
        JdbcType subType,
        String description,
        
        JdbcConfigCommand jdbcConfig,
        ApiConfigCommand apiConfig
) {
}
