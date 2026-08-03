package com.linkroa.deepdataagent.agent.domain.service;

import com.linkroa.deepdataagent.agent.domain.model.ChartConfig;
import com.linkroa.deepdataagent.agent.domain.model.DataAnalysisQuery;
import com.linkroa.deepdataagent.agent.domain.model.DataAnalysisResult;
import com.linkroa.deepdataagent.agent.domain.service.port.ChartConfigGenerator;
import com.linkroa.deepdataagent.agent.domain.service.port.LLMGenerationPort;
import com.linkroa.deepdataagent.agent.domain.support.DataSummaryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.ObjectUtils;

import java.util.List;
import java.util.Map;

/**
 * 数据分析领域服务
 * <p>编排核心流程：Text-to-SQL → 图表生成 → 分析报告。
 * 依赖领域层端口接口 {@link ChartConfigGenerator} 与 {@link LLMGenerationPort}，实现与具体实现的解耦。
 * 该领域服务由基础设施层 {@code DomainServiceConfig} 通过 {@code @Bean} 装配。</p>
 */
public class DataAnalysisDomainService {

    private static final Logger log = LoggerFactory.getLogger(DataAnalysisDomainService.class);

    private static final String EMPTY_RESULT_SUGGESTION =
            "查询结果为空。建议：1) 尝试缩小查询范围或更换筛选条件；"
            + "2) 检查数据源中是否存在目标数据；3) 确认自然语言问题是否准确表达了查询意图。";

    private final TextToSqlService textToSqlService;
    private final ChartConfigGenerator chartGenerator;
    private final LLMGenerationPort llmGenerationPort;
    private final DataSummaryBuilder dataSummaryBuilder;

    public DataAnalysisDomainService(
            TextToSqlService textToSqlService,
            ChartConfigGenerator chartGenerator,
            LLMGenerationPort llmGenerationPort,
            DataSummaryBuilder dataSummaryBuilder) {
        this.textToSqlService = textToSqlService;
        this.chartGenerator = chartGenerator;
        this.llmGenerationPort = llmGenerationPort;
        this.dataSummaryBuilder = dataSummaryBuilder;
    }

    /**
     * 生成 SQL 查询语句
     *
     * @param modelConfigId 模型配置 ID
     * @param userQuestion  用户问题
     * @param schemaInfo    数据库 schema 信息
     * @param sqlDialect    SQL 方言（MySQL / ClickHouse）
     * @return 生成的 SQL 语句
     */
    public String generateSql(Long modelConfigId, String userQuestion, String schemaInfo, String sqlDialect) {
        return textToSqlService.convert(modelConfigId, userQuestion, schemaInfo, sqlDialect);
    }

    /**
     * 执行数据分析（图表生成 + 分析报告）
     *
     * @param query      查询请求
     * @param schemaInfo schema 信息
     * @param sql        已生成的 SQL
     * @param queryData  查询结果（由应用层提供）
     * @return 分析结果
     */
    public DataAnalysisResult analyze(DataAnalysisQuery query, String schemaInfo, String sql, List<Map<String, Object>> queryData) {
        // 1. 图表配置
        ChartConfig chart = chartGenerator.generate(query.modelConfigId(), queryData, query.userQuestion());

        // 2. 分析报告
        String analysis = generateAnalysis(query.modelConfigId(), query.userQuestion(), sql, queryData, chart);

        return new DataAnalysisResult(sql, queryData, chart, analysis);
    }

    private String generateAnalysis(Long modelConfigId, String question, String sql, List<Map<String, Object>> data, ChartConfig chart) {
        if (ObjectUtils.isEmpty(data)) {
            return EMPTY_RESULT_SUGGESTION;
        }

        try {
            String dataSummary = dataSummaryBuilder.build(data);
            return llmGenerationPort.generateAnalysis(modelConfigId, question, sql, dataSummary, chart.description());
        } catch (Exception e) {
            log.warn("分析报告生成失败: {}", e.getMessage());
            throw new RuntimeException("分析报告生成失败", e);
        }
    }
}
