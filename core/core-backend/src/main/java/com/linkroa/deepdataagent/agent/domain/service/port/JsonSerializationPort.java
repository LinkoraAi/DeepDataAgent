package com.linkroa.deepdataagent.agent.domain.service.port;

/**
 * JSON 序列化端口
 * <p>领域层端口接口，用于抽象 JSON 序列化/反序列化能力，由基础设施层实现，
 * 避免领域层直接依赖具体 JSON 库。</p>
 */
public interface JsonSerializationPort {

    /**
     * 将对象序列化为 JSON 字符串
     *
     * @param obj 待序列化对象
     * @return JSON 字符串
     */
    String toJson(Object obj);

    /**
     * 将 JSON 字符串反序列化为对象
     *
     * @param json  JSON 字符串
     * @param clazz 目标类型
     * @param <T>   目标类型泛型
     * @return 反序列化后的对象
     */
    <T> T fromJson(String json, Class<T> clazz);
}