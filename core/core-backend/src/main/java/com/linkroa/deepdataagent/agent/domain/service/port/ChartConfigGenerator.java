package com.linkroa.deepdataagent.agent.domain.service.port;

import com.linkroa.deepdataagent.agent.domain.model.ChartConfig;

import java.util.List;
import java.util.Map;

/**
 * 图表配置生成端口
 * <p>领域层端口接口，用于抽象图表配置生成能力，由基础设施层实现，
 * 实现领域层与具体技术实现（LLM、ECharts）的解耦。</p>
 */
public interface ChartConfigGenerator {

    /**
     * 根据查询结果生成图表配置
     *
     * @param modelConfigId 模型配置 ID
     * @param queryResult   查询结果数据
     * @param userQuestion  用户问题
     * @return 图表配置
     */
    ChartConfig generate(Long modelConfigId, List<Map<String, Object>> queryResult, String userQuestion);
}