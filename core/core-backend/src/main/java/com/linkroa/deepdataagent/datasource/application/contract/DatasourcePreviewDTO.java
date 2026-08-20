package com.linkroa.deepdataagent.datasource.application.contract;

import java.util.List;
import java.util.Map;

/**
 * 数据源预览契约（发布语言 DTO，Published Language）。
 * <p>由 datasource BC 在应用边界出版，供 runtime BC 的数据源查询工具读取表数据预览；
 * 仅承载已格式化的预览结果（列名 + 行数据），不泄露本 BC 领域模型。</p>
 *
 * @param dataSourceName 数据源名称
 * @param tableName      表名（API 数据源为 API schema 名）
 * @param columns        列名顺序集合（派生自首行键序，空结果时为空）
 * @param rows           预览行数据（每行一个列名→值映射）
 */
public record DatasourcePreviewDTO(
        String dataSourceName,
        String tableName,
        List<String> columns,
        List<Map<String, Object>> rows
) {
    public DatasourcePreviewDTO {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}