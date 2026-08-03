package com.linkroa.deepdataagent.agent.infrastructure.persistence;

import com.linkroa.deepdataagent.agent.domain.service.port.JsonSerializationPort;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Jackson JSON 序列化适配器
 * <p>实现领域层 {@link JsonSerializationPort} 端口接口，基于 Jackson ObjectMapper 提供 JSON 序列化/反序列化能力，
 * 使领域层无需直接依赖具体 JSON 库。</p>
 */
@Component
public class JacksonJsonAdapter implements JsonSerializationPort {

    private final ObjectMapper objectMapper;

    /**
     * 构造方法
     *
     * @param objectMapper Jackson ObjectMapper
     */
    public JacksonJsonAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 将对象序列化为 JSON 字符串
     *
     * @param obj 待序列化对象
     * @return JSON 字符串
     */
    @Override
    public String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 序列化失败", e);
        }
    }

    /**
     * 将 JSON 字符串反序列化为对象
     *
     * @param json  JSON 字符串
     * @param clazz 目标类型
     * @param <T>   目标类型泛型
     * @return 反序列化后的对象
     */
    @Override
    public <T> T fromJson(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 反序列化失败", e);
        }
    }
}