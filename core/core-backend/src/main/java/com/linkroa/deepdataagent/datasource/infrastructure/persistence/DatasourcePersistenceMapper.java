package com.linkroa.deepdataagent.datasource.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkroa.deepdataagent.datasource.domain.model.*;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceStatus;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.HttpMethod;
import com.linkroa.deepdataagent.datasource.domain.model.enums.JdbcType;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.*;
import com.linkroa.deepdataagent.datasource.infrastructure.util.PasswordEncryptionUtil;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class DatasourcePersistenceMapper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DatasourcePersistenceMapper() {
    }

    public static DatasourceConnectionEntity toEntity(DatasourceConnection connection, PasswordEncryptionUtil encryptionUtil) {
        DatasourceConnectionEntity entity = new DatasourceConnectionEntity();
        entity.setId(connection.id());
        entity.setName(connection.name());
        entity.setType(connection.type() != null ? connection.type().name() : null);
        entity.setSubType(connection.subType() != null ? connection.subType().name() : null);
        entity.setStatus(connection.status() != null ? connection.status().name() : null);
        entity.setJdbcConnectionConfig(toJson(encryptJdbcConfig(connection.jdbcConnectionConfig(), encryptionUtil)));
        entity.setApiConnectionConfig(toJson(connection.apiConnectionConfig()));
        entity.setApiAuthConfig(toJson(encryptApiAuthConfig(connection.apiAuthConfig(), encryptionUtil)));
        entity.setDescription(connection.description());
        entity.setCreatedAt(formatTime(connection.createdAt()));
        entity.setUpdatedAt(formatTime(connection.updatedAt()));
        entity.setCreatedBy(connection.createdBy());
        entity.setUpdatedBy(connection.updatedBy());
        return entity;
    }

    public static DatasourceConnection toDomain(DatasourceConnectionEntity entity, PasswordEncryptionUtil encryptionUtil) {
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
                fromJson(entity.getApiConnectionConfig(), ApiConnectionConfig.class),
                decryptApiAuthConfig(fromJson(entity.getApiAuthConfig(), ApiAuthConfig.class), encryptionUtil),
                entity.getDescription(),
                parseTime(entity.getCreatedAt()),
                parseTime(entity.getUpdatedAt()),
                entity.getCreatedBy(),
                entity.getUpdatedBy()
        );
    }

    public static DatabaseSchemaEntity toEntity(DatabaseSchema schema) {
        DatabaseSchemaEntity entity = new DatabaseSchemaEntity();
        entity.setId(schema.id());
        entity.setConnectionId(schema.connectionId());
        entity.setSchemaName(schema.schemaName());
        entity.setDescription(schema.description());
        entity.setCreatedAt(formatTime(schema.createdAt()));
        entity.setUpdatedAt(formatTime(schema.updatedAt()));
        return entity;
    }

    public static DatabaseSchema toDomain(DatabaseSchemaEntity entity) {
        if (entity == null) {
            return null;
        }
        return new DatabaseSchema(
                entity.getId(),
                entity.getConnectionId(),
                entity.getSchemaName(),
                entity.getDescription(),
                parseTime(entity.getCreatedAt()),
                parseTime(entity.getUpdatedAt())
        );
    }

    public static TableInfoEntity toEntity(TableInfo tableInfo) {
        TableInfoEntity entity = new TableInfoEntity();
        entity.setId(tableInfo.id());
        entity.setDatabaseSchemaId(tableInfo.databaseSchemaId());
        entity.setTableName(tableInfo.tableName());
        entity.setTableComment(tableInfo.tableComment());
        entity.setTableCustomComment(tableInfo.tableCustomComment());
        entity.setCreatedAt(formatTime(tableInfo.createdAt()));
        entity.setUpdatedAt(formatTime(tableInfo.updatedAt()));
        return entity;
    }

    public static TableInfo toDomain(TableInfoEntity entity) {
        if (entity == null) {
            return null;
        }
        return new TableInfo(
                entity.getId(),
                entity.getDatabaseSchemaId(),
                entity.getTableName(),
                entity.getTableComment(),
                entity.getTableCustomComment(),
                parseTime(entity.getCreatedAt()),
                parseTime(entity.getUpdatedAt())
        );
    }

    public static ColumnInfoEntity toEntity(ColumnInfo columnInfo) {
        ColumnInfoEntity entity = new ColumnInfoEntity();
        entity.setId(columnInfo.id());
        entity.setTableId(columnInfo.tableId());
        entity.setColumnName(columnInfo.columnName());
        entity.setDataType(columnInfo.dataType());
        entity.setColumnComment(columnInfo.columnComment());
        entity.setColumnCustomComment(columnInfo.columnCustomComment());
        entity.setCreatedAt(formatTime(columnInfo.createdAt()));
        entity.setUpdatedAt(formatTime(columnInfo.updatedAt()));
        return entity;
    }

    public static ColumnInfo toDomain(ColumnInfoEntity entity) {
        if (entity == null) {
            return null;
        }
        return new ColumnInfo(
                entity.getId(),
                entity.getTableId(),
                entity.getColumnName(),
                entity.getDataType(),
                entity.getColumnComment(),
                entity.getColumnCustomComment(),
                parseTime(entity.getCreatedAt()),
                parseTime(entity.getUpdatedAt())
        );
    }

    public static ApiSchemaEntity toEntity(ApiSchema apiSchema) {
        ApiSchemaEntity entity = new ApiSchemaEntity();
        entity.setId(apiSchema.id());
        entity.setConnectionId(apiSchema.connectionId());
        entity.setName(apiSchema.name());
        entity.setPath(apiSchema.path());
        entity.setMethod(apiSchema.method() != null ? apiSchema.method().name() : "");
        entity.setJsonPathConfig(apiSchema.jsonPathConfig());
        entity.setCreatedAt(formatTime(apiSchema.createdAt()));
        entity.setUpdatedAt(formatTime(apiSchema.updatedAt()));
        entity.setCreatedBy(apiSchema.createdBy());
        entity.setUpdatedBy(apiSchema.updatedBy());
        return entity;
    }

    public static ApiSchema toDomain(ApiSchemaEntity entity) {
        if (entity == null) {
            return null;
        }
        return new ApiSchema(
                entity.getId(),
                entity.getConnectionId(),
                entity.getName(),
                entity.getPath(),
                entity.getMethod() != null && !entity.getMethod().isBlank() ? HttpMethod.valueOf(entity.getMethod()) : null,
                entity.getJsonPathConfig(),
                parseTime(entity.getCreatedAt()),
                parseTime(entity.getUpdatedAt()),
                entity.getCreatedBy(),
                entity.getUpdatedBy()
        );
    }

    public static ApiFieldEntity toEntity(ApiField apiField) {
        ApiFieldEntity entity = new ApiFieldEntity();
        entity.setId(apiField.id());
        entity.setApiSchemaId(apiField.apiSchemaId());
        entity.setOriginalName(apiField.originalName());
        entity.setDisplayName(apiField.displayName());
        entity.setJsonPath(apiField.jsonPath());
        entity.setFieldType(apiField.fieldType());
        entity.setDescription(apiField.description());
        entity.setCreatedAt(formatTime(apiField.createdAt()));
        entity.setUpdatedAt(formatTime(apiField.updatedAt()));
        return entity;
    }

    public static ApiField toDomain(ApiFieldEntity entity) {
        if (entity == null) {
            return null;
        }
        return new ApiField(
                entity.getId(),
                entity.getApiSchemaId(),
                entity.getOriginalName(),
                entity.getDisplayName(),
                entity.getJsonPath(),
                entity.getFieldType(),
                entity.getDescription(),
                parseTime(entity.getCreatedAt()),
                parseTime(entity.getUpdatedAt())
        );
    }

    private static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize datasource payload", e);
        }
    }

    private static <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize datasource payload", e);
        }
    }

    private static JdbcConnectionConfig encryptJdbcConfig(JdbcConnectionConfig config, PasswordEncryptionUtil encryptionUtil) {
        if (config == null || encryptionUtil == null) {
            return config;
        }
        return new JdbcConnectionConfig(
                config.host(),
                config.port(),
                config.database(),
                config.username(),
                encryptionUtil.encrypt(config.password())
        );
    }

    private static JdbcConnectionConfig decryptJdbcConfig(JdbcConnectionConfig config, PasswordEncryptionUtil encryptionUtil) {
        if (config == null || encryptionUtil == null) {
            return config;
        }
        return new JdbcConnectionConfig(
                config.host(),
                config.port(),
                config.database(),
                config.username(),
                encryptionUtil.decrypt(config.password())
        );
    }

    private static ApiAuthConfig encryptApiAuthConfig(ApiAuthConfig config, PasswordEncryptionUtil encryptionUtil) {
        if (config == null || encryptionUtil == null) {
            return config;
        }
        return new ApiAuthConfig(
                config.authType(),
                config.username(),
                config.password() != null ? encryptionUtil.encrypt(config.password()) : null,
                config.token() != null ? encryptionUtil.encrypt(config.token()) : null
        );
    }

    private static ApiAuthConfig decryptApiAuthConfig(ApiAuthConfig config, PasswordEncryptionUtil encryptionUtil) {
        if (config == null || encryptionUtil == null) {
            return config;
        }
        return new ApiAuthConfig(
                config.authType(),
                config.username(),
                config.password() != null ? encryptionUtil.decrypt(config.password()) : null,
                config.token() != null ? encryptionUtil.decrypt(config.token()) : null
        );
    }

    private static String formatTime(LocalDateTime time) {
        if (ObjectUtils.isEmpty(time)) {
            return null;
        }
        return time.format(TIME_FORMATTER);
    }

    private static LocalDateTime parseTime(String timeStr) {
        if (StringUtils.isBlank(timeStr)) {
            return null;
        }
        try {
            return LocalDateTime.parse(timeStr, TIME_FORMATTER);
        } catch (Exception e) {
            // 如果解析失败，返回 null
            return null;
        }
    }
}
