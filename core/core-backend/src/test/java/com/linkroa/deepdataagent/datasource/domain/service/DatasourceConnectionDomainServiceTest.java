package com.linkroa.deepdataagent.datasource.domain.service;

import com.linkroa.deepdataagent.datasource.domain.model.*;
import com.linkroa.deepdataagent.datasource.domain.model.enums.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class DatasourceConnectionDomainServiceTest {

    private final DatasourceConnectionDomainService domainService = new DatasourceConnectionDomainService();


    @Test
    void should_validateSuccessfully_when_commentIsValid_given_validComment() {
        assertDoesNotThrow(() -> domainService.validateComment(null));
        assertDoesNotThrow(() -> domainService.validateComment("正常注释"));
    }

    @Test
    void should_throwException_when_commentExceedsMaxLength_given_longComment() {
        String longComment = "a".repeat(151);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> domainService.validateComment(longComment));
        assertTrue(ex.getMessage().contains("150"));
    }

    @Test
    void should_validateCanEnable_when_statusIsDisabled_given_disabledConnection() {
        DatasourceConnection connection = createConnection(DatasourceStatus.DISABLED);
        assertDoesNotThrow(() -> domainService.validateCanEnable(connection));
    }

    @Test
    void should_throwException_when_enableAlreadyEnabled_given_enabledConnection() {
        DatasourceConnection connection = createConnection(DatasourceStatus.ENABLED);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> domainService.validateCanEnable(connection));
        assertTrue(ex.getMessage().contains("已禁用"));
    }

    @Test
    void should_validateCanDisable_when_statusIsEnabled_given_enabledConnection() {
        DatasourceConnection connection = createConnection(DatasourceStatus.ENABLED);
        assertDoesNotThrow(() -> domainService.validateCanDisable(connection));
    }

    @Test
    void should_throwException_when_disableAlreadyDisabled_given_disabledConnection() {
        DatasourceConnection connection = createConnection(DatasourceStatus.DISABLED);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> domainService.validateCanDisable(connection));
        assertTrue(ex.getMessage().contains("已启用"));
    }

    @Test
    void should_validateCanDelete_when_statusIsDisabled_given_disabledConnection() {
        DatasourceConnection connection = createConnection(DatasourceStatus.DISABLED);
        assertDoesNotThrow(() -> domainService.validateCanDelete(connection));
    }

    @Test
    void should_throwException_when_deleteEnabledConnection_given_enabledConnection() {
        DatasourceConnection connection = createConnection(DatasourceStatus.ENABLED);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> domainService.validateCanDelete(connection));
        assertTrue(ex.getMessage().contains("已禁用"));
    }

    @Test
    void should_validateCanSync_when_statusIsEnabled_given_enabledConnection() {
        DatasourceConnection connection = createConnection(DatasourceStatus.ENABLED);
        assertDoesNotThrow(() -> domainService.validateCanSync(connection));
    }

    @Test
    void should_throwException_when_syncDisabledConnection_given_disabledConnection() {
        DatasourceConnection connection = createConnection(DatasourceStatus.DISABLED);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> domainService.validateCanSync(connection));
        assertTrue(ex.getMessage().contains("已启用"));
    }

    @Test
    void should_throwException_when_jdbcSubTypeIsNull_given_nullSubType() {
        assertThrows(IllegalArgumentException.class, () -> new DatasourceConnection(
                1L, "test", DatasourceType.JDBC, null, DatasourceStatus.ENABLED,
                new JdbcConnectionConfig("host", 3306, "db", "user", "pass"),
                null, null, null, null, null, null, null
        ));
    }

    @Test
    void should_throwException_when_jdbcConfigIsNull_given_nullJdbcConfig() {
        assertThrows(IllegalArgumentException.class, () -> new DatasourceConnection(
                1L, "test", DatasourceType.JDBC, JdbcType.MYSQL, DatasourceStatus.ENABLED,
                null, null, null, null, null, null, null, null
        ));
    }

    @Test
    void should_throwException_when_jdbcHostIsNull_given_nullHost() {
        assertThrows(IllegalArgumentException.class, () -> createJdbcConnection(null, 3306, "testdb", "root", "pass"));
    }

    @Test
    void should_throwException_when_jdbcHostIsBlank_given_blankHost() {
        assertThrows(IllegalArgumentException.class, () -> createJdbcConnection("  ", 3306, "testdb", "root", "pass"));
    }

    @Test
    void should_throwException_when_jdbcDatabaseIsNull_given_nullDatabase() {
        assertThrows(IllegalArgumentException.class, () -> createJdbcConnection("localhost", 3306, null, "root", "pass"));
    }

    @Test
    void should_throwException_when_jdbcUsernameIsNull_given_nullUsername() {
        assertThrows(IllegalArgumentException.class, () -> createJdbcConnection("localhost", 3306, "testdb", null, "pass"));
    }

    @Test
    void should_throwException_when_jdbcPasswordIsNull_given_nullPassword() {
        assertThrows(IllegalArgumentException.class, () -> createJdbcConnection("localhost", 3306, "testdb", "root", null));
    }

    @Test
    void should_throwException_when_apiConfigIsNull_given_nullApiConfig() {
        assertThrows(IllegalArgumentException.class, () -> new DatasourceConnection(
                1L, "test", DatasourceType.API, null, DatasourceStatus.ENABLED,
                null, null, null, null, null, null, null, null
        ));
    }

    @Test
    void should_throwException_when_apiUrlIsNull_given_nullUrl() {
        assertThrows(IllegalArgumentException.class, () -> new DatasourceConnection(
                1L, "test", DatasourceType.API, null, DatasourceStatus.ENABLED,
                null,
                new ApiConnectionConfig(null, HttpMethod.GET, null, null, null, null, 10, null),
                new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null),
                null, null, null, null, null
        ));
    }

    @Test
    void should_throwException_when_apiMethodIsNull_given_nullMethod() {
        assertThrows(IllegalArgumentException.class, () -> new DatasourceConnection(
                1L, "test", DatasourceType.API, null, DatasourceStatus.ENABLED,
                null,
                new ApiConnectionConfig("http://example.com", null, null, null, null, null, 10, null),
                new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null),
                null, null, null, null, null
        ));
    }


    private DatasourceConnection createConnection(DatasourceStatus status) {
        return new DatasourceConnection(1L, "test", DatasourceType.JDBC, JdbcType.MYSQL, status,
                new JdbcConnectionConfig("localhost", 3306, "testdb", "root", "pass"),
                null, null, null, null, null, null, null);
    }

    private DatasourceConnection createJdbcConnection(String host, int port, String database, String username, String password) {
        return new DatasourceConnection(1L, "test", DatasourceType.JDBC, JdbcType.MYSQL, DatasourceStatus.ENABLED,
                new JdbcConnectionConfig(host, port, database, username, password),
                null, null, null, null, null, null, null);
    }

    private DatasourceConnection createApiConnection() {
        return new DatasourceConnection(1L, "test", DatasourceType.API, null, DatasourceStatus.ENABLED,
                null,
                new ApiConnectionConfig("http://example.com", HttpMethod.GET, null, null, null, null, 10, "$.data"),
                new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null),
                null, null, null, null, null);
    }
}
