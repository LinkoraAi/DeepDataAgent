package com.linkroa.deepdataagent.knowledgebase.domain.model;

import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ParseEngineProvider;

/**
 * 文档解析引擎配置值对象。
 *
 * @param provider     解析提供方（LOCAL / MINERU）
 * @param engineConfig 解析引擎配置参数
 */
public record DocumentParseConfig(
        ParseEngineProvider provider,
        EngineConfig engineConfig
) {
}
