package com.linkroa.deepdataagent.agent.infrastructure.validator;

import com.linkroa.deepdataagent.agent.infrastructure.config.DataAnalysisProperties;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQL 安全校验器单元测试
 * <p>测试 SqlValidator 的危险关键字拦截、单词边界匹配、非 SELECT 语句拦截等场景。</p>
 */
class SqlValidatorTest {

    private SqlValidator sqlValidator;

    @BeforeEach
    void setUp() {
        DataAnalysisProperties properties = new DataAnalysisProperties();
        properties.setDangerousKeywords(List.of(
                "DROP", "ALTER", "CREATE", "TRUNCATE",
                "GRANT", "REVOKE", "EXEC", "EXECUTE",
                "LOAD_FILE", "INTO OUTFILE", "INTO DUMPFILE"
        ));
        sqlValidator = new SqlValidator(properties);
    }

    // ==================== 合法 SELECT 查询通过 ====================

    @Test
    void should_passValidation_when_validate_given_validSelectQuery() {
        // given
        String sql = "SELECT id, name FROM users WHERE status = 1";

        // when
        assertDoesNotThrow(() -> sqlValidator.validate(sql));
    }

    @Test
    void should_passValidation_when_validate_given_selectWithCreateTimeColumn() {
        // given - 包含 create_time 列名，之前会被误判为 CREATE
        String sql = "SELECT id, create_time FROM users";

        // when
        assertDoesNotThrow(() -> sqlValidator.validate(sql));
    }

    @Test
    void should_passValidation_when_validate_given_selectWithDropRateColumn() {
        // given - 包含 drop_rate 列名，之前会被误判为 DROP
        String sql = "SELECT id, drop_rate FROM game_stats";

        // when
        assertDoesNotThrow(() -> sqlValidator.validate(sql));
    }

    @Test
    void should_passValidation_when_validate_given_selectWithAlterNameColumn() {
        // given - 包含 alter_name 列名，之前会被误判为 ALTER
        String sql = "SELECT id, alter_name FROM records";

        // when
        assertDoesNotThrow(() -> sqlValidator.validate(sql));
    }

    @Test
    void should_passValidation_when_validate_given_selectWithGrantTypeColumn() {
        // given - 包含 grant_type 列名，之前会被误判为 GRANT
        String sql = "SELECT id, grant_type FROM oauth_tokens";

        // when
        assertDoesNotThrow(() -> sqlValidator.validate(sql));
    }

    @Test
    void should_passValidation_when_validate_given_selectWithTruncateFlagColumn() {
        // given - 包含 truncate_flag 列名，之前会被误判为 TRUNCATE
        String sql = "SELECT id, truncate_flag FROM logs";

        // when
        assertDoesNotThrow(() -> sqlValidator.validate(sql));
    }

    @Test
    void should_passValidation_when_validate_given_selectWithExecuteCountColumn() {
        // given - 包含 execute_count 列名，之前会被误判为 EXECUTE
        String sql = "SELECT id, execute_count FROM jobs";

        // when
        assertDoesNotThrow(() -> sqlValidator.validate(sql));
    }

    @Test
    void should_passValidation_when_validate_given_cteQuery() {
        // given - WITH CTE 查询
        String sql = "WITH temp AS (SELECT id FROM users) SELECT * FROM temp";

        // when
        assertDoesNotThrow(() -> sqlValidator.validate(sql));
    }

    // ==================== 危险 DDL/DCL 语句被拦截 ====================

