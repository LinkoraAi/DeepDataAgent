package com.linkroa.deepdataagent.agent.infrastructure.chart;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.linkroa.deepdataagent.agent.domain.model.ChartConfig;
import com.linkroa.deepdataagent.agent.domain.model.ChartType;
import com.linkroa.deepdataagent.agent.domain.service.port.ChartConfigGenerator;
import com.linkroa.deepdataagent.agent.infrastructure.client.LLMClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import java.util.List;
import java.util.Map;

/**
 * ECharts 配置生成器
 * <p>根据查询结果生成 ECharts 图表配置 JSON。</p>
 * <p>实现领域层 {@link ChartConfigGenerator} 端口接口。</p>
 */
@Component
public class EChartsConfigGenerator implements ChartConfigGenerator {

    private static final Logger log = LoggerFactory.getLogger(EChartsConfigGenerator.class);

    private final LLMClient llmClient;
    private final ObjectMapper objectMapper;

    /**
     * 构造方法
     *
     * @param llmClient LLM 客户端
     */
    public EChartsConfigGenerator(LLMClient llmClient) {
        this.llmClient = llmClient;
        this.objectMapper = JsonMapper.builder().build();
    }

    /**
     * 根据查询结果生成图表配置
     *
     * @param modelConfigId 模型配置 ID
     * @param queryResult   查询结果数据
     * @param userQuestion  用户问题
     * @return 图表配置
     */
    @Override
    public ChartConfig generate(Long modelConfigId, List<Map<String, Object>> queryResult, String userQuestion) {
        if (ObjectUtils.isEmpty(queryResult)) {
            return ChartConfig.empty();
        }

        try {
            String dataDescription = buildDataDescription(queryResult);
            String echartsJson = llmClient.generateChartConfig(modelConfigId, dataDescription, userQuestion);
            ChartType type = inferType(echartsJson);

            return new ChartConfig(
                    type,
                    echartsJson,
                    userQuestion.length() > 30 ? userQuestion.substring(0, 27) + "..." : userQuestion,
                    "自动生成的 " + type.getDisplayName()
            );
        } catch (Exception e) {
            log.warn("图表配置生成失败: {}", e.getMessage());
            return ChartConfig.empty();
        }
    }

    /**
     * 构建数据描述
     *
     * @param queryResult 查询结果数据
     * @return 数据描述文本
     */
    private String buildDataDescription(List<Map<String, Object>> queryResult) {
        try {
            StringBuilder sb = new StringBuilder();

            Map<String, Object> firstRow = queryResult.get(0);
            sb.append("字段信息：\n");
            for (Map.Entry<String, Object> entry : firstRow.entrySet()) {
                sb.append(String.format("  - %s: %s\n", entry.getKey(),
                        entry.getValue() != null ? entry.getValue().getClass().getSimpleName() : "unknown"));
            }

            int maxRows = Math.min(queryResult.size(), 10);
            sb.append(String.format("\n数据（共 %d 行，展示前 %d 行）：\n", queryResult.size(), maxRows));
            String sampleJson = objectMapper.writeValueAsString(queryResult.subList(0, maxRows));
            sb.append(sampleJson);

            return sb.toString();
        } catch (Exception e) {
            log.warn("构建数据描述失败: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * 推断图表类型
     *
     * @param echartsJson ECharts 配置 JSON
     * @return 图表类型
     */
    private ChartType inferType(String echartsJson) {
        String json = echartsJson.toLowerCase();
        if (json.contains("\"line\"")) return ChartType.LINE;
        if (json.contains("\"bar\"")) return ChartType.BAR;
        if (json.contains("\"pie\"")) return ChartType.PIE;
        if (json.contains("\"scatter\"")) return ChartType.SCATTER;
        return ChartType.TABLE;
    }
}