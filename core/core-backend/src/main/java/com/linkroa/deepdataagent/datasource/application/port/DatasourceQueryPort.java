package com.linkroa.deepdataagent.datasource.application.port;

import com.linkroa.deepdataagent.datasource.application.contract.DatasourcePreviewDTO;
import com.linkroa.deepdataagent.datasource.application.contract.DatasourceReferenceDTO;

import java.util.List;

/**
 * 数据源查询出站端口（生产者侧对外 SPI）。
 * <p>向 runtime BC 暴露「数据源引用 → 表清单 / 表数据预览」的只读查询能力，返回发布语言
 * DTO，凭证与连接细节不跨 BC 泄露。数据源查询工具随 Agent 版本数据源引用自动装配，
 * 由此端口驱动。</p>
 */
public interface DatasourceQueryPort {

    /**
     * 解析数据源引用为可用数据源清单（仅返回已存在的引用，缺失引用跳过）。
     */
    List<DatasourceReferenceDTO> listDatasources(List<Long> dataSourceIds);

    /**
     * 列出指定数据源下的表名（API 数据源为 API schema 名）。
     */
    List<String> listTableNames(Long dataSourceId);

    /**
     * 预览指定数据源指定表的数据（只读）。
     */
    DatasourcePreviewDTO previewTable(Long dataSourceId, String tableName, int limit);
}