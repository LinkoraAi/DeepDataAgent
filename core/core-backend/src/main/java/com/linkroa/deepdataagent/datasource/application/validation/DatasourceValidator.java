package com.linkroa.deepdataagent.datasource.application.validation;

import com.linkroa.deepdataagent.datasource.domain.model.enums.*;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.apache.commons.lang3.StringUtils;

import java.util.List;


/**
 * 数据源请求参数校验器
 * <p>根据数据源类型（JDBC/API）调用不同的参数校验方法，
 * 校验失败抛出 DeepDataAgentException 异常。</p>
 * <p>同时负责 Application 层的类型解析和参数校验，
 * 避免 Application 层反向依赖 Controller 层。</p>
 */
public final class DatasourceValidator {

    private DatasourceValidator() {
    }

    // ==================== 类型解析方法 ====================

    /**
     * 解析 DatasourceType
     *
     * @param value 数据源类型字符串
     * @return DatasourceType 枚举值
     */
    public static DatasourceType parseDatasourceType(String value) {
        return DatasourceType.valueOf(value);
    }

    /**
     * 解析 DatasourceType，允许为 null
     *
     * @param value 数据源类型字符串
     * @return DatasourceType 枚举值或 null
     */
    public static DatasourceType parseDatasourceTypeOrNull(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return DatasourceType.valueOf(value);
    }

    /**
     * 解析 JdbcType，允许为 null
     *
     * @param value JDBC 类型字符串
     * @return JdbcType 枚举值或 null
     */
    public static JdbcType parseJdbcType(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return JdbcType.valueOf(value);
    }

    /**
     * 解析 HttpMethod，允许为 null
     *
     * @param value HTTP 方法字符串
     * @return HttpMethod 枚举值或 null
     */
    public static HttpMethod parseHttpMethod(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return HttpMethod.valueOf(value);
    }

    /**
     * 解析 DatasourceStatus，允许为 null
     *
     * @param value 数据源状态字符串
     * @return DatasourceStatus 枚举值或 null
     */
    public static DatasourceStatus parseDatasourceStatusOrNull(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return DatasourceStatus.valueOf(value);
    }

    /**
     * PostgreSQL 内置 schema，禁止作为同步目标
     */
    private static final List<String> POSTGRESQL_BUILTIN_SCHEMAS =
            List.of("pg_catalog", "information_schema", "pg_toast");

    /**
     * 校验 PostgreSQL schema 配置
     * <p>仅当子类型为 PostgreSQL 时校验，要求 schema 为单个值且不是系统内置 schema。
     * schema 为空时使用默认值 public，不做校验。</p>
     *
     * @param subType JDBC 子类型
     * @param schema  待校验的 schema，可为空
     */
    public static void validatePostgresqlSchema(JdbcType subType, String schema) {
        if (subType != JdbcType.POSTGRESQL || StringUtils.isBlank(schema)) {
            return;
        }
        String trimmed = schema.trim();
        if (trimmed.contains(",") || trimmed.contains(";")) {
            throw new DeepDataAgentException("PostgreSQL 仅支持单个 schema，请勿填写多个值");
        }
        if (POSTGRESQL_BUILTIN_SCHEMAS.contains(trimmed.toLowerCase())) {
            throw new DeepDataAgentException("PostgreSQL 内置 schema 不允许作为同步目标: " + schema);
        }
    }

}
