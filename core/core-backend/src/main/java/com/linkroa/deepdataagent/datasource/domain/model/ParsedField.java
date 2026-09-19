package com.linkroa.deepdataagent.datasource.domain.model;

import java.util.List;

/**
 * API 响应解析字段树领域模型（ParsedField，不可变递归结构）。
 * <p>由 {@code ApiResponseParser} 对响应 JSON 按根路径递归展开：每个节点记录
 * 原始字段名、JsonPath 定位与推断类型（string/number/boolean/object/array），
 * {@code children} 承载 object / array 元素的嵌套子字段；datasource 收敛为
 * 内部工具能力后，该模型即解析能力的唯一对外形状（不再暴露协议层 DTO）。</p>
 *
 * @param originalName 原始字段名
 * @param jsonPath     字段 JsonPath 定位（如 {@code $.data.list.*.id}）
 * @param fieldType    推断类型（string/number/boolean/object/array）
 * @param children     嵌套子字段列表（叶子字段为空列表）
 */
public record ParsedField(
        String originalName,
        String jsonPath,
        String fieldType,
        List<ParsedField> children
) {

    /**
     * 紧凑构造器：子节点列表 null 归一为空列表，保证树遍历无需判空。
     */
    public ParsedField {
        if (children == null) {
            children = List.of();
        }
    }
}
