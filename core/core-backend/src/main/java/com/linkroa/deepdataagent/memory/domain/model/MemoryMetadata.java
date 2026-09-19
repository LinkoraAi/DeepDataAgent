package com.linkroa.deepdataagent.memory.domain.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 记忆自定义元数据值对象（对应 memories.metadata JSONB 列）。
 * <p>不变量：键值对最多 16 对；key 长度 1-64 字符；value 长度 ≤512 字符。
 * 内部持有不可变 {@link Map}，并提供与 JSONB 列互转的语义化方法。</p>
 *
 * @param entries 元数据键值对（不可变副本，可为空）
 */
public record MemoryMetadata(Map<String, String> entries) {

    private static final int MAX_PAIRS = 16;
    private static final int MAX_KEY_LENGTH = 64;
    private static final int MAX_VALUE_LENGTH = 512;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 紧凑构造器：不变量校验并做不可变防御拷贝
     */
    public MemoryMetadata {
        if (entries == null) {
            entries = Map.of();
        } else {
            if (entries.size() > MAX_PAIRS) {
                throw new IllegalArgumentException("记忆元数据键值对不能超过16对");
            }
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (StringUtils.isBlank(key) || key.length() > MAX_KEY_LENGTH) {
                    throw new IllegalArgumentException("记忆元数据键长度必须在1-64个字符之间");
                }
                if (value == null || value.length() > MAX_VALUE_LENGTH) {
                    throw new IllegalArgumentException("记忆元数据值不能为null且长度不能超过512个字符");
                }
            }
            entries = Map.copyOf(entries);
        }
    }

    /**
     * 空元数据。
     */
    public static MemoryMetadata empty() {
        return new MemoryMetadata(Map.of());
    }

    /**
     * 从键值对 Map 构建（null 视为空元数据）。
     */
    public static MemoryMetadata of(Map<String, String> values) {
        return new MemoryMetadata(values);
    }

    /**
     * 序列化为 JSON 字符串（写入 JSONB 列）。
     */
    public String toJson() {
        try {
            return OBJECT_MAPPER.writeValueAsString(entries);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("记忆元数据序列化失败", e);
        }
    }

    /**
     * 从 JSON 字符串反序列化（读取 JSONB 列；空白输入返回空元数据）。
     */
    public static MemoryMetadata fromJson(String json) {
        if (StringUtils.isBlank(json)) {
            return empty();
        }
        try {
            Map<String, String> values =
                    OBJECT_MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, String>>() { });
            return new MemoryMetadata(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("记忆元数据反序列化失败", e);
        }
    }
}
