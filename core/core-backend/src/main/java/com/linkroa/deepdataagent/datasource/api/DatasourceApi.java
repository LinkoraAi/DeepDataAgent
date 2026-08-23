package com.linkroa.deepdataagent.datasource.api;

import com.linkroa.deepdataagent.datasource.application.contract.DatasourcePreviewDTO;
import com.linkroa.deepdataagent.datasource.application.contract.DatasourceReferenceDTO;

import java.util.List;

/**
 * 数据源查询服务契约（跨 BC 服务边界，未来 Feign 落点）。
 * <p>与领域出站端口分离：本接口是 datasource BC 对外暴露的跨 BC 只读查询能力面，
 * 向 runtime BC 暴露「数据源引用 → 表清单 / 表数据预览」。当前由
 * {@code DefaultDatasourceApi} 进程内实现，未来接入 Feign 时仅需在本接口追加
 * {@code @FeignClient} 注解 + 提供远程实现，消费方无需改动。凭证与连接细节不跨 BC 泄露。</p>
 */
public interface DatasourceApi {

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