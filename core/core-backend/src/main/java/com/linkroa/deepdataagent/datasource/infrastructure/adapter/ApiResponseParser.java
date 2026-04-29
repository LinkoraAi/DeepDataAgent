package com.linkroa.deepdataagent.datasource.infrastructure.adapter;

import com.jayway.jsonpath.JsonPath;
import com.linkroa.deepdataagent.datasource.domain.model.ApiField;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * API响应解析适配器
 * <p>基于JsonPath解析API响应JSON结构，自动提取字段信息和推断字段类型。</p>
 *
 * @author system
 * @since 2026-05-12
 */
@Component
public class ApiResponseParser {

    private static final Logger log = LoggerFactory.getLogger(ApiResponseParser.class);

    /**
     * 解析API响应，提取字段列表
     *
     * @param jsonResponse API响应JSON字符串
     * @param rootPath JsonPath根路径，如"$"或"$.list.*"
     * @return 解析后的字段列表（领域对象）
     */
    public List<ApiField> parseFields(String jsonResponse, String rootPath) {
        if (StringUtils.isBlank(jsonResponse)) {
            throw new IllegalArgumentException("API响应不能为空");
        }
        if (StringUtils.isBlank(rootPath)) {
            throw new IllegalArgumentException("JsonPath根路径不能为空");
        }

        Object rootNode = JsonPath.parse(jsonResponse).read(rootPath);
        return parseFieldsFromNode(rootNode, rootPath);
    }

    /**
     * 解析已解析的API响应数据，提取字段列表
     *
     * @param responseData 已解析的API响应数据（Map或List）
     * @param rootPath JsonPath根路径
     * @return 解析后的字段列表（领域对象）
     */
    public List<ApiField> parseFieldsFromData(Object responseData, String rootPath) {
        if (responseData == null) {
            return List.of();
        }
        if (rootPath == null || rootPath.isBlank()) {
            throw new IllegalArgumentException("JsonPath根路径不能为空");
        }

        return parseFieldsFromNode(responseData, rootPath);
    }

    /**
     * 从解析后的节点提取字段列表
     *
     * @param rootNode JsonPath解析结果节点
     * @param rootPath 根路径表达式
     * @return 字段列表（领域对象）
     */
    private List<ApiField> parseFieldsFromNode(Object rootNode, String rootPath) {
        if (rootNode == null) {
            throw new IllegalArgumentException("根路径解析结果为空");
        }

        if (rootNode instanceof List<?> list) {
            if (list.isEmpty()) {
                return List.of();
            }
            Object firstElement = list.getFirst();
            return extractFieldsFromMap(firstElement, rootPath);
        }

        if (rootNode instanceof Map<?, ?> map) {
            return extractFieldsFromMap(map, rootPath);
        }

        throw new IllegalArgumentException("根路径必须指向Map或Array类型，当前类型为: " + rootNode.getClass().getSimpleName());
    }

    /**
     * 从Map对象提取字段信息
     *
     * @param obj 待解析的对象
     * @param rootPath 根路径
     * @return 字段列表（领域对象）
     */
    @SuppressWarnings("unchecked")
    private List<ApiField> extractFieldsFromMap(Object obj, String rootPath) {
        if (!(obj instanceof Map<?, ?> map)) {
            return List.of();
        }

        List<ApiField> fields = new ArrayList<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String fieldName = String.valueOf(entry.getKey());
            Object fieldValue = entry.getValue();
            String fieldPath = buildJsonPath(rootPath, fieldName);
            String fieldType = inferFieldType(fieldValue);

            fields.add(new ApiField(
                    null,
                    null,
                    fieldName,
                    fieldName,
                    fieldPath,
                    fieldType,
                    null,
                    LocalDateTime.now(),
                    LocalDateTime.now()
            ));
        }
        return fields;
    }

    /**
     * 构建完整JsonPath
     *
     * @param rootPath 根路径，如"$.list.*"或"$"
     * @param fieldName 字段名称
     * @return 完整JsonPath
     */
    private String buildJsonPath(String rootPath, String fieldName) {
        if (rootPath.endsWith(".*")) {
            return rootPath + "." + fieldName;
        }
        return rootPath + "." + fieldName;
    }

    /**
     * 推断字段类型
     *
     * @param value 字段值
     * @return 类型字符串: string/number/boolean/object/array
     */
    private String inferFieldType(Object value) {
        if (value == null) {
            return "string";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Map) {
            return "object";
        }
        if (value instanceof List) {
            return "array";
        }
        return "string";
    }
}