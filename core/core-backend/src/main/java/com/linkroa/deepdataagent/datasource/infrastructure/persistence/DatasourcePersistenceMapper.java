package com.linkroa.deepdataagent.datasource.infrastructure.persistence;

import com.linkroa.deepdataagent.datasource.domain.model.*;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceStatus;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.HttpMethod;
import com.linkroa.deepdataagent.datasource.domain.model.enums.JdbcType;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.*;
import com.linkroa.deepdataagent.datasource.infrastructure.util.PasswordEncryptionUtil;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 领域对象 ⇄ 持久化实体的 MapStruct 转换器。
 * <p>DatabaseSchema / TableInfo / ColumnInfo / ApiField 的字段一一对应，由 MapStruct 自动生成映射；
 * DatasourceConnection / ApiSchema 涉及 jsonb 序列化与密码加密边界，以 default 方法保留原有行为。</p>
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface DatasourcePersistenceMapper {

    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // ===== DatabaseSchema =====

    DatabaseSchemaEntity toEntity(DatabaseSchema schema);

    DatabaseSchema toDomain(DatabaseSchemaEntity entity);

    // ===== TableInfo =====

    TableInfoEntity toEntity(TableInfo tableInfo);

    TableInfo toDomain(TableInfoEntity entity);

    // ===== ColumnInfo =====

    ColumnInfoEntity toEntity(ColumnInfo columnInfo);

    ColumnInfo toDomain(ColumnInfoEntity entity);

    // ===== ApiField =====

    ApiFieldEntity toEntity(ApiField apiField);

    ApiField toDomain(ApiFieldEntity entity);

    // ===== DatasourceConnection（含 jsonb 加密序列化） =====

    default DatasourceConnectionEntity toEntity(DatasourceConnection connection, PasswordEncryptionUtil encryptionUtil) {
        DatasourceConnectionEntity entity = new DatasourceConnectionEntity();
        entity.setId(connection.id());
        entity.setName(connection.name());
        entity.setType(connection.type() != null ? connection.type().name() : null);
        entity.setSubType(connection.subType() != null ? connection.subType().name() : null);
        entity.setStatus(connection.status() != null ? connection.status().name() : null);
        entity.setJdbcConnectionConfig(toJson(encryptJdbcConfig(connection.jdbcConnectionConfig(), encryptionUtil)));
        entity.setDescription(connection.description());
        entity.setCreatedAt(connection.createdAt());
        entity.setUpdatedAt(connection.updatedAt());
        entity.setCreatedBy(connection.createdBy());
        entity.setUpdatedBy(connection.updatedBy());
        return entity;
    }

    default DatasourceConnection toDomain(DatasourceConnectionEntity entity, PasswordEncryptionUtil encryptionUtil) {
        if (entity == null) {
            return null;
        }
        return new DatasourceConnection(
                entity.getId(),
                entity.getName(),
                entity.getType() != null ? DatasourceType.valueOf(entity.getType()) : null,
                entity.getSubType() != null ? JdbcType.valueOf(entity.getSubType()) : null,
                entity.getStatus() != null && !entity.getStatus().isBlank()
                        ? DatasourceStatus.valueOf(entity.getStatus())
                        : DatasourceStatus.ENABLED,
                decryptJdbcConfig(fromJson(entity.getJdbcConnectionConfig(), JdbcConnectionConfig.class), encryptionUtil),
                entity.getDescription(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getCreatedBy(),
                entity.getUpdatedBy()
        );
    }

    // ===== ApiSchema（含 jsonb 加密序列化） =====

    default ApiSchemaEntity toEntity(ApiSchema apiSchema, PasswordEncryptionUtil encryptionUtil) {
        ApiSchemaEntity entity = new ApiSchemaEntity();
        entity.setId(apiSchema.id());
        entity.setConnectionId(apiSchema.connectionId());
        entity.setName(apiSchema.name());
        entity.setUrl(apiSchema.url());
        entity.setMethod(apiSchema.method() != null ? apiSchema.method().name() : "");
        entity.setConfig(toJson(encryptApiRequestConfig(apiSchema.config(), encryptionUtil)));
        entity.setCreatedAt(apiSchema.createdAt());
        entity.setUpdatedAt(apiSchema.updatedAt());
        entity.setCreatedBy(apiSchema.createdBy());
        entity.setUpdatedBy(apiSchema.updatedBy());
        return entity;
    }

    default ApiSchema toDomain(ApiSchemaEntity entity, PasswordEncryptionUtil encryptionUtil) {
        if (entity == null) {
            return null;
        }
        ApiRequestConfig config = fromJson(entity.getConfig(), ApiRequestConfig.class);
        if (config == null) {
            config = ApiRequestConfig.defaultConfig();
        }
        return new ApiSchema(
                entity.getId(),
                entity.getConnectionId(),
                entity.getName(),
                entity.getUrl(),
                entity.getMethod() != null && !entity.getMethod().isBlank() ? HttpMethod.valueOf(entity.getMethod()) : null,
                decryptApiRequestConfig(config, encryptionUtil),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getCreatedBy(),
                entity.getUpdatedBy()
        );
    }

    // ===== JSON 序列化 =====

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize datasource payload", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize datasource payload", e);
        }
    }

    // ===== JDBC 连接配置加解密 =====

    private JdbcConnectionConfig encryptJdbcConfig(JdbcConnectionConfig config, PasswordEncryptionUtil encryptionUtil) {
        if (config == null || encryptionUtil == null) {
            return config;
        }
        return new JdbcConnectionConfig(
                config.host(),
                config.port(),
                config.database(),
                config.username(),
                encryptionUtil.encrypt(config.password()),
                config.schema()
        );
    }

    private JdbcConnectionConfig decryptJdbcConfig(JdbcConnectionConfig config, PasswordEncryptionUtil encryptionUtil) {
        if (config == null || encryptionUtil == null) {
            return config;
        }
        return new JdbcConnectionConfig(
                config.host(),
                config.port(),
                config.database(),
                config.username(),
                encryptionUtil.decrypt(config.password()),
                config.schema()
        );
    }

    // ===== API 请求配置加解密 =====

    private ApiRequestConfig encryptApiRequestConfig(ApiRequestConfig config, PasswordEncryptionUtil encryptionUtil) {
        if (config == null || encryptionUtil == null) {
            return config;
        }
        ApiAuthConfig encryptedAuthConfig = encryptApiAuthConfig(config.authConfig(), encryptionUtil);
        return new ApiRequestConfig(
                config.headers(),
                config.params(),
                config.body(),
                config.bodyType(),
                config.jsonPathConfig(),
                config.timeout(),
                config.retryCount(),
                encryptedAuthConfig,
                config.paginationConfig(),
                config.preOperationConfigs()
        );
    }

    private ApiRequestConfig decryptApiRequestConfig(ApiRequestConfig config, PasswordEncryptionUtil encryptionUtil) {
        if (config == null || encryptionUtil == null) {
            return config;
        }
        ApiAuthConfig decryptedAuthConfig = decryptApiAuthConfig(config.authConfig(), encryptionUtil);
        return new ApiRequestConfig(
                config.headers(),
                config.params(),
                config.body(),
                config.bodyType(),
                config.jsonPathConfig(),
                config.timeout(),
                config.retryCount(),
                decryptedAuthConfig,
                config.paginationConfig(),
                config.preOperationConfigs()
        );
    }

    private ApiAuthConfig encryptApiAuthConfig(ApiAuthConfig config, PasswordEncryptionUtil encryptionUtil) {
        if (config == null || encryptionUtil == null) {
            return config;
        }
        return new ApiAuthConfig(
                config.authType(),
                config.username(),
                config.password() != null ? encryptionUtil.encrypt(config.password()) : null
        );
    }

    private ApiAuthConfig decryptApiAuthConfig(ApiAuthConfig config, PasswordEncryptionUtil encryptionUtil) {
        if (config == null || encryptionUtil == null) {
            return config;
        }
        return new ApiAuthConfig(
                config.authType(),
                config.username(),
                config.password() != null ? encryptionUtil.decrypt(config.password()) : null
        );
    }
}