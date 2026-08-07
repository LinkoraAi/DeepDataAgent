package com.linkroa.deepdataagent.agent.infrastructure.validator;

import com.linkroa.deepdataagent.agent.domain.service.port.SqlValidationPort;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.agent.infrastructure.config.DataAnalysisProperties;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQL 安全校验器
 * <p>校验 SQL 语句是否安全，仅允许 SELECT 查询（含 WITH CTE），
 * 防止 SQL 注入和危险操作。危险关键字列表可通过配置调整。</p>
 * <p>实现领域层 {@link SqlValidationPort} 端口接口。</p>
 */
@Component
public class SqlValidator implements SqlValidationPort {

    private final Set<String> dangerousKeywords;

    /**
     * 单词边界匹配正则模板
     */
    private static final String WORD_BOUNDARY_PATTERN = "\\b%s\\b";

    /**
     * 构造方法
     *
     * @param properties 数据分析配置
     */
    public SqlValidator(DataAnalysisProperties properties) {
        this.dangerousKeywords = properties.getDangerousKeywords() != null
                ? Set.copyOf(properties.getDangerousKeywords())
                : Set.of(
                    "DROP", "ALTER", "CREATE", "TRUNCATE",
                    "GRANT", "REVOKE", "EXEC", "EXECUTE",
                    "DELETE", "UPDATE", "INSERT",
                    "LOAD_FILE", "INTO OUTFILE", "INTO DUMPFILE",
                    "REPLACE INTO"
                );
    }

    /**
     * 校验 SQL 安全性
     *
     * @param sql 待校验的 SQL 语句
     * @throws DeepDataAgentException 如果 SQL 不安全
     */
    @Override
    public void validate(String sql) {
        if (StringUtils.isBlank(sql)) {
            throw new DeepDataAgentException("SQL 语句不能为空");
        }

        // 预处理：去除 markdown 代码块标记、SQL 注释和前导空白
        String cleanedSql = cleanSql(sql);
        String upperSql = cleanedSql.toUpperCase().strip();

        // 显式拦截非 SELECT 语句（INSERT/UPDATE/DELETE 等）
        if (!upperSql.startsWith("SELECT") && !upperSql.startsWith("WITH")) {
            throw new DeepDataAgentException("仅允许 SELECT 查询，SQL: " + sql);
        }

        // 使用单词边界匹配危险关键字，避免列名误判（如 create_time 被误判为 CREATE）
        // 仅在可执行代码区匹配：先屏蔽字符串/标识符/注释字面量，
        // 避免其内容误伤（如 SELECT 'drop' 不应命中 DROP）
        String maskedUpperSql = maskLiteralsAndComments(upperSql);
        for (String keyword : dangerousKeywords) {
            if (containsKeyword(maskedUpperSql, keyword)) {
                throw new DeepDataAgentException("SQL 包含禁止的操作: " + keyword + "，SQL: " + sql);
            }
        }

        // 多语句校验：引号感知扫描，仅将真实语句分隔符视为多语句，
        // 忽略字符串/标识符/注释内的分号，避免合法 SQL（如 WHERE note='a;b'）被误判
        if (hasRealMultiStatement(cleanedSql)) {
            throw new DeepDataAgentException("禁止多语句执行，SQL: " + sql);
        }
    }

    /**
     * 判断 SQL 是否包含真实的多语句（存在真实语句分隔符且其后还有非注释内容）。
     * <p>通过引号感知扫描区分分号是"语句分隔符"还是"字符串/标识符/注释内的字面量"，
     * 避免合法单条 SELECT 因字符串内分号被误判为多语句。</p>
     *
     * @param simplifiedSql 已清理的 SQL 语句
     * @return 是否包含真实多语句
     */
    private boolean hasRealMultiStatement(String simplifiedSql) {
        int semi = findFirstRealSemicolon(simplifiedSql);
        if (semi < 0) {
            return false;
        }
        String after = simplifiedSql.substring(semi + 1).strip();
        // 分号后为空或仅注释（-- / # / /* */）则视为单条语句，否则视为多语句
        return !after.isEmpty()
                && !after.startsWith("--")
                && !after.startsWith("#")
                && !after.startsWith("/*");
    }

