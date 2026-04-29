package com.linkroa.deepdataagent.datasource.application.assembler;

import com.linkroa.deepdataagent.datasource.application.command.CreateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.command.UpdateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.domain.model.*;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * 数据源组装器
 * <p>负责Command到领域模型的转换。</p>
 */
public class DatasourceAssembler {

    public static DatasourceConnection toDatasourceConnection(CreateDatasourceCommand command) {
        JdbcConnectionConfig jdbcConfig = null;

        if (command.type() == DatasourceType.JDBC && command.jdbcConfig() != null) {
            jdbcConfig = new JdbcConnectionConfig(
                    command.jdbcConfig().host(),
                    ObjectUtils.firstNonNull(command.jdbcConfig().port(), 0),
                    command.jdbcConfig().database(),
                    command.jdbcConfig().username(),
                    command.jdbcConfig().password()
            );
        }

        return DatasourceConnection.create(
                command.name(),
                command.type(),
                command.subType(),
                command.description(),
                jdbcConfig
        );
    }

    public static DatasourceConnection toDatasourceConnection(UpdateDatasourceCommand command, DatasourceConnection existing) {
        return new DatasourceConnection(
                existing.id(),
                StringUtils.defaultIfBlank(command.name(), existing.name()),
                existing.type(),
                existing.subType(),
                existing.status(),
                existing.jdbcConnectionConfig(),
                StringUtils.defaultIfBlank(command.description(), existing.description()),
                existing.createdAt(),
                existing.updatedAt(),
                existing.createdBy(),
                existing.updatedBy()
        );
    }
}
