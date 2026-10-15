package com.linkroa.deepdataagent.datasource.api.dto;

import java.util.List;
import java.util.Map;

/**
 * 表数据预览契约（发布语言 DTO，Published Language）。
 *
 * <p>由 datasource BC 在跨 BC 边界出版，仅承载已格式化的预览结果（列名顺序 + 行数据），
 * 不泄露本 BC 领域模型与连接凭证。<b>载荷受限</b>：行数上限恒定 100（由能力面钳制），
 * 不提供无界取回。</p>
 *
 * @param tableName 表名（API 型数据源为 API 数据表名）
 * @param columns   列名顺序集合（派生自首行键序；无数据时为空列表）
 * @param rows      预览行数据（每行一个「列名 → 值」映射）
 * @author DeepDataAgent
 */
public record DatasourcePreviewDTO(
        String tableName,
        List<String> columns,
        List<Map<String, Object>> rows
) {

    /**
     * 紧凑构造器：列名与行数据为 {@code null} 时归一为空列表。
     */
    public DatasourcePreviewDTO {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}