    /**
     * 引号感知扫描，返回第一个真实语句分隔符（分号）的位置。
     * <p>逐字符维护状态机，跳过单引号字符串、双引号/反引号标识符、
     * 行注释（-- / #）与块注释内的分号，仅在 NORMAL 状态下遇到 ';' 返回其下标。</p>
     *
     * @param sql 待扫描的 SQL 语句
     * @return 第一个真实分号的位置，未找到返回 -1
     */
    private int findFirstRealSemicolon(String sql) {
        int len = sql.length();
        // 状态：0=普通，1=单引号字符串，2=双引号标识符，3=反引号标识符，4=行注释，5=块注释
        int state = 0;
        for (int i = 0; i < len; i++) {
            char c = sql.charAt(i);
            char next = i + 1 < len ? sql.charAt(i + 1) : (char) 0;
            switch (state) {
                case 0: // 普通状态
                    if (c == '\'') {
                        state = 1;
                    } else if (c == '"') {
                        state = 2;
                    } else if (c == '`') {
                        state = 3;
                    } else if (c == '-' && next == '-') {
                        state = 4;
                        i++;
                    } else if (c == '#') {
                        state = 4;
                    } else if (c == '/' && next == '*') {
                        state = 5;
                        i++;
                    } else if (c == ';') {
                        return i;
                    }
                    break;
                case 1: // 单引号字符串，'' 转义不退出
                    if (c == '\'') {
                        if (next == '\'') {
                            i++;
                        } else {
                            state = 0;
                        }
                    }
                    break;
                case 2: // 双引号标识符，"" 转义不退出
                    if (c == '"') {
                        if (next == '"') {
                            i++;
                        } else {
                            state = 0;
                        }
                    }
                    break;
                case 3: // 反引号标识符，`` 转义不退出
                    if (c == '`') {
                        if (next == '`') {
                            i++;
                        } else {
                            state = 0;
                        }
                    }
                    break;
                case 4: // 行注释，遇换行结束
                    if (c == '\n') {
                        state = 0;
                    }
                    break;
                case 5: // 块注释，遇 */ 结束
                    if (c == '*' && next == '/') {
                        state = 0;
                        i++;
                    }
                    break;
                default:
                    break;
            }
        }
        return -1;
    }

    /**
     * 屏蔽字符串/标识符/注释字面量内字符，返回"可执行代码区"文本。
     * <p>逐字符维护与 {@link #findFirstRealSemicolon} 相同的状态机，
     * 将单引号字符串、双引号/反引号标识符、行注释（-- / #）与块注释内的
     * 字符替换为空格占位，使危险关键字匹配不会命中字面量内容
     * （如 {@code SELECT 'drop'} 不应触发 DROP，而 {@code WITH x AS (UPDATE ...)}
     * 的 UPDATE 仍在可执行区，可被拦截）。</p>
     *
     * @param sql 待扫描的 SQL（建议传入已 strip 的文本）
     * @return 仅含可执行代码字符的文本，字面量位置以空格占位
     */
    private String maskLiteralsAndComments(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        int len = sql.length();
        // 状态：0=普通，1=单引号字符串，2=双引号标识符，3=反引号标识符，4=行注释，5=块注释
        int state = 0;
        for (int i = 0; i < len; i++) {
            char c = sql.charAt(i);
            char next = i + 1 < len ? sql.charAt(i + 1) : (char) 0;
            boolean masked = false;
            switch (state) {
                case 0: // 普通状态
                    if (c == '\'') { state = 1; masked = true; }
                    else if (c == '"') { state = 2; masked = true; }
                    else if (c == '`') { state = 3; masked = true; }
                    else if (c == '#') { state = 4; masked = true; }
                    else if (c == '-' && next == '-') { state = 4; masked = true; i++; }
                    else if (c == '/' && next == '*') { state = 5; masked = true; i++; }
                    break;
                case 1: // 单引号字符串，'' 转义不退出
                    masked = true;
                    if (c == '\'') {
                        if (next == '\'') { i++; }
                        else { state = 0; }
                    }
                    break;
                case 2: // 双引号标识符，"" 转义不退出
                    masked = true;
                    if (c == '"') {
                        if (next == '"') { i++; }
                        else { state = 0; }
                    }
                    break;
                case 3: // 反引号标识符，`` 转义不退出
                    masked = true;
                    if (c == '`') {
                        if (next == '`') { i++; }
                        else { state = 0; }
                    }
                    break;
                case 4: // 行注释，遇换行结束
                    masked = true;
                    if (c == '\n') { state = 0; }
                    break;
                case 5: // 块注释，遇 */ 结束
                    masked = true;
                    if (c == '*' && next == '/') { state = 0; i++; }
                    break;
                default:
                    break;
            }
            sb.append(masked ? ' ' : c);
        }
        return sb.toString();
    }

    /**
     * 清理 SQL 语句：去除 markdown 代码块标记、SQL 注释和前导空白。
     * <p>LLM 可能返回被 markdown 包裹或带注释的 SQL，需在验证前清理。</p>
     *
     * @param sql 原始 SQL 语句
     * @return 清理后的 SQL 语句
     */
    private String cleanSql(String sql) {
        String cleaned = sql.strip();
        // 去除 markdown 代码块标记（```sql ... ``` 或 ``` ... ```）
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\s*\\n?", "")
                             .replaceAll("\\n?```\\s*$", "")
                             .strip();
        }
        // 去除单行 SQL 注释（-- ...）
        cleaned = cleaned.replaceAll("(?m)^\\s*--.*$", "").strip();
        // 去除多行注释（/* ... */）
        cleaned = cleaned.replaceAll("(?s)/\\*.*?\\*/", "").strip();
        return cleaned;
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