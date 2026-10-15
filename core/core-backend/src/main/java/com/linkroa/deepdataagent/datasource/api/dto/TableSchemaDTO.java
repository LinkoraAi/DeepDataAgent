package com.linkroa.deepdataagent.datasource.api.dto;

import java.util.List;

/**
 * 表结构契约（发布语言 DTO，Published Language）。
 *
 * <p>由 datasource BC 在跨 BC 边界出版，承载<b>结构化表结构</b>（表名、表注释、字段清单），
 * 供上游构造查询与理解数据结构使用（未来 SQL 生成的核心输入）；不泄露本 BC 领域模型。</p>
 *
 * @param tableName    表名（API 型数据源为 API 数据表名）
 * @param tableComment 表注释（API 型数据源无注释概念时为 {@code null}）
 * @param columns      字段清单（按数据源侧既有顺序，空表结构时为空列表）
 * @author DeepDataAgent
 */
public record TableSchemaDTO(
        String tableName,
        String tableComment,
        List<Column> columns
) {

    /**
     * 紧凑构造器：字段清单为 {@code null} 时归一为空列表。
     */
    public TableSchemaDTO {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    /**
     * 字段结构契约。
     *
     * <p>注释刻意<b>保留双来源</b>（数据库原始注释 + 用户自定义注释），能力面不做合并：
     * 显示口径（如"自定义优先"）属消费方职责，合并会丢失原始信息。</p>
     *
     * @param name         字段名（API 型数据源为字段映射的原始名）
     * @param dataType     数据类型（API 型数据源为推断类型：string/number/boolean/object/array）
     * @param comment      数据库原始注释（API 型数据源为字段描述）
     * @param customComment 用户编辑的自定义注释（API 型数据源为 {@code null}）
     */
    public record Column(
            String name,
            String dataType,
            String comment,
            String customComment
    ) {
    }
}