    @Test
    void should_throwException_when_validate_given_dropTable() {
        // given
        String sql = "SELECT * FROM users; DROP TABLE users";

        // when & then
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> sqlValidator.validate(sql));
        assertTrue(exception.getMessage().contains("DROP"));
    }

    @Test
    void should_throwException_when_validate_given_intoOutfile() {
        // given
        String sql = "SELECT * FROM users INTO OUTFILE '/tmp/data.csv'";

        // when & then
        assertThrows(DeepDataAgentException.class,
                () -> sqlValidator.validate(sql));
    }

    @Test
    void should_throwException_when_validate_intoDumpfile() {
        // given
        String sql = "SELECT * FROM users INTO DUMPFILE '/tmp/data.bin'";

        // when & then
        assertThrows(DeepDataAgentException.class,
                () -> sqlValidator.validate(sql));
    }

    // ==================== 非 SELECT 语句被拦截 ====================

    @Test
    void should_throwException_when_validate_given_insertStatement() {
        // given
        String sql = "INSERT INTO users (id, name) VALUES (1, 'test')";

        // when & then
        assertThrows(DeepDataAgentException.class,
                () -> sqlValidator.validate(sql));
    }

    @Test
    void should_throwException_when_validate_given_updateStatement() {
        // given
        String sql = "UPDATE users SET name = 'test' WHERE id = 1";

        // when & then
        assertThrows(DeepDataAgentException.class,
                () -> sqlValidator.validate(sql));
    }

    @Test
    void should_throwException_when_validate_given_deleteStatement() {
        // given
        String sql = "DELETE FROM users WHERE id = 1";

        // when & then
        assertThrows(DeepDataAgentException.class,
                () -> sqlValidator.validate(sql));
    }

    @Test
    void should_throwException_when_validate_given_createTableStatement() {
        // given
        String sql = "CREATE TABLE users (id INT PRIMARY KEY)";

        // when & then
        assertThrows(DeepDataAgentException.class,
                () -> sqlValidator.validate(sql));
    }

    @Test
    void should_throwException_when_validate_given_alterTableStatement() {
        // given
        String sql = "ALTER TABLE users ADD COLUMN age INT";

        // when & then
        assertThrows(DeepDataAgentException.class,
                () -> sqlValidator.validate(sql));
    }

    @Test
    void should_throwException_when_validate_given_dropTableStatement() {
        // given
        String sql = "DROP TABLE users";

        // when & then
        assertThrows(DeepDataAgentException.class,
                () -> sqlValidator.validate(sql));
    }

    // ==================== 空白/空 SQL 被拦截 ====================

    @Test
    void should_throwException_when_validate_given_nullSql() {
        // when & then
        assertThrows(DeepDataAgentException.class,
                () -> sqlValidator.validate(null));
    }

    @Test
    void should_throwException_when_validate_given_emptySql() {
        // when & then
        assertThrows(DeepDataAgentException.class,
                () -> sqlValidator.validate(""));
    }

    @Test
    void should_throwException_when_validate_given_blankSql() {
        // given
        String sql = "   ";

        // when & then
        assertThrows(DeepDataAgentException.class,
                () -> sqlValidator.validate(sql));
    }

    // ==================== 多语句被拦截 ====================

    @Test
    void should_throwException_when_validate_given_multipleStatements() {
        // given
        String sql = "SELECT * FROM users; SELECT * FROM orders";

        // when & then
        assertThrows(DeepDataAgentException.class,
                () -> sqlValidator.validate(sql));
    }

    @Test
    void should_passValidation_when_validate_given_singleStatementWithTrailingSemiColon() {
        // given - 仅末尾分号应允许
        String sql = "SELECT * FROM users;";

        // when
        assertDoesNotThrow(() -> sqlValidator.validate(sql));
    }

    @Test
    void should_passValidation_when_validate_given_statementWithCommentAfterSemiColon() {
        // given - 分号后仅注释应允许
        String sql = "SELECT * FROM users; -- comment";

        // when
        assertDoesNotThrow(() -> sqlValidator.validate(sql));
    }

    // ==================== 引号感知扫描：字符串内分号不误判 ====================

    @Test
    void should_passValidation_when_validate_given_semicolonInsideStringLiteral() {
        // given - 字符串字面量内的分号不应被当作语句分隔符
        String sql = "SELECT * FROM users WHERE note = 'a;b'";

        // when
        assertDoesNotThrow(() -> sqlValidator.validate(sql));
    }

    @Test
    void should_passValidation_when_validate_given_semicolonOnlyInsideString() {
        // given - 分号仅出现在字符串字面量中
        String sql = "SELECT ';' AS marker FROM users";

        // when
        assertDoesNotThrow(() -> sqlValidator.validate(sql));
    }

    @Test
    void should_passValidation_when_validate_given_escapedQuoteInsideString() {
        // given - 转义单引号（''）内的分号不应被当作语句分隔符
        String sql = "SELECT name FROM users WHERE remark = 'it''s; ok'";

        // when
        assertDoesNotThrow(() -> sqlValidator.validate(sql));
    }

    @Test
    void should_passValidation_when_validate_given_semicolonAfterHashComment() {
        // given - 分号后仅 # 注释应允许
        String sql = "SELECT * FROM users; # comment";

        // when
        assertDoesNotThrow(() -> sqlValidator.validate(sql));
    }

    // ==================== 异常消息内嵌违规 SQL ====================

    @Test
    void should_throwExceptionWithSql_when_validate_given_multipleStatements() {
        // given
        String sql = "SELECT * FROM users; SELECT * FROM orders";

        // when & then
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> sqlValidator.validate(sql));
        assertTrue(ex.getMessage().contains(sql));
    }

    @Test
    void should_throwExceptionWithSql_when_validate_given_dropTable() {
        // given
        String sql = "DROP TABLE users";

        // when & then
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> sqlValidator.validate(sql));
        assertTrue(ex.getMessage().contains(sql));
    }

    @Test
    void should_throwExceptionWithSql_when_validate_given_insertStatement() {
        // given
        String sql = "INSERT INTO users VALUES (1)";

        // when & then
        DeepDataAgentException ex = assertThrows(DeepDataAgentException.class,
                () -> sqlValidator.validate(sql));
        assertTrue(ex.getMessage().contains(sql));
    }

    // ==================== 大小写不敏感 ====================

    @Test
    void should_passValidation_when_validate_given_lowercaseSelectWithCreateTime() {
        // given
        String sql = "select id, create_time from users";

        // when
        assertDoesNotThrow(() -> sqlValidator.validate(sql));
    }

    @Test
    void should_throwException_when_validate_given_mixedCaseDropTable() {
        // given
        String sql = "SELECT * FROM users; DrOp TABLE users";

        // when & then
        assertThrows(DeepDataAgentException.class,
                () -> sqlValidator.validate(sql));
    }
}