package com.linkroa.deepdataagent.datasource.infrastructure.persistence;

import com.linkroa.deepdataagent.datasource.domain.model.*;
import com.linkroa.deepdataagent.datasource.domain.model.enums.*;
import com.linkroa.deepdataagent.datasource.infrastructure.config.EncryptionProperties;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.*;
import com.linkroa.deepdataagent.datasource.infrastructure.util.PasswordEncryptionUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DatasourcePersistenceMapperTest {

    private DatasourcePersistenceMapper persistenceMapper;
    private PasswordEncryptionUtil encryptionUtil;

    /** 固定带时区时刻（东八区，与部署时区约定 Asia/Shanghai 对齐） */
    private static final OffsetDateTime FIXED_TIME = OffsetDateTime.of(2024, 6, 15, 10, 30, 0, 0, ZoneOffset.ofHours(8));
    private static final String TEST_ENCRYPTION_KEY = "test-encryption-key-for-unit-test";

    @BeforeEach
    void setUp() {
        EncryptionProperties props = new EncryptionProperties();
        props.setKey(TEST_ENCRYPTION_KEY);
        encryptionUtil = new PasswordEncryptionUtil(props);
        persistenceMapper = Mappers.getMapper(DatasourcePersistenceMapper.class);
    }

    @Test
    void should_mapAllFields_when_toEntity_given_validApiSchema() {
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.BASIC_AUTH, "user1", "pass123");
        ApiPaginationConfig paginationConfig = new ApiPaginationConfig(ApiPaginationType.PAGE_BASED, "size", "page", "$.total", 20, 50);
        PreOperationConfig preOpConfig = new PreOperationConfig(true, "http://token.api", HttpMethod.POST, null, null, null, null, List.of());
        ApiRequestConfig requestConfig = new ApiRequestConfig(
                Map.of("X-Header", "value"), Map.of("param1", "v1"), "{\"key\":\"val\"}",
                BodyType.JSON, "$.data", 30, 3, authConfig, paginationConfig, List.of(preOpConfig)
        );
        ApiSchema apiSchema = new ApiSchema(1L, 10L, "users", "http://example.com/api/users", HttpMethod.GET,
                requestConfig, FIXED_TIME, FIXED_TIME, "admin", "admin2");

        ApiSchemaEntity entity = persistenceMapper.toEntity(apiSchema, encryptionUtil);

        assertEquals(1L, entity.getId());
        assertEquals(10L, entity.getConnectionId());
        assertEquals("users", entity.getName());
        assertEquals("http://example.com/api/users", entity.getUrl());
        assertEquals("GET", entity.getMethod());
        assertNotNull(entity.getConfig());
        assertFalse(entity.getConfig().contains("pass123"));
        assertTrue(encryptionUtil.isEncrypted(extractPasswordFromConfigJson(entity.getConfig())));
        assertEquals(FIXED_TIME, entity.getCreatedAt());
        assertEquals(FIXED_TIME, entity.getUpdatedAt());
        assertEquals("admin", entity.getCreatedBy());
        assertEquals("admin2", entity.getUpdatedBy());
    }

    @Test
    void should_mapAllFields_when_toDomain_given_validApiSchemaEntity() {
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.BASIC_AUTH, "user1", encryptionUtil.encrypt("pass123"));
        ApiRequestConfig requestConfig = new ApiRequestConfig(
                Map.of("X-Header", "value"), Map.of("param1", "v1"), "{\"key\":\"val\"}",
                BodyType.JSON, "$.data", 30, 3, authConfig, null, null
        );
        String configJson;
        try {
            configJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(requestConfig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ApiSchemaEntity entity = new ApiSchemaEntity();
        entity.setId(1L);
        entity.setConnectionId(10L);
        entity.setName("users");
        entity.setUrl("http://example.com/api/users");
        entity.setMethod("GET");
        entity.setConfig(configJson);
        entity.setCreatedAt(FIXED_TIME);
        entity.setUpdatedAt(FIXED_TIME);
        entity.setCreatedBy("admin");
        entity.setUpdatedBy("admin2");

        ApiSchema schema = persistenceMapper.toDomain(entity, encryptionUtil);

        assertEquals(1L, schema.id());
        assertEquals(10L, schema.connectionId());
        assertEquals("users", schema.name());
        assertEquals("http://example.com/api/users", schema.url());
        assertEquals(HttpMethod.GET, schema.method());
        assertNotNull(schema.config());
        assertEquals("pass123", schema.config().authConfig().password());
        assertEquals("user1", schema.config().authConfig().username());
        assertEquals(ApiAuthType.BASIC_AUTH, schema.config().authConfig().authType());
        assertEquals("value", schema.config().headers().get("X-Header"));
        assertEquals("v1", schema.config().params().get("param1"));
        assertEquals(BodyType.JSON, schema.config().bodyType());
        assertEquals("$.data", schema.config().jsonPathConfig());
        assertEquals(30, schema.config().timeout());
        assertEquals(3, schema.config().retryCount());
        assertEquals(FIXED_TIME, schema.createdAt());
        assertEquals(FIXED_TIME, schema.updatedAt());
        assertEquals("admin", schema.createdBy());
        assertEquals("admin2", schema.updatedBy());
    }

    @Test
    void should_returnNull_when_toDomain_given_nullApiSchemaEntity() {
        assertNull(persistenceMapper.toDomain((ApiSchemaEntity) null, encryptionUtil));
    }

    @Test
    void should_encryptPassword_when_toEntity_given_basicAuthConfig() {
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.BASIC_AUTH, "admin", "secret123");
        ApiRequestConfig requestConfig = new ApiRequestConfig(null, null, null, null, null, 10, null, authConfig, null, null);
        ApiSchema apiSchema = new ApiSchema(1L, 1L, "test", "http://test.com", HttpMethod.GET, requestConfig, null, null, null, null);

        ApiSchemaEntity entity = persistenceMapper.toEntity(apiSchema, encryptionUtil);

        assertNotNull(entity.getConfig());
        assertFalse(entity.getConfig().contains("secret123"));
        String encryptedPassword = extractPasswordFromConfigJson(entity.getConfig());
        assertTrue(encryptionUtil.isEncrypted(encryptedPassword));
        assertEquals("secret123", encryptionUtil.decrypt(encryptedPassword));
    }

    @Test
    void should_decryptPassword_when_toDomain_given_encryptedPassword() {
        String encryptedPassword = encryptionUtil.encrypt("secret123");
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.BASIC_AUTH, "admin", encryptedPassword);
        ApiRequestConfig requestConfig = new ApiRequestConfig(null, null, null, null, null, 10, null, authConfig, null, null);
        String configJson;
        try {
            configJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(requestConfig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ApiSchemaEntity entity = new ApiSchemaEntity();
        entity.setId(1L);
        entity.setConnectionId(1L);
        entity.setName("test");
        entity.setUrl("http://test.com");
        entity.setMethod("GET");
        entity.setConfig(configJson);

        ApiSchema schema = persistenceMapper.toDomain(entity, encryptionUtil);

        assertEquals("secret123", schema.config().authConfig().password());
    }

    @Test
    void should_notEncryptPassword_when_toEntity_given_noAuthConfig() {
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null);
        ApiRequestConfig requestConfig = new ApiRequestConfig(null, null, null, null, null, 10, null, authConfig, null, null);
        ApiSchema apiSchema = new ApiSchema(1L, 1L, "test", "http://test.com", HttpMethod.GET, requestConfig, null, null, null, null);

        ApiSchemaEntity entity = persistenceMapper.toEntity(apiSchema, encryptionUtil);

        assertNotNull(entity.getConfig());
    }

    @Test
    void should_handleNullConfig_when_toEntity_given_nullConfig() {
        ApiSchema apiSchema = new ApiSchema(1L, 1L, "test", "http://test.com", HttpMethod.GET, null, null, null, null, null);

        ApiSchemaEntity entity = persistenceMapper.toEntity(apiSchema, encryptionUtil);

        assertNull(entity.getConfig());
    }

    @Test
    void should_handleNullConfig_when_toDomain_given_nullConfigJson() {
        ApiSchemaEntity entity = new ApiSchemaEntity();
        entity.setId(1L);
        entity.setConnectionId(1L);
        entity.setName("test");
        entity.setUrl("http://test.com");
        entity.setMethod("GET");
        entity.setConfig(null);

        ApiSchema schema = persistenceMapper.toDomain(entity, encryptionUtil);

        assertNotNull(schema.config());
        assertEquals(180, schema.config().timeout());
    }

    @Test
    void should_handleBlankConfig_when_toDomain_given_blankConfigJson() {
        ApiSchemaEntity entity = new ApiSchemaEntity();
        entity.setId(1L);
        entity.setConnectionId(1L);
        entity.setName("test");
        entity.setUrl("http://test.com");
        entity.setMethod("GET");
        entity.setConfig("  ");

        ApiSchema schema = persistenceMapper.toDomain(entity, encryptionUtil);

        assertNotNull(schema.config());
        assertEquals(180, schema.config().timeout());
    }

    @Test
    void should_handleNullMethod_when_toEntity_given_nullMethod() {
        ApiSchema apiSchema = new ApiSchema(1L, 1L, "test", "http://test.com", null, null, null, null, null, null);

        ApiSchemaEntity entity = persistenceMapper.toEntity(apiSchema, encryptionUtil);

        assertEquals("", entity.getMethod());
    }

    @Test
    void should_handleBlankMethod_when_toDomain_given_blankMethod() {
        ApiSchemaEntity entity = new ApiSchemaEntity();
        entity.setId(1L);
        entity.setConnectionId(1L);
        entity.setName("test");
        entity.setUrl("http://test.com");
        entity.setMethod("");
        entity.setConfig(null);

        ApiSchema schema = persistenceMapper.toDomain(entity, encryptionUtil);

        assertNull(schema.method());
    }

    @Test
    void should_handleNullEncryptionUtil_when_toEntity_given_nullEncryptionUtil() {
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.BASIC_AUTH, "user", "pass");
        ApiRequestConfig requestConfig = new ApiRequestConfig(null, null, null, null, null, 10, null, authConfig, null, null);
        ApiSchema apiSchema = new ApiSchema(1L, 1L, "test", "http://test.com", HttpMethod.GET, requestConfig, null, null, null, null);

        ApiSchemaEntity entity = persistenceMapper.toEntity(apiSchema, null);

        assertNotNull(entity.getConfig());
        assertTrue(entity.getConfig().contains("pass"));
    }

    @Test
    void should_handleNullEncryptionUtil_when_toDomain_given_nullEncryptionUtil() {
        String encryptedPassword = encryptionUtil.encrypt("pass");
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.BASIC_AUTH, "user", encryptedPassword);
        ApiRequestConfig requestConfig = new ApiRequestConfig(null, null, null, null, null, 10, null, authConfig, null, null);
        String configJson;
        try {
            configJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(requestConfig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ApiSchemaEntity entity = new ApiSchemaEntity();
        entity.setId(1L);
        entity.setConnectionId(1L);
        entity.setName("test");
        entity.setUrl("http://test.com");
        entity.setMethod("GET");
        entity.setConfig(configJson);

        ApiSchema schema = persistenceMapper.toDomain(entity, null);

        assertEquals(encryptedPassword, schema.config().authConfig().password());
    }

    @Test
    void should_roundTrip_when_toEntityAndToDomain_given_completeApiSchema() {
        ApiAuthConfig authConfig = new ApiAuthConfig(ApiAuthType.BASIC_AUTH, "admin", "mySecret");
        ApiPaginationConfig paginationConfig = new ApiPaginationConfig(ApiPaginationType.PAGE_BASED, "size", "page", "$.total", 20, 50);
        PreOperationConfig preOpConfig = new PreOperationConfig(true, "http://token.api", HttpMethod.POST, Map.of("X-Token", "val"), null, null, null, List.of());
        ApiRequestConfig requestConfig = new ApiRequestConfig(
                Map.of("Accept", "application/json"), Map.of("page", "1"), "{\"query\":\"test\"}",
                BodyType.JSON, "$.items", 60, 5, authConfig, paginationConfig, List.of(preOpConfig)
        );
        ApiSchema original = new ApiSchema(1L, 10L, "products", "http://shop.com/api/products", HttpMethod.POST,
                requestConfig, FIXED_TIME, FIXED_TIME, "creator", "updater");

        ApiSchemaEntity entity = persistenceMapper.toEntity(original, encryptionUtil);
        ApiSchema restored = persistenceMapper.toDomain(entity, encryptionUtil);

        assertEquals(original.id(), restored.id());
        assertEquals(original.connectionId(), restored.connectionId());
        assertEquals(original.name(), restored.name());
        assertEquals(original.url(), restored.url());
        assertEquals(original.method(), restored.method());
        assertEquals(original.config().headers(), restored.config().headers());
        assertEquals(original.config().params(), restored.config().params());
        assertEquals(original.config().body(), restored.config().body());
        assertEquals(original.config().bodyType(), restored.config().bodyType());
        assertEquals(original.config().jsonPathConfig(), restored.config().jsonPathConfig());
        assertEquals(original.config().timeout(), restored.config().timeout());
        assertEquals(original.config().retryCount(), restored.config().retryCount());
        assertEquals(original.config().authConfig().authType(), restored.config().authConfig().authType());
        assertEquals(original.config().authConfig().username(), restored.config().authConfig().username());
        assertEquals("mySecret", restored.config().authConfig().password());
        assertEquals(original.config().paginationConfig(), restored.config().paginationConfig());
        assertEquals(original.createdAt(), restored.createdAt());
        assertEquals(original.updatedAt(), restored.updatedAt());
        assertEquals(original.createdBy(), restored.createdBy());
        assertEquals(original.updatedBy(), restored.updatedBy());
    }

    @Test
    void should_mapAllFields_when_toEntity_given_validDatasourceConnection() {
        JdbcConnectionConfig jdbcConfig = new JdbcConnectionConfig("localhost", 3306, "mydb", "root", "pass123", null);
        DatasourceConnection connection = new DatasourceConnection(1L, "testDs", DatasourceType.JDBC, JdbcType.MYSQL,
                DatasourceStatus.ENABLED, jdbcConfig, "desc", FIXED_TIME, FIXED_TIME, "admin", "admin2");

        DatasourceConnectionEntity entity = persistenceMapper.toEntity(connection, encryptionUtil);

        assertEquals(1L, entity.getId());
        assertEquals("testDs", entity.getName());
        assertEquals("JDBC", entity.getType());
        assertEquals("MYSQL", entity.getSubType());
        assertEquals("ENABLED", entity.getStatus());
        assertNotNull(entity.getJdbcConnectionConfig());
        assertFalse(entity.getJdbcConnectionConfig().contains("pass123"));
        String encryptedPassword = extractPasswordFromJdbcConfigJson(entity.getJdbcConnectionConfig());
        assertTrue(encryptionUtil.isEncrypted(encryptedPassword));
        assertEquals("pass123", encryptionUtil.decrypt(encryptedPassword));
        assertEquals("desc", entity.getDescription());
        assertEquals(FIXED_TIME, entity.getCreatedAt());
        assertEquals("admin", entity.getCreatedBy());
    }

    @Test
    void should_mapAllFields_when_toDomain_given_validDatasourceConnectionEntity() {
        String encryptedPassword = encryptionUtil.encrypt("pass123");
        JdbcConnectionConfig jdbcConfig = new JdbcConnectionConfig("localhost", 3306, "mydb", "root", encryptedPassword, null);
        String jdbcJson;
        try {
            jdbcJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(jdbcConfig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        DatasourceConnectionEntity entity = new DatasourceConnectionEntity();
        entity.setId(1L);
        entity.setName("testDs");
        entity.setType("JDBC");
        entity.setSubType("MYSQL");
        entity.setStatus("ENABLED");
        entity.setJdbcConnectionConfig(jdbcJson);
        entity.setDescription("desc");
        entity.setCreatedAt(FIXED_TIME);
        entity.setUpdatedAt(FIXED_TIME);
        entity.setCreatedBy("admin");
        entity.setUpdatedBy("admin2");

        DatasourceConnection connection = persistenceMapper.toDomain(entity, encryptionUtil);

        assertEquals(1L, connection.id());
        assertEquals("testDs", connection.name());
        assertEquals(DatasourceType.JDBC, connection.type());
        assertEquals(JdbcType.MYSQL, connection.subType());
        assertEquals(DatasourceStatus.ENABLED, connection.status());
        assertNotNull(connection.jdbcConnectionConfig());
        assertEquals("pass123", connection.jdbcConnectionConfig().password());
        assertEquals("localhost", connection.jdbcConnectionConfig().host());
        assertEquals(3306, connection.jdbcConnectionConfig().port());
        assertEquals("mydb", connection.jdbcConnectionConfig().database());
        assertEquals("root", connection.jdbcConnectionConfig().username());
        assertEquals("desc", connection.description());
        assertEquals(FIXED_TIME, connection.createdAt());
        assertEquals("admin", connection.createdBy());
    }

    @Test
    void should_returnNull_when_toDomain_given_nullDatasourceConnectionEntity() {
        assertNull(persistenceMapper.toDomain((DatasourceConnectionEntity) null, encryptionUtil));
    }

    @Test
    void should_defaultToEnabled_when_toDomain_given_blankStatus() {
        JdbcConnectionConfig jdbcConfig = new JdbcConnectionConfig("localhost", 3306, "mydb", "root", "pass", null);
        String jdbcJson;
        try {
            jdbcJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(jdbcConfig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        DatasourceConnectionEntity entity = new DatasourceConnectionEntity();
        entity.setId(1L);
        entity.setName("testDs");
        entity.setType("JDBC");
        entity.setSubType("MYSQL");
        entity.setStatus("");
        entity.setJdbcConnectionConfig(jdbcJson);

        DatasourceConnection connection = persistenceMapper.toDomain(entity, encryptionUtil);

        assertEquals(DatasourceStatus.ENABLED, connection.status());
    }

    @Test
    void should_roundTrip_when_toEntityAndToDomain_given_jdbcDatasourceConnection() {
        JdbcConnectionConfig jdbcConfig = new JdbcConnectionConfig("localhost", 5432, "testdb", "admin", "secret", null);
        DatasourceConnection original = new DatasourceConnection(1L, "myDs", DatasourceType.JDBC, JdbcType.MYSQL,
                DatasourceStatus.ENABLED, jdbcConfig, "my description", FIXED_TIME, FIXED_TIME, "creator", "updater");

        DatasourceConnectionEntity entity = persistenceMapper.toEntity(original, encryptionUtil);
        DatasourceConnection restored = persistenceMapper.toDomain(entity, encryptionUtil);

        assertEquals(original.id(), restored.id());
        assertEquals(original.name(), restored.name());
        assertEquals(original.type(), restored.type());
        assertEquals(original.subType(), restored.subType());
        assertEquals(original.status(), restored.status());
        assertEquals(original.jdbcConnectionConfig().host(), restored.jdbcConnectionConfig().host());
        assertEquals(original.jdbcConnectionConfig().port(), restored.jdbcConnectionConfig().port());
        assertEquals(original.jdbcConnectionConfig().database(), restored.jdbcConnectionConfig().database());
        assertEquals(original.jdbcConnectionConfig().username(), restored.jdbcConnectionConfig().username());
        assertEquals("secret", restored.jdbcConnectionConfig().password());
        assertEquals(original.description(), restored.description());
    }

    @Test
    void should_encryptJdbcPassword_when_toEntity_given_jdbcConnection() {
        JdbcConnectionConfig jdbcConfig = new JdbcConnectionConfig("host", 3306, "db", "user", "plainPassword", null);
        DatasourceConnection connection = new DatasourceConnection(1L, "testDs", DatasourceType.JDBC, JdbcType.MYSQL,
                DatasourceStatus.ENABLED, jdbcConfig, null, null, null, null, null);

        DatasourceConnectionEntity entity = persistenceMapper.toEntity(connection, encryptionUtil);

        assertFalse(entity.getJdbcConnectionConfig().contains("plainPassword"));
        String encryptedPassword = extractPasswordFromJdbcConfigJson(entity.getJdbcConnectionConfig());
        assertTrue(encryptionUtil.isEncrypted(encryptedPassword));
        assertEquals("plainPassword", encryptionUtil.decrypt(encryptedPassword));
    }

    @Test
    void should_decryptJdbcPassword_when_toDomain_given_encryptedPassword() {
        String encryptedPassword = encryptionUtil.encrypt("plainPassword");
        JdbcConnectionConfig jdbcConfig = new JdbcConnectionConfig("host", 3306, "db", "user", encryptedPassword, null);
        String jdbcJson;
        try {
            jdbcJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(jdbcConfig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        DatasourceConnectionEntity entity = new DatasourceConnectionEntity();
        entity.setId(1L);
        entity.setName("testDs");
        entity.setType("JDBC");
        entity.setSubType("MYSQL");
        entity.setStatus("ENABLED");
        entity.setJdbcConnectionConfig(jdbcJson);

        DatasourceConnection connection = persistenceMapper.toDomain(entity, encryptionUtil);

        assertEquals("plainPassword", connection.jdbcConnectionConfig().password());
    }

    @Test
    void should_handleNullJdbcConfig_when_toEntity_given_nullJdbcConfig() {
        DatasourceConnection connection = new DatasourceConnection(1L, "apiDs", DatasourceType.API, null,
                DatasourceStatus.ENABLED, null, null, null, null, null, null);

        DatasourceConnectionEntity entity = persistenceMapper.toEntity(connection, encryptionUtil);

        assertNull(entity.getJdbcConnectionConfig());
    }

    @Test
    void should_handleNullJdbcConfig_when_toDomain_given_nullJdbcConfigJson() {
        DatasourceConnectionEntity entity = new DatasourceConnectionEntity();
        entity.setId(1L);
        entity.setName("apiDs");
        entity.setType("API");
        entity.setSubType(null);
        entity.setStatus("ENABLED");
        entity.setJdbcConnectionConfig(null);

        DatasourceConnection connection = persistenceMapper.toDomain(entity, encryptionUtil);

        assertNull(connection.jdbcConnectionConfig());
    }

    @Test
    void should_handleNullEncryptionUtil_when_toEntity_given_nullEncryptionUtilForJdbc() {
        JdbcConnectionConfig jdbcConfig = new JdbcConnectionConfig("host", 3306, "db", "user", "plain", null);
        DatasourceConnection connection = new DatasourceConnection(1L, "testDs", DatasourceType.JDBC, JdbcType.MYSQL,
                DatasourceStatus.ENABLED, jdbcConfig, null, null, null, null, null);

        DatasourceConnectionEntity entity = persistenceMapper.toEntity(connection, null);

        assertTrue(entity.getJdbcConnectionConfig().contains("plain"));
    }

    @Test
    void should_handleNullEncryptionUtil_when_toDomain_given_nullEncryptionUtilForJdbc() {
        String encryptedPassword = encryptionUtil.encrypt("plain");
        JdbcConnectionConfig jdbcConfig = new JdbcConnectionConfig("host", 3306, "db", "user", encryptedPassword, null);
        String jdbcJson;
        try {
            jdbcJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(jdbcConfig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        DatasourceConnectionEntity entity = new DatasourceConnectionEntity();
        entity.setId(1L);
        entity.setName("testDs");
        entity.setType("JDBC");
        entity.setSubType("MYSQL");
        entity.setStatus("ENABLED");
        entity.setJdbcConnectionConfig(jdbcJson);

        DatasourceConnection connection = persistenceMapper.toDomain(entity, null);

        assertEquals(encryptedPassword, connection.jdbcConnectionConfig().password());
    }

    @Test
    void should_mapAllFields_when_toEntity_given_validDatabaseSchema() {
        DatabaseSchema schema = new DatabaseSchema(1L, 10L, "public", "main schema", FIXED_TIME, FIXED_TIME);

        DatabaseSchemaEntity entity = persistenceMapper.toEntity(schema);

        assertEquals(1L, entity.getId());
        assertEquals(10L, entity.getConnectionId());
        assertEquals("public", entity.getSchemaName());
        assertEquals("main schema", entity.getDescription());
        assertEquals(FIXED_TIME, entity.getCreatedAt());
    }

    @Test
    void should_mapAllFields_when_toDomain_given_validDatabaseSchemaEntity() {
        DatabaseSchemaEntity entity = new DatabaseSchemaEntity();
        entity.setId(1L);
        entity.setConnectionId(10L);
        entity.setSchemaName("public");
        entity.setDescription("desc");
        entity.setCreatedAt(FIXED_TIME);
        entity.setUpdatedAt(FIXED_TIME);

        DatabaseSchema schema = persistenceMapper.toDomain(entity);

        assertEquals(1L, schema.id());
        assertEquals(10L, schema.connectionId());
        assertEquals("public", schema.schemaName());
        assertEquals("desc", schema.description());
        assertEquals(FIXED_TIME, schema.createdAt());
        assertEquals(FIXED_TIME, schema.updatedAt());
    }

    @Test
    void should_returnNull_when_toDomain_given_nullDatabaseSchemaEntity() {
        assertNull(persistenceMapper.toDomain((DatabaseSchemaEntity) null));
    }

    @Test
    void should_mapAllFields_when_toEntity_given_validTableInfo() {
        TableInfo tableInfo = new TableInfo(1L, 10L, "users", "user table", "custom", FIXED_TIME, FIXED_TIME);

        TableInfoEntity entity = persistenceMapper.toEntity(tableInfo);

        assertEquals(1L, entity.getId());
        assertEquals(10L, entity.getDatabaseSchemaId());
        assertEquals("users", entity.getTableName());
        assertEquals("user table", entity.getTableComment());
        assertEquals("custom", entity.getTableCustomComment());
        assertEquals(FIXED_TIME, entity.getCreatedAt());
    }

    @Test
    void should_mapAllFields_when_toDomain_given_validTableInfoEntity() {
        TableInfoEntity entity = new TableInfoEntity();
        entity.setId(1L);
        entity.setDatabaseSchemaId(10L);
        entity.setTableName("users");
        entity.setTableComment("user table");
        entity.setTableCustomComment("custom");
        entity.setCreatedAt(FIXED_TIME);
        entity.setUpdatedAt(FIXED_TIME);

        TableInfo tableInfo = persistenceMapper.toDomain(entity);

        assertEquals(1L, tableInfo.id());
        assertEquals(10L, tableInfo.databaseSchemaId());
        assertEquals("users", tableInfo.tableName());
        assertEquals("user table", tableInfo.tableComment());
        assertEquals("custom", tableInfo.tableCustomComment());
        assertEquals(FIXED_TIME, tableInfo.createdAt());
        assertEquals(FIXED_TIME, tableInfo.updatedAt());
    }

    @Test
    void should_returnNull_when_toDomain_given_nullTableInfoEntity() {
        assertNull(persistenceMapper.toDomain((TableInfoEntity) null));
    }

    @Test
    void should_mapAllFields_when_toEntity_given_validColumnInfo() {
        ColumnInfo columnInfo = new ColumnInfo(1L, 10L, "id", "INTEGER", "primary key", "custom", FIXED_TIME, FIXED_TIME);

        ColumnInfoEntity entity = persistenceMapper.toEntity(columnInfo);

        assertEquals(1L, entity.getId());
        assertEquals(10L, entity.getTableId());
        assertEquals("id", entity.getColumnName());
        assertEquals("INTEGER", entity.getDataType());
        assertEquals("primary key", entity.getColumnComment());
        assertEquals("custom", entity.getColumnCustomComment());
        assertEquals(FIXED_TIME, entity.getCreatedAt());
    }

    @Test
    void should_mapAllFields_when_toDomain_given_validColumnInfoEntity() {
        ColumnInfoEntity entity = new ColumnInfoEntity();
        entity.setId(1L);
        entity.setTableId(10L);
        entity.setColumnName("id");
        entity.setDataType("INTEGER");
        entity.setColumnComment("primary key");
        entity.setColumnCustomComment("custom");
        entity.setCreatedAt(FIXED_TIME);
        entity.setUpdatedAt(FIXED_TIME);

        ColumnInfo columnInfo = persistenceMapper.toDomain(entity);

        assertEquals(1L, columnInfo.id());
        assertEquals(10L, columnInfo.tableId());
        assertEquals("id", columnInfo.columnName());
        assertEquals("INTEGER", columnInfo.dataType());
        assertEquals("primary key", columnInfo.columnComment());
        assertEquals("custom", columnInfo.columnCustomComment());
        assertEquals(FIXED_TIME, columnInfo.createdAt());
        assertEquals(FIXED_TIME, columnInfo.updatedAt());
    }

    @Test
    void should_returnNull_when_toDomain_given_nullColumnInfoEntity() {
        assertNull(persistenceMapper.toDomain((ColumnInfoEntity) null));
    }

    @Test
    void should_mapAllFields_when_toEntity_given_validApiField() {
        ApiField apiField = new ApiField(1L, 10L, "userName", "User Name", "$.user.name", "STRING", "desc", FIXED_TIME, FIXED_TIME);

        ApiFieldEntity entity = persistenceMapper.toEntity(apiField);

        assertEquals(1L, entity.getId());
        assertEquals(10L, entity.getApiSchemaId());
        assertEquals("userName", entity.getOriginalName());
        assertEquals("User Name", entity.getDisplayName());
        assertEquals("$.user.name", entity.getJsonPath());
        assertEquals("STRING", entity.getFieldType());
        assertEquals("desc", entity.getDescription());
        assertEquals(FIXED_TIME, entity.getCreatedAt());
    }

    @Test
    void should_mapAllFields_when_toDomain_given_validApiFieldEntity() {
        ApiFieldEntity entity = new ApiFieldEntity();
        entity.setId(1L);
        entity.setApiSchemaId(10L);
        entity.setOriginalName("userName");
        entity.setDisplayName("User Name");
        entity.setJsonPath("$.user.name");
        entity.setFieldType("STRING");
        entity.setDescription("desc");
        entity.setCreatedAt(FIXED_TIME);
        entity.setUpdatedAt(FIXED_TIME);

        ApiField apiField = persistenceMapper.toDomain(entity);

        assertEquals(1L, apiField.id());
        assertEquals(10L, apiField.apiSchemaId());
        assertEquals("userName", apiField.originalName());
        assertEquals("User Name", apiField.displayName());
        assertEquals("$.user.name", apiField.jsonPath());
        assertEquals("STRING", apiField.fieldType());
        assertEquals("desc", apiField.description());
        assertEquals(FIXED_TIME, apiField.createdAt());
        assertEquals(FIXED_TIME, apiField.updatedAt());
    }

    @Test
    void should_returnNull_when_toDomain_given_nullApiFieldEntity() {
        assertNull(persistenceMapper.toDomain((ApiFieldEntity) null));
    }

    @Test
    void should_handleNullTimes_when_toEntity_given_nullTimestamps() {
        ApiSchema apiSchema = new ApiSchema(1L, 1L, "test", "http://test.com", HttpMethod.GET, null, null, null, null, null);

        ApiSchemaEntity entity = persistenceMapper.toEntity(apiSchema, encryptionUtil);

        assertNull(entity.getCreatedAt());
        assertNull(entity.getUpdatedAt());
    }

    @Test
    void should_returnNullTimeFields_when_toDomain_given_nullTimeFields() {
        ApiSchemaEntity entity = new ApiSchemaEntity();
        entity.setId(1L);
        entity.setConnectionId(1L);
        entity.setName("test");
        entity.setUrl("http://test.com");
        entity.setMethod("GET");
        entity.setCreatedAt(null);
        entity.setUpdatedAt(null);

        ApiSchema schema = persistenceMapper.toDomain(entity, encryptionUtil);

        assertNull(schema.createdAt());
        assertNull(schema.updatedAt());
    }

    @Test
    void should_preservePreOperationConfigs_when_roundTrip_given_configWithPreOps() {
        PreOperationConfig preOp = new PreOperationConfig(
                true, "http://auth.api/token", HttpMethod.POST,
                Map.of("Content-Type", "application/json"), null, "{\"grant_type\":\"client_credentials\"}",
                BodyType.JSON, List.of(new ParamMapping("accessToken", "header", "$.access_token"))
        );
        ApiRequestConfig requestConfig = new ApiRequestConfig(
                Map.of("Authorization", "${accessToken}"), null, null, null, "$.data", 30, 2,
                new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null), null, List.of(preOp)
        );
        ApiSchema original = new ApiSchema(1L, 1L, "test", "http://test.com", HttpMethod.GET, requestConfig, null, null, null, null);

        ApiSchemaEntity entity = persistenceMapper.toEntity(original, encryptionUtil);
        ApiSchema restored = persistenceMapper.toDomain(entity, encryptionUtil);

        assertNotNull(restored.config().preOperationConfigs());
        assertEquals(1, restored.config().preOperationConfigs().size());
        PreOperationConfig restoredPreOp = restored.config().preOperationConfigs().getFirst();
        assertTrue(restoredPreOp.enabled());
        assertEquals("http://auth.api/token", restoredPreOp.url());
        assertEquals(HttpMethod.POST, restoredPreOp.method());
        assertNotNull(restoredPreOp.paramMappings());
        assertEquals(1, restoredPreOp.paramMappings().size());
        assertEquals("accessToken", restoredPreOp.paramMappings().getFirst().paramName());
    }

    @Test
    void should_preservePaginationConfig_when_roundTrip_given_configWithPagination() {
        ApiPaginationConfig pagination = new ApiPaginationConfig(ApiPaginationType.PAGE_BASED, "pageNum", "pageSize", "$.data.total", 50, 100);
        ApiRequestConfig requestConfig = new ApiRequestConfig(null, null, null, null, "$.data.records", 30, null,
                new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null), pagination, null);
        ApiSchema original = new ApiSchema(1L, 1L, "test", "http://test.com", HttpMethod.GET, requestConfig, null, null, null, null);

        ApiSchemaEntity entity = persistenceMapper.toEntity(original, encryptionUtil);
        ApiSchema restored = persistenceMapper.toDomain(entity, encryptionUtil);

        assertNotNull(restored.config().paginationConfig());
        assertEquals(ApiPaginationType.PAGE_BASED, restored.config().paginationConfig().paginationType());
        assertEquals("pageNum", restored.config().paginationConfig().pageParamName());
        assertEquals("pageSize", restored.config().paginationConfig().sizeParamName());
        assertEquals("$.data.total", restored.config().paginationConfig().totalCountJsonPath());
        assertEquals(50, restored.config().paginationConfig().pageSize());
        assertEquals(100, restored.config().paginationConfig().maxPages());
    }

    private String extractPasswordFromConfigJson(String configJson) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(configJson);
            com.fasterxml.jackson.databind.JsonNode authNode = node.get("authConfig");
            if (authNode != null && authNode.has("password")) {
                return authNode.get("password").asText();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractPasswordFromJdbcConfigJson(String jdbcConfigJson) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(jdbcConfigJson);
            if (node.has("password")) {
                return node.get("password").asText();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}