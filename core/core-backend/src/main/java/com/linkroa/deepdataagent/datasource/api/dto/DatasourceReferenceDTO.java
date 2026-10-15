package com.linkroa.deepdataagent.datasource.api.dto;

/**
 * 数据源引用契约（发布语言 DTO，Published Language）。
 *
 * <p>由 datasource BC 在跨 BC 边界出版，供上游（未来的 agent / runtime 绑定层）生成
 * 可用数据源清单；仅承载标识与已格式化的类型字符串，<b>不泄露本 BC 领域模型与连接凭证</b>。</p>
 *
 * @param id      数据源标识
 * @param name    数据源名称
 * @param type    主类型名称（{@code JDBC} / {@code API}，以枚举名传输，不跨 BC 传 Java 枚举类型）
 * @param subType 子类型名称（{@code MYSQL} / {@code CLICKHOUSE} / {@code POSTGRESQL}；API 型为 {@code null}）
 * @author DeepDataAgent
 */
public record DatasourceReferenceDTO(
        Long id,
        String name,
        String type,
        String subType
) {
}