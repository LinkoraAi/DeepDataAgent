package com.linkroa.deepdataagent.datasource.domain.model;

import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceStatus;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.JdbcType;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 数据源连接领域模型
 */
public record DatasourceConnection(
        Long id,
        String name,
        DatasourceType type,
        JdbcType subType,
        DatasourceStatus status,
        JdbcConnectionConfig jdbcConnectionConfig,
        ApiConnectionConfig apiConnectionConfig,
        ApiAuthConfig apiAuthConfig,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy
) {
    private static final Pattern NAME_PATTERN = Pattern.compile("^[\\p{IsHan}a-zA-Z][\\p{IsHan}a-zA-Z0-9_-]{0,99}$");

    /**
     * @param id 数据源ID
     * @param name 数据源名称
     * @param type 数据源类型
     * @param subType JDBC子类型（API类型可为null）
     * @param status 数据源状态
     * @param jdbcConnectionConfig JDBC连接配置（JDBC类型必填）
     * @param apiConnectionConfig API连接配置（API类型必填）
     * @param apiAuthConfig API认证配置（API类型必填）
     * @param description 描述
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     * @param createdBy 创建人
     * @param updatedBy 更新人
     */

    public DatasourceConnection {
        // 名称校验
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("数据源名称不能为空");
        }
        if (name.length() > 100) {
            throw new IllegalArgumentException("数据源名称长度不能超过100个字符");
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("数据源名称只能包含中文、英文字母、数字、下划线和连字符，且不能以数字或特殊字符开头");
        }
        
        // 类型校验
        if (ObjectUtils.isEmpty(type)) {
            throw new IllegalArgumentException("数据源类型不能为空");
        }

        // 子类型校验（JDBC类型必填）
        if (type == DatasourceType.JDBC) {
            if (ObjectUtils.isEmpty(subType)) {
                throw new IllegalArgumentException("JDBC子类型不能为空");
            }

            if (ObjectUtils.isEmpty(jdbcConnectionConfig)) {
                throw new IllegalArgumentException("JDBC连接配置不能为空");
            }
        }

        // 状态校验
        if (ObjectUtils.isEmpty(status)) {
            throw new IllegalArgumentException("数据源状态不能为空");
        }

        // 连接配置校验 (API)
        if (type == DatasourceType.API) {
            if (ObjectUtils.isEmpty(apiConnectionConfig)) {
                throw new IllegalArgumentException("API连接配置不能为空");
            }

            if (ObjectUtils.isEmpty(apiAuthConfig)) {
                throw new IllegalArgumentException("API认证配置不能为空");
            }
        }

        // 描述校验（可选字段）
        if (ObjectUtils.isNotEmpty(description) && description.length() > 500) {
            throw new IllegalArgumentException("描述不能超过500个字符");
        }
    }
    
    /**
     * 创建新的数据源连接实体
     *
     * @param name 数据源名称
     * @param type 数据源类型
     * @param subType JDBC子类型（API类型可为null）
     * @param description 描述
     * @param jdbcConfig JDBC连接配置（JDBC类型必填）
     * @param apiConfig API连接配置（API类型必填）
     * @param authConfig API认证配置（API类型必填）
     * @return 数据源连接实体
     */
    public static DatasourceConnection create(
            String name,
            DatasourceType type,
            JdbcType subType,
            String description,
            JdbcConnectionConfig jdbcConfig,
            ApiConnectionConfig apiConfig,
            ApiAuthConfig authConfig
    ) {
        return new DatasourceConnection(
                null,
                name,
                type,
                subType,
                DatasourceStatus.ENABLED,
                jdbcConfig,
                apiConfig,
                authConfig,
                description,
                LocalDateTime.now(),
                LocalDateTime.now(),
                null,
                null
        );
    }

    /**
     * 从数据库恢复数据源连接实体（用于查询场景）
     *
     * @param id 数据源ID
     * @param name 数据源名称
     * @param type 数据源类型
     * @param subType JDBC子类型
     * @param status 状态
     * @param jdbcConfig JDBC连接配置
     * @param apiConfig API连接配置
     * @param authConfig API认证配置
     * @param description 描述
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     * @param createdBy 创建人
     * @param updatedBy 更新人
     * @return 数据源连接实体
     */
    public static DatasourceConnection restore(
            Long id,
            String name,
            DatasourceType type,
            JdbcType subType,
            DatasourceStatus status,
            JdbcConnectionConfig jdbcConfig,
            ApiConnectionConfig apiConfig,
            ApiAuthConfig authConfig,
            String description,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            String createdBy,
            String updatedBy
    ) {
        return new DatasourceConnection(
                id,
                name,
                type,
                subType,
                status,
                jdbcConfig,
                apiConfig,
                authConfig,
                description,
                createdAt,
                updatedAt,
                createdBy,
                updatedBy
        );
    }

}
