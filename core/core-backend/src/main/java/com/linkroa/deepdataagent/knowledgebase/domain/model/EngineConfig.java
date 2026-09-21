package com.linkroa.deepdataagent.knowledgebase.domain.model;

import java.util.Map;

/**
 * 引擎配置参数值对象（通用键值对，对应 JSONB 中的 engineConfig.params）。
 *
 * @param params 配置参数映射
 */
public record EngineConfig(
        Map<String, Object> params
) {

    /** 空配置单例：record 不可变、参数映射为不可变空集，可安全共享（避免热路径每次调用重复分配）。 */
    private static final EngineConfig EMPTY = new EngineConfig(Map.of());

    /**
     * 空引擎配置（无参数形态）。
     *
     * @return 共享的不可变空配置实例（多次调用返回同一实例）
     */
    public static EngineConfig empty() {
        return EMPTY;
    }
}
