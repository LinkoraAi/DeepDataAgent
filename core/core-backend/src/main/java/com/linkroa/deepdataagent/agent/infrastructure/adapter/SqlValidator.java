package com.linkroa.deepdataagent.agent.infrastructure.adapter;

import com.linkroa.deepdataagent.agent.exception.DataAnalysisException;
import com.linkroa.deepdataagent.agent.infrastructure.config.DataAnalysisProperties;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQL 安全校验器
 * <p>校验 SQL 语句是否安全，仅允许 SELECT 查询（含 WITH CTE），
 * 防止 SQL 注入和危险操作。危险关键字列表可通过配置调整。</p>
 */
@Component
public class SqlValidator {

    private final Set<String> dangerousKeywords;

    /**
     * 单词边界匹配正则模板
     */
    private static final String WORD_BOUNDARY_PATTERN = "\\b%s\\b";

    public SqlValidator(DataAnalysisProperties properties) {
        this.dangerousKeywords = properties.getDangerousKeywords() != null
                ? Set.copyOf(properties.getDangerousKeywords())
                : Set.of(
                    "DROP", "ALTER", "CREATE", "TRUNCATE",
                    "GRANT", "REVOKE", "EXEC", "EXECUTE",
                    "LOAD_FILE", "INTO OUTFILE", "INTO DUMPFILE"
                );
    }

    /**
     * 校验 SQL 安全性
     *
     * @param sql 待校验的 SQL 语句
     * @throws DataAnalysisException 如果 SQL 不安全
     */
    public void validate(String sql) {
        if (StringUtils.isBlank(sql)) {
            throw new DataAnalysisException("SQL 语句不能为空");
        }

        String upperSql = sql.toUpperCase().strip();

        // 显式拦截非 SELECT 语句（INSERT/UPDATE/DELETE 等）
        if (!upperSql.startsWith("SELECT") && !upperSql.startsWith("WITH")) {
            throw new DataAnalysisException("仅允许 SELECT 查询");
        }

        // 使用单词边界匹配危险关键字，避免列名误判（如 create_time 被误判为 CREATE）
        for (String keyword : dangerousKeywords) {
            if (containsKeyword(upperSql, keyword)) {
                throw new DataAnalysisException("SQL 包含禁止的操作: " + keyword);
            }
        }

        // 多语句校验
        if (upperSql.contains(";") && upperSql.indexOf(';') < upperSql.length() - 1) {
            String afterSemi = upperSql.substring(upperSql.indexOf(';') + 1).strip();
            if (!afterSemi.isEmpty() && !afterSemi.startsWith("--")) {
                throw new DataAnalysisException("禁止多语句执行");
            }
        }
    }

    /**
     * 使用单词边界正则匹配关键字
     * <p>避免子串误判，例如 create_time 不应匹配 CREATE。</p>
     *
     * @param upperSql 大写 SQL 语句
     * @param keyword  待匹配的关键字
     * @return 是否包含该关键字
     */
    private boolean containsKeyword(String upperSql, String keyword) {
        Pattern pattern = Pattern.compile(String.format(WORD_BOUNDARY_PATTERN, keyword));
        return pattern.matcher(upperSql).find();
    }
}
