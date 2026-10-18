package com.linkroa.deepdataagent.datasource.application.convert;

import com.linkroa.deepdataagent.datasource.application.command.CreateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.command.UpdateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.domain.model.*;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * 数据源组装器
 * <p>负责Command到领域模型的转换，含合并与默认值逻辑。</p>
 */
@Mapper
public interface DatasourceConvert {

    DatasourceConvert INSTANCE = Mappers.getMapper(DatasourceConvert.class);

    /**
     * 创建命令 → 领域模型（归属者由调用方从请求身份解析后显式传入，MUST NOT 由命令携带）。
     *
     * @param command 创建命令
     * @param ownerId 归属用户 ID（必填，创建即定）
     * @return 待落库的数据源连接领域模型
     */
    default DatasourceConnection toDatasourceConnection(CreateDatasourceCommand command, Long ownerId) {
        JdbcConnectionConfig jdbcConfig = null;

        if (command.type() == DatasourceType.JDBC && command.jdbcConfig() != null) {
            jdbcConfig = new JdbcConnectionConfig(
                    command.jdbcConfig().host(),
                    ObjectUtils.firstNonNull(command.jdbcConfig().port(), 0),
                    command.jdbcConfig().database(),
                    command.jdbcConfig().username(),
                    command.jdbcConfig().password(),
                    command.jdbcConfig().schema()
            );
        }

        return DatasourceConnection.create(
                command.name(),
                command.type(),
                command.subType(),
                command.description(),
                jdbcConfig,
                ownerId
        );
    }

    default DatasourceConnection toDatasourceConnection(UpdateDatasourceCommand command, DatasourceConnection existing) {
        JdbcConnectionConfig jdbcConfig = existing.jdbcConnectionConfig();
        if (command.jdbcConfig() != null) {
            // 如果新密码为空,保留原有密码
            String password = StringUtils.isNotBlank(command.jdbcConfig().password())
                    ? command.jdbcConfig().password()
                    : (existing.jdbcConnectionConfig() != null ? existing.jdbcConnectionConfig().password() : "");
            jdbcConfig = new JdbcConnectionConfig(
                    command.jdbcConfig().host(),
                    ObjectUtils.firstNonNull(command.jdbcConfig().port(), existing.jdbcConnectionConfig() != null ? existing.jdbcConnectionConfig().port() : 0),
                    command.jdbcConfig().database(),
                    command.jdbcConfig().username(),
                    password,
                    command.jdbcConfig().schema()
            );
        }
        return new DatasourceConnection(
                existing.id(),
                StringUtils.defaultIfBlank(command.name(), existing.name()),
                existing.type(),
                existing.subType(),
                existing.status(),
                jdbcConfig,
                StringUtils.defaultIfBlank(command.description(), existing.description()),
                // 归属不可变：更新链路原样携带既有归属者
                existing.ownerId(),
                existing.createdAt(),
                existing.updatedAt(),
                existing.createdBy(),
                existing.updatedBy()
        );
    }
}