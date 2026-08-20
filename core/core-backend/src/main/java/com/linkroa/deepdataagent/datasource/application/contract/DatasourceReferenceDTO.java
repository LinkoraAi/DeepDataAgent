package com.linkroa.deepdataagent.datasource.application.contract;

/**
 * 数据源引用契约（发布语言 DTO，Published Language）。
 * <p>由 datasource BC 在应用边界出版，供 runtime BC 生成数据源可用清单；仅承载已格式化的
 * 数据源 ID / 名称 / 类型字符串，不泄露本 BC 领域枚举。</p>
 *
 * @param id   数据源 ID
 * @param name 数据源名称
 * @param type 已格式化的类型标识（如 {@code JDBC:POSTGRESQL} / {@code API}）
 */
public record DatasourceReferenceDTO(Long id, String name, String type) {